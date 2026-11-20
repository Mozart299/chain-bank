import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { network } from "hardhat";
import { keccak256, stringToHex, toHex, type Address, type Hex } from "viem";

const INST_A = stringToHex("INST-A", { size: 32 });
const INST_B = stringToHex("INST-B", { size: 32 });
const INST_C = stringToHex("INST-C", { size: 32 });
const CAP = 10_000_000_000n;
const OPENING = 1_000_000_000n;

/** Same derivation as the Java gateway: keccak256(utf8("chainbank:transfer:" + uuid)). */
const settlementId = (transferId: string): Hex => keccak256(toHex(`chainbank:transfer:${transferId}`));

async function deploy() {
  const { viem } = await network.create();
  const [admin, settler, walletA, walletB, walletC, stranger] = await viem.getWalletClients();
  const token = await viem.deployContract("SettlementToken", [admin.account.address, CAP]);
  await token.write.registerInstitution([INST_A, walletA.account.address]);
  await token.write.registerInstitution([INST_B, walletB.account.address]);
  await token.write.registerInstitution([INST_C, walletC.account.address]);
  await token.write.grantRole([await token.read.SETTLER_ROLE(), settler.account.address]);
  await token.write.mint([INST_A, OPENING]);
  await token.write.mint([INST_B, OPENING]);
  await token.write.mint([INST_C, OPENING]);
  const asSettler = await viem.getContractAt("SettlementToken", token.address, { client: { wallet: settler } });
  const asStranger = await viem.getContractAt("SettlementToken", token.address, { client: { wallet: stranger } });
  const balance = (w: { account: { address: Address } }) => token.read.balanceOf([w.account.address]);
  return { viem, token, asSettler, asStranger, admin, settler, walletA, walletB, walletC, stranger, balance };
}

describe("SettlementToken", () => {
  it("has 0 decimals and the opening positions", async () => {
    const { token, walletA, balance } = await deploy();
    assert.equal(await token.read.decimals(), 0);
    assert.equal(await token.read.symbol(), "sxUGX");
    assert.equal(await balance(walletA), OPENING);
    assert.equal(await token.read.totalSupply(), 3n * OPENING);
  });

  it("settles between institutions and emits pseudonymous event values", async () => {
    const { viem, token, asSettler, walletA, walletB, balance } = await deploy();
    const id = settlementId("11111111-2222-3333-4444-555555555555");
    await viem.assertions.emitWithArgs(asSettler.write.settle([id, INST_A, INST_B, 75_000n]), token, "Settled", [
      id,
      INST_A,
      INST_B,
      75_000n,
    ]);
    assert.equal(await balance(walletA), OPENING - 75_000n);
    assert.equal(await balance(walletB), OPENING + 75_000n);
    assert.equal(await token.read.settled([id]), true);
  });

  it("rejects a duplicate settlement id", async () => {
    const { viem, token, asSettler, walletA, balance } = await deploy();
    const id = settlementId("dup");
    await asSettler.write.settle([id, INST_A, INST_B, 10n]);
    await viem.assertions.revertWithCustomErrorWithArgs(asSettler.write.settle([id, INST_A, INST_B, 10n]), token, "AlreadySettled", [id]);
    assert.equal(await balance(walletA), OPENING - 10n, "moved exactly once");
  });

  it("rejects invalid settlements", async () => {
    const { viem, token, asSettler } = await deploy();
    const unknown = stringToHex("INST-Z", { size: 32 });
    await viem.assertions.revertWithCustomError(asSettler.write.settle([settlementId("z"), INST_A, unknown, 1n]), token, "UnknownInstitution");
    await viem.assertions.revertWithCustomError(asSettler.write.settle([settlementId("zero"), INST_A, INST_B, 0n]), token, "InvalidSettlement");
    await viem.assertions.revertWithCustomError(asSettler.write.settle([settlementId("self"), INST_A, INST_A, 1n]), token, "InvalidSettlement");
    await viem.assertions.revertWithCustomError(
      asSettler.write.settle([settlementId("too-much"), INST_A, INST_B, OPENING + 1n]),
      token,
      "ERC20InsufficientBalance",
    );
  });

  it("only the settler can settle; only admin roles can register, mint and pause", async () => {
    const { viem, token, asStranger } = await deploy();
    const denied = "AccessControlUnauthorizedAccount";
    await viem.assertions.revertWithCustomError(asStranger.write.settle([settlementId("x"), INST_A, INST_B, 1n]), token, denied);
    await viem.assertions.revertWithCustomError(asStranger.write.mint([INST_A, 1n]), token, denied);
    await viem.assertions.revertWithCustomError(asStranger.write.registerInstitution([INST_C, asStranger.address]), token, denied);
    await viem.assertions.revertWithCustomError(asStranger.write.pause(), token, denied);
  });

  it("cannot register an institution twice or mint beyond the cap", async () => {
    const { viem, token, stranger } = await deploy();
    await viem.assertions.revertWithCustomError(
      token.write.registerInstitution([INST_A, stranger.account.address]),
      token,
      "InstitutionAlreadyRegistered",
    );
    await viem.assertions.revertWithCustomError(token.write.mint([INST_A, CAP]), token, "ERC20ExceededCap");
  });

  it("disables ordinary ERC-20 transfers and approvals", async () => {
    const { viem, token, walletA, walletB } = await deploy();
    const asA = await viem.getContractAt("SettlementToken", token.address, { client: { wallet: walletA } });
    await viem.assertions.revertWithCustomError(asA.write.transfer([walletB.account.address, 1n]), token, "TransfersDisabled");
    await viem.assertions.revertWithCustomError(asA.write.approve([walletB.account.address, 1n]), token, "TransfersDisabled");
    await viem.assertions.revertWithCustomError(
      asA.write.transferFrom([walletA.account.address, walletB.account.address, 1n]),
      token,
      "TransfersDisabled",
    );
  });

  it("emergency pause stops settlement and minting until unpaused", async () => {
    const { viem, token, asSettler } = await deploy();
    await token.write.pause();
    await viem.assertions.revertWithCustomError(asSettler.write.settle([settlementId("p"), INST_A, INST_B, 1n]), token, "EnforcedPause");
    await viem.assertions.revertWithCustomError(token.write.mint([INST_A, 1n]), token, "EnforcedPause");
    await token.write.unpause();
    await asSettler.write.settle([settlementId("p"), INST_A, INST_B, 1n]);
  });

  it("property: random settlements conserve supply and match an in-memory model", async () => {
    const { token, asSettler, walletA, walletB, walletC, balance } = await deploy();
    const insts = [INST_A, INST_B, INST_C];
    const wallets = [walletA, walletB, walletC];
    const model = [OPENING, OPENING, OPENING];
    let seed = 0x5eed;
    const rand = (n: number) => {
      seed = (seed * 1103515245 + 12345) & 0x7fffffff;
      return seed % n;
    };
    let reverted = 0;
    for (let i = 0; i < 200; i++) {
      const from = rand(3);
      const to = (from + 1 + rand(2)) % 3;
      // Occasionally ask for more than the sender holds: must revert and change nothing.
      const amount = rand(10) === 0 ? model[from] + 1n : BigInt(1 + rand(50_000_000));
      try {
        await asSettler.write.settle([settlementId(`prop-${i}`), insts[from], insts[to], amount]);
        model[from] -= amount;
        model[to] += amount;
      } catch {
        assert.ok(amount > model[from], `settle ${i} reverted unexpectedly`);
        reverted++;
      }
    }
    assert.ok(reverted > 0, "the property run should include failing settlements");
    for (let k = 0; k < 3; k++) assert.equal(await balance(wallets[k]), model[k], `balance ${k}`);
    assert.equal(await token.read.totalSupply(), 3n * OPENING, "settlement never creates or destroys tokens");
  });
});
