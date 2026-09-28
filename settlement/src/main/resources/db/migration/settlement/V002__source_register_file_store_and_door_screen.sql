-- The source register, the encrypted file store and the door screen (P8-TSK-002; ADR-0066).
--
-- What arrives here is the schema half of "evidence can be received and nothing else":
--   * `source` - the seeded identity and operational state of each declared source, and NEVER
--     a position: which clearing position a source's evidence discharges is compiled data
--     (SettlementSources, composed in app from each counterparty's own declaration -
--     INV-SET-05), so no environment can hold a different answer.
--   * `file` - the metadata a delivery committed with its bytes, born RECEIVED. PARSED and
--     REJECTED join with the parse leg (P8-TSK-008), ACCEPTED with the accept leg (P8-TSK-009):
--     the generated CHECK and the transition trigger admit exactly the machine that exists.
--   * `file_chunk` - the bytes, AES-256-GCM in chunks of at most 1 MiB under a key held
--     outside the database, each chunk's associated data binding file, source, content address
--     and seat, the whole plaintext's SHA-256 on the file row (INV-HIST-02).
--   * `file_event`, `file_receipt` - the history and the arrivals, append-only.
--   * `refused_delivery` - what the door refused, as metadata and NEVER a value: for a refused
--     delivery INV-PAY-02 and INV-RAIL-03 take precedence over INV-HIST-02 (ADR-0066 §4).
--
-- `finapp_app` receives no DELETE on any table here, ever, and every append-only table binds
-- EVERY writer - the migrator included - by trigger (the provider_evidence regime).

-- ---------------------------------------------------------------------------------------------
-- The source: identity and operational state. No position column, by design.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE settlement.source (
    id            UUID        NOT NULL,
    code          TEXT        NOT NULL,
    kind          TEXT        NOT NULL,
    status        TEXT        NOT NULL DEFAULT 'ACTIVE',
    next_sequence BIGINT      NOT NULL DEFAULT 1,

    CONSTRAINT source_pk PRIMARY KEY (id),
    CONSTRAINT source_code_unique UNIQUE (code),
    CONSTRAINT source_code_shape CHECK (
        code ~ '^[a-z0-9]+(-[a-z0-9]+)*(\.[a-z0-9]+(-[a-z0-9]+)*)+$' AND char_length(code) <= 100),
    CONSTRAINT source_kind CHECK (kind IN (
        'PSP_SETTLEMENT_REPORT', 'SCHEME_CYCLE_REPORT', 'PAYOUT_PROVIDER_REPORT',
        'BANK_STATEMENT')),
    CONSTRAINT source_status CHECK (status IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT source_sequence_positive CHECK (next_sequence >= 1)
);

COMMENT ON TABLE settlement.source IS
    'The seeded identity and operational state of each declared settlement source. Which position a source''s evidence discharges is compiled data (SettlementSources, INV-SET-05) and is deliberately NOT a column here: a register row an environment could edit would let two deployments disagree about whose evidence discharges a clearing.';
COMMENT ON COLUMN settlement.source.next_sequence IS
    'The next statement sequence acceptance expects from this source (P8-TSK-009); advanced under the accept leg''s row lock.';

-- Retirement is one-way, and the sequence never walks backwards - for every writer.
CREATE OR REPLACE FUNCTION settlement.source_moves_forward_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.code IS DISTINCT FROM OLD.code
            OR NEW.kind IS DISTINCT FROM OLD.kind THEN
        RAISE EXCEPTION 'a settlement source''s identity is frozen (P8-TSK-002)';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status
            AND NOT (OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED') THEN
        RAISE EXCEPTION 'a settlement source retires once and never returns (P8-TSK-002): a re-opened source is a NEW source';
    END IF;
    IF NEW.next_sequence < OLD.next_sequence THEN
        RAISE EXCEPTION 'a settlement source''s sequence never walks backwards (P8-TSK-002)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER source_moves_forward_only
    BEFORE UPDATE ON settlement.source
    FOR EACH ROW
    EXECUTE FUNCTION settlement.source_moves_forward_only();

CREATE OR REPLACE FUNCTION settlement.source_is_never_deleted()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'a settlement source is never deleted: files, receipts and refusals name it (P8-TSK-002)';
END;
$$;

CREATE TRIGGER source_is_never_deleted
    BEFORE DELETE ON settlement.source
    FOR EACH ROW
    EXECUTE FUNCTION settlement.source_is_never_deleted();

-- The four declared sources, seeded by identity (their formats, channels and positions are
-- compiled). Literal UUIDv7s minted at 2026-09-27T12:00:00Z - below the 2026-09-28T00:00:00Z
-- seed-id ceiling, so every seeded id sorts below every runtime id where that still matters
-- (SETTLEMENT_CLEARING's rule; nothing here relies on it).
INSERT INTO settlement.source (id, code, kind, status, next_sequence) VALUES
    ('01a0e2bc-8200-7001-8000-000000000001', 'simulated-psp.settlement',    'PSP_SETTLEMENT_REPORT',  'ACTIVE', 1),
    ('01a0e2bc-8200-7002-8000-000000000002', 'simulated-scheme.cycle-report', 'SCHEME_CYCLE_REPORT',  'ACTIVE', 1),
    ('01a0e2bc-8200-7003-8000-000000000003', 'simulated-payout.settlement', 'PAYOUT_PROVIDER_REPORT', 'ACTIVE', 1),
    ('01a0e2bc-8200-7004-8000-000000000004', 'simulated-bank.statement',    'BANK_STATEMENT',         'ACTIVE', 1);

-- ---------------------------------------------------------------------------------------------
-- The file: metadata committed with the bytes, born RECEIVED.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE settlement.file (
    id                UUID        NOT NULL,
    source_id         UUID        NOT NULL,
    received_via      TEXT        NOT NULL,
    status            TEXT        NOT NULL DEFAULT 'RECEIVED',
    business_date     DATE,
    format_id         TEXT        NOT NULL,
    format_version    INT         NOT NULL,
    content_sha256    BYTEA       NOT NULL,
    content_length    INT         NOT NULL,
    line_count        INT         NOT NULL,
    key_version       INT         NOT NULL,
    received_by       TEXT,
    attested_by       TEXT,
    attested_at       TIMESTAMPTZ,
    readmits_file_id  UUID,
    rejection_code    TEXT,
    rejection_detail  TEXT,
    parse_failures    INT         NOT NULL DEFAULT 0,
    next_parse_at     TIMESTAMPTZ,
    received_at       TIMESTAMPTZ NOT NULL,
    status_changed_at TIMESTAMPTZ NOT NULL,
    correlation_id    TEXT        NOT NULL,

    CONSTRAINT file_pk PRIMARY KEY (id),
    CONSTRAINT file_source_fk FOREIGN KEY (source_id) REFERENCES settlement.source (id),
    CONSTRAINT file_received_via CHECK (received_via IN ('UPLOAD', 'PULL', 'READMISSION')),
    -- The machine that exists: born RECEIVED, no edge. PARSED and REJECTED arrive with
    -- P8-TSK-008, ACCEPTED with P8-TSK-009, each regenerating this CHECK and the trigger
    -- below with the edges its producer brings.
    CONSTRAINT file_status CHECK (status IN ('RECEIVED')),
    CONSTRAINT file_format CHECK (format_id IN (
        'SIM_PSP_CSV', 'SIM_SCHEME_JSON', 'SIM_PAYOUT_CSV', 'SIM_STATEMENT_TAGGED')),
    CONSTRAINT file_format_version_positive CHECK (format_version >= 1),
    CONSTRAINT file_sha256_is_sha256 CHECK (octet_length(content_sha256) = 32),
    CONSTRAINT file_content_bounded CHECK (content_length BETWEEN 1 AND 8388608),
    CONSTRAINT file_lines_bounded CHECK (line_count BETWEEN 0 AND 50000),
    CONSTRAINT file_key_version_positive CHECK (key_version >= 1),
    -- A pull is authenticated by the source's credential; a person delivers the rest.
    CONSTRAINT file_pull_has_no_deliverer CHECK ((received_via = 'PULL') = (received_by IS NULL)),
    -- The uploader cannot attest their own file (INV-SET-07, INV-AUD-04) ...
    CONSTRAINT file_attester_is_second_person CHECK (
        attested_by IS NULL OR attested_by <> received_by),
    CONSTRAINT file_attestation_is_one_fact CHECK ((attested_by IS NULL) = (attested_at IS NULL)),
    -- ... and an upload is never ACCEPTED unattested. Vacuous while no edge reaches
    -- ACCEPTED; stated now so every later writer inherits it (ADR-0066 §2). A READMISSION's
    -- cross-row rule needs a trigger and arrives with P8-TSK-022's own migration.
    CONSTRAINT file_accepted_upload_is_attested CHECK (
        status <> 'ACCEPTED' OR received_via <> 'UPLOAD' OR attested_by IS NOT NULL),
    CONSTRAINT file_readmission_names_original CHECK (
        (received_via = 'READMISSION') = (readmits_file_id IS NOT NULL)),
    CONSTRAINT file_readmits_fk FOREIGN KEY (readmits_file_id) REFERENCES settlement.file (id),
    CONSTRAINT file_readmits_once UNIQUE (readmits_file_id),
    CONSTRAINT file_rejection_only_when_rejected CHECK (
        status = 'REJECTED' OR (rejection_code IS NULL AND rejection_detail IS NULL)),
    CONSTRAINT file_rejection_detail_bounded CHECK (char_length(rejection_detail) <= 500),
    CONSTRAINT file_parse_failures_counted CHECK (parse_failures >= 0)
);

-- One live file per content address and source (ADR-0066 §5): the door's arbiter. A
-- readmission deliberately re-presents its original's bytes, so it stands outside.
CREATE UNIQUE INDEX file_content_address
    ON settlement.file (source_id, content_sha256)
    WHERE readmits_file_id IS NULL;

CREATE INDEX file_by_source_and_status ON settlement.file (source_id, status);

COMMENT ON TABLE settlement.file IS
    'Settlement evidence metadata, committed in one transaction with the bytes (ADR-0066): born RECEIVED and inert; the checksum is the content address (INV-HIST-02); authentication follows received_via - a pull by the source''s credential, an upload only by a second person''s attestation.';
COMMENT ON COLUMN settlement.file.business_date IS
    'The date the uploader declared the statement covers - the counterparty''s claim, recorded verbatim; the parse leg reads the file''s own dates (P8-TSK-008).';
COMMENT ON COLUMN settlement.file.line_count IS
    'By the door screen''s own walk - the 50,000-record bound''s one source of truth.';

-- The transition trigger: the frozen columns for every writer, the attestation once, and -
-- until an edge's producer exists - no status movement at all.
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
    IF NEW.status IS DISTINCT FROM OLD.status THEN
        RAISE EXCEPTION 'a settlement file has no edge yet: PARSED and REJECTED arrive with the parse leg (P8-TSK-008), ACCEPTED with the accept leg (P8-TSK-009)';
    END IF;
    IF OLD.attested_by IS NOT NULL
            AND (NEW.attested_by IS DISTINCT FROM OLD.attested_by
                OR NEW.attested_at IS DISTINCT FROM OLD.attested_at) THEN
        RAISE EXCEPTION 'an attestation is recorded once and never moves (INV-SET-07)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER file_permits_only_machine_edges
    BEFORE UPDATE ON settlement.file
    FOR EACH ROW
    EXECUTE FUNCTION settlement.file_permits_only_machine_edges();

CREATE OR REPLACE FUNCTION settlement.file_is_never_deleted()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'settlement evidence is never deleted, for any writer (INV-HIST-02, INV-REC-01)';
END;
$$;

CREATE TRIGGER file_is_never_deleted
    BEFORE DELETE ON settlement.file
    FOR EACH ROW
    EXECUTE FUNCTION settlement.file_is_never_deleted();

-- ---------------------------------------------------------------------------------------------
-- The bytes: encrypted chunks, bound to their seat.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE settlement.file_chunk (
    file_id          UUID  NOT NULL,
    seq              INT   NOT NULL,
    ciphertext       BYTEA NOT NULL,
    nonce            BYTEA NOT NULL,
    plaintext_length INT   NOT NULL,

    CONSTRAINT file_chunk_pk PRIMARY KEY (file_id, seq),
    CONSTRAINT file_chunk_file_fk FOREIGN KEY (file_id) REFERENCES settlement.file (id),
    CONSTRAINT file_chunk_seq_seated CHECK (seq >= 0),
    -- 96-bit nonces, the size GCM is specified for (the V005 rule).
    CONSTRAINT file_chunk_nonce_is_gcm CHECK (octet_length(nonce) = 12),
    CONSTRAINT file_chunk_bounded CHECK (plaintext_length BETWEEN 1 AND 1048576),
    -- GCM appends a 16-byte tag; a ciphertext shorter than plaintext + tag is not GCM output.
    CONSTRAINT file_chunk_carries_tag CHECK (octet_length(ciphertext) = plaintext_length + 16)
);

COMMENT ON TABLE settlement.file_chunk IS
    'The evidence bytes: AES-256-GCM chunks of at most 1 MiB under FINAPP_SETTLEMENT_FILE_KEY (held outside the database), each chunk''s associated data binding file_id, source_id, content_sha256 and seq, so a chunk transplanted to any other file, source, content or seat fails authenticated decryption (ADR-0066 §6). Append-only for every writer.';
COMMENT ON COLUMN settlement.file_chunk.ciphertext IS
    'AES-256-GCM ciphertext of up to 1 MiB of the received bytes; bank statements name people, so never plaintext at rest (RESTRICTED-PII at the ceiling of what it decrypts to).';

CREATE OR REPLACE FUNCTION settlement.file_chunk_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'settlement file chunks are append-only for every writer: retained bytes are never edited and never deleted (INV-HIST-02)';
END;
$$;

CREATE TRIGGER file_chunk_is_append_only
    BEFORE UPDATE OR DELETE ON settlement.file_chunk
    FOR EACH ROW
    EXECUTE FUNCTION settlement.file_chunk_is_append_only();

-- ---------------------------------------------------------------------------------------------
-- The history: every move, and birth is the first.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE settlement.file_event (
    seq            BIGINT      GENERATED ALWAYS AS IDENTITY,
    file_id        UUID        NOT NULL,
    from_status    TEXT,
    to_status      TEXT        NOT NULL,
    actor          TEXT        NOT NULL,
    actor_type     TEXT        NOT NULL,
    reason         TEXT,
    occurred_at    TIMESTAMPTZ NOT NULL,
    correlation_id TEXT        NOT NULL,

    CONSTRAINT file_event_pk PRIMARY KEY (seq),
    CONSTRAINT file_event_file_fk FOREIGN KEY (file_id) REFERENCES settlement.file (id),
    CONSTRAINT file_event_statuses CHECK (
        (from_status IS NULL OR from_status IN ('RECEIVED', 'PARSED'))
        AND to_status IN ('RECEIVED', 'PARSED', 'ACCEPTED', 'REJECTED')),
    CONSTRAINT file_event_reason_bounded CHECK (char_length(reason) <= 1000)
);

CREATE INDEX file_event_by_file ON settlement.file_event (file_id, seq);

COMMENT ON TABLE settlement.file_event IS
    'The file machine''s append-only history: actor, actor type, occurrence time and reason per edge, birth included (the three-layer discipline).';

CREATE OR REPLACE FUNCTION settlement.file_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'a settlement file''s history is append-only for every writer (INV-REC-01)';
END;
$$;

CREATE TRIGGER file_event_is_append_only
    BEFORE UPDATE OR DELETE ON settlement.file_event
    FOR EACH ROW
    EXECUTE FUNCTION settlement.file_event_is_append_only();

-- ---------------------------------------------------------------------------------------------
-- The receipts: every arrival, NEW or DUPLICATE - the door''s own tally.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE settlement.file_receipt (
    id             UUID        NOT NULL,
    file_id        UUID        NOT NULL,
    outcome        TEXT        NOT NULL,
    channel        TEXT        NOT NULL,
    actor          TEXT        NOT NULL,
    actor_type     TEXT        NOT NULL,
    received_at    TIMESTAMPTZ NOT NULL,
    correlation_id TEXT        NOT NULL,

    CONSTRAINT file_receipt_pk PRIMARY KEY (id),
    CONSTRAINT file_receipt_file_fk FOREIGN KEY (file_id) REFERENCES settlement.file (id),
    CONSTRAINT file_receipt_outcome CHECK (outcome IN ('NEW', 'DUPLICATE')),
    CONSTRAINT file_receipt_channel CHECK (channel IN ('UPLOAD', 'PULL', 'READMISSION'))
);

CREATE INDEX file_receipt_by_file ON settlement.file_receipt (file_id);

COMMENT ON TABLE settlement.file_receipt IS
    'One row per delivery of a file''s bytes: the winner''s NEW and every loser''s DUPLICATE (ADR-0066 §5). A receipt never authenticates - authentication follows the FILE row''s channel.';

CREATE OR REPLACE FUNCTION settlement.file_receipt_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'settlement file receipts are append-only for every writer (INV-REC-01)';
END;
$$;

CREATE TRIGGER file_receipt_is_append_only
    BEFORE UPDATE OR DELETE ON settlement.file_receipt
    FOR EACH ROW
    EXECUTE FUNCTION settlement.file_receipt_is_append_only();

-- ---------------------------------------------------------------------------------------------
-- The refusals: metadata, never a value (the precedence ruling, ADR-0066 §4).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE settlement.refused_delivery (
    id             UUID        NOT NULL,
    source_id      UUID        NOT NULL,
    content_sha256 BYTEA       NOT NULL,
    content_length INT         NOT NULL,
    format_id      TEXT        NOT NULL,
    format_version INT         NOT NULL,
    reason         TEXT        NOT NULL,
    line_no        INT,
    field_name     TEXT,
    channel        TEXT        NOT NULL,
    actor          TEXT        NOT NULL,
    actor_type     TEXT        NOT NULL,
    refused_at     TIMESTAMPTZ NOT NULL,
    correlation_id TEXT        NOT NULL,

    CONSTRAINT refused_delivery_pk PRIMARY KEY (id),
    CONSTRAINT refused_delivery_source_fk FOREIGN KEY (source_id)
        REFERENCES settlement.source (id),
    CONSTRAINT refused_delivery_sha256 CHECK (octet_length(content_sha256) = 32),
    CONSTRAINT refused_delivery_length CHECK (content_length BETWEEN 1 AND 8388608),
    -- Only a content refusal leaves a row; an over-bound delivery leaves its audit record
    -- alone (ADR-0066 §4).
    CONSTRAINT refused_delivery_reason CHECK (reason IN (
        'PRIMARY_ACCOUNT_NUMBER', 'ACCOUNT_IDENTIFIER')),
    CONSTRAINT refused_delivery_channel CHECK (channel IN ('UPLOAD', 'PULL', 'READMISSION')),
    -- The metadata rule at the schema rank: what could carry content is bounded to a NAME's
    -- length, and the value columns simply do not exist.
    CONSTRAINT refused_delivery_field_is_a_name CHECK (char_length(field_name) <= 200),
    CONSTRAINT refused_delivery_line_positive CHECK (line_no IS NULL OR line_no >= 1)
);

CREATE INDEX refused_delivery_by_source ON settlement.refused_delivery (source_id, refused_at);
-- The chain to a later re-presentation of the same bytes (ADR-0066 §4): deliberately NOT
-- unique - the same dirty file presented ten times is ten refusals.
CREATE INDEX refused_delivery_by_address ON settlement.refused_delivery (content_sha256);

COMMENT ON TABLE settlement.refused_delivery IS
    'A delivery the door screen refused: source, checksum, length, reason, position - never the value (INV-PAY-02 and INV-RAIL-03 outrank INV-HIST-02 for a refusal, ADR-0066 §4). Recovery is re-presentation; the checksum chains the refusal to the later file.';

CREATE OR REPLACE FUNCTION settlement.refused_delivery_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'refused-delivery records are append-only for every writer (INV-REC-01)';
END;
$$;

CREATE TRIGGER refused_delivery_is_append_only
    BEFORE UPDATE OR DELETE ON settlement.refused_delivery
    FOR EACH ROW
    EXECUTE FUNCTION settlement.refused_delivery_is_append_only();

-- ---------------------------------------------------------------------------------------------
-- Grants: least privilege per table, and no DELETE for finapp_app anywhere, ever.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT ON settlement.source TO finapp_app;
GRANT UPDATE (status, next_sequence) ON settlement.source TO finapp_app;

GRANT SELECT, INSERT ON settlement.file TO finapp_app;
GRANT UPDATE (status, rejection_code, rejection_detail, parse_failures, next_parse_at,
              attested_by, attested_at, status_changed_at)
    ON settlement.file TO finapp_app;

GRANT SELECT, INSERT ON settlement.file_chunk TO finapp_app;
GRANT SELECT, INSERT ON settlement.file_event TO finapp_app;
GRANT SELECT, INSERT ON settlement.file_receipt TO finapp_app;
GRANT SELECT, INSERT ON settlement.refused_delivery TO finapp_app;
