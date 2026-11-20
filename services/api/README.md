# services/api: Quarkus API

The bank's backend: authentication, ledger, transfers, the settlement gateway, reconciliation and audit. Java 21, Quarkus 3, plain JDBC (no ORM, so the money SQL stays visible), Flyway, and web3j for the chain.

## Run

```sh
make api-dev            # from the repo root: dev mode with its own Postgres (Dev Services) and demo users
make test-api           # all tests (needs Docker: Postgres + Anvil containers)
make test-api TEST=TransferFlowTest
```

In Docker, use `make up`. The API's environment variables are listed in [docs/runbook.md](../../docs/runbook.md#3-configuration).

## Layout (`src/main/java/dev/chainbank/api`)

| Package | Responsibility | Start with |
|---|---|---|
| `platform` | Config mapping (`ChainbankConfig`), transactions (`Db`), error format, correlation IDs, `AppClock` | `Db.java` |
| `auth` | Argon2id, TOTP, encrypted secrets, sessions, Quarkus Security integration, CSRF, rate limits, step-up | `AuthService.java` |
| `wallet` | EIP-4361 challenges, secp256k1 signer recovery | `WalletService.java` |
| `ledger` | Accounts, opening deposits, history, the ledger health check | `AccountService.java` |
| `transfer` | State machine, locking, reservations, idempotency, approvals, operator review, expiry | `TransferService.java` |
| `chain` | web3j client, contract deployment, `SettlementWorker`, reconciliation, positions, pause | `SettlementWorker.java` |
| `audit` | Hash-chained log, Merkle tree, batches, anchoring, verification, export | `AuditLog.java` |
| `admin`, `institution`, `demo` | Suspension, public bank list, demo users | |

Migrations live in `src/main/resources/db/migration` (`V1`…`V6`). They're append-only: never edit an applied one. Contract ABIs exported from `contracts/` are in `src/main/resources/contracts`.

## Conventions
- Every money operation runs in one `Db.tx`, locks accounts with `TransferService.lockAccounts`, and changes state with compare-and-set (`TransferService.transition`).
- Security events go through `AuditLog.record` inside the same transaction as the action.
- Sensitive endpoints call `CurrentSession.requireStepUp()`.
- New `chainbank.*` properties must be added to `ChainbankConfig`, or Quarkus refuses to start.

How the parts fit together: [docs/architecture.md](../../docs/architecture.md). Testing: [docs/testing.md](../../docs/testing.md).
