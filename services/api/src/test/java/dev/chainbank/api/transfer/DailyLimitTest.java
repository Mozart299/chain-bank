package dev.chainbank.api.transfer;

import static org.hamcrest.Matchers.equalTo;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import dev.chainbank.api.ApiTestBase;

/** Runs with the production daily limit (5,000,000 xUGX); other tests raise it. */
@QuarkusTest
@TestProfile(DailyLimitTest.ProductionLimits.class)
class DailyLimitTest extends ApiTestBase {

    public static class ProductionLimits implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("chainbank.transfers.daily-limit", "5000000");
        }
    }

    @Test
    void sixthMillionInADayIsRejected() {
        Session alice = newCustomer("limit");
        Session ann = newCustomer("limit-payee");
        sendApproved(alice, ann, 1_000_000);
        for (int i = 0; i < 3; i++) {
            send(alice, ann, 1_000_000).then().body("state", equalTo("CONFIRMED"));
        }
        // 4,000,000 spent; 999,999 more fits, 1,000,001 would not.
        send(alice, ann, 1_000_001).then().statusCode(201)
                .body("state", equalTo("REJECTED")).body("failureReason", equalTo("DAILY_LIMIT"));
        send(alice, ann, 999_999).then().body("state", equalTo("CONFIRMED"));
    }

    @Test
    void cancelledTransfersDoNotCountTowardsTheLimit() {
        Session alice = newCustomer("limit-cancel");
        Session ann = newCustomer("limit-cancel-payee");
        String id = send(alice, ann, 4_000_000).then().extract().path("id");
        as(alice).post("/api/transfers/" + id + "/cancel").then().statusCode(200);
        sendApproved(alice, ann, 4_500_000);
    }
}
