package dev.chainbank.api.auth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.chainbank.api.platform.ChainbankConfig;

class CryptoPrimitivesTest {

    /** RFC 6238 Appendix B: SHA-1 seed "12345678901234567890", 8 digits. */
    @Test
    void totpMatchesRfc6238Vectors() {
        byte[] seed = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        long[][] vectors = {
                { 59L, 94287082 }, { 1111111109L, 7081804 }, { 1111111111L, 14050471 },
                { 1234567890L, 89005924 }, { 2000000000L, 69279037 }, { 20000000000L, 65353130 } };
        for (long[] v : vectors) {
            long step = Totp.step(Instant.ofEpochSecond(v[0]));
            assertEquals(String.format("%08d", v[1]), Totp.code(seed, step, 8, "HmacSHA1"), "t=" + v[0]);
        }
    }

    @Test
    void totpAcceptsSkewAndRejectsReplay() {
        byte[] secret = Totp.newSecret();
        Instant now = Instant.parse("2026-11-19T12:00:10Z");
        long step = Totp.step(now);
        assertEquals(step, Totp.verify(secret, Totp.code(secret, step), now, 0));
        assertEquals(step - 1, Totp.verify(secret, Totp.code(secret, step - 1), now, 0));
        assertEquals(step + 1, Totp.verify(secret, Totp.code(secret, step + 1), now, 0));
        assertEquals(-1, Totp.verify(secret, Totp.code(secret, step - 2), now, 0), "outside window");
        assertEquals(-1, Totp.verify(secret, Totp.code(secret, step), now, step), "replay of used step");
        assertEquals(-1, Totp.verify(secret, "12345", now, 0), "wrong length");
        assertEquals(-1, Totp.verify(secret, null, now, 0));
    }

    @Test
    void base32RoundTrip() {
        byte[] b = Totp.newSecret();
        assertArrayEquals(b, Totp.base32Decode(Totp.base32Encode(b)));
        assertEquals("JBSWY3DPEHPK3PXP", Totp.base32Encode("Hello!Þ­¾ï".getBytes(StandardCharsets.ISO_8859_1)));
    }

    @Test
    void otpauthUriIsWellFormed() {
        String uri = Totp.otpauthUri("ChainBank", "alice@example.test", "Hello!".getBytes(StandardCharsets.US_ASCII));
        assertTrue(uri.startsWith("otpauth://totp/ChainBank%3Aalice%40example.test?secret="), uri);
        assertTrue(uri.contains("&issuer=ChainBank&"));
    }

    @Test
    void passwordHashing() {
        PasswordHasher h = new PasswordHasher();
        String phc = h.hash("correct horse battery staple");
        assertTrue(phc.startsWith("$argon2id$v=19$m=19456,t=2,p=1$"));
        assertTrue(h.verify("correct horse battery staple", phc));
        assertFalse(h.verify("correct horse battery stapler", phc));
        assertNotEquals(phc, h.hash("correct horse battery staple"), "salted");
        assertFalse(h.verifyAgainstDummy("anything"));
    }

    @Test
    void secretCipherBindsOwnerAndDetectsTampering() {
        SecretCipher c = new SecretCipher(configWithKey(Base64.getEncoder().encodeToString(new byte[32])));
        UUID alice = UUID.randomUUID();
        byte[] secret = Totp.newSecret();
        byte[] blob = c.encrypt(secret, alice);
        assertArrayEquals(secret, c.decrypt(blob, alice));
        assertThrows(IllegalStateException.class, () -> c.decrypt(blob, UUID.randomUUID()), "other owner");
        blob[blob.length - 1] ^= 1;
        assertThrows(IllegalStateException.class, () -> c.decrypt(blob, alice), "tampered");
    }

    private static ChainbankConfig configWithKey(String key) {
        return (ChainbankConfig) java.lang.reflect.Proxy.newProxyInstance(
                ChainbankConfig.class.getClassLoader(), new Class<?>[] { ChainbankConfig.class },
                (p, m, a) -> switch (m.getName()) {
                    case "secrets" -> (ChainbankConfig.Secrets) () -> key;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }
}
