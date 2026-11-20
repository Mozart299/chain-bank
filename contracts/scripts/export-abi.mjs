// Exports the SettlementToken ABI and creation bytecode for the Java gateway (services/api).
// The output is committed; CI re-runs this and fails if it differs (stale ABI). Bump ABI_VERSION
// whenever the external interface changes.
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const ABI_VERSION = 1;
const here = dirname(fileURLToPath(import.meta.url));
const artifact = JSON.parse(
  readFileSync(join(here, "../artifacts/contracts/SettlementToken.sol/SettlementToken.json"), "utf8"),
);
const out = join(here, "../../services/api/src/main/resources/contracts/SettlementToken.json");
writeFileSync(
  out,
  JSON.stringify(
    { contractName: artifact.contractName, abiVersion: ABI_VERSION, abi: artifact.abi, bytecode: artifact.bytecode },
    null,
    2,
  ) + "\n",
);
console.log(`Exported ${artifact.contractName} (abiVersion ${ABI_VERSION}) -> ${out}`);
