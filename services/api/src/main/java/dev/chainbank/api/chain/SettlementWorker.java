package dev.chainbank.api.chain;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.chainbank.api.audit.AuditLog;
import dev.chainbank.api.platform.AppClock;
import dev.chainbank.api.platform.ChainbankConfig;
import dev.chainbank.api.platform.Db;
import dev.chainbank.api.transfer.TransferService;
import io.quarkus.scheduler.Scheduled;

/**
 * Moves cross-bank transfers from the transactional outbox onto the chain and follows them to
 * finality (ADR 0006):
 *
 * <pre>
 * outbox PENDING ─submit─► chain_txs SUBMITTED ─receipt─► MINED ─2 confirmations─► FINAL
 *   transfer APPROVED ───► SUBMITTED ─────────────────────────────────────────────► CONFIRMED ─event─► RECONCILED
 *   reverted ► REVERTED (transfer PENDING_INVESTIGATION, or DUPLICATE if already settled)
 *   reorg    ► REORGED  (transfer PENDING_INVESTIGATION until the tx is final again)
 * </pre>
 *
 * Safety: the signed transaction is stored before it is broadcast, so a crash can only lead to a
 * rebroadcast of the same transaction. The contract rejects duplicate settlement ids, so even a
 * second, different transaction for the same transfer cannot move funds twice.
 */
@ApplicationScoped
public class SettlementWorker {

    private static final Logger LOG = Logger.getLogger(SettlementWorker.class);
    static final BigInteger SETTLE_GAS = BigInteger.valueOf(200_000);

    @Inject
    Db db;

    @Inject
    ChainClient chain;

    @Inject
    SettlementDeployment deployment;

    @Inject
    SettlementReconciler reconciler;

    @Inject
    ChainbankConfig config;

    @Inject
    AppClock clock;

    @Inject
    AuditLog audit;

    @Inject
    ObjectMapper mapper;

    record OutboxRow(UUID id, UUID transferId, JsonNode payload, int attempts) {
    }

    record ChainTx(UUID id, UUID transferId, String txHash, String status, String raw, String blockHash,
            String settlementId, Instant createdAt, Instant lastCheckedAt, UUID outboxId) {
    }

    @Scheduled(every = "${chainbank.chain.poll-every}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        if (chain.settler().isEmpty()) {
            return; // settlement disabled (no gateway key configured)
        }
        try {
            runOnce();
        } catch (RuntimeException e) {
            LOG.warnf("Settlement cycle failed: %s", e.getMessage());
        }
    }

    public void runOnce() {
        if (deployment.ensureDeployed().isEmpty()) {
            return;
        }
        submit();
        confirm();
        reconciler.index();
        reconciler.reconcile();
    }

    // --- 1. submit ---------------------------------------------------------------------------------

    public int submit() {
        Optional<String> contract = deployment.address();
        Optional<Credentials> settler = chain.settler();
        if (contract.isEmpty() || settler.isEmpty()) {
            return 0;
        }
        List<OutboxRow> rows = claim();
        if (rows.isEmpty()) {
            return 0;
        }
        try {
            chain.latestBlock();
        } catch (ChainClient.ChainException e) {
            rows.forEach(r -> retryLater(r, "RPC_UNAVAILABLE: " + e.getMessage()));
            return 0;
        }
        if (SettlementAbi.decodeBool(chain.call(contract.get(), SettlementAbi.pausedCall()))) {
            rows.forEach(this::holdWhilePaused);
            return 0;
        }
        int submitted = 0;
        for (OutboxRow row : rows) {
            try {
                submitOne(contract.get(), settler.get(), row);
                submitted++;
            } catch (RuntimeException e) {
                retryLater(row, e.getMessage());
            }
        }
        return submitted;
    }

    private List<OutboxRow> claim() {
        Instant now = clock.now();
        return db.tx(c -> {
            List<OutboxRow> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT id, aggregate_id, payload::text, attempts FROM outbox
                    WHERE status = 'PENDING' AND event_type = 'SETTLEMENT_REQUESTED' AND next_attempt_at <= ?
                    ORDER BY created_at LIMIT 20 FOR UPDATE SKIP LOCKED""")) {
                ps.setTimestamp(1, Timestamp.from(now));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new OutboxRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                                mapper.readTree(rs.getString(3)), rs.getInt(4) + 1));
                    }
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new IllegalStateException(e);
                }
            }
            for (OutboxRow r : out) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE outbox SET status = 'IN_FLIGHT', attempts = attempts + 1 WHERE id = ?")) {
                    ps.setObject(1, r.id());
                    ps.executeUpdate();
                }
            }
            return out;
        });
    }

    private void submitOne(String contract, Credentials settler, OutboxRow row) {
        String settlementId = SettlementAbi.settlementId(row.transferId());
        String data = SettlementAbi.settle(settlementId, row.payload().get("from").asText(),
                row.payload().get("to").asText(), BigInteger.valueOf(row.payload().get("amount").asLong()));
        ChainClient.Signed signed = chain.sign(settler, contract, data, SETTLE_GAS, chain.pendingNonce(settler.getAddress()));
        Instant now = clock.now();
        // Record first, then broadcast: a crash in between leaves a stored transaction the confirmer rebroadcasts.
        db.tx(c -> {
            insertChainTx(c, row.id(), row.transferId(), signed, settlementId, now);
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE transfers SET settlement_id = ? WHERE id = ? AND (settlement_id IS NULL OR settlement_id = ?)")) {
                ps.setString(1, settlementId);
                ps.setObject(2, row.transferId());
                ps.setString(3, settlementId);
                ps.executeUpdate();
            }
            if ("APPROVED".equals(transferState(c, row.transferId()))) {
                TransferService.transition(c, row.transferId(), "APPROVED", "SUBMITTED", "tx " + signed.txHash(), now);
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE outbox SET status = 'PUBLISHED', published_at = ?, last_error = NULL WHERE id = ?")) {
                ps.setTimestamp(1, Timestamp.from(now));
                ps.setObject(2, row.id());
                ps.executeUpdate();
            }
            audit.record(c, null, "SETTLEMENT_SUBMITTED", "TRANSFER", row.transferId().toString(),
                    Map.of("txHash", signed.txHash(), "nonce", signed.nonce().toString()));
            return null;
        });
        try {
            chain.broadcast(signed.raw());
        } catch (ChainClient.ChainException e) {
            LOG.warnf("Broadcast of %s failed (%s); the confirmer will rebroadcast", signed.txHash(), e.getMessage());
        }
    }

    private void retryLater(OutboxRow row, String error) {
        Instant now = clock.now();
        boolean dead = row.attempts() >= config.chain().maxSubmitAttempts();
        long backoff = Math.min(300, 1L << Math.min(row.attempts(), 9));
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE outbox SET status = ?, next_attempt_at = ?, last_error = ? WHERE id = ?")) {
                ps.setString(1, dead ? "DEAD" : "PENDING");
                ps.setTimestamp(2, Timestamp.from(now.plusSeconds(backoff)));
                ps.setString(3, error == null ? "unknown" : error.substring(0, Math.min(error.length(), 500)));
                ps.setObject(4, row.id());
                ps.executeUpdate();
            }
            if (dead) {
                SettlementReconciler.raise(c, row.transferId(), null, "MISSING_ON_CHAIN",
                        "Gave up submitting after " + row.attempts() + " attempts: " + error, now);
            }
            return null;
        });
    }

    /** Pausing is an operator decision, not a failure: keep the row pending without counting an attempt. */
    private void holdWhilePaused(OutboxRow row) {
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE outbox SET status = 'PENDING', attempts = attempts - 1, next_attempt_at = ?,
                                      last_error = 'CONTRACT_PAUSED' WHERE id = ?""")) {
                ps.setTimestamp(1, Timestamp.from(clock.now().plusSeconds(5)));
                ps.setObject(2, row.id());
                ps.executeUpdate();
            }
            return null;
        });
    }

    // --- 2. confirm --------------------------------------------------------------------------------

    public void confirm() {
        long latest;
        try {
            latest = chain.latestBlock();
        } catch (ChainClient.ChainException e) {
            return;
        }
        for (ChainTx tx : openTxs()) {
            try {
                confirmOne(tx, latest);
            } catch (ChainClient.ChainException e) {
                LOG.warnf("Could not check %s: %s", tx.txHash(), e.getMessage());
            }
        }
    }

    private void confirmOne(ChainTx tx, long latest) {
        Instant now = clock.now();
        Optional<TransactionReceipt> receipt = chain.receipt(tx.txHash());
        if (receipt.isEmpty()) {
            if ("MINED".equals(tx.status())) {
                onReorg(tx, "Transaction disappeared from the canonical chain");
                rebroadcast(tx, now);
            } else if (tx.lastCheckedAt() == null
                    || Duration.between(tx.lastCheckedAt(), now).compareTo(config.chain().rebroadcastAfter()) >= 0) {
                rebroadcast(tx, now);
            }
            return;
        }
        TransactionReceipt r = receipt.get();
        if (!r.isStatusOK()) {
            onRevert(tx);
            return;
        }
        long block = r.getBlockNumber().longValue();
        String blockHash = r.getBlockHash();
        if (!chain.blockHash(block).map(blockHash::equalsIgnoreCase).orElse(false)) {
            onReorg(tx, "Block " + block + " is no longer canonical");
            return;
        }
        int confirmations = (int) (latest - block + 1);
        boolean isFinal = confirmations >= config.chain().confirmations();
        boolean reincluded = tx.blockHash() != null && !tx.blockHash().equalsIgnoreCase(blockHash);
        db.tx(c -> {
            if (reincluded) {
                // A reorg moved the transaction to another block; it is still valid, but record what happened.
                SettlementReconciler.raiseResolved(c, tx.transferId(), "REORG",
                        "Reorg moved " + tx.txHash() + " from block hash " + tx.blockHash() + " to block " + block, now);
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE chain_txs SET status = ?, block_number = ?, block_hash = ?, confirmations = ?,
                                         last_checked_at = ?, updated_at = ?
                    WHERE id = ?""")) {
                ps.setString(1, isFinal ? "FINAL" : "MINED");
                ps.setLong(2, block);
                ps.setString(3, blockHash);
                ps.setInt(4, confirmations);
                ps.setTimestamp(5, Timestamp.from(now));
                ps.setTimestamp(6, Timestamp.from(now));
                ps.setObject(7, tx.id());
                ps.executeUpdate();
            }
            if (isFinal) {
                String state = transferState(c, tx.transferId());
                if ("SUBMITTED".equals(state) || "PENDING_INVESTIGATION".equals(state)) {
                    TransferService.transition(c, tx.transferId(), state, "CONFIRMED",
                            "Final after " + confirmations + " confirmations (block " + block + ")", now);
                }
                SettlementReconciler.resolve(c, tx.transferId(), "REORG",
                        "Settlement became final in block " + block + " after the reorg", now);
            }
            return null;
        });
    }

    private void onReorg(ChainTx tx, String why) {
        Instant now = clock.now();
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE chain_txs SET status = 'REORGED', block_number = NULL, block_hash = NULL, confirmations = 0,
                                         updated_at = ? WHERE id = ?""")) {
                ps.setTimestamp(1, Timestamp.from(now));
                ps.setObject(2, tx.id());
                ps.executeUpdate();
            }
            if ("SUBMITTED".equals(transferState(c, tx.transferId()))) {
                TransferService.transition(c, tx.transferId(), "SUBMITTED", "PENDING_INVESTIGATION", "REORG", now);
            }
            SettlementReconciler.raise(c, tx.transferId(), null, "REORG", why + " (tx " + tx.txHash() + ")", now);
            return null;
        });
    }

    private void onRevert(ChainTx tx) {
        Instant now = clock.now();
        String contract = deployment.address().orElseThrow();
        boolean alreadySettled = SettlementAbi.decodeBool(chain.call(contract, SettlementAbi.settledCall(tx.settlementId())));
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE chain_txs SET status = 'REVERTED', updated_at = ?, last_checked_at = ? WHERE id = ?")) {
                ps.setTimestamp(1, Timestamp.from(now));
                ps.setTimestamp(2, Timestamp.from(now));
                ps.setObject(3, tx.id());
                ps.executeUpdate();
            }
            if (alreadySettled) {
                // A second submission for an already-settled transfer: the contract refused it. Benign, but recorded.
                SettlementReconciler.raiseResolved(c, tx.transferId(), "DUPLICATE",
                        "Duplicate submission " + tx.txHash() + " reverted; the transfer was already settled once", now);
            } else {
                if ("SUBMITTED".equals(transferState(c, tx.transferId()))) {
                    TransferService.transition(c, tx.transferId(), "SUBMITTED", "PENDING_INVESTIGATION", "REVERTED", now);
                }
                SettlementReconciler.raise(c, tx.transferId(), null, "REVERTED", "Settlement tx " + tx.txHash() + " reverted", now);
            }
            return null;
        });
    }

    private void rebroadcast(ChainTx tx, Instant now) {
        try {
            chain.broadcast(tx.raw());
        } catch (ChainClient.ChainException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (msg.contains("nonce too low") || msg.contains("nonce")) {
                replace(tx, now);
                return;
            }
            LOG.warnf("Rebroadcast of %s failed: %s", tx.txHash(), e.getMessage());
        }
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE chain_txs SET attempts = attempts + 1, last_checked_at = ? WHERE id = ?")) {
                ps.setTimestamp(1, Timestamp.from(now));
                ps.setObject(2, tx.id());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /**
     * The stored transaction can never be mined (its nonce was used by another transaction). Sign the
     * same settlement again with a fresh nonce. The contract's duplicate guard makes this safe.
     */
    private void replace(ChainTx tx, Instant now) {
        String contract = deployment.address().orElseThrow();
        Credentials settler = chain.settler().orElseThrow();
        if (SettlementAbi.decodeBool(chain.call(contract, SettlementAbi.settledCall(tx.settlementId())))) {
            return; // settled by another transaction already; reconciliation will match its event
        }
        JsonNode payload = db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT payload::text FROM outbox WHERE id = ?")) {
                ps.setObject(1, tx.outboxId());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return mapper.readTree(rs.getString(1));
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new IllegalStateException(e);
                }
            }
        });
        String data = SettlementAbi.settle(tx.settlementId(), payload.get("from").asText(), payload.get("to").asText(),
                BigInteger.valueOf(payload.get("amount").asLong()));
        ChainClient.Signed signed = chain.sign(settler, contract, data, SETTLE_GAS, chain.pendingNonce(settler.getAddress()));
        db.tx(c -> {
            UUID newId = insertChainTx(c, tx.outboxId(), tx.transferId(), signed, tx.settlementId(), now);
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE chain_txs SET status = 'DROPPED', replaced_by = ?, updated_at = ? WHERE id = ?")) {
                ps.setObject(1, newId);
                ps.setTimestamp(2, Timestamp.from(now));
                ps.setObject(3, tx.id());
                ps.executeUpdate();
            }
            return null;
        });
        chain.broadcast(signed.raw());
    }

    // --- helpers -----------------------------------------------------------------------------------

    private UUID insertChainTx(Connection c, UUID outboxId, UUID transferId, ChainClient.Signed signed,
            String settlementId, Instant now) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO chain_txs (id, outbox_id, transfer_id, chain_id, tx_hash, status, settlement_id, nonce,
                                       raw_tx, attempts, last_checked_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'SUBMITTED', ?, ?, ?, 1, ?, ?, ?)""")) {
            ps.setObject(1, id);
            ps.setObject(2, outboxId);
            ps.setObject(3, transferId);
            ps.setLong(4, chain.chainId());
            ps.setString(5, signed.txHash().toLowerCase());
            ps.setString(6, settlementId);
            ps.setLong(7, signed.nonce().longValue());
            ps.setString(8, signed.raw());
            ps.setTimestamp(9, Timestamp.from(now));
            ps.setTimestamp(10, Timestamp.from(now));
            ps.setTimestamp(11, Timestamp.from(now));
            ps.executeUpdate();
        }
        return id;
    }

    private List<ChainTx> openTxs() {
        return db.tx(c -> {
            List<ChainTx> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT id, transfer_id, tx_hash, status, raw_tx, block_hash, settlement_id, created_at,
                           last_checked_at, outbox_id
                    FROM chain_txs WHERE chain_id = ? AND status IN ('SUBMITTED', 'MINED', 'REORGED') ORDER BY created_at""")) {
                ps.setLong(1, chain.chainId());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Timestamp checked = rs.getTimestamp(9);
                        out.add(new ChainTx(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                                rs.getTimestamp(8).toInstant(), checked == null ? null : checked.toInstant(),
                                rs.getObject(10, UUID.class)));
                    }
                }
            }
            return out;
        });
    }

    static String transferState(Connection c, UUID transferId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT state FROM transfers WHERE id = ? FOR UPDATE")) {
            ps.setObject(1, transferId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}
