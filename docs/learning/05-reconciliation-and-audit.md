# 5. Reconciling off-chain and on-chain records

## Key ideas

- **Two records, one truth to prove.** The journal says "Nile owes Rwenzori 50,000", and the chain says "INST-A sent INST-B 50,000". **Reconciliation** matches them by settlement ID, amount and parties. Anything unmatched becomes an **exception** for a human.
- **Finality:** a transaction in the newest block can still disappear in a reorganisation. ChainBank counts a settlement as final after **2 confirmations**, and only if its block is still canonical.
- **Transactional outbox:** the transfer and its "please settle" message are written in one database transaction. A separate worker publishes it, so nothing is ever posted without being queued, or queued without being posted.
- **Record before broadcast:** the signed transaction is stored *before* it's sent. A crash can then only lead to re-sending the same transaction, and the contract's duplicate guard makes even a different transaction harmless.
- **Positions:** for each bank, the on-chain balance = the off-chain settlement ledger + outgoing not yet settled − incoming not yet settled. Any difference means money moved on only one side.
- **Tamper-evident audit:**
  - Every event's hash includes the previous event's hash.
  - Batches of event hashes become an RFC 6962 **Merkle root** committed to `AuditAnchor`.
  - Anyone can recompute that root from an export and compare it with the chain, without trusting the bank.

## Read the code

| What | Where |
|---|---|
| Worker: submit → confirm → reorg/revert handling | `services/api/.../chain/SettlementWorker.java` (start at the class diagram) |
| Event index, matching, positions, exceptions | `.../chain/SettlementReconciler.java` |
| Audit hash chain, batches, Merkle tree, verification | `.../audit/AuditLog.java`, `AuditBatcher.java`, `MerkleTree.java`, `AuditVerifier.java` |
| Independent verifier | `tools/audit-verifier/verify.mjs` |
| Decision records | [ADR 0006](../adr/0006-onchain-settlement.md), [ADR 0007](../adr/0007-audit-commitments.md) |

## Hands-on

1. Send an interbank transfer and watch its page: **Settling on-chain → 1 of 2 → 2 of 2 → Completed · reconciled**.
2. Follow it through the tables:
   ```sql
   SELECT state FROM transfers ORDER BY created_at DESC LIMIT 1;
   SELECT status, confirmations, block_number, left(tx_hash, 12) FROM chain_txs ORDER BY created_at DESC LIMIT 1;
   SELECT from_pseudonym, to_pseudonym, amount, block_number FROM chain_events ORDER BY id DESC LIMIT 1;
   ```
3. As `olivia.ops@nile.demo`, open **Reviews** to see the settlement positions (ledger vs on-chain). Both banks should say **Matched**.
4. As `aria.audit@chainbank.demo`, open **Audit**, click **Verify** on a batch, then **Download export** and run the copied verifier command. Edit one event's `action` in the file and run it again: it reports exactly which check fails.

## Break it

| Experiment | What happens | Test |
|---|---|---|
| Freeze the chain container mid-settlement | Backoff; the transfer stays APPROVED, then settles after unfreezing | `SettlementFlowTest.chainOutageBacksOffThenSettles` |
| Reorganise the chain under a settlement (`anvil_reorg`) | PENDING_INVESTIGATION, then a rebroadcast, then CONFIRMED with the exception resolved | `SettlementFlowTest.reorgIsDetectedInvestigatedAndRecovered` |
| Settle directly on-chain, bypassing the bank | MISSING_IN_JOURNAL, and positions show a mismatch | `SettlementFlowTest.rogueOnChainSettlementIsFlaggedAndPositionsDisagree` |
| Edit an audit event (or edit it *and* re-hash it) | Pinpointed by different checks | `AuditBatchTest.editingAnEventIsPinpointed`, `forgingAConsistentEventStillBreaksTheChainAndTheRoot` |

`make test-api TEST=SettlementFlowTest` (it starts its own Anvil node through Testcontainers).

## Check yourself

- Why does the worker store the signed transaction *before* broadcasting it?
- Why does the indexer only read events up to `latest − 1`?
- The Merkle root alone doesn't encode how many leaves it has. What in `AuditAnchor` makes up for that?
- What does an auditor need to trust to accept the independent verifier's "VALID"?
