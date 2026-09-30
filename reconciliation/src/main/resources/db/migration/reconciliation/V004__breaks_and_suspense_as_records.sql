-- P8-TSK-010: breaks and suspense as records (ADR-0069, ADR-0070; INV-REC-02, INV-REC-09).
--
-- Every discrepancy is a classified, severity-graded break that is NEVER deleted - no DELETE
-- grant, and a refusing trigger beneath it for every writer, the migrator included. Every
-- unit of value in SUSPENSE_UNMATCHED belongs to a suspense item that names the one break
-- answering for it (break_id NOT NULL), opened only in the transaction that records that
-- break. The machines are generated from BreakStatus.permittedTransitions() and
-- SuspenseItemStatus.permittedTransitions(), the value lists from their enums, and the
-- raise-time type-cause pairing from BreakCause.raisesAs() - each reconciled by
-- ReconciliationV004MigrationTest. Only birth is produced by P8-TSK-010; the later edges
-- arrive with their producers and stay inert behind the narrowed UPDATE grants.

-- ---------------------------------------------------------------------------------------------
-- The card-number screen for free text (INV-PAY-02 at the database rank): a Luhn-valid
-- 13..19-digit run anywhere in the text. Bodies are bounded, so the window scan is too.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION reconciliation.holds_luhn_valid_digit_run(body TEXT)
    RETURNS boolean
    LANGUAGE plpgsql
    IMMUTABLE
AS $$
DECLARE
    run       TEXT;
    candidate TEXT;
    len       INT;
    width     INT;
    start_at  INT;
    total     INT;
    digit     INT;
    i         INT;
BEGIN
    FOR run IN SELECT (regexp_matches(body, '[0-9]{13,}', 'g'))[1] LOOP
        len := char_length(run);
        width := 13;
        WHILE width <= 19 AND width <= len LOOP
            start_at := 1;
            WHILE start_at + width - 1 <= len LOOP
                candidate := substr(run, start_at, width);
                total := 0;
                FOR i IN 1..width LOOP
                    digit := substr(candidate, width - i + 1, 1)::int;
                    IF i % 2 = 0 THEN
                        digit := digit * 2;
                        IF digit > 9 THEN
                            digit := digit - 9;
                        END IF;
                    END IF;
                    total := total + digit;
                END LOOP;
                IF total % 10 = 0 THEN
                    RETURN true;
                END IF;
                start_at := start_at + 1;
            END LOOP;
            width := width + 1;
        END LOOP;
    END LOOP;
    RETURN false;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The break: a subject, a type, a cause and a value at issue, fixed when it is raised
-- (ADR-0069 section 1). The suspense-item foreign key is added after that table exists.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE reconciliation.break (
    id                      UUID        NOT NULL,
    type                    TEXT        NOT NULL,
    cause                   TEXT        NOT NULL,
    status                  TEXT        NOT NULL DEFAULT 'OPEN',
    severity                TEXT        NOT NULL,
    source_id               UUID        NOT NULL,
    rule_set_id             UUID        NOT NULL,
    expectation_id          UUID,
    external_item_id        UUID,
    suspense_item_id        UUID,
    run_id                  UUID,
    -- No foreign key yet: match decisions arrive with V005 (P8-TSK-011), which adds it.
    decision_id             UUID,
    value_at_issue_minor    BIGINT      NOT NULL,
    currency                CHAR(3)     NOT NULL,
    scale                   SMALLINT    NOT NULL,
    internal_classification TEXT,
    internal_operation_ref  TEXT,
    internal_state          TEXT,
    assignee                TEXT,
    residual_version        BIGINT      NOT NULL DEFAULT 0,
    follows_break_id        UUID,
    raised_at               TIMESTAMPTZ NOT NULL,
    resolved_at             TIMESTAMPTZ,
    status_changed_at       TIMESTAMPTZ NOT NULL,
    correlation_id          TEXT        NOT NULL,

    CONSTRAINT break_pk PRIMARY KEY (id),
    CONSTRAINT break_rule_set_fk FOREIGN KEY (rule_set_id)
        REFERENCES reconciliation.rule_set (id),
    CONSTRAINT break_expectation_fk FOREIGN KEY (expectation_id)
        REFERENCES reconciliation.expectation (id),
    CONSTRAINT break_external_item_fk FOREIGN KEY (external_item_id)
        REFERENCES reconciliation.external_item (id),
    CONSTRAINT break_run_fk FOREIGN KEY (run_id)
        REFERENCES reconciliation.reconciliation_batch (id),
    CONSTRAINT break_follows_fk FOREIGN KEY (follows_break_id)
        REFERENCES reconciliation.break (id),
    -- BreakType.sqlValueList - fourteen, closed; a fifteenth amends ADR-0069.
    CONSTRAINT break_type CHECK (type IN (
        'MISSING_EXTERNAL', 'MISSING_INTERNAL', 'UNKNOWN_EXTERNAL', 'AMOUNT_MISMATCH',
        'CURRENCY_MISMATCH', 'FEE_MISMATCH', 'DUPLICATE_EXTERNAL', 'DUPLICATE_INTERNAL',
        'AMBIGUOUS_MATCH', 'TIMING_DIFFERENCE', 'REVERSAL_MISMATCH', 'REFUND_MISMATCH',
        'SETTLEMENT_MISMATCH', 'PROCESSING_ERROR')),
    -- BreakCause.sqlValueList - which detector raised it, frozen for life.
    CONSTRAINT break_cause CHECK (cause IN (
        'EXPECTATION_OVERDUE', 'GRACE_EXPIRED', 'PARKED_ON_RECEIPT', 'BANK_LINE_UNATTRIBUTED',
        'AMOUNT_DIFFERS', 'CURRENCY_DIFFERS', 'FEE_BEYOND_TOLERANCE', 'EXPECTATION_EXHAUSTED',
        'REPEATED_FINGERPRINT', 'KEY_COLLISION', 'MULTIPLE_CANDIDATES', 'LATE_MATCH',
        'CYCLE_MISMATCH', 'DIRECTION_CONTRADICTED', 'TERMINAL_STATE_CONTRADICTED',
        'RETURN_NOT_APPLICABLE', 'REFUND_CONTRADICTED', 'REMITTANCE_DIFFERS', 'STATEMENT_GAP',
        'OPENING_BALANCE', 'ITEM_ERRORED', 'RUN_BLOCKED', 'REPLAY_DIVERGED',
        'EVIDENCE_REPUDIATED')),
    -- BreakStatus.sqlValueList.
    CONSTRAINT break_status CHECK (status IN (
        'OPEN', 'INVESTIGATING', 'RESOLUTION_PROPOSED', 'RESOLVED')),
    -- Severity.sqlValueList.
    CONSTRAINT break_severity CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    -- InternalClassification.sqlValueList - what the lookup answered at raise, frozen.
    CONSTRAINT break_internal_classification CHECK (internal_classification IS NULL
        OR internal_classification IN ('UNKNOWN', 'IN_FLIGHT', 'TERMINAL', 'COMPLETED')),
    -- A break needs a subject that holds value (ADR-0069 section 3): at least one.
    CONSTRAINT break_has_a_subject CHECK (
        expectation_id IS NOT NULL OR external_item_id IS NOT NULL
        OR suspense_item_id IS NOT NULL OR run_id IS NOT NULL OR decision_id IS NOT NULL),
    -- The ADR-0003 triple, in the subject's own currency, never converted (INV-MON-04).
    CONSTRAINT break_value_counted CHECK (value_at_issue_minor >= 0),
    CONSTRAINT break_currency_shape CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT break_scale_bounded CHECK (scale BETWEEN 0 AND 9),
    CONSTRAINT break_residual_counted CHECK (residual_version >= 0),
    CONSTRAINT break_resolved_iff_dated CHECK ((status = 'RESOLVED') = (resolved_at IS NOT NULL)),
    CONSTRAINT break_internal_ref_bounded CHECK (char_length(internal_operation_ref) <= 200),
    CONSTRAINT break_internal_state_bounded CHECK (char_length(internal_state) <= 100),
    CONSTRAINT break_assignee_bounded CHECK (char_length(assignee) <= 100)
);

-- One open break per (type, subject) for any writer under any race (ADR-0069 section 4):
-- the raise's ON CONFLICT DO NOTHING converges on these. A resolved break frees the seat,
-- and the recurrence names its predecessor (follows_break_id).
CREATE UNIQUE INDEX break_one_open_per_expectation
    ON reconciliation.break (type, expectation_id) WHERE status <> 'RESOLVED';
CREATE UNIQUE INDEX break_one_open_per_external_item
    ON reconciliation.break (type, external_item_id) WHERE status <> 'RESOLVED';
CREATE UNIQUE INDEX break_one_open_per_suspense_item
    ON reconciliation.break (type, suspense_item_id) WHERE status <> 'RESOLVED';
CREATE UNIQUE INDEX break_one_open_per_run
    ON reconciliation.break (type, run_id) WHERE status <> 'RESOLVED';

-- The desk's reads (P8-TSK-014) and the sweep's escalation scans.
CREATE INDEX break_open_by_severity
    ON reconciliation.break (severity, raised_at) WHERE status <> 'RESOLVED';
CREATE INDEX break_by_source ON reconciliation.break (source_id, raised_at);

COMMENT ON TABLE reconciliation.break IS
    'A classified discrepancy (ADR-0069): never deleted, its subject, cause and value at issue frozen at raise; value_at_issue_* RESTRICTED-FINANCIAL, internal_operation_ref CONFIDENTIAL.';

-- The raise-time type-cause pairing, generated from BreakCause.raisesAs(). A BEFORE INSERT
-- trigger and DELIBERATELY not a CHECK: a reclassification (ADR-0069 section 7) moves the
-- type while the cause stays frozen, so the pairing binds only the raise.
CREATE OR REPLACE FUNCTION reconciliation.break_is_raised_by_its_own_detector()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT ((NEW.cause = 'EXPECTATION_OVERDUE' AND NEW.type IN ('MISSING_EXTERNAL')) OR (NEW.cause = 'GRACE_EXPIRED' AND NEW.type IN ('MISSING_INTERNAL', 'UNKNOWN_EXTERNAL')) OR (NEW.cause = 'PARKED_ON_RECEIPT' AND NEW.type IN ('UNKNOWN_EXTERNAL')) OR (NEW.cause = 'BANK_LINE_UNATTRIBUTED' AND NEW.type IN ('UNKNOWN_EXTERNAL')) OR (NEW.cause = 'AMOUNT_DIFFERS' AND NEW.type IN ('AMOUNT_MISMATCH')) OR (NEW.cause = 'CURRENCY_DIFFERS' AND NEW.type IN ('CURRENCY_MISMATCH')) OR (NEW.cause = 'FEE_BEYOND_TOLERANCE' AND NEW.type IN ('FEE_MISMATCH')) OR (NEW.cause = 'EXPECTATION_EXHAUSTED' AND NEW.type IN ('DUPLICATE_EXTERNAL')) OR (NEW.cause = 'REPEATED_FINGERPRINT' AND NEW.type IN ('DUPLICATE_EXTERNAL')) OR (NEW.cause = 'KEY_COLLISION' AND NEW.type IN ('DUPLICATE_INTERNAL')) OR (NEW.cause = 'MULTIPLE_CANDIDATES' AND NEW.type IN ('AMBIGUOUS_MATCH')) OR (NEW.cause = 'LATE_MATCH' AND NEW.type IN ('TIMING_DIFFERENCE')) OR (NEW.cause = 'CYCLE_MISMATCH' AND NEW.type IN ('TIMING_DIFFERENCE')) OR (NEW.cause = 'DIRECTION_CONTRADICTED' AND NEW.type IN ('REVERSAL_MISMATCH')) OR (NEW.cause = 'TERMINAL_STATE_CONTRADICTED' AND NEW.type IN ('REVERSAL_MISMATCH')) OR (NEW.cause = 'RETURN_NOT_APPLICABLE' AND NEW.type IN ('REVERSAL_MISMATCH')) OR (NEW.cause = 'REFUND_CONTRADICTED' AND NEW.type IN ('REFUND_MISMATCH')) OR (NEW.cause = 'REMITTANCE_DIFFERS' AND NEW.type IN ('SETTLEMENT_MISMATCH')) OR (NEW.cause = 'STATEMENT_GAP' AND NEW.type IN ('SETTLEMENT_MISMATCH')) OR (NEW.cause = 'OPENING_BALANCE' AND NEW.type IN ('SETTLEMENT_MISMATCH')) OR (NEW.cause = 'ITEM_ERRORED' AND NEW.type IN ('PROCESSING_ERROR')) OR (NEW.cause = 'RUN_BLOCKED' AND NEW.type IN ('PROCESSING_ERROR')) OR (NEW.cause = 'REPLAY_DIVERGED' AND NEW.type IN ('PROCESSING_ERROR')) OR (NEW.cause = 'EVIDENCE_REPUDIATED' AND NEW.type IN ('PROCESSING_ERROR'))) THEN
        RAISE EXCEPTION 'a break is raised only by its own detector: % never raises % (ADR-0069 section 2)',
            NEW.cause, NEW.type;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER break_is_raised_by_its_own_detector
    BEFORE INSERT ON reconciliation.break
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.break_is_raised_by_its_own_detector();

-- The moving columns move only on machine edges; everything else is frozen at raise, the
-- grade and the residual version only ever rise, and a RESOLVED break takes no write at all.
CREATE OR REPLACE FUNCTION reconciliation.break_moves_only_on_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'RESOLVED' THEN
        RAISE EXCEPTION 'a resolved break takes no write; the case continues on its successor (ADR-0069 section 4)';
    END IF;
    IF NEW.cause IS DISTINCT FROM OLD.cause
            OR NEW.source_id IS DISTINCT FROM OLD.source_id
            OR NEW.rule_set_id IS DISTINCT FROM OLD.rule_set_id
            OR NEW.expectation_id IS DISTINCT FROM OLD.expectation_id
            OR NEW.external_item_id IS DISTINCT FROM OLD.external_item_id
            OR NEW.suspense_item_id IS DISTINCT FROM OLD.suspense_item_id
            OR NEW.run_id IS DISTINCT FROM OLD.run_id
            OR NEW.decision_id IS DISTINCT FROM OLD.decision_id
            OR NEW.value_at_issue_minor IS DISTINCT FROM OLD.value_at_issue_minor
            OR NEW.currency IS DISTINCT FROM OLD.currency
            OR NEW.scale IS DISTINCT FROM OLD.scale
            OR NEW.internal_classification IS DISTINCT FROM OLD.internal_classification
            OR NEW.internal_operation_ref IS DISTINCT FROM OLD.internal_operation_ref
            OR NEW.internal_state IS DISTINCT FROM OLD.internal_state
            OR NEW.follows_break_id IS DISTINCT FROM OLD.follows_break_id
            OR NEW.raised_at IS DISTINCT FROM OLD.raised_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a break''s subject, cause and value at issue are fixed when it is raised (ADR-0069 section 1)';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status
            AND NOT ((OLD.status = 'OPEN' AND NEW.status IN ('INVESTIGATING', 'RESOLUTION_PROPOSED', 'RESOLVED')) OR (OLD.status = 'INVESTIGATING' AND NEW.status IN ('RESOLUTION_PROPOSED', 'RESOLVED')) OR (OLD.status = 'RESOLUTION_PROPOSED' AND NEW.status IN ('INVESTIGATING', 'RESOLVED'))) THEN
        RAISE EXCEPTION 'not a break edge: % -> % (section 5.6)', OLD.status, NEW.status;
    END IF;
    IF NOT ((OLD.severity = 'LOW' AND NEW.severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')) OR (OLD.severity = 'MEDIUM' AND NEW.severity IN ('MEDIUM', 'HIGH', 'CRITICAL')) OR (OLD.severity = 'HIGH' AND NEW.severity IN ('HIGH', 'CRITICAL')) OR (OLD.severity = 'CRITICAL' AND NEW.severity IN ('CRITICAL'))) THEN
        RAISE EXCEPTION 'severity only ever rises: a relabel cannot quiet an alert (ADR-0069 section 5)';
    END IF;
    IF NEW.residual_version < OLD.residual_version THEN
        RAISE EXCEPTION 'the residual version only moves forward (ADR-0069 section 1)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER break_moves_only_on_machine_edges
    BEFORE UPDATE ON reconciliation.break
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.break_moves_only_on_machine_edges();

CREATE OR REPLACE FUNCTION reconciliation.break_is_never_deleted()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'no code path deletes a break (INV-REC-02; MODULE_ARCHITECTURE.md''s hard rule at two ranks)';
END;
$$;

CREATE TRIGGER break_is_never_deleted
    BEFORE DELETE ON reconciliation.break
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.break_is_never_deleted();

-- ---------------------------------------------------------------------------------------------
-- The break's append-only history: raises, assignments, reclassifications, escalations.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE reconciliation.break_event (
    seq            BIGINT      GENERATED ALWAYS AS IDENTITY,
    break_id       UUID        NOT NULL,
    event_type     TEXT        NOT NULL,
    actor          TEXT        NOT NULL,
    actor_type     TEXT        NOT NULL,
    reason         TEXT,
    detail         TEXT,
    occurred_at    TIMESTAMPTZ NOT NULL,
    correlation_id TEXT        NOT NULL,

    CONSTRAINT break_event_pk PRIMARY KEY (seq),
    CONSTRAINT break_event_break_fk FOREIGN KEY (break_id)
        REFERENCES reconciliation.break (id),
    -- BreakEventType.sqlValueList.
    CONSTRAINT break_event_type CHECK (event_type IN (
        'RAISED', 'ASSIGNED', 'RECLASSIFIED', 'SEVERITY_ESCALATED', 'RESOLVED')),
    CONSTRAINT break_event_reason_bounded CHECK (char_length(reason) <= 1000),
    -- Identifiers and enumerated names only, never an amount (INV-AUD-02).
    CONSTRAINT break_event_detail_bounded CHECK (char_length(detail) <= 500)
);

CREATE INDEX break_event_by_break ON reconciliation.break_event (break_id, seq);

CREATE OR REPLACE FUNCTION reconciliation.break_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'a break''s history is append-only for every writer (INV-REC-02)';
END;
$$;

CREATE TRIGGER break_event_is_append_only
    BEFORE UPDATE OR DELETE ON reconciliation.break_event
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.break_event_is_append_only();

-- ---------------------------------------------------------------------------------------------
-- Notes: an investigator's own words, CONFIDENTIAL, screened at this rank for card-number
-- and bank-identifier shapes (INV-PAY-02, INV-RAIL-03) - ready for their P8-TSK-014 door.
-- The unanchored IBAN shape over-refuses some identifier-dense prose; the conservative
-- refusal is deliberate (the ADR-0066 section 4 asymmetry), and identifiers belong in
-- evidence links, not note bodies.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE reconciliation.break_note (
    id             UUID        NOT NULL,
    break_id       UUID        NOT NULL,
    body           TEXT        NOT NULL,
    author         TEXT        NOT NULL,
    author_type    TEXT        NOT NULL,
    added_at       TIMESTAMPTZ NOT NULL,
    correlation_id TEXT        NOT NULL,

    CONSTRAINT break_note_pk PRIMARY KEY (id),
    CONSTRAINT break_note_break_fk FOREIGN KEY (break_id)
        REFERENCES reconciliation.break (id),
    CONSTRAINT break_note_body_bounded CHECK (char_length(body) BETWEEN 1 AND 4000),
    CONSTRAINT break_note_no_card_number CHECK (
        NOT reconciliation.holds_luhn_valid_digit_run(body)),
    CONSTRAINT break_note_no_account_shape CHECK (
        body !~ '\m[A-Za-z]{2}[0-9]{2}[A-Za-z0-9]{11,30}\M')
);

CREATE INDEX break_note_by_break ON reconciliation.break_note (break_id, added_at);

COMMENT ON TABLE reconciliation.break_note IS
    'An investigator''s notes (CONFIDENTIAL): never logged, evented or audited in the body; screened here for PAN and IBAN shapes for every writer.';

CREATE OR REPLACE FUNCTION reconciliation.break_note_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'a break''s notes are append-only for every writer (ADR-0069 section 7)';
END;
$$;

CREATE TRIGGER break_note_is_append_only
    BEFORE UPDATE OR DELETE ON reconciliation.break_note
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.break_note_is_append_only();

-- ---------------------------------------------------------------------------------------------
-- Evidence links: identifiers into the stored chain (INV-REC-01), never content or a URL.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE reconciliation.break_evidence_link (
    id             UUID        NOT NULL,
    break_id       UUID        NOT NULL,
    target_kind    TEXT        NOT NULL,
    target_ref     TEXT        NOT NULL,
    added_by       TEXT        NOT NULL,
    added_by_type  TEXT        NOT NULL,
    added_at       TIMESTAMPTZ NOT NULL,
    correlation_id TEXT        NOT NULL,

    CONSTRAINT break_evidence_link_pk PRIMARY KEY (id),
    CONSTRAINT break_evidence_link_break_fk FOREIGN KEY (break_id)
        REFERENCES reconciliation.break (id),
    -- EvidenceTargetKind.sqlValueList.
    CONSTRAINT break_evidence_link_kind CHECK (target_kind IN (
        'SETTLEMENT_FILE', 'SETTLEMENT_BATCH', 'SETTLEMENT_LINE', 'JOURNAL_ENTRY',
        'OPERATION', 'PROVIDER_EVIDENCE', 'RUN', 'DECISION')),
    CONSTRAINT break_evidence_link_ref_bounded CHECK (
        char_length(target_ref) BETWEEN 1 AND 200),
    CONSTRAINT break_evidence_link_no_card_number CHECK (
        NOT reconciliation.holds_luhn_valid_digit_run(target_ref)),
    CONSTRAINT break_evidence_link_no_account_shape CHECK (
        target_ref !~ '\m[A-Za-z]{2}[0-9]{2}[A-Za-z0-9]{11,30}\M')
);

CREATE INDEX break_evidence_link_by_break
    ON reconciliation.break_evidence_link (break_id, added_at);

CREATE OR REPLACE FUNCTION reconciliation.break_evidence_link_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'a break''s evidence links are append-only for every writer (ADR-0069 section 7)';
END;
$$;

CREATE TRIGGER break_evidence_link_is_append_only
    BEFORE UPDATE OR DELETE ON reconciliation.break_evidence_link
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.break_evidence_link_is_append_only();

-- ---------------------------------------------------------------------------------------------
-- The park: one posting per (transaction, position, settlement date), at most four lines,
-- keyed recon-suspense:<parkId>. Append-only, so decided_on is stamped once structurally;
-- inserted AFTER its posting, carrying the entry id (the payout_return precedent) - the
-- posting stays the last CONTENDED write, and this row is the transaction's own claim.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE reconciliation.park (
    id                  UUID        NOT NULL,
    source_id           UUID        NOT NULL,
    kind                TEXT        NOT NULL,
    position_account_id UUID        NOT NULL,
    currency            CHAR(3)     NOT NULL,
    decided_on          DATE        NOT NULL,
    value_date          DATE        NOT NULL,
    journal_entry_id    UUID        NOT NULL,
    actor               TEXT        NOT NULL,
    actor_type          TEXT        NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL,
    correlation_id      TEXT        NOT NULL,

    CONSTRAINT park_pk PRIMARY KEY (id),
    -- ParkKind.sqlValueList - the unpark posts the exact inverse under a NEW park id.
    CONSTRAINT park_kind CHECK (kind IN ('PARK', 'UNPARK')),
    CONSTRAINT park_entry_once UNIQUE (journal_entry_id),
    CONSTRAINT park_currency_shape CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE OR REPLACE FUNCTION reconciliation.park_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'a park is append-only for every writer: decided_on is stamped once (ADR-0070 section 2)';
END;
$$;

CREATE TRIGGER park_is_append_only
    BEFORE UPDATE OR DELETE ON reconciliation.park
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.park_is_append_only();

-- ---------------------------------------------------------------------------------------------
-- The suspense item: every unit of parked value, owned by exactly one break (INV-REC-09).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE reconciliation.suspense_item (
    id                  UUID        NOT NULL,
    break_id            UUID        NOT NULL,
    external_item_id    UUID,
    origin              TEXT        NOT NULL,
    origin_ref          TEXT        NOT NULL,
    side                TEXT        NOT NULL,
    amount_minor        BIGINT      NOT NULL,
    currency            CHAR(3)     NOT NULL,
    scale               SMALLINT    NOT NULL,
    released_minor      BIGINT      NOT NULL DEFAULT 0,
    status              TEXT        NOT NULL DEFAULT 'OPEN',
    opened_on           DATE        NOT NULL,
    entry_id            UUID        NOT NULL,
    park_id             UUID,
    position_account_id UUID,
    status_changed_at   TIMESTAMPTZ NOT NULL,
    correlation_id      TEXT        NOT NULL,

    CONSTRAINT suspense_item_pk PRIMARY KEY (id),
    CONSTRAINT suspense_item_break_fk FOREIGN KEY (break_id)
        REFERENCES reconciliation.break (id),
    CONSTRAINT suspense_item_external_item_fk FOREIGN KEY (external_item_id)
        REFERENCES reconciliation.external_item (id),
    CONSTRAINT suspense_item_park_fk FOREIGN KEY (park_id)
        REFERENCES reconciliation.park (id),
    -- Ten parks of one item: the item's conditional transition, with this beneath it.
    CONSTRAINT suspense_item_external_item_once UNIQUE (external_item_id),
    -- The origin's own row, one uniform arbiter: the backfill and every opener converge.
    CONSTRAINT suspense_item_origin_once UNIQUE (origin_ref),
    -- SuspenseOrigin.sqlValueList - REPUDIATION joins by V009 (P8-TSK-023).
    CONSTRAINT suspense_item_origin CHECK (origin IN (
        'RECON_PARK', 'BANK_UNATTRIBUTED', 'UNMATCHED_CONFIRMATION')),
    -- SuspenseSide.sqlValueList - fixed at birth, never netted against the other side.
    CONSTRAINT suspense_item_side CHECK (side IN ('CREDIT', 'DEBIT')),
    -- SuspenseItemStatus.sqlValueList.
    CONSTRAINT suspense_item_status CHECK (status IN (
        'OPEN', 'PARTIALLY_RELEASED', 'RELEASED')),
    CONSTRAINT suspense_item_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT suspense_item_currency_shape CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT suspense_item_scale_bounded CHECK (scale BETWEEN 0 AND 9),
    CONSTRAINT suspense_item_origin_ref_bounded CHECK (
        char_length(origin_ref) BETWEEN 1 AND 100),
    -- released_minor <= amount_minor, and the status is the amounts' mirror.
    CONSTRAINT suspense_item_release_counted CHECK (
        released_minor >= 0 AND released_minor <= amount_minor),
    CONSTRAINT suspense_item_status_mirrors_amounts CHECK (
        (status = 'OPEN' AND released_minor = 0)
        OR (status = 'PARTIALLY_RELEASED'
            AND released_minor > 0 AND released_minor < amount_minor)
        OR (status = 'RELEASED' AND released_minor = amount_minor)),
    -- A RECON_PARK item names its park and its position; the other origins carry their
    -- entry through their own records (the batch's recognition, the parking's entry).
    CONSTRAINT suspense_item_park_iff_parked CHECK (
        (origin = 'RECON_PARK') = (park_id IS NOT NULL)),
    CONSTRAINT suspense_item_position_iff_parked CHECK (
        (origin = 'RECON_PARK') = (position_account_id IS NOT NULL))
);

CREATE INDEX suspense_item_open_by_currency
    ON reconciliation.suspense_item (currency, side) WHERE status <> 'RELEASED';
CREATE INDEX suspense_item_by_break ON reconciliation.suspense_item (break_id);

COMMENT ON TABLE reconciliation.suspense_item IS
    'A unit of parked value and its owner (ADR-0070): amounts RESTRICTED-FINANCIAL, origin_ref CONFIDENTIAL; CREDIT and DEBIT items are never netted.';

-- The break-side FK that had to wait for this table.
ALTER TABLE reconciliation.break
    ADD CONSTRAINT break_suspense_item_fk FOREIGN KEY (suspense_item_id)
        REFERENCES reconciliation.suspense_item (id);

-- Ownership is typed (ADR-0070 section 2): only a break whose type parks may own an item,
-- and never one already RESOLVED - for every writer, raw SQL included. The list is
-- BreakType.sqlSuspenseOwningList.
CREATE OR REPLACE FUNCTION reconciliation.suspense_item_owner_must_park()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    owner_type   TEXT;
    owner_status TEXT;
BEGIN
    SELECT type, status INTO owner_type, owner_status
        FROM reconciliation.break WHERE id = NEW.break_id;
    IF owner_type IS NULL THEN
        RAISE EXCEPTION 'a suspense item is born with its owning break (INV-REC-09)';
    END IF;
    IF owner_type NOT IN ('MISSING_INTERNAL', 'UNKNOWN_EXTERNAL', 'AMOUNT_MISMATCH', 'CURRENCY_MISMATCH', 'DUPLICATE_EXTERNAL', 'AMBIGUOUS_MATCH', 'REVERSAL_MISMATCH', 'REFUND_MISMATCH', 'SETTLEMENT_MISMATCH', 'PROCESSING_ERROR') THEN
        RAISE EXCEPTION 'a % break never owns suspense (ADR-0070 section 2)', owner_type;
    END IF;
    IF owner_status = 'RESOLVED' THEN
        RAISE EXCEPTION 'a resolved break cannot take new value (ADR-0069 section 6)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER suspense_item_owner_must_park
    BEFORE INSERT ON reconciliation.suspense_item
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.suspense_item_owner_must_park();

-- Only the release columns move, and only on machine edges.
CREATE OR REPLACE FUNCTION reconciliation.suspense_item_moves_only_on_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.break_id IS DISTINCT FROM OLD.break_id
            OR NEW.external_item_id IS DISTINCT FROM OLD.external_item_id
            OR NEW.origin IS DISTINCT FROM OLD.origin
            OR NEW.origin_ref IS DISTINCT FROM OLD.origin_ref
            OR NEW.side IS DISTINCT FROM OLD.side
            OR NEW.amount_minor IS DISTINCT FROM OLD.amount_minor
            OR NEW.currency IS DISTINCT FROM OLD.currency
            OR NEW.scale IS DISTINCT FROM OLD.scale
            OR NEW.opened_on IS DISTINCT FROM OLD.opened_on
            OR NEW.entry_id IS DISTINCT FROM OLD.entry_id
            OR NEW.park_id IS DISTINCT FROM OLD.park_id
            OR NEW.position_account_id IS DISTINCT FROM OLD.position_account_id
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a suspense item''s birth facts are frozen: only its releases move (ADR-0070 section 1)';
    END IF;
    IF NEW.released_minor < OLD.released_minor THEN
        RAISE EXCEPTION 'a release is never taken back (ADR-0070 section 3)';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status
            AND NOT ((OLD.status = 'OPEN' AND NEW.status IN ('PARTIALLY_RELEASED', 'RELEASED')) OR (OLD.status = 'PARTIALLY_RELEASED' AND NEW.status IN ('RELEASED'))) THEN
        RAISE EXCEPTION 'not a suspense item edge: % -> % (section 5.8)', OLD.status, NEW.status;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER suspense_item_moves_only_on_machine_edges
    BEFORE UPDATE ON reconciliation.suspense_item
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.suspense_item_moves_only_on_machine_edges();

CREATE OR REPLACE FUNCTION reconciliation.suspense_item_is_never_deleted()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'a suspense item is never deleted (INV-REC-05, INV-REC-09)';
END;
$$;

CREATE TRIGGER suspense_item_is_never_deleted
    BEFORE DELETE ON reconciliation.suspense_item
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.suspense_item_is_never_deleted();

-- ---------------------------------------------------------------------------------------------
-- Releases: one row per release, the item's append-only history (ADR-0070 section 1).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE reconciliation.suspense_release (
    seq            BIGINT      GENERATED ALWAYS AS IDENTITY,
    item_id        UUID        NOT NULL,
    amount_minor   BIGINT      NOT NULL,
    park_id        UUID,
    cause          TEXT        NOT NULL,
    cause_ref      TEXT        NOT NULL,
    actor          TEXT        NOT NULL,
    actor_type     TEXT        NOT NULL,
    released_at    TIMESTAMPTZ NOT NULL,
    correlation_id TEXT        NOT NULL,

    CONSTRAINT suspense_release_pk PRIMARY KEY (seq),
    CONSTRAINT suspense_release_item_fk FOREIGN KEY (item_id)
        REFERENCES reconciliation.suspense_item (id),
    CONSTRAINT suspense_release_park_fk FOREIGN KEY (park_id)
        REFERENCES reconciliation.park (id),
    -- ReleaseCause.sqlValueList - stated whole; P8-TSK-010 produces only UNPARK.
    CONSTRAINT suspense_release_cause CHECK (cause IN (
        'UNPARK', 'CORRECTION_OFFSET', 'RESOLUTION', 'OFFSET_SUSPENSE', 'REPUDIATION')),
    CONSTRAINT suspense_release_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT suspense_release_ref_bounded CHECK (char_length(cause_ref) BETWEEN 1 AND 200)
);

CREATE INDEX suspense_release_by_item ON reconciliation.suspense_release (item_id, seq);

CREATE OR REPLACE FUNCTION reconciliation.suspense_release_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'a suspense release is history, append-only for every writer (ADR-0070 section 1)';
END;
$$;

CREATE TRIGGER suspense_release_is_append_only
    BEFORE UPDATE OR DELETE ON reconciliation.suspense_release
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.suspense_release_is_append_only();

-- ---------------------------------------------------------------------------------------------
-- Grants: least privilege; no DELETE for finapp_app anywhere, ever - and the refusing
-- triggers above hold DELETE shut for every OTHER writer too, the migrator included.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON reconciliation.break TO finapp_app;
GRANT UPDATE (status, type, severity, assignee, residual_version, resolved_at,
              status_changed_at)
    ON reconciliation.break TO finapp_app;
GRANT SELECT, INSERT ON reconciliation.break_event TO finapp_app;
GRANT SELECT, INSERT ON reconciliation.break_note TO finapp_app;
GRANT SELECT, INSERT ON reconciliation.break_evidence_link TO finapp_app;
GRANT SELECT, INSERT ON reconciliation.park TO finapp_app;
GRANT SELECT, INSERT ON reconciliation.suspense_item TO finapp_app;
GRANT UPDATE (released_minor, status, status_changed_at)
    ON reconciliation.suspense_item TO finapp_app;
GRANT SELECT, INSERT ON reconciliation.suspense_release TO finapp_app;
