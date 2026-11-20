# contracts: Solidity

Hardhat 3, viem, OpenZeppelin 5, solc 0.8.28.

| Contract | Purpose | ADR |
|---|---|---|
| `SettlementToken` | Permissioned interbank settlement tokens (sxUGX, 0 decimals). Only `SETTLER_ROLE` can `settle(id, from, to, amount)`; duplicate IDs revert; ERC-20 transfers are disabled; capped; pausable | [0006](../docs/adr/0006-onchain-settlement.md) |
| `AuditAnchor` | Append-only Merkle roots of audit batches. Sequential IDs, contiguous event ranges, immutable | [0007](../docs/adr/0007-audit-commitments.md) |

Both are **non-upgradeable** by design.

```sh
pnpm --filter @chainbank/contracts test     # 15 tests, including a 200-step property test
pnpm --filter @chainbank/contracts export   # compile, then write ABI + bytecode for the API
```

**After changing a contract, run `export` and commit** `services/api/src/main/resources/contracts/*.json`. Bump `abiVersion` in `scripts/export-abi.mjs` if the interface changed. CI re-exports and fails on any difference.

Locally, the API deploys both contracts on first start (`CHAINBANK_CHAIN_AUTO_DEPLOY=true`) to the Anvil node in Compose. `hardhat.config.ts` also defines a `sepolia` network whose RPC URL and key come from `configVariable(...)`, resolved from the Hardhat keystore or environment variables, never from the repo.
