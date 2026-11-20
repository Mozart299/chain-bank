-- Phase 3: on-chain settlement, receipts, event index, reconciliation.

CREATE TABLE chain_deployments (
    chain_id     bigint NOT NULL,
    name         text NOT NULL,
    address      text NOT NULL CHECK (address ~ '^0x[0-9a-f]{40}$'),
    abi_version  int NOT NULL,
    tx_hash      text NOT NULL,
    block_number bigint NOT NULL,
    deployed_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (chain_id, name)
);

-- Settlement id (keccak256 of "chainbank:transfer:<uuid>") links a transfer to its on-chain settlement.
ALTER TABLE transfers ADD COLUMN settlement_id text UNIQUE CHECK (settlement_id ~ '^0x[0-9a-f]{64}$');

ALTER TABLE chain_txs ADD COLUMN settlement_id text CHECK (settlement_id ~ '^0x[0-9a-f]{64}$');
ALTER TABLE chain_txs ADD COLUMN nonce bigint;
ALTER TABLE chain_txs ADD COLUMN raw_tx text;               -- signed transaction, for rebroadcast
ALTER TABLE chain_txs ADD COLUMN attempts int NOT NULL DEFAULT 0;
ALTER TABLE chain_txs ADD COLUMN last_checked_at timestamptz;
ALTER TABLE chain_txs ADD COLUMN replaced_by uuid REFERENCES chain_txs (id);
CREATE INDEX chain_txs_open_idx ON chain_txs (status) WHERE status IN ('SUBMITTED', 'MINED', 'REORGED');
CREATE INDEX chain_txs_transfer_idx ON chain_txs (transfer_id);

-- Finalized `Settled` events, indexed from the contract.
CREATE TABLE chain_events (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    chain_id        bigint NOT NULL,
    block_number    bigint NOT NULL,
    block_hash      text NOT NULL,
    tx_hash         text NOT NULL,
    log_index       int NOT NULL,
    settlement_id   text NOT NULL,
    from_pseudonym  text NOT NULL,
    to_pseudonym    text NOT NULL,
    amount          numeric(78, 0) NOT NULL CHECK (amount > 0),
    indexed_at      timestamptz NOT NULL DEFAULT now(),
    reconciled_at   timestamptz,
    UNIQUE (tx_hash, log_index)
);
CREATE INDEX chain_events_settlement_idx ON chain_events (settlement_id);
CREATE INDEX chain_events_unreconciled_idx ON chain_events (id) WHERE reconciled_at IS NULL;

CREATE TABLE chain_cursor (
    chain_id           bigint PRIMARY KEY,
    last_indexed_block bigint NOT NULL
);

ALTER TABLE reconciliation_exceptions ADD COLUMN chain_event_id bigint REFERENCES chain_events (id);
ALTER TABLE reconciliation_exceptions ADD COLUMN note text;
-- One open exception of each kind per transfer / event.
CREATE UNIQUE INDEX recon_open_transfer_uq ON reconciliation_exceptions (transfer_id, kind)
    WHERE status <> 'RESOLVED' AND transfer_id IS NOT NULL;
CREATE UNIQUE INDEX recon_event_uq ON reconciliation_exceptions (chain_event_id, kind) WHERE chain_event_id IS NOT NULL;
