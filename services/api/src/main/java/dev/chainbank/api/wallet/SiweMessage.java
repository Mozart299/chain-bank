package dev.chainbank.api.wallet;

import java.time.Instant;
import java.util.UUID;

/**
 * Builds an EIP-4361 ("Sign-In with Ethereum") message. The server generates the full text and
 * stores it, and the wallet signs it verbatim, so the server never has to parse client-supplied
 * messages. Every field that matters (domain, address, chain, nonce, expiry, purpose) is bound
 * into the signed bytes.
 */
public final class SiweMessage {

    private SiweMessage() {
    }

    public static String build(String domain, String address, String statement, String uri, long chainId,
            String nonce, Instant issuedAt, Instant expiresAt, UUID requestId) {
        return """
                %s wants you to sign in with your Ethereum account:
                %s

                %s

                URI: %s
                Version: 1
                Chain ID: %d
                Nonce: %s
                Issued At: %s
                Expiration Time: %s
                Request ID: %s""".formatted(
                domain, EthSignatures.toChecksumAddress(address), statement, uri, chainId, nonce,
                issuedAt, expiresAt, requestId);
    }

    public static String statementFor(String purpose) {
        return switch (purpose) {
            case "LINK_WALLET" ->
                "Link this wallet to your ChainBank account. This is a simulation: no funds move and no transaction is sent.";
            default -> throw new IllegalArgumentException("unsupported purpose " + purpose);
        };
    }
}
