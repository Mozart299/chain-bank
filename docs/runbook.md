# Runbook

How to run, configure, operate and troubleshoot the local ChainBank stack. Every problem in the troubleshooting section has actually happened during development.

## 1. Prerequisites

| Tool | Version | Notes |
|---|---|---|
| Docker Desktop | recent | Runs Postgres, Anvil, the API and the web app |
| Node.js + pnpm | Node ≥ 22, pnpm 10 | `pnpm install` at the repo root (workspace) |
| JDK | **21** | Only needed to run the API or its tests outside Docker. The Makefile finds a JDK in `~/.jdks/jdk-21*` or one registered with `/usr/libexec/java_home -v 21`; otherwise set `JAVA_HOME` |

Installing JDK 21 without Homebrew (Intel Macs need full Xcode for `brew install openjdk@21`): download Eclipse Temurin 21 from [adoptium.net](https://adoptium.net), verify its SHA-256 against the published checksum, and unpack it into `~/.jdks/`.

## 2. Everyday commands

| Command | What it does |
|---|---|
| `make up` | Build and start postgres, chain, api and web; waits until all are healthy |
| `make smoke` | Smoke checks plus the full end-to-end script (auth, wallet, transfers, settlement, audit verification) |
| `make down` | Stop the stack and **keep** its data |
| `make reset` | Stop the stack and **delete** the database and chain volumes (all local data) |
| `make logs` | Follow all service logs |
| `make psql` | SQL shell on the bank's database |
| `make test` | API tests, contract tests, web lint and typecheck |
| `make test-api TEST=Name` | One API test suite |
| `make api-dev` / `make web-dev` | Run the API (Quarkus dev mode, own Postgres) or the web app outside Docker |
| `make openapi` | Refresh `docs/api/openapi.yaml` from the running API |

URLs:
- Web: http://127.0.0.1:3000
- API: http://127.0.0.1:8080, with the OpenAPI UI at `/q/swagger-ui`
- Chain RPC: http://127.0.0.1:8545
- Health: `/q/health/ready` (database and ledger invariants) and `/q/health/group/chain` (chain, contracts, pause)

## 3. Configuration

The API reads its configuration from `services/api/src/main/resources/application.properties`. These environment variables override it; Compose sets local-only values in `infra/docker-compose.yml`.

| Variable | Purpose | Local value | Production rule |
|---|---|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | PostgreSQL | Compose service `postgres` | Secret store |
| `TOTP_KEY` | AES-256 key (base64, 32 bytes) for TOTP secrets at rest | A fixed local key | **Required**; generate with `openssl rand -base64 32`; never commit it |
| `WEB_ORIGIN` | Allowed `Origin` for mutating requests | `http://localhost:3000` | The real site origin |
| `SESSION_COOKIE_SECURE` | `Secure` flag on the session cookie | `false` (HTTP on localhost) | `true` (HTTPS) |
| `WALLET_DOMAIN`, `WALLET_URI` | EIP-4361 domain and URI | `localhost:3000` | The real site |
| `CHAIN_RPC_URL`, `CHAIN_ID` | EVM node and the chain ID it must report | `http://chain:8545`, `31337` | Testnet or consortium node |
| `CHAIN_SETTLER_KEY` | Gateway key (SETTLER and COMMITTER roles). Unset means settlement is disabled | Anvil dev key #2 (**public**) | Secret store or HSM |
| `CHAIN_ADMIN_KEY` | Governance key (deploy, mint, pause) | Anvil dev key #1 (**public**) | Keep offline or in a multisig; don't give it to the API |
| `CHAIN_WALLET_BANK_A/B` | Each bank's settlement wallet address | Anvil accounts #3 and #4 | Real institution wallets |
| `CHAINBANK_CHAIN_AUTO_DEPLOY` | Deploy contracts on first run | `true` | `false`; deploy deliberately |
| `CHAINBANK_DEMO_SEED`, `CHAINBANK_DEMO_PASSWORD` | Create the 5 demo users | `true` | **Never** enable |

The business rules aren't environment variables. They're set in `application.properties` under `chainbank.transfers.*`, `chainbank.chain.confirmations`, `chainbank.audit.*` and `chainbank.rate-limit.*`; ADRs 0005–0007 explain each one.

## 4. Operations

### Tracing a request
Every API response carries `X-Correlation-Id`, and every log line prints `[cid=…]`. The same ID is stored on transfers (`transfers.correlation_id`) and audit events. To trace one:

```sh
make logs | grep <correlation-id>
```

### Is the ledger healthy?
`curl -s 127.0.0.1:8080/q/health/ready` reports `unbalancedJournals` and `driftedBalances`, which must both be 0. The **Reviews** and **Admin** pages show whether settlement positions match.

### Settlement is stuck
1. `curl -s 127.0.0.1:8080/q/health/group/chain`: is the RPC reachable, is the contract there, is it paused?
2. Admin page: look at the backlog (waiting to submit, awaiting confirmation).
3. ```sql
   SELECT status, attempts, last_error, next_attempt_at FROM outbox WHERE status <> 'PUBLISHED' ORDER BY created_at;
   SELECT status, confirmations, left(tx_hash, 12), last_checked_at FROM chain_txs WHERE status IN ('SUBMITTED','MINED','REORGED');
   ```
4. Rows that gave up (`DEAD`) appear as `MISSING_ON_CHAIN` exceptions. An operator can **Retry** them from the Reviews page.

### Emergency pause
Admin page → **Emergency pause** (step-up required, audited). Customers can still transfer; interbank settlement queues until you **Resume**.

### Audit checkpoint
Audit page → **Seal checkpoint now** (admin, step-up). Batches also seal automatically every 100 events or 10 minutes.

## 5. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `Cannot connect to the Docker daemon` or `500 Internal Server Error … docker.sock` | Docker Desktop crashed or is half-alive | Quit and reopen Docker Desktop (`open -a Docker`), wait until `docker ps` works, then run `make up` |
| http://localhost:3000 shows **a different app** | Another dev server (for example a Next.js portfolio) listens on `*:3000`, and `localhost` resolves to IPv6 `::1` | Use **http://127.0.0.1:3000** (Compose binds IPv4 only), or stop the other server. The scripts already use 127.0.0.1 |
| Login works at 127.0.0.1:3000 but every action fails with `ORIGIN_NOT_ALLOWED` | The browser sends `Origin: http://127.0.0.1:3000`, which isn't in `WEB_ORIGIN` | Browse via `localhost:3000` once the other server is stopped, or add the origin to `WEB_ORIGIN` |
| `make test-api` fails with `release version 21 not supported` | Maven is running on JDK 17 | Install JDK 21 (see §1) |
| A TOTP code is "invalid" although the app shows it | It was already used (codes are single-use), or the clock is off | Wait for the next code; check the system clock |
| Login says `RATE_LIMITED` | 5 failures for that account within 5 minutes | Wait 5 minutes, or restart the API (limits are in-memory) |
| Chain health check is DOWN: "no contract code at recorded address" | The chain volume was reset but the database wasn't | `make reset` (wipes both), then `make up` |
| `npm`/`pnpm` complains about `esbuild` build scripts | pnpm blocks install scripts by default | It's already allowed in `pnpm-workspace.yaml`; run `pnpm install` again |
| CI fails with "Contract ABI exported to the API is up to date" | A contract changed without re-export | `pnpm --filter @chainbank/contracts export` and commit the JSON files |

## 6. Resetting to a clean demo

```sh
make reset && make up && make smoke
```

This produces fresh banks, demo users, newly deployed contracts and a full end-to-end pass.
