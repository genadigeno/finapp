-- P7-TSK-005: the card rail's clearing evidence (ADR-0059 section 4, INV-SET-01). The PSP
-- reports that a capture cleared on the card network; what Phase 8 needs preserved are the
-- network's own references - the acquirer reference and the network transaction identifier -
-- because those are the keys a clearing or settlement file is matched against. Clearing
-- agrees an obligation and moves no money: no amount here (the capture row holds the money
-- facts - one fact, one place), no machine state, no posting. One record per attempt, one
-- acquirer reference platform-wide, append-only for every writer (INV-HIST-02's regime):
-- these rows are what reconciliation stands on, so an editable one would corrupt the match
-- before it starts.

CREATE TABLE payments.clearing_record (
    id uuid PRIMARY KEY,
    attempt_id uuid NOT NULL REFERENCES payments.payment_attempt (id),
    acquirer_reference text NOT NULL
        CONSTRAINT clearing_record_acquirer_reference_shape
            CHECK (acquirer_reference ~ '^[A-Za-z0-9_.:-]{1,128}$'),
    network_transaction_id text NOT NULL
        CONSTRAINT clearing_record_network_transaction_id_shape
            CHECK (network_transaction_id ~ '^[A-Za-z0-9_.:-]{1,128}$'),
    recorded_at timestamptz NOT NULL,
    -- One clearing per capture: the arbiter that makes ten concurrent deliveries of one
    -- notice - each under a fresh event id, past the inbox - record it exactly once.
    CONSTRAINT clearing_record_one_per_attempt UNIQUE (attempt_id),
    -- An acquirer reference names ONE clearing platform-wide: a second attempt claiming the
    -- same reference is the integration break itself, refused before matching starts.
    CONSTRAINT clearing_record_acquirer_reference_is_unique UNIQUE (acquirer_reference)
);

-- Append-only for EVERY writer, the migrator included (the provider-evidence regime):
-- clearing evidence that can be edited is not evidence.
CREATE OR REPLACE FUNCTION payments.clearing_record_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'a clearing record is append-only for every writer: the network''s references are reconciliation''s match keys and are never edited or deleted (INV-HIST-02, INV-SET-01)';
END;
$$;

CREATE TRIGGER clearing_record_is_append_only
    BEFORE UPDATE OR DELETE ON payments.clearing_record
    FOR EACH ROW
    EXECUTE FUNCTION payments.clearing_record_is_append_only();

COMMENT ON TABLE payments.clearing_record IS
    'One capture''s clearing evidence (P7-TSK-005, ADR-0059 section 4): the acquirer reference and network transaction identifier the PSP reported, recorded once per attempt when the capture cleared on the card network. No amount, no state, no posting (INV-SET-01: clearing agrees an obligation; settlement discharges it in Phase 8). Append-only for every writer.';
COMMENT ON COLUMN payments.clearing_record.acquirer_reference IS
    'The acquirer''s reference for the cleared transaction (the ARN class): the primary key a clearing or settlement file is matched against in Phase 8. Unique platform-wide - one network clearing belongs to one capture.';
COMMENT ON COLUMN payments.clearing_record.network_transaction_id IS
    'The card network''s own transaction identifier, preserved beside the acquirer''s reference for file matching and dispute reference (ADR-0061).';

GRANT SELECT, INSERT ON payments.clearing_record TO finapp_app;
