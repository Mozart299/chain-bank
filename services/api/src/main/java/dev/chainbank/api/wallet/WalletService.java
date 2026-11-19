package dev.chainbank.api.wallet;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import dev.chainbank.api.audit.AuditLog;
import dev.chainbank.api.auth.Crypto;
import dev.chainbank.api.auth.SessionService;
import dev.chainbank.api.platform.ApiException;
import dev.chainbank.api.platform.AppClock;
import dev.chainbank.api.platform.ChainbankConfig;
import dev.chainbank.api.platform.Db;

/**
 * Wallet linking via signed one-time challenges (spec section 7.2). A wallet signature proves control of
 * an address; it never authenticates a user by itself. Every call here already runs as an
 * authenticated, CSRF-checked customer session.
 */
@ApplicationScoped
public class WalletService {

    public static final String PURPOSE_LINK = "LINK_WALLET";

    @Inject
    Db db;

    @Inject
    AppClock clock;

    @Inject
    ChainbankConfig config;

    @Inject
    AuditLog audit;

    public record Challenge(UUID challengeId, String message, Instant expiresAt) {
    }

    public record Link(UUID id, String address, long chainId, Instant verifiedAt) {
    }

    record ConsumedChallenge(String address, long chainId, String message, Instant expiresAt) {
    }

    public Challenge createChallenge(SessionService.ActiveSession session, String rawAddress, long chainId,
            boolean stepUpValid) {
        if (!EthSignatures.isAddress(rawAddress)) {
            throw ApiException.badRequest("INVALID_ADDRESS", "Not a valid Ethereum address");
        }
        if (!config.wallet().allowedChainIds().contains(chainId)) {
            throw ApiException.badRequest("UNSUPPORTED_CHAIN", "Chain " + chainId + " is not supported");
        }
        String address = rawAddress.toLowerCase();
        UUID id = UUID.randomUUID();
        String nonce = Crypto.hex(Crypto.randomBytes(16));
        Instant issued = clock.now();
        Instant expires = issued.plus(config.wallet().challengeTtl());
        String message = SiweMessage.build(config.wallet().domain(), address, SiweMessage.statementFor(PURPOSE_LINK),
                config.wallet().uri(), chainId, nonce, issued, expires, id);
        db.tx(c -> {
            if (activeLinkOwner(c, chainId, address).filter(owner -> owner.equals(session.userId())).isPresent()) {
                throw ApiException.conflict("ALREADY_LINKED", "This wallet is already linked to your account");
            }
            // Re-linking (adding another wallet once one is active) is a sensitive change: require step-up.
            if (hasActiveLink(c, session.userId()) && !stepUpValid) {
                throw ApiException.forbidden("STEP_UP_REQUIRED", "Re-verify with your authenticator code first");
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO wallet_challenges (id, user_id, nonce_hash, purpose, address, chain_id, message,
                                                   issued_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
                ps.setObject(1, id);
                ps.setObject(2, session.userId());
                ps.setBytes(3, Crypto.sha256(nonce));
                ps.setString(4, PURPOSE_LINK);
                ps.setString(5, address);
                ps.setLong(6, chainId);
                ps.setString(7, message);
                ps.setTimestamp(8, Timestamp.from(issued));
                ps.setTimestamp(9, Timestamp.from(expires));
                ps.executeUpdate();
            }
            return null;
        });
        return new Challenge(id, message, expires);
    }

    /**
     * Consumes the challenge first, in its own committed transaction, so every challenge is
     * single-use even when verification then fails. Only one of several concurrent verify calls can
     * win the conditional UPDATE.
     */
    public Link verify(SessionService.ActiveSession session, UUID challengeId, String signature) {
        Instant now = clock.now();
        ConsumedChallenge ch = db.tx(c -> consume(c, challengeId, session.userId(), now))
                .orElseThrow(() -> ApiException.notFound("Challenge not found or already used"));

        if (!now.isBefore(ch.expiresAt())) {
            auditFailure(session.userId(), challengeId, "EXPIRED");
            throw new ApiException(410, "CHALLENGE_EXPIRED", "Challenge expired; request a new one");
        }
        Optional<String> signer = EthSignatures.recoverPersonalSigner(ch.message(), signature);
        if (signer.isEmpty() || !signer.get().equals(ch.address())) {
            auditFailure(session.userId(), challengeId, "SIGNATURE_MISMATCH");
            throw ApiException.badRequest("SIGNATURE_INVALID", "Signature does not match the challenged address");
        }
        try {
            return db.tx(c -> {
                UUID linkId = UUID.randomUUID();
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO wallet_links (id, user_id, address, chain_id, verified_at) VALUES (?, ?, ?, ?, ?)""")) {
                    ps.setObject(1, linkId);
                    ps.setObject(2, session.userId());
                    ps.setString(3, ch.address());
                    ps.setLong(4, ch.chainId());
                    ps.setTimestamp(5, Timestamp.from(now));
                    ps.executeUpdate();
                }
                audit.record(c, session.userId(), "WALLET_LINKED", "WALLET_LINK", linkId.toString(),
                        Map.of("address", ch.address(), "chainId", Long.toString(ch.chainId()),
                                "challengeId", challengeId.toString()));
                return new Link(linkId, ch.address(), ch.chainId(), now);
            });
        } catch (RuntimeException e) {
            if (Db.isUniqueViolation(e)) {
                auditFailure(session.userId(), challengeId, "ADDRESS_TAKEN");
                throw ApiException.conflict("ADDRESS_TAKEN", "This wallet is already linked to an account");
            }
            throw e;
        }
    }

    public List<Link> list(UUID userId) {
        return db.tx(c -> {
            List<Link> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT id, address, chain_id, verified_at FROM wallet_links
                    WHERE user_id = ? AND revoked_at IS NULL ORDER BY verified_at""")) {
                ps.setObject(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Link(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3),
                                rs.getTimestamp(4).toInstant()));
                    }
                }
            }
            return out;
        });
    }

    /** Ownership is part of the WHERE clause: another user's link id behaves exactly like a missing one. */
    public void unlink(UUID userId, UUID linkId) {
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE wallet_links SET revoked_at = ? WHERE id = ? AND user_id = ? AND revoked_at IS NULL""")) {
                ps.setTimestamp(1, Timestamp.from(clock.now()));
                ps.setObject(2, linkId);
                ps.setObject(3, userId);
                if (ps.executeUpdate() != 1) {
                    throw ApiException.notFound("Wallet link not found");
                }
            }
            audit.record(c, userId, "WALLET_UNLINKED", "WALLET_LINK", linkId.toString(), Map.of());
            return null;
        });
    }

    private Optional<ConsumedChallenge> consume(Connection c, UUID id, UUID userId, Instant now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE wallet_challenges SET consumed_at = ?
                WHERE id = ? AND user_id = ? AND purpose = ? AND consumed_at IS NULL
                RETURNING address, chain_id, message, expires_at""")) {
            ps.setTimestamp(1, Timestamp.from(now));
            ps.setObject(2, id);
            ps.setObject(3, userId);
            ps.setString(4, PURPOSE_LINK);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ConsumedChallenge(rs.getString(1), rs.getLong(2), rs.getString(3),
                        rs.getTimestamp(4).toInstant()));
            }
        }
    }

    private static Optional<UUID> activeLinkOwner(Connection c, long chainId, String address) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT user_id FROM wallet_links WHERE chain_id = ? AND address = ? AND revoked_at IS NULL")) {
            ps.setLong(1, chainId);
            ps.setString(2, address);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getObject(1, UUID.class)) : Optional.empty();
            }
        }
    }

    private static boolean hasActiveLink(Connection c, UUID userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM wallet_links WHERE user_id = ? AND revoked_at IS NULL LIMIT 1")) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void auditFailure(UUID userId, UUID challengeId, String reason) {
        audit.recordStandalone(userId, "WALLET_LINK_FAILED", "WALLET_CHALLENGE", challengeId.toString(),
                Map.of("reason", reason));
    }
}
