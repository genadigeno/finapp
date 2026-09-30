-- The reconciliation run and the external items (P8-TSK-009; ADR-0064, ADR-0068;
-- SETTLEMENT_AND_RECONCILIATION_LIFECYCLES sections 5.3 and 5.4).
--
-- Acceptance's intake half: one run of kind BATCH per accepted batch, born OPEN in the
-- acceptance transaction, and one external item per settlement line - reconciliation's
-- WORKING COPY (matching never reads another schema to allocate, ADR-0064), born PENDING
-- with its disposition at zero and its typed keys beside it.
--
-- BOTH MACHINES ARE STATED WHOLE (the V002 expectation precedent): the CHECKs and the
-- every-writer transition triggers are RunStatus's and ItemStatus's mirrors, reconciled by
-- the migration test, while P8-TSK-009 produces only birth - the run leg (P8-TSK-011), the
-- grace and sweep legs (-013), the reprocess (-014), the resolutions (-015) and the
-- repudiation's edges (-023) each arrive to a machine that already refuses everything else.
-- The narrowed UPDATE grants and the absent producers keep the unproduced edges inert.

-- ---------------------------------------------------------------------------------------------
-- The run: an accepted batch's unit of matching work.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE reconciliation.reconciliation_batch (
    id                UUID        NOT NULL,
    source_id         UUID        NOT NULL,
    batch_id          UUID,
    kind              TEXT        NOT NULL,
    rule_set_id       UUID        NOT NULL,
    business_date     DATE        NOT NULL,
    source_sequence   BIGINT,
    status            TEXT        NOT NULL DEFAULT 'OPEN',
    item_count        INT         NOT NULL,
    cursor            INT         NOT NULL DEFAULT 0,
    failures          INT         NOT NULL DEFAULT 0,
    requested_by      TEXT,
    reason            TEXT,
    created_at        TIMESTAMPTZ NOT NULL,
    status_changed_at TIMESTAMPTZ NOT NULL,
    correlation_id    TEXT        NOT NULL,

    CONSTRAINT reconciliation_batch_pk PRIMARY KEY (id),
    CONSTRAINT reconciliation_batch_rule_set_fk FOREIGN KEY (rule_set_id)
        REFERENCES reconciliation.rule_set (id),
    -- One run per accepted batch, for any writer under any race.
    CONSTRAINT reconciliation_batch_batch_once UNIQUE (batch_id),
    -- RunKind.sqlValueList and RunStatus.sqlValueList.
    CONSTRAINT reconciliation_batch_kind CHECK (kind IN ('BATCH', 'REPROCESS')),
    CONSTRAINT reconciliation_batch_status CHECK (status IN (
        'OPEN', 'IN_PROGRESS', 'COMPLETED', 'BLOCKED')),
    -- The kind decides which identity facts exist (section 5.3).
    CONSTRAINT reconciliation_batch_kind_facts CHECK (
        (kind = 'BATCH') = (batch_id IS NOT NULL AND source_sequence IS NOT NULL)),
    CONSTRAINT reconciliation_batch_reprocess_is_reasoned CHECK (
        kind <> 'REPROCESS' OR (requested_by IS NOT NULL AND reason IS NOT NULL)),
    CONSTRAINT reconciliation_batch_counts CHECK (
        item_count >= 0 AND cursor >= 0 AND failures >= 0),
    CONSTRAINT reconciliation_batch_reason_bounded CHECK (char_length(reason) <= 1000)
);

-- One BATCH run per (source, sequence): the gapless acceptance's mirror.
CREATE UNIQUE INDEX reconciliation_batch_sequence_once
    ON reconciliation.reconciliation_batch (source_id, source_sequence)
    WHERE kind = 'BATCH';
-- One open REPROCESS per source (section 5.3; the door arrives with P8-TSK-014).
CREATE UNIQUE INDEX reconciliation_batch_one_open_reprocess
    ON reconciliation.reconciliation_batch (source_id)
    WHERE kind = 'REPROCESS' AND status <> 'COMPLETED';

CREATE INDEX reconciliation_batch_by_source_and_status
    ON reconciliation.reconciliation_batch (source_id, status);

COMMENT ON TABLE reconciliation.reconciliation_batch IS
    'One unit of matching work (section 5.3): an accepted batch''s own run (kind BATCH, born in the acceptance transaction through AcceptedBatchIntake) or a person''s keyed re-resolution (REPROCESS, P8-TSK-014). Pins the deciding rule set (INV-HIST-04); the cursor makes a chunked run resumable on any instance.';

CREATE OR REPLACE FUNCTION reconciliation.reconciliation_batch_moves_only_on_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.source_id IS DISTINCT FROM OLD.source_id
            OR NEW.batch_id IS DISTINCT FROM OLD.batch_id
            OR NEW.kind IS DISTINCT FROM OLD.kind
            OR NEW.rule_set_id IS DISTINCT FROM OLD.rule_set_id
            OR NEW.business_date IS DISTINCT FROM OLD.business_date
            OR NEW.source_sequence IS DISTINCT FROM OLD.source_sequence
            OR NEW.item_count IS DISTINCT FROM OLD.item_count
            OR NEW.requested_by IS DISTINCT FROM OLD.requested_by
            OR NEW.reason IS DISTINCT FROM OLD.reason
            OR NEW.created_at IS DISTINCT FROM OLD.created_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a reconciliation run''s birth statement is frozen (INV-HIST-01''s discipline, P8-TSK-009)';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status
            AND NOT ((OLD.status = 'OPEN' AND NEW.status IN ('IN_PROGRESS', 'COMPLETED', 'BLOCKED')) OR (OLD.status = 'IN_PROGRESS' AND NEW.status IN ('COMPLETED', 'BLOCKED')) OR (OLD.status = 'BLOCKED' AND NEW.status IN ('IN_PROGRESS'))) THEN
        RAISE EXCEPTION 'not a reconciliation run edge: % -> % (section 5.3)',
            OLD.status, NEW.status;
    END IF;
    IF NEW.cursor < OLD.cursor THEN
        RAISE EXCEPTION 'a run''s cursor never walks backwards: replay is by decision records, never by re-running the walk (INV-REC-04)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER reconciliation_batch_moves_only_on_machine_edges
    BEFORE UPDATE ON reconciliation.reconciliation_batch
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.reconciliation_batch_moves_only_on_machine_edges();

CREATE OR REPLACE FUNCTION reconciliation.reconciliation_batch_is_never_deleted()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'a reconciliation run is never deleted: decisions and items name it (INV-REC-01)';
END;
$$;

CREATE TRIGGER reconciliation_batch_is_never_deleted
    BEFORE DELETE ON reconciliation.reconciliation_batch
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.reconciliation_batch_is_never_deleted();

CREATE TABLE reconciliation.reconciliation_batch_event (
    seq            BIGINT      GENERATED ALWAYS AS IDENTITY,
    run_id         UUID        NOT NULL,
    from_status    TEXT,
    to_status      TEXT        NOT NULL,
    actor          TEXT        NOT NULL,
    actor_type     TEXT        NOT NULL,
    reason         TEXT,
    occurred_at    TIMESTAMPTZ NOT NULL,
    correlation_id TEXT        NOT NULL,

    CONSTRAINT reconciliation_batch_event_pk PRIMARY KEY (seq),
    CONSTRAINT reconciliation_batch_event_run_fk FOREIGN KEY (run_id)
        REFERENCES reconciliation.reconciliation_batch (id),
    CONSTRAINT reconciliation_batch_event_statuses CHECK (
        (from_status IS NULL OR from_status IN ('OPEN', 'IN_PROGRESS', 'COMPLETED', 'BLOCKED'))
        AND to_status IN ('OPEN', 'IN_PROGRESS', 'COMPLETED', 'BLOCKED')),
    CONSTRAINT reconciliation_batch_event_reason_bounded CHECK (char_length(reason) <= 1000)
);

CREATE INDEX reconciliation_batch_event_by_run
    ON reconciliation.reconciliation_batch_event (run_id, seq);

CREATE OR REPLACE FUNCTION reconciliation.reconciliation_batch_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'a reconciliation run''s history is append-only for every writer (INV-REC-01)';
END;
$$;

CREATE TRIGGER reconciliation_batch_event_is_append_only
    BEFORE UPDATE OR DELETE ON reconciliation.reconciliation_batch_event
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.reconciliation_batch_event_is_append_only();

-- ---------------------------------------------------------------------------------------------
-- The external item: the working copy the matcher disposes of.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE reconciliation.external_item (
    id                    UUID        NOT NULL,
    run_id                UUID        NOT NULL,
    source_id             UUID        NOT NULL,
    settlement_line_id    UUID        NOT NULL,
    line_no               INT         NOT NULL,
    line_type             TEXT        NOT NULL,
    direction             TEXT        NOT NULL,
    amount_minor          BIGINT      NOT NULL,
    currency              CHAR(3)     NOT NULL,
    scale                 SMALLINT    NOT NULL,
    position_purpose      TEXT        NOT NULL,
    business_date         DATE        NOT NULL,
    settlement_date       DATE,
    value_date            DATE,
    canonical_fingerprint BYTEA       NOT NULL,
    allocated_minor       BIGINT      NOT NULL DEFAULT 0,
    parked_minor          BIGINT      NOT NULL DEFAULT 0,
    offset_minor          BIGINT      NOT NULL DEFAULT 0,
    status                TEXT        NOT NULL DEFAULT 'PENDING',
    grace_until           TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL,
    status_changed_at     TIMESTAMPTZ NOT NULL,
    correlation_id        TEXT        NOT NULL,

    CONSTRAINT external_item_pk PRIMARY KEY (id),
    CONSTRAINT external_item_run_fk FOREIGN KEY (run_id)
        REFERENCES reconciliation.reconciliation_batch (id),
    -- One working copy per settlement line, for any writer under any race.
    CONSTRAINT external_item_line_once UNIQUE (settlement_line_id),
    CONSTRAINT external_item_line_no_positive CHECK (line_no >= 1),
    -- ExternalLineType.sqlValueList - reconciliation's mirror of the canonical vocabulary,
    -- held name-equal to settlement's by the app guard (ADR-0064 forbids the build edge).
    CONSTRAINT external_item_line_type CHECK (line_type IN (
        'CAPTURE', 'REFUND', 'CHARGEBACK', 'CHARGEBACK_REVERSAL', 'DISPUTE_FEE',
        'PROCESSING_FEE', 'COUNTERPARTY_ADJUSTMENT', 'OTHER_IN', 'OTHER_OUT')),
    CONSTRAINT external_item_direction CHECK (direction IN ('INBOUND', 'OUTBOUND')),
    CONSTRAINT external_item_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT external_item_currency_shape CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT external_item_scale_bounded CHECK (scale BETWEEN 0 AND 9),
    CONSTRAINT external_item_fingerprint_is_sha256 CHECK (
        octet_length(canonical_fingerprint) = 32),
    -- ItemStatus.sqlValueList.
    CONSTRAINT external_item_status CHECK (status IN (
        'PENDING', 'MATCHED', 'CHECKED', 'OFFSET', 'UNMATCHED', 'PARKED', 'RESOLVED',
        'REPUDIATED')),
    -- Value is conserved (INV-REC-07's item side): the dispositions never exceed the item.
    CONSTRAINT external_item_value_conserved CHECK (
        allocated_minor >= 0 AND parked_minor >= 0 AND offset_minor >= 0
            AND allocated_minor + parked_minor + offset_minor <= amount_minor)
);

CREATE INDEX external_item_by_run ON reconciliation.external_item (run_id, line_no);
CREATE INDEX external_item_by_source_and_status
    ON reconciliation.external_item (source_id, status);

COMMENT ON TABLE reconciliation.external_item IS
    'Reconciliation''s working copy of one immutable settlement line (section 5.4, ADR-0064): born PENDING in the acceptance transaction with disposition zero; the run, rematch, grace and resolution legs move it, and its batch''s repudiation ends it. Amounts are RESTRICTED-FINANCIAL.';

CREATE OR REPLACE FUNCTION reconciliation.external_item_moves_only_on_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.run_id IS DISTINCT FROM OLD.run_id
            OR NEW.source_id IS DISTINCT FROM OLD.source_id
            OR NEW.settlement_line_id IS DISTINCT FROM OLD.settlement_line_id
            OR NEW.line_no IS DISTINCT FROM OLD.line_no
            OR NEW.line_type IS DISTINCT FROM OLD.line_type
            OR NEW.direction IS DISTINCT FROM OLD.direction
            OR NEW.amount_minor IS DISTINCT FROM OLD.amount_minor
            OR NEW.currency IS DISTINCT FROM OLD.currency
            OR NEW.scale IS DISTINCT FROM OLD.scale
            OR NEW.position_purpose IS DISTINCT FROM OLD.position_purpose
            OR NEW.business_date IS DISTINCT FROM OLD.business_date
            OR NEW.settlement_date IS DISTINCT FROM OLD.settlement_date
            OR NEW.value_date IS DISTINCT FROM OLD.value_date
            OR NEW.canonical_fingerprint IS DISTINCT FROM OLD.canonical_fingerprint
            OR NEW.created_at IS DISTINCT FROM OLD.created_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'an external item''s copied line is frozen (ADR-0064, INV-HIST-01''s discipline): a corrected line is a NEW line in a later batch';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status
            AND NOT ((OLD.status = 'PENDING' AND NEW.status IN ('MATCHED', 'CHECKED', 'OFFSET', 'UNMATCHED', 'PARKED')) OR (OLD.status = 'MATCHED' AND NEW.status IN ('UNMATCHED', 'REPUDIATED')) OR (OLD.status = 'CHECKED' AND NEW.status IN ('REPUDIATED')) OR (OLD.status = 'OFFSET' AND NEW.status IN ('REPUDIATED')) OR (OLD.status = 'UNMATCHED' AND NEW.status IN ('MATCHED', 'PARKED', 'REPUDIATED')) OR (OLD.status = 'PARKED' AND NEW.status IN ('MATCHED', 'RESOLVED', 'REPUDIATED'))) THEN
        RAISE EXCEPTION 'not an external item edge: % -> % (section 5.4)',
            OLD.status, NEW.status;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER external_item_moves_only_on_machine_edges
    BEFORE UPDATE ON reconciliation.external_item
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.external_item_moves_only_on_machine_edges();

CREATE OR REPLACE FUNCTION reconciliation.external_item_is_never_deleted()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'an external item is never deleted: unmatched records are never discarded (INV-REC-02)';
END;
$$;

CREATE TRIGGER external_item_is_never_deleted
    BEFORE DELETE ON reconciliation.external_item
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.external_item_is_never_deleted();

CREATE TABLE reconciliation.external_item_event (
    seq            BIGINT      GENERATED ALWAYS AS IDENTITY,
    item_id        UUID        NOT NULL,
    from_status    TEXT,
    to_status      TEXT        NOT NULL,
    actor          TEXT        NOT NULL,
    actor_type     TEXT        NOT NULL,
    reason         TEXT,
    occurred_at    TIMESTAMPTZ NOT NULL,
    correlation_id TEXT        NOT NULL,

    CONSTRAINT external_item_event_pk PRIMARY KEY (seq),
    CONSTRAINT external_item_event_item_fk FOREIGN KEY (item_id)
        REFERENCES reconciliation.external_item (id),
    CONSTRAINT external_item_event_statuses CHECK (
        (from_status IS NULL OR from_status IN (
            'PENDING', 'MATCHED', 'CHECKED', 'OFFSET', 'UNMATCHED', 'PARKED', 'RESOLVED',
            'REPUDIATED'))
        AND to_status IN (
            'PENDING', 'MATCHED', 'CHECKED', 'OFFSET', 'UNMATCHED', 'PARKED', 'RESOLVED',
            'REPUDIATED')),
    CONSTRAINT external_item_event_reason_bounded CHECK (char_length(reason) <= 1000)
);

CREATE INDEX external_item_event_by_item
    ON reconciliation.external_item_event (item_id, seq);

CREATE OR REPLACE FUNCTION reconciliation.external_item_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'an external item''s history is append-only for every writer (INV-REC-01)';
END;
$$;

CREATE TRIGGER external_item_event_is_append_only
    BEFORE UPDATE OR DELETE ON reconciliation.external_item_event
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.external_item_event_is_append_only();

-- ---------------------------------------------------------------------------------------------
-- The item's typed keys: the external side's references, indexed for the matcher.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE reconciliation.external_item_key (
    item_id   UUID NOT NULL,
    source_id UUID NOT NULL,
    key_kind  TEXT NOT NULL,
    key_value TEXT NOT NULL,

    CONSTRAINT external_item_key_pk PRIMARY KEY (item_id, key_kind),
    CONSTRAINT external_item_key_item_fk FOREIGN KEY (item_id)
        REFERENCES reconciliation.external_item (id),
    -- ItemKeyKind.sqlValueList - the EXTERNAL vocabulary (the counterparty's references);
    -- which INTERNAL kind each is matched against is the rule set's decision (P8-TSK-011).
    CONSTRAINT external_item_key_kind CHECK (key_kind IN (
        'PSP_CAPTURE_REF', 'PSP_REFUND_REF', 'ACQUIRER_REF', 'DISPUTE_REF', 'OUR_REF',
        'ORIGINAL_REF')),
    CONSTRAINT external_item_key_value_bounded CHECK (
        char_length(key_value) BETWEEN 1 AND 100)
);

-- Deliberately NON-UNIQUE (ADR-0068): two lines quoting one reference are both real
-- evidence, and the second becomes DUPLICATE_EXTERNAL at matching, never lost here.
CREATE INDEX external_item_key_by_source
    ON reconciliation.external_item_key (source_id, key_kind, key_value);

CREATE OR REPLACE FUNCTION reconciliation.external_item_key_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'an external item''s keys are its copied line''s, append-only for every writer (INV-REC-01)';
END;
$$;

CREATE TRIGGER external_item_key_is_append_only
    BEFORE UPDATE OR DELETE ON reconciliation.external_item_key
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.external_item_key_is_append_only();

-- ---------------------------------------------------------------------------------------------
-- Grants: least privilege; no DELETE for finapp_app anywhere, ever.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON reconciliation.reconciliation_batch TO finapp_app;
GRANT UPDATE (status, cursor, failures, status_changed_at)
    ON reconciliation.reconciliation_batch TO finapp_app;
GRANT SELECT, INSERT ON reconciliation.reconciliation_batch_event TO finapp_app;
GRANT SELECT, INSERT ON reconciliation.external_item TO finapp_app;
GRANT UPDATE (status, allocated_minor, parked_minor, offset_minor, grace_until,
              status_changed_at)
    ON reconciliation.external_item TO finapp_app;
GRANT SELECT, INSERT ON reconciliation.external_item_event TO finapp_app;
GRANT SELECT, INSERT ON reconciliation.external_item_key TO finapp_app;
