package dev.chainbank.api.platform;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "chainbank")
public interface ChainbankConfig {

    Web web();

    Session session();

    Secrets secrets();

    Wallet wallet();

    RateLimit rateLimit();

    Demo demo();

    Chain chain();

    Transfers transfers();

    /** Spec section 16 decisions, recorded in ADR 0005. All amounts in xUGX minor units. */
    interface Transfers {
        /** Amounts above this need the customer's step-up approval. */
        @WithDefault("1000000")
        long stepUpThreshold();

        /** Cross-bank amounts above this are flagged for bank-operator review. */
        @WithDefault("2000000")
        long reviewThreshold();

        @WithDefault("5000000")
        long dailyLimit();

        /** Demo funds deposited into every new customer account. */
        @WithDefault("10000000")
        long openingBalance();

        /** How long a transfer may wait for approvals before it is cancelled and funds released. */
        @WithDefault("PT15M")
        Duration approvalTtl();
    }

    interface Chain {
        /** JSON-RPC endpoint of the EVM chain (Anvil locally); used by the Phase 3 gateway. */
        String rpcUrl();
    }

    interface Web {
        /** Origins allowed to send mutating requests (the Next.js app). */
        List<String> allowedOrigins();
    }

    interface Session {
        @WithDefault("PT30M")
        Duration idleTimeout();

        @WithDefault("PT12H")
        Duration absoluteTimeout();

        @WithDefault("PT5M")
        Duration stepUpValidity();

        @WithDefault("true")
        boolean cookieSecure();
    }

    interface Secrets {
        /** Base64 AES-256 key that encrypts TOTP secrets at rest. Never commit a production value. */
        String totpKey();
    }

    interface Wallet {
        /** EIP-4361 domain, e.g. localhost:3000 */
        String domain();

        /** EIP-4361 URI, e.g. http://localhost:3000 */
        String uri();

        List<Long> allowedChainIds();

        @WithDefault("PT5M")
        Duration challengeTtl();
    }

    interface RateLimit {
        @WithDefault("5")
        int perAccount();

        @WithDefault("100")
        int perIp();

        @WithDefault("PT5M")
        Duration window();
    }

    interface Demo {
        @WithDefault("false")
        boolean seed();

        Optional<String> password();
    }
}
