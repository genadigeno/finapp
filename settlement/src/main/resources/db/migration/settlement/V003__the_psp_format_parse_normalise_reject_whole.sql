-- The PSP format: parse, normalise, reject whole (P8-TSK-008; ADR-0066 §§3, 8, 9; ADR-0065).
--
-- The file's machine gains the edges whose producers now exist - the parse leg and the
-- decline - and the canonical batch arrives: batch, batch_event, batch_total, line,
-- line_reference and ingestion_error, each committed WHOLE with its file's edge or not at
-- all (INV-SET-07). The generated CHECKs and the transition rules are each enum's mirror
-- (FileStatus, BatchStatus, SettlementLineType, LineDirection, LineReferenceKind,
-- RejectionCode), reconciled by the migration test. ACCEPTED stays out: its producer is the
-- accept leg (P8-TSK-009), which regenerates these again.

-- ---------------------------------------------------------------------------------------------
-- The file: the machine that now exists - RECEIVED -> PARSED | REJECTED, PARSED -> REJECTED.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE settlement.file DROP CONSTRAINT file_status;
ALTER TABLE settlement.file ADD CONSTRAINT file_status CHECK (status IN (
    'RECEIVED', 'PARSED', 'REJECTED'));

-- The verdict's closed list (RejectionCode.sqlValueList), and a REJECTED file names one.
ALTER TABLE settlement.file ADD CONSTRAINT file_rejection_code CHECK (
    rejection_code IS NULL OR rejection_code IN (
        'MALFORMED', 'CONTROL_TOTAL_MISMATCH', 'UNKNOWN_CURRENCY', 'SCALE_MISMATCH',
        'UNSUPPORTED_FORMAT', 'CONFLICTING_BATCH', 'DECLINED'));
ALTER TABLE settlement.file ADD CONSTRAINT file_rejected_names_its_code CHECK (
    status <> 'REJECTED' OR rejection_code IS NOT NULL);

-- The transition trigger, regenerated: the frozen birth statement unchanged, the machine's
-- edges from FileStatus.permittedTransitions(), the attestation settable only on a
-- non-terminal file (ADR-0066 §2) and recorded once, and a rejection verdict frozen once set.
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
            AND NOT ((OLD.status = 'RECEIVED' AND NEW.status IN ('PARSED', 'REJECTED')) OR (OLD.status = 'PARSED' AND NEW.status IN ('REJECTED'))) THEN
        RAISE EXCEPTION 'not a settlement file edge: % -> % (P8-TSK-008; ACCEPTED arrives with the accept leg, P8-TSK-009)',
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
-- The batch: one per file, born PARSED, whole (INV-SET-07).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE settlement.batch (
    id                  UUID        NOT NULL,
    file_id             UUID        NOT NULL,
    source_id           UUID        NOT NULL,
    external_batch_ref  TEXT        NOT NULL,
    currency            TEXT        NOT NULL,
    status              TEXT        NOT NULL DEFAULT 'PARSED',
    business_date       DATE        NOT NULL,
    format_id           TEXT        NOT NULL,
    format_version      INT         NOT NULL,
    line_count          INT         NOT NULL,
    declared_line_count INT         NOT NULL,
    net_minor           BIGINT      NOT NULL,
    net_scale           SMALLINT    NOT NULL,
    remittance_reference TEXT       NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL,
    status_changed_at   TIMESTAMPTZ NOT NULL,
    correlation_id      TEXT        NOT NULL,

    CONSTRAINT batch_pk PRIMARY KEY (id),
    -- One batch per file, for any writer under any race: the parse leg's second arbiter.
    CONSTRAINT batch_file_once UNIQUE (file_id),
    CONSTRAINT batch_file_fk FOREIGN KEY (file_id) REFERENCES settlement.file (id),
    CONSTRAINT batch_source_fk FOREIGN KEY (source_id) REFERENCES settlement.source (id),
    -- BatchStatus.sqlValueList - ACCEPTED and REPUDIATED join with their producers.
    CONSTRAINT batch_status CHECK (status IN ('PARSED', 'REJECTED')),
    CONSTRAINT batch_format CHECK (format_id IN (
        'SIM_PSP_CSV', 'SIM_SCHEME_JSON', 'SIM_PAYOUT_CSV', 'SIM_STATEMENT_TAGGED')),
    CONSTRAINT batch_format_version_positive CHECK (format_version >= 1),
    CONSTRAINT batch_currency_shape CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT batch_ref_bounded CHECK (char_length(external_batch_ref) BETWEEN 1 AND 100),
    CONSTRAINT batch_remittance_bounded CHECK (
        char_length(remittance_reference) BETWEEN 1 AND 100),
    CONSTRAINT batch_line_counts CHECK (line_count >= 0 AND declared_line_count >= 0),
    -- The Money bound (Money.MAX_SUPPORTED_SCALE): a nonsensical scale fails loudly.
    CONSTRAINT batch_net_scale_bounded CHECK (net_scale BETWEEN 0 AND 9)
);

-- THE LIVE UNIQUE, written whole now: one live batch per (source, reference, currency),
-- among batches neither REJECTED nor REPUDIATED - so P8-TSK-023's REPUDIATED needs no index
-- change, and a rejected file's genuine re-issue is admitted because the loser freed the key.
CREATE UNIQUE INDEX batch_live_identity
    ON settlement.batch (source_id, external_batch_ref, currency)
    WHERE status NOT IN ('REJECTED', 'REPUDIATED');

CREATE INDEX batch_by_source_and_status ON settlement.batch (source_id, status);

COMMENT ON TABLE settlement.batch IS
    'One received file''s canonical batch (INV-SET-07): born PARSED with all its lines, references and totals in its file''s RECEIVED -> PARSED transaction, or never born. The acceptance columns (source_sequence, accepted_on, journal_entry_id, posting_omitted) arrive with the accept leg (P8-TSK-009).';
COMMENT ON COLUMN settlement.batch.net_minor IS
    'The trailer''s declared net in minor units - proven equal to the Money fold of the lines before this row can exist; negative when the counterparty is owed (RESTRICTED-FINANCIAL).';
COMMENT ON COLUMN settlement.batch.remittance_reference IS
    'The trailer''s remittance reference, matching the source''s declared shape - what hop 2 attributes the bank line by (ADR-0065; CONFIDENTIAL).';

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
            AND NOT ((OLD.status = 'PARSED' AND NEW.status IN ('REJECTED'))) THEN
        RAISE EXCEPTION 'not a settlement batch edge: % -> % (P8-TSK-008; ACCEPTED arrives with P8-TSK-009)',
            OLD.status, NEW.status;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER batch_permits_only_machine_edges
    BEFORE UPDATE ON settlement.batch
    FOR EACH ROW
    EXECUTE FUNCTION settlement.batch_permits_only_machine_edges();

CREATE OR REPLACE FUNCTION settlement.batch_is_never_deleted()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'a settlement batch is never deleted (INV-HIST-02): a rejected batch frees its live key by STATUS';
END;
$$;

CREATE TRIGGER batch_is_never_deleted
    BEFORE DELETE ON settlement.batch
    FOR EACH ROW
    EXECUTE FUNCTION settlement.batch_is_never_deleted();

-- ---------------------------------------------------------------------------------------------
-- The batch's history.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE settlement.batch_event (
    seq            BIGINT      GENERATED ALWAYS AS IDENTITY,
    batch_id       UUID        NOT NULL,
    from_status    TEXT,
    to_status      TEXT        NOT NULL,
    actor          TEXT        NOT NULL,
    actor_type     TEXT        NOT NULL,
    reason         TEXT,
    occurred_at    TIMESTAMPTZ NOT NULL,
    correlation_id TEXT        NOT NULL,

    CONSTRAINT batch_event_pk PRIMARY KEY (seq),
    CONSTRAINT batch_event_batch_fk FOREIGN KEY (batch_id) REFERENCES settlement.batch (id),
    -- The whole machine's names, stated once: birth (NULL -> PARSED) and every later edge.
    CONSTRAINT batch_event_statuses CHECK (
        (from_status IS NULL OR from_status IN ('PARSED', 'ACCEPTED'))
        AND to_status IN ('PARSED', 'ACCEPTED', 'REJECTED', 'REPUDIATED')),
    CONSTRAINT batch_event_reason_bounded CHECK (char_length(reason) <= 1000)
);

CREATE INDEX batch_event_by_batch ON settlement.batch_event (batch_id, seq);

CREATE OR REPLACE FUNCTION settlement.batch_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'a settlement batch''s history is append-only for every writer (INV-REC-01)';
END;
$$;

CREATE TRIGGER batch_event_is_append_only
    BEFORE UPDATE OR DELETE ON settlement.batch_event
    FOR EACH ROW
    EXECUTE FUNCTION settlement.batch_event_is_append_only();

-- ---------------------------------------------------------------------------------------------
-- The control totals: the Money fold per (type, direction), persisted for the attester.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE settlement.batch_total (
    batch_id     UUID     NOT NULL,
    line_type    TEXT     NOT NULL,
    direction    TEXT     NOT NULL,
    line_count   BIGINT   NOT NULL,
    amount_minor BIGINT   NOT NULL,
    amount_scale SMALLINT NOT NULL,

    CONSTRAINT batch_total_pk PRIMARY KEY (batch_id, line_type, direction),
    CONSTRAINT batch_total_batch_fk FOREIGN KEY (batch_id) REFERENCES settlement.batch (id),
    -- SettlementLineType.sqlValueList and LineDirection.sqlValueList.
    CONSTRAINT batch_total_line_type CHECK (line_type IN (
        'CAPTURE', 'REFUND', 'CHARGEBACK', 'CHARGEBACK_REVERSAL', 'DISPUTE_FEE',
        'PROCESSING_FEE', 'COUNTERPARTY_ADJUSTMENT', 'OTHER_IN', 'OTHER_OUT')),
    CONSTRAINT batch_total_direction CHECK (direction IN ('INBOUND', 'OUTBOUND')),
    CONSTRAINT batch_total_counted CHECK (line_count >= 1),
    CONSTRAINT batch_total_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT batch_total_scale_bounded CHECK (amount_scale BETWEEN 0 AND 9)
);

CREATE OR REPLACE FUNCTION settlement.batch_total_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'a settlement batch''s totals are its parse''s statement, append-only for every writer (INV-SET-07)';
END;
$$;

CREATE TRIGGER batch_total_is_append_only
    BEFORE UPDATE OR DELETE ON settlement.batch_total
    FOR EACH ROW
    EXECUTE FUNCTION settlement.batch_total_is_append_only();

-- ---------------------------------------------------------------------------------------------
-- The canonical lines: typed, positive, dated - the matcher's material (P8-TSK-011).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE settlement.line (
    id                    UUID     NOT NULL,
    batch_id              UUID     NOT NULL,
    file_id               UUID     NOT NULL,
    line_no               INT      NOT NULL,
    line_type             TEXT     NOT NULL,
    direction             TEXT     NOT NULL,
    amount_minor          BIGINT   NOT NULL,
    amount_scale          SMALLINT NOT NULL,
    currency              TEXT     NOT NULL,
    business_date         DATE     NOT NULL,
    settlement_date       DATE,
    value_date            DATE,
    raw_record_sha256     BYTEA    NOT NULL,
    canonical_fingerprint BYTEA    NOT NULL,
    attributed_source_id  UUID,

    CONSTRAINT line_pk PRIMARY KEY (id),
    CONSTRAINT line_batch_fk FOREIGN KEY (batch_id) REFERENCES settlement.batch (id),
    CONSTRAINT line_file_fk FOREIGN KEY (file_id) REFERENCES settlement.file (id),
    -- One canonical line per seat in its file, for any writer under any race.
    CONSTRAINT line_seat_once UNIQUE (file_id, line_no),
    CONSTRAINT line_no_positive CHECK (line_no >= 1),
    CONSTRAINT line_type CHECK (line_type IN (
        'CAPTURE', 'REFUND', 'CHARGEBACK', 'CHARGEBACK_REVERSAL', 'DISPUTE_FEE',
        'PROCESSING_FEE', 'COUNTERPARTY_ADJUSTMENT', 'OTHER_IN', 'OTHER_OUT')),
    CONSTRAINT line_direction CHECK (direction IN ('INBOUND', 'OUTBOUND')),
    -- The ADR-0003 triple: positive, scaled, currency-explicit; the sign is the direction.
    CONSTRAINT line_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT line_scale_bounded CHECK (amount_scale BETWEEN 0 AND 9),
    CONSTRAINT line_currency_shape CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT line_raw_is_sha256 CHECK (octet_length(raw_record_sha256) = 32),
    CONSTRAINT line_fingerprint_is_sha256 CHECK (octet_length(canonical_fingerprint) = 32),
    CONSTRAINT line_attributed_source_fk FOREIGN KEY (attributed_source_id)
        REFERENCES settlement.source (id)
);

-- Indexed and DELIBERATELY NOT UNIQUE: a repeated line survives parsing to become
-- DUPLICATE_EXTERNAL at matching (P8-TSK-011), instead of vanishing here.
CREATE INDEX line_by_fingerprint ON settlement.line (canonical_fingerprint);
CREATE INDEX line_by_batch ON settlement.line (batch_id, line_no);

COMMENT ON TABLE settlement.line IS
    'One canonical settlement line (P8-TSK-008, ADR-0065): the platform''s vocabulary only - the provider''s words never leave the format adapter (INV-PAY-03). Free text stays inside the encrypted file; this table holds types, directions, amounts, dates and digests (ADR-0066 §3).';
COMMENT ON COLUMN settlement.line.attributed_source_id IS
    'NULL until the bank statement''s lines arrive (P8-TSK-016): a report''s lines belong to their file''s source; a bank line is attributed by remittance pattern.';

CREATE OR REPLACE FUNCTION settlement.line_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'canonical settlement lines are append-only for every writer (INV-SET-07, INV-HIST-02): a counterparty correction is a new line in a later batch';
END;
$$;

CREATE TRIGGER line_is_append_only
    BEFORE UPDATE OR DELETE ON settlement.line
    FOR EACH ROW
    EXECUTE FUNCTION settlement.line_is_append_only();

-- ---------------------------------------------------------------------------------------------
-- The typed references: shape-checked, with bank-identifier and alias shapes refused.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE settlement.line_reference (
    line_id UUID NOT NULL,
    kind    TEXT NOT NULL,
    value   TEXT NOT NULL,

    CONSTRAINT line_reference_pk PRIMARY KEY (line_id, kind),
    CONSTRAINT line_reference_line_fk FOREIGN KEY (line_id) REFERENCES settlement.line (id),
    -- LineReferenceKind.sqlValueList.
    CONSTRAINT line_reference_kind CHECK (kind IN (
        'PSP_CAPTURE_REF', 'PSP_REFUND_REF', 'ACQUIRER_REF', 'DISPUTE_REF', 'OUR_REF',
        'ORIGINAL_REF')),
    CONSTRAINT line_reference_bounded CHECK (char_length(value) BETWEEN 1 AND 100),
    -- INV-RAIL-03 at the database rank (ADR-0066 §3): an international account identifier
    -- or an alias shape is refused as a reference VALUE for every writer.
    CONSTRAINT line_reference_no_account_shape CHECK (
        value !~ '^[A-Za-z]{2}[0-9]{2}[A-Za-z0-9]{11,30}$'),
    CONSTRAINT line_reference_no_alias_shape CHECK (position('@' IN value) = 0)
);

COMMENT ON TABLE settlement.line_reference IS
    'A canonical line''s typed references (CONFIDENTIAL) - what the matcher keys on (ADR-0068). Shape-checked at parse by the format''s field classes; the CHECKs here bind every writer.';

CREATE OR REPLACE FUNCTION settlement.line_reference_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'canonical line references are append-only for every writer (INV-SET-07)';
END;
$$;

CREATE TRIGGER line_reference_is_append_only
    BEFORE UPDATE OR DELETE ON settlement.line_reference
    FOR EACH ROW
    EXECUTE FUNCTION settlement.line_reference_is_append_only();

-- ---------------------------------------------------------------------------------------------
-- The errors: what substantiates a rejection - code, line, field NAME; no content column.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE settlement.ingestion_error (
    file_id    UUID NOT NULL,
    seq        INT  NOT NULL,
    line_no    INT,
    error_code TEXT NOT NULL,
    field_name TEXT,

    CONSTRAINT ingestion_error_pk PRIMARY KEY (file_id, seq),
    CONSTRAINT ingestion_error_file_fk FOREIGN KEY (file_id) REFERENCES settlement.file (id),
    -- The first hundred tell the story; the bound keeps a hostile file from writing a book.
    CONSTRAINT ingestion_error_seq_bounded CHECK (seq BETWEEN 1 AND 100),
    CONSTRAINT ingestion_error_line_positive CHECK (line_no IS NULL OR line_no >= 1),
    -- RejectionCode.sqlErrorRowList: the content verdicts; CONFLICTING_BATCH and DECLINED
    -- are whole-file judgements and leave no rows.
    CONSTRAINT ingestion_error_code CHECK (error_code IN (
        'MALFORMED', 'CONTROL_TOTAL_MISMATCH', 'UNKNOWN_CURRENCY', 'SCALE_MISMATCH',
        'UNSUPPORTED_FORMAT')),
    -- A field NAME's length, never a value - the value columns do not exist (ADR-0066 §9).
    CONSTRAINT ingestion_error_field_is_a_name CHECK (char_length(field_name) <= 200)
);

CREATE OR REPLACE FUNCTION settlement.ingestion_error_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'ingestion errors are a rejection''s evidence, append-only for every writer (INV-REC-01)';
END;
$$;

CREATE TRIGGER ingestion_error_is_append_only
    BEFORE UPDATE OR DELETE ON settlement.ingestion_error
    FOR EACH ROW
    EXECUTE FUNCTION settlement.ingestion_error_is_append_only();

-- ---------------------------------------------------------------------------------------------
-- Grants: least privilege; no DELETE for finapp_app anywhere, ever.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON settlement.batch TO finapp_app;
GRANT UPDATE (status, status_changed_at) ON settlement.batch TO finapp_app;
GRANT SELECT, INSERT ON settlement.batch_event TO finapp_app;
GRANT SELECT, INSERT ON settlement.batch_total TO finapp_app;
GRANT SELECT, INSERT ON settlement.line TO finapp_app;
GRANT SELECT, INSERT ON settlement.line_reference TO finapp_app;
GRANT SELECT, INSERT ON settlement.ingestion_error TO finapp_app;
