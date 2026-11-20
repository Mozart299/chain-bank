package dev.chainbank.api.transfer;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;

import dev.chainbank.api.ApiTestBase;

/** Spec section 11: "Duplicate transfer or network retry → one journal posting only". */
@QuarkusTest
class IdempotencyTest extends ApiTestBase {

    @Test
    void retryWithSameKeyReturnsOriginalWithoutMovingMoneyAgain() {
        Session alice = newCustomer("retry");
        Session ann = newCustomer("retry-payee");
        sendApproved(alice, ann, 1_000); // make Ann a known payee so transfers post immediately
        long before = available(alice);

        String key = UUID.randomUUID().toString();
        Response first = send(alice, accountNumber(ann), 25_000, key).then().statusCode(201)
                .header("Idempotent-Replayed", "false").body("state", equalTo("CONFIRMED")).extract().response();
        send(alice, accountNumber(ann), 25_000, key).then().statusCode(200)
                .header("Idempotent-Replayed", "true")
                .body("id", equalTo(first.path("id")))
                .body("state", equalTo("CONFIRMED"));

        assertEquals(before - 25_000, available(alice), "charged exactly once");
        String id = first.path("id");
        assertEquals(1, queryLong("SELECT count(*) FROM journals WHERE transfer_id = ?::uuid", id));
    }

    @Test
    void sameKeyWithDifferentBodyIsRefused() {
        Session alice = newCustomer("reuse");
        Session ann = newCustomer("reuse-payee");
        String key = UUID.randomUUID().toString();
        send(alice, accountNumber(ann), 10_000, key).then().statusCode(201);
        send(alice, accountNumber(ann), 99_999, key).then().statusCode(422).body("code", equalTo("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void keyIsRequired() {
        Session alice = newCustomer("nokey");
        as(alice).body(Map.of("sourceAccountId", accountId(alice), "destinationAccountNumber", "NILE-1",
                "amount", 1, "currency", "xUGX"))
                .post("/api/transfers").then().statusCode(400).body("code", equalTo("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void keysAreScopedPerUser() {
        Session alice = newCustomer("scope-a");
        Session bob = newCustomer("scope-b");
        Session ann = newCustomer("scope-payee");
        String key = UUID.randomUUID().toString();
        String a = send(alice, accountNumber(ann), 1_000, key).then().statusCode(201).extract().path("id");
        String b = send(bob, accountNumber(ann), 1_000, key).then().statusCode(201).extract().path("id");
        assertEquals(false, a.equals(b));
    }

    @Test
    void concurrentDuplicatesCreateExactlyOneTransferAndOneJournal() throws Exception {
        Session alice = newCustomer("dupes");
        Session ann = newCustomer("dupes-payee");
        sendApproved(alice, ann, 1_000);
        long before = available(alice);
        String key = UUID.randomUUID().toString();
        String payee = accountNumber(ann);

        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Response> responses = new ArrayList<>();
        try {
            List<Callable<Response>> calls = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                calls.add(() -> send(alice, payee, 40_000, key));
            }
            for (Future<Response> f : pool.invokeAll(calls)) {
                responses.add(f.get());
            }
        } finally {
            pool.shutdownNow();
        }
        Set<String> ids = new HashSet<>();
        int created = 0;
        for (Response r : responses) {
            assertEquals(true, r.statusCode() == 201 || r.statusCode() == 200, "status " + r.statusCode());
            if (r.statusCode() == 201) {
                created++;
            }
            ids.add(r.path("id"));
        }
        assertEquals(1, created, "exactly one request created the transfer");
        assertEquals(1, ids.size(), "every response names the same transfer");
        assertEquals(1, queryLong("SELECT count(*) FROM journals WHERE transfer_id = ?::uuid", ids.iterator().next()));
        assertEquals(before - 40_000, available(alice));
    }
}
