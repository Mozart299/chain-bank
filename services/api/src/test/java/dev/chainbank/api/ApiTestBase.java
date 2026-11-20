package dev.chainbank.api;

import static io.restassured.RestAssured.given;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;

import io.quarkus.test.junit.QuarkusMock;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import dev.chainbank.api.auth.Totp;
import dev.chainbank.api.platform.AppClock;

/** Shared helpers: a controllable clock and real register → confirm → login flows over HTTP. */
public abstract class ApiTestBase {

    public static final String PASSWORD = "correct horse battery staple";

    /** Movable clock: lets tests mint fresh TOTP codes and expire sessions/challenges deterministically. */
    public static class TestClock extends AppClock {
        private Duration offset = Duration.ZERO;

        @Override
        public Instant now() {
            return Instant.now().plus(offset).truncatedTo(ChronoUnit.MICROS);
        }

        public void advance(Duration d) {
            offset = offset.plus(d);
        }
    }

    /** A logged-in browser: session cookie plus the CSRF token the page would hold. */
    public record Session(UUID userId, String email, String totpSecret, String cookie, String csrf) {
    }

    @Inject
    protected DataSource dataSource;

    protected TestClock clock;

    @BeforeEach
    void installClock() {
        clock = new TestClock();
        QuarkusMock.installMockForType(clock, AppClock.class);
    }

    /** Next TOTP code: moves time forward one step so each code is fresh (codes are single-use). */
    protected String nextCode(String totpSecret) {
        clock.advance(Duration.ofSeconds(Totp.STEP_SECONDS));
        return Totp.code(Totp.base32Decode(totpSecret), Totp.step(clock.now()));
    }

    protected static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
    }

    protected static RequestSpecification json() {
        return given().contentType(ContentType.JSON);
    }

    protected static RequestSpecification as(Session s) {
        return json().cookie("cb_session", s.cookie()).header("X-CSRF-Token", s.csrf());
    }

    /** Registers and activates a customer, returning its email and TOTP secret. */
    protected Map<String, String> registerActive(String prefix, String institution) {
        String email = uniqueEmail(prefix);
        Response r = json().body(Map.of("email", email, "password", PASSWORD, "displayName", prefix,
                "institutionCode", institution))
                .post("/api/auth/register").then().statusCode(201).extract().response();
        String secret = r.path("totpSecret");
        json().body(Map.of("email", email, "password", PASSWORD, "code", nextCode(secret)))
                .post("/api/auth/totp/confirm").then().statusCode(204);
        return Map.of("email", email, "secret", secret, "userId", r.path("userId"));
    }

    protected Session login(String email, String secret) {
        Response r = json().body(Map.of("email", email, "password", PASSWORD, "code", nextCode(secret)))
                .post("/api/auth/login").then().statusCode(204).extract().response();
        String userId = as(new Session(null, email, secret, r.getCookie("cb_session"), r.header("X-CSRF-Token")))
                .get("/api/auth/me").then().statusCode(200).extract().path("userId");
        return new Session(UUID.fromString(userId), email, secret, r.getCookie("cb_session"), r.header("X-CSRF-Token"));
    }

    protected Session newCustomer(String prefix) {
        Map<String, String> u = registerActive(prefix, "BANK_A");
        return login(u.get("email"), u.get("secret"));
    }

    /** Creates an active user holding exactly the given role (replacing CUSTOMER), then logs in. */
    protected Session newUserWithRole(String prefix, String role) {
        Map<String, String> u = registerActive(prefix, "BANK_A");
        exec("DELETE FROM user_roles WHERE user_id = ?::uuid", u.get("userId"));
        exec("INSERT INTO user_roles (user_id, role) VALUES (?::uuid, ?)", u.get("userId"), role);
        return login(u.get("email"), u.get("secret"));
    }

    protected void stepUp(Session s) {
        as(s).body(Map.of("code", nextCode(s.totpSecret()))).post("/api/auth/step-up").then().statusCode(204);
    }

    // --- Phase 2 helpers ---------------------------------------------------------------------

    protected Session newCustomerAt(String prefix, String institution) {
        Map<String, String> u = registerActive(prefix, institution);
        return login(u.get("email"), u.get("secret"));
    }

    /** The customer's deposit account as returned by GET /api/accounts. */
    protected Map<String, Object> account(Session s) {
        java.util.List<Map<String, Object>> accounts = as(s).get("/api/accounts").then().statusCode(200).extract().jsonPath()
                .getList("$");
        return accounts.get(0);
    }

    protected String accountId(Session s) {
        return (String) account(s).get("id");
    }

    protected String accountNumber(Session s) {
        return (String) account(s).get("accountNumber");
    }

    protected long available(Session s) {
        return ((Number) account(s).get("availableBalance")).longValue();
    }

    protected Response send(Session from, String payeeNumber, long amount, String key) {
        return as(from).header("Idempotency-Key", key)
                .body(Map.of("sourceAccountId", accountId(from), "destinationAccountNumber", payeeNumber,
                        "amount", amount, "currency", "xUGX", "reference", "test"))
                .post("/api/transfers");
    }

    protected Response send(Session from, Session to, long amount) {
        return send(from, accountNumber(to), amount, UUID.randomUUID().toString());
    }

    protected Response approveTransfer(Session s, String transferId) {
        return as(s).body(Map.of("code", nextCode(s.totpSecret()))).post("/api/transfers/" + transferId + "/approve");
    }

    /** Sends and, if the transfer waits for the customer's approval, approves it. Returns the final body. */
    protected Response sendApproved(Session from, Session to, long amount) {
        Response r = send(from, to, amount).then().statusCode(201).extract().response();
        if ("VALIDATED".equals(r.path("state")) && Boolean.TRUE.equals(r.path("requiresStepUp"))) {
            return approveTransfer(from, r.path("id")).then().statusCode(200).extract().response();
        }
        return r;
    }

    protected long queryLong(String sql, Object... args) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    protected void exec(String sql, Object... args) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.execute();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
