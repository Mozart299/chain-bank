package dev.chainbank.api.chain;

import java.io.IOException;
import java.util.List;

import jakarta.inject.Inject;

import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.methods.response.VoidResponse;
import org.web3j.protocol.http.HttpService;

import dev.chainbank.api.ApiTestBase;

/**
 * Helpers for tests that drive the chain worker step by step against the shared Anvil node.
 * Anvil mines asynchronously, so every step waits for pending receipts first: otherwise an explicit
 * evm_mine can pull a still-pending transaction into its own block and silently cost a confirmation.
 */
public abstract class ChainTestBase extends ApiTestBase {

    @Inject
    protected SettlementWorker worker;

    @Inject
    protected SettlementReconciler reconciler;

    @Inject
    protected SettlementDeployment deployment;

    @Inject
    protected ChainClient chain;

    protected final HttpService rpc = new HttpService(AnvilResource.rpcUrl());

    /** Waits (up to 5 s) until every open settlement and audit-anchor transaction has a receipt. */
    protected void awaitReceipts() {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            boolean allMined = true;
            try (var c = dataSource.getConnection(); var ps = c.prepareStatement("""
                    SELECT tx_hash FROM chain_txs WHERE status IN ('SUBMITTED', 'MINED', 'REORGED')
                    UNION ALL SELECT chain_tx_hash FROM audit_batches WHERE status = 'SUBMITTED'""");
                    var rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (chain.receipt(rs.getString(1)).isEmpty()) {
                        allMined = false;
                    }
                }
            } catch (java.sql.SQLException e) {
                throw new RuntimeException(e);
            }
            if (allMined) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    protected void confirm() {
        awaitReceipts();
        worker.confirm();
    }

    protected void mine(int blocks) {
        awaitReceipts();
        try {
            for (int i = 0; i < blocks; i++) {
                new Request<>("evm_mine", List.of(), rpc, VoidResponse.class).send();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    protected void settleFully() {
        worker.submit();
        mine(1);
        confirm();
        mine(1);
        confirm();
        reconciler.index();
        reconciler.reconcile();
    }

    protected String state(Session s, String id) {
        return as(s).get("/api/transfers/" + id).then().statusCode(200).extract().path("state");
    }

    protected String queryString(String sql, Object... args) {
        try (var c = dataSource.getConnection(); var ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
