package dev.chainbank.api.wallet;

import java.math.BigInteger;
import java.util.HexFormat;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.signers.HMacDSAKCalculator;
import org.bouncycastle.math.ec.ECPoint;

import dev.chainbank.api.auth.Crypto;

/** Test-only Ethereum wallet: produces personal_sign signatures exactly as MetaMask/viem do. */
public final class TestWallet {

    private static final ECDomainParameters DOMAIN = new ECDomainParameters(EthSignatures.CURVE);

    private final BigInteger privateKey;
    public final String address;

    private TestWallet(BigInteger privateKey) {
        this.privateKey = privateKey;
        ECPoint pub = EthSignatures.CURVE.getG().multiply(privateKey).normalize();
        this.address = EthSignatures.addressOf(pub);
    }

    public static TestWallet random() {
        BigInteger k;
        do {
            k = new BigInteger(1, Crypto.randomBytes(32));
        } while (k.signum() == 0 || k.compareTo(EthSignatures.N) >= 0);
        return new TestWallet(k);
    }

    public static TestWallet fromHex(String hex) {
        return new TestWallet(new BigInteger(hex.replace("0x", ""), 16));
    }

    public String signPersonal(String message) {
        byte[] hash = EthSignatures.personalMessageHash(message);
        ECDSASigner signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()));
        signer.init(true, new ECPrivateKeyParameters(privateKey, DOMAIN));
        BigInteger[] rs = signer.generateSignature(hash);
        BigInteger r = rs[0];
        BigInteger s = rs[1].compareTo(EthSignatures.HALF_N) > 0 ? EthSignatures.N.subtract(rs[1]) : rs[1];
        for (int recId = 0; recId < 2; recId++) {
            ECPoint q = EthSignatures.recoverPublicKey(hash, r, s, recId);
            if (q != null && EthSignatures.addressOf(q).equals(address)) {
                return "0x" + to32(r) + to32(s) + HexFormat.of().toHexDigits((byte) (27 + recId));
            }
        }
        throw new IllegalStateException("could not determine recovery id");
    }

    private static String to32(BigInteger v) {
        return String.format("%064x", v);
    }
}
