package dev.chainbank.api.audit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.MDC;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.chainbank.api.auth.Crypto;
import dev.chainbank.api.platform.AppClock;
import dev.chainbank.api.platform.CorrelationIdFilter;
import dev.chainbank.api.platform.Db;

/**
 * Append-only, hash-chained security audit log. Each event's hash covers the previous event's
 * hash, so altering or deleting any past event breaks every hash after it.
 *
 * <p>Never put secrets, TOTP codes, passwords, session tokens or full signatures in details.
 */
@ApplicationScoped
public class AuditLog {

    /** Arbitrary constant key for pg_advisory_xact_lock; serialises appends so the chain stays linear. */
    private static final long CHAIN_LOCK = 0x43_42_41_55_44_49_54L; // "CBAUDIT"
    private static final TypeReference<TreeMap<String, String>> DETAILS = new TypeReference<>() {
    };

    @Inject
    Db db;

    @Inject
    AppClock clock;

    @Inject
    ObjectMapper mapper;

    public record Verification(boolean valid, long eventsChecked, Long firstInvalidEventId) {
    }

    /** Appends within the caller's transaction, so the event commits or rolls back with the action. */
    public void record(Connection c, UUID actor, String action, String resourceType, String resourceId,
            Map<String, String> details) throws SQLException {
        try (PreparedStatement lock = c.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
            lock.setLong(1, CHAIN_LOCK);
            lock.execute();
        }
        byte[] prev = null;
        try (PreparedStatement ps = c.prepareStatement("SELECT hash FROM audit_events ORDER BY id DESC LIMIT 1");
                ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                prev = rs.getBytes(1);
            }
        }
        Instant at = clock.now();
        Object cidObj = MDC.get(CorrelationIdFilter.MDC_KEY);
        String cid = cidObj == null ? null : cidObj.toString();
        String detailsJson = canonical(details == null ? Map.of() : details);
        byte[] hash = hash(prev, at, actor, action, resourceType, resourceId, cid, detailsJson);
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO audit_events (occurred_at, actor_user_id, action, resource_type, resource_id,
                                          correlation_id, details, prev_hash, hash)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)""")) {
            ps.setTimestamp(1, Timestamp.from(at));
            ps.setObject(2, actor);
            ps.setString(3, action);
            ps.setString(4, resourceType);
            ps.setString(5, resourceId);
            ps.setString(6, cid);
            ps.setString(7, detailsJson);
            ps.setBytes(8, prev);
            ps.setBytes(9, hash);
            ps.executeUpdate();
        }
    }

    /** For events that must persist even when the surrounding action fails (e.g. failed logins). */
    public void recordStandalone(UUID actor, String action, String resourceType, String resourceId,
            Map<String, String> details) {
        db.tx(c -> {
            record(c, actor, action, resourceType, resourceId, details);
            return null;
        });
    }

    /** Recomputes every hash from the start of the chain. */
    public Verification verifyChain() {
        return db.tx(c -> {
            byte[] prev = null;
            long n = 0;
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT id, occurred_at, actor_user_id, action, resource_type, resource_id, correlation_id,
                           details::text, prev_hash, hash
                    FROM audit_events ORDER BY id""");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    n++;
                    long id = rs.getLong(1);
                    byte[] storedPrev = rs.getBytes(9);
                    byte[] expected = hash(prev, rs.getTimestamp(2).toInstant(), rs.getObject(3, UUID.class),
                            rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                            canonical(mapper.readValue(rs.getString(8), DETAILS)));
                    boolean linkOk = prev == null ? storedPrev == null : storedPrev != null && Crypto.constantTimeEquals(prev, storedPrev);
                    if (!linkOk || !Crypto.constantTimeEquals(expected, rs.getBytes(10))) {
                        return new Verification(false, n, id);
                    }
                    prev = rs.getBytes(10);
                }
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
            return new Verification(true, n, null);
        });
    }

    private String canonical(Map<String, String> details) {
        try {
            return mapper.writeValueAsString(new TreeMap<>(details));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hash(byte[] prev, Instant at, UUID actor, String action, String resourceType,
            String resourceId, String cid, String detailsJson) {
        // Length-prefixed fields so no two different events can serialise to the same bytes.
        StringBuilder sb = new StringBuilder();
        for (String f : new String[] { prev == null ? "" : Crypto.hex(prev), at.toString(),
                actor == null ? "" : actor.toString(), action, resourceType, resourceId == null ? "" : resourceId,
                cid == null ? "" : cid, detailsJson }) {
            sb.append(f.length()).append(':').append(f).append('|');
        }
        return Crypto.sha256(sb.toString());
    }
}
