package dev.chainbank.api.chain;

import java.time.Duration;
import java.util.Map;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

/**
 * Starts one Anvil node for the whole test JVM (shared by every Quarkus test profile so the chain
 * and the database never disagree). Automine is on: each transaction gets its own block, and tests
 * add confirmations explicitly with evm_mine.
 *
 * <p>Keys are Anvil's public development keys (default mnemonic) and are only valid on this throwaway chain.
 */
public class AnvilResource implements QuarkusTestResourceLifecycleManager {

    public static final String IMAGE = "ghcr.io/foundry-rs/foundry:v1.8.7";
    public static final String ADMIN_KEY = "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d"; // #1
    public static final String SETTLER_KEY = "0x5de4111afa1a4b94908f83103eb1f1706367c2e68ca870fc3fb9a804cdab365a"; // #2

    static GenericContainer<?> container;

    public static synchronized GenericContainer<?> container() {
        if (container == null) {
            container = new GenericContainer<>(IMAGE)
                    .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("anvil"))
                    .withCommand("--host", "0.0.0.0", "--chain-id", "31337")
                    .withExposedPorts(8545)
                    .waitingFor(Wait.forLogMessage(".*Listening on.*", 1).withStartupTimeout(Duration.ofMinutes(2)));
            container.start();
        }
        return container;
    }

    public static String rpcUrl() {
        GenericContainer<?> c = container();
        return "http://" + c.getHost() + ":" + c.getMappedPort(8545);
    }

    @Override
    public Map<String, String> start() {
        return Map.of(
                "chainbank.chain.rpc-url", rpcUrl(),
                "chainbank.chain.auto-deploy", "true",
                "chainbank.chain.admin-key", ADMIN_KEY,
                "chainbank.chain.settler-key", SETTLER_KEY,
                "chainbank.chain.rebroadcast-after", "PT15S");
    }

    @Override
    public void stop() {
        // Kept for the whole JVM; Testcontainers removes it on exit.
    }
}
