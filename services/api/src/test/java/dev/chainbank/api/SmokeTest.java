package dev.chainbank.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class SmokeTest {

    @Test
    void readinessIncludesLedgerInvariants() {
        given().when().get("/q/health/ready")
                .then().statusCode(200)
                .body("status", equalTo("UP"))
                .body("checks.find { it.name == 'ledger-invariants' }.data.unbalancedJournals", equalTo(0));
    }

    @Test
    void twoSeededBanks() {
        given().when().get("/api/institutions")
                .then().statusCode(200)
                .body("$", hasSize(2))
                .body("code", contains("BANK_A", "BANK_B"))
                .header("X-Correlation-Id", notNullValue());
    }

    @Test
    void correlationIdIsEchoed() {
        given().header("X-Correlation-Id", "test-correlation-1234")
                .when().get("/api/institutions")
                .then().header("X-Correlation-Id", equalTo("test-correlation-1234"));
    }

    @Test
    void unknownRouteReturnsConsistentError() {
        given().when().get("/api/does-not-exist")
                .then().statusCode(404)
                .body("code", equalTo("NOT_FOUND"))
                .body("correlationId", notNullValue());
    }
}
