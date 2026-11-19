# ADR 0001: Architecture and repository layout

- Status: accepted
- Date: 2026-11-19

## Context

ChainBank teaches the difference between a bank ledger and blockchain settlement. The spec (§6, §15) proposes Next.js, Quarkus, PostgreSQL, Solidity/Hardhat, and Docker Compose in one repository.

## Decision

- One monorepo: `apps/web` (Next.js 16), `services/api` (Quarkus 3, Java 17, Maven wrapper), `contracts` (Hardhat 3, viem, OpenZeppelin 5), `packages/shared`, `infra`, `docs`.
- Node packages are a pnpm workspace. Java is built with Maven on its own.
- The API uses plain JDBC with explicit SQL, not an ORM, so ledger behaviour stays visible and easy to learn from.
- Flyway owns the schema. Migrations are append-only.
- The local chain is Anvil (chain id 31337) in Compose. Contract tests run on Hardhat's in-process EDR network.
- The browser never holds backend signing keys. Server-side Next.js code calls the API.

## Consequences

- Two build toolchains (Maven and pnpm). CI runs them as separate jobs.
- Quarkus Dev Services gives tests a real PostgreSQL through Docker, so the API tests need Docker.
