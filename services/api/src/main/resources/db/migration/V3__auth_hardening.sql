-- Phase 1: session idle tracking and TOTP replay protection.
ALTER TABLE sessions ADD COLUMN last_seen_at timestamptz NOT NULL DEFAULT now();

-- Highest TOTP time-step already accepted for this user; codes at or below it are replays.
ALTER TABLE users ADD COLUMN totp_last_step bigint NOT NULL DEFAULT 0;

-- Fast lookup of a user's open challenges.
CREATE INDEX wallet_challenges_user_idx ON wallet_challenges (user_id) WHERE consumed_at IS NULL;
