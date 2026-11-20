package dev.chainbank.api.chain;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.methods.response.Log;

import dev.chainbank.api.platform.AppClock;
import dev.chainbank.api.platform.ChainbankConfig;
import dev.chainbank.api.platform.Db;
import dev.chainbank.api.transfer.TransferService;

/**
 * Indexes finalized {@code Settled} events and reconciles them with the journal (spec section 5,
 * use case 5). Anything that does not match becomes a reconciliation exception for an operator.
 */
@ApplicationScoped
public class SettlementReconciler {

    @Inject
    Db db;

    @Inject
    ChainClient chain;

    @Inject
    SettlementDeployment deployment;

    @Inject
    ChainbankConfig config;

    @Inject
    AppClock clock;

    @Inject
    dev.chainbank.api.audit.AuditLog audit;

    public record Position(String institution, String pseudonym, String wallet, long offChainLedger,
            long outgoingUnsettled, long incomingUnsettled, long expectedOnChain, Long onChain, boolean matched,
            long asOfBlock) {
    }

    public record ReconciliationException(UUID id, UUID transferId, Long chainEventId, String kind, String status,
            String note, Instant createdAt, Instant resolvedAt, String resolutionNote, int retryCount, boolean retryable) {
    }

    /** Reads Settled logs from the last indexed block up to the newest final block. */
    public int index() {
        Optional<String> contract = deployment.address();
        if (contract.isEmpty()) {
            return 0;
        }
        long finalBlock = chain.latestBlock() - (config.chain().confirmations() - 1);
        long from = cursor() + 1;
        if (finalBlock < from) {
            return 0;
        }
        List<Log> logs = chain.logs(contract.get(), from, finalBlock, SettlementAbi.SETTLED_TOPIC);
        return db.tx(c -> {
            int n = 0;
            for (Log log : logs) {
                List<String> t = log.getTopics();
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO chain_events (chain_id, block_number, block_hash, tx_hash, log_index, settlement_id,
                                                  from_pseudonym, to_pseudonym, amount)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (tx_hash, log_index) DO NOTHING""")) {
                    ps.setLong(1, chain.chainId());
                    ps.setLong(2, log.getBlockNumber().longValue());
                    ps.setString(3, log.getBlockHash());
                    ps.setString(4, log.getTransactionHash().toLowerCase());
                    ps.setInt(5, log.getLogIndex().intValue());
                    ps.setString(6, t.get(1).toLowerCase());
                    ps.setString(7, SettlementAbi.pseudonymFromTopic(t.get(2)));
                    ps.setString(8, SettlementAbi.pseudonymFromTopic(t.get(3)));
                    ps.setBigDecimal(9, new java.math.BigDecimal(SettlementAbi.decodeSettledAmount(log.getData())));
                    n += ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO chain_cursor (chain_id, last_indexed_block) VALUES (?, ?)
                    ON CONFLICT (chain_id) DO UPDATE SET last_indexed_block = EXCLUDED.last_indexed_block""")) {
                ps.setLong(1, chain.chainId());
                ps.setLong(2, finalBlock);
                ps.executeUpdate();
            }
            return n;
        });
    }

    /** Matches indexed events to transfers: CONFIRMED + matching event becomes RECONCILED. */
    public int reconcile() {
        Instant now = clock.now();
        List<Long> pending = db.tx(c -> {
            List<Long> ids = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id FROM chain_events WHERE reconciled_at IS NULL AND chain_id = ? ORDER BY id")) {
                ps.setLong(1, chain.chainId());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getLong(1));
                    }
                }
            }
            return ids;
        });
        int reconciled = 0;
        for (long eventId : pending) {
            if (db.tx(c -> reconcileEvent(c, eventId, now))) {
                reconciled++;
            }
        }
        return reconciled;
    }

    private boolean reconcileEvent(Connection c, long eventId, Instant now) throws SQLException {
        String settlementId, from, to;
        BigInteger amount;
        long block;
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT settlement_id, from_pseudonym, to_pseudonym, amount, block_number FROM chain_events
                WHERE id = ? AND reconciled_at IS NULL FOR UPDATE""")) {
            ps.setLong(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                settlementId = rs.getString(1);
                from = rs.getString(2);
                to = rs.getString(3);
                amount = rs.getBigDecimal(4).toBigIntegerExact();
                block = rs.getLong(5);
            }
        }
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT t.id, t.state, t.amount_minor, si.pseudonym, di.pseudonym
                FROM transfers t
                JOIN institutions si ON si.id = t.source_institution_id
                JOIN institutions di ON di.id = t.destination_institution_id
                WHERE t.settlement_id = ? FOR UPDATE OF t""")) {
            ps.setString(1, settlementId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    raise(c, null, eventId, "MISSING_IN_JOURNAL",
                            "On-chain settlement " + settlementId + " (" + amount + " " + from + "→" + to
                                    + ") has no matching transfer",
                            now);
                    markEvent(c, eventId, now);
                    return false;
                }
                UUID transferId = rs.getObject(1, UUID.class);
                String state = rs.getString(2);
                boolean matches = BigInteger.valueOf(rs.getLong(3)).equals(amount) && rs.getString(4).equals(from)
                        && rs.getString(5).equals(to);
                if (!matches) {
                    raise(c, transferId, eventId, "AMOUNT_MISMATCH", "Event " + amount + " " + from + "→" + to
                            + " does not match the journal (" + rs.getLong(3) + " " + rs.getString(4) + "→"
                            + rs.getString(5) + ")", now);
                    markEvent(c, eventId, now);
                    return false;
                }
                if (!"CONFIRMED".equals(state)) {
                    return false; // not final from the gateway's view yet; try again next cycle
                }
                TransferService.transition(c, transferId, "CONFIRMED", "RECONCILED",
                        "Matched Settled event in block " + block, now);
                markEvent(c, eventId, now);
                return true;
            }
        }
    }

    /** Off-chain settlement ledgers vs on-chain balances, both as of the last indexed (final) block. */
    public List<Position> positions() {
        String contract = deployment.address().orElse(null);
        long asOf = cursor();
        List<Position> out = new ArrayList<>();
        for (SettlementDeployment.Institution i : deployment.institutions()) {
            long[] off = db.tx(c -> {
                try (PreparedStatement ps = c.prepareStatement("""
                        SELECT coalesce(b.ledger_minor, 0),
                          coalesce((SELECT sum(t.amount_minor) FROM transfers t
                                    WHERE t.source_institution_id = inst.id
                                      AND t.source_institution_id <> t.destination_institution_id
                                      AND t.state IN ('APPROVED', 'SUBMITTED', 'CONFIRMED', 'RECONCILED', 'PENDING_INVESTIGATION')
                                      AND NOT EXISTS (SELECT 1 FROM chain_events e WHERE e.settlement_id = t.settlement_id
                                                      AND e.block_number <= ?)), 0),
                          coalesce((SELECT sum(t.amount_minor) FROM transfers t
                                    WHERE t.destination_institution_id = inst.id
                                      AND t.source_institution_id <> t.destination_institution_id
                                      AND t.state IN ('APPROVED', 'SUBMITTED', 'CONFIRMED', 'RECONCILED', 'PENDING_INVESTIGATION')
                                      AND NOT EXISTS (SELECT 1 FROM chain_events e WHERE e.settlement_id = t.settlement_id
                                                      AND e.block_number <= ?)), 0)
                        FROM institutions inst
                        JOIN accounts a ON a.institution_id = inst.id AND a.account_type = 'SETTLEMENT'
                        LEFT JOIN account_balances b ON b.account_id = a.id
                        WHERE inst.code = ?""")) {
                    ps.setLong(1, asOf);
                    ps.setLong(2, asOf);
                    ps.setString(3, i.code());
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        return new long[] { rs.getLong(1), rs.getLong(2), rs.getLong(3) };
                    }
                }
            });
            long expected = off[0] + off[1] - off[2];
            Long onChain = null;
            if (contract != null) {
                try {
                    onChain = SettlementAbi.decodeUint(chain.call(contract, SettlementAbi.balanceOfCall(i.wallet()),
                            DefaultBlockParameter.valueOf(BigInteger.valueOf(asOf)))).longValueExact();
                } catch (ChainClient.ChainException e) {
                    onChain = null;
                }
            }
            out.add(new Position(i.code(), i.pseudonym(), i.wallet(), off[0], off[1], off[2], expected, onChain,
                    onChain != null && onChain == expected, asOf));
        }
        return out;
    }

    public List<ReconciliationException> exceptions(boolean openOnly) {
        return db.tx(c -> {
            List<ReconciliationException> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT id, transfer_id, chain_event_id, kind, status, note, created_at, resolved_at,
                           resolution_note, retry_count
                    FROM reconciliation_exceptions""" + (openOnly ? " WHERE status <> 'RESOLVED'" : "")
                    + " ORDER BY created_at DESC LIMIT 200")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(mapException(rs));
                    }
                }
            }
            return out;
        });
    }

    private static ReconciliationException mapException(ResultSet rs) throws SQLException {
        Timestamp resolved = rs.getTimestamp(8);
        long ev = rs.getLong(3);
        Long eventId = rs.wasNull() ? null : ev;
        String kind = rs.getString(4);
        String status = rs.getString(5);
        UUID transferId = rs.getObject(2, UUID.class);
        boolean retryable = transferId != null && !"RESOLVED".equals(status)
                && ("MISSING_ON_CHAIN".equals(kind) || "REVERTED".equals(kind));
        return new ReconciliationException(rs.getObject(1, UUID.class), transferId, eventId, kind, status,
                rs.getString(6), rs.getTimestamp(7).toInstant(), resolved == null ? null : resolved.toInstant(),
                rs.getString(9), rs.getInt(10), retryable);
    }

    /** An operator closes an exception after investigating it; the note is mandatory and audited. */
    public ReconciliationException resolveManually(UUID exceptionId, UUID operator, String note) {
        Instant now = clock.now();
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE reconciliation_exceptions SET status = 'RESOLVED', resolved_at = ?, resolved_by = ?,
                           resolution_note = ?
                    WHERE id = ? AND status <> 'RESOLVED'""")) {
                ps.setTimestamp(1, Timestamp.from(now));
                ps.setObject(2, operator);
                ps.setString(3, note);
                ps.setObject(4, exceptionId);
                if (ps.executeUpdate() != 1) {
                    throw dev.chainbank.api.platform.ApiException.conflict("NOT_OPEN", "Exception not found or already resolved");
                }
            }
            audit.record(c, operator, "RECON_EXCEPTION_RESOLVED", "RECON_EXCEPTION", exceptionId.toString(),
                    java.util.Map.of("note", note));
            return load(c, exceptionId);
        });
    }

    /**
     * Retries settlement for a dead outbox row (MISSING_ON_CHAIN) or a reverted transaction (REVERTED, only
     * if the settlement id is still unused on-chain). The worker resolves the exception once the transfer is final.
     */
    public ReconciliationException retry(UUID exceptionId, UUID operator) {
        Instant now = clock.now();
        String contract = deployment.address().orElse(null);
        return db.tx(c -> {
            ReconciliationException ex = load(c, exceptionId);
            if (!ex.retryable()) {
                throw dev.chainbank.api.platform.ApiException.conflict("NOT_RETRYABLE",
                        "Only open MISSING_ON_CHAIN or REVERTED exceptions can be retried");
            }
            if ("MISSING_ON_CHAIN".equals(ex.kind())) {
                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE outbox SET status = 'PENDING', attempts = 0, next_attempt_at = ?, last_error = NULL
                        WHERE aggregate_id = ? AND status = 'DEAD'""")) {
                    ps.setTimestamp(1, Timestamp.from(now));
                    ps.setObject(2, ex.transferId());
                    ps.executeUpdate();
                }
            } else {
                String settlementId = SettlementAbi.settlementId(ex.transferId());
                if (contract != null && SettlementAbi.decodeBool(chain.call(contract, SettlementAbi.settledCall(settlementId)))) {
                    throw dev.chainbank.api.platform.ApiException.conflict("ALREADY_SETTLED",
                            "This transfer is already settled on-chain; resolve the exception instead");
                }
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO outbox (aggregate_type, aggregate_id, event_type, payload, created_at, next_attempt_at)
                        SELECT aggregate_type, aggregate_id, event_type, payload, ?, ? FROM outbox
                        WHERE aggregate_id = ? ORDER BY created_at LIMIT 1""")) {
                    ps.setTimestamp(1, Timestamp.from(now));
                    ps.setTimestamp(2, Timestamp.from(now));
                    ps.setObject(3, ex.transferId());
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE reconciliation_exceptions SET status = 'RETRYING', retry_count = retry_count + 1 WHERE id = ?")) {
                ps.setObject(1, exceptionId);
                ps.executeUpdate();
            }
            audit.record(c, operator, "RECON_EXCEPTION_RETRIED", "RECON_EXCEPTION", exceptionId.toString(),
                    java.util.Map.of("kind", ex.kind()));
            return load(c, exceptionId);
        });
    }

    private static ReconciliationException load(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT id, transfer_id, chain_event_id, kind, status, note, created_at, resolved_at, resolution_note,
                       retry_count FROM reconciliation_exceptions WHERE id = ?""")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw dev.chainbank.api.platform.ApiException.notFound("Exception not found");
                }
                return mapException(rs);
            }
        }
    }

    long cursor() {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT last_indexed_block FROM chain_cursor WHERE chain_id = ?")) {
                ps.setLong(1, chain.chainId());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
    }

    // --- exception helpers (shared with the worker) ----------------------------------------------

    /** Opens an exception unless an open one of the same kind already exists (partial unique indexes). */
    static void raise(Connection c, UUID transferId, Long eventId, String kind, String note, Instant now)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO reconciliation_exceptions (transfer_id, chain_event_id, kind, status, note, created_at, details)
                VALUES (?, ?, ?, 'OPEN', ?, ?, '{}'::jsonb) ON CONFLICT DO NOTHING""")) {
            ps.setObject(1, transferId);
            if (eventId == null) {
                ps.setNull(2, java.sql.Types.BIGINT);
            } else {
                ps.setLong(2, eventId);
            }
            ps.setString(3, kind);
            ps.setString(4, note);
            ps.setTimestamp(5, Timestamp.from(now));
            ps.executeUpdate();
        }
    }

    static void raiseResolved(Connection c, UUID transferId, String kind, String note, Instant now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO reconciliation_exceptions (transfer_id, kind, status, note, created_at, resolved_at, details)
                VALUES (?, ?, 'RESOLVED', ?, ?, ?, '{}'::jsonb)""")) {
            ps.setObject(1, transferId);
            ps.setString(2, kind);
            ps.setString(3, note);
            ps.setTimestamp(4, Timestamp.from(now));
            ps.setTimestamp(5, Timestamp.from(now));
            ps.executeUpdate();
        }
    }

    static void resolve(Connection c, UUID transferId, String kind, String note, Instant now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE reconciliation_exceptions SET status = 'RESOLVED', resolved_at = ?,
                       note = coalesce(note, '') || ' — ' || ?
                WHERE transfer_id = ? AND kind = ? AND status <> 'RESOLVED'""")) {
            ps.setTimestamp(1, Timestamp.from(now));
            ps.setString(2, note);
            ps.setObject(3, transferId);
            ps.setString(4, kind);
            ps.executeUpdate();
        }
    }

    private static void markEvent(Connection c, long eventId, Instant now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE chain_events SET reconciled_at = ? WHERE id = ?")) {
            ps.setTimestamp(1, Timestamp.from(now));
            ps.setLong(2, eventId);
            ps.executeUpdate();
        }
    }
}
