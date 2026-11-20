# ChainBank

The project is defined by **[docs/specification.md](docs/specification.md)**. Read the relevant sections before starting any feature or phase. Decisions that refine the spec live in `docs/adr/` and override it. Security status is tracked in `docs/threat-model.md`.

## Rules

- Build phase by phase, following spec §14. Each phase is done only when its exit condition is met and backed by tests.
- Money is `bigint` minor units of xUGX. Never use floating point.
- Ledger invariants are enforced in PostgreSQL (see ADR 0003). Never weaken a trigger or constraint to make code work.
- Migrations are append-only. Never edit an applied `V*__*.sql` file; add a new one.
- A wallet address never authenticates a user (spec §7.1).
- Only institution pseudonyms, settlement IDs, amounts, and commitments go on-chain. Never put PII on-chain.
- Never commit private keys or secrets. Testnet keys go in the Hardhat keystore.
- Record each new resolution of a spec §16 open decision as an ADR, and update the status table in §16.

## Commands

- `make up` / `make smoke` / `make down` / `make reset`: run the full stack
- `make test`: API tests (need Docker), contract tests, web lint and typecheck
- `make api-dev`, `make web-dev`: local development servers

## Gotchas

- `apps/web` uses Next.js 16 with `cacheComponents`. Read `apps/web/node_modules/next/dist/docs/` before relying on Next APIs from memory. Request-time data needs `await connection()` inside a `<Suspense>` boundary.
- Hardhat 3: use `network.create()`, not `network.connect()`, which is deprecated.
- The API is on Java 21 (web3j 6). Run the Makefile targets or set `JAVA_HOME` to a JDK 21; one is unpacked in `~/.jdks`. web3j uses Jackson 3, which needs `jackson-annotations` 2.22, pinned in the pom. Don't remove that pin.
- Changing the contract means running `pnpm --filter @chainbank/contracts export` and committing `services/api/src/main/resources/contracts/SettlementToken.json`, or CI fails. Bump `ABI_VERSION` if the interface changes.
- Settlement tests run against a real Anvil node (Testcontainers, `AnvilResource`). Anvil mines asynchronously, so wait for receipts before calling `worker.confirm()` (see `SettlementFlowTest.confirm()`).
- Never post committed test journals against real SETTLEMENT accounts. They're mirrored on-chain, and reconciliation will flag the difference.
- Audit hashing is a published spec (ADR 0007), implemented twice on purpose: `AuditLog.hashFields` and `tools/audit-verifier/verify.mjs`. Change both together, or not at all, and keep the verifier free of imports from the API.
- Only `SettlementWorker.runOnce()` signs transactions (settlement and audit anchors). Don't add another scheduled chain writer: nonces would collide.
- Chain-driving tests extend `ChainTestBase`. Its `mine()` and `confirm()` wait for pending receipts, because Anvil mines asynchronously.
- Smoke and e2e scripts target `127.0.0.1`, because another dev server may hold `localhost:3000` on IPv6.
- shadcn/ui: components live in `apps/web/src/components/ui`. Add more with `pnpm dlx shadcn@4.23.0 add <name>` (the radix-nova preset). Check that the generated files import `cn` from `@/lib/utils`; the CLI once wrote `from "cn"`.
- API security: every mutating request with a session needs `X-CSRF-Token`. Sensitive actions call `CurrentSession.requireStepUp()`. Record security events through `AuditLog` inside the same transaction.
- Next 16 + `cacheComponents`: a client component that calls `usePathname`/`useParams` above a dynamic route (`[id]`) must be inside `<Suspense>`, or `next build` fails. The header and the account shell are wrapped for this reason.
- Money paths: lock accounts first with `TransferService.lockAccounts` (UUID text order), reserve with a conditional UPDATE, and change state with compare-and-set. Never write `account_balances.ledger_minor` directly; only journal postings move it.
- Every `POST /api/transfers` needs an `Idempotency-Key`. The web send form makes a new one per distinct form state.
- API tests: extend `ApiTestBase`. Its `TestClock` moves time forward; use `nextCode()` for fresh TOTP codes, because codes are single-use.
