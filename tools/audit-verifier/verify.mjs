#!/usr/bin/env node
// ChainBank independent audit-batch verifier (ADR 0007).
//
// Verifies an exported audit batch WITHOUT trusting ChainBank's API or code: it re-derives every
// event hash, the hash-chain links and the RFC 6962 Merkle root from the export alone, then reads the
// commitment directly from the AuditAnchor contract on the chain.
//
// Usage:
//   node tools/audit-verifier/verify.mjs <batch-export.json> [--rpc URL] [--contract 0x...] [--confirmations N]
//
// Pass --contract with the anchor address the bank publishes out-of-band. Without it the address in the
// export is used (and the report says so, because a forged export could name a forged contract).
// Exit code: 0 when every check passes, 1 when any check fails, 2 on usage or input errors.

import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";

import { createPublicClient, http } from "viem";

const ANCHOR_ABI = [
  {
    type: "function",
    name: "batches",
    stateMutability: "view",
    inputs: [{ name: "", type: "uint256" }],
    outputs: [
      { name: "root", type: "bytes32" },
      { name: "firstEventId", type: "uint64" },
      { name: "lastEventId", type: "uint64" },
      { name: "blockNumber", type: "uint64" },
    ],
  },
];

// --- arguments -------------------------------------------------------------------------------------
const args = process.argv.slice(2);
const file = args.find((a) => !a.startsWith("--") && !isOptionValue(a));
function isOptionValue(a) {
  const i = args.indexOf(a);
  return i > 0 && args[i - 1].startsWith("--");
}
const opt = (name, fallback) => {
  const i = args.indexOf(`--${name}`);
  return i >= 0 ? args[i + 1] : fallback;
};
if (!file) {
  console.error("usage: verify.mjs <batch-export.json> [--rpc URL] [--contract 0x...] [--confirmations N]");
  process.exit(2);
}
const rpc = opt("rpc", "http://127.0.0.1:8545");
const requiredConfirmations = Number(opt("confirmations", "2"));
let exp;
try {
  exp = JSON.parse(readFileSync(file, "utf8"));
} catch (e) {
  console.error(`cannot read ${file}: ${e.message}`);
  process.exit(2);
}
if (exp.format !== "chainbank-audit-batch/1") {
  console.error(`unsupported export format: ${exp.format}`);
  process.exit(2);
}

// --- primitives (re-implemented from the published spec, no shared code) --------------------------
const sha256 = (buf) => createHash("sha256").update(buf).digest();
const hex = (buf) => buf.toString("hex");

/** sha256(utf8(concat(len + ":" + field + "|"))), len counted in UTF-16 code units. */
function eventHash(e) {
  const fields = [e.prevHash, e.occurredAt, e.actorUserId, e.action, e.resourceType, e.resourceId, e.correlationId, e.details];
  return hex(sha256(Buffer.from(fields.map((f) => `${f.length}:${f}|`).join(""), "utf8")));
}

/** RFC 6962 Merkle tree head. */
function merkleRoot(leaves) {
  if (leaves.length === 0) return sha256(Buffer.alloc(0));
  const mth = (from, to) => {
    const n = to - from;
    if (n === 1) return sha256(Buffer.concat([Buffer.from([0x00]), leaves[from]]));
    let k = 1;
    while (k * 2 < n) k *= 2;
    return sha256(Buffer.concat([Buffer.from([0x01]), mth(from, from + k), mth(from + k, to)]));
  };
  return mth(0, leaves.length);
}

// --- checks ------------------------------------------------------------------------------------------
const results = [];
const check = (name, ok, detail) => results.push({ name, ok, detail });
const events = exp.events ?? [];

const badHashes = events.filter((e) => eventHash(e) !== e.hash).map((e) => e.id);
check(
  "event hashes",
  badHashes.length === 0,
  badHashes.length === 0
    ? `${events.length} events recompute from their fields`
    : `event(s) ${badHashes.join(", ")} do not match their recorded hash (fields were edited)`,
);

let expectedPrev = exp.previousBatchLastHash ?? "";
const broken = [];
for (const e of events) {
  if (e.prevHash !== expectedPrev) broken.push(e.id);
  expectedPrev = e.hash;
}
const inRange = events.every((e) => e.id >= exp.firstEventId && e.id <= exp.lastEventId);
const ordered = events.every((e, i) => i === 0 || e.id > events[i - 1].id);
check(
  "hash chain",
  broken.length === 0 && inRange && ordered && events.length === exp.eventCount,
  broken.length > 0
    ? `link broken before event(s) ${broken.join(", ")} (an event was removed, inserted or re-hashed)`
    : !inRange || !ordered
      ? "events are out of order or outside the batch range"
      : events.length !== exp.eventCount
        ? `export lists ${events.length} events but claims ${exp.eventCount}`
        : "every event links to the previous one, starting from the previous batch",
);

const root = "0x" + hex(merkleRoot(events.map((e) => Buffer.from(e.hash, "hex"))));
check("merkle root", root === exp.merkleRoot, root === exp.merkleRoot ? `recomputed ${root}` : `recomputed ${root}, export says ${exp.merkleRoot}`);

const trustedContract = opt("contract", null);
const contract = (trustedContract ?? exp.contract ?? "").toLowerCase();
if (trustedContract && exp.contract && trustedContract.toLowerCase() !== exp.contract.toLowerCase()) {
  check("contract", false, `export names ${exp.contract}, but the published anchor is ${trustedContract}`);
}

try {
  const client = createPublicClient({ transport: http(rpc) });
  const chainId = await client.getChainId();
  check("chain", chainId === exp.chainId, `connected to chain ${chainId}${chainId === exp.chainId ? "" : `, export expects ${exp.chainId}`}`);
  const code = await client.getCode({ address: contract });
  if (!code || code === "0x") {
    check("on-chain commitment", false, `no contract at ${contract}`);
  } else {
    const [onRoot, first, last, block] = await client.readContract({
      address: contract,
      abi: ANCHOR_ABI,
      functionName: "batches",
      args: [BigInt(exp.batchId)],
    });
    // Compare the chain against the RECOMPUTED root, not just the export's claim.
    const matches = onRoot.toLowerCase() === root && Number(first) === exp.firstEventId && Number(last) === exp.lastEventId;
    check(
      "on-chain commitment",
      matches,
      matches
        ? `AuditAnchor ${contract} batch ${exp.batchId} holds the same root for events ${first}-${last}`
        : `chain holds root ${onRoot} for events ${first}-${last}`,
    );
    const latest = await client.getBlockNumber();
    const confirmations = block > 0n ? Number(latest - block + 1n) : 0;
    check("finality", confirmations >= requiredConfirmations, `${confirmations} confirmation(s), ${requiredConfirmations} required`);
  }
} catch (e) {
  check("on-chain commitment", false, `could not read the chain at ${rpc}: ${e.shortMessage ?? e.message}`);
}

// --- report ------------------------------------------------------------------------------------------
console.log(`ChainBank audit batch ${exp.batchId} · events ${exp.firstEventId}-${exp.lastEventId} (${events.length})`);
for (const r of results) console.log(`  ${r.ok ? "✓" : "✗"} ${r.name}: ${r.detail}`);
if (!trustedContract) console.log("  ! contract address taken from the export; pass --contract with the bank's published address");
const valid = results.every((r) => r.ok);
console.log(valid ? "VALID: this batch is exactly what the bank committed on-chain." : "INVALID: see the failed checks above.");
process.exit(valid ? 0 : 1);
