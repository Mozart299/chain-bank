package dev.chainbank.api.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class EthSignaturesTest {

    // Generated with viem's privateKeyToAccount(...).signMessage using the public Anvil dev key #0.
    static final String ANVIL0 = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";
    static final String SIG_HELLO = "0xdf2ba40d0cf4d7544f9a7652dcf37b5cd20e6dcadb9af804718c2491134fbb15"
            + "4a8e1ffbca4f903b3f9051688061eace71cef890f41f3542119d5bee059dc75b1b";
    static final String SIG_UNICODE = "0xedd4576dfa2099c83fd703f99e8dbaea0a663809ec8dc226e09725ea134d4d29"
            + "3b07644642c2a9960d8abd4970e7b05c36919b5f8bbc4858dd0edc5eceabba641b";

    @Test
    void recoversSignerOfViemSignatures() {
        assertEquals(Optional.of(ANVIL0.toLowerCase()), EthSignatures.recoverPersonalSigner("hello chainbank", SIG_HELLO));
        assertEquals(Optional.of(ANVIL0.toLowerCase()),
                EthSignatures.recoverPersonalSigner("Multi\nline\nmessage with ünïcode", SIG_UNICODE));
    }

    @Test
    void tamperedMessageRecoversDifferentAddress() {
        Optional<String> r = EthSignatures.recoverPersonalSigner("hello chainbank!", SIG_HELLO);
        assertTrue(r.isEmpty() || !r.get().equals(ANVIL0.toLowerCase()));
    }

    @Test
    void rejectsMalformedAndHighSSignatures() {
        assertTrue(EthSignatures.recoverPersonalSigner("x", "0x1234").isEmpty());
        assertTrue(EthSignatures.recoverPersonalSigner("x", null).isEmpty());
        // Flip s to n - s (same signature mathematically, but non-canonical; EIP-2 forbids it).
        BigInteger s = new BigInteger(SIG_HELLO.substring(66, 130), 16);
        String highS = SIG_HELLO.substring(0, 66) + String.format("%064x", EthSignatures.N.subtract(s)) + "1c";
        assertTrue(EthSignatures.recoverPersonalSigner("hello chainbank", highS).isEmpty());
        // Invalid recovery byte.
        String badV = SIG_HELLO.substring(0, 130) + "05";
        assertTrue(EthSignatures.recoverPersonalSigner("hello chainbank", badV).isEmpty());
    }

    @Test
    void testWalletMatchesViem() {
        TestWallet w = TestWallet.fromHex("ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80");
        assertEquals(ANVIL0.toLowerCase(), w.address);
        // RFC 6979 deterministic k means our signature must be byte-identical to viem's.
        assertEquals(SIG_HELLO, w.signPersonal("hello chainbank"));
    }

    @Test
    void randomWalletsRoundTrip() {
        for (int i = 0; i < 20; i++) {
            TestWallet w = TestWallet.random();
            assertEquals(Optional.of(w.address), EthSignatures.recoverPersonalSigner("msg " + i, w.signPersonal("msg " + i)));
        }
    }

    @Test
    void eip55Checksum() {
        // Test vectors from EIP-55.
        assertEquals("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
                EthSignatures.toChecksumAddress("0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed"));
        assertEquals("0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
                EthSignatures.toChecksumAddress("0xFB6916095CA1DF60BB79CE92CE3EA74C37C5D359"));
    }

    @Test
    void siweMessageBindsAllFields() {
        UUID id = UUID.fromString("11111111-2222-3333-4444-555555555555");
        String m = SiweMessage.build("localhost:3000", ANVIL0.toLowerCase(), SiweMessage.statementFor("LINK_WALLET"),
                "http://localhost:3000", 31337, "abc123def456", Instant.parse("2026-11-19T10:00:00Z"),
                Instant.parse("2026-11-19T10:05:00Z"), id);
        assertTrue(m.startsWith("localhost:3000 wants you to sign in with your Ethereum account:\n" + ANVIL0 + "\n\n"));
        assertTrue(m.contains("\nChain ID: 31337\n"));
        assertTrue(m.contains("\nNonce: abc123def456\n"));
        assertTrue(m.contains("\nExpiration Time: 2026-11-19T10:05:00Z\n"));
        assertTrue(m.endsWith("\nRequest ID: " + id));
    }
}
