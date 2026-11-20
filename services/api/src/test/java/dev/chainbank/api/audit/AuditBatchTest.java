package dev.chainbank.api.audit;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;

import dev.chainbank.api.auth.Crypto;
import dev.chainbank.api.chain.ChainTestBase;

/**
 * Phase 4 exit evidence (server side): audit batches are sealed, anchored on-chain, and verify;
 * tampering is pinpointed. The standalone verifier is exercised by scripts/e2e-auth.mjs.
 */
@QuarkusTest
class AuditBatchTest extends ChainTestBase {

    @Inject
    AuditBatcher batcher;

    @Inject
    AuditVerifier verifier;

    @BeforeEach
    void contracts() {
        assertTrue(deployment.ensureDeployed().isPresent());
        assertTrue(deployment.ensureAnchorDeployed().isPresent());
    }

    /** Seals now, anchors, and waits for 2 confirmations. */
    private long sealAndCommit() {
        long id = batcher.sealNow().orElseThrow();
        anchorAll();
        return id;
    }

    private void anchorAll() {
        batcher.anchor();
        awaitReceipts();
        mine(1);
        batcher.confirm();
    }

    @Test
    void sealedBatchIsAnchoredAndVerifies() {
        newCustomer("audit-activity");
        long id = sealAndCommit();
        assertEquals("COMMITTED", batcher.get(id).orElseThrow().status());

        Session auditor = newUserWithRole("batch-auditor", "AUDITOR");
        as(auditor).get("/api/audit/" + id + "/verify").then().statusCode(200)
                .body("valid", equalTo(true))
                .body("checks.name", equalTo(List.of("event-hashes", "hash-chain", "merkle-root", "on-chain", "finality")))
                .body("checks.ok", everyItem(equalTo(true)));
        as(auditor).get("/api/audit/batches").then().statusCode(200).body("id", hasItem((int) id));
    }

    @Test
    void consecutiveBatchesChainTogether() {
        newCustomer("chain-a");
        long first = sealAndCommit();
        newCustomer("chain-b");
        long second = sealAndCommit();
        AuditVerifier.Export a = verifier.export(first);
        AuditVerifier.Export b = verifier.export(second);
        assertEquals(a.lastEventId() + 1, b.firstEventId(), "contiguous ranges");
        assertEquals(a.events().get(a.events().size() - 1).hash(), b.previousBatchLastHash(), "linked by hash");
        assertTrue(verifier.verify(second).valid());
    }

    @Test
    void sealsByAgeWhenFewEventsArrive() {
        batcher.sealNow(); // flush whatever earlier tests left
        newCustomer("slow-trickle");
        assertEquals(Optional.empty(), batcher.sealIfDue(), "below 100 events and younger than 10 minutes");
        clock.advance(Duration.ofMinutes(11));
        assertTrue(batcher.sealIfDue().isPresent(), "sealed by age");
        anchorAll();
    }

    @Test
    void editingAnEventIsPinpointed() throws SQLException {
        newCustomer("tamper-target");
        long id = sealAndCommit();
        AuditVerifier.Export export = verifier.export(id);
        AuditLog.ExportedEvent victim = export.events().get(0);

        withTriggerDisabled(() -> exec("UPDATE audit_events SET action = 'NOTHING_TO_SEE' WHERE id = ?", victim.id()),
                () -> {
                    AuditVerifier.Verification v = verifier.verify(id);
                    assertFalse(v.valid());
                    assertEquals(Map.of("event-hashes", false, "hash-chain", true, "merkle-root", true, "on-chain", true,
                            "finality", true), outcome(v));
                },
                () -> exec("UPDATE audit_events SET action = ? WHERE id = ?", victim.action(), victim.id()));
        assertTrue(verifier.verify(id).valid(), "restored");
    }

    @Test
    void forgingAConsistentEventStillBreaksTheChainAndTheRoot() throws SQLException {
        newCustomer("forge-a");
        newCustomer("forge-b");
        long id = sealAndCommit();
        AuditLog.ExportedEvent victim = verifier.export(id).events().get(0);
        // A smarter attacker also recomputes the edited event's own hash...
        String forgedHash = Crypto.hex(AuditLog.hashFields(victim.prevHash(), victim.occurredAt(), victim.actorUserId(),
                "NOTHING_TO_SEE", victim.resourceType(), victim.resourceId(), victim.correlationId(), victim.details()));

        withTriggerDisabled(() -> exec("UPDATE audit_events SET action = 'NOTHING_TO_SEE', hash = decode(?, 'hex') WHERE id = ?",
                forgedHash, victim.id()),
                () -> {
                    // ...but the next event still points at the old hash, and the anchored root no longer matches.
                    AuditVerifier.Verification v = verifier.verify(id);
                    assertEquals(Map.of("event-hashes", true, "hash-chain", false, "merkle-root", false, "on-chain", true,
                            "finality", true), outcome(v));
                },
                () -> exec("UPDATE audit_events SET action = ?, hash = decode(?, 'hex') WHERE id = ?", victim.action(),
                        victim.hash(), victim.id()));
        assertTrue(verifier.verify(id).valid(), "restored");
    }

    @Test
    void sealedBatchesAreImmutable() {
        newCustomer("immutable-batch");
        long id = batcher.sealNow().orElseThrow();
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> exec("UPDATE audit_batches SET merkle_root = decode(repeat('00', 32), 'hex') WHERE id = ?", id));
        assertTrue(String.valueOf(e.getCause()).contains("cannot change"), String.valueOf(e.getCause()));
        anchorAll();
    }

    @Test
    void checkpointEndpointIsAdminOnlyWithStepUp() {
        Session admin = newUserWithRole("audit-admin", "SYSTEM_ADMIN");
        Session auditor = newUserWithRole("audit-ro", "AUDITOR");
        Session customer = newCustomer("audit-nosy");
        as(customer).get("/api/audit/batches").then().statusCode(403);
        stepUp(auditor);
        as(auditor).post("/api/audit/batches/seal").then().statusCode(403);
        as(admin).post("/api/audit/batches/seal").then().statusCode(403).body("code", equalTo("STEP_UP_REQUIRED"));
        stepUp(admin);
        Response r = as(admin).post("/api/audit/batches/seal").then().statusCode(200).extract().response();
        assertTrue(((Number) r.path("batchId")).longValue() > 0);
        as(auditor).get("/api/audit/batches/" + r.path("batchId") + "/export").then().statusCode(200)
                .header("Content-Disposition", org.hamcrest.Matchers.containsString("chainbank-audit-batch-"))
                .body("format", equalTo("chainbank-audit-batch/1"));
        anchorAll();
    }

    // --- exception handling ----------------------------------------------------------------------

    @Test
    void operatorRetriesDeadSettlementAndItAutoResolves() {
        Session alice = newCustomerAt("retry-alice", "BANK_A");
        Session bob = newCustomerAt("retry-bob", "BANK_B");
        Session ops = newUserWithRole("retry-ops", "BANK_OPERATOR");
        String transferId = sendApproved(alice, bob, 4_000).path("id");
        // What the worker leaves behind after giving up (10 failed attempts).
        exec("UPDATE outbox SET status = 'DEAD', attempts = 10 WHERE aggregate_id = ?::uuid", transferId);
        exec("""
                INSERT INTO reconciliation_exceptions (transfer_id, kind, status, note, details)
                VALUES (?::uuid, 'MISSING_ON_CHAIN', 'OPEN', 'Gave up after 10 attempts', '{}'::jsonb)""", transferId);
        String exceptionId = queryString(
                "SELECT id::text FROM reconciliation_exceptions WHERE transfer_id = ?::uuid AND kind = 'MISSING_ON_CHAIN'", transferId);

        as(ops).post("/api/reconciliation/exceptions/" + exceptionId + "/retry").then().statusCode(403)
                .body("code", equalTo("STEP_UP_REQUIRED"));
        stepUp(ops);
        as(ops).post("/api/reconciliation/exceptions/" + exceptionId + "/retry").then().statusCode(200)
                .body("status", equalTo("RETRYING")).body("retryCount", equalTo(1));

        settleFully();
        assertEquals("RECONCILED", state(alice, transferId));
        assertEquals("RESOLVED", queryString("SELECT status FROM reconciliation_exceptions WHERE id = ?::uuid", exceptionId));
    }

    @Test
    void resolvingNeedsAStepUpAndANote() {
        Session ops = newUserWithRole("resolve-ops", "BANK_OPERATOR");
        Session customer = newCustomer("resolve-nosy");
        exec("""
                INSERT INTO reconciliation_exceptions (kind, status, note, details)
                VALUES ('MISSING_IN_JOURNAL', 'OPEN', 'test exception', '{}'::jsonb)""");
        String id = queryString("SELECT id::text FROM reconciliation_exceptions WHERE note = 'test exception' "
                + "AND status = 'OPEN' ORDER BY created_at DESC LIMIT 1");

        as(customer).body(Map.of("note", "not mine to close")).post("/api/reconciliation/exceptions/" + id + "/resolve")
                .then().statusCode(403);
        stepUp(ops);
        as(ops).body(Map.of("note", "")).post("/api/reconciliation/exceptions/" + id + "/resolve").then().statusCode(400);
        as(ops).post("/api/reconciliation/exceptions/" + id + "/retry").then().statusCode(409)
                .body("code", equalTo("NOT_RETRYABLE"));
        as(ops).body(Map.of("note", "Test settlement; confirmed with both banks"))
                .post("/api/reconciliation/exceptions/" + id + "/resolve").then().statusCode(200)
                .body("status", equalTo("RESOLVED")).body("resolutionNote", equalTo("Test settlement; confirmed with both banks"));
        as(ops).body(Map.of("note", "again please")).post("/api/reconciliation/exceptions/" + id + "/resolve")
                .then().statusCode(409);
    }

    // --- helpers -----------------------------------------------------------------------------------

    private static Map<String, Boolean> outcome(AuditVerifier.Verification v) {
        return v.checks().stream().collect(java.util.stream.Collectors.toMap(AuditVerifier.Check::name, AuditVerifier.Check::ok));
    }

    /** Simulates a database-owner attacker: bypass the append-only trigger, check, then always restore. */
    private void withTriggerDisabled(Runnable tamper, Runnable check, Runnable restore) {
        exec("ALTER TABLE audit_events DISABLE TRIGGER audit_events_immutable");
        try {
            tamper.run();
            check.run();
        } finally {
            restore.run();
            exec("ALTER TABLE audit_events ENABLE TRIGGER audit_events_immutable");
        }
    }
}
