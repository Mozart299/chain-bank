# ChainBank Project Specification

Blockchain-secured authentication and simulated interbank transactions

Version 1.0 | 19 November 2026 | Status: proposed engineering baseline
Prepared for Peter Kyeyune Ssendegeya | Learning and portfolio development

> Source of truth for scope and design. Decisions that refine or resolve parts of this spec are recorded in [`docs/adr/`](adr/). When an ADR and this document disagree, the ADR wins.

## 1 Executive summary

ChainBank is a full-stack banking simulator. It teaches cryptographic identity, secure backend engineering, Ethereum-compatible smart contracts, and the difference between a bank ledger and blockchain settlement. Customers sign in through a conventional secure session backed by proof that they own a wallet. They start simulated payments and inspect transaction and audit records that anyone can verify independently. Administrators manage test institutions, customer eligibility, and reconciliation.

The initial release involves no real money, no real customer data, and no legal claim to bank deposits. All balances and interbank tokens are explicitly fictional. The priorities are correct financial invariants, threat modeling, thorough testing, and an understandable learning path, ahead of deploying a production financial service.

## 2 Goals and success criteria

- Implement secure authentication, including wallet challenge-response and optional second-factor step-up approval for transfers.
- Create deterministic simulated transfers with idempotency, authorization, immutable journal entries, and clear transaction status.
- Deploy and test Solidity contracts that keep a permissioned token ledger and emit verifiable transfer events.
- Show cross-institution reconciliation and tamper-evident audit checkpoints.
- Document trust boundaries, failed transactions, security assumptions, and test evidence.

Success means a new developer can run the system locally, seed two fictional banks, complete and reject transfers, inspect tests and ledger entries, and explain what the blockchain contributes and what the backend remains responsible for.

## 3 Scope

| In scope for MVP | Excluded from MVP |
| --- | --- |
| Customer registration, demo identity verification, role-based access | Real KYC processing or identity-document storage |
| Wallet linking via signed one-time challenges | Wallet-only recovery as the sole account recovery mechanism |
| Simulated deposit accounts and internal transfers | Real UGX deposits, custody, forex, or payment rail integration |
| Testnet/local-chain token transfers and transaction event indexing | Public token sale, stablecoin issuance, or real-value settlement |
| Transaction journals, approvals, audit commitments | Production bank licensing and regulatory compliance claims |
| Two-bank sandbox and reconciliation dashboard | Direct connection to live banks or mobile money providers |

## 4 Users and permissions

| Role | Capabilities |
| --- | --- |
| Customer | View own balances and history; link wallet; create and approve own transfers. |
| Bank operator | Review flagged demo transactions and reconciliation for the assigned institution. |
| Compliance reviewer | View synthetic policy decisions and audit evidence, without unrestricted customer administration. |
| System administrator | Manage test institutions, roles, contract configuration, and a controlled emergency pause. |
| Auditor | Read-only verification of journals, event proofs, and batch commitments. |

## 5 Key use cases

1. A customer registers and signs in with a password or passkey, and a secure server-side session is established.
2. A customer links an EVM wallet by signing a server-issued nonce bound to the application domain, chain, address, purpose, and expiry.
3. A customer starts a simulated transfer with a unique idempotency key. The backend checks account ownership, available funds, policy rules, and step-up authorization.
4. A transfer moves from requested to approved to submitted to confirmed or failed. The user sees the blockchain transaction hash when there is one.
5. An operator reconciles bank journals against contract events. Mismatches go into a documented exception queue.
6. An auditor verifies that an exported journal batch matches an on-chain cryptographic commitment.

## 6 Proposed technical architecture

| Layer | Proposed component | Responsibility |
| --- | --- | --- |
| Web | Next.js, TypeScript, Tailwind, Wagmi/Viem | UI, wallet connector, signed challenges, transaction state. |
| API | Quarkus, Java, REST/OpenAPI | Sessions, authorization, limits, policy, orchestration. |
| Database | PostgreSQL | Accounts, double-entry journal, idempotency, outbox, event index. |
| Chain | Solidity, OpenZeppelin, local EVM/Hardhat | Permissioned simulated token ledger and events. |
| Background worker | Quarkus scheduled worker / queue | Outbox submission, confirmations, reorg handling, reconciliation. |
| Operations | Docker Compose, CI, telemetry | Repeatable local environment, logs, tests, metrics. |

Interaction: Browser → Quarkus API → PostgreSQL journal and outbox → blockchain gateway → smart contract → event indexer → reconciliation. The browser never receives backend signing keys. Only commitments meant to be public and fictional token movements are written on-chain. Personal information stays off-chain.

## 7 Authentication and identity design

### 7.1 Primary authentication

Use conventional secure authentication, such as passkeys or a password plus MFA. Issue short-lived sessions in HttpOnly, Secure, SameSite cookies, with CSRF defenses where they apply. A wallet signature proves control of an address. It does not prove legal identity or eligibility to operate a bank account. Never treat an address on its own as an authenticated user.

### 7.2 Wallet linking

1. The authenticated user requests a one-time challenge. The server stores a hashed nonce with a TTL and the intended action.
2. The wallet signs a standardized, human-readable message containing the domain, address, chain identifier, issue time, expiration, and nonce.
3. The server verifies the signature, origin and context, expiry, nonce uniqueness, address ownership rules, and the exact intended action.
4. The server marks the challenge consumed in a single atomic step and records a security audit event. Re-linking or unlinking requires step-up authentication.

### 7.3 Transfer approval

Require step-up approval for transfers above a demo threshold, for new beneficiaries, and for risky actions. Keep authentication, authorization, and transaction signing separate. Prevent replay by binding each approval to the transfer ID, amount, destination, chain, nonce, and expiry. Support session revocation and emergency account suspension.

### 7.4 Future digital credentials

A later release may issue synthetic verifiable credentials for completing demo KYC. Holders keep their credentials. Only the necessary verification keys and status, or privacy-preserving commitments, may be shared. Blockchain is optional for credential verification and must not expose sensitive claims.

## 8 Financial and on-chain transaction design

### 8.1 Double-entry accounting

Post balanced journal lines for every accepted simulated monetary movement. Each journal has a unique reference, debit and credit legs, a currency, a value date, and a source transaction. Enforce that debit totals equal credit totals per currency. Never derive spendable account balances solely from browser state or smart contract events.

### 8.2 State machine

`REQUESTED → VALIDATED → APPROVED → SUBMITTED → CONFIRMED → RECONCILED`

The terminal alternatives are `REJECTED`, `FAILED`, and `CANCELLED`. `CANCELLED` is allowed only before the irreversible submission. An on-chain transaction that reverted is not a success. A blockchain reorganization or an ambiguous receipt status moves the transfer to pending investigation, so it can never settle twice without anyone noticing.

### 8.3 Simulated interbank settlement

Bank A and Bank B receive prefunded fictional settlement tokens in a permissioned ERC-20-like contract. An approved transfer records the customer-side obligations in the off-chain journal and triggers an authorized settlement token movement. Finalized events are reconciled against the journal. The model is explicitly a simulation: a token transfer is not a legal transfer of UGX or of central-bank reserves.

### 8.4 Contract controls

- Role-based mint, burn, and transfer privileges, issuance caps, a pause mechanism, and explicit admin governance.
- No public arbitrary minting, no exposed private keys, and no sensitive customer identifiers in events.
- Events carry settlement IDs, amounts, institutional pseudonyms, and finalization status. Duplicate settlement IDs are rejected.
- Document the upgrade strategy. Prefer simple non-upgradeable contracts for the MVP and avoid proxy complexity until later.

## 9 Logical data model

| Entity | Key fields / invariants |
| --- | --- |
| User | id, status, credentials, institution_id; a wallet address is never the sole identity. |
| WalletLink | user_id, address, chain_id, verified_at, revoked_at; unique active ownership policy. |
| Bank / Account | institution_id, account_id, currency, status, balance projection. |
| Journal / JournalLine | journal_id, account_id, debit, credit, currency; balanced and immutable postings. |
| Transfer | id, source, destination, amount_minor, currency, state, idempotency_key. |
| Approval | transfer_id, approver, method, context_hash, expiry, used_at. |
| Outbox / ChainTx | event_id, transfer_id, tx_hash, chain_id, block, confirmations, retry state. |
| AuditEvent / Commitment | actor, action, resource, timestamp, hash, batch_root, verification result. |

Amounts use integer minor units or fixed-precision decimals, never binary floating point. Enforce non-negative amounts, per-currency balancing, and account and tenant isolation at both the API and database layers.

## 10 Initial API surface

| Method and path | Purpose |
| --- | --- |
| `POST /api/auth/login` | Establish the primary authenticated session. |
| `POST /api/wallet/challenge` | Issue a one-time, domain-bound wallet challenge. |
| `POST /api/wallet/verify` | Verify the signature and link the wallet. |
| `GET /api/accounts` | List the simulated accounts the caller may see. |
| `POST /api/transfers` | Create a transfer with an Idempotency-Key. |
| `POST /api/transfers/{id}/approve` | Approve a validated transfer with step-up authentication. |
| `GET /api/transfers/{id}` | Read status and evidence, filtered by authorization. |
| `GET /api/audit/{batch}/verify` | Verify a journal batch against its chain commitment. |
| `GET /api/reconciliation/exceptions` | Operator-only: mismatches and retry information. |

Every mutating endpoint needs validation, an authenticated context, authorization, rate limits, consistent error payloads, and auditability. API identifiers are opaque and must not be trusted as proof of ownership.

## 11 Security threat model

| Threat | Mitigation and required test |
| --- | --- |
| Signature replay or phishing | Domain- and purpose-bound challenge with expiry, atomic nonce consumption, explicit signing UX; reject replay. |
| Broken object authorization | Tenant and owner checks on every object query; cross-user access returns denial. |
| Duplicate transfer or network retry | Idempotency key with request fingerprint, unique constraints, outbox; one journal posting only. |
| Double spending / concurrency | Transactional balance reservations, row locking or a serializable strategy; concurrency tests. |
| Key compromise | No plaintext keys in the repository; secret isolation, least privilege, rotation, and emergency pause. |
| Smart contract vulnerability | Access control tests, fuzz/property tests, static analysis, event and failure validation. |
| Chain outage / reorganization | Pending status, confirmation policy, retry backoff, reconciliation and reorg tests. |
| PII exposure | Synthetic identities only; no personal data or unredacted account details on-chain. |
| Insider misuse | Role separation, audit logs, approvals for privileged actions, alerts on admin actions. |

This is a learning-grade design, not a bank security certification. Production use would require a formal security review, independent audits, resilience testing, regulated governance, and a privacy and compliance assessment.

## 12 Nonfunctional requirements

- **Correctness:** 100% of accepted postings balance, and a repeated idempotent request never produces a duplicate posting.
- **Availability:** local services recover after a restart without losing accepted requests, and pending outbox entries resume safely.
- **Observability:** correlation IDs trace the API request, journal, outbound chain submission, event, and reconciliation result.
- **Privacy:** logs redact secrets, tokens, sensitive signatures, and customer data.
- **Maintainability:** contract ABI versioning, migrations, API contracts, linting, automated tests, and architecture decision records.
- **Accessibility:** the transaction workflow works by keyboard, and pending and failure states are shown explicitly.

## 13 Test and acceptance plan

| Test layer | Acceptance evidence |
| --- | --- |
| Unit | Authorization, transfer policy, balance calculations, signature validation, replay prevention. |
| Contract | Roles, caps, pause, duplicate settlement prevention, revert cases, emitted event values. |
| Integration | Database transactions, outbox publication, chain receipts, event indexing, retry and idempotency. |
| End-to-end | Register → link wallet → send simulated funds → confirm → reconcile → verify audit hash. |
| Adversarial | Forged signature, stale nonce, unauthorized account, concurrent spend, chain outage or reorg. |
| Operations | Docker Compose boot, migrations, seed data, CI quality gates, and documented recovery. |

## 14 Delivery roadmap

| Phase | Deliverables | Exit condition |
| --- | --- | --- |
| 0 Foundation | Repository, ADRs, Docker, schema, CI, seeded banks. | One-command boot and a green smoke test. |
| 1 Authentication | Sessions, roles, wallet challenge and link, security tests. | Replay and unauthorized-access tests pass. |
| 2 Core banking | Accounts, balanced journals, transfer state machine, idempotency. | Concurrent and duplicate transfers preserve the invariants. |
| 3 Blockchain | Local EVM, permissioned settlement contract, gateway, receipts. | Transfers reconcile with contract events. |
| 4 Audit and UX | Dashboards, audit batch commitments, exception handling. | An auditor can verify an exported batch independently. |
| 5 Hardening | Threat review, load and failure exercises, testnet deployment, docs. | A reproducible demo and a complete test report. |

Suggested learning sequence:

1. Hashing, asymmetric keys, and signing.
2. Building a wallet challenge.
3. Solidity state and events.
4. Double-entry accounting and the transaction lifecycle.
5. Reconciling off-chain and on-chain records.

Each phase should include a short explanation, a hands-on task, and a test exercise.

## 15 Repository structure

- `apps/web`: Next.js dashboard
- `services/api`: Quarkus backend
- `contracts`: Solidity, Hardhat configuration and tests
- `packages/shared`: API schemas and generated contract types
- `infra`: Docker Compose, seed scripts, local chain configuration
- `docs`: ADRs, threat model, API reference, diagrams and runbook

## 16 Open decisions and assumptions

| # | Decision | Status |
| --- | --- | --- |
| 1 | Primary authentication: passkeys or password plus MFA (wallet linking stays supplementary). | **Resolved:** password + TOTP, see [ADR 0002](adr/0002-primary-authentication.md) |
| 2 | Initial test currency (suggested: fictional xUGX), minimum confirmation policy, sample transfer limits. | Currency **resolved:** xUGX, see [ADR 0003](adr/0003-money-and-ledger-invariants.md). Confirmations and limits are open. |
| 3 | Institution operator approval model; whether all cross-bank transfers need manual review. | Open |
| 4 | Event-batching strategy and chain checkpoint frequency for audit commitments. | Open |
| 5 | Whether the final learning phase explores Hyperledger Besu or another permissioned consortium network. | Open |

## 17 References and background reading

- Ethereum developer documentation: https://ethereum.org/developers/docs/
- Hardhat 3 documentation: https://hardhat.org/docs/getting-started
- OpenZeppelin Contracts: https://docs.openzeppelin.com/contracts/5.x/
- Wagmi documentation: https://wagmi.sh/react/getting-started
- W3C Verifiable Credentials Data Model 2.0: https://www.w3.org/TR/vc-data-model/
- Bank for International Settlements, Project Agorá: https://www.bis.org/project/agora
- OWASP Application Security Verification Standard: https://owasp.org/www-project-application-security-verification-standard/
