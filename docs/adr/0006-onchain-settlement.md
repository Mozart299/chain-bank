# ADR 0006: On-chain settlement, finality and reconciliation

- Status: accepted
- Date: 2026-11-20
- Resolves: spec §16 item 2 (minimum confirmation policy)
- Implements: spec §6 (blockchain gateway, worker, event indexer), §8.3–8.4, §11 (key compromise, contract vulnerability, chain outage/reorganisation)

## Decisions

### Runtime: Java 21 + web3j
The API moved from Java 17 to Java 21 LTS so it can use web3j 6.1, which handles signing, ABI encoding and log decoding. web3j 6 uses Jackson 3, which needs `jackson-annotations` 2.22; annotation releases only add types, so pinning it is safe for Quarkus's Jackson 2.21. Our own `EthSignatures` (wallet login) is kept, since it is tested byte-for-byte against viem.

### Contract: `SettlementToken` (non-upgradeable)
- OpenZeppelin 5: ERC20 + Capped + AccessControl + Pausable, with 0 decimals (xUGX).
- **Permissioned.** Only `settle(settlementId, fromPseudonym, toPseudonym, amount)` moves tokens, and only `SETTLER_ROLE` can call it. `transfer`, `transferFrom` and `approve` revert.
- **Settlement IDs are unique.** `settlementId = keccak256("chainbank:transfer:" + transferUuid)`, and a repeated id reverts with `AlreadySettled`. This is the backstop that makes any duplicate or replayed submission harmless.
- **No PII on-chain.** Events carry institution pseudonyms (`INST-A`), the settlement id and the amount, nothing else.
- **Roles.** `DEFAULT_ADMIN`, `MINTER` and `PAUSER` belong to the governance key, `SETTLER` to the gateway key. Minting is capped.
- **Upgrade strategy.** Deploy a new version, pause the old one, and migrate the opening positions. The ABI exported to the API is versioned (`abiVersion`), and CI fails if the committed export is stale.

### Keys
- Keys come from environment variables only (`CHAIN_SETTLER_KEY`, `CHAIN_ADMIN_KEY`) and are never sent to the browser.
- Compose uses Anvil's **public** dev keys #1 and #2, which is acceptable only because the chain is local and throwaway.
- A real deployment would keep the governance key offline (or in an HSM or multisig) and give the API only the settler key.

### Finality: **2 confirmations**
A settlement counts as final once its block has one block on top of it and its hash is still canonical. That's about 4 s on the local chain (2 s blocks). Set it with `chainbank.chain.confirmations`; a public testnet would use more.

### Worker (scheduled every 2 s, single instance)
1. **Submit.** Claim due outbox rows (`FOR UPDATE SKIP LOCKED`).
   - If the contract is paused, hold the rows without counting an attempt.
   - Otherwise sign the transaction and record `chain_txs` (including the signed raw transaction), move the transfer from `APPROVED` to `SUBMITTED`, mark the outbox row published, commit, and only then broadcast.
   - If the RPC is unavailable, back off exponentially (2^n s, capped at 5 min). After 10 attempts the row goes `DEAD` with a `MISSING_ON_CHAIN` exception.
2. **Confirm.** Fetch each open transaction's receipt.
   - **No receipt:** rebroadcast the stored raw transaction once `rebroadcast-after` (15 s) has passed. If the nonce was meanwhile consumed, re-sign with a fresh nonce; the duplicate guard makes that safe.
   - **Reverted:** if `settled(id)` is already true, record a `DUPLICATE` exception and mark it resolved. Otherwise move the transfer to `PENDING_INVESTIGATION` with a `REVERTED` exception.
   - **Its block is no longer canonical, or the receipt has disappeared (reorg):** move the transfer to `PENDING_INVESTIGATION` with a `REORG` exception, rebroadcast, and keep tracking. When the transaction is final again, the transfer becomes `CONFIRMED` and the exception is resolved automatically.
3. **Index.** Read `Settled` logs only up to the newest final block into `chain_events`, which is append-only and unique by `(tx, logIndex)`.
4. **Reconcile.** A `CONFIRMED` transfer with a matching event (id, amount, pseudonyms) becomes `RECONCILED`. An event with no matching transfer raises `MISSING_IN_JOURNAL`; any difference raises `AMOUNT_MISMATCH`.

Crash safety comes from recording before broadcasting: a crash at any point can only lead to a rebroadcast of an already-recorded transaction, never an unrecorded one.

### Positions
For each bank: on-chain balance at the last indexed final block = off-chain SETTLEMENT ledger + outgoing not yet settled − incoming not yet settled. A mismatch means money moved on only one side. The test suite caught exactly this: an older test that posted against a real settlement account.

### Availability
- Chain health has its own group (`/q/health/group/chain`) and does **not** affect API readiness. If the chain is down, customer banking continues and settlements queue up.
- Anvil persists its state to a volume alongside Postgres. If the chain loses the contract while the database still references it, the chain health check reports DOWN, and nothing is redeployed automatically.

## Consequences
- The design assumes one worker instance. Scaling out would need per-row leases and nonce management per sender.
- Reorgs deeper than the confirmation depth are out of scope: the indexer only reads final blocks and doesn't rewind them.
- An emergency pause also blocks minting (OpenZeppelin `_update`). That's intended.
