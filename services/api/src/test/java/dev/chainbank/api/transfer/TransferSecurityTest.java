package dev.chainbank.api.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

import dev.chainbank.api.ApiTestBase;

/** Broken object authorization (spec section 11): other users' accounts and transfers behave as missing. */
@QuarkusTest
class TransferSecurityTest extends ApiTestBase {

    @Test
    void cannotSendFromSomeoneElsesAccount() {
        Session alice = newCustomer("victim");
        Session mallory = newCustomer("thief");
        as(mallory).header("Idempotency-Key", UUID.randomUUID().toString())
                .body(Map.of("sourceAccountId", accountId(alice), "destinationAccountNumber", accountNumber(mallory),
                        "amount", 1_000, "currency", "xUGX"))
                .post("/api/transfers").then().statusCode(404);
        assertEquals(10_000_000L, available(alice));
    }

    @Test
    void cannotReadApproveOrCancelSomeoneElsesTransfer() {
        Session alice = newCustomer("owner");
        Session ann = newCustomer("owner-payee");
        Session mallory = newCustomer("snoop");
        String id = send(alice, ann, 10_000).then().extract().path("id");

        as(mallory).get("/api/transfers/" + id).then().statusCode(404);
        as(mallory).body(Map.of("code", nextCode(mallory.totpSecret()))).post("/api/transfers/" + id + "/approve")
                .then().statusCode(404);
        as(mallory).post("/api/transfers/" + id + "/cancel").then().statusCode(404);
        as(alice).get("/api/transfers/" + id).then().statusCode(200);
    }

    @Test
    void cannotReadSomeoneElsesHistory() {
        Session alice = newCustomer("history");
        Session mallory = newCustomer("history-snoop");
        as(mallory).get("/api/accounts/" + accountId(alice) + "/transactions").then().statusCode(404);
    }

    @Test
    void staffWithoutCustomerRoleCannotTransfer() {
        Session auditor = newUserWithRole("auditor-tx", "AUDITOR");
        as(auditor).get("/api/accounts").then().statusCode(403);
        as(auditor).header("Idempotency-Key", UUID.randomUUID().toString())
                .body(Map.of("sourceAccountId", UUID.randomUUID(), "destinationAccountNumber", "NILE-1",
                        "amount", 1, "currency", "xUGX"))
                .post("/api/transfers").then().statusCode(403);
    }

    @Test
    void anonymousIsUnauthenticated() {
        json().get("/api/accounts").then().statusCode(401);
        json().get("/api/transfers").then().statusCode(401);
    }
}
