package dev.chainbank.api.chain;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.web3j.abi.EventEncoder;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * ABI encoding for {@code SettlementToken} (contracts/contracts/SettlementToken.sol). The ABI and
 * bytecode are exported by {@code pnpm --filter @chainbank/contracts export} into
 * {@code resources/contracts/SettlementToken.json}; CI fails if that file is stale.
 */
public final class SettlementAbi {

    public static final String NAME = "SettlementToken";
    public static final Event SETTLED = new Event("Settled", List.of(
            new TypeReference<Bytes32>(true) {
            }, new TypeReference<Bytes32>(true) {
            }, new TypeReference<Bytes32>(true) {
            }, new TypeReference<Uint256>(false) {
            }));
    public static final String SETTLED_TOPIC = EventEncoder.encode(SETTLED);
    public static final String SETTLER_ROLE = Numeric.toHexString(Hash.sha3("SETTLER_ROLE".getBytes(StandardCharsets.US_ASCII)));

    private static final Artifact ARTIFACT = loadArtifact();

    record Artifact(int abiVersion, String bytecode) {
    }

    private SettlementAbi() {
    }

    public static int abiVersion() {
        return ARTIFACT.abiVersion();
    }

    /** Same derivation as the Solidity tests: keccak256(utf8("chainbank:transfer:" + uuid)). */
    public static String settlementId(UUID transferId) {
        return Numeric.toHexString(Hash.sha3(("chainbank:transfer:" + transferId).getBytes(StandardCharsets.UTF_8)));
    }

    /** "INST-A" -> 32 bytes, ASCII left-aligned and zero-padded (viem stringToHex(.., {size: 32})). */
    public static byte[] pseudonym(String pseudonym) {
        byte[] raw = pseudonym.getBytes(StandardCharsets.US_ASCII);
        if (raw.length > 32) {
            throw new IllegalArgumentException("pseudonym too long");
        }
        return Arrays.copyOf(raw, 32);
    }

    public static String pseudonymFromTopic(String topicHex) {
        byte[] b = Numeric.hexStringToByteArray(topicHex);
        int len = b.length;
        while (len > 0 && b[len - 1] == 0) {
            len--;
        }
        return new String(b, 0, len, StandardCharsets.US_ASCII);
    }

    public static String deployData(String admin, BigInteger cap) {
        return ARTIFACT.bytecode() + FunctionEncoder.encodeConstructor(List.of(new Address(admin), new Uint256(cap)));
    }

    public static String settle(String settlementId, String from, String to, BigInteger amount) {
        return encode("settle", new Bytes32(Numeric.hexStringToByteArray(settlementId)), new Bytes32(pseudonym(from)),
                new Bytes32(pseudonym(to)), new Uint256(amount));
    }

    public static String registerInstitution(String pseudonym, String wallet) {
        return encode("registerInstitution", new Bytes32(pseudonym(pseudonym)), new Address(wallet));
    }

    public static String mint(String pseudonym, BigInteger amount) {
        return encode("mint", new Bytes32(pseudonym(pseudonym)), new Uint256(amount));
    }

    public static String grantRole(String roleHex, String account) {
        return encode("grantRole", new Bytes32(Numeric.hexStringToByteArray(roleHex)), new Address(account));
    }

    public static String pause() {
        return encode("pause");
    }

    public static String unpause() {
        return encode("unpause");
    }

    public static String pausedCall() {
        return encode("paused");
    }

    public static String settledCall(String settlementId) {
        return encode("settled", new Bytes32(Numeric.hexStringToByteArray(settlementId)));
    }

    public static String balanceOfCall(String account) {
        return encode("balanceOf", new Address(account));
    }

    public static boolean decodeBool(String hex) {
        List<Type> out = FunctionReturnDecoder.decode(hex, List.of(asType(new TypeReference<Bool>() {
        })));
        return !out.isEmpty() && ((Bool) out.get(0)).getValue();
    }

    public static BigInteger decodeUint(String hex) {
        List<Type> out = FunctionReturnDecoder.decode(hex, List.of(asType(new TypeReference<Uint256>() {
        })));
        return out.isEmpty() ? BigInteger.ZERO : ((Uint256) out.get(0)).getValue();
    }

    public static BigInteger decodeSettledAmount(String data) {
        return decodeUint(data);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static String encode(String name, Type... args) {
        return FunctionEncoder.encode(new Function(name, (List) List.of(args), List.of()));
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static TypeReference<Type> asType(TypeReference<?> t) {
        return (TypeReference) t;
    }

    private static Artifact loadArtifact() {
        try (InputStream in = SettlementAbi.class.getResourceAsStream("/contracts/SettlementToken.json")) {
            if (in == null) {
                throw new IllegalStateException("contracts/SettlementToken.json missing; run the contracts export");
            }
            JsonNode n = new ObjectMapper().readTree(in);
            return new Artifact(n.get("abiVersion").asInt(), n.get("bytecode").asText());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
