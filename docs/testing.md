# Testing

ChainBank's tests are the evidence for each phase's exit condition (spec §13–14). This page explains how they're organised, how to run them, and the techniques they rely on.

## Overview

| Layer | Count | Where | Runs against |
|---|---|---|---|
| API unit and integration | 109 | `services/api/src/test` | Real PostgreSQL (Quarkus Dev Services) and a **real Anvil node** (Testcontainers) |
| Contracts | 15 | `contracts/test` | Hardhat's in-process EVM |
| Web | lint, typecheck and build | `apps/web` | none |
| Smoke + end-to-end | 34 checks | `scripts/smoke.sh`, `scripts/e2e-auth.mjs` | The full Docker stack, through the web app's `/api` proxy |

```sh
make test                                   # everything except the Docker e2e
make test-api TEST=TransferConcurrencyTest  # one API suite (needs JDK 21 + Docker)
make test-contracts
make up && make smoke                       # e2e on the real stack
```

CI (`.github/workflows/ci.yml`) runs three jobs: the API tests; contracts plus web (including the ABI drift check); and the Docker smoke test, which runs only after both pass.

## API suites

| Suite | Proves | Spec |
|---|---|---|
| `LedgerSchemaTest` | DB-enforced invariants: balanced (overall and per bank), append-only, non-negative, integer money | §8.1 |
| `SmokeTest` | Health, seeded banks, correlation IDs, error format | §12 |
| `CryptoPrimitivesTest` | RFC 6238 TOTP vectors, replay window, Argon2id, AES-GCM owner binding | §7 |
| `EthSignaturesTest` | secp256k1 recovery byte-identical to viem, EIP-55, EIP-2 low-s, EIP-4361 format | §7.2 |
| `AuthFlowTest` | Register, confirm, login, logout; identical errors; TOTP replay; rate limit; idle and absolute expiry; step-up | §7.1 |
| `AuthorizationTest` | CSRF, foreign origin, role separation, suspension | §11 |
| `WalletLinkTest` | Wallet-link replay, wrong key, tampered message, expiry, another user's challenge, ownership, an 8-way race | §7.2, §11 |
| `AuditChainTest` | Hash chain valid; tampering detected even with triggers bypassed | §11 |
| `TransferFlowTest` | Opening funds, approvals, cancel and expiry, cross-bank journal and outbox, operator review | §8.2 |
| `TransferSecurityTest` | Other users' accounts and transfers behave as missing | §11 |
| `IdempotencyTest` | Replay, key reuse, per-user scope, 10-way concurrent duplicates → 1 journal | §11 |
| `TransferConcurrencyTest` | 30 parallel spends (exactly the affordable ones succeed), crossing transfers, parallel interbank | §14 phase 2 exit |
| `DailyLimitTest` | The production 5M limit (separate Quarkus profile) | ADR 0005 |
| `SettlementFlowTest` | Settlement happy path, duplicate, crash recovery, outage, reorg, pause, rogue settlement, authorization | §14 phase 3 exit |
| `MerkleTreeTest` | RFC 6962 reference vectors, proofs, CVE-2012-2459 | ADR 0007 |
| `AuditBatchTest` | Seal by count and by age, anchoring, `/verify`, two tamper modes, immutability, exception retry and resolve | §14 phase 4 exit |

## Techniques

- **Controllable time.** `ApiTestBase.TestClock` replaces `AppClock`, so tests advance time to mint fresh TOTP codes (`nextCode()`) and to expire sessions, challenges and transfers. Security decisions use application time for exactly this reason.
- **A real chain.** `AnvilResource` starts one Anvil container per test JVM, shared across Quarkus profiles so the chain and database stay consistent. Tests drive the worker step by step (the scheduler is off in tests) through `ChainTestBase`: `mine()`, `confirm()`, `settleFully()`.
- **Asynchronous mining.** Anvil returns from `eth_sendRawTransaction` before the block exists. `ChainTestBase.awaitReceipts()` waits for pending receipts before every mine or confirm step. Without it, an explicit `evm_mine` can swallow a pending transaction and cost a confirmation. This was a real CI failure, fixed in `317220d`.
- **Attacks as tests.** Tamper tests disable the append-only trigger, act as a database-owner attacker, assert the detection, and always restore the data in `finally`.
- **Concurrency.** Thread pools fire parallel HTTP requests. The assertions are about invariants (final balances, journal counts, ledger health), never about timing.
- **Shared database hygiene.** Tests create their own users (`uniqueEmail`) and never commit postings against real SETTLEMENT accounts, because reconciliation would rightly flag them.

## Mutation checks

To show a security test really guards something, the guard was removed, the test watched to fail, and the code restored. These were done by hand during development:

| Guard removed | Failing test |
|---|---|
| CSRF filter | `AuthorizationTest` (2 tests) |
| Funds condition in `TransferService.reserve` | `TransferConcurrencyTest.parallelSpendingCannotOverdraw` |
| `AlreadySettled` guard in `SettlementToken` | Contract test **and** `SettlementFlowTest.duplicate…`: the money moved twice on-chain |
| `OutOfOrder` check in `AuditAnchor` | `AuditAnchor.test.ts` |

The learning guides ([docs/learning](learning/)) turn these into exercises.

## Flakiness policy

The concurrency and chain suites were each run three or more times in a row before being committed. A test that depends on timing is a bug in the test: fix the race (see `awaitReceipts`) rather than adding sleeps or retries.
