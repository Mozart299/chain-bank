-- ChainBank core schema. All money is integer minor units (bigint); never floating point.
-- Ledger invariants are enforced in the database, not only in application code.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ---------------------------------------------------------------------------
-- Reference data
-- ---------------------------------------------------------------------------
CREATE TABLE currencies (
    code     text PRIMARY KEY CHECK (code ~ '^[A-Za-z]{1,8}$'),
    exponent smallint NOT NULL CHECK (exponent BETWEEN 0 AND 8),
    name     text NOT NULL
);

CREATE TABLE institutions (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code       text NOT NULL UNIQUE CHECK (code ~ '^[A-Z0-9_]{2,16}$'),
    name       text NOT NULL,
    -- Public, non-identifying label used in on-chain events.
    pseudonym  text NOT NULL UNIQUE,
    status     text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Identity and authentication (wallet address is never the sole identity)
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    institution_id     uuid REFERENCES institutions (id),
    email              text NOT NULL,
    display_name       text NOT NULL,
    password_hash      text NOT NULL,
    totp_secret_cipher bytea,
    totp_enabled_at    timestamptz,
    status             text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED')),
    created_at         timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX users_email_uq ON users (lower(email));

CREATE TABLE user_roles (
    user_id uuid NOT NULL REFERENCES users (id),
    role    text NOT NULL CHECK (role IN ('CUSTOMER', 'BANK_OPERATOR', 'COMPLIANCE_REVIEWER', 'SYSTEM_ADMIN', 'AUDITOR')),
    PRIMARY KEY (user_id, role)
);

CREATE TABLE sessions (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid NOT NULL REFERENCES users (id),
    token_hash  bytea NOT NULL UNIQUE,
    created_at  timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz NOT NULL,
    step_up_at  timestamptz,
    revoked_at  timestamptz,
    CHECK (expires_at > created_at)
);
CREATE INDEX sessions_user_idx ON sessions (user_id) WHERE revoked_at IS NULL;

CREATE TABLE wallet_challenges (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid NOT NULL REFERENCES users (id),
    nonce_hash  bytea NOT NULL UNIQUE,
    purpose     text NOT NULL CHECK (purpose IN ('LINK_WALLET', 'UNLINK_WALLET')),
    address     text NOT NULL CHECK (address ~ '^0x[0-9a-f]{40}$'),
    chain_id    bigint NOT NULL CHECK (chain_id > 0),
    message     text NOT NULL,
    issued_at   timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz NOT NULL,
    consumed_at timestamptz,
    CHECK (expires_at > issued_at)
);

CREATE TABLE wallet_links (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid NOT NULL REFERENCES users (id),
    address     text NOT NULL CHECK (address ~ '^0x[0-9a-f]{40}$'),
    chain_id    bigint NOT NULL CHECK (chain_id > 0),
    verified_at timestamptz NOT NULL DEFAULT now(),
    revoked_at  timestamptz
);
-- One active owner per address per chain.
CREATE UNIQUE INDEX wallet_links_active_uq ON wallet_links (chain_id, address) WHERE revoked_at IS NULL;

-- ---------------------------------------------------------------------------
-- Accounts and double-entry ledger
-- ---------------------------------------------------------------------------
CREATE TABLE accounts (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    institution_id  uuid NOT NULL REFERENCES institutions (id),
    owner_user_id   uuid REFERENCES users (id),
    account_type    text NOT NULL CHECK (account_type IN ('CUSTOMER_DEPOSIT', 'SETTLEMENT', 'SUSPENSE', 'EQUITY')),
    -- DEBIT-normal accounts (assets) grow with debits; CREDIT-normal (liabilities, equity) with credits.
    normal_side     text NOT NULL CHECK (normal_side IN ('DEBIT', 'CREDIT')),
    allow_negative  boolean NOT NULL DEFAULT false,
    currency        text NOT NULL REFERENCES currencies (code),
    name            text NOT NULL,
    status          text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    CHECK ((account_type = 'CUSTOMER_DEPOSIT') = (owner_user_id IS NOT NULL)),
    UNIQUE (id, institution_id)
);
CREATE INDEX accounts_owner_idx ON accounts (owner_user_id);

-- Projection maintained exclusively by the journal_lines trigger below.
CREATE TABLE account_balances (
    account_id     uuid PRIMARY KEY REFERENCES accounts (id),
    ledger_minor   bigint NOT NULL DEFAULT 0,
    reserved_minor bigint NOT NULL DEFAULT 0 CHECK (reserved_minor >= 0),
    updated_at     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE journals (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    reference   text NOT NULL UNIQUE,
    transfer_id uuid,
    currency    text NOT NULL REFERENCES currencies (code),
    value_date  date NOT NULL DEFAULT current_date,
    description text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE journal_lines (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    journal_id   uuid NOT NULL REFERENCES journals (id),
    account_id   uuid NOT NULL REFERENCES accounts (id),
    debit_minor  bigint NOT NULL DEFAULT 0 CHECK (debit_minor >= 0),
    credit_minor bigint NOT NULL DEFAULT 0 CHECK (credit_minor >= 0),
    currency     text NOT NULL REFERENCES currencies (code),
    CHECK ((debit_minor > 0) <> (credit_minor > 0))
);
CREATE INDEX journal_lines_journal_idx ON journal_lines (journal_id);
CREATE INDEX journal_lines_account_idx ON journal_lines (account_id);

-- Line currency must match both the journal and the account.
CREATE FUNCTION journal_line_currency_check() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.currency <> (SELECT currency FROM journals WHERE id = NEW.journal_id) THEN
        RAISE EXCEPTION 'journal line currency % does not match journal', NEW.currency USING ERRCODE = '23514';
    END IF;
    IF NEW.currency <> (SELECT currency FROM accounts WHERE id = NEW.account_id) THEN
        RAISE EXCEPTION 'journal line currency % does not match account', NEW.currency USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER journal_lines_currency BEFORE INSERT ON journal_lines
    FOR EACH ROW EXECUTE FUNCTION journal_line_currency_check();

-- Apply each line to the balance projection; the UPDATE row-locks the account.
CREATE FUNCTION journal_line_apply_balance() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    acct    accounts%ROWTYPE;
    delta   bigint;
    new_bal bigint;
BEGIN
    SELECT * INTO acct FROM accounts WHERE id = NEW.account_id;
    delta := CASE acct.normal_side
                 WHEN 'DEBIT' THEN NEW.debit_minor - NEW.credit_minor
                 ELSE NEW.credit_minor - NEW.debit_minor
             END;
    INSERT INTO account_balances (account_id, ledger_minor) VALUES (NEW.account_id, delta)
    ON CONFLICT (account_id) DO UPDATE
        SET ledger_minor = account_balances.ledger_minor + EXCLUDED.ledger_minor, updated_at = now()
    RETURNING ledger_minor INTO new_bal;
    IF new_bal < 0 AND NOT acct.allow_negative THEN
        RAISE EXCEPTION 'account % would become negative', NEW.account_id USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER journal_lines_balance AFTER INSERT ON journal_lines
    FOR EACH ROW EXECUTE FUNCTION journal_line_apply_balance();

-- Every journal must balance (debits = credits, at least two lines) at commit time.
CREATE FUNCTION journal_balanced_check() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    d bigint; c bigint; n int;
BEGIN
    SELECT coalesce(sum(debit_minor), 0), coalesce(sum(credit_minor), 0), count(*)
      INTO d, c, n FROM journal_lines WHERE journal_id = NEW.journal_id;
    IF n < 2 OR d <> c THEN
        RAISE EXCEPTION 'journal % is unbalanced (debits %, credits %, lines %)', NEW.journal_id, d, c, n
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER journal_lines_balanced AFTER INSERT ON journal_lines
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION journal_balanced_check();

-- Journals with no lines are also rejected at commit.
CREATE FUNCTION journal_has_lines_check() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM journal_lines WHERE journal_id = NEW.id) THEN
        RAISE EXCEPTION 'journal % has no lines', NEW.id USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER journals_have_lines AFTER INSERT ON journals
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION journal_has_lines_check();

-- Postings are immutable: corrections are new reversing journals.
CREATE FUNCTION reject_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME USING ERRCODE = '42501';
END $$;
CREATE TRIGGER journals_immutable BEFORE UPDATE OR DELETE ON journals
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER journal_lines_immutable BEFORE UPDATE OR DELETE ON journal_lines
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- ---------------------------------------------------------------------------
-- Transfers, approvals, idempotency
-- ---------------------------------------------------------------------------
CREATE TABLE transfers (
    id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source_account_id      uuid NOT NULL REFERENCES accounts (id),
    destination_account_id uuid NOT NULL REFERENCES accounts (id),
    amount_minor           bigint NOT NULL CHECK (amount_minor > 0),
    currency               text NOT NULL REFERENCES currencies (code),
    state                  text NOT NULL DEFAULT 'REQUESTED' CHECK (state IN (
        'REQUESTED', 'VALIDATED', 'APPROVED', 'SUBMITTED', 'CONFIRMED', 'RECONCILED',
        'REJECTED', 'FAILED', 'CANCELLED', 'PENDING_INVESTIGATION')),
    failure_reason         text,
    initiated_by           uuid NOT NULL REFERENCES users (id),
    idempotency_key        text NOT NULL CHECK (length(idempotency_key) BETWEEN 8 AND 128),
    request_fingerprint    bytea NOT NULL,
    correlation_id         text,
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    CHECK (source_account_id <> destination_account_id),
    UNIQUE (initiated_by, idempotency_key)
);
CREATE INDEX transfers_source_idx ON transfers (source_account_id);
CREATE INDEX transfers_destination_idx ON transfers (destination_account_id);

ALTER TABLE journals ADD CONSTRAINT journals_transfer_fk FOREIGN KEY (transfer_id) REFERENCES transfers (id);

CREATE TABLE transfer_events (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id uuid NOT NULL REFERENCES transfers (id),
    from_state  text,
    to_state    text NOT NULL,
    reason      text,
    occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER transfer_events_immutable BEFORE UPDATE OR DELETE ON transfer_events
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE TABLE approvals (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    transfer_id  uuid NOT NULL REFERENCES transfers (id),
    approver_id  uuid NOT NULL REFERENCES users (id),
    method       text NOT NULL CHECK (method IN ('TOTP', 'WALLET_SIGNATURE', 'OPERATOR')),
    -- Hash over transfer id, amount, destination, chain, nonce and expiry.
    context_hash bytea NOT NULL,
    expires_at   timestamptz NOT NULL,
    used_at      timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX approvals_transfer_used_uq ON approvals (transfer_id) WHERE used_at IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Outbox and chain transactions
-- ---------------------------------------------------------------------------
CREATE TABLE outbox (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type  text NOT NULL,
    aggregate_id    uuid NOT NULL,
    event_type      text NOT NULL,
    payload         jsonb NOT NULL,
    status          text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED', 'DEAD')),
    attempts        int NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_error      text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    published_at    timestamptz
);
CREATE INDEX outbox_pending_idx ON outbox (next_attempt_at) WHERE status IN ('PENDING', 'IN_FLIGHT');

CREATE TABLE chain_txs (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    outbox_id     uuid NOT NULL REFERENCES outbox (id),
    transfer_id   uuid REFERENCES transfers (id),
    chain_id      bigint NOT NULL,
    tx_hash       text NOT NULL CHECK (tx_hash ~ '^0x[0-9a-f]{64}$'),
    block_number  bigint,
    block_hash    text,
    confirmations int NOT NULL DEFAULT 0,
    status        text NOT NULL DEFAULT 'SUBMITTED' CHECK (status IN ('SUBMITTED', 'MINED', 'FINAL', 'REVERTED', 'DROPPED', 'REORGED')),
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (chain_id, tx_hash)
);

-- ---------------------------------------------------------------------------
-- Audit (hash-chained) and commitments
-- ---------------------------------------------------------------------------
CREATE TABLE audit_events (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at    timestamptz NOT NULL DEFAULT now(),
    actor_user_id  uuid REFERENCES users (id),
    action         text NOT NULL,
    resource_type  text NOT NULL,
    resource_id    text,
    correlation_id text,
    details        jsonb NOT NULL DEFAULT '{}'::jsonb,
    prev_hash      bytea,
    hash           bytea NOT NULL
);
CREATE TRIGGER audit_events_immutable BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE TABLE audit_batches (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    first_event_id bigint NOT NULL REFERENCES audit_events (id),
    last_event_id  bigint NOT NULL REFERENCES audit_events (id),
    merkle_root    bytea NOT NULL,
    chain_id       bigint,
    chain_tx_hash  text,
    created_at     timestamptz NOT NULL DEFAULT now(),
    committed_at   timestamptz,
    CHECK (last_event_id >= first_event_id)
);

CREATE TABLE reconciliation_exceptions (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    transfer_id uuid REFERENCES transfers (id),
    chain_tx_id uuid REFERENCES chain_txs (id),
    kind        text NOT NULL CHECK (kind IN ('MISSING_ON_CHAIN', 'MISSING_IN_JOURNAL', 'AMOUNT_MISMATCH', 'REVERTED', 'REORG', 'DUPLICATE')),
    details     jsonb NOT NULL DEFAULT '{}'::jsonb,
    status      text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RETRYING', 'RESOLVED')),
    created_at  timestamptz NOT NULL DEFAULT now(),
    resolved_at timestamptz
);
