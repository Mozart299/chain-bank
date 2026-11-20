package dev.chainbank.api.chain;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;

import io.smallrye.health.api.HealthGroup;

/**
 * Chain connectivity, reported in its own group (/q/health/group/chain). It deliberately does not
 * affect readiness: if the chain is down, banking continues off-chain and settlements queue up.
 */
@HealthGroup("chain")
@ApplicationScoped
public class ChainHealthCheck implements HealthCheck {

    @Inject
    ChainClient chain;

    @Inject
    SettlementDeployment deployment;

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder b = HealthCheckResponse.named("settlement-chain").withData("chainId", chain.chainId());
        try {
            long latest = chain.latestBlock();
            b.withData("latestBlock", latest);
            var contract = deployment.address();
            if (contract.isEmpty()) {
                return b.withData("contract", "not deployed").up().build();
            }
            String code = chain.code(contract.get());
            if (code == null || code.equals("0x")) {
                return b.withData("contract", contract.get()).withData("error",
                        "no contract code at recorded address; the chain was reset while the database was not").down().build();
            }
            boolean paused = SettlementAbi.decodeBool(chain.call(contract.get(), SettlementAbi.pausedCall()));
            return b.withData("contract", contract.get()).withData("paused", paused).up().build();
        } catch (ChainClient.ChainException e) {
            return b.withData("error", "RPC unavailable").down().build();
        }
    }
}
