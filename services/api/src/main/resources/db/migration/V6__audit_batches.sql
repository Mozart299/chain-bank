-- Phase 4: audit batches committed on-chain as Merkle roots; actions on reconciliation exceptions.

-- A batch covers every audit event with previous_batch.last_event_id < id <= last_event_id. Identity values
-- consumed by rolled-back transactions leave holes, so first_event_id may name an id that never existed:
-- the hash chain (prev_hash links), not id density, proves nothing is missing.
ALTER TABLE audit_batches DROP CONSTRAINT audit_batches_first_event_id_fkey;

ALTER TABLE audit_batches ADD COLUMN event_count int NOT NULL DEFAULT 0;
ALTER TABLE audit_batches ADD COLUMN status text NOT NULL DEFAULT 'SEALED'
    CHECK (status IN ('SEALED', 'SUBMITTED', 'COMMITTED', 'FAILED'));
ALTER TABLE audit_batches ADD COLUMN prev_last_hash bytea;
ALTER TABLE audit_batches ADD COLUMN contract text;
ALTER TABLE audit_batches ADD COLUMN raw_tx text;
ALTER TABLE audit_batches ADD COLUMN block_number bigint;
ALTER TABLE audit_batches ADD COLUMN block_hash text;
ALTER TABLE audit_batches ADD COLUMN last_checked_at timestamptz;
ALTER TABLE audit_batches ADD COLUMN last_error text;
CREATE UNIQUE INDEX audit_batches_last_event_uq ON audit_batches (last_event_id);

-- Batch contents never change once sealed; only the anchoring columns move forward.
CREATE FUNCTION audit_batch_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'audit_batches is append-only' USING ERRCODE = '42501';
    END IF;
    IF NEW.first_event_id <> OLD.first_event_id OR NEW.last_event_id <> OLD.last_event_id
       OR NEW.merkle_root <> OLD.merkle_root OR NEW.event_count <> OLD.event_count
       OR NEW.prev_last_hash IS DISTINCT FROM OLD.prev_last_hash THEN
        RAISE EXCEPTION 'sealed audit batch % cannot change', OLD.id USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER audit_batches_guard BEFORE UPDATE OR DELETE ON audit_batches
    FOR EACH ROW EXECUTE FUNCTION audit_batch_guard();

ALTER TABLE reconciliation_exceptions ADD COLUMN resolved_by uuid REFERENCES users (id);
ALTER TABLE reconciliation_exceptions ADD COLUMN resolution_note text;
ALTER TABLE reconciliation_exceptions ADD COLUMN retry_count int NOT NULL DEFAULT 0;
