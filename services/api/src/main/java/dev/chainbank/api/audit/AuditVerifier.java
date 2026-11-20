package dev.chainbank.api.audit;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import dev.chainbank.api.auth.Crypto;
import dev.chainbank.api.chain.AuditAnchorAbi;
import dev.chainbank.api.chain.ChainClient;
import dev.chainbank.api.platform.ApiException;
import dev.chainbank.api.platform.ChainbankConfig;
import dev.chainbank.api.platform.Db;

/**
 * Builds self-contained batch exports and verifies batches server-side (spec section 10,
 * {@code GET /api/audit/{batch}/verify}). The standalone verifier in tools/audit-verifier performs the
 * same checks from an export without trusting this code.
 */
@ApplicationScoped
public class AuditVerifier {

    private static final HexFormat HEX = HexFormat.of();
    public static final String FORMAT = "chainbank-audit-batch/1";

    @Inject
    Db db;

    @Inject
    AuditLog audit;

    @Inject
    AuditBatcher batcher;

    @Inject
    ChainClient chain;

    @Inject
    ChainbankConfig config;

    public record Check(String name, boolean ok, String detail) {
    }

    public record Verification(long batchId, boolean valid, List<Check> checks) {
    }

    public record Export(String format, long batchId, long chainId, String contract, String txHash, Long blockNumber,
            long firstEventId, long lastEventId, int eventCount, String merkleRoot, String previousBatchLastHash,
            Map<String, String> hashing, List<AuditLog.ExportedEvent> events) {
    }

    public Export export(long batchId) {
        AuditBatcher.Batch b = batcher.get(batchId).orElseThrow(() -> ApiException.notFound("Audit batch not found"));
        String prevLast = db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT prev_last_hash FROM audit_batches WHERE id = ?")) {
                ps.setLong(1, batchId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    byte[] h = rs.getBytes(1);
                    return h == null ? "" : Crypto.hex(h);
                }
            }
        });
        List<AuditLog.ExportedEvent> events = db.tx(c -> audit.eventsInRange(c, b.firstEventId() - 1, b.lastEventId()));
        return new Export(FORMAT, b.id(), chain.chainId(), b.contract(), b.txHash(), b.blockNumber(), b.firstEventId(),
                b.lastEventId(), b.eventCount(), b.merkleRoot(), prevLast, Map.of(
                        "event", "sha256(utf8(concat(len(f) + ':' + f + '|' for f in [prevHash, occurredAt, actorUserId, "
                                + "action, resourceType, resourceId, correlationId, details]))); len counts UTF-16 code units",
                        "merkle", "RFC 6962: leaf = sha256(0x00 || eventHash), node = sha256(0x01 || left || right), "
                                + "split at the largest power of two below n",
                        "chain", "AuditAnchor.batches(batchId) must return (merkleRoot, firstEventId, lastEventId, block) "
                                + "with at least " + config.chain().confirmations() + " confirmations"),
                events);
    }

    public Verification verify(long batchId) {
        Export e = export(batchId);
        List<Check> checks = new ArrayList<>();

        int badHashes = 0;
        for (AuditLog.ExportedEvent ev : e.events()) {
            if (!Crypto.hex(ev.recomputeHash()).equals(ev.hash())) {
                badHashes++;
            }
        }
        checks.add(new Check("event-hashes", badHashes == 0, badHashes == 0
                ? e.events().size() + " event hashes recompute from their fields"
                : badHashes + " event(s) do not match their recorded hash"));

        String expectedPrev = e.previousBatchLastHash();
        int brokenLinks = 0;
        for (AuditLog.ExportedEvent ev : e.events()) {
            if (!ev.prevHash().equals(expectedPrev)) {
                brokenLinks++;
            }
            expectedPrev = ev.hash();
        }
        checks.add(new Check("hash-chain", brokenLinks == 0 && e.events().size() == e.eventCount(), brokenLinks == 0
                ? "every event links to the one before it, starting from the previous batch"
                : brokenLinks + " broken link(s) in the hash chain"));

        List<byte[]> leaves = e.events().stream().map(ev -> HEX.parseHex(ev.hash())).toList();
        String recomputed = "0x" + HEX.formatHex(MerkleTree.root(leaves));
        checks.add(new Check("merkle-root", recomputed.equals(e.merkleRoot()),
                recomputed.equals(e.merkleRoot()) ? "Merkle root recomputes" : "recomputed root " + recomputed + " differs"));

        if (e.contract() == null) {
            checks.add(new Check("on-chain", false, "batch not anchored yet"));
        } else {
            try {
                AuditAnchorAbi.OnChainBatch onChain = AuditAnchorAbi.decodeBatch(chain.call(e.contract(), AuditAnchorAbi.batchesCall(batchId)));
                boolean match = onChain.root().equalsIgnoreCase(e.merkleRoot()) && onChain.firstEventId() == e.firstEventId()
                        && onChain.lastEventId() == e.lastEventId();
                checks.add(new Check("on-chain", match, match ? "contract holds the same root and event range"
                        : "contract holds root " + onChain.root() + " for events " + onChain.firstEventId() + "-" + onChain.lastEventId()));
                long confirmations = onChain.exists() ? chain.latestBlock() - onChain.blockNumber() + 1 : 0;
                boolean fin = onChain.exists() && confirmations >= config.chain().confirmations();
                checks.add(new Check("finality", fin, confirmations + " confirmation(s), " + config.chain().confirmations()
                        + " required"));
            } catch (ChainClient.ChainException ex) {
                checks.add(new Check("on-chain", false, "chain unavailable: " + ex.getMessage()));
            }
        }
        return new Verification(batchId, checks.stream().allMatch(Check::ok), checks);
    }
}
