package dev.chainbank.api.auth;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import jakarta.enterprise.context.ApplicationScoped;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/**
 * Argon2id password hashing (OWASP baseline: m=19 MiB, t=2, p=1), stored in PHC string format:
 * {@code $argon2id$v=19$m=19456,t=2,p=1$<salt>$<hash>}.
 */
@ApplicationScoped
public class PasswordHasher {

    private static final int MEMORY_KIB = 19_456;
    private static final int ITERATIONS = 2;
    private static final int PARALLELISM = 1;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final Base64.Encoder B64 = Base64.getEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getDecoder();

    /** Verified against when the user does not exist, so timing does not reveal account existence. */
    private final String dummyHash = hash("dummy-password-for-timing-equalisation");

    public String hash(String password) {
        byte[] salt = Crypto.randomBytes(SALT_BYTES);
        byte[] out = derive(password, salt, MEMORY_KIB, ITERATIONS, PARALLELISM, HASH_BYTES);
        return "$argon2id$v=19$m=%d,t=%d,p=%d$%s$%s".formatted(
                MEMORY_KIB, ITERATIONS, PARALLELISM, B64.encodeToString(salt), B64.encodeToString(out));
    }

    public boolean verify(String password, String phc) {
        String[] parts = phc.split("\\$");
        // ["", "argon2id", "v=19", "m=..,t=..,p=..", salt, hash]
        if (parts.length != 6 || !"argon2id".equals(parts[1]) || !"v=19".equals(parts[2])) {
            return false;
        }
        int m = 0, t = 0, p = 0;
        for (String kv : parts[3].split(",")) {
            String[] pair = kv.split("=");
            int v = Integer.parseInt(pair[1]);
            switch (pair[0]) {
                case "m" -> m = v;
                case "t" -> t = v;
                case "p" -> p = v;
                default -> {
                    return false;
                }
            }
        }
        byte[] salt = B64D.decode(parts[4]);
        byte[] expected = B64D.decode(parts[5]);
        byte[] actual = derive(password, salt, m, t, p, expected.length);
        return Crypto.constantTimeEquals(expected, actual);
    }

    /** Burns the same CPU as a real verification. Always returns false. */
    public boolean verifyAgainstDummy(String password) {
        verify(password, dummyHash);
        return false;
    }

    private static byte[] derive(String password, byte[] salt, int m, int t, int p, int len) {
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(m)
                .withIterations(t)
                .withParallelism(p)
                .withSalt(salt)
                .build();
        Argon2BytesGenerator gen = new Argon2BytesGenerator();
        gen.init(params);
        byte[] out = new byte[len];
        gen.generateBytes(password.getBytes(StandardCharsets.UTF_8), out);
        return out;
    }
}
