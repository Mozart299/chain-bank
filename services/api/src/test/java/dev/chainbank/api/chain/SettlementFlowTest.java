package dev.chainbank.api.chain;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.methods.response.VoidResponse;
import org.web3j.protocol.http.HttpService;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;

import dev.chainbank.api.ApiTestBase;

/**
 * Phase 3 exit condition: transfers reconcile with contract events. Runs against a real Anvil node,
 * driving the worker step by step (the scheduler is off in tests).
 */
@QuarkusTest
class SettlementFlowTest extends ApiTestBase {

    @Inject
    SettlementWorker worker;

    @Inject
    SettlementReconciler reconciler;

    @Inject
    SettlementDeployment deployment;

    @Inject
    ChainClient chain;

    final HttpService rpc = new HttpService(AnvilResource.rpcUrl());

    @BeforeEach
    void deployed() {
        assertTrue(deployment.ensureDeployed().isPresent());
    }

    // --- happy path ------------------------------------------------------------------------------

    @Test
    void crossBankTransferSettlesOnChainAndReconciles() {
        Session alice = newCustomerAt("set-alice", "BANK_A");
        Session bob = newCustomerAt("set-bob", "BANK_B");
        String id = sendApproved(alice, bob, 75_000).path("id");
        assertEquals("APPROVED", state(alice, id));

        worker.submit();
        Response afterSubmit = as(alice).get("/api/transfers/" + id).then().extract().response();
        assertEquals("SUBMITTED", afterSubmit.path("state"));
        String txHash = afterSubmit.path("settlement.txHash");
        assertTrue(txHash.startsWith("0x") && txHash.length() == 66, txHash);

        confirm(); // mined in one block: 1 of 2 confirmations
        as(alice).get("/api/transfers/" + id).then()
                .body("state", equalTo("SUBMITTED"))
                .body("settlement.status", equalTo("MINED"))
                .body("settlement.confirmations", equalTo(1))
                .body("settlement.required", equalTo(2));

        mine(1);
        confirm();
        assertEquals("CONFIRMED", state(alice, id));

        reconciler.index();
        reconciler.reconcile();
        as(alice).get("/api/transfers/" + id).then()
                .body("state", equalTo("RECONCILED"))
                .body("settlement.status", equalTo("FINAL"))
                .body("events.toState", hasItem("RECONCILED"));
        assertEquals(1, queryLong("SELECT count(*) FROM chain_events WHERE settlement_id = ?",
                SettlementAbi.settlementId(UUID.fromString(id))));
        assertPositionsMatch();
    }

    @Test
    void sameBankTransfersNeverTouchTheChain() {
        Session a = newCustomer("local-a");
        Session b = newCustomer("local-b");
        String id = sendApproved(a, b, 10_000).path("id");
        assertEquals("CONFIRMED", state(a, id));
        assertEquals(0, queryLong("SELECT count(*) FROM outbox WHERE aggregate_id = ?::uuid", id));
        assertEquals(0, queryLong("SELECT count(*) FROM chain_txs WHERE transfer_id = ?::uuid", id));
    }

    // --- failure handling ------------------------------------------------------------------------

    @Test
    void duplicateSubmissionRevertsOnChainAndMovesNothing() {
        Session alice = newCustomerAt("dup-alice", "BANK_A");
        Session bob = newCustomerAt("dup-bob", "BANK_B");
        String id = sendApproved(alice, bob, 33_000).path("id");
        settleFully();
        assertEquals("RECONCILED", state(alice, id));
        BigInteger walletB = onChainBalance("BANK_B");

        // A duplicate outbox row for the same transfer (e.g. a replayed message).
        exec("""
                INSERT INTO outbox (aggregate_type, aggregate_id, event_type, payload)
                SELECT aggregate_type, aggregate_id, event_type, payload FROM outbox WHERE aggregate_id = ?::uuid LIMIT 1""", id);
        worker.submit();
        mine(1);
        confirm();

        assertEquals(walletB, onChainBalance("BANK_B"), "no second movement on-chain");
        assertEquals("RECONCILED", state(alice, id));
        assertEquals(1, queryLong("SELECT count(*) FROM chain_txs WHERE transfer_id = ?::uuid AND status = 'REVERTED'", id));
        assertEquals(1, queryLong("SELECT count(*) FROM reconciliation_exceptions WHERE transfer_id = ?::uuid "
                + "AND kind = 'DUPLICATE' AND status = 'RESOLVED'", id));
    }

    @Test
    void crashBetweenRecordAndBroadcastIsRecoveredByRebroadcast() {
        Session alice = newCustomerAt("crash-alice", "BANK_A");
        Session bob = newCustomerAt("crash-bob", "BANK_B");
        String id = sendApproved(alice, bob, 21_000).path("id");
        String contract = deployment.address().orElseThrow();
        var settler = chain.settler().orElseThrow();

        // Reproduce exactly what a crash after commit but before broadcast leaves behind.
        String settlementId = SettlementAbi.settlementId(UUID.fromString(id));
        ChainClient.Signed signed = chain.sign(settler, contract,
                SettlementAbi.settle(settlementId, "INST-A", "INST-B", BigInteger.valueOf(21_000)),
                SettlementWorker.SETTLE_GAS, chain.pendingNonce(settler.getAddress()));
        exec("UPDATE transfers SET settlement_id = ?, state = 'SUBMITTED' WHERE id = ?::uuid", settlementId, id);
        exec("UPDATE outbox SET status = 'PUBLISHED' WHERE aggregate_id = ?::uuid", id);
        exec("""
                INSERT INTO chain_txs (outbox_id, transfer_id, chain_id, tx_hash, status, settlement_id, nonce, raw_tx,
                                       attempts, last_checked_at)
                SELECT o.id, ?::uuid, 31337, ?, 'SUBMITTED', ?, ?, ?, 1, now() - interval '1 hour'
                FROM outbox o WHERE o.aggregate_id = ?::uuid""",
                id, signed.txHash(), settlementId, signed.nonce().longValue(), signed.raw(), id);

        confirm(); // no receipt, overdue: rebroadcast the stored transaction
        mine(1);
        confirm();
        assertEquals("CONFIRMED", state(alice, id));
    }

    @Test
    void chainOutageBacksOffThenSettles() {
        Session alice = newCustomerAt("outage-alice", "BANK_A");
        Session bob = newCustomerAt("outage-bob", "BANK_B");
        String id = sendApproved(alice, bob, 12_000).path("id");

        var docker = DockerClientFactory.instance().client();
        String containerId = AnvilResource.container().getContainerId();
        docker.pauseContainerCmd(containerId).exec();
        try {
            worker.submit();
        } finally {
            docker.unpauseContainerCmd(containerId).exec();
        }
        assertEquals("APPROVED", state(alice, id), "nothing recorded while the chain is down");
        assertEquals(1, queryLong("SELECT attempts FROM outbox WHERE aggregate_id = ?::uuid", id));
        assertEquals(1, queryLong("SELECT count(*) FROM outbox WHERE aggregate_id = ?::uuid AND status = 'PENDING' "
                + "AND last_error LIKE 'RPC_UNAVAILABLE%'", id));

        clock.advance(Duration.ofSeconds(5)); // past the backoff
        settleFully();
        assertEquals("RECONCILED", state(alice, id));
    }

    @Test
    void reorgIsDetectedInvestigatedAndRecovered() throws IOException {
        Session alice = newCustomerAt("reorg-alice", "BANK_A");
        Session bob = newCustomerAt("reorg-bob", "BANK_B");
        String id = sendApproved(alice, bob, 9_000).path("id");
        worker.submit();
        confirm();
        assertEquals("MINED", queryString("SELECT status FROM chain_txs WHERE transfer_id = ?::uuid", id));

        // Replace the block holding the settlement with one that does not contain it.
        new Request<>("anvil_reorg", List.of(1, List.of()), rpc, VoidResponse.class).send();
        confirm();

        long reorgs = queryLong("SELECT count(*) FROM reconciliation_exceptions WHERE transfer_id = ?::uuid AND kind = 'REORG'", id);
        assertTrue(reorgs >= 1, "the reorg was recorded");
        String stateAfterReorg = state(alice, id);
        assertTrue(List.of("PENDING_INVESTIGATION", "SUBMITTED").contains(stateAfterReorg), stateAfterReorg);

        mine(2);
        confirm();
        mine(1);
        confirm();
        assertEquals("CONFIRMED", state(alice, id));
        assertEquals(0, queryLong("SELECT count(*) FROM reconciliation_exceptions WHERE transfer_id = ?::uuid "
                + "AND kind = 'REORG' AND status <> 'RESOLVED'", id), "reorg exception resolved once final");
        reconciler.index();
        reconciler.reconcile();
        assertEquals("RECONCILED", state(alice, id));
    }

    // --- governance ------------------------------------------------------------------------------

    @Test
    void emergencyPauseHoldsSettlementUntilUnpaused() {
        Session admin = newUserWithRole("chain-admin", "SYSTEM_ADMIN");
        Session operator = newUserWithRole("chain-ops", "BANK_OPERATOR");
        Session alice = newCustomerAt("pause-alice", "BANK_A");
        Session bob = newCustomerAt("pause-bob", "BANK_B");

        as(admin).post("/api/admin/chain/pause").then().statusCode(403).body("code", equalTo("STEP_UP_REQUIRED"));
        stepUp(operator);
        as(operator).post("/api/admin/chain/pause").then().statusCode(403);
        as(alice).post("/api/admin/chain/pause").then().statusCode(403);

        stepUp(admin);
        as(admin).post("/api/admin/chain/pause").then().statusCode(200).body("paused", equalTo(true));
        try {
            String id = sendApproved(alice, bob, 5_000).path("id");
            worker.submit();
            assertEquals("APPROVED", state(alice, id), "held while paused");
            assertEquals(1, queryLong("SELECT count(*) FROM outbox WHERE aggregate_id = ?::uuid AND status = 'PENDING' "
                    + "AND last_error = 'CONTRACT_PAUSED' AND attempts = 0", id));
        } finally {
            as(admin).post("/api/admin/chain/unpause").then().statusCode(200).body("paused", equalTo(false));
        }
        clock.advance(Duration.ofSeconds(6));
        settleFully();
        assertEquals(2, queryLong("SELECT count(*) FROM audit_events WHERE action IN ('CHAIN_PAUSED', 'CHAIN_UNPAUSED') "
                + "AND actor_user_id = ?::uuid", admin.userId().toString()), "pause and unpause are audited");
    }

    @Test
    void rogueOnChainSettlementIsFlaggedAndPositionsDisagree() {
        Session auditor = newUserWithRole("recon-auditor", "AUDITOR");
        settleFully();
        String contract = deployment.address().orElseThrow();
        var settler = chain.settler().orElseThrow();
        String rogueId = SettlementAbi.settlementId(UUID.randomUUID());

        chain.transactAndWait(settler, contract, SettlementAbi.settle(rogueId, "INST-A", "INST-B", BigInteger.valueOf(777)),
                SettlementWorker.SETTLE_GAS);
        mine(1);
        reconciler.index();
        reconciler.reconcile();

        as(auditor).get("/api/reconciliation/exceptions?open=true").then().statusCode(200)
                .body("kind", hasItem("MISSING_IN_JOURNAL"));
        List<Map<String, Object>> positions = as(auditor).get("/api/settlement/positions").then().statusCode(200)
                .extract().jsonPath().getList("$");
        assertFalse(positions.stream().allMatch(p -> Boolean.TRUE.equals(p.get("matched"))), "mismatch visible");

        // Compensate on-chain so later tests see matching positions again (both legs stay flagged).
        chain.transactAndWait(settler, contract, SettlementAbi.settle(SettlementAbi.settlementId(UUID.randomUUID()),
                "INST-B", "INST-A", BigInteger.valueOf(777)), SettlementWorker.SETTLE_GAS);
        mine(1);
        reconciler.index();
        reconciler.reconcile();
        assertPositionsMatch();
    }

    @Test
    void settlementEndpointsAreStaffOnly() {
        Session customer = newCustomer("nosy-customer");
        as(customer).get("/api/settlement/positions").then().statusCode(403);
        as(customer).get("/api/reconciliation/exceptions").then().statusCode(403);
        as(customer).get("/api/admin/chain").then().statusCode(403);
        json().get("/api/settlement/positions").then().statusCode(401);
        Session operator = newUserWithRole("pos-ops", "BANK_OPERATOR");
        as(operator).get("/api/settlement/positions").then().statusCode(200).body("[0].pseudonym", startsWith("INST-"));
    }

    @Test
    void chainHealthGroupReportsContract() {
        io.restassured.RestAssured.get("/q/health/group/chain").then().statusCode(200)
                .body("checks[0].data.contract", startsWith("0x"))
                .body("checks[0].data.paused", equalTo(false));
    }

    // --- helpers ---------------------------------------------------------------------------------

    /**
     * Anvil mines asynchronously: eth_sendRawTransaction returns before the block exists. Wait until
     * every open settlement transaction has a receipt (or 5 s pass), then run the confirmer once.
     */
    private void confirm() {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            boolean allMined = true;
            try (var c = dataSource.getConnection(); var ps = c.prepareStatement(
                    "SELECT tx_hash FROM chain_txs WHERE status IN ('SUBMITTED', 'MINED', 'REORGED')"); var rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (chain.receipt(rs.getString(1)).isEmpty()) {
                        allMined = false;
                    }
                }
            } catch (java.sql.SQLException e) {
                throw new RuntimeException(e);
            }
            if (allMined) {
                break;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        worker.confirm();
    }

    private void settleFully() {
        worker.submit();
        mine(1);
        confirm();
        mine(1);
        confirm();
        reconciler.index();
        reconciler.reconcile();
    }

    private void assertPositionsMatch() {
        for (SettlementReconciler.Position p : reconciler.positions()) {
            assertTrue(p.matched(), () -> "position " + p + "\n" + crossBankDump());
        }
    }

    /** Failure diagnostics: every cross-bank transfer with its settlement evidence. */
    private String crossBankDump() {
        StringBuilder sb = new StringBuilder();
        try (var c = dataSource.getConnection(); var ps = c.prepareStatement("""
                SELECT t.id, t.state, t.amount_minor, t.settlement_id IS NOT NULL,
                       (SELECT string_agg(e.amount::text || '@' || e.block_number, ',') FROM chain_events e WHERE e.settlement_id = t.settlement_id),
                       (SELECT string_agg(x.status, ',') FROM chain_txs x WHERE x.transfer_id = t.id),
                       (SELECT string_agg(o.status, ',') FROM outbox o WHERE o.aggregate_id = t.id)
                FROM transfers t WHERE t.source_institution_id <> t.destination_institution_id
                  AND t.state NOT IN ('REJECTED', 'CANCELLED') ORDER BY t.created_at"""); var rs = ps.executeQuery()) {
            while (rs.next()) {
                sb.append(rs.getString(2)).append(' ').append(rs.getLong(3)).append(" sid=").append(rs.getBoolean(4))
                        .append(" events=").append(rs.getString(5)).append(" txs=").append(rs.getString(6))
                        .append(" outbox=").append(rs.getString(7)).append('\n');
            }
        } catch (java.sql.SQLException e) {
            sb.append(e);
        }
        return sb.toString();
    }

    private BigInteger onChainBalance(String bank) {
        String wallet = deployment.institutions().stream().filter(i -> i.code().equals(bank)).findFirst().orElseThrow().wallet();
        return SettlementAbi.decodeUint(chain.call(deployment.address().orElseThrow(), SettlementAbi.balanceOfCall(wallet)));
    }

    private String state(Session s, String id) {
        return as(s).get("/api/transfers/" + id).then().statusCode(200).extract().path("state");
    }

    private void mine(int blocks) {
        try {
            for (int i = 0; i < blocks; i++) {
                new Request<>("evm_mine", List.of(), rpc, VoidResponse.class).send();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String queryString(String sql, Object... args) {
        try (var c = dataSource.getConnection(); var ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
