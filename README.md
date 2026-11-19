# ChainBank

A full-stack banking simulator for learning cryptographic identity, secure backend engineering, and the difference between a bank ledger and blockchain settlement.

The project is guided by the **[specification](docs/specification.md)**. Decisions that refine it are in [`docs/adr/`](docs/adr/), and security status is in [`docs/threat-model.md`](docs/threat-model.md).

> **Simulation only.** It holds no real money, no real customer data, and no claim to bank deposits. xUGX and the settlement tokens are fictional.

## Layout

| Path | What |
| --- | --- |
| `apps/web` | Next.js 16 dashboard |
| `services/api` | Quarkus 3 API (Java 17), Flyway migrations, PostgreSQL ledger |
| `contracts` | Hardhat 3 + viem + OpenZeppelin 5 (settlement contract arrives in Phase 3) |
| `packages/shared` | Shared API schemas and generated contract types (later phases) |
| `infra` | Docker Compose: PostgreSQL 17, Anvil local chain, API, web |
| `docs` | Specification, ADRs, threat model |

## Quick start

You need Docker. For local development you also need Node 22+ with pnpm 10, and Java 17.

```sh
make up      # build and boot postgres, chain, api, web (waits for health)
make smoke   # end-to-end checks against the running stack
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

## Seed data

Migration `V2` creates two fictional banks: **BANK_A** (Nile Demo Bank, `INST-A`) and **BANK_B** (Rwenzori Demo Bank, `INST-B`). Each has equity, settlement, and suspense accounts. An opening journal gives each bank 1,000,000,000 xUGX of settlement capacity.

## Roadmap

| Phase | Status |
| --- | --- |
| 0 Foundation: repo, ADRs, Docker, schema, CI, seeded banks | done |
| 1 Authentication: sessions, roles, TOTP, wallet challenge and link | next |
| 2 Core banking: accounts, transfers, state machine, idempotency | |
| 3 Blockchain: permissioned settlement contract, gateway, receipts | |
| 4 Audit and UX: dashboards, batch commitments, exceptions | |
| 5 Hardening: threat review, failure exercises, testnet | |
