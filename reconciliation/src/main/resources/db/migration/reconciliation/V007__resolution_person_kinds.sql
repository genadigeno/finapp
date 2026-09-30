-- P8-TSK-015: the person's resolution kinds and their machine (ADR-0071 sections 1-9;
-- INV-REC-03, INV-AUD-04, INV-REV-04).
--
-- V006 birthed reconciliation.resolution with the platform's EVIDENCED only - a narrowed
-- rank, not a narrowed vocabulary. This migration REGENERATES its CHECKs from the enums for
-- the person kinds (ResolutionKind.admittedByV007(): every kind but REPUDIATE_BATCH, whose
-- batch subject is V009's, P8-TSK-023) and the PROPOSED machine, adds the generated
-- (kind, reason code) pairing and the derived four-eyes flag, and replaces V006's blanket
-- freeze with the machine's own every-writer transition trigger and a narrowed UPDATE grant.
-- ReconciliationV007MigrationTest reconciles every generated fragment with its enum.
--
-- It also carries P8-TSK-014's recorded design input on the break: a type moves only while
-- the break is OPEN or INVESTIGATING (a proposal's frozen lines depend on the type), and a
-- break that owns unreleased suspense moves only onto a type that may own it (INV-REC-09's
-- owner half at the database rank; the reverse half - a non-owner moving onto a parking type
-- on a parked subject - stays the domain's, BreakCaseFile's parking rule).

-- ---------------------------------------------------------------------------------------------
-- The regenerated vocabulary (ResolutionKind / ResolutionStatus / ResolutionReasonCode).
-- ---------------------------------------------------------------------------------------------
ALTER TABLE reconciliation.resolution
    DROP CONSTRAINT resolution_kind,
    ADD CONSTRAINT resolution_kind CHECK (kind IN (
        'EVIDENCED', 'ACKNOWLEDGE', 'WRITE_OFF', 'TRANSFER_TO_ACCOUNT', 'OFFSET_SUSPENSE', 'RECOGNISE_GAIN', 'MANUAL_MATCH')),

    DROP CONSTRAINT resolution_status,
    ADD CONSTRAINT resolution_status CHECK (status IN (
        'PROPOSED', 'APPROVED', 'REJECTED', 'WITHDRAWN')),

    DROP CONSTRAINT resolution_reason_code,
    ADD CONSTRAINT resolution_reason_code CHECK (reason_code IN (
        'EVIDENCE_RECEIVED', 'COUNTERPARTY_ERROR_CONFIRMED', 'INTERNAL_PROCESSING_ERROR', 'DUPLICATE_BY_COUNTERPARTY', 'FUNDS_ATTRIBUTED', 'UNATTRIBUTABLE_AGED', 'TIMING_CONFIRMED', 'FEE_ACCEPTED_AS_CHARGED', 'FEE_RECOVERED', 'AMBIGUITY_RESOLVED_BY_EVIDENCE', 'IMMATERIAL_DIFFERENCE', 'LOSS_ACCEPTED')),

    -- ResolutionKind.sqlReasonPairingRule: each kind's admitted codes (ADR-0071 section 5).
    ADD CONSTRAINT resolution_kind_reason_pairing CHECK (
        (kind = 'EVIDENCED' AND reason_code IN ('EVIDENCE_RECEIVED')) OR (kind = 'ACKNOWLEDGE' AND reason_code IN ('COUNTERPARTY_ERROR_CONFIRMED', 'INTERNAL_PROCESSING_ERROR', 'TIMING_CONFIRMED', 'FEE_ACCEPTED_AS_CHARGED', 'FEE_RECOVERED', 'IMMATERIAL_DIFFERENCE')) OR (kind = 'WRITE_OFF' AND reason_code IN ('COUNTERPARTY_ERROR_CONFIRMED', 'INTERNAL_PROCESSING_ERROR', 'UNATTRIBUTABLE_AGED', 'IMMATERIAL_DIFFERENCE', 'LOSS_ACCEPTED')) OR (kind = 'TRANSFER_TO_ACCOUNT' AND reason_code IN ('COUNTERPARTY_ERROR_CONFIRMED', 'INTERNAL_PROCESSING_ERROR', 'FUNDS_ATTRIBUTED')) OR (kind = 'OFFSET_SUSPENSE' AND reason_code IN ('COUNTERPARTY_ERROR_CONFIRMED', 'INTERNAL_PROCESSING_ERROR', 'DUPLICATE_BY_COUNTERPARTY')) OR (kind = 'RECOGNISE_GAIN' AND reason_code IN ('UNATTRIBUTABLE_AGED')) OR (kind = 'MANUAL_MATCH' AND reason_code IN ('AMBIGUITY_RESOLVED_BY_EVIDENCE'))),

    -- Four-eyes is DERIVED, never chosen (ADR-0071 section 3): a raw writer cannot turn off
    -- the second person by clearing a flag.
    ADD CONSTRAINT resolution_four_eyes_derived CHECK (
        four_eyes = (kind <> 'EVIDENCED' AND NOT (kind = 'ACKNOWLEDGE' AND proposed_amount_minor = 0))),

    -- The resolution, its ledger proposal and its entry are one-to-one (ADR-0071 section 6):
    -- a posting kind always names its proposal, and an APPROVED one its entry; no other kind
    -- names a proposal.
    ADD CONSTRAINT resolution_posting_kind_names_proposal CHECK (
        (kind IN ('WRITE_OFF', 'TRANSFER_TO_ACCOUNT', 'RECOGNISE_GAIN'))
            = (adjustment_proposal_id IS NOT NULL)),
    ADD CONSTRAINT resolution_approved_posting_names_entry CHECK (
        status <> 'APPROVED'
            OR kind NOT IN ('WRITE_OFF', 'TRANSFER_TO_ACCOUNT', 'RECOGNISE_GAIN')
            OR journal_entry_id IS NOT NULL),

    -- The per-kind operands, exactly where the template needs them.
    ADD CONSTRAINT resolution_transfer_names_target CHECK (
        (kind = 'TRANSFER_TO_ACCOUNT') = (target_account_id IS NOT NULL)),
    ADD CONSTRAINT resolution_manual_match_names_candidate CHECK (
        (kind = 'MANUAL_MATCH') = (chosen_expectation_id IS NOT NULL)),
    ADD CONSTRAINT resolution_offset_names_item CHECK (
        kind <> 'OFFSET_SUSPENSE' OR offset_item_id IS NOT NULL);

-- ---------------------------------------------------------------------------------------------
-- The machine, for every writer: V006's blanket freeze gives way to the transition trigger.
-- ---------------------------------------------------------------------------------------------
DROP TRIGGER resolution_is_frozen ON reconciliation.resolution;
DROP FUNCTION reconciliation.resolution_is_frozen();

CREATE OR REPLACE FUNCTION reconciliation.resolution_moves_only_on_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a resolution is never deleted - withdrawal is a WITHDRAWN row (INV-REC-02, ADR-0071)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.break_id IS DISTINCT FROM OLD.break_id
            OR NEW.kind IS DISTINCT FROM OLD.kind
            OR NEW.reason_code IS DISTINCT FROM OLD.reason_code
            OR NEW.narrative IS DISTINCT FROM OLD.narrative
            OR NEW.four_eyes IS DISTINCT FROM OLD.four_eyes
            OR NEW.proposed_amount_minor IS DISTINCT FROM OLD.proposed_amount_minor
            OR NEW.currency IS DISTINCT FROM OLD.currency
            OR NEW.scale IS DISTINCT FROM OLD.scale
            OR NEW.residual_version IS DISTINCT FROM OLD.residual_version
            OR NEW.target_account_id IS DISTINCT FROM OLD.target_account_id
            OR NEW.offset_item_id IS DISTINCT FROM OLD.offset_item_id
            OR NEW.chosen_expectation_id IS DISTINCT FROM OLD.chosen_expectation_id
            OR NEW.rule_set_id IS DISTINCT FROM OLD.rule_set_id
            OR NEW.adjustment_proposal_id IS DISTINCT FROM OLD.adjustment_proposal_id
            OR NEW.proposed_by IS DISTINCT FROM OLD.proposed_by
            OR NEW.proposed_by_type IS DISTINCT FROM OLD.proposed_by_type
            OR NEW.proposed_at IS DISTINCT FROM OLD.proposed_at
            OR NEW.created_at IS DISTINCT FROM OLD.created_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a resolution''s proposal is frozen when proposed: the approver approves what was proposed (ADR-0071 section 8)';
    END IF;
    -- ResolutionStatus.sqlTransitionRule: a terminal row takes no write at all.
    IF NOT ((OLD.status = 'PROPOSED' AND NEW.status IN ('APPROVED', 'REJECTED', 'WITHDRAWN'))) THEN
        RAISE EXCEPTION 'not a resolution edge: % -> % (ADR-0071 section 1)', OLD.status, NEW.status;
    END IF;
    -- What the approval produced - its entry, and a manual match's decision and unpark -
    -- is named once, on the approval, and never moved.
    IF (NEW.journal_entry_id IS DISTINCT FROM OLD.journal_entry_id
                AND (OLD.journal_entry_id IS NOT NULL OR NEW.status <> 'APPROVED'))
            OR (NEW.decision_id IS DISTINCT FROM OLD.decision_id
                AND (OLD.decision_id IS NOT NULL OR NEW.status <> 'APPROVED'))
            OR (NEW.park_id IS DISTINCT FROM OLD.park_id
                AND (OLD.park_id IS NOT NULL OR NEW.status <> 'APPROVED')) THEN
        RAISE EXCEPTION 'a resolution names what its approval produced once, on the approval (ADR-0071 section 6)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER resolution_moves_only_on_machine_edges
    BEFORE UPDATE OR DELETE ON reconciliation.resolution
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.resolution_moves_only_on_machine_edges();

-- The decision columns, and what the approval produced - nothing else is writable.
GRANT UPDATE (status, decided_by, decided_by_type, decided_at, status_changed_at,
              journal_entry_id, decision_id, park_id)
    ON reconciliation.resolution TO finapp_app;

-- ---------------------------------------------------------------------------------------------
-- The expectation's history gains its resolution producer: an approved closing resolution
-- takes the remainder into resolved_minor (RESOLVED_BY_ADJUSTMENT) and says so.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE reconciliation.expectation_event
    DROP CONSTRAINT expectation_event_type,
    ADD CONSTRAINT expectation_event_type CHECK (event_type IN (
        'OPENED', 'KEY_COLLISION', 'ALLOCATED', 'RESOLVED'));

-- ---------------------------------------------------------------------------------------------
-- P8-TSK-014's design input: the break's type moves only while it is investigable, and a
-- suspense owner never moves onto a type that may not own suspense (INV-REC-09).
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION reconciliation.break_type_moves_only_while_investigable()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.type IS DISTINCT FROM OLD.type THEN
        IF OLD.status NOT IN ('OPEN', 'INVESTIGATING') THEN
            RAISE EXCEPTION 'a break is reclassified only while OPEN or INVESTIGATING: a proposal''s frozen lines depend on its type (ADR-0069 section 7)';
        END IF;
        -- BreakType.sqlSuspenseOwningList.
        IF NEW.type NOT IN ('MISSING_INTERNAL', 'UNKNOWN_EXTERNAL', 'AMOUNT_MISMATCH', 'CURRENCY_MISMATCH', 'DUPLICATE_EXTERNAL', 'AMBIGUOUS_MATCH', 'REVERSAL_MISMATCH', 'REFUND_MISMATCH', 'SETTLEMENT_MISMATCH', 'PROCESSING_ERROR')
                AND EXISTS (SELECT 1 FROM reconciliation.suspense_item s
                            WHERE s.break_id = NEW.id
                              AND s.status IN ('OPEN', 'PARTIALLY_RELEASED')
                              AND s.released_minor < s.amount_minor) THEN
            RAISE EXCEPTION 'a break owning unreleased suspense keeps a suspense-owning type (INV-REC-09): % may not own suspense', NEW.type;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER break_type_moves_only_while_investigable
    BEFORE UPDATE OF type ON reconciliation.break
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.break_type_moves_only_while_investigable();
