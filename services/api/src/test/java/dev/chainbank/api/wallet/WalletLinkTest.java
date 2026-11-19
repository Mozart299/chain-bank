package dev.chainbank.api.wallet;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;

import dev.chainbank.api.ApiTestBase;

/** Wallet linking, including the replay and impersonation attacks from spec section 11. */
@QuarkusTest
class WalletLinkTest extends ApiTestBase {

    static final long CHAIN = 31337;

    private Response challenge(Session s, String address, long chainId) {
        return as(s).body(Map.of("address", address, "chainId", chainId)).post("/api/wallet/challenge");
    }

    private Response verify(Session s, String challengeId, String signature) {
        return as(s).body(Map.of("challengeId", challengeId, "signature", signature)).post("/api/wallet/verify");
    }

    @Test
    void linkWalletHappyPath() {
        Session s = newCustomer("linker");
        TestWallet w = TestWallet.random();
        Response ch = challenge(s, w.address, CHAIN).then().statusCode(200)
                .body("message", containsString("localhost:3000 wants you to sign in with your Ethereum account:"))
                .body("message", containsString(EthSignatures.toChecksumAddress(w.address)))
                .body("message", containsString("Chain ID: 31337"))
                .extract().response();

        verify(s, ch.path("challengeId"), w.signPersonal(ch.path("message"))).then().statusCode(201)
                .body("address", equalTo(w.address)).body("chainId", equalTo(31337));

        as(s).get("/api/wallet").then().statusCode(200).body("$", hasSize(1)).body("[0].address", equalTo(w.address));
    }

    @Test
    void replayedSignatureIsRejected() {
        Session s = newCustomer("replay");
        TestWallet w = TestWallet.random();
        Response ch = challenge(s, w.address, CHAIN).then().extract().response();
        String sig = w.signPersonal(ch.path("message"));
        verify(s, ch.path("challengeId"), sig).then().statusCode(201);
        verify(s, ch.path("challengeId"), sig).then().statusCode(404);
    }

    @Test
    void signatureFromDifferentKeyIsRejectedAndBurnsChallenge() {
        Session s = newCustomer("wrongkey");
        TestWallet claimed = TestWallet.random();
        TestWallet attacker = TestWallet.random();
        Response ch = challenge(s, claimed.address, CHAIN).then().extract().response();
        verify(s, ch.path("challengeId"), attacker.signPersonal(ch.path("message")))
                .then().statusCode(400).body("code", equalTo("SIGNATURE_INVALID"));
        // One-shot: the real owner must request a new challenge.
        verify(s, ch.path("challengeId"), claimed.signPersonal(ch.path("message"))).then().statusCode(404);
    }

    @Test
    void signatureOverDifferentMessageIsRejected() {
        Session s = newCustomer("othermsg");
        TestWallet w = TestWallet.random();
        Response ch = challenge(s, w.address, CHAIN).then().extract().response();
        String tampered = ((String) ch.path("message")).replace("Chain ID: 31337", "Chain ID: 1");
        verify(s, ch.path("challengeId"), w.signPersonal(tampered)).then().statusCode(400);
    }

    @Test
    void expiredChallengeIsRejected() {
        Session s = newCustomer("stale");
        TestWallet w = TestWallet.random();
        Response ch = challenge(s, w.address, CHAIN).then().extract().response();
        clock.advance(Duration.ofMinutes(6));
        verify(s, ch.path("challengeId"), w.signPersonal(ch.path("message")))
                .then().statusCode(410).body("code", equalTo("CHALLENGE_EXPIRED"));
    }

    @Test
    void unsupportedChainAndBadAddressRejected() {
        Session s = newCustomer("chain");
        challenge(s, TestWallet.random().address, 1).then().statusCode(400).body("code", equalTo("UNSUPPORTED_CHAIN"));
        challenge(s, "0x1234", CHAIN).then().statusCode(400).body("code", equalTo("INVALID_ADDRESS"));
    }

    @Test
    void anotherUserCannotRedeemMyChallenge() {
        Session alice = newCustomer("alice");
        Session mallory = newCustomer("mallory");
        TestWallet w = TestWallet.random();
        Response ch = challenge(alice, w.address, CHAIN).then().extract().response();
        verify(mallory, ch.path("challengeId"), w.signPersonal(ch.path("message"))).then().statusCode(404);
        // Alice's challenge is untouched by Mallory's attempt.
        verify(alice, ch.path("challengeId"), w.signPersonal(ch.path("message"))).then().statusCode(201);
    }

    @Test
    void addressCanOnlyBelongToOneUser() {
        Session alice = newCustomer("owner1");
        Session bob = newCustomer("owner2");
        TestWallet w = TestWallet.random();
        Response a = challenge(alice, w.address, CHAIN).then().extract().response();
        Response b = challenge(bob, w.address, CHAIN).then().extract().response();
        verify(alice, a.path("challengeId"), w.signPersonal(a.path("message"))).then().statusCode(201);
        verify(bob, b.path("challengeId"), w.signPersonal(b.path("message")))
                .then().statusCode(409).body("code", equalTo("ADDRESS_TAKEN"));
        challenge(alice, w.address, CHAIN).then().statusCode(409).body("code", equalTo("ALREADY_LINKED"));
    }

    @Test
    void relinkAndUnlinkRequireStepUp() {
        Session s = newCustomer("relink");
        TestWallet first = TestWallet.random();
        Response ch = challenge(s, first.address, CHAIN).then().extract().response();
        Response link = verify(s, ch.path("challengeId"), first.signPersonal(ch.path("message")))
                .then().statusCode(201).extract().response();

        challenge(s, TestWallet.random().address, CHAIN).then().statusCode(403).body("code", equalTo("STEP_UP_REQUIRED"));
        as(s).delete("/api/wallet/" + link.path("id")).then().statusCode(403).body("code", equalTo("STEP_UP_REQUIRED"));

        stepUp(s);
        challenge(s, TestWallet.random().address, CHAIN).then().statusCode(200);
        as(s).delete("/api/wallet/" + link.path("id")).then().statusCode(204);
        as(s).get("/api/wallet").then().body("$", hasSize(0));
    }

    @Test
    void cannotUnlinkSomeoneElsesWallet() {
        Session alice = newCustomer("victim");
        Session mallory = newCustomer("thief");
        TestWallet w = TestWallet.random();
        Response ch = challenge(alice, w.address, CHAIN).then().extract().response();
        Response link = verify(alice, ch.path("challengeId"), w.signPersonal(ch.path("message"))).then().extract().response();
        stepUp(mallory);
        as(mallory).delete("/api/wallet/" + link.path("id")).then().statusCode(404);
        as(alice).get("/api/wallet").then().body("$", hasSize(1));
    }

    @Test
    void concurrentVerifiesOnOneChallengeSucceedExactlyOnce() throws Exception {
        Session s = newCustomer("race");
        TestWallet w = TestWallet.random();
        Response ch = challenge(s, w.address, CHAIN).then().extract().response();
        String id = ch.path("challengeId");
        String sig = w.signPersonal(ch.path("message"));

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                calls.add(() -> verify(s, id, sig).statusCode());
            }
            int created = 0;
            for (Future<Integer> f : pool.invokeAll(calls)) {
                if (f.get() == 201) {
                    created++;
                }
            }
            assertEquals(1, created);
        } finally {
            pool.shutdownNow();
        }
        as(s).get("/api/wallet").then().body("$", hasSize(1));
    }
}
