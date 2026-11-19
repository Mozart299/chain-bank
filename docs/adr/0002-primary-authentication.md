# ADR 0002: Primary authentication is password plus TOTP

- Status: accepted
- Date: 2026-11-19
- Resolves: spec §16, open decision 1

## Decision

- Primary sign-in uses an email and password. Passwords are hashed with Argon2id or bcrypt (to be chosen in Phase 1), and TOTP (RFC 6238) is required as a second factor.
- Sessions are opaque random tokens. Only their SHA-256 hash is stored (`sessions.token_hash`). They are sent in `HttpOnly; Secure; SameSite=Lax` cookies with CSRF protection on mutating requests.
- Step-up (`sessions.step_up_at`) needs a fresh TOTP code. It is required for transfers above the demo threshold, new beneficiaries, and wallet re-link or unlink.
- Wallet signatures (EIP-4361-style messages) only link an address to an already authenticated user. An address alone never authenticates.
- Passkeys (WebAuthn) are a later addition, not a replacement.

## Consequences

- Phase 1 is simpler to build and test without browser authenticator tooling.
- TOTP secrets must be encrypted at rest (`users.totp_secret_cipher`), with the key outside the database.
