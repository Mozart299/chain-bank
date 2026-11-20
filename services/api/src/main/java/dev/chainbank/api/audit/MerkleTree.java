package dev.chainbank.api.audit;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * RFC 6962 (Certificate Transparency) Merkle tree over SHA-256 (ADR 0007).
 *
 * <pre>
 * leaf(d)    = SHA256(0x00 || d)
 * node(l, r) = SHA256(0x01 || l || r)
 * MTH(D[n])  = node(MTH(D[0:k]), MTH(D[k:n]))  where k is the largest power of two below n
 * </pre>
 *
 * The 0x00/0x01 prefixes keep leaves and inner nodes from being confused, and there is no
 * "duplicate the last leaf" rule, so two different leaf lists can never share a root.
 */
public final class MerkleTree {

    private MerkleTree() {
    }

    public static byte[] root(List<byte[]> leaves) {
        if (leaves.isEmpty()) {
            return sha256(new byte[0]);
        }
        return mth(leaves, 0, leaves.size());
    }

    /** Audit path for the leaf at {@code index} (RFC 6962 section 2.1.1), ordered from the leaf upwards. */
    public static List<byte[]> proof(List<byte[]> leaves, int index) {
        if (index < 0 || index >= leaves.size()) {
            throw new IllegalArgumentException("index out of range");
        }
        List<byte[]> path = new ArrayList<>();
        path(leaves, index, 0, leaves.size(), path);
        return path;
    }

    /** RFC 9162 section 2.1.3.2 inclusion-proof verification. */
    public static boolean verify(byte[] leaf, int index, int size, List<byte[]> proof, byte[] root) {
        if (index < 0 || index >= size) {
            return false;
        }
        long fn = index, sn = size - 1L;
        byte[] r = leafHash(leaf);
        for (byte[] p : proof) {
            if (sn == 0) {
                return false;
            }
            if ((fn & 1) == 1 || fn == sn) {
                r = nodeHash(p, r);
                if ((fn & 1) == 0) {
                    while ((fn & 1) == 0 && fn != 0) {
                        fn >>= 1;
                        sn >>= 1;
                    }
                }
            } else {
                r = nodeHash(r, p);
            }
            fn >>= 1;
            sn >>= 1;
        }
        return sn == 0 && Arrays.equals(r, root);
    }

    static byte[] leafHash(byte[] data) {
        return sha256(concat(new byte[] { 0x00 }, data));
    }

    static byte[] nodeHash(byte[] left, byte[] right) {
        return sha256(concat(new byte[] { 0x01 }, left, right));
    }

    private static byte[] mth(List<byte[]> d, int from, int to) {
        int n = to - from;
        if (n == 1) {
            return leafHash(d.get(from));
        }
        int k = largestPowerOfTwoBelow(n);
        return nodeHash(mth(d, from, from + k), mth(d, from + k, to));
    }

    private static void path(List<byte[]> d, int m, int from, int to, List<byte[]> out) {
        int n = to - from;
        if (n == 1) {
            return;
        }
        int k = largestPowerOfTwoBelow(n);
        if (m < k) {
            path(d, m, from, from + k, out);
            out.add(mth(d, from + k, to));
        } else {
            path(d, m - k, from + k, to, out);
            out.add(mth(d, from, from + k));
        }
    }

    static int largestPowerOfTwoBelow(int n) {
        int k = 1;
        while (k << 1 < n) {
            k <<= 1;
        }
        return k;
    }

    private static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) {
            len += p.length;
        }
        byte[] out = new byte[len];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    private static byte[] sha256(byte[] in) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(in);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
