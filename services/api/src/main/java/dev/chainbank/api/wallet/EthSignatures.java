package dev.chainbank.api.wallet;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.asn1.x9.X9IntegerConverter;
import org.bouncycastle.crypto.digests.KeccakDigest;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.math.ec.ECAlgorithms;
import org.bouncycastle.math.ec.ECCurve;
import org.bouncycastle.math.ec.ECPoint;

/**
 * Recovers the Ethereum address that produced a {@code personal_sign} (EIP-191) signature.
 *
 * <p>An ECDSA signature (r, s) plus a one-bit recovery id is enough to reconstruct the signer's
 * public key (SEC 1 v2, section 4.1.6). The Ethereum address is the last 20 bytes of the Keccak-256 hash
 * of that public key. If the recovered address equals the claimed one, the holder of that private
 * key signed exactly this message.
 */
public final class EthSignatures {

    public static final X9ECParameters CURVE = CustomNamedCurves.getByName("secp256k1");
    static final BigInteger N = CURVE.getN();
    static final BigInteger HALF_N = N.shiftRight(1);
    private static final Pattern ADDRESS = Pattern.compile("^0x[0-9a-fA-F]{40}$");
    private static final Pattern SIGNATURE = Pattern.compile("^0x[0-9a-fA-F]{130}$");
    private static final HexFormat HEX = HexFormat.of();

    private EthSignatures() {
    }

    public static boolean isAddress(String s) {
        return s != null && ADDRESS.matcher(s).matches();
    }

    public static byte[] keccak256(byte[] input) {
        KeccakDigest d = new KeccakDigest(256);
        d.update(input, 0, input.length);
        byte[] out = new byte[32];
        d.doFinal(out, 0);
        return out;
    }

    /** EIP-191 version 0x45: keccak256("\x19Ethereum Signed Message:\n" + len(message) + message). */
    public static byte[] personalMessageHash(String message) {
        byte[] msg = message.getBytes(StandardCharsets.UTF_8);
        byte[] prefix = ("\u0019Ethereum Signed Message:\n" + msg.length).getBytes(StandardCharsets.UTF_8);
        byte[] all = Arrays.copyOf(prefix, prefix.length + msg.length);
        System.arraycopy(msg, 0, all, prefix.length, msg.length);
        return keccak256(all);
    }

    /**
     * Returns the lowercase 0x-address that signed {@code message}, or empty if the signature is
     * malformed, non-canonical (high-s, EIP-2), or does not recover to a valid point.
     */
    public static Optional<String> recoverPersonalSigner(String message, String signatureHex) {
        if (signatureHex == null || !SIGNATURE.matcher(signatureHex).matches()) {
            return Optional.empty();
        }
        byte[] sig = HEX.parseHex(signatureHex.substring(2));
        BigInteger r = new BigInteger(1, Arrays.copyOfRange(sig, 0, 32));
        BigInteger s = new BigInteger(1, Arrays.copyOfRange(sig, 32, 64));
        int v = sig[64] & 0xff;
        int recId = v >= 27 ? v - 27 : v;
        if (recId < 0 || recId > 1) {
            return Optional.empty();
        }
        if (r.signum() <= 0 || r.compareTo(N) >= 0 || s.signum() <= 0 || s.compareTo(HALF_N) > 0) {
            return Optional.empty();
        }
        ECPoint q = recoverPublicKey(personalMessageHash(message), r, s, recId);
        return q == null ? Optional.empty() : Optional.of(addressOf(q));
    }

    static ECPoint recoverPublicKey(byte[] hash, BigInteger r, BigInteger s, int recId) {
        ECCurve curve = CURVE.getCurve();
        BigInteger p = curve.getField().getCharacteristic();
        if (r.compareTo(p) >= 0) {
            return null;
        }
        // R = the curve point whose x-coordinate is r and whose y parity is recId.
        X9IntegerConverter conv = new X9IntegerConverter();
        byte[] compressed = conv.integerToBytes(r, 1 + conv.getByteLength(curve));
        compressed[0] = (byte) ((recId & 1) == 1 ? 0x03 : 0x02);
        ECPoint bigR;
        try {
            bigR = curve.decodePoint(compressed);
        } catch (IllegalArgumentException e) {
            return null;
        }
        // Q = r^-1 (sR - eG)
        BigInteger e = new BigInteger(1, hash);
        BigInteger rInv = r.modInverse(N);
        BigInteger u1 = e.negate().mod(N).multiply(rInv).mod(N);
        BigInteger u2 = s.multiply(rInv).mod(N);
        ECPoint q = ECAlgorithms.sumOfTwoMultiplies(CURVE.getG(), u1, bigR, u2).normalize();
        return q.isInfinity() ? null : q;
    }

    public static String addressOf(ECPoint publicKey) {
        byte[] uncompressed = publicKey.getEncoded(false); // 0x04 || X || Y
        byte[] hash = keccak256(Arrays.copyOfRange(uncompressed, 1, uncompressed.length));
        return "0x" + HEX.formatHex(Arrays.copyOfRange(hash, 12, 32));
    }

    /** EIP-55 mixed-case checksum encoding, as required in EIP-4361 messages. */
    public static String toChecksumAddress(String address) {
        String lower = address.toLowerCase().substring(2);
        String hash = HEX.formatHex(keccak256(lower.getBytes(StandardCharsets.US_ASCII)));
        StringBuilder sb = new StringBuilder("0x");
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            sb.append(Character.digit(hash.charAt(i), 16) >= 8 ? Character.toUpperCase(c) : c);
        }
        return sb.toString();
    }
}
