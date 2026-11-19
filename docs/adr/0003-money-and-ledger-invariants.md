# ADR 0003: Money representation and database-enforced ledger invariants

- Status: accepted
- Date: 2026-11-19
- Resolves: spec §16, open decision 2 (currency)

## Decision

- The test currency is **xUGX**: a fictional shilling with exponent 0, matching ISO 4217 UGX. Amounts are `bigint` minor units and never floating point.
- PostgreSQL enforces the ledger invariants itself, so a bug in the application cannot break them:
  - Every journal line is a debit or a credit, never both, and never negative.
  - A line's currency must match its journal and its account.
  - A deferred constraint trigger checks at commit that every journal has at least two lines and that debits equal credits.
  - `journals`, `journal_lines`, `transfer_events` and `audit_events` are append-only. Triggers reject `UPDATE` and `DELETE`. Corrections are made with reversing journals.
  - `account_balances` is a projection maintained only by the posting trigger. The trigger's row update serialises concurrent postings to the same account, and it rejects a negative balance unless the account has `allow_negative` set (suspense accounts only).
- Accounts carry a `normal_side`: DEBIT for assets such as settlement, CREDIT for liabilities and equity such as customer deposits.
- A readiness health check recomputes the totals and reports any unbalanced journal or drifted balance.

## Consequences

- Concurrency safety for available funds (reservations) is added in Phase 2 on top of these guarantees.
- Migrations that touch these triggers need extra review.
