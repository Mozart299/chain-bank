# Threat model (living document)

This is a learning-grade threat model, not a security certification. It follows spec §11. Each threat gets a mitigation and a test in the phase that introduces the attack surface.

| Threat | Mitigation | Test | Phase | Status |
| --- | --- | --- | --- | --- |
| Signature replay / phishing | Domain-, chain- and purpose-bound EIP-4361 challenge with expiry; nonce stored hashed; challenge consumed atomically before verification; high-s signatures rejected | `WalletLinkTest` (replay, wrong key, tampered message, expired, other user's challenge, concurrent verify), `e2e-auth.mjs` | 1 | **enforced** |
| Broken object authorization | Owner and institution checks in every WHERE clause; unknown and foreign IDs both return 404; `@RolesAllowed` on every protected resource | `WalletLinkTest.cannotUnlinkSomeoneElsesWallet`, `TransferSecurityTest`, `AuthorizationTest` | 1–2 | **enforced** |
| Duplicate transfer / retry | `UNIQUE(initiated_by, idempotency_key)` + request fingerprint, re-checked under account locks; rejections persisted; replays return the original | `IdempotencyTest` (10 parallel duplicates → 1 transfer, 1 journal), `e2e-auth.mjs` | 2 | **enforced** |
| Double spend / concurrency | Ordered account row locks; conditional reservation (`ledger − reserved ≥ amount`); DB trigger forbids negative balances; compare-and-set state transitions | `TransferConcurrencyTest` (30 parallel spends, crossing transfers, parallel interbank), mutation-checked | 2 | **enforced** |
| Unbalanced or tampered ledger | Deferred balance trigger; append-only journals; readiness invariant check | `LedgerSchemaTest` | 0 | **enforced** |
| Key compromise | No keys in repo; Hardhat keystore for testnet; emergency pause | Secret scan in CI | 3, 5 | planned |
| Smart contract vulnerability | AccessControl, caps, pause, duplicate-settlement guard | Contract and fuzz tests | 3 | planned |
| Chain outage / reorg | Confirmation policy, PENDING_INVESTIGATION state, reconciliation | Reorg simulation on Anvil | 3–4 | schema ready |
| PII exposure | Only institution pseudonyms and settlement IDs on-chain; log redaction | Event payload assertions | 3 | planned |
| Insider misuse | Role separation; admin actions need step-up; hash-chained `audit_events` with chain verification | `AuthorizationTest.adminSuspensionNeedsStepUp…`, `AuditChainTest` (tampering detected even with triggers bypassed) | 1, 4 | **enforced (audit chain); batch commitments in Phase 4** |
| Credential stuffing / brute force | Argon2id password hashes; TOTP second factor; per-account and per-IP failure limits; identical errors for every login failure | `AuthFlowTest` (rate limit, indistinguishable errors, TOTP replay) | 1 | **enforced** |
| Session theft / CSRF | HttpOnly SameSite cookie; only the token hash stored; idle and absolute expiry; per-session CSRF token and Origin allow-list | `AuthFlowTest` (expiry, revoke-all), `AuthorizationTest` (CSRF, foreign origin) | 1 | **enforced** |
| Account enumeration | Login, confirm and step-up return one generic error and do equal work (dummy Argon2) | `AuthFlowTest.wrongPasswordUnknownEmail…` | 1 | **enforced for login; registration reveals taken emails (accepted)** |
| TOTP seed disclosure | Seeds encrypted with AES-256-GCM, bound to the user ID; key outside the database | `CryptoPrimitivesTest.secretCipher…` | 1 | **enforced** |
| Unbalanced interbank posting | Commit-time trigger requires every journal to balance per institution as well as overall; readiness check recomputes it | `LedgerSchemaTest.journalMustBalanceWithinEachBank`, `TransferFlowTest.crossBankTransfer…` | 2 | **enforced** |
| Approval reuse / substitution | Approvals are append-only, one per method per transfer, bound by `context_hash` to id, amount, accounts and expiry; approval TOTP codes are single-use | `TransferFlowTest.approvalCodesMustBeValidAndFresh` | 2 | **enforced** |
| Insider releases flagged payment | Operator review requires the sending bank's `BANK_OPERATOR` role, a fresh step-up, and a reviewer other than the initiator; all decisions audited | `TransferFlowTest.flaggedCrossBankTransferNeedsOperatorReview` | 2 | **enforced** |
