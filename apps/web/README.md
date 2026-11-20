# apps/web: Next.js dashboard

Next.js 16 (App Router, `cacheComponents`), Tailwind 4, shadcn/ui (Radix, Nova preset), and wagmi/viem for the browser wallet.

## Run

```sh
make web-dev          # from the repo root; expects the API on :8080 (make up or make api-dev)
pnpm --filter @chainbank/web lint
pnpm --filter @chainbank/web typecheck
pnpm --filter @chainbank/web build
```

`/api/*` is rewritten to `CHAINBANK_API_URL` (default `http://localhost:8080`), so the browser stays same-origin and session cookies are first-party. The rewrite is fixed **at build time**, which is why the Docker image takes it as a build argument.

## Pages

| Route | Who | What |
|---|---|---|
| `/` | everyone | Status, banks, sign-in |
| `/login`, `/register` | visitors | Password + TOTP; QR enrollment |
| `/account` | customers | Balance, pending approvals, activity |
| `/account/send` | customers | Transfer form (one `Idempotency-Key` per distinct form state) |
| `/account/transfers/[id]` | customers | Timeline, approve, cancel, live on-chain settlement |
| `/account/security` | signed-in users | Step-up, wallets, sessions |
| `/operator` | BANK_OPERATOR | Review queue, settlement positions, exception actions |
| `/admin` | SYSTEM_ADMIN | Chain status, emergency pause |
| `/audit` | AUDITOR, COMPLIANCE_REVIEWER, SYSTEM_ADMIN | Hash-chain status, batches, verify, export |

## Code
- `src/lib/client.ts`: the fetch wrapper (CSRF header, typed errors). `src/lib/banking.ts`: types, money formatting, status labels.
- `src/components/session-provider.tsx` loads `/api/auth/me` once for the whole app. `site-header.tsx` uses it.
- `src/components/ui/`: shadcn components. Add more with `pnpm dlx shadcn@4.23.0 add <name>`, then check that the files import `cn` from `@/lib/utils`.
- Next 16 gotcha: a component that calls `usePathname` or `useParams` above a dynamic route needs a `<Suspense>` boundary, or `next build` fails. Read `node_modules/next/dist/docs/` before relying on Next APIs from memory (see `AGENTS.md`).
