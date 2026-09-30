-- Acceptance joins the file and batch machines (P8-TSK-009; ADR-0065 section 2, ADR-0066
-- section 9's acceptance half; SETTLEMENT_AND_RECONCILIATION_LIFECYCLES sections 5.1-5.2).
--
-- The accept leg's schema half: ACCEPTED exists now on file and batch because its producer
-- does; the batch gains its four acceptance facts, set once and frozen; the gapless
-- source sequence gets its unique arbiter; and SOURCE_RETIRED joins the rejection codes as
-- the accept leg's own verdict. The CHECKs and transition rules are the enums' mirrors,
-- regenerated (FileStatus, BatchStatus, RejectionCode), reconciled by the migration test.

-- ---------------------------------------------------------------------------------------------
-- The file: the machine is complete.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE settlement.file DROP CONSTRAINT file_status;
ALTER TABLE settlement.file ADD CONSTRAINT file_status CHECK (status IN (
    'RECEIVED', 'PARSED', 'ACCEPTED', 'REJECTED'));

ALTER TABLE settlement.file DROP CONSTRAINT file_rejection_code;
ALTER TABLE settlement.file ADD CONSTRAINT file_rejection_code CHECK (
    rejection_code IS NULL OR rejection_code IN (
        'MALFORMED', 'CONTROL_TOTAL_MISMATCH', 'UNKNOWN_CURRENCY', 'SCALE_MISMATCH',
        'UNSUPPORTED_FORMAT', 'CONFLICTING_BATCH', 'DECLINED', 'SOURCE_RETIRED'));

CREATE OR REPLACE FUNCTION settlement.file_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.source_id IS DISTINCT FROM OLD.source_id
            OR NEW.received_via IS DISTINCT FROM OLD.received_via
            OR NEW.business_date IS DISTINCT FROM OLD.business_date
            OR NEW.format_id IS DISTINCT FROM OLD.format_id
            OR NEW.format_version IS DISTINCT FROM OLD.format_version
            OR NEW.content_sha256 IS DISTINCT FROM OLD.content_sha256
            OR NEW.content_length IS DISTINCT FROM OLD.content_length
            OR NEW.line_count IS DISTINCT FROM OLD.line_count
            OR NEW.key_version IS DISTINCT FROM OLD.key_version
            OR NEW.received_by IS DISTINCT FROM OLD.received_by
            OR NEW.readmits_file_id IS DISTINCT FROM OLD.readmits_file_id
            OR NEW.received_at IS DISTINCT FROM OLD.received_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a settlement file''s birth statement is frozen (P8-TSK-002, INV-HIST-01''s discipline)';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status
            AND NOT ((OLD.status = 'RECEIVED' AND NEW.status IN ('PARSED', 'REJECTED')) OR (OLD.status = 'PARSED' AND NEW.status IN ('ACCEPTED', 'REJECTED'))) THEN
        RAISE EXCEPTION 'not a settlement file edge: % -> % (sections 5.1; the machine is complete since P8-TSK-009)',
            OLD.status, NEW.status;
    END IF;
    IF OLD.attested_by IS NOT NULL
            AND (NEW.attested_by IS DISTINCT FROM OLD.attested_by
                OR NEW.attested_at IS DISTINCT FROM OLD.attested_at) THEN
        RAISE EXCEPTION 'an attestation is recorded once and never moves (INV-SET-07)';
    END IF;
    IF OLD.attested_by IS NULL AND NEW.attested_by IS NOT NULL
            AND NEW.status NOT IN ('RECEIVED', 'PARSED') THEN
        RAISE EXCEPTION 'a terminal settlement file is not attestable (ADR-0066 §2, P8-TSK-008)';
    END IF;
    IF OLD.rejection_code IS NOT NULL
            AND (NEW.rejection_code IS DISTINCT FROM OLD.rejection_code
                OR NEW.rejection_detail IS DISTINCT FROM OLD.rejection_detail) THEN
        RAISE EXCEPTION 'a rejection verdict is recorded once (INV-HIST-02): recovery is readmission, never an edit';
    END IF;
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The batch: the acceptance facts, once and frozen; the gapless sequence's arbiter.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE settlement.batch
    ADD COLUMN source_sequence  BIGINT,
    ADD COLUMN accepted_on      DATE,
    ADD COLUMN journal_entry_id UUID,
    ADD COLUMN posting_omitted  BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE settlement.batch DROP CONSTRAINT batch_status;
ALTER TABLE settlement.batch ADD CONSTRAINT batch_status CHECK (status IN (
    'PARSED', 'ACCEPTED', 'REJECTED'));

-- ACCEPTED carries its facts; a zero-fee batch omits its entry HONESTLY, never silently.
ALTER TABLE settlement.batch ADD CONSTRAINT batch_accepted_carries_its_facts CHECK (
    status <> 'ACCEPTED' OR (source_sequence IS NOT NULL AND accepted_on IS NOT NULL));
ALTER TABLE settlement.batch ADD CONSTRAINT batch_posting_omitted_is_honest CHECK (
    status <> 'ACCEPTED' OR ((journal_entry_id IS NULL) = posting_omitted));
ALTER TABLE settlement.batch ADD CONSTRAINT batch_sequence_positive CHECK (
    source_sequence IS NULL OR source_sequence >= 1);

-- The gapless sequence's arbiter: one accepted batch per (source, sequence), any writer.
ALTER TABLE settlement.batch
    ADD CONSTRAINT batch_sequence_once UNIQUE (source_id, source_sequence);

CREATE OR REPLACE FUNCTION settlement.batch_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.file_id IS DISTINCT FROM OLD.file_id
            OR NEW.source_id IS DISTINCT FROM OLD.source_id
            OR NEW.external_batch_ref IS DISTINCT FROM OLD.external_batch_ref
            OR NEW.currency IS DISTINCT FROM OLD.currency
            OR NEW.business_date IS DISTINCT FROM OLD.business_date
            OR NEW.format_id IS DISTINCT FROM OLD.format_id
            OR NEW.format_version IS DISTINCT FROM OLD.format_version
            OR NEW.line_count IS DISTINCT FROM OLD.line_count
            OR NEW.declared_line_count IS DISTINCT FROM OLD.declared_line_count
            OR NEW.net_minor IS DISTINCT FROM OLD.net_minor
            OR NEW.net_scale IS DISTINCT FROM OLD.net_scale
            OR NEW.remittance_reference IS DISTINCT FROM OLD.remittance_reference
            OR NEW.created_at IS DISTINCT FROM OLD.created_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a settlement batch''s parse statement is frozen (INV-SET-07): a correction is a new batch in a later file, never an edit';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status
            AND NOT ((OLD.status = 'PARSED' AND NEW.status IN ('ACCEPTED', 'REJECTED'))) THEN
        RAISE EXCEPTION 'not a settlement batch edge: % -> % (section 5.2; REPUDIATED arrives with P8-TSK-023)',
            OLD.status, NEW.status;
    END IF;
    -- The acceptance facts are set once - with the ACCEPTED edge - and never move
    -- (INV-SET-04: re-acceptance converges instead of re-stating).
    IF OLD.source_sequence IS NOT NULL
            AND (NEW.source_sequence IS DISTINCT FROM OLD.source_sequence
                OR NEW.accepted_on IS DISTINCT FROM OLD.accepted_on
                OR NEW.journal_entry_id IS DISTINCT FROM OLD.journal_entry_id
                OR NEW.posting_omitted IS DISTINCT FROM OLD.posting_omitted) THEN
        RAISE EXCEPTION 'a batch''s acceptance facts are recorded once (INV-SET-04, P8-TSK-009)';
    END IF;
    IF OLD.source_sequence IS NULL AND NEW.source_sequence IS NOT NULL
            AND NEW.status <> 'ACCEPTED' THEN
        RAISE EXCEPTION 'the acceptance facts arrive only with the ACCEPTED edge (P8-TSK-009)';
    END IF;
    RETURN NEW;
END;
$$;

-- The application role sets the acceptance facts in the accepting UPDATE.
GRANT UPDATE (source_sequence, accepted_on, journal_entry_id, posting_omitted)
    ON settlement.batch TO finapp_app;
