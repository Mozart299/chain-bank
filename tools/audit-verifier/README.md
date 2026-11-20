# Independent audit-batch verifier

A standalone checker for ChainBank's audit trail. It shares **no code** with the bank: it re-implements the published hashing rules (ADR 0007) and reads the commitment straight from the chain. An auditor needs to trust only two things: **the chain**, and **the anchor contract address the bank publishes**.

```sh
# 1. Download a batch export (Audit page → "Download export", or GET /api/audit/batches/{id}/export)
# 2. Verify it
node tools/audit-verifier/verify.mjs chainbank-audit-batch-7.json --rpc http://127.0.0.1:8545 --contract 0x…
```

It exits with 0 when the batch is valid, 1 when any check fails, and 2 on bad input.

| Check | What it proves |
|---|---|
| event hashes | Each event's fields still produce its recorded hash, so nothing inside an event was edited. |
| hash chain | Each event's `prevHash` is the previous event's hash, starting from the previous batch. Nothing was inserted, removed or re-ordered. |
| merkle root | The RFC 6962 root over the event hashes is recomputed from scratch. |
| on-chain commitment | `AuditAnchor.batches(id)` holds **the recomputed root** and the same event range. The bank committed exactly these events, publicly, at that time. |
| finality | The commitment block has enough confirmations (2 by default; `--confirmations N`). |

## Hashing rules

- **Event hash:** `sha256(utf8(join(len(f) + ":" + f + "|")))` over `prevHash, occurredAt, actorUserId, action, resourceType, resourceId, correlationId, details`. `len` counts UTF-16 code units. Missing values are empty strings.
- **Merkle tree (RFC 6962):** `leaf = sha256(0x00 ‖ hash)`, `node = sha256(0x01 ‖ left ‖ right)`, split at the largest power of two below n.

## Trust model

- A forged export can name a forged contract. Always pass `--contract` with the address the bank publishes out of band. The verifier fails if the two differ.
- The verifier compares the chain against the root **it recomputed**, never the root the export claims.
- The contract only accepts sequential batch ids with contiguous event ranges, and committed batches are immutable. The bank therefore can't rewrite or skip history after the fact without it showing on-chain.
