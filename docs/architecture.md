# Architecture

How ChainBank fits together, and how one transfer travels through it. The *why* behind each decision is in the [ADRs](adr/); this page is the map.

> Simulation only. The money, banks, customers and tokens are all fictional.

## 1. System context

```mermaid
flowchart LR
    subgraph Browser
        UI["Next.js pages<br/>(shadcn/ui, wagmi)"]
        MM["Browser wallet<br/>(MetaMask)"]
    end
    subgraph Web["apps/web · Next.js 16 :3000"]
        RW["/api/* rewrite<br/>(same origin)"]
        SSR["Server components<br/>(home status)"]
    end
    subgraph API["services/api · Quarkus 3 / Java 21 :8080"]
        REST["REST resources<br/>auth · accounts · transfers<br/>operator · audit · admin"]
        SVC["Services<br/>AuthService · TransferService<br/>AuditLog · AuditBatcher"]
        W["SettlementWorker<br/>(every 2 s, single chain writer)"]
    end
    PG[("PostgreSQL 17<br/>ledger · outbox · audit")]
    subgraph Chain["Anvil local EVM :8545 (chain 31337)"]
        ST["SettlementToken"]
        AA["AuditAnchor"]
    end
    V["tools/audit-verifier<br/>(independent)"]

    UI --> RW --> REST
    SSR --> REST
    MM -. "signs EIP-4361 message" .-> UI
    REST --> SVC --> PG
    W --> PG
    W -- "settle() / commit()" --> ST & AA
    W -- "receipts · Settled logs" --> Chain
    V -- "export JSON" --> REST
    V -- "batches(id)" --> AA
```

| Part | Responsible for | Not responsible for |
|---|---|---|
| **Browser** | UI and wallet signatures (the private key never leaves the wallet) | It never holds API keys or chain keys, and never calls the API cross-origin |
| **Next.js** | Pages, plus proxying `/api/*` so session cookies are first-party | Business rules |
| **Quarkus API** | Authentication, authorization, limits, the transfer state machine, the outbox, settlement, reconciliation, audit | Being the source of truth for balances: that's the database ledger |
| **PostgreSQL** | **The bank's books.** It enforces the ledger invariants itself with triggers | |
| **Chain** | A public, verifiable record of interbank settlement and audit commitments | Customer balances, or any personal data |
| **Audit verifier** | Checking an exported audit batch against the chain without trusting the bank | |

## 2. Where the truth lives

```mermaid
flowchart TB
    J["journal_lines<br/>(append-only, balanced per bank)"] -->|trigger| B["account_balances<br/>(projection)"]
    T["transfers + transfer_events"] --> J
    T --> O["outbox<br/>SETTLEMENT_REQUESTED"]
    O --> CT["chain_txs<br/>(signed raw tx stored first)"]
    CT --> E["chain_events<br/>(indexed final Settled logs)"]
    E -->|reconcile| T
    AE["audit_events<br/>(hash-chained)"] --> AB["audit_batches<br/>(Merkle roots)"]
    AB --> AA["AuditAnchor on-chain"]
```

- **Balances** come only from `journal_lines`. A trigger keeps `account_balances` in step and rejects overdrafts. The chain is never consulted for a customer balance (spec §8.1).
- **Settlement tokens** on the chain mirror each bank's SETTLEMENT account. Reconciliation proves the two agree ([ADR 0006](adr/0006-onchain-settlement.md)).
- **The audit log** is hash-chained in the database and committed to the chain in batches ([ADR 0007](adr/0007-audit-commitments.md)).

## 3. One transfer, end to end

```mermaid
stateDiagram-v2
    [*] --> REQUESTED: POST /api/transfers + Idempotency-Key
    REQUESTED --> REJECTED: daily limit / insufficient funds
    REQUESTED --> VALIDATED: funds reserved
    VALIDATED --> CANCELLED: customer cancels / 15 min expiry
    VALIDATED --> REJECTED: operator rejects (flagged)
    VALIDATED --> APPROVED: all approvals present → journal posted
    APPROVED --> CONFIRMED: same bank (book transfer)
    APPROVED --> SUBMITTED: cross bank → settle() broadcast
    SUBMITTED --> CONFIRMED: 2 confirmations, canonical block
    SUBMITTED --> PENDING_INVESTIGATION: reverted / reorg
    PENDING_INVESTIGATION --> CONFIRMED: final after retry or reorg recovery
    CONFIRMED --> RECONCILED: matching Settled event indexed
    RECONCILED --> [*]
```

Cross-bank, in time order:

```mermaid
sequenceDiagram
    autonumber
    actor A as Alice (Nile)
    participant API as Quarkus API
    participant DB as PostgreSQL
    participant W as SettlementWorker
    participant C as SettlementToken
    A->>API: POST /api/transfers (Idempotency-Key)
    API->>DB: lock accounts (UUID order) · reserve funds · VALIDATED
    A->>API: POST /transfers/{id}/approve (TOTP)
    API->>DB: approval(context_hash) · journal (balanced per bank) · APPROVED · outbox row
    W->>DB: claim outbox (SKIP LOCKED)
    W->>W: sign settle(id, INST-A, INST-B, amount)
    W->>DB: record chain_tx + raw tx · SUBMITTED  (commit BEFORE broadcast)
    W->>C: eth_sendRawTransaction
    C-->>W: receipt (block N), then block N+1
    W->>DB: FINAL · CONFIRMED
    W->>C: eth_getLogs(Settled) up to the final block
    W->>DB: chain_events · match → RECONCILED
```

The guarantees and where they come from:

| Guarantee | Mechanism | Code |
|---|---|---|
| No overdraft under concurrency | Ordered account locks, a conditional reservation, and a DB trigger | `TransferService.lockAccounts`, `reserve`, V1 trigger |
| Retries never move money twice | `UNIQUE(initiated_by, idempotency_key)` plus a request fingerprint | `TransferService.create` |
| Books always balance | Commit-time trigger: debits = credits, overall **and per bank** | V1, V4 `journal_balanced_check` |
| An approval authorises only its own transfer | `context_hash` over id, accounts, amount and expiry | `TransferService.contextHash` |
| A crash never loses or duplicates a settlement | Record then broadcast, rebroadcast, and a contract duplicate guard | `SettlementWorker`, `SettlementToken.settle` |
| Reorgs and reverts are surfaced | `PENDING_INVESTIGATION` plus reconciliation exceptions | `SettlementWorker.onReorg/onRevert` |

## 4. Identity and sessions

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant W as Next.js
    participant API as Quarkus
    U->>W: email + password + TOTP
    W->>API: POST /api/auth/login (rewrite)
    API-->>U: Set-Cookie cb_session (HttpOnly, SameSite=Lax) + X-CSRF-Token
    U->>API: mutating request + X-CSRF-Token + Origin check
    Note over API: SessionAuthMechanism → SecurityIdentity (roles)<br/>@RolesAllowed · CurrentSession.requireStepUp()
    U->>API: POST /api/wallet/challenge
    API-->>U: EIP-4361 message (domain, chain, nonce, expiry)
    U->>U: wallet signs (personal_sign)
    U->>API: POST /api/wallet/verify (signature)
    Note over API: consume challenge atomically → recover signer (secp256k1) → link
```

Details: [ADR 0002](adr/0002-primary-authentication.md) and [ADR 0004](adr/0004-sessions-csrf-and-wallet-linking.md). Roles: `CUSTOMER`, `BANK_OPERATOR`, `COMPLIANCE_REVIEWER`, `SYSTEM_ADMIN`, `AUDITOR` (spec §4).

## 5. Audit anchoring

```mermaid
flowchart LR
    ev["every security / money action"] --> AL["AuditLog.record<br/>hash = H(prevHash, fields)"]
    AL --> AE[("audit_events")]
    AE -->|"100 events or 10 min,<br/>or admin checkpoint"| S["AuditBatcher.seal<br/>RFC 6962 Merkle root"]
    S --> AB[("audit_batches")]
    AB -->|worker| AN["AuditAnchor.commit<br/>sequential · contiguous · immutable"]
    AB -->|export JSON| X["auditor"]
    X --> VR["verify.mjs<br/>re-derive hashes + root,<br/>compare with chain"]
    AN --> VR
```

## 6. Code map

| Concern | Location |
|---|---|
| Schema and invariants | `services/api/src/main/resources/db/migration/V1…V6` |
| Auth, sessions, CSRF, TOTP | `services/api/.../auth/` |
| Wallet linking (EIP-4361, secp256k1) | `.../wallet/` |
| Accounts and postings | `.../ledger/` |
| Transfers, approvals, operator review | `.../transfer/` |
| Chain client, settlement worker, reconciliation | `.../chain/` |
| Audit log, batches, Merkle, verification | `.../audit/` |
| Contracts | `contracts/contracts/*.sol` |
| Web pages | `apps/web/src/app/**/page.tsx` |
| Independent verifier | `tools/audit-verifier/verify.mjs` |
| Local stack | `infra/docker-compose.yml`, `Makefile` |
