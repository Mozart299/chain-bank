package dev.chainbank.api.chain;

import java.io.IOException;
import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthLog;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;

import dev.chainbank.api.platform.ChainbankConfig;
import okhttp3.OkHttpClient;

/**
 * Thin wrapper over web3j JSON-RPC. Keys come from configuration (environment) only and never
 * leave this class; the browser never sees them (spec section 6).
 */
@ApplicationScoped
public class ChainClient {

    static final BigInteger PRIORITY_FEE = BigInteger.valueOf(1_000_000_000L); // 1 gwei

    @Inject
    ChainbankConfig config;

    private volatile Web3j web3j;

    public record Signed(String txHash, String raw, BigInteger nonce) {
    }

    public static class ChainException extends RuntimeException {
        public ChainException(String message) {
            super(message);
        }

        public ChainException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    Web3j web3() {
        if (web3j == null) {
            synchronized (this) {
                if (web3j == null) {
                    OkHttpClient http = new OkHttpClient.Builder()
                            .connectTimeout(Duration.ofSeconds(3))
                            .readTimeout(Duration.ofSeconds(5))
                            .callTimeout(Duration.ofSeconds(8))
                            .build();
                    web3j = Web3j.build(new HttpService(config.chain().rpcUrl(), http));
                }
            }
        }
        return web3j;
    }

    @PreDestroy
    void close() {
        if (web3j != null) {
            web3j.shutdown();
        }
    }

    public Optional<Credentials> settler() {
        return config.chain().settlerKey().filter(k -> !k.isBlank()).map(Credentials::create);
    }

    public Optional<Credentials> admin() {
        return config.chain().adminKey().filter(k -> !k.isBlank()).map(Credentials::create);
    }

    public long chainId() {
        return config.chain().chainId();
    }

    /** Fails if the node is on a different chain than configured: never sign for the wrong network. */
    public void verifyChainId() {
        long actual = rpc(() -> web3().ethChainId().send()).getChainId().longValue();
        if (actual != chainId()) {
            throw new ChainException("connected to chain " + actual + " but configured for " + chainId());
        }
    }

    public long latestBlock() {
        return rpc(() -> web3().ethBlockNumber().send()).getBlockNumber().longValue();
    }

    public Optional<String> blockHash(long number) {
        EthBlock.Block b = rpc(() -> web3().ethGetBlockByNumber(DefaultBlockParameter.valueOf(BigInteger.valueOf(number)),
                false).send()).getBlock();
        return b == null ? Optional.empty() : Optional.of(b.getHash());
    }

    public Optional<TransactionReceipt> receipt(String txHash) {
        return rpc(() -> web3().ethGetTransactionReceipt(txHash).send()).getTransactionReceipt();
    }

    public BigInteger pendingNonce(String address) {
        return rpc(() -> web3().ethGetTransactionCount(address, DefaultBlockParameterName.PENDING).send())
                .getTransactionCount();
    }

    public String code(String address) {
        return rpc(() -> web3().ethGetCode(address, DefaultBlockParameterName.LATEST).send()).getCode();
    }

    public String call(String to, String data) {
        return call(to, data, DefaultBlockParameterName.LATEST);
    }

    public String call(String to, String data, DefaultBlockParameter block) {
        return rpc(() -> web3().ethCall(Transaction.createEthCallTransaction(null, to, data), block).send()).getValue();
    }

    public List<Log> logs(String address, long from, long to, String topic) {
        EthFilter filter = new EthFilter(DefaultBlockParameter.valueOf(BigInteger.valueOf(from)),
                DefaultBlockParameter.valueOf(BigInteger.valueOf(to)), address);
        filter.addSingleTopic(topic);
        List<EthLog.LogResult<?>> results = rpc(() -> web3().ethGetLogs(filter).send()).getLogs();
        return results.stream().map(r -> (Log) r.get()).toList();
    }

    /** Signs an EIP-1559 transaction locally. {@code to == null} creates a contract. */
    public Signed sign(Credentials key, String to, String data, BigInteger gasLimit, BigInteger nonce) {
        EthBlock.Block latest = rpc(() -> web3().ethGetBlockByNumber(DefaultBlockParameterName.LATEST, false).send())
                .getBlock();
        BigInteger base = latest.getBaseFeePerGas() == null ? BigInteger.ZERO : latest.getBaseFeePerGas();
        BigInteger maxFee = base.multiply(BigInteger.TWO).add(PRIORITY_FEE);
        RawTransaction tx = RawTransaction.createTransaction(chainId(), nonce, gasLimit, to == null ? "" : to,
                BigInteger.ZERO, data, PRIORITY_FEE, maxFee);
        byte[] signed = TransactionEncoder.signMessage(tx, chainId(), key);
        return new Signed(Numeric.toHexString(Hash.sha3(signed)), Numeric.toHexString(signed), nonce);
    }

    /** Broadcasts a signed transaction. "already known" counts as success (it is in the mempool). */
    public void broadcast(String raw) {
        var resp = rpc(() -> web3().ethSendRawTransaction(raw).send(), true);
        if (resp.hasError()) {
            String msg = resp.getError().getMessage();
            if (msg != null && msg.toLowerCase().contains("already known")) {
                return;
            }
            throw new ChainException("broadcast rejected: " + msg);
        }
    }

    /** Signs, sends and waits for a successful receipt (administrative actions only). */
    public TransactionReceipt transactAndWait(Credentials key, String to, String data, BigInteger gasLimit) {
        Signed s = sign(key, to, data, gasLimit, pendingNonce(key.getAddress()));
        broadcast(s.raw());
        for (int i = 0; i < 150; i++) {
            Optional<TransactionReceipt> r = receipt(s.txHash());
            if (r.isPresent()) {
                if (!r.get().isStatusOK()) {
                    throw new ChainException("transaction reverted: " + s.txHash());
                }
                return r.get();
            }
            sleep(200);
        }
        throw new ChainException("no receipt for " + s.txHash());
    }

    public BigInteger estimateGas(String from, String to, String data) {
        return rpc(() -> web3().ethEstimateGas(Transaction.createFunctionCallTransaction(from, null, null, null, to, data))
                .send()).getAmountUsed();
    }

    // --- internals ------------------------------------------------------------------------------

    interface Call<T extends Response<?>> {
        T send() throws IOException;
    }

    private <T extends Response<?>> T rpc(Call<T> call) {
        return rpc(call, false);
    }

    private <T extends Response<?>> T rpc(Call<T> call, boolean allowError) {
        try {
            T r = call.send();
            if (!allowError && r.hasError()) {
                throw new ChainException("RPC error: " + r.getError().getMessage());
            }
            return r;
        } catch (IOException e) {
            throw new ChainException("RPC unavailable: " + e.getMessage(), e);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChainException("interrupted");
        }
    }
}
