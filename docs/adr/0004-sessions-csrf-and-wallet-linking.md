# ADR 0004: Sessions, CSRF, step-up and wallet linking

- Status: accepted
- Date: 2026-11-19
- Implements: spec §7.1–7.3, §11 (replay, broken object authorization, insider misuse)

## Decisions

### Same-origin through Next.js
The browser only talks to the web origin (`:3000`). A Next.js rewrite proxies `/api/*` to Quarkus, so the session cookie is first-party and the API needs no CORS. The rewrite target is fixed at build time, so the Docker image receives `CHAINBANK_API_URL` as a build argument.

### Sessions
- The session token is 32 random bytes in cookie `cb_session` (`HttpOnly; SameSite=Lax; Path=/`, plus `Secure` outside local HTTP). The database stores only `SHA-256(token)`.
- Sessions expire after 30 minutes idle and 12 hours absolute. Logout revokes one session; "revoke all" (step-up required) revokes every session for the user.
- A custom Quarkus `HttpAuthenticationMechanism` and `IdentityProvider` turn the cookie into a `SecurityIdentity`, so endpoints use standard `@Authenticated` / `@RolesAllowed`. Authentication is lazy (`quarkus.http.auth.proactive=false`), so a stale cookie never breaks a public endpoint.
- A suspended user's sessions stop resolving immediately, because the lookup joins on `users.status = 'ACTIVE'`.

### CSRF
- The CSRF token is `base64url(SHA-256("csrf:" + sessionToken))`. It's derived from the cookie, so it needs no storage, and the page learns it only through `/api/auth/me` or the login response.
- Every non-GET request carrying a session cookie must send a matching `X-CSRF-Token` header. Any `Origin` header present must be on the allow-list.
- `SameSite=Lax` adds a third, browser-side layer.

### TOTP and step-up
- TOTP follows RFC 6238 (SHA-1, 30 s, 6 digits, ±1 step), written in-house and tested against the RFC vectors.
- Secrets are encrypted with AES-256-GCM, with the user ID as associated data, so a secret copied to another row won't decrypt.
- `users.totp_last_step` plus a conditional `UPDATE` makes every code single-use, even under concurrent requests.
- Step-up sets `sessions.step_up_at` and is valid for 5 minutes. Re-linking a wallet, unlinking, revoking all sessions and admin suspension all require it.

### Wallet linking
- The server builds the full EIP-4361 message: domain, checksummed address, statement, URI, chain ID, nonce, issue and expiry times, request ID. It stores the message, and the wallet signs it verbatim. The server never parses a message supplied by the client.
- The signature is verified by recovering the signer's public key with secp256k1 (BouncyCastle), applying the EIP-191 `personal_sign` prefix, and rejecting high-s signatures (EIP-2).
  - web3j was rejected because its current releases need Java 21, while this project is on Java 17.
  - The implementation is tested byte-for-byte against viem signatures.
- The challenge is consumed first, in its own committed transaction, using `UPDATE … WHERE consumed_at IS NULL`. Every challenge is therefore single-use, even when verification fails or requests race.
- A partial unique index allows one active owner per (chain, address).
- Only chain 31337 is accepted.

### Audit
`audit_events` is hash-chained: each event's hash covers the previous hash plus the event's fields, length-prefixed. Appends are serialised with `pg_advisory_xact_lock`. `GET /api/audit/chain/verify` (auditor, compliance, admin) recomputes the whole chain. Secrets, codes, tokens and signatures are never recorded.

### Rate limiting
In-memory sliding windows count failures per account (5 per 5 minutes) and per IP (100 per 5 minutes). That works for this single-instance simulator only; scaling out would need a shared store. In Compose, Quarkus trusts `X-Forwarded-For` only from the Docker network, so per-IP limits see the real client rather than the Next.js proxy.

### Demo users
When `chainbank.demo.seed=true` (Compose and dev), 5 fictional users are created with a shared, public password and TOTP secret. Never enable this anywhere real.

## Consequences
- The CSRF token is tied to the session token, so rotating the session also rotates the CSRF token.
- Stepping up a single session doesn't elevate the user's other sessions.
- Registration reveals whether an email is already taken (409). Login does not. This is acceptable for a simulator, and it's listed in the threat model.
