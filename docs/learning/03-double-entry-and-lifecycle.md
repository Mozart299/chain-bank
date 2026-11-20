# 3. Double-entry accounting and the transfer lifecycle

## Key ideas

- **Double entry:** every movement is a journal of lines where **debits = credits**. Money is never created or destroyed, only moved between accounts.
- **Normal side:** assets (cash, settlement) grow with debits. Liabilities and equity (customer deposits, owners' equity) grow with credits. A customer deposit is the bank's **liability**: money the bank owes the customer.
- **Cross-bank transfers balance within each bank:** each bank's half nets against that bank's settlement position.

  | | Debit | Credit |
  |---|---|---|
  | Opening | bank CASH 10,000,000 | Alice DEPOSIT 10,000,000 |
  | Same bank | Alice DEPOSIT | Ann DEPOSIT |
  | Cross bank | Alice DEPOSIT · Rwenzori SETTLEMENT | Nile SETTLEMENT · Bob DEPOSIT |

- **Invariants live in the database,** so an application bug can't break them. Journals are append-only, must balance at commit (overall and per bank), and non-overdraft accounts can't go negative.
- **The state machine** is: `REQUESTED → VALIDATED → APPROVED → CONFIRMED` (same bank), or onwards via `SUBMITTED → CONFIRMED → RECONCILED` (cross bank). The alternative endings are `REJECTED` and `CANCELLED`.
- **Concurrency:**
  - lock every involved account in one global order (so no deadlocks),
  - reserve funds with a conditional `UPDATE` (so no overdraft),
  - move between states with compare-and-set (so racing approve and cancel can't both win).
- **Idempotency:** an `Idempotency-Key` plus a request fingerprint means a retried request returns the original result instead of moving money again.

## Read the code

| What | Where |
|---|---|
| Triggers: balance, immutability, non-negative, per-bank | `db/migration/V1__core_schema.sql`, `V4__core_banking.sql` |
| Account opening (DR cash / CR deposit) | `services/api/.../ledger/AccountService.java` |
| Lifecycle, locks, reservation, approvals, posting | `.../transfer/TransferService.java` (start at the class comment) |
| Decision records | [ADR 0003](../adr/0003-money-and-ledger-invariants.md), [ADR 0005](../adr/0005-transfers-limits-and-accounting.md) |

## Hands-on

1. As Alice, send 50,000 to Bob's account number (on his Overview page), approve it with your code, and open the transfer's timeline.
2. Read the journal it produced:
   ```sql
   SELECT j.reference, a.name, l.debit_minor, l.credit_minor
   FROM journal_lines l JOIN journals j ON j.id = l.journal_id JOIN accounts a ON a.id = l.account_id
   WHERE j.reference LIKE 'TRF-%' ORDER BY l.id DESC LIMIT 4;
   ```
3. Try to cheat the database directly; every one of these is refused:
   ```sql
   UPDATE journal_lines SET debit_minor = 1;                       -- append-only
   BEGIN; INSERT INTO journals (reference, currency, description) VALUES ('X', 'xUGX', 'x'); COMMIT;   -- no lines
   ```
4. Send the same request twice with the same key (the send form does this if you double-click). One transfer, one journal.

## Break it

| Change | Caught by |
|---|---|
| In `TransferService.reserve`, replace `ledger_minor - reserved_minor >= ?` with `? >= 0` | `TransferConcurrencyTest.parallelSpendingCannotOverdraw` (30 parallel spends) |
| Remove the `findByKey` re-check under the lock in `createTx` | `IdempotencyTest.concurrentDuplicatesCreateExactlyOneTransferAndOneJournal` (the unique-constraint fallback still saves you: find out why) |
| In V4, drop the per-institution part of `journal_balanced_check` (in a new migration) | `LedgerSchemaTest.journalMustBalanceWithinEachBank` |

`make test-api TEST=TransferConcurrencyTest`

## Check yourself

- Why is a customer's deposit a *credit* balance on the bank's books?
- Why must the accounts be locked in the same order everywhere?
- Why does a rejected transfer still get stored when it moves no money?
- What exactly do the `approvals.context_hash` values bind, and what attack does that stop?
