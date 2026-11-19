package dev.chainbank.api.auth;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import dev.chainbank.api.platform.AppClock;
import dev.chainbank.api.platform.ChainbankConfig;
import dev.chainbank.api.platform.Db;

/**
 * Server-side sessions. The browser holds a random opaque token; the database stores only its
 * SHA-256, so a database leak does not yield usable session cookies.
 */
@ApplicationScoped
public class SessionService {

    public static final String COOKIE = "cb_session";

    @Inject
    Db db;

    @Inject
    AppClock clock;

    @Inject
    ChainbankConfig config;

    public record NewSession(UUID sessionId, String token, String csrfToken, Instant expiresAt) {
    }

    public record ActiveSession(UUID sessionId, UUID userId, String email, String displayName, UUID institutionId,
            Set<String> roles, Instant stepUpAt, Instant expiresAt) {

        public boolean stepUpValid(Instant now, java.time.Duration validity) {
            return stepUpAt != null && stepUpAt.plus(validity).isAfter(now);
        }
    }

    /**
     * The CSRF token is derived from the session token, so the server can return it to the page
     * without storing it in plaintext. Only a holder of the HttpOnly cookie can learn it via /me.
     */
    public static String csrfTokenFor(String sessionToken) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(Crypto.sha256("csrf:" + sessionToken));
    }

    public NewSession create(Connection c, UUID userId) throws SQLException {
        String token = Crypto.randomToken();
        String csrf = csrfTokenFor(token);
        Instant now = clock.now();
        Instant expires = now.plus(config.session().absoluteTimeout());
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO sessions (id, user_id, token_hash, created_at, last_seen_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)""")) {
            ps.setObject(1, id);
            ps.setObject(2, userId);
            ps.setBytes(3, Crypto.sha256(token));
            ps.setTimestamp(4, Timestamp.from(now));
            ps.setTimestamp(5, Timestamp.from(now));
            ps.setTimestamp(6, Timestamp.from(expires));
            ps.executeUpdate();
        }
        return new NewSession(id, token, csrf, expires);
    }

    /**
     * Resolves a cookie token to an active session for an ACTIVE user, enforcing absolute and idle
     * expiry, and slides the idle window. Returns empty for anything invalid.
     */
    public Optional<ActiveSession> resolve(String token) {
        if (token == null || token.length() < 32 || token.length() > 64) {
            return Optional.empty();
        }
        Instant now = clock.now();
        return db.tx(c -> {
            ActiveSession s;
            Instant lastSeen;
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT s.id, s.user_id, u.email, u.display_name, u.institution_id,
                           s.step_up_at, s.expires_at, s.last_seen_at
                    FROM sessions s JOIN users u ON u.id = s.user_id
                    WHERE s.token_hash = ? AND s.revoked_at IS NULL AND u.status = 'ACTIVE'""")) {
                ps.setBytes(1, Crypto.sha256(token));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.<ActiveSession>empty();
                    }
                    UUID userId = rs.getObject(2, UUID.class);
                    Timestamp stepUp = rs.getTimestamp(6);
                    s = new ActiveSession(rs.getObject(1, UUID.class), userId, rs.getString(3), rs.getString(4),
                            rs.getObject(5, UUID.class), roles(c, userId),
                            stepUp == null ? null : stepUp.toInstant(), rs.getTimestamp(7).toInstant());
                    lastSeen = rs.getTimestamp(8).toInstant();
                }
            }
            if (!now.isBefore(s.expiresAt()) || !now.isBefore(lastSeen.plus(config.session().idleTimeout()))) {
                return Optional.<ActiveSession>empty();
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE sessions SET last_seen_at = ? WHERE id = ?")) {
                ps.setTimestamp(1, Timestamp.from(now));
                ps.setObject(2, s.sessionId());
                ps.executeUpdate();
            }
            return Optional.of(s);
        });
    }

    public void markStepUp(Connection c, UUID sessionId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE sessions SET step_up_at = ? WHERE id = ?")) {
            ps.setTimestamp(1, Timestamp.from(clock.now()));
            ps.setObject(2, sessionId);
            ps.executeUpdate();
        }
    }

    public void revoke(Connection c, UUID sessionId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE sessions SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL")) {
            ps.setTimestamp(1, Timestamp.from(clock.now()));
            ps.setObject(2, sessionId);
            ps.executeUpdate();
        }
    }

    public int revokeAllForUser(Connection c, UUID userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE sessions SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL")) {
            ps.setTimestamp(1, Timestamp.from(clock.now()));
            ps.setObject(2, userId);
            return ps.executeUpdate();
        }
    }

    static Set<String> roles(Connection c, UUID userId) throws SQLException {
        Set<String> roles = new HashSet<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT role FROM user_roles WHERE user_id = ?")) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    roles.add(rs.getString(1));
                }
            }
        }
        return roles;
    }
}
