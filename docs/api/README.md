# API reference

- **Machine-readable:** [`openapi.yaml`](openapi.yaml) (OpenAPI 3.1, generated from the running API with `make openapi`).
- **Interactive:** http://127.0.0.1:8080/q/swagger-ui while the stack runs.

## Conventions

- **Same origin.** Browsers call `/api/*` on the web origin, and Next.js proxies it to the API. Session cookies are `HttpOnly; SameSite=Lax`.
- **CSRF.** Every non-GET request with a session must send `X-CSRF-Token` (from `GET /api/auth/me` or the login response). Any `Origin` header must be allowed.
- **Step-up.** Endpoints marked 🔐 need `POST /api/auth/step-up` with a fresh TOTP code within the last 5 minutes. Otherwise they return `403 STEP_UP_REQUIRED`.
- **Errors.** Always `{status, code, message, correlationId}`. Unknown IDs and other users' IDs both return `404`, never `403`, so the API doesn't reveal what exists.
- **Money.** Integer xUGX (no decimals), never floating point.
- **Idempotency.** `POST /api/transfers` requires an `Idempotency-Key` header. A replay returns `200` with `Idempotent-Replayed: true`; the same key with a different body returns `422`.

## Endpoints

| Method and path | Who | Notes |
|---|---|---|
| `POST /api/auth/register` | public | Returns a TOTP enrollment (`otpauth://`) |
| `POST /api/auth/totp/confirm` | public | Activates the account and opens it with demo funds |
| `POST /api/auth/login` | public | Email + password + code; sets the session cookie |
| `POST /api/auth/logout` | session | |
| `GET /api/auth/me` | session | User, roles, CSRF token, step-up expiry |
| `POST /api/auth/step-up` | session | Fresh TOTP code |
| `POST /api/auth/sessions/revoke-all` | session 🔐 | |
| `POST /api/wallet/challenge` · `POST /api/wallet/verify` | CUSTOMER | EIP-4361 challenge and signature |
| `GET /api/wallet` · `DELETE /api/wallet/{id}` 🔐 | CUSTOMER | |
| `GET /api/accounts` · `GET /api/accounts/{id}/transactions` | CUSTOMER | Balances (ledger, reserved, available) and history |
| `POST /api/transfers` | CUSTOMER | `Idempotency-Key` required |
| `GET /api/transfers` · `GET /api/transfers/{id}` | CUSTOMER (payee once posted; operators of either bank) | Includes the timeline and on-chain settlement |
| `POST /api/transfers/{id}/approve` | CUSTOMER | `{code}`, bound to the transfer |
| `POST /api/transfers/{id}/cancel` | CUSTOMER | Only while VALIDATED |
| `GET /api/operator/reviews` · `POST /api/operator/transfers/{id}/review` 🔐 | BANK_OPERATOR (sending bank) | Flagged interbank transfers |
| `GET /api/reconciliation/exceptions` | operator, auditor, compliance, admin | Spec §10 |
| `POST /api/reconciliation/exceptions/{id}/resolve` 🔐 · `/retry` 🔐 | BANK_OPERATOR, SYSTEM_ADMIN | |
| `GET /api/settlement/positions` | operator, auditor, compliance, admin | Ledger vs on-chain balance per bank |
| `GET /api/admin/chain` | admin, auditor, operator | Contracts, pause state, backlog |
| `POST /api/admin/chain/pause` 🔐 · `/unpause` 🔐 | SYSTEM_ADMIN | Spec §8.4 |
| `POST /api/admin/users/{id}/suspend` 🔐 | SYSTEM_ADMIN | Also revokes the user's sessions |
| `GET /api/audit/chain/verify` | auditor, compliance, admin | Re-hashes the whole log |
| `GET /api/audit/batches` · `GET /api/audit/batches/{id}/export` | auditor, compliance, admin | Export for `tools/audit-verifier` |
| `GET /api/audit/{batch}/verify` | auditor, compliance, admin | Spec §10 |
| `POST /api/audit/batches/seal` 🔐 | SYSTEM_ADMIN | Checkpoint now |
| `GET /api/institutions` | public | |
| `GET /q/health/ready` · `GET /q/health/group/chain` | public | Readiness (DB, ledger) and chain status |
