-- P8-TSK-023: the repudiated batch (ADR-0065 section 10, ADR-0070 section 10, ADR-0071; INV-REV-01,
-- INV-AUD-04, INV-REC-07, INV-REC-09).
--
-- WHY NOW
--   Each state arrives with its producer (the lifecycle document section 5.11). The approved
--   REPUDIATE_BATCH resolution is the producer of everything below: the kind and its batch
--   subject, the counter-allocation that restores an expectation's remainder append-only, the
--   item's two repudiation-only edges, the expectation's reopening recorded, the suspense item's
--   fourth opener, and the record of each break the repudiation closed.
--
-- WHAT THE DESIGN FOUND (P8-TSK-023's design, 2026-10-01)
--   allocation.reverses_allocation_id existed with no unique and no writer: a counter-allocation
--   is now bound to its original - once, the same item, expectation, amount and shape - for
--   every writer. resolution had no batch subject at all (break_id NOT NULL). The item machine
--   already carried MATCHED -> UNMATCHED and every edge to REPUDIATED; it lacked the two the
--   design decided: an over-paying bank item reopened whole (PARKED -> UNMATCHED, its excess
--   unparked) and an item a resolution already closed still leaving the live evidence when its
--   batch is repudiated (RESOLVED -> REPUDIATED). The expectation machine already admitted its
--   reopening edges.

-- ---------------------------------------------------------------------------------------------
-- The resolution: the kind, its reason, and the batch subject - exactly one subject.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE reconciliation.resolution
    ALTER COLUMN break_id DROP NOT NULL,
    ADD COLUMN settlement_batch_id UUID,
    ADD COLUMN subject_digest BYTEA;

ALTER TABLE reconciliation.resolution
    -- ResolutionKind.sqlValueList(): every kind, REPUDIATE_BATCH now among them.
    DROP CONSTRAINT resolution_kind,
    ADD CONSTRAINT resolution_kind CHECK (kind IN (
        'EVIDENCED', 'ACKNOWLEDGE', 'WRITE_OFF', 'TRANSFER_TO_ACCOUNT', 'OFFSET_SUSPENSE', 'RECOGNISE_GAIN', 'MANUAL_MATCH', 'REPUDIATE_BATCH')),

    -- ResolutionReasonCode.sqlValueList(): EVIDENCE_REPUDIATED now among them.
    DROP CONSTRAINT resolution_reason_code,
    ADD CONSTRAINT resolution_reason_code CHECK (reason_code IN (
        'EVIDENCE_RECEIVED', 'COUNTERPARTY_ERROR_CONFIRMED', 'INTERNAL_PROCESSING_ERROR', 'DUPLICATE_BY_COUNTERPARTY', 'FUNDS_ATTRIBUTED', 'UNATTRIBUTABLE_AGED', 'TIMING_CONFIRMED', 'FEE_ACCEPTED_AS_CHARGED', 'FEE_RECOVERED', 'AMBIGUITY_RESOLVED_BY_EVIDENCE', 'IMMATERIAL_DIFFERENCE', 'LOSS_ACCEPTED', 'EVIDENCE_REPUDIATED')),

    -- ResolutionKind.sqlReasonPairingRule(every kind).
    DROP CONSTRAINT resolution_kind_reason_pairing,
    ADD CONSTRAINT resolution_kind_reason_pairing CHECK (
        (kind = 'EVIDENCED' AND reason_code IN ('EVIDENCE_RECEIVED')) OR (kind = 'ACKNOWLEDGE' AND reason_code IN ('COUNTERPARTY_ERROR_CONFIRMED', 'INTERNAL_PROCESSING_ERROR', 'TIMING_CONFIRMED', 'FEE_ACCEPTED_AS_CHARGED', 'FEE_RECOVERED', 'IMMATERIAL_DIFFERENCE')) OR (kind = 'WRITE_OFF' AND reason_code IN ('COUNTERPARTY_ERROR_CONFIRMED', 'INTERNAL_PROCESSING_ERROR', 'UNATTRIBUTABLE_AGED', 'IMMATERIAL_DIFFERENCE', 'LOSS_ACCEPTED')) OR (kind = 'TRANSFER_TO_ACCOUNT' AND reason_code IN ('COUNTERPARTY_ERROR_CONFIRMED', 'INTERNAL_PROCESSING_ERROR', 'FUNDS_ATTRIBUTED')) OR (kind = 'OFFSET_SUSPENSE' AND reason_code IN ('COUNTERPARTY_ERROR_CONFIRMED', 'INTERNAL_PROCESSING_ERROR', 'DUPLICATE_BY_COUNTERPARTY')) OR (kind = 'RECOGNISE_GAIN' AND reason_code IN ('UNATTRIBUTABLE_AGED')) OR (kind = 'MANUAL_MATCH' AND reason_code IN ('AMBIGUITY_RESOLVED_BY_EVIDENCE')) OR (kind = 'REPUDIATE_BATCH' AND reason_code IN ('EVIDENCE_REPUDIATED'))),

    -- Exactly one subject: a break, or - for a repudiation alone - a settlement batch
    -- (ADR-0071 section 1). The batch lives in settlement's schema: an identifier, no key
    -- across the boundary (ADR-0064).
    ADD CONSTRAINT resolution_one_subject CHECK (
        num_nonnulls(break_id, settlement_batch_id) = 1),
    ADD CONSTRAINT resolution_batch_subject_is_repudiation CHECK (
        (settlement_batch_id IS NOT NULL) = (kind = 'REPUDIATE_BATCH')),
    -- The plan the approver must find unchanged under the locks (the design's staleness rule:
    -- a batch has no residual_version of its own).
    ADD CONSTRAINT resolution_repudiation_carries_its_digest CHECK (
        (kind = 'REPUDIATE_BATCH') = (subject_digest IS NOT NULL)),
    ADD CONSTRAINT resolution_digest_shape CHECK (
        subject_digest IS NULL OR octet_length(subject_digest) = 32);

-- One live proposal per batch, and a batch repudiated once - for any writer.
CREATE UNIQUE INDEX resolution_one_proposed_per_batch
    ON reconciliation.resolution (settlement_batch_id)
    WHERE status = 'PROPOSED' AND settlement_batch_id IS NOT NULL;
CREATE UNIQUE INDEX resolution_batch_repudiated_once
    ON reconciliation.resolution (settlement_batch_id)
    WHERE status = 'APPROVED' AND settlement_batch_id IS NOT NULL;

-- The machine re-stated (V007's function): the new subject columns are frozen with the proposal.
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
            OR NEW.settlement_batch_id IS DISTINCT FROM OLD.settlement_batch_id
            OR NEW.subject_digest IS DISTINCT FROM OLD.subject_digest
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

-- ---------------------------------------------------------------------------------------------
-- The breaks a repudiation closed: one row per break, naming the resolution that emptied its
-- subject - append-only, never deleted. A batch-subject resolution has no break of its own, so
-- this is the trail from each closed break back to the repudiation.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE reconciliation.repudiation_closure (
    break_id       UUID        NOT NULL,
    resolution_id  UUID        NOT NULL,
    closed_at      TIMESTAMPTZ NOT NULL,
    correlation_id TEXT        NOT NULL,

    CONSTRAINT repudiation_closure_pk PRIMARY KEY (break_id),
    CONSTRAINT repudiation_closure_break_fk FOREIGN KEY (break_id)
        REFERENCES reconciliation.break (id),
    CONSTRAINT repudiation_closure_resolution_fk FOREIGN KEY (resolution_id)
        REFERENCES reconciliation.resolution (id)
);

CREATE INDEX repudiation_closure_by_resolution
    ON reconciliation.repudiation_closure (resolution_id);

CREATE OR REPLACE FUNCTION reconciliation.repudiation_closure_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'a repudiation''s closure is append-only (INV-REC-02)';
END;
$$;

CREATE TRIGGER repudiation_closure_is_append_only
    BEFORE UPDATE OR DELETE ON reconciliation.repudiation_closure
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.repudiation_closure_is_append_only();

-- A closure names a repudiation, and the break it names is already RESOLVED: the approval
-- resolves the break, then records why. (The resolution itself is still PROPOSED at that moment -
-- its APPROVED edge is the transaction's last write - so the status is not judged here.)
CREATE OR REPLACE FUNCTION reconciliation.repudiation_closure_names_a_repudiation()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
            SELECT 1 FROM reconciliation.resolution r
             WHERE r.id = NEW.resolution_id AND r.kind = 'REPUDIATE_BATCH') THEN
        RAISE EXCEPTION 'a repudiation closure names a REPUDIATE_BATCH resolution (P8-TSK-023)';
    END IF;
    IF NOT EXISTS (
            SELECT 1 FROM reconciliation.break b
             WHERE b.id = NEW.break_id AND b.status = 'RESOLVED') THEN
        RAISE EXCEPTION 'a repudiation closure records a break it RESOLVED (P8-TSK-023)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER repudiation_closure_names_a_repudiation
    BEFORE INSERT ON reconciliation.repudiation_closure
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.repudiation_closure_names_a_repudiation();

-- ---------------------------------------------------------------------------------------------
-- The counter-allocation: append-only, once per original, its exact mirror - for every writer.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE reconciliation.allocation
    ADD CONSTRAINT allocation_reversed_once UNIQUE (reverses_allocation_id);

CREATE OR REPLACE FUNCTION reconciliation.allocation_counter_mirrors_its_original()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    original reconciliation.allocation%ROWTYPE;
BEGIN
    IF NEW.reverses_allocation_id IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT * INTO original FROM reconciliation.allocation WHERE id = NEW.reverses_allocation_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'a counter-allocation names an existing allocation (INV-REV-01)';
    END IF;
    IF original.reverses_allocation_id IS NOT NULL THEN
        RAISE EXCEPTION 'a counter-allocation is never itself countered: the original stands reversed (INV-REV-01)';
    END IF;
    IF NEW.external_item_id IS DISTINCT FROM original.external_item_id
            OR NEW.expectation_id IS DISTINCT FROM original.expectation_id
            OR NEW.amount_minor IS DISTINCT FROM original.amount_minor
            OR NEW.currency IS DISTINCT FROM original.currency
            OR NEW.scale IS DISTINCT FROM original.scale THEN
        RAISE EXCEPTION 'a counter-allocation mirrors its original exactly: the same item, expectation, amount and shape (INV-REV-01, INV-REC-07)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER allocation_counter_mirrors_its_original
    BEFORE INSERT ON reconciliation.allocation
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.allocation_counter_mirrors_its_original();

-- ---------------------------------------------------------------------------------------------
-- The item: V009's function re-stated with ItemStatus.sqlTransitionRule() - the over-paying
-- bank item reopened whole (PARKED -> UNMATCHED) and a resolved item of the batch repudiated
-- (RESOLVED -> REPUDIATED), both the approved repudiation's alone (the domain's rank).
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION reconciliation.external_item_moves_only_on_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    run_cycle TEXT;
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
            OR NEW.attributed_source_id IS DISTINCT FROM OLD.attributed_source_id
            OR NEW.business_date IS DISTINCT FROM OLD.business_date
            OR NEW.settlement_date IS DISTINCT FROM OLD.settlement_date
            OR NEW.value_date IS DISTINCT FROM OLD.value_date
            OR NEW.canonical_fingerprint IS DISTINCT FROM OLD.canonical_fingerprint
            OR NEW.created_at IS DISTINCT FROM OLD.created_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'an external item''s copied line is frozen (ADR-0064, INV-HIST-01''s discipline): a corrected line is a NEW line in a later batch';
    END IF;
    IF NEW.learned_cycle IS DISTINCT FROM OLD.learned_cycle THEN
        IF OLD.learned_cycle IS NOT NULL THEN
            RAISE EXCEPTION 'a learned cycle is recorded once (P8-TSK-017): it never moves again';
        END IF;
        SELECT settlement_cycle INTO run_cycle
            FROM reconciliation.reconciliation_batch WHERE id = NEW.run_id;
        IF run_cycle IS NULL OR NEW.learned_cycle IS DISTINCT FROM run_cycle THEN
            RAISE EXCEPTION 'a learned cycle is the item''s own report''s cycle (P8-TSK-017)';
        END IF;
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status
            AND NOT ((OLD.status = 'PENDING' AND NEW.status IN ('MATCHED', 'CHECKED', 'OFFSET', 'UNMATCHED', 'PARKED')) OR (OLD.status = 'MATCHED' AND NEW.status IN ('UNMATCHED', 'REPUDIATED')) OR (OLD.status = 'CHECKED' AND NEW.status IN ('REPUDIATED')) OR (OLD.status = 'OFFSET' AND NEW.status IN ('REPUDIATED')) OR (OLD.status = 'UNMATCHED' AND NEW.status IN ('MATCHED', 'PARKED', 'REPUDIATED')) OR (OLD.status = 'PARKED' AND NEW.status IN ('MATCHED', 'UNMATCHED', 'RESOLVED', 'REPUDIATED')) OR (OLD.status = 'RESOLVED' AND NEW.status IN ('REPUDIATED'))) THEN
        RAISE EXCEPTION 'not an external item edge: % -> % (section 5.4)',
            OLD.status, NEW.status;
    END IF;
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The expectation's history gains its reopening: a repudiation's counter-allocation restored
-- the remainder (SETTLED | PARTIALLY_SETTLED -> OPEN | PARTIALLY_SETTLED, V002's edges).
-- ---------------------------------------------------------------------------------------------
ALTER TABLE reconciliation.expectation_event
    DROP CONSTRAINT expectation_event_type,
    ADD CONSTRAINT expectation_event_type CHECK (event_type IN (
        'OPENED', 'KEY_COLLISION', 'ALLOCATED', 'RESOLVED', 'REOPENED'));

-- ---------------------------------------------------------------------------------------------
-- The suspense item's fourth opener (ADR-0070 sections 2 and 10): a repudiation answering a
-- value a resolution already released - no external item, no park, no position; the line it
-- carries is the reversal's or the inverse posting's, and its origin_ref the released item.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE reconciliation.suspense_item
    -- SuspenseOrigin.sqlValueList().
    DROP CONSTRAINT suspense_item_origin,
    ADD CONSTRAINT suspense_item_origin CHECK (origin IN (
        'RECON_PARK', 'BANK_UNATTRIBUTED', 'UNMATCHED_CONFIRMATION', 'REPUDIATION')),
    ADD CONSTRAINT suspense_item_repudiation_shape CHECK (
        origin <> 'REPUDIATION'
            OR (external_item_id IS NULL AND park_id IS NULL AND position_account_id IS NULL
                AND entry_id IS NOT NULL));

-- ---------------------------------------------------------------------------------------------
-- Grants: the closure is written by the approval; nothing else changes its privileges.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON reconciliation.repudiation_closure TO finapp_app;
