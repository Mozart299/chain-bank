#!/usr/bin/env node
// Phase 1 end-to-end check against the running stack (make up), through the web app's /api proxy:
// register -> TOTP confirm -> login -> me -> CSRF enforced -> wallet challenge -> sign -> verify
// -> replay rejected -> cross-bank transfer -> TOTP approval -> idempotent replay -> on-chain
// settlement reconciled -> balances -> logout -> session dead.
import { spawnSync } from "node:child_process";
import { createHmac, randomUUID } from "node:crypto";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { generatePrivateKey, privateKeyToAccount } from "viem/accounts";

// 127.0.0.1, not localhost: Compose binds IPv4 loopback, and another dev server on ::1 must not shadow it.
const WEB = process.env.WEB_URL ?? "http://127.0.0.1:3000";
// The browser origin the API allows (chainbank.web.allowed-origins).
const ORIGIN = process.env.WEB_ORIGIN ?? "http://localhost:3000";

let failures = 0;
const ok = (msg) => console.log(`  \x1b[32mok\x1b[0m  ${msg}`);
const fail = (msg) => {
  console.log(`  \x1b[31mFAIL\x1b[0m ${msg}`);
  failures++;
};
const expect = (cond, msg, detail) => (cond ? ok(msg) : fail(`${msg}${detail ? `: ${detail}` : ""}`));

// --- RFC 6238 TOTP (mirrors the API's implementation) --------------------------------------
function base32Decode(s) {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let bits = 0, value = 0;
  const out = [];
  for (const ch of s.replace(/=+$/, "")) {
    value = (value << 5) | alphabet.indexOf(ch);
    bits += 5;
    if (bits >= 8) {
      out.push((value >>> (bits - 8)) & 0xff);
      bits -= 8;
    }
  }
  return Buffer.from(out);
}

function totp(secretBase32, step) {
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(step));
  const h = createHmac("sha1", base32Decode(secretBase32)).update(counter).digest();
  const o = h[h.length - 1] & 0xf;
  const bin = ((h[o] & 0x7f) << 24) | (h[o + 1] << 16) | (h[o + 2] << 8) | h[o + 3];
  return String(bin % 1_000_000).padStart(6, "0");
}
const currentStep = () => Math.floor(Date.now() / 1000 / 30);

/** Next unused code: TOTP codes are single-use, and the server accepts steps within ±1 of now. */
async function freshCode(secretBase32, lastUsedStep) {
  const step = lastUsedStep + 1;
  while (currentStep() + 1 < step) await new Promise((r) => setTimeout(r, 1000));
  return { code: totp(secretBase32, step), step };
}

// --- minimal cookie-aware client --------------------------------------------------------------
async function call(path, { method = "GET", body, cookie, csrf } = {}) {
  const headers = { Accept: "application/json", Origin: ORIGIN };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (cookie) headers.Cookie = `cb_session=${cookie}`;
  if (csrf) headers["X-CSRF-Token"] = csrf;
  const res = await fetch(`${WEB}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    redirect: "manual",
  });
  const text = await res.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    // non-JSON
  }
  return { status: res.status, json, headers: res.headers };
}

console.log("ChainBank end-to-end (auth, wallet, transfers, settlement, audit)");

const email = `e2e-${randomUUID().slice(0, 8)}@example.test`;
const password = "e2e correct horse battery staple";

const reg = await call("/api/auth/register", {
  method: "POST",
  body: { email, password, displayName: "E2E", institutionCode: "BANK_A" },
});
expect(reg.status === 201 && reg.json?.totpSecret, "register returns TOTP enrollment", reg.status);
const secret = reg.json?.totpSecret;

// Codes are single-use: confirm with the current step, log in with the next (inside the ±1 window).
const step = currentStep();
const confirm = await call("/api/auth/totp/confirm", { method: "POST", body: { email, password, code: totp(secret, step) } });
expect(confirm.status === 204, "TOTP enrollment confirmed", confirm.status);

const reuse = await call("/api/auth/login", { method: "POST", body: { email, password, code: totp(secret, step) } });
expect(reuse.status === 401, "reused TOTP code rejected", reuse.status);

const login = await call("/api/auth/login", { method: "POST", body: { email, password, code: totp(secret, step + 1) } });
const setCookie = login.headers.get("set-cookie") ?? "";
const cookie = /cb_session=([^;]+)/.exec(setCookie)?.[1];
const csrf = login.headers.get("x-csrf-token");
expect(login.status === 204 && cookie && csrf, "login sets session cookie", login.status);
expect(/HttpOnly/i.test(setCookie) && /SameSite=Lax/i.test(setCookie), "cookie is HttpOnly + SameSite=Lax");

const me = await call("/api/auth/me", { cookie });
expect(me.status === 200 && me.json?.roles?.includes("CUSTOMER"), "me returns CUSTOMER", me.status);

const noCsrf = await call("/api/wallet/challenge", { method: "POST", cookie, body: { address: "0x" + "1".repeat(40), chainId: 31337 } });
expect(noCsrf.status === 403, "mutation without CSRF token rejected", noCsrf.status);

const account = privateKeyToAccount(generatePrivateKey());
const address = account.address.toLowerCase();
const ch = await call("/api/wallet/challenge", { method: "POST", cookie, csrf, body: { address, chainId: 31337 } });
expect(ch.status === 200 && ch.json?.message?.includes("Chain ID: 31337"), "wallet challenge issued", ch.status);

const signature = await account.signMessage({ message: ch.json.message });
const verify = await call("/api/wallet/verify", { method: "POST", cookie, csrf, body: { challengeId: ch.json.challengeId, signature } });
expect(verify.status === 201 && verify.json?.address === address, "signed challenge links wallet", verify.status);

const replay = await call("/api/wallet/verify", { method: "POST", cookie, csrf, body: { challengeId: ch.json.challengeId, signature } });
expect(replay.status === 404, "replayed signature rejected", replay.status);

const wallets = await call("/api/wallet", { cookie });
expect(wallets.status === 200 && wallets.json?.length === 1, "wallet listed", wallets.status);

// --- Phase 2: accounts and transfers ------------------------------------------------------------
const accounts = await call("/api/accounts", { cookie });
const myAccount = accounts.json?.[0];
expect(accounts.status === 200 && myAccount?.availableBalance === 10_000_000, "account opened with demo funds", accounts.status);

// A payee at the other bank.
const payeeEmail = `e2e-payee-${randomUUID().slice(0, 8)}@example.test`;
const payeeReg = await call("/api/auth/register", {
  method: "POST",
  body: { email: payeeEmail, password, displayName: "E2E Payee", institutionCode: "BANK_B" },
});
const payeeSecret = payeeReg.json?.totpSecret;
const pStep = currentStep();
await call("/api/auth/totp/confirm", { method: "POST", body: { email: payeeEmail, password, code: totp(payeeSecret, pStep) } });
const payeeLogin = await call("/api/auth/login", { method: "POST", body: { email: payeeEmail, password, code: totp(payeeSecret, pStep + 1) } });
const payeeCookie = /cb_session=([^;]+)/.exec(payeeLogin.headers.get("set-cookie") ?? "")?.[1];
const payeeAccount = (await call("/api/accounts", { cookie: payeeCookie })).json?.[0];
expect(payeeAccount?.accountNumber?.startsWith("RWZ-"), "payee account at the other bank", payeeAccount?.accountNumber);

const transferBody = {
  sourceAccountId: myAccount?.id,
  destinationAccountNumber: payeeAccount?.accountNumber,
  amount: 50_000,
  currency: "xUGX",
  reference: "e2e",
};
const key = randomUUID();
const sendIt = () =>
  fetch(`${WEB}/api/transfers`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Origin: ORIGIN,
      Cookie: `cb_session=${cookie}`,
      "X-CSRF-Token": csrf,
      "Idempotency-Key": key,
    },
    body: JSON.stringify(transferBody),
  });
const created = await sendIt();
const transfer = await created.json();
expect(created.status === 201 && transfer.state === "VALIDATED" && transfer.requiresStepUp, "new payee needs approval", created.status);

const { code: approveCode } = await freshCode(secret, step + 1);
const approved = await call(`/api/transfers/${transfer.id}/approve`, { method: "POST", cookie, csrf, body: { code: approveCode } });
expect(approved.status === 200 && approved.json?.state === "APPROVED", "approved cross-bank transfer is posted", approved.json?.state);

const replayed = await sendIt();
const replayBody = await replayed.json();
expect(
  replayed.status === 200 && replayed.headers.get("idempotent-replayed") === "true" && replayBody.id === transfer.id,
  "retry with same Idempotency-Key returns the original",
  replayed.status,
);

// --- Phase 3: on-chain settlement -----------------------------------------------------------
// The gateway submits the settlement, waits for 2 confirmations, indexes the event and reconciles.
let settled = null;
const deadline = Date.now() + 90_000;
while (Date.now() < deadline) {
  const r = await call(`/api/transfers/${transfer.id}`, { cookie });
  if (r.json?.state === "RECONCILED" || ["FAILED", "PENDING_INVESTIGATION"].includes(r.json?.state)) {
    settled = r.json;
    break;
  }
  await new Promise((res) => setTimeout(res, 1000));
}
expect(settled?.state === "RECONCILED", "cross-bank transfer settled on-chain and reconciled", settled?.state ?? "timeout");
expect(/^0x[0-9a-f]{64}$/.test(settled?.settlement?.txHash ?? ""), "transfer shows its settlement tx hash", settled?.settlement?.txHash);
expect(
  settled?.settlement?.status === "FINAL" && settled?.settlement?.confirmations >= settled?.settlement?.required,
  "settlement reached finality",
  `${settled?.settlement?.status} ${settled?.settlement?.confirmations}/${settled?.settlement?.required}`,
);

const after1 = (await call("/api/accounts", { cookie })).json?.[0];
const after2 = (await call("/api/accounts", { cookie: payeeCookie })).json?.[0];
expect(after1?.availableBalance === 9_950_000, "payer charged exactly once", after1?.availableBalance);
expect(after2?.availableBalance === 10_050_000, "payee credited", after2?.availableBalance);

// --- Phase 4: audit batch committed on-chain and verified independently ---------------------------
const DEMO_SECRET = "CHAINBANKDEMOTOTPSECRETXUGX23456";
const DEMO_PASSWORD = process.env.CHAINBANK_DEMO_PASSWORD ?? "chainbank-demo-password";

/** Demo users share one TOTP secret and may have used recent steps already: try fresh steps until one is accepted. */
async function demoLogin(email) {
  for (let attempt = 0; attempt < 3; attempt++) {
    const step = currentStep() + attempt;
    while (currentStep() + 1 < step) await new Promise((r) => setTimeout(r, 1000));
    const res = await call("/api/auth/login", { method: "POST", body: { email, password: DEMO_PASSWORD, code: totp(DEMO_SECRET, step) } });
    if (res.status === 204) {
      return { cookie: /cb_session=([^;]+)/.exec(res.headers.get("set-cookie") ?? "")?.[1], csrf: res.headers.get("x-csrf-token"), step };
    }
  }
  return null;
}
async function demoStepUp(session) {
  for (let step = session.step + 1; step <= session.step + 3; step++) {
    while (currentStep() + 1 < step) await new Promise((r) => setTimeout(r, 1000));
    const res = await call("/api/auth/step-up", { method: "POST", cookie: session.cookie, csrf: session.csrf, body: { code: totp(DEMO_SECRET, step) } });
    if (res.status === 204) return true;
  }
  return false;
}

const admin = await demoLogin("sam.admin@chainbank.demo");
expect(admin && (await demoStepUp(admin)), "admin signs in with step-up");
const sealed = await call("/api/audit/batches/seal", { method: "POST", cookie: admin?.cookie, csrf: admin?.csrf });
const batchId = sealed.json?.batchId;
expect(sealed.status === 200 && batchId > 0, "admin seals an audit checkpoint", sealed.status);

const auditor = await demoLogin("aria.audit@chainbank.demo");
let batch = null;
const anchorDeadline = Date.now() + 90_000;
while (Date.now() < anchorDeadline) {
  const list = await call("/api/audit/batches", { cookie: auditor?.cookie });
  batch = list.json?.find((b) => b.id === batchId);
  if (batch?.status === "COMMITTED" || batch?.status === "FAILED") break;
  await new Promise((r) => setTimeout(r, 1000));
}
expect(batch?.status === "COMMITTED", "batch Merkle root committed on-chain with finality", batch?.status ?? "timeout");

const status = await call("/api/admin/chain", { cookie: auditor?.cookie });
const exported = await call(`/api/audit/batches/${batchId}/export`, { cookie: auditor?.cookie });
expect(exported.status === 200 && exported.json?.events?.length > 0, "auditor downloads the batch export", exported.status);

const dir = mkdtempSync(join(tmpdir(), "chainbank-audit-"));
const exportFile = join(dir, `batch-${batchId}.json`);
writeFileSync(exportFile, JSON.stringify(exported.json));
const verifier = new URL("../tools/audit-verifier/verify.mjs", import.meta.url).pathname;
const rpcUrl = process.env.CHAIN_RPC_URL ?? "http://127.0.0.1:8545";
const run = (f) =>
  spawnSync(process.execPath, [verifier, f, "--rpc", rpcUrl, "--contract", status.json?.auditAnchor ?? ""], { encoding: "utf8" });
const good = run(exportFile);
expect(good.status === 0, "independent verifier accepts the untouched export", good.stdout.trim().split("\n").pop());

const tampered = structuredClone(exported.json);
tampered.events[0].action = "NOTHING_TO_SEE";
const tamperedFile = join(dir, `batch-${batchId}-tampered.json`);
writeFileSync(tamperedFile, JSON.stringify(tampered));
const bad = run(tamperedFile);
expect(bad.status === 1 && /\u2717 event hashes/.test(bad.stdout), "independent verifier rejects an edited event", bad.stdout.trim().split("\n").pop());

const logout = await call("/api/auth/logout", { method: "POST", cookie, csrf });
expect(logout.status === 204, "logout", logout.status);
const after = await call("/api/auth/me", { cookie });
expect(after.status === 401, "session dead after logout", after.status);

if (failures > 0) {
  console.log(`${failures} end-to-end check(s) failed.`);
  process.exit(1);
}
console.log("All end-to-end checks passed.");
