package dev.chainbank.api.chain;

import java.util.Map;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import dev.chainbank.api.audit.AuditLog;
import dev.chainbank.api.auth.CurrentSession;
import dev.chainbank.api.platform.ApiException;
import dev.chainbank.api.platform.ChainbankConfig;
import dev.chainbank.api.platform.Db;

/** Settlement-chain status and the emergency pause (spec section 8.4). */
@Path("/api/admin/chain")
@Produces(MediaType.APPLICATION_JSON)
public class ChainAdminResource {

    public record ChainStatus(long chainId, String contract, String auditAnchor, boolean paused, long latestBlock,
            int confirmations, long pendingOutbox, long openTransactions) {
    }

    @Inject
    SettlementDeployment deployment;

    @Inject
    ChainClient chain;

    @Inject
    CurrentSession current;

    @Inject
    AuditLog audit;

    @Inject
    Db db;

    @Inject
    ChainbankConfig config;

    @GET
    @RolesAllowed({ "SYSTEM_ADMIN", "AUDITOR", "BANK_OPERATOR" })
    public ChainStatus status() {
        String contract = deployment.address().orElse(null);
        boolean paused = contract != null && SettlementAbi.decodeBool(chain.call(contract, SettlementAbi.pausedCall()));
        long[] backlog = db.tx(c -> {
            try (var ps = c.prepareStatement("""
                    SELECT (SELECT count(*) FROM outbox WHERE status IN ('PENDING', 'IN_FLIGHT')),
                           (SELECT count(*) FROM chain_txs WHERE status IN ('SUBMITTED', 'MINED', 'REORGED'))""");
                    var rs = ps.executeQuery()) {
                rs.next();
                return new long[] { rs.getLong(1), rs.getLong(2) };
            }
        });
        return new ChainStatus(chain.chainId(), contract, deployment.anchorAddress().orElse(null), paused,
                chain.latestBlock(), config.chain().confirmations(), backlog[0], backlog[1]);
    }

    /** Spec section 8.4: controlled emergency pause. Settlements queue off-chain while paused. */
    @POST
    @Path("/pause")
    @RolesAllowed("SYSTEM_ADMIN")
    public ChainStatus pause() {
        return setPaused(true);
    }

    @POST
    @Path("/unpause")
    @RolesAllowed("SYSTEM_ADMIN")
    public ChainStatus unpause() {
        return setPaused(false);
    }

    private ChainStatus setPaused(boolean pause) {
        var admin = current.requireStepUp();
        String contract = deployment.address()
                .orElseThrow(() -> ApiException.conflict("NOT_DEPLOYED", "Settlement contract is not deployed"));
        var key = chain.admin().orElseThrow(() -> ApiException.conflict("NO_ADMIN_KEY", "No governance key configured"));
        boolean paused = SettlementAbi.decodeBool(chain.call(contract, SettlementAbi.pausedCall()));
        if (paused != pause) {
            chain.transactAndWait(key, contract, pause ? SettlementAbi.pause() : SettlementAbi.unpause(),
                    java.math.BigInteger.valueOf(100_000));
        }
        db.tx(c -> {
            audit.record(c, admin.userId(), pause ? "CHAIN_PAUSED" : "CHAIN_UNPAUSED", "CHAIN", contract, Map.of());
            return null;
        });
        return status();
    }
}
