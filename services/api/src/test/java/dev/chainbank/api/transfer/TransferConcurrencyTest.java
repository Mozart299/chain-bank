package dev.chainbank.api.transfer;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;

import dev.chainbank.api.ApiTestBase;

/**
 * Phase 2 exit condition: concurrent transfers preserve the ledger invariants. Money is neither
 * created nor destroyed, no account overdraws, and every journal balances.
 */
@QuarkusTest
class TransferConcurrencyTest extends ApiTestBase {

    static final long AMOUNT = 500_000;

    @Test
    void parallelSpendingCannotOverdraw() throws Exception {
        Session alice = newCustomer("spender");
        Session ann = newCustomer("spender-payee");
        sendApproved(alice, ann, AMOUNT); // known payee; all following transfers post immediately
        long start = available(alice);
        long affordable = start / AMOUNT;
        String payee = accountNumber(ann);

        List<Response> results = parallel(30, () -> send(alice, payee, AMOUNT, java.util.UUID.randomUUID().toString()));

        long confirmed = results.stream().filter(r -> "CONFIRMED".equals(r.path("state"))).count();
        long rejected = results.stream().filter(r -> "INSUFFICIENT_FUNDS".equals(r.path("failureReason"))).count();
        assertTrue(results.stream().allMatch(r -> r.statusCode() == 201), "every request got a definite answer");
        assertEquals(affordable, confirmed, "exactly the affordable transfers succeed");
        assertEquals(30 - affordable, rejected);

        assertEquals(start - affordable * AMOUNT, available(alice));
        assertEquals(0L, ((Number) account(alice).get("reservedBalance")).longValue(), "no reservation left behind");
        assertLedgerHealthy();
    }

    @Test
    void crossingTransfersDoNotDeadlockAndConserveMoney() throws Exception {
        Session a = newCustomer("cross-a");
        Session b = newCustomer("cross-b");
        sendApproved(a, b, 1_000);
        sendApproved(b, a, 1_000);
        long total = available(a) + available(b);
        String toA = accountNumber(a);
        String toB = accountNumber(b);

        List<Callable<Response>> calls = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            calls.add(() -> send(a, toB, 100_000, java.util.UUID.randomUUID().toString()));
            calls.add(() -> send(b, toA, 100_000, java.util.UUID.randomUUID().toString()));
        }
        List<Response> results = run(calls);

        assertTrue(results.stream().allMatch(r -> r.statusCode() == 201 && "CONFIRMED".equals(r.path("state"))),
                "all 40 crossing transfers confirmed");
        assertEquals(total, available(a) + available(b), "money is conserved");
        assertLedgerHealthy();
    }

    @Test
    void parallelCrossBankTransfersKeepEveryBankBalanced() throws Exception {
        Session alice = newCustomerAt("xb-alice", "BANK_A");
        Session bob = newCustomerAt("xb-bob", "BANK_B");
        sendApproved(alice, bob, 1_000);
        sendApproved(bob, alice, 1_000);
        String toA = accountNumber(alice);
        String toB = accountNumber(bob);
        long settlementA = settlement("a");
        long settlementB = settlement("b");

        List<Callable<Response>> calls = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            calls.add(() -> send(alice, toB, 50_000, java.util.UUID.randomUUID().toString()));
            calls.add(() -> send(bob, toA, 30_000, java.util.UUID.randomUUID().toString()));
        }
        List<Response> results = run(calls);
        assertTrue(results.stream().allMatch(r -> "APPROVED".equals(r.path("state"))));
        // Net flow A→B: 10 × (50,000 − 30,000) = 200,000.
        assertEquals(settlementA - 200_000, settlement("a"));
        assertEquals(settlementB + 200_000, settlement("b"));
        assertLedgerHealthy();
    }

    private void assertLedgerHealthy() {
        given().get("/q/health/ready").then().statusCode(200)
                .body("checks.find { it.name == 'ledger-invariants' }.data.unbalancedJournals", equalTo(0))
                .body("checks.find { it.name == 'ledger-invariants' }.data.driftedBalances", equalTo(0));
        assertEquals(0, queryLong("SELECT count(*) FROM account_balances WHERE reserved_minor < 0 OR "
                + "(ledger_minor - reserved_minor < 0 AND account_id IN (SELECT id FROM accounts WHERE NOT allow_negative))"));
    }

    private long settlement(String bankSuffix) {
        return queryLong("SELECT ledger_minor FROM account_balances WHERE account_id = ?::uuid",
                "00000000-0000-0000-0002-00000000000" + bankSuffix);
    }

    private static List<Response> parallel(int n, Callable<Response> call) throws Exception {
        List<Callable<Response>> calls = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            calls.add(call);
        }
        return run(calls);
    }

    private static List<Response> run(List<Callable<Response>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Response> out = new ArrayList<>();
            for (Future<Response> f : pool.invokeAll(calls)) {
                out.add(f.get());
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
