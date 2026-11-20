// Exports contract ABIs and creation bytecode for the Java gateway (services/api).
// The output is committed; CI re-runs this and fails if it differs (stale ABI). Bump ABI_VERSION
// whenever the external interface changes.
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

// Bump a contract's abiVersion whenever its external interface changes.
const CONTRACTS = [
  { name: "SettlementToken", abiVersion: 1 },
  { name: "AuditAnchor", abiVersion: 1 },
];
const here = dirname(fileURLToPath(import.meta.url));
for (const { name, abiVersion } of CONTRACTS) {
  const artifact = JSON.parse(readFileSync(join(here, `../artifacts/contracts/${name}.sol/${name}.json`), "utf8"));
  const out = join(here, `../../services/api/src/main/resources/contracts/${name}.json`);
  writeFileSync(
    out,
    JSON.stringify({ contractName: artifact.contractName, abiVersion, abi: artifact.abi, bytecode: artifact.bytecode }, null, 2) +
      "\n",
  );
  console.log(`Exported ${name} (abiVersion ${abiVersion}) -> ${out}`);
}
