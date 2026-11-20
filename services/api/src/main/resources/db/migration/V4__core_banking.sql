-- Phase 2: customer accounts, transfers, approvals.

-- ---------------------------------------------------------------------------
-- Accounts: CASH (vault cash, source of demo deposits) and human-readable account numbers
-- ---------------------------------------------------------------------------
ALTER TABLE accounts DROP CONSTRAINT accounts_account_type_check;
ALTER TABLE accounts ADD CONSTRAINT accounts_account_type_check
    CHECK (account_type IN ('CUSTOMER_DEPOSIT', 'SETTLEMENT', 'SUSPENSE', 'EQUITY', 'CASH'));

ALTER TABLE institutions ADD COLUMN account_prefix text;
UPDATE institutions SET account_prefix = 'NILE' WHERE code = 'BANK_A';
UPDATE institutions SET account_prefix = 'RWZ' WHERE code = 'BANK_B';
ALTER TABLE institutions ALTER COLUMN account_prefix SET NOT NULL;
ALTER TABLE institutions ADD CONSTRAINT institutions_account_prefix_uq UNIQUE (account_prefix);

CREATE SEQUENCE account_number_seq START WITH 10000001;
ALTER TABLE accounts ADD COLUMN account_number text UNIQUE;
ALTER TABLE accounts ADD CONSTRAINT accounts_customer_number_chk
    CHECK (account_type <> 'CUSTOMER_DEPOSIT' OR account_number IS NOT NULL);

-- One deposit account per customer per currency (makes account opening idempotent under races).
CREATE UNIQUE INDEX accounts_one_deposit_per_owner_uq ON accounts (owner_user_id, currency)
    WHERE account_type = 'CUSTOMER_DEPOSIT';

INSERT INTO accounts (id, institution_id, account_type, normal_side, allow_negative, currency, name) VALUES
    ('00000000-0000-0000-0004-00000000000a', '00000000-0000-0000-0000-00000000000a', 'CASH', 'DEBIT', false, 'xUGX', 'Nile Demo Bank vault cash'),
    ('00000000-0000-0000-0004-00000000000b', '00000000-0000-0000-0000-00000000000b', 'CASH', 'DEBIT', false, 'xUGX', 'Rwenzori Demo Bank vault cash');

-- ---------------------------------------------------------------------------
-- Ledger: a journal must balance per institution, not only overall. A cross-bank transfer is one
-- journal whose lines balance inside each bank (deposit vs settlement), so neither bank's books
-- can be left lopsided.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION journal_balanced_check() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    d bigint; c bigint; n int; lopsided uuid;
BEGIN
    SELECT coalesce(sum(debit_minor), 0), coalesce(sum(credit_minor), 0), count(*)
      INTO d, c, n FROM journal_lines WHERE journal_id = NEW.journal_id;
    IF n < 2 OR d <> c THEN
        RAISE EXCEPTION 'journal % is unbalanced (debits %, credits %, lines %)', NEW.journal_id, d, c, n
            USING ERRCODE = '23514';
    END IF;
    SELECT a.institution_id INTO lopsided
      FROM journal_lines l JOIN accounts a ON a.id = l.account_id
     WHERE l.journal_id = NEW.journal_id
     GROUP BY a.institution_id
    HAVING sum(l.debit_minor) <> sum(l.credit_minor)
     LIMIT 1;
    IF lopsided IS NOT NULL THEN
        RAISE EXCEPTION 'journal % is unbalanced for institution %', NEW.journal_id, lopsided
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END $$;

-- ---------------------------------------------------------------------------
-- Transfers and approvals
-- ---------------------------------------------------------------------------
ALTER TABLE transfers ADD COLUMN source_institution_id uuid REFERENCES institutions (id);
ALTER TABLE transfers ADD COLUMN destination_institution_id uuid REFERENCES institutions (id);
ALTER TABLE transfers ADD COLUMN reference text CHECK (reference IS NULL OR length(reference) <= 140);
ALTER TABLE transfers ADD COLUMN requires_step_up boolean NOT NULL DEFAULT false;
ALTER TABLE transfers ADD COLUMN requires_review boolean NOT NULL DEFAULT false;
ALTER TABLE transfers ADD COLUMN expires_at timestamptz;
ALTER TABLE transfers ADD COLUMN approved_at timestamptz;

CREATE INDEX transfers_initiator_created_idx ON transfers (initiated_by, created_at DESC);
CREATE INDEX transfers_pending_expiry_idx ON transfers (expires_at) WHERE state = 'VALIDATED';
CREATE INDEX transfers_review_idx ON transfers (source_institution_id) WHERE state = 'VALIDATED' AND requires_review;

-- A transfer may carry one customer approval and one operator approval.
DROP INDEX approvals_transfer_used_uq;
CREATE UNIQUE INDEX approvals_transfer_method_uq ON approvals (transfer_id, method);
CREATE TRIGGER approvals_immutable BEFORE UPDATE OR DELETE ON approvals
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
