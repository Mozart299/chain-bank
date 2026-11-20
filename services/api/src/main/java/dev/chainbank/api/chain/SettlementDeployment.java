package dev.chainbank.api.chain;

import java.math.BigInteger;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import dev.chainbank.api.audit.AuditLog;
import dev.chainbank.api.platform.ChainbankConfig;
import dev.chainbank.api.platform.Db;

/**
 * Knows where the settlement contract lives, and in local environments deploys and configures it:
 * register both banks' pseudonyms and wallets, grant the gateway SETTLER_ROLE, and mint each bank's
 * opening settlement position to mirror the off-chain OPENING journals.
 */
@ApplicationScoped
public class SettlementDeployment {

    private static final Logger LOG = Logger.getLogger(SettlementDeployment.class);
    /** Supply cap: generous headroom over the 2 x 1e9 opening positions. */
    static final BigInteger CAP = BigInteger.valueOf(1_000_000_000_000L);

    @Inject
    Db db;

    @Inject
    ChainClient chain;

    @Inject
    ChainbankConfig config;

    @Inject
    AuditLog audit;

    private volatile String address;
    private volatile String anchorAddress;

    public record Institution(String code, String pseudonym, String wallet) {
    }

    public Optional<String> address() {
        if (address == null) {
            address = lookup(SettlementAbi.NAME);
        }
        return Optional.ofNullable(address);
    }

    /** Address of the AuditAnchor contract (Phase 4), if deployed. */
    public Optional<String> anchorAddress() {
        if (anchorAddress == null) {
            anchorAddress = lookup(AuditAnchorAbi.NAME);
        }
        return Optional.ofNullable(anchorAddress);
    }

    private String lookup(String name) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT address FROM chain_deployments WHERE chain_id = ? AND name = ?")) {
                ps.setLong(1, chain.chainId());
                ps.setString(2, name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    /** Deploys the AuditAnchor if allowed and not yet deployed; grants the gateway COMMITTER_ROLE. */
    public synchronized Optional<String> ensureAnchorDeployed() {
        Optional<String> existing = anchorAddress();
        if (existing.isPresent() || !config.chain().autoDeploy()) {
            return existing;
        }
        Credentials admin = chain.admin().orElseThrow(() -> new IllegalStateException("auto-deploy needs CHAIN_ADMIN_KEY"));
        Credentials gateway = chain.settler().orElseThrow(() -> new IllegalStateException("auto-deploy needs CHAIN_SETTLER_KEY"));
        chain.verifyChainId();
        String data = AuditAnchorAbi.deployData(admin.getAddress());
        BigInteger gas = chain.estimateGas(admin.getAddress(), null, data).multiply(BigInteger.valueOf(12)).divide(BigInteger.TEN);
        TransactionReceipt deployed = chain.transactAndWait(admin, null, data, gas);
        String contract = deployed.getContractAddress().toLowerCase();
        chain.transactAndWait(admin, contract, AuditAnchorAbi.grantCommitter(gateway.getAddress()), BigInteger.valueOf(200_000));
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO chain_deployments (chain_id, name, address, abi_version, tx_hash, block_number)
                    VALUES (?, ?, ?, ?, ?, ?)""")) {
                ps.setLong(1, chain.chainId());
                ps.setString(2, AuditAnchorAbi.NAME);
                ps.setString(3, contract);
                ps.setInt(4, AuditAnchorAbi.abiVersion());
                ps.setString(5, deployed.getTransactionHash());
                ps.setLong(6, deployed.getBlockNumber().longValue());
                ps.executeUpdate();
            }
            audit.record(c, null, "AUDIT_ANCHOR_DEPLOYED", "CHAIN", contract,
                    Map.of("chainId", Long.toString(chain.chainId())));
            return null;
        });
        anchorAddress = contract;
        LOG.infof("Deployed AuditAnchor at %s on chain %d", contract, chain.chainId());
        return Optional.of(contract);
    }

    /** Deploys if allowed and not yet deployed. Never redeploys over an existing record. */
    public synchronized Optional<String> ensureDeployed() {
        Optional<String> existing = address();
        if (existing.isPresent() || !config.chain().autoDeploy()) {
            return existing;
        }
        Credentials admin = chain.admin().orElseThrow(() -> new IllegalStateException("auto-deploy needs CHAIN_ADMIN_KEY"));
        Credentials settler = chain.settler().orElseThrow(() -> new IllegalStateException("auto-deploy needs CHAIN_SETTLER_KEY"));
        chain.verifyChainId();

        String data = SettlementAbi.deployData(admin.getAddress(), CAP);
        BigInteger gas = chain.estimateGas(admin.getAddress(), null, data).multiply(BigInteger.valueOf(12)).divide(BigInteger.TEN);
        TransactionReceipt deployed = chain.transactAndWait(admin, null, data, gas);
        String contract = deployed.getContractAddress().toLowerCase();
        BigInteger opsGas = BigInteger.valueOf(200_000);

        for (Institution i : institutions()) {
            chain.transactAndWait(admin, contract, SettlementAbi.registerInstitution(i.pseudonym(), i.wallet()), opsGas);
            long opening = openingPosition(i.code());
            chain.transactAndWait(admin, contract, SettlementAbi.mint(i.pseudonym(), BigInteger.valueOf(opening)), opsGas);
        }
        chain.transactAndWait(admin, contract, SettlementAbi.grantRole(SettlementAbi.SETTLER_ROLE, settler.getAddress()), opsGas);

        long block = deployed.getBlockNumber().longValue();
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO chain_deployments (chain_id, name, address, abi_version, tx_hash, block_number)
                    VALUES (?, ?, ?, ?, ?, ?)""")) {
                ps.setLong(1, chain.chainId());
                ps.setString(2, SettlementAbi.NAME);
                ps.setString(3, contract);
                ps.setInt(4, SettlementAbi.abiVersion());
                ps.setString(5, deployed.getTransactionHash());
                ps.setLong(6, block);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO chain_cursor (chain_id, last_indexed_block) VALUES (?, ?)
                    ON CONFLICT (chain_id) DO UPDATE SET last_indexed_block = EXCLUDED.last_indexed_block""")) {
                ps.setLong(1, chain.chainId());
                ps.setLong(2, block);
                ps.executeUpdate();
            }
            audit.record(c, null, "SETTLEMENT_CONTRACT_DEPLOYED", "CHAIN", contract,
                    Map.of("chainId", Long.toString(chain.chainId()), "abiVersion", Integer.toString(SettlementAbi.abiVersion())));
            return null;
        });
        address = contract;
        LOG.infof("Deployed SettlementToken at %s on chain %d (block %d)", contract, chain.chainId(), block);
        return Optional.of(contract);
    }

    public List<Institution> institutions() {
        return db.tx(c -> {
            List<Institution> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT code, pseudonym FROM institutions ORDER BY code");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String code = rs.getString(1);
                    String wallet = config.chain().institutionWallets().get(code);
                    if (wallet == null) {
                        throw new IllegalStateException("no settlement wallet configured for " + code);
                    }
                    out.add(new Institution(code, rs.getString(2), wallet.toLowerCase()));
                }
            }
            return out;
        });
    }

    /** The bank's opening settlement position, taken from its OPENING journal. */
    private long openingPosition(String code) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT l.debit_minor FROM journal_lines l
                    JOIN journals j ON j.id = l.journal_id JOIN accounts a ON a.id = l.account_id
                    WHERE j.reference = ? AND a.account_type = 'SETTLEMENT'""")) {
                ps.setString(1, "OPENING-" + code);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
    }

    /** Test hook: forget the cached address (tests that reset the chain). */
    void clearCache() {
        address = null;
    }
}
