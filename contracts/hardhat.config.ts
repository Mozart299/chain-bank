import hardhatToolboxViemPlugin from "@nomicfoundation/hardhat-toolbox-viem";
import { configVariable, defineConfig } from "hardhat/config";

export default defineConfig({
  plugins: [hardhatToolboxViemPlugin],
  solidity: {
    profiles: {
      default: { version: "0.8.28" },
      production: {
        version: "0.8.28",
        settings: { optimizer: { enabled: true, runs: 200 } },
      },
    },
  },
  networks: {
    // In-process simulated chain used by tests.
    hardhatMainnet: { type: "edr-simulated", chainType: "l1" },
    // Long-running local chain from infra/docker-compose (anvil, chain id 31337).
    localhost: { type: "http", chainType: "l1", url: "http://127.0.0.1:8545" },
    // Public testnet for Phase 5 only; keys come from the Hardhat keystore, never the repo.
    sepolia: {
      type: "http",
      chainType: "l1",
      url: configVariable("SEPOLIA_RPC_URL"),
      accounts: [configVariable("SEPOLIA_PRIVATE_KEY")],
    },
  },
});
