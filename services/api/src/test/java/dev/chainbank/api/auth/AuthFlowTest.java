package dev.chainbank.api.auth;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;

import dev.chainbank.api.ApiTestBase;

@QuarkusTest
class AuthFlowTest extends ApiTestBase {

    @Test
    void registerConfirmLoginMeLogout() {
        String email = uniqueEmail("flow");
        Response reg = json().body(Map.of("email", email, "password", PASSWORD, "displayName", "Flow",
                "institutionCode", "BANK_B"))
                .post("/api/auth/register").then().statusCode(201)
                .body("otpauthUri", startsWith("otpauth://totp/ChainBank"))
                .extract().response();
        String secret = reg.path("totpSecret");

        // Pending users cannot log in before proving they enrolled TOTP.
        json().body(Map.of("email", email, "password", PASSWORD, "code", nextCode(secret)))
                .post("/api/auth/login").then().statusCode(401).body("code", equalTo("INVALID_CREDENTIALS"));

        json().body(Map.of("email", email, "password", PASSWORD, "code", nextCode(secret)))
                .post("/api/auth/totp/confirm").then().statusCode(204);

        Session s = login(email, secret);
        Response login = json().body(Map.of("email", email, "password", PASSWORD, "code", nextCode(secret)))
                .post("/api/auth/login").then().statusCode(204).extract().response();
        String setCookie = login.header("Set-Cookie");
        assertTrue(setCookie.contains("HttpOnly"), setCookie);
        assertTrue(setCookie.contains("SameSite=Lax"), setCookie);

        as(s).get("/api/auth/me").then().statusCode(200)
                .body("email", equalTo(email))
                .body("roles", contains("CUSTOMER"))
                .body("csrfToken", equalTo(s.csrf()))
                .body("stepUpValidUntil", nullValue());

        as(s).post("/api/auth/logout").then().statusCode(204);
        as(s).get("/api/auth/me").then().statusCode(401).body("code", equalTo("UNAUTHENTICATED"));
    }

    @Test
    void duplicateEmailRejected() {
        Map<String, String> u = registerActive("dup", "BANK_A");
        json().body(Map.of("email", u.get("email").toUpperCase(), "password", PASSWORD, "displayName", "x",
                "institutionCode", "BANK_A"))
                .post("/api/auth/register").then().statusCode(409).body("code", equalTo("EMAIL_TAKEN"));
    }

    @Test
    void weakInputRejected() {
        json().body(Map.of("email", "not-an-email", "password", "short", "displayName", "x", "institutionCode", "BANK_A"))
                .post("/api/auth/register").then().statusCode(400);
        json().body(Map.of("email", uniqueEmail("x"), "password", PASSWORD, "displayName", "x", "institutionCode", "NOPE"))
                .post("/api/auth/register").then().statusCode(400).body("code", equalTo("UNKNOWN_INSTITUTION"));
    }

    @Test
    void wrongPasswordUnknownEmailAndWrongCodeAreIndistinguishable() {
        Map<String, String> u = registerActive("enum", "BANK_A");
        String a = json().body(Map.of("email", u.get("email"), "password", "wrong password!!", "code", nextCode(u.get("secret"))))
                .post("/api/auth/login").then().statusCode(401).extract().body().asString();
        String b = json().body(Map.of("email", uniqueEmail("ghost"), "password", PASSWORD, "code", "123456"))
                .post("/api/auth/login").then().statusCode(401).extract().body().asString();
        String c = json().body(Map.of("email", u.get("email"), "password", PASSWORD, "code", "000000"))
                .post("/api/auth/login").then().statusCode(401).extract().body().asString();
        // Same code and message; only the correlation id differs.
        for (String body : new String[] { a, b, c }) {
            assertTrue(body.contains("\"code\":\"INVALID_CREDENTIALS\""), body);
            assertTrue(body.contains("Invalid email, password or code"), body);
        }
    }

    @Test
    void totpCodeCannotBeReplayed() {
        Map<String, String> u = registerActive("replay", "BANK_A");
        String code = nextCode(u.get("secret"));
        json().body(Map.of("email", u.get("email"), "password", PASSWORD, "code", code))
                .post("/api/auth/login").then().statusCode(204);
        // Same code, same 30-second window: must be refused.
        json().body(Map.of("email", u.get("email"), "password", PASSWORD, "code", code))
                .post("/api/auth/login").then().statusCode(401);
    }

    @Test
    void bruteForceIsRateLimitedPerAccount() {
        Map<String, String> u = registerActive("brute", "BANK_A");
        for (int i = 0; i < 5; i++) {
            json().body(Map.of("email", u.get("email"), "password", "wrong password " + i, "code", "000000"))
                    .post("/api/auth/login").then().statusCode(401);
        }
        // Even the correct credentials are refused while locked.
        json().body(Map.of("email", u.get("email"), "password", PASSWORD, "code", nextCode(u.get("secret"))))
                .post("/api/auth/login").then().statusCode(429).body("code", equalTo("RATE_LIMITED"));
        // The window slides: after it passes, login works again.
        clock.advance(Duration.ofMinutes(6));
        login(u.get("email"), u.get("secret"));
    }

    @Test
    void sessionsExpireWhenIdle() {
        Session s = newCustomer("idle");
        clock.advance(Duration.ofMinutes(29));
        as(s).get("/api/auth/me").then().statusCode(200);
        clock.advance(Duration.ofMinutes(31));
        as(s).get("/api/auth/me").then().statusCode(401);
    }

    @Test
    void sessionsExpireAbsolutely() {
        Session s = newCustomer("absolute");
        for (int i = 0; i < 25; i++) { // keep it active, but past the 12h ceiling
            clock.advance(Duration.ofMinutes(29));
            as(s).get("/api/auth/me");
        }
        as(s).get("/api/auth/me").then().statusCode(401);
    }

    @Test
    void forgedOrMissingCookieIsUnauthenticated() {
        json().get("/api/auth/me").then().statusCode(401);
        json().cookie("cb_session", "A".repeat(43)).get("/api/auth/me").then().statusCode(401);
    }

    @Test
    void publicEndpointsIgnoreStaleCookies() {
        json().cookie("cb_session", "A".repeat(43)).get("/api/institutions").then().statusCode(200);
    }

    @Test
    void stepUpRequiresFreshCodeAndExpires() {
        Session s = newCustomer("stepup");
        as(s).body(Map.of("code", "000000")).post("/api/auth/step-up").then().statusCode(401);
        stepUp(s);
        as(s).get("/api/auth/me").then().body("stepUpValidUntil", notNullValue());
        clock.advance(Duration.ofMinutes(6));
        as(s).get("/api/auth/me").then().body("stepUpValidUntil", nullValue());
    }

    @Test
    void revokeAllRequiresStepUpAndKillsEverySession() {
        Map<String, String> u = registerActive("revoke", "BANK_A");
        Session phone = login(u.get("email"), u.get("secret"));
        Session laptop = login(u.get("email"), u.get("secret"));

        as(laptop).post("/api/auth/sessions/revoke-all").then().statusCode(403).body("code", equalTo("STEP_UP_REQUIRED"));
        stepUp(laptop);
        as(laptop).post("/api/auth/sessions/revoke-all").then().statusCode(204);

        as(phone).get("/api/auth/me").then().statusCode(401);
        as(laptop).get("/api/auth/me").then().statusCode(401);
    }
}
