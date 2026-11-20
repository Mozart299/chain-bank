# ChainBank

A full-stack banking simulator for learning cryptographic identity, secure backend engineering, and the difference between a bank ledger and blockchain settlement.

The project is guided by the **[specification](docs/specification.md)**. Decisions that refine it are in [`docs/adr/`](docs/adr/), and security status is in [`docs/threat-model.md`](docs/threat-model.md).

> **Simulation only.** It holds no real money, no real customer data, and no claim to bank deposits. xUGX and the settlement tokens are fictional.

## Layout

| Path | What |
| --- | --- |
| `apps/web` | Next.js 16 dashboard (shadcn/ui, wagmi) |
| `services/api` | Quarkus 3 API (Java 17), Flyway migrations, PostgreSQL ledger |
| `contracts` | Hardhat 3 + viem + OpenZeppelin 5: the permissioned `SettlementToken` |
| `packages/shared` | Shared API schemas and generated contract types (later phases) |
| `infra` | Docker Compose: PostgreSQL 17, Anvil local chain, API, web |
| `docs` | Specification, ADRs, threat model |

## Quick start

You need Docker. For local development you also need Node 22+ with pnpm 10, and **Java 21** (the Makefile also finds a JDK unpacked in `~/.jdks`).

```sh
make up      # build and boot postgres, chain, api, web (waits for health)
make smoke   # smoke checks plus the auth/wallet end-to-end script
```

- Web: http://localhost:3000
- API: http://localhost:8080. OpenAPI UI at `/q/swagger-ui`, readiness at `/q/health/ready`
- Chain RPC: http://localhost:8545 (chain id 31337)

`make reset` stops the stack and deletes the database volume.

## Development

```sh
pnpm install
make api-dev          # Quarkus dev mode; Dev Services starts its own Postgres
make web-dev          # Next.js on :3000, talks to CHAINBANK_API_URL (default :8080)
make test             # API tests, contract tests, web lint and typecheck
```

## Demo users

`make up` seeds five fictional users. They all share the password `chainbank-demo-password` and the TOTP secret `CHAINBANKDEMOTOTPSECRETXUGX23456`. Add that secret to any authenticator app (as a manual key, time-based, 6 digits), then sign in at http://localhost:3000/login.

| Email | Role |
| --- | --- |
| `alice@nile.demo` | Customer, Nile Demo Bank |
| `bob@rwenzori.demo` | Customer, Rwenzori Demo Bank |
| `olivia.ops@nile.demo` | Bank operator |
| `sam.admin@chainbank.demo` | System administrator |
| `aria.audit@chainbank.demo` | Auditor |

These credentials are public on purpose, like Anvil's dev keys. Seeding only runs when `CHAINBANK_DEMO_SEED=true`.

To link a wallet, add the Anvil network to MetaMask (RPC `http://localhost:8545`, chain ID `31337`). Importing one of Anvil's public dev keys gives you a funded test account.

## Sending money

Every customer gets a current account with **xUGX 10,000,000** of fictional funds. Bob's account number is on his Overview page (for example `RWZ-10000002`).

1. Sign in as Alice and open **Send money**. Enter Bob's account number and an amount.
2. A first payment to a payee, or any amount over **1,000,000**, waits for approval. Enter a code from your authenticator app on the transfer page. Unapproved transfers are cancelled after 15 minutes and the held funds are released.
3. Interbank payments over **2,000,000** also need a bank operator. Sign in as `olivia.ops@nile.demo`, open **Reviews**, verify with a code, then approve or reject.
4. A same-bank payment completes immediately. An interbank payment is settled on the local chain. Its page updates live: *Settling on-chain*, then confirmations 0/2, 1/2, 2/2, then *Completed · reconciled*. It also shows the transaction hash.
5. Operators (`/operator`) and admins (`/admin`) see each bank's settlement position, ledger against on-chain, plus any reconciliation exceptions. Admins can use **Emergency pause** (with a step-up code): transfers keep posting, and settlements queue until you resume.

> If http://localhost:3000 shows a different app, another dev server is using port 3000 on IPv6. Use http://127.0.0.1:3000 or stop the other server.

The daily limit is **5,000,000** per customer. The rules and the accounting are in [ADR 0005](docs/adr/0005-transfers-limits-and-accounting.md).

## Seed data

Migration `V2` creates two fictional banks: **BANK_A** (Nile Demo Bank, `INST-A`) and **BANK_B** (Rwenzori Demo Bank, `INST-B`). Each has equity, settlement, and suspense accounts. An opening journal gives each bank 1,000,000,000 xUGX of settlement capacity.

## Roadmap

| Phase | Status |
| --- | --- |
| 0 Foundation: repo, ADRs, Docker, schema, CI, seeded banks | done |
| 1 Authentication: sessions, roles, TOTP, wallet challenge and link | done |
| 2 Core banking: accounts, transfers, state machine, idempotency | done |
| 3 Blockchain: permissioned settlement contract, gateway, receipts | done |
| 4 Audit and UX: dashboards, batch commitments, exceptions | next |
| 5 Hardening: threat review, failure exercises, testnet | |
