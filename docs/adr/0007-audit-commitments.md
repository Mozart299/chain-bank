# ADR 0007: Audit batch commitments and independent verification

- Status: accepted
- Date: 2026-11-20
- Resolves: spec §16 item 4 (event-batching strategy and checkpoint frequency)
- Implements: spec §5 use case 6, §10 `GET /api/audit/{batch}/verify`, §11 insider misuse

## Problem
`audit_events` is append-only and hash-chained (Phase 1), so tampering inside the database can be detected. That detection depends on the bank's own verifier, though. An insider who controls the database **and** the code could rewrite history and recompute every hash. Auditors need a check that doesn't depend on trusting the bank.

## Decisions

### Batching policy (spec §16 item 4)
- Seal a batch after **100 events or 10 minutes**, whichever comes first (`chainbank.audit.batch-size`, `chainbank.audit.max-age`).
- Admins can also **seal a checkpoint now** (SYSTEM_ADMIN with step-up, audited). Use it before an audit, or after an incident.
- A batch covers every event with `previous.last_event_id < id ≤ last_event_id`. Identity values used by rolled-back transactions leave holes in the ids. The `prev_hash` links, not dense ids, prove that nothing is missing.
- Once sealed, a batch can't change: a trigger rejects edits to its range or root, and rows can't be deleted.

### Hashing
- **Event hash** (unchanged since Phase 1, now published): `sha256(utf8(Σ len(f) ":" f "|"))` over `prevHash, occurredAt, actorUserId, action, resourceType, resourceId, correlationId, details(canonical JSON)`. The length prefix makes the encoding unambiguous.
- **Merkle tree: RFC 6962**, as in Certificate Transparency. `leaf = sha256(0x00‖h)`, `node = sha256(0x01‖l‖r)`, split at the largest power of two below n. It's checked against the CT reference vectors.
  - The 0x00/0x01 prefixes keep leaves and inner nodes from being confused.
  - There's no duplicate-last-leaf rule, so the Bitcoin CVE-2012-2459 ambiguity can't arise.
  - The root alone doesn't bind the tree size, so the size is fixed on-chain through the event range.

### `AuditAnchor` contract
- `commit(batchId, firstEventId, lastEventId, root)` can only be called by `COMMITTER_ROLE` (the gateway key).
- It enforces **sequential ids**, **contiguous ranges** (`first = previous.last + 1`) and **immutability** on-chain. The bank can't skip, reorder or rewrite committed history.
- It's anchored through the same single chain writer as settlement, so nonces never collide. A batch counts as `COMMITTED` after 2 confirmations, with a canonical-hash check.

### Verification
| Check | Server (`/api/audit/{id}/verify`) | Independent (`tools/audit-verifier`) |
|---|---|---|
| Event hashes recompute | ✓ | ✓ |
| Hash chain links, including from the previous batch | ✓ | ✓ |
| Merkle root recomputes | ✓ | ✓ |
| On-chain root and range equal the **recomputed** root | ✓ | ✓ |
| Finality (≥ 2 confirmations) | ✓ | ✓ |

The independent verifier is a standalone Node script that shares **no code** with the API. Its only dependency is viem, used to read the chain. An auditor needs to trust only the chain and the contract address the bank publishes; a forged export could name a forged contract, so the auditor passes `--contract` from an out-of-band source.

### Exception handling
Operators and admins, with step-up, can now act on reconciliation exceptions:
- **Resolve** with a mandatory note, recording who resolved it.
- **Retry** a dead outbox row (`MISSING_ON_CHAIN`), or a `REVERTED` settlement whose id is still unused on-chain. The worker resolves the exception automatically once the transfer is final.

Every action is audited, so it ends up in a committed batch too.

## Consequences
- An event becomes publicly anchored within about 10 minutes (or immediately after a checkpoint). Tampering inside that window is still caught by the in-database hash chain, but not yet by outsiders.
- Exports contain audit details: wallet addresses, amounts and institution codes, but no names or emails. They're only available to auditors, compliance reviewers and admins, and every export is itself audited.
