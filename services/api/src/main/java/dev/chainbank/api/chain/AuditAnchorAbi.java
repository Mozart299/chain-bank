package dev.chainbank.api.chain;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint64;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** ABI encoding for {@code AuditAnchor} (contracts/contracts/AuditAnchor.sol, ADR 0007). */
public final class AuditAnchorAbi {

    public static final String NAME = "AuditAnchor";
    public static final String COMMITTER_ROLE = Numeric.toHexString(Hash.sha3("COMMITTER_ROLE".getBytes(StandardCharsets.US_ASCII)));
    private static final JsonNode ARTIFACT = load();

    public record OnChainBatch(String root, long firstEventId, long lastEventId, long blockNumber) {
        public boolean exists() {
            return !root.equals("0x" + "0".repeat(64));
        }
    }

    private AuditAnchorAbi() {
    }

    public static int abiVersion() {
        return ARTIFACT.get("abiVersion").asInt();
    }

    public static String deployData(String admin) {
        return ARTIFACT.get("bytecode").asText() + FunctionEncoder.encodeConstructor(List.of(new Address(admin)));
    }

    public static String grantCommitter(String account) {
        return SettlementAbi.grantRole(COMMITTER_ROLE, account);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static String commit(long batchId, long firstEventId, long lastEventId, byte[] root) {
        return FunctionEncoder.encode(new Function("commit", (List) List.of(new Uint256(BigInteger.valueOf(batchId)),
                new Uint64(BigInteger.valueOf(firstEventId)), new Uint64(BigInteger.valueOf(lastEventId)), new Bytes32(root)),
                List.of()));
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static String batchesCall(long batchId) {
        return FunctionEncoder.encode(new Function("batches", (List) List.of(new Uint256(BigInteger.valueOf(batchId))), List.of()));
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static OnChainBatch decodeBatch(String hex) {
        List<TypeReference<Type>> outputs = (List) List.of(new TypeReference<Bytes32>() {
        }, new TypeReference<Uint64>() {
        }, new TypeReference<Uint64>() {
        }, new TypeReference<Uint64>() {
        });
        List<Type> v = FunctionReturnDecoder.decode(hex, outputs);
        return new OnChainBatch(Numeric.toHexString((byte[]) v.get(0).getValue()),
                ((BigInteger) v.get(1).getValue()).longValueExact(), ((BigInteger) v.get(2).getValue()).longValueExact(),
                ((BigInteger) v.get(3).getValue()).longValueExact());
    }

    private static JsonNode load() {
        try (InputStream in = AuditAnchorAbi.class.getResourceAsStream("/contracts/AuditAnchor.json")) {
            if (in == null) {
                throw new IllegalStateException("contracts/AuditAnchor.json missing; run the contracts export");
            }
            return new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
