# 2. Wallet challenge (Sign-In with Ethereum)

## Key ideas

- To link a wallet, the server issues a **one-time challenge**, the wallet **signs** it, and the server **recovers** the signer and compares it with the claimed address.
- The message is an **EIP-4361** text that binds every detail that matters: domain, address, chain ID, nonce, issue time, expiry, and a purpose statement. A signature for one site, chain or purpose can't be replayed somewhere else.
- **The server writes the message and the wallet signs it byte-for-byte.** The server never parses text sent by the client, which removes a whole class of injection bugs.
- **Single use is enforced atomically:** `UPDATE … SET consumed_at = now() WHERE id = ? AND consumed_at IS NULL` succeeds for exactly one request, even when eight arrive at once.
- **The challenge is consumed before the signature is checked.** A wrong signature still burns the challenge, so an attacker can't keep guessing against the same one.

## Read the code

| What | Where |
|---|---|
| Message builder | `services/api/.../wallet/SiweMessage.java` |
| Challenge, verify, unlink (owner checks in SQL) | `.../wallet/WalletService.java` |
| Browser side: connect, request a challenge, `signMessage`, verify | `apps/web/src/app/account/wallet-panel.tsx` |
| Decision record | [ADR 0004](../adr/0004-sessions-csrf-and-wallet-linking.md) |

## Hands-on

1. In MetaMask, add the network RPC `http://localhost:8545`, chain ID `31337`, and import one of Anvil's **public** dev keys. Sign in as Alice, open **Security**, and link the wallet. Then expand "Message you signed" and find each bound field.
2. Watch the challenge lifecycle:
   ```sql
   SELECT purpose, address, chain_id, issued_at, expires_at, consumed_at FROM wallet_challenges ORDER BY issued_at DESC LIMIT 3;
   SELECT address, chain_id, verified_at, revoked_at FROM wallet_links ORDER BY verified_at DESC LIMIT 3;
   ```
3. Try linking a second wallet without step-up: you're refused (re-linking is a sensitive change).

## Break it

| Change | Caught by |
|---|---|
| In `WalletService.consume`, remove `AND consumed_at IS NULL` | `WalletLinkTest.replayedSignatureIsRejected`, `concurrentVerifiesOnOneChallengeSucceedExactlyOnce` |
| In `WalletService.consume`, remove `AND user_id = ?` | `WalletLinkTest.anotherUserCannotRedeemMyChallenge` |
| Make `verify` check the signature *before* consuming the challenge | `WalletLinkTest.signatureFromDifferentKeyIsRejectedAndBurnsChallenge` |

`make test-api TEST=WalletLinkTest`

## Check yourself

- Which field stops a signature made for chain 1 being accepted on chain 31337?
- Why is it safer for the server, not the client, to build the message?
- What would go wrong if the same address could be actively linked to two users?
