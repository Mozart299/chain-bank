package dev.chainbank.api.auth;

import static org.hamcrest.Matchers.equalTo;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

import dev.chainbank.api.ApiTestBase;

/** Unauthorized-access tests: CSRF, origin, role separation, suspension. */
@QuarkusTest
class AuthorizationTest extends ApiTestBase {

    @Test
    void mutatingRequestWithoutCsrfTokenIsRejected() {
        Session s = newCustomer("csrf");
        json().cookie("cb_session", s.cookie())
                .post("/api/auth/logout").then().statusCode(403).body("code", equalTo("CSRF_TOKEN_INVALID"));
        json().cookie("cb_session", s.cookie()).header("X-CSRF-Token", "forged")
                .post("/api/auth/logout").then().statusCode(403).body("code", equalTo("CSRF_TOKEN_INVALID"));
        // The token from another session does not work either.
        Session other = newCustomer("csrf-other");
        json().cookie("cb_session", s.cookie()).header("X-CSRF-Token", other.csrf())
                .post("/api/auth/logout").then().statusCode(403);
        as(s).get("/api/auth/me").then().statusCode(200); // still logged in
    }

    @Test
    void foreignOriginIsRejected() {
        Session s = newCustomer("origin");
        as(s).header("Origin", "https://evil.example")
                .post("/api/auth/logout").then().statusCode(403).body("code", equalTo("ORIGIN_NOT_ALLOWED"));
        json().header("Origin", "https://evil.example")
                .body(Map.of("email", s.email(), "password", PASSWORD, "code", "000000"))
                .post("/api/auth/login").then().statusCode(403);
        as(s).header("Origin", "http://localhost:3000").post("/api/auth/logout").then().statusCode(204);
    }

    @Test
    void customerCannotUseAdminOrAuditEndpoints() {
        Session customer = newCustomer("nosy");
        stepUp(customer);
        as(customer).post("/api/admin/users/" + customer.userId() + "/suspend").then().statusCode(403);
        as(customer).get("/api/audit/chain/verify").then().statusCode(403);
    }

    @Test
    void auditorIsReadOnly() {
        Session auditor = newUserWithRole("auditor", "AUDITOR");
        as(auditor).get("/api/audit/chain/verify").then().statusCode(200);
        as(auditor).body(Map.of("address", "0x" + "a".repeat(40), "chainId", 31337))
                .post("/api/wallet/challenge").then().statusCode(403);
        as(auditor).post("/api/admin/users/" + auditor.userId() + "/suspend").then().statusCode(403);
    }

    @Test
    void anonymousCannotReachProtectedEndpoints() {
        json().get("/api/wallet").then().statusCode(401);
        json().get("/api/audit/chain/verify").then().statusCode(401);
        json().post("/api/admin/users/00000000-0000-0000-0000-000000000000/suspend").then().statusCode(401);
    }

    @Test
    void adminSuspensionNeedsStepUpAndRevokesLiveSessions() {
        Session admin = newUserWithRole("admin", "SYSTEM_ADMIN");
        Session victim = newCustomer("suspended");

        as(admin).post("/api/admin/users/" + victim.userId() + "/suspend")
                .then().statusCode(403).body("code", equalTo("STEP_UP_REQUIRED"));
        stepUp(admin);
        as(admin).post("/api/admin/users/" + admin.userId() + "/suspend")
                .then().statusCode(400).body("code", equalTo("CANNOT_SUSPEND_SELF"));
        as(admin).post("/api/admin/users/" + victim.userId() + "/suspend").then().statusCode(204);

        as(victim).get("/api/auth/me").then().statusCode(401);
        json().body(Map.of("email", victim.email(), "password", PASSWORD, "code", nextCode(victim.totpSecret())))
                .post("/api/auth/login").then().statusCode(401);
    }
}
