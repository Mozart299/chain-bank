import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { network } from "hardhat";
import { keccak256, toHex, zeroHash } from "viem";

const root = (s: string) => keccak256(toHex(s));

async function deploy() {
  const { viem } = await network.create();
  const [admin, committer, stranger] = await viem.getWalletClients();
  const anchor = await viem.deployContract("AuditAnchor", [admin.account.address]);
  await anchor.write.grantRole([await anchor.read.COMMITTER_ROLE(), committer.account.address]);
  const asCommitter = await viem.getContractAt("AuditAnchor", anchor.address, { client: { wallet: committer } });
  const asStranger = await viem.getContractAt("AuditAnchor", anchor.address, { client: { wallet: stranger } });
  return { viem, anchor, asCommitter, asStranger };
}

describe("AuditAnchor", () => {
  it("commits sequential, contiguous batches and emits the root", async () => {
    const { viem, anchor, asCommitter } = await deploy();
    await viem.assertions.emitWithArgs(asCommitter.write.commit([1n, 1n, 100n, root("a")]), anchor, "BatchCommitted", [
      1n,
      1n,
      100n,
      root("a"),
    ]);
    await asCommitter.write.commit([2n, 101n, 150n, root("b")]);
    const [r, first, last, block] = await anchor.read.batches([2n]);
    assert.equal(r, root("b"));
    assert.equal(first, 101n);
    assert.equal(last, 150n);
    assert.ok(block > 0n);
    assert.equal(await anchor.read.lastBatchId(), 2n);
  });

  it("rejects gaps, reordering and rewrites of committed batches", async () => {
    const { viem, anchor, asCommitter } = await deploy();
    await viem.assertions.revertWithCustomErrorWithArgs(asCommitter.write.commit([2n, 1n, 10n, root("x")]), anchor, "OutOfOrder", [1n]);
    await asCommitter.write.commit([1n, 1n, 10n, root("a")]);
    // Rewriting batch 1 is impossible: id 1 is no longer "next".
    await viem.assertions.revertWithCustomErrorWithArgs(asCommitter.write.commit([1n, 1n, 10n, root("evil")]), anchor, "OutOfOrder", [2n]);
    // Skipping events 11..19 is impossible.
    await viem.assertions.revertWithCustomErrorWithArgs(asCommitter.write.commit([2n, 20n, 30n, root("b")]), anchor, "NotContiguous", [11n]);
    const [r] = await anchor.read.batches([1n]);
    assert.equal(r, root("a"), "batch 1 unchanged");
  });

  it("rejects malformed batches", async () => {
    const { viem, anchor, asCommitter } = await deploy();
    await viem.assertions.revertWithCustomError(asCommitter.write.commit([1n, 1n, 10n, zeroHash]), anchor, "InvalidBatch");
    await viem.assertions.revertWithCustomError(asCommitter.write.commit([1n, 0n, 10n, root("a")]), anchor, "InvalidBatch");
    await viem.assertions.revertWithCustomError(asCommitter.write.commit([1n, 10n, 9n, root("a")]), anchor, "InvalidBatch");
  });

  it("only the committer can commit", async () => {
    const { viem, anchor, asStranger } = await deploy();
    await viem.assertions.revertWithCustomError(
      asStranger.write.commit([1n, 1n, 10n, root("a")]),
      anchor,
      "AccessControlUnauthorizedAccount",
    );
  });
});
