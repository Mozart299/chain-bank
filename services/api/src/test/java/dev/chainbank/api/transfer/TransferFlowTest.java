package dev.chainbank.api.transfer;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;

import dev.chainbank.api.ApiTestBase;

@QuarkusTest
class TransferFlowTest extends ApiTestBase {

    static final long OPENING = 10_000_000L;

    @Inject
    TransferService transfers;

    @Test
    void newCustomerGetsAccountWithDemoFunds() {
        Session alice = newCustomer("opening");
        Map<String, Object> acct = account(alice);
        assertTrue(((String) acct.get("accountNumber")).startsWith("NILE-"));
        assertEquals(OPENING, ((Number) acct.get("ledgerBalance")).longValue());
        assertEquals(OPENING, available(alice));
        as(alice).get("/api/accounts/" + acct.get("id") + "/transactions").then().statusCode(200)
                .body("[0].amount", equalTo(10_000_000)).body("[0].direction", equalTo("IN"));
    }

    @Test
    void newBeneficiaryNeedsApprovalOnlyTheFirstTime() {
        Session alice = newCustomer("payer");
        Session ann = newCustomer("payee");

        Response first = send(alice, ann, 50_000).then().statusCode(201)
                .body("state", equalTo("VALIDATED")).body("requiresStepUp", equalTo(true)).extract().response();
        assertEquals(OPENING - 50_000, available(alice), "funds reserved while waiting");
        assertEquals(OPENING, available(ann), "nothing arrives before approval");

        approveTransfer(alice, first.path("id")).then().statusCode(200)
                .body("state", equalTo("CONFIRMED"))
                .body("events.toState", equalTo(List.of("REQUESTED", "VALIDATED", "APPROVED", "CONFIRMED")));
        assertEquals(OPENING + 50_000, available(ann));

        // Known payee, small amount: posts immediately.
        send(alice, ann, 20_000).then().statusCode(201).body("state", equalTo("CONFIRMED"))
                .body("requiresStepUp", equalTo(false));
        assertEquals(OPENING - 70_000, available(alice));
    }

    @Test
    void largeAmountsNeedApprovalEvenForKnownPayees() {
        Session alice = newCustomer("big");
        Session ann = newCustomer("big-payee");
        sendApproved(alice, ann, 1_000);
        send(alice, ann, 1_000_000).then().body("state", equalTo("CONFIRMED")); // at threshold: fine
        send(alice, ann, 1_000_001).then().body("state", equalTo("VALIDATED")).body("requiresStepUp", equalTo(true));
    }

    @Test
    void approvalCodesMustBeValidAndFresh() {
        Session alice = newCustomer("codes");
        Session ann = newCustomer("codes-payee");
        String id = send(alice, ann, 5_000).then().extract().path("id");
        as(alice).body(Map.of("code", "000000")).post("/api/transfers/" + id + "/approve")
                .then().statusCode(401).body("code", equalTo("INVALID_CODE"));
        // A code already used for login cannot approve a transfer.
        String usedCode = nextCode(alice.totpSecret());
        stepUpWith(alice, usedCode);
        as(alice).body(Map.of("code", usedCode)).post("/api/transfers/" + id + "/approve").then().statusCode(401);
        approveTransfer(alice, id).then().statusCode(200);
        approveTransfer(alice, id).then().statusCode(409).body("code", equalTo("NOT_AWAITING_APPROVAL"));
    }

    private void stepUpWith(Session s, String code) {
        as(s).body(Map.of("code", code)).post("/api/auth/step-up").then().statusCode(204);
    }

    @Test
    void cancelReleasesReservedFunds() {
        Session alice = newCustomer("cancel");
        Session ann = newCustomer("cancel-payee");
        String id = send(alice, ann, 300_000).then().extract().path("id");
        assertEquals(OPENING - 300_000, available(alice));
        as(alice).post("/api/transfers/" + id + "/cancel").then().statusCode(200)
                .body("state", equalTo("CANCELLED")).body("failureReason", equalTo("CANCELLED_BY_CUSTOMER"));
        assertEquals(OPENING, available(alice));
        approveTransfer(alice, id).then().statusCode(409);
        as(alice).post("/api/transfers/" + id + "/cancel").then().statusCode(409);
    }

    @Test
    void unapprovedTransfersExpireAndReleaseFunds() {
        Session alice = newCustomer("expire");
        Session ann = newCustomer("expire-payee");
        String id = send(alice, ann, 400_000).then().extract().path("id");
        clock.advance(Duration.ofMinutes(16));
        assertTrue(transfers.expireDue() >= 1);
        as(alice).get("/api/transfers/" + id).then().body("state", equalTo("CANCELLED"))
                .body("failureReason", equalTo("EXPIRED"));
        assertEquals(OPENING, available(alice));
    }

    @Test
    void approvingAfterExpiryIsRefused() {
        Session alice = newCustomer("late");
        Session ann = newCustomer("late-payee");
        String id = send(alice, ann, 400_000).then().extract().path("id");
        clock.advance(Duration.ofMinutes(16));
        approveTransfer(alice, id).then().statusCode(410).body("code", equalTo("TRANSFER_EXPIRED"));
        assertEquals(OPENING, available(alice));
    }

    @Test
    void insufficientFundsIsRejectedAndRecorded() {
        Session alice = newCustomer("broke");
        Session ann = newCustomer("broke-payee");
        send(alice, ann, OPENING + 1).then().statusCode(201)
                .body("state", equalTo("REJECTED")).body("failureReason", equalTo("INSUFFICIENT_FUNDS"));
        assertEquals(OPENING, available(alice));
    }

    @Test
    void validationErrors() {
        Session alice = newCustomer("invalid");
        send(alice, "NILE-00000000", 1_000, UUID.randomUUID().toString()).then().statusCode(404);
        send(alice, accountNumber(alice), 1_000, UUID.randomUUID().toString())
                .then().statusCode(400).body("code", equalTo("SAME_ACCOUNT"));
        send(alice, accountNumber(newCustomer("x")), 0, UUID.randomUUID().toString()).then().statusCode(400);
    }

    @Test
    void crossBankTransferBalancesPerBankAndQueuesSettlement() {
        Session alice = newCustomerAt("cb-alice", "BANK_A");
        Session bob = newCustomerAt("cb-bob", "BANK_B");
        assertTrue(accountNumber(bob).startsWith("RWZ-"));
        long nileSettlementBefore = settlement("a");
        long rwzSettlementBefore = settlement("b");

        Response r = sendApproved(alice, bob, 75_000);
        assertEquals("APPROVED", r.path("state"), "cross-bank transfers wait for on-chain settlement (Phase 3)");
        assertEquals(true, r.path("crossBank"));
        String id = r.path("id");

        assertEquals(4, queryLong("SELECT count(*) FROM journal_lines l JOIN journals j ON j.id = l.journal_id "
                + "WHERE j.transfer_id = ?::uuid", id));
        assertEquals(1, queryLong("SELECT count(*) FROM outbox WHERE aggregate_id = ?::uuid "
                + "AND event_type = 'SETTLEMENT_REQUESTED' AND status = 'PENDING'", id));
        assertEquals(nileSettlementBefore - 75_000, settlement("a"));
        assertEquals(rwzSettlementBefore + 75_000, settlement("b"));
        assertEquals(OPENING + 75_000, available(bob));
        as(bob).get("/api/transfers").then().body("id", hasItem(id));
        as(bob).get("/api/transfers/" + id).then().statusCode(200).body("direction", equalTo("INCOMING"));
    }

    @Test
    void payeeCannotSeeTransferBeforeItPosts() {
        Session alice = newCustomer("hidden");
        Session ann = newCustomer("hidden-payee");
        String id = send(alice, ann, 10_000).then().extract().path("id");
        as(ann).get("/api/transfers/" + id).then().statusCode(404);
        as(ann).get("/api/transfers").then().body("id", not(hasItem(id)));
    }

    @Test
    void flaggedCrossBankTransferNeedsOperatorReview() {
        Session alice = newCustomerAt("flag-alice", "BANK_A");
        Session bob = newCustomerAt("flag-bob", "BANK_B");
        Session nileOps = newUserWithRole("nile-ops", "BANK_OPERATOR");
        Session rwzOps = newOperatorAt("rwz-ops", "BANK_B");

        String id = send(alice, bob, 3_000_000).then().statusCode(201)
                .body("requiresStepUp", equalTo(true)).body("requiresReview", equalTo(true)).extract().path("id");
        approveTransfer(alice, id).then().statusCode(200).body("state", equalTo("VALIDATED"))
                .body("stepUpApproved", equalTo(true)).body("operatorApproved", equalTo(false));

        as(nileOps).get("/api/operator/reviews").then().statusCode(200).body("id", hasItem(id));
        as(rwzOps).get("/api/operator/reviews").then().body("id", not(hasItem(id)));
        as(alice).get("/api/operator/reviews").then().statusCode(403);

        review(nileOps, id, "APPROVE").then().statusCode(403).body("code", equalTo("STEP_UP_REQUIRED"));
        stepUp(rwzOps);
        review(rwzOps, id, "APPROVE").then().statusCode(404); // other bank's operator
        stepUp(nileOps);
        review(nileOps, id, "APPROVE").then().statusCode(200).body("state", equalTo("APPROVED"));
        assertEquals(OPENING + 3_000_000, available(bob));
    }

    @Test
    void operatorCanRejectFlaggedTransfer() {
        Session alice = newCustomerAt("rej-alice", "BANK_A");
        Session bob = newCustomerAt("rej-bob", "BANK_B");
        Session ops = newUserWithRole("rej-ops", "BANK_OPERATOR");
        String id = send(alice, bob, 2_500_000).then().extract().path("id");
        stepUp(ops);
        review(ops, id, "REJECT").then().statusCode(200)
                .body("state", equalTo("REJECTED")).body("failureReason", equalTo("OPERATOR_REJECTED"));
        assertEquals(OPENING, available(alice));
        approveTransfer(alice, id).then().statusCode(409);
    }

    private Response review(Session ops, String id, String decision) {
        return as(ops).body(Map.of("decision", decision)).post("/api/operator/transfers/" + id + "/review");
    }

    private Session newOperatorAt(String prefix, String institution) {
        Map<String, String> u = registerActive(prefix, institution);
        exec("DELETE FROM user_roles WHERE user_id = ?::uuid", u.get("userId"));
        exec("INSERT INTO user_roles (user_id, role) VALUES (?::uuid, 'BANK_OPERATOR')", u.get("userId"));
        return login(u.get("email"), u.get("secret"));
    }

    private long settlement(String bankSuffix) {
        return queryLong("SELECT ledger_minor FROM account_balances WHERE account_id = ?::uuid",
                "00000000-0000-0000-0002-00000000000" + bankSuffix);
    }
}
