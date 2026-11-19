package dev.chainbank.api.auth;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 6238 time-based one-time passwords (HMAC-SHA1, 30 s steps, 6 digits), compatible with
 * Google Authenticator, 1Password, Authy, etc.
 */
public final class Totp {

    public static final int STEP_SECONDS = 30;
    public static final int DIGITS = 6;
    /** Accept codes from one step before/after to tolerate clock drift. */
    public static final int SKEW_STEPS = 1;
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Totp() {
    }

    public static byte[] newSecret() {
        return Crypto.randomBytes(20);
    }

    public static long step(Instant t) {
        return Math.floorDiv(t.getEpochSecond(), STEP_SECONDS);
    }

    public static String code(byte[] secret, long step) {
        return code(secret, step, DIGITS, "HmacSHA1");
    }

    /** RFC 4226 HOTP with dynamic truncation; exposed with algorithm/digits for RFC test vectors. */
    static String code(byte[] secret, long step, int digits, String algorithm) {
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(secret, algorithm));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = h[h.length - 1] & 0x0f;
            int binary = ((h[offset] & 0x7f) << 24) | ((h[offset + 1] & 0xff) << 16)
                    | ((h[offset + 2] & 0xff) << 8) | (h[offset + 3] & 0xff);
            int otp = binary % (int) Math.pow(10, digits);
            return String.format("%0" + digits + "d", otp);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Returns the matched time step if {@code code} is valid at {@code now} (±{@link #SKEW_STEPS})
     * and strictly newer than {@code lastUsedStep}; otherwise -1. Callers must persist the returned
     * step atomically to make each code single-use.
     */
    public static long verify(byte[] secret, String code, Instant now, long lastUsedStep) {
        if (code == null || !code.matches("\\d{" + DIGITS + "}")) {
            return -1;
        }
        long current = step(now);
        long matched = -1;
        for (long s = current - SKEW_STEPS; s <= current + SKEW_STEPS; s++) {
            // Evaluate every candidate (no early exit) to keep timing independent of which step matched.
            boolean eq = Crypto.constantTimeEquals(
                    code(secret, s).getBytes(StandardCharsets.US_ASCII), code.getBytes(StandardCharsets.US_ASCII));
            if (eq && s > lastUsedStep) {
                matched = s;
            }
        }
        return matched;
    }

    public static String otpauthUri(String issuer, String account, byte[] secret) {
        String label = URLEncoder.encode(issuer + ":" + account, StandardCharsets.UTF_8).replace("+", "%20");
        return "otpauth://totp/%s?secret=%s&issuer=%s&algorithm=SHA1&digits=%d&period=%d".formatted(
                label, base32Encode(secret), URLEncoder.encode(issuer, StandardCharsets.UTF_8).replace("+", "%20"),
                DIGITS, STEP_SECONDS);
    }

    public static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        }
        return sb.toString();
    }

    public static byte[] base32Decode(String s) {
        String in = s.replace("=", "").replace(" ", "").toUpperCase();
        ByteBuffer out = ByteBuffer.allocate(in.length() * 5 / 8);
        int buffer = 0, bits = 0;
        for (char ch : in.toCharArray()) {
            int v = BASE32.indexOf(ch);
            if (v < 0) {
                throw new IllegalArgumentException("invalid base32");
            }
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) (buffer >> (bits - 8)));
                bits -= 8;
            }
        }
        return out.array();
    }
}
