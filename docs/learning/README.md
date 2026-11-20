# Learning path

ChainBank exists to teach cryptographic identity, secure backend engineering, smart contracts, and the difference between a bank ledger and blockchain settlement (spec §1). These guides follow the learning sequence from spec §14. Each one has:

- **Key ideas**: the concept, in plain language.
- **Read the code**: where it lives in this repo.
- **Hands-on**: something to do with the running stack.
- **Break it**: a deliberate bug and the test that catches it. Revert with `git checkout -- <file>` afterwards.
- **Check yourself**: questions to answer before moving on.

| # | Guide | Built in |
|---|---|---|
| 1 | [Hashing, keys and signatures](01-hashing-keys-signing.md) | Phase 1 |
| 2 | [Wallet challenge (Sign-In with Ethereum)](02-wallet-challenge.md) | Phase 1 |
| 3 | [Double-entry accounting and the transfer lifecycle](03-double-entry-and-lifecycle.md) | Phases 0 and 2 |
| 4 | [Solidity state and events](04-solidity-state-and-events.md) | Phase 3 |
| 5 | [Reconciling off-chain and on-chain records](05-reconciliation-and-audit.md) | Phases 3 and 4 |

Guides 3 and 4 are in the opposite order from the spec's list: the ledger comes before the contract that mirrors it, because settlement makes no sense until you know what is being settled.

## Setup for every guide

```sh
make up                      # full stack (see ../runbook.md if anything fails)
make psql                    # SQL shell on the bank's database
make test-api TEST=SomeTest  # run one API test suite
make test-contracts          # Solidity tests
```

Demo users and the shared TOTP secret are in the [README](../../README.md#demo-users). The overall map is in [architecture.md](../architecture.md).
