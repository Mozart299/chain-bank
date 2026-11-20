package dev.chainbank.api.audit;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import dev.chainbank.api.chain.AuditAnchorAbi;
import dev.chainbank.api.chain.ChainClient;
import dev.chainbank.api.chain.SettlementDeployment;
import dev.chainbank.api.platform.AppClock;
import dev.chainbank.api.platform.ChainbankConfig;
import dev.chainbank.api.platform.Db;

/**
 * Seals the hash-chained audit log into batches and anchors each batch's Merkle root on-chain
 * (ADR 0007). A batch is sealed after {@code batch-size} events or once the oldest unsealed event is
 * {@code max-age} old. Anchoring follows the settlement worker's record-then-broadcast pattern and
 * counts a batch as COMMITTED after the same finality depth.
 */
@ApplicationScoped
public class AuditBatcher {

    private static final Logger LOG = Logger.getLogger(AuditBatcher.class);
    /** Same advisory lock as AuditLog appends, so a seal sees a consistent end of the chain. */
    private static final long CHAIN_LOCK = 0x43_42_41_55_44_49_54L;
    static final BigInteger COMMIT_GAS = BigInteger.valueOf(150_000);
    private static final HexFormat HEX = HexFormat.of();

    @Inject
    Db db;

    @Inject
    AuditLog audit;

    @Inject
    ChainClient chain;

    @Inject
    SettlementDeployment deployment;

    @Inject
    AppClock clock;

    @Inject
    ChainbankConfig config;


    public record Batch(long id, long firstEventId, long lastEventId, int eventCount, String merkleRoot,
            String status, String contract, String txHash, Long blockNumber, Instant createdAt, Instant committedAt) {
    }

    /** Seals a batch if the size or age threshold is reached. Returns the new batch id, if any. */
    public Optional<Long> sealIfDue() {
        return seal(false);
    }

    /** Seals whatever is pending right now (admin "checkpoint now"). */
    public Optional<Long> sealNow() {
        return seal(true);
    }

    private Optional<Long> seal(boolean force) {
        Instant now = clock.now();
        return db.tx(c -> {
            try (PreparedStatement lock = c.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                lock.setLong(1, CHAIN_LOCK);
                lock.execute();
            }
            long lastSealed = lastSealedEventId(c);
            List<long[]> ids = new ArrayList<>();
            List<byte[]> hashes = new ArrayList<>();
            Instant oldest = null;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, hash, occurred_at FROM audit_events WHERE id > ? ORDER BY id LIMIT ?")) {
                ps.setLong(1, lastSealed);
                ps.setInt(2, config.audit().batchSize());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(new long[] { rs.getLong(1) });
                        hashes.add(rs.getBytes(2));
                        if (oldest == null) {
                            oldest = rs.getTimestamp(3).toInstant();
                        }
                    }
                }
            }
            boolean due = !ids.isEmpty() && (force || ids.size() >= config.audit().batchSize() || !oldest.plus(config.audit().maxAge()).isAfter(now));
            if (!due) {
                return Optional.<Long>empty();
            }
            long last = ids.get(ids.size() - 1)[0];
            byte[] root = MerkleTree.root(hashes);
            byte[] prevLast = null;
            try (PreparedStatement ps = c.prepareStatement("SELECT hash FROM audit_events WHERE id = ?")) {
                ps.setLong(1, lastSealed);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        prevLast = rs.getBytes(1);
                    }
                }
            }
            long id;
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO audit_batches (first_event_id, last_event_id, event_count, merkle_root, prev_last_hash,
                                               status, chain_id, created_at)
                    VALUES (?, ?, ?, ?, ?, 'SEALED', ?, ?) RETURNING id""")) {
                ps.setLong(1, lastSealed + 1);
                ps.setLong(2, last);
                ps.setInt(3, ids.size());
                ps.setBytes(4, root);
                ps.setBytes(5, prevLast);
                ps.setLong(6, chain.chainId());
                ps.setTimestamp(7, Timestamp.from(now));
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    id = rs.getLong(1);
                }
            }
            // Recorded after the range was fixed, so this event belongs to the next batch.
            audit.record(c, null, "AUDIT_BATCH_SEALED", "AUDIT_BATCH", Long.toString(id), Map.of(
                    "events", Integer.toString(ids.size()), "merkleRoot", "0x" + HEX.formatHex(root)));
            return Optional.of(id);
        });
    }

    /** Submits SEALED batches in id order. On-chain batch ids must be sequential, so stop at the first failure. */
    public int anchor() {
        Optional<String> contract = deployment.anchorAddress();
        Optional<Credentials> key = chain.settler();
        if (contract.isEmpty() || key.isEmpty()) {
            return 0;
        }
        int submitted = 0;
        for (Batch b : batches("status = 'SEALED'", 20)) {
            try {
                ChainClient.Signed signed = chain.sign(key.get(), contract.get(),
                        AuditAnchorAbi.commit(b.id(), b.firstEventId(), b.lastEventId(), HEX.parseHex(b.merkleRoot().substring(2))),
                        COMMIT_GAS, chain.pendingNonce(key.get().getAddress()));
                Instant now = clock.now();
                db.tx(c -> {
                    try (PreparedStatement ps = c.prepareStatement("""
                            UPDATE audit_batches SET status = 'SUBMITTED', contract = ?, chain_tx_hash = ?, raw_tx = ?,
                                                     last_checked_at = ?, last_error = NULL
                            WHERE id = ? AND status = 'SEALED'""")) {
                        ps.setString(1, contract.get());
                        ps.setString(2, signed.txHash().toLowerCase());
                        ps.setString(3, signed.raw());
                        ps.setTimestamp(4, Timestamp.from(now));
                        ps.setLong(5, b.id());
                        ps.executeUpdate();
                    }
                    return null;
                });
                chain.broadcast(signed.raw());
                submitted++;
            } catch (ChainClient.ChainException e) {
                note(b.id(), e.getMessage());
                break;
            }
        }
        return submitted;
    }

    /** Follows submitted anchors to finality; rebroadcasts or re-signs if a transaction went missing. */
    public void confirm() {
        Optional<String> contract = deployment.anchorAddress();
        if (contract.isEmpty()) {
            return;
        }
        long latest;
        try {
            latest = chain.latestBlock();
        } catch (ChainClient.ChainException e) {
            return;
        }
        for (Batch b : batches("status = 'SUBMITTED'", 50)) {
            try {
                confirmOne(contract.get(), b, latest);
            } catch (ChainClient.ChainException e) {
                LOG.warnf("Could not check audit batch %d: %s", b.id(), e.getMessage());
            }
        }
    }

    private void confirmOne(String contract, Batch b, long latest) {
        Instant now = clock.now();
        Optional<TransactionReceipt> receipt = chain.receipt(b.txHash());
        if (receipt.isEmpty() || !receipt.get().isStatusOK()) {
            // Reverted or missing. If the chain already holds our exact root for this id, it is committed.
            AuditAnchorAbi.OnChainBatch onChain = AuditAnchorAbi.decodeBatch(chain.call(contract, AuditAnchorAbi.batchesCall(b.id())));
            if (onChain.exists() && onChain.root().equalsIgnoreCase(b.merkleRoot())) {
                return; // a confirmed duplicate; the original transaction's receipt will finalize it
            }
            if (receipt.isPresent()) {
                fail(b.id(), "commit reverted (on-chain batch " + (onChain.exists() ? onChain.root() : "absent") + ")");
            } else if (Duration.between(lastChecked(b.id()), now).compareTo(config.chain().rebroadcastAfter()) >= 0) {
                resubmit(contract, b, now);
            }
            return;
        }
        TransactionReceipt r = receipt.get();
        long block = r.getBlockNumber().longValue();
        if (!chain.blockHash(block).map(r.getBlockHash()::equalsIgnoreCase).orElse(false)) {
            return; // reorged out of this block: the next cycle sees it re-mined or missing
        }
        if (latest - block + 1 < config.chain().confirmations()) {
            return;
        }
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE audit_batches SET status = 'COMMITTED', block_number = ?, block_hash = ?, committed_at = ?
                    WHERE id = ? AND status = 'SUBMITTED'""")) {
                ps.setLong(1, block);
                ps.setString(2, r.getBlockHash());
                ps.setTimestamp(3, Timestamp.from(now));
                ps.setLong(4, b.id());
                ps.executeUpdate();
            }
            return null;
        });
    }

    private void resubmit(String contract, Batch b, Instant now) {
        String raw = db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT raw_tx FROM audit_batches WHERE id = ?")) {
                ps.setLong(1, b.id());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        });
        try {
            chain.broadcast(raw);
            touch(b.id(), now);
        } catch (ChainClient.ChainException e) {
            // Nonce consumed elsewhere: put the batch back to SEALED so it is signed again with a fresh nonce.
            db.tx(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE audit_batches SET status = 'SEALED', last_error = ? WHERE id = ? AND status = 'SUBMITTED'")) {
                    ps.setString(1, e.getMessage());
                    ps.setLong(2, b.id());
                    ps.executeUpdate();
                }
                return null;
            });
        }
    }

    public List<Batch> list(int limit) {
        return batches("true", limit);
    }

    public Optional<Batch> get(long id) {
        return batches("id = " + id, 1).stream().findFirst();
    }

    private List<Batch> batches(String where, int limit) {
        return db.tx(c -> {
            List<Batch> out = new ArrayList<>();
            boolean ascending = !where.equals("true");
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT id, first_event_id, last_event_id, event_count, merkle_root, status, contract, chain_tx_hash,
                           block_number, created_at, committed_at
                    FROM audit_batches WHERE """ + " " + where + " ORDER BY id " + (ascending ? "ASC" : "DESC") + " LIMIT ?")) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long block = rs.getLong(9);
                        Long blockNumber = rs.wasNull() ? null : block;
                        Timestamp committed = rs.getTimestamp(11);
                        out.add(new Batch(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getInt(4),
                                "0x" + HEX.formatHex(rs.getBytes(5)), rs.getString(6), rs.getString(7), rs.getString(8),
                                blockNumber, rs.getTimestamp(10).toInstant(),
                                committed == null ? null : committed.toInstant()));
                    }
                }
            }
            return out;
        });
    }

    static long lastSealedEventId(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT coalesce(max(last_event_id), 0) FROM audit_batches");
                ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private Instant lastChecked(long id) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT coalesce(last_checked_at, created_at) FROM audit_batches WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getTimestamp(1).toInstant();
                }
            }
        });
    }

    private void touch(long id, Instant now) {
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE audit_batches SET last_checked_at = ? WHERE id = ?")) {
                ps.setTimestamp(1, Timestamp.from(now));
                ps.setLong(2, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private void note(long id, String error) {
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE audit_batches SET last_error = ? WHERE id = ?")) {
                ps.setString(1, error == null ? "unknown" : error.substring(0, Math.min(error.length(), 500)));
                ps.setLong(2, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private void fail(long id, String error) {
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE audit_batches SET status = 'FAILED', last_error = ? WHERE id = ?")) {
                ps.setString(1, error);
                ps.setLong(2, id);
                ps.executeUpdate();
            }
            return null;
        });
        LOG.errorf("Audit batch %d failed to anchor: %s", id, error);
    }
}
