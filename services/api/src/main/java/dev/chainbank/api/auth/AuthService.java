package dev.chainbank.api.auth;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import dev.chainbank.api.audit.AuditLog;
import dev.chainbank.api.platform.ApiException;
import dev.chainbank.api.platform.AppClock;
import dev.chainbank.api.platform.ChainbankConfig;
import dev.chainbank.api.platform.Db;

/** Registration, TOTP enrollment, login, step-up and logout. */
@ApplicationScoped
public class AuthService {

    public static final String ISSUER = "ChainBank";

    @Inject
    Db db;

    @Inject
    PasswordHasher passwords;

    @Inject
    SecretCipher cipher;

    @Inject
    SessionService sessions;

    @Inject
    RateLimiter limiter;

    @Inject
    AuditLog audit;

    @Inject
    AppClock clock;

    @Inject
    ChainbankConfig config;

    public record Enrollment(UUID userId, String otpauthUri, String secret) {
    }

    record UserRow(UUID id, String passwordHash, byte[] totpCipher, String status, long totpLastStep) {
    }

    private static ApiException invalidCredentials() {
        // Deliberately identical for unknown email, wrong password, wrong code, and inactive account.
        return new ApiException(401, "INVALID_CREDENTIALS", "Invalid email, password or code");
    }

    public Enrollment register(String email, String password, String displayName, String institutionCode) {
        UUID userId = UUID.randomUUID();
        byte[] secret = Totp.newSecret();
        try {
            db.tx(c -> {
                UUID institution = institutionId(c, institutionCode);
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO users (id, institution_id, email, display_name, password_hash, totp_secret_cipher, status)
                        VALUES (?, ?, ?, ?, ?, ?, 'PENDING')""")) {
                    ps.setObject(1, userId);
                    ps.setObject(2, institution);
                    ps.setString(3, email.trim());
                    ps.setString(4, displayName.trim());
                    ps.setString(5, passwords.hash(password));
                    ps.setBytes(6, cipher.encrypt(secret, userId));
                    ps.executeUpdate();
                }
                audit.record(c, userId, "USER_REGISTERED", "USER", userId.toString(),
                        Map.of("institution", institutionCode));
                return null;
            });
        } catch (RuntimeException e) {
            if (Db.isUniqueViolation(e)) {
                throw ApiException.conflict("EMAIL_TAKEN", "An account with this email already exists");
            }
            throw e;
        }
        return new Enrollment(userId, Totp.otpauthUri(ISSUER, email.trim(), secret), Totp.base32Encode(secret));
    }

    /** Proves the user saved the TOTP secret, then activates the account as a CUSTOMER. */
    public void confirmEnrollment(String email, String password, String code, String clientIp) {
        String accountKey = "confirm:" + email.toLowerCase();
        guard(accountKey, clientIp);
        boolean ok = db.tx(c -> {
            Optional<UserRow> u = findByEmail(c, email);
            if (u.isEmpty() || !"PENDING".equals(u.get().status())) {
                passwords.verifyAgainstDummy(password);
                return false;
            }
            UserRow user = u.get();
            if (!passwords.verify(password, user.passwordHash()) || !consumeTotp(c, user, code)) {
                return false;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE users SET status = 'ACTIVE', totp_enabled_at = now() WHERE id = ?")) {
                ps.setObject(1, user.id());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO user_roles (user_id, role) VALUES (?, 'CUSTOMER') ON CONFLICT DO NOTHING")) {
                ps.setObject(1, user.id());
                ps.executeUpdate();
            }
            audit.record(c, user.id(), "USER_ACTIVATED", "USER", user.id().toString(), Map.of("mfa", "TOTP"));
            return true;
        });
        if (!ok) {
            fail(accountKey, clientIp, "AUTH_ENROLLMENT_FAILED");
        }
        limiter.reset(accountKey);
    }

    public SessionService.NewSession login(String email, String password, String code, String clientIp) {
        String accountKey = "login:" + email.toLowerCase();
        guard(accountKey, clientIp);
        Optional<SessionService.NewSession> session = db.tx(c -> {
            Optional<UserRow> u = findByEmail(c, email);
            if (u.isEmpty()) {
                passwords.verifyAgainstDummy(password);
                return Optional.empty();
            }
            UserRow user = u.get();
            boolean passwordOk = passwords.verify(password, user.passwordHash());
            if (!passwordOk || !"ACTIVE".equals(user.status()) || !consumeTotp(c, user, code)) {
                return Optional.empty();
            }
            SessionService.NewSession s = sessions.create(c, user.id());
            audit.record(c, user.id(), "AUTH_LOGIN", "SESSION", s.sessionId().toString(), Map.of("mfa", "TOTP"));
            return Optional.of(s);
        });
        if (session.isEmpty()) {
            fail(accountKey, clientIp, "AUTH_LOGIN_FAILED");
        }
        limiter.reset(accountKey);
        return session.get();
    }

    public void stepUp(SessionService.ActiveSession session, String code, String clientIp) {
        String accountKey = "stepup:" + session.userId();
        guard(accountKey, clientIp);
        boolean ok = db.tx(c -> {
            UserRow user = findById(c, session.userId()).orElseThrow(ApiException::unauthorized);
            if (!consumeTotp(c, user, code)) {
                return false;
            }
            sessions.markStepUp(c, session.sessionId());
            audit.record(c, user.id(), "AUTH_STEP_UP", "SESSION", session.sessionId().toString(), Map.of());
            return true;
        });
        if (!ok) {
            fail(accountKey, clientIp, "AUTH_STEP_UP_FAILED");
        }
        limiter.reset(accountKey);
    }

    public void logout(SessionService.ActiveSession session) {
        db.tx(c -> {
            sessions.revoke(c, session.sessionId());
            audit.record(c, session.userId(), "AUTH_LOGOUT", "SESSION", session.sessionId().toString(), Map.of());
            return null;
        });
    }

    public int revokeAllSessions(SessionService.ActiveSession session) {
        return db.tx(c -> {
            int n = sessions.revokeAllForUser(c, session.userId());
            audit.record(c, session.userId(), "AUTH_SESSIONS_REVOKED", "USER", session.userId().toString(),
                    Map.of("count", Integer.toString(n)));
            return n;
        });
    }

    // --- internals -------------------------------------------------------------

    private void guard(String accountKey, String clientIp) {
        limiter.check(accountKey, config.rateLimit().perAccount(), config.rateLimit().window());
        limiter.check("ip:" + clientIp, config.rateLimit().perIp(), config.rateLimit().window());
    }

    private void fail(String accountKey, String clientIp, String auditAction) {
        limiter.recordFailure(accountKey, config.rateLimit().window());
        limiter.recordFailure("ip:" + clientIp, config.rateLimit().window());
        audit.recordStandalone(null, auditAction, "AUTH", null, Map.of("reason", "INVALID_CREDENTIALS"));
        throw invalidCredentials();
    }

    /**
     * Verifies the code and atomically records its time step. The conditional UPDATE means two
     * concurrent requests with the same code cannot both succeed.
     */
    private boolean consumeTotp(Connection c, UserRow user, String code) throws SQLException {
        if (user.totpCipher() == null) {
            return false;
        }
        byte[] secret = cipher.decrypt(user.totpCipher(), user.id());
        long step = Totp.verify(secret, code, clock.now(), user.totpLastStep());
        if (step < 0) {
            return false;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE users SET totp_last_step = ? WHERE id = ? AND totp_last_step < ?")) {
            ps.setLong(1, step);
            ps.setObject(2, user.id());
            ps.setLong(3, step);
            return ps.executeUpdate() == 1;
        }
    }

    private static UUID institutionId(Connection c, String code) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM institutions WHERE code = ? AND status = 'ACTIVE'")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw ApiException.badRequest("UNKNOWN_INSTITUTION", "Unknown institution");
                }
                return rs.getObject(1, UUID.class);
            }
        }
    }

    private static Optional<UserRow> findByEmail(Connection c, String email) throws SQLException {
        return findOne(c, "lower(email) = lower(?)", email.trim());
    }

    private static Optional<UserRow> findById(Connection c, UUID id) throws SQLException {
        return findOne(c, "id = ?", id);
    }

    private static Optional<UserRow> findOne(Connection c, String where, Object arg) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, password_hash, totp_secret_cipher, status, totp_last_step FROM users WHERE " + where
                        + " FOR UPDATE")) {
            ps.setObject(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new UserRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getBytes(3),
                        rs.getString(4), rs.getLong(5)));
            }
        }
    }
}
