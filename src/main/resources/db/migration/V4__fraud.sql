-- Phase 5: fraud-flagging hook.
-- The tx_status enum already carries 'HELD' (declared back in V2), so no enum change is needed here.
-- We only add explainability columns and an index for the held-review queue.

-- risk_score: the total score the rules engine produced for this payment (null = never scored,
-- e.g. an internal top-up or a pre-Phase-5 row). risk_reasons: comma-separated rule codes that
-- fired (e.g. 'AMOUNT_OVER_P95,FIRST_TIME_PAYEE'), so a held payment is auditable, not a black box.
ALTER TABLE transactions
    ADD COLUMN risk_score   INT,
    ADD COLUMN risk_reasons TEXT;

-- The held-review admin queue lists HELD rows newest-first. A partial index keeps that scan tiny:
-- once a payment is released (HELD -> COMPLETED) it drops out of the index entirely.
CREATE INDEX idx_tx_held ON transactions (created_at DESC) WHERE status = 'HELD';
