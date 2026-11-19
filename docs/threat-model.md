# Threat model (living document)

This is a learning-grade threat model, not a security certification. It follows spec §11. Each threat gets a mitigation and a test in the phase that introduces the attack surface.

| Threat | Mitigation | Test | Phase | Status |
| --- | --- | --- | --- | --- |
| Signature replay / phishing | Domain-, chain- and purpose-bound challenge with expiry; nonce stored hashed and consumed atomically | Replay and stale-nonce rejection | 1 | planned |
| Broken object authorization | Owner and tenant checks on every query | Cross-user access denied | 1–2 | planned |
| Duplicate transfer / retry | `UNIQUE(initiated_by, idempotency_key)` plus request fingerprint; outbox | Same key yields one journal | 2 | schema ready |
| Double spend / concurrency | Posting trigger row-locks the balance and rejects negative balances; reservations in Phase 2 | Concurrent spend test | 2 | partially enforced (DB) |
| Unbalanced or tampered ledger | Deferred balance trigger; append-only journals; readiness invariant check | `LedgerSchemaTest` | 0 | **enforced** |
| Key compromise | No keys in repo; Hardhat keystore for testnet; emergency pause | Secret scan in CI | 3, 5 | planned |
| Smart contract vulnerability | AccessControl, caps, pause, duplicate-settlement guard | Contract and fuzz tests | 3 | planned |
| Chain outage / reorg | Confirmation policy, PENDING_INVESTIGATION state, reconciliation | Reorg simulation on Anvil | 3–4 | schema ready |
| PII exposure | Only institution pseudonyms and settlement IDs on-chain; log redaction | Event payload assertions | 3 | planned |
| Insider misuse | Role separation; hash-chained `audit_events` | Audit chain verification | 4 | schema ready |
