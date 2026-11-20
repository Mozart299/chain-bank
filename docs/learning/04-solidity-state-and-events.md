# 4. Solidity state and events

## Key ideas

- **Contract state** (mappings and variables) is stored by every node, so writing it costs gas. **Events** are cheap logs that off-chain systems index. They can't be read by contracts.
- **Access control:** roles (OpenZeppelin `AccessControl`) decide who may call what. `SettlementToken` lets **only** `SETTLER_ROLE` move tokens, and ordinary ERC-20 `transfer` and `approve` revert, which makes the token *permissioned*.
- **Guards are invariants on-chain:** `settled[settlementId]` makes a settlement impossible to execute twice, whoever submits it, however often. `AuditAnchor` enforces sequential batch ids, contiguous event ranges and immutability.
- **Custom errors** (`revert AlreadySettled(id)`) are cheap and explain exactly why a call failed.
- **Pause** is an emergency brake. **Cap** limits total issuance.
- **Non-upgradeable by design:** what you deploy is what runs. A new version means a new deployment plus a migration ([ADR 0006](../adr/0006-onchain-settlement.md)).

## Read the code

| What | Where |
|---|---|
| Settlement token | `contracts/contracts/SettlementToken.sol` |
| Audit anchor | `contracts/contracts/AuditAnchor.sol` |
| Tests, including a 200-step property test | `contracts/test/*.test.ts` |
| Java side: ABI encoding and signing | `services/api/.../chain/SettlementAbi.java`, `ChainClient.java` |

## Hands-on

1. Run the contract tests: `make test-contracts`.
2. Query the live chain with Foundry's `cast` (it's inside the chain container). The contract addresses are on the Admin or Audit page:
   ```sh
   docker compose -f infra/docker-compose.yml exec chain cast call <token> "totalSupply()(uint256)"
   docker compose -f infra/docker-compose.yml exec chain cast call <token> "balanceOf(address)(uint256)" 0x90F79bf6EB2c4f870365E785982E1f101E93b906
   docker compose -f infra/docker-compose.yml exec chain cast logs --address <token> "Settled(bytes32,bytes32,bytes32,uint256)"
   ```
   The events carry only pseudonyms (`INST-A`) and amounts, never customer data.
3. As `sam.admin@chainbank.demo`, press **Emergency pause**, send an interbank transfer, and watch it wait. Then resume.

## Break it

| Change | Caught by |
|---|---|
| Remove `if (settled[settlementId]) revert AlreadySettled(...)` | `SettlementToken.test.ts` ("rejects a duplicate settlement id") **and** `SettlementFlowTest.duplicateSubmissionRevertsOnChainAndMovesNothing`: the money moves twice on-chain |
| Remove the `OutOfOrder` check in `AuditAnchor.commit` | `AuditAnchor.test.ts` ("rejects gaps, reordering and rewrites") |
| Delete the `transfer` override (re-enabling ERC-20 transfers) | "disables ordinary ERC-20 transfers and approvals" |

After changing a contract, run `pnpm --filter @chainbank/contracts export` so the API uses the new bytecode. CI fails if you forget.

## Check yourself

- Why are the on-chain events not enough to know a customer's balance?
- What would a public `mint` function let an attacker do?
- Why does the gateway key hold `SETTLER_ROLE` but not `DEFAULT_ADMIN_ROLE`?
