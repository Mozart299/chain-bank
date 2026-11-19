import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { network } from "hardhat";

// Phase 0 smoke test: the simulated chain boots and funded dev accounts exist.
// The permissioned settlement contract and its tests arrive in Phase 3.
describe("local chain", async () => {
  const { viem } = await network.create();
  const publicClient = await viem.getPublicClient();

  it("exposes funded test accounts", async () => {
    const [bankA, bankB] = await viem.getWalletClients();
    assert.ok(bankA && bankB, "expected at least two wallet clients");
    const balance = await publicClient.getBalance({ address: bankA.account.address });
    assert.ok(balance > 0n);
  });

  it("mines blocks", async () => {
    const before = await publicClient.getBlockNumber();
    const [sender, receiver] = await viem.getWalletClients();
    const hash = await sender.sendTransaction({ to: receiver.account.address, value: 1n });
    const receipt = await publicClient.waitForTransactionReceipt({ hash });
    assert.equal(receipt.status, "success");
    assert.ok(receipt.blockNumber > before);
  });
});
