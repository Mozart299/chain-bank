# ADR 0005: Transfers, limits, accounting model and review policy

- Status: accepted
- Date: 2026-11-20
- Resolves: spec §16 item 2 (sample limits; the confirmation policy is deferred to Phase 3) and item 3 (operator approval model)
- Implements: spec §8.1–8.2, §11 (duplicate transfer, double spend)

## Policy decisions

| Setting | Value (xUGX) | Config key |
|---|---|---|
| Customer step-up approval | amount **> 1,000,000**, or the first transfer to a payee | `chainbank.transfers.step-up-threshold` |
| Bank-operator review | cross-bank **and** amount **> 2,000,000** | `chainbank.transfers.review-threshold` |
| Daily limit per customer | **5,000,000** (rejected, cancelled and failed transfers don't count) | `chainbank.transfers.daily-limit` |
| Demo opening deposit | **10,000,000** per new customer | `chainbank.transfers.opening-balance` |
| Approval window | 15 minutes; then the transfer is cancelled and the reservation released | `chainbank.transfers.approval-ttl` |

Only flagged transfers get operator review. That gives the operator role real work without slowing every interbank payment. The reviewer must be a `BANK_OPERATOR` at the **sending** bank, must have a fresh step-up, and can't be the initiator.

## Accounting model

| Event | Lines |
|---|---|
| Account opening | DR bank CASH / CR customer DEPOSIT |
| Same bank | DR payer DEPOSIT / CR payee DEPOSIT |
| Cross bank | DR payer DEPOSIT / CR sending bank SETTLEMENT · DR receiving bank SETTLEMENT / CR payee DEPOSIT |

- Deposits are CREDIT-normal (liabilities). CASH and SETTLEMENT are DEBIT-normal (assets).
- Migration V4 strengthens the commit-time journal check: **every journal must balance within each institution**, not just overall. A cross-bank transfer is one journal. Each bank's half nets to zero against that bank's settlement position, so neither bank's books can end up lopsided.
- The settlement positions are what Phase 3 mirrors on-chain with permissioned settlement tokens.

## Lifecycle

`REQUESTED → VALIDATED → APPROVED → CONFIRMED` (same bank). Alternative endings are `REJECTED` (insufficient funds, daily limit, operator) and `CANCELLED` (customer, or expiry).

A cross-bank transfer stops at `APPROVED`. Its journal is posted and an outbox `SETTLEMENT_REQUESTED` row is written in the same transaction. Phase 3 takes it from there: `SUBMITTED → CONFIRMED → RECONCILED`.

## Concurrency and idempotency
- **Lock order.** Every operation first locks all involved `accounts` rows (source, destination, and both settlement accounts when cross-bank) with `ORDER BY id::text FOR UPDATE`. One global order means no deadlocks. A retry on 40P01/40001 is a backstop.
- **Reservation.** `reserved_minor += amount` only `WHERE ledger_minor - reserved_minor >= amount`. When nothing is updated, the transfer is REJECTED (`INSUFFICIENT_FUNDS`). The Phase 0 trigger independently forbids any negative ledger balance.
- **State changes are compare-and-set** (`WHERE state = ?`), and every change appends to `transfer_events`.
- **Idempotency.**
  - `UNIQUE (initiated_by, idempotency_key)` plus a SHA-256 fingerprint of the request.
  - The key is re-checked after taking the account locks, so concurrent duplicates line up and the later one sees the first.
  - Same key and same body returns 200 with `Idempotent-Replayed: true`. Same key with a different body returns 422.
  - Rejections are stored as well, so a retry gets the same answer.
- **Approvals** live in `approvals`, which is append-only, one per method. Each stores a `context_hash` over id, source, destination, amount, currency and expiry. Posting re-derives the hash, so an approval only ever authorises exactly the transfer it was given for.

## Consequences
- Locking both settlement accounts serialises cross-bank postings for a bank pair. That's fine at simulator scale; real systems would use striped positions or deferred netting.
- The daily limit uses UTC days.
- The web client generates a new `Idempotency-Key` whenever the form changes and reuses it for retries of an unchanged form.
