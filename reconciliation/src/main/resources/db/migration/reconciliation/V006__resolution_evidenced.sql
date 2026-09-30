-- P8-TSK-012: the resolution record, born with the platform's EVIDENCED kind (ADR-0071;
-- INV-REC-02 as amended, INV-REC-03's threshold rule).
--
-- The vocabulary is stated whole in code (ResolutionKind, ResolutionStatus,
-- ResolutionReasonCode); THIS migration's CHECKs deliberately admit only the produced
-- subset - kind EVIDENCED, status APPROVED, reason EVIDENCE_RECEIVED - exactly as the
-- plan's section 8 slices V006 and V007: the person kinds, the PROPOSED machine and the
-- per-kind reason pairing arrive with P8-TSK-015, which REGENERATES these CHECKs from the
-- enums (V007). A narrowed rank, not a narrowed vocabulary.
--
-- Rows are append-only for every writer here: no UPDATE or DELETE grant, and refusing
-- triggers beneath the absent grants (V007 narrows the UPDATE when the person machine
-- brings decided edges).

CREATE TABLE reconciliation.resolution (
    id                      UUID        NOT NULL,
    break_id                UUID        NOT NULL,
    kind                    TEXT        NOT NULL,
    status                  TEXT        NOT NULL,
    reason_code             TEXT        NOT NULL,
    narrative               TEXT        NOT NULL,
    four_eyes               BOOLEAN     NOT NULL,
    proposed_amount_minor   BIGINT      NOT NULL,
    currency                CHAR(3)     NOT NULL,
    scale                   SMALLINT    NOT NULL,
    residual_version        BIGINT      NOT NULL,
    target_account_id       UUID,
    offset_item_id          UUID,
    chosen_expectation_id   UUID,
    decision_id             UUID,
    park_id                 UUID,
    rule_set_id             UUID        NOT NULL,
    adjustment_proposal_id  UUID,
    journal_entry_id        UUID,
    proposed_by             TEXT        NOT NULL,
    proposed_by_type        TEXT        NOT NULL,
    proposed_at             TIMESTAMPTZ NOT NULL,
    decided_by              TEXT,
    decided_by_type         TEXT,
    decided_at              TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL,
    status_changed_at       TIMESTAMPTZ NOT NULL,
    correlation_id          TEXT        NOT NULL,

    CONSTRAINT resolution_pk PRIMARY KEY (id),
    CONSTRAINT resolution_break_fk FOREIGN KEY (break_id)
        REFERENCES reconciliation.break (id),
    CONSTRAINT resolution_decision_fk FOREIGN KEY (decision_id)
        REFERENCES reconciliation.match_decision (id),
    CONSTRAINT resolution_park_fk FOREIGN KEY (park_id)
        REFERENCES reconciliation.park (id),
    CONSTRAINT resolution_offset_item_fk FOREIGN KEY (offset_item_id)
        REFERENCES reconciliation.suspense_item (id),
    CONSTRAINT resolution_expectation_fk FOREIGN KEY (chosen_expectation_id)
        REFERENCES reconciliation.expectation (id),
    CONSTRAINT resolution_rule_set_fk FOREIGN KEY (rule_set_id)
        REFERENCES reconciliation.rule_set (id),
    -- The produced subset (the plan's V006 slice; V007 regenerates from the enums).
    CONSTRAINT resolution_kind CHECK (kind IN ('EVIDENCED')),
    CONSTRAINT resolution_status CHECK (status IN ('APPROVED')),
    CONSTRAINT resolution_reason_code CHECK (reason_code IN ('EVIDENCE_RECEIVED')),
    -- EVIDENCED is the platform's alone, born decided (ADR-0071 section 2), for every
    -- writer: a person as proposer, or an undecided row, is refused at this rank.
    CONSTRAINT resolution_evidenced_is_platform CHECK (
        kind <> 'EVIDENCED' OR (proposed_by_type = 'SYSTEM' AND status = 'APPROVED'
            AND NOT four_eyes)),
    -- Distinctness at the database rank (INV-REC-03), stated now, vacuous until a
    -- four-eyes kind is admitted.
    CONSTRAINT resolution_four_eyes_distinct CHECK (
        status <> 'APPROVED' OR NOT four_eyes OR decided_by <> proposed_by),
    CONSTRAINT resolution_decided_iff_terminal CHECK (
        (status = 'PROPOSED') = (decided_by IS NULL)),
    CONSTRAINT resolution_narrative_bounded CHECK (
        char_length(narrative) BETWEEN 1 AND 1000),
    -- The narrative never carries a counterparty's account number or a PAN
    -- (INV-PAY-02, INV-RAIL-03 - the break note's screens, at the same rank).
    CONSTRAINT resolution_narrative_no_pan CHECK (
        NOT reconciliation.holds_luhn_valid_digit_run(narrative)),
    CONSTRAINT resolution_narrative_no_iban CHECK (
        narrative !~ '\m[A-Za-z]{2}[0-9]{2}[A-Za-z0-9]{11,30}\M'),
    CONSTRAINT resolution_amount_counted CHECK (proposed_amount_minor >= 0),
    CONSTRAINT resolution_currency_shape CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT resolution_scale_bounded CHECK (scale BETWEEN 0 AND 9),
    CONSTRAINT resolution_residual_counted CHECK (residual_version >= 0),
    CONSTRAINT resolution_proposal_once UNIQUE (adjustment_proposal_id),
    CONSTRAINT resolution_entry_once UNIQUE (journal_entry_id)
);

-- One live proposal per subject (ADR-0071 section 2) - stated now, inert until V007
-- admits PROPOSED.
CREATE UNIQUE INDEX resolution_one_proposed_per_break
    ON reconciliation.resolution (break_id)
    WHERE status = 'PROPOSED';

CREATE INDEX resolution_by_break ON reconciliation.resolution (break_id);

COMMENT ON TABLE reconciliation.resolution IS
    'How a break closed (ADR-0071): template-bound, reason-coded, four-eyes where value moves; EVIDENCED is the platform''s alone, born APPROVED, naming the decision and the park that explained the break.';

CREATE TABLE reconciliation.resolution_event (
    seq            BIGINT      GENERATED ALWAYS AS IDENTITY,
    resolution_id  UUID        NOT NULL,
    from_status    TEXT,
    to_status      TEXT        NOT NULL,
    actor          TEXT        NOT NULL,
    actor_type     TEXT        NOT NULL,
    reason         TEXT,
    occurred_at    TIMESTAMPTZ NOT NULL,
    correlation_id TEXT        NOT NULL,

    CONSTRAINT resolution_event_pk PRIMARY KEY (seq),
    CONSTRAINT resolution_event_fk FOREIGN KEY (resolution_id)
        REFERENCES reconciliation.resolution (id)
);

CREATE INDEX resolution_event_by_resolution
    ON reconciliation.resolution_event (resolution_id, seq);

-- Append-only for EVERY writer, the migrator included; V007 narrows the resolution's
-- UPDATE to the machine's own columns when the person edges arrive.
CREATE OR REPLACE FUNCTION reconciliation.resolution_is_frozen()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'a resolution is decided history until V007 brings the person machine - append-only for every writer (INV-REC-02, ADR-0071)';
END;
$$;

CREATE TRIGGER resolution_is_frozen
    BEFORE UPDATE OR DELETE ON reconciliation.resolution
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.resolution_is_frozen();

CREATE OR REPLACE FUNCTION reconciliation.resolution_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'a resolution''s history is append-only for every writer (INV-REC-02)';
END;
$$;

CREATE TRIGGER resolution_event_is_append_only
    BEFORE UPDATE OR DELETE ON reconciliation.resolution_event
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.resolution_event_is_append_only();

GRANT SELECT, INSERT ON reconciliation.resolution TO finapp_app;
GRANT SELECT, INSERT ON reconciliation.resolution_event TO finapp_app;
