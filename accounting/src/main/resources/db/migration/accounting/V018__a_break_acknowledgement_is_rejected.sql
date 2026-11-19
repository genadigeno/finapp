-- finapp-contract: first-conforming-release=ANY; waives=DROP,CHECK,REPLACED_BODY; reason=each replaced check admits every event the old one did and a rejection besides, and the replaced body every edge the old one did and a rejection's besides, so no row a release has written and no write a release makes is refused
--
-- An acknowledgement proposal's rejection (P17-TSK-111 item 5; finapp-admin-panel docs/FINAPP_BACKEND_WORK.md B5.6): a proposed
-- acknowledgement of an accounting break could be approved but never rejected, so a proposal its approver would not accept stood
-- until the break was resolved. A holder of ACCOUNTING_ADJUST_APPROVE now rejects it, reasoned: the break stays OPEN, the
-- proposal is cleared from its row, and a new one may be proposed.
--
-- WHAT IT CHANGES
--   accounting.break_event's two checks that name the acts, each replaced by one admitting ACKNOWLEDGEMENT_REJECTED as well:
--   an OPEN -> OPEN act naming the proposer it rejected (proposed_by), reasoned by the existing break_event_reasoned. Four eyes
--   stays the approval's alone: a rejection may be its own proposer's, as a rate set's, a mapping version's and a prior-period
--   adjustment's may (the summary says so). And accounting.accounting_break_permits_only_machine_edges, V016's definition with
--   one edge added: a pending, unapproved proposal cleared whole on an OPEN break, nothing else moving. Every other edge and
--   refusal is V016's byte for byte. No column, no row, no grant: finapp_app already holds UPDATE on the four columns cleared.
--
-- NOT VALID, AND WHY NOTHING IS LOST
--   Each new check is enforced on every insert from this migration on and judges no existing row: every one passed the old check,
--   which the new one contains, so none can fail it - and a check judged over old rows is the lint's TIGHTENED_OVER_OLD_ROWS
--   (DATA_MIGRATIONS.md section 4.1). A later release may VALIDATE them under SHARE UPDATE EXCLUSIVE.
--
-- THE CASE FILE KEEPS WHAT THE ROW LETS GO
--   The row holds the pending proposal only. The proposal's event (ACKNOWLEDGEMENT_PROPOSED, its disclosure the reason) and the
--   rejection's (ACKNOWLEDGEMENT_REJECTED, the rejecter's reason, the proposer named) are appended in the rejection's own
--   transaction, and break_event refuses UPDATE and DELETE: nothing proposed is lost from the record.
--
-- LOCKS
--   A brief ACCESS EXCLUSIVE on accounting.break_event for each catalogue change, no scan; CREATE OR REPLACE keeps the function's
--   OID, so its trigger stays bound. In the migration's transaction.

ALTER TABLE accounting.break_event
    DROP CONSTRAINT break_event_act_is_an_edge;

ALTER TABLE accounting.break_event
    ADD CONSTRAINT break_event_act_is_an_edge CHECK (
        (act = 'RAISED' AND from_status IS NULL AND to_status = 'OPEN')
        OR (act = 'ACKNOWLEDGEMENT_PROPOSED' AND from_status = 'OPEN' AND to_status = 'OPEN')
        OR (act = 'ACKNOWLEDGEMENT_REJECTED' AND from_status = 'OPEN' AND to_status = 'OPEN')
        OR (act = 'ACKNOWLEDGED' AND from_status = 'OPEN' AND to_status = 'ACKNOWLEDGED')
        OR (act = 'RESOLVED' AND from_status IN ('OPEN', 'ACKNOWLEDGED') AND to_status = 'RESOLVED')) NOT VALID;

ALTER TABLE accounting.break_event
    DROP CONSTRAINT break_event_four_eyes;

-- The acknowledgement and its rejection name the proposer; only the acknowledgement is another person's.
ALTER TABLE accounting.break_event
    ADD CONSTRAINT break_event_four_eyes CHECK (
        (act IN ('ACKNOWLEDGED', 'ACKNOWLEDGEMENT_REJECTED')) = (proposed_by IS NOT NULL)
        AND (act <> 'ACKNOWLEDGED' OR proposed_by <> actor)) NOT VALID;

-- accounting.accounting_break_permits_only_machine_edges: V016's definition, the rejection's edge added.
CREATE OR REPLACE FUNCTION accounting.accounting_break_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'OPEN' OR NEW.ack_proposed_by IS NOT NULL OR NEW.ack_approved_by IS NOT NULL
                OR NEW.resolved_by IS NOT NULL THEN
            RAISE EXCEPTION 'an accounting break is born OPEN with nothing acted (P14-TSK-010)';
        END IF;
        -- A LATER GENERATION (P14-TSK-012, ADR-0117): only over the generation before it, decided, and reading differently. A racer that
        -- lost passes this and meets the unique's ON CONFLICT DO NOTHING - a loser writes nothing, it is not refused.
        IF NEW.generation > 1 AND NOT EXISTS (
                SELECT 1 FROM accounting.accounting_break earlier
                 WHERE earlier.period_start = NEW.period_start AND earlier.check_code = NEW.check_code
                   AND earlier.subject_ref = NEW.subject_ref AND earlier.generation = NEW.generation - 1
                   AND earlier.status IN ('ACKNOWLEDGED', 'RESOLVED') AND earlier.evidence <> NEW.evidence) THEN
            RAISE EXCEPTION 'a later generation of a break follows a decided one and reads differently (P14-TSK-012, ADR-0117)';
        END IF;
        NEW.raised_at := pg_catalog.clock_timestamp();
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'an accounting break is never deleted (P14-TSK-010, INV-ACC-12)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.period_start IS DISTINCT FROM OLD.period_start
            OR NEW.check_code IS DISTINCT FROM OLD.check_code OR NEW.break_type IS DISTINCT FROM OLD.break_type
            OR NEW.subject_ref IS DISTINCT FROM OLD.subject_ref OR NEW.generation IS DISTINCT FROM OLD.generation
            OR NEW.evidence IS DISTINCT FROM OLD.evidence
            OR NEW.judged_by IS DISTINCT FROM OLD.judged_by OR NEW.raised_at IS DISTINCT FROM OLD.raised_at
            OR NEW.raised_by IS DISTINCT FROM OLD.raised_by OR NEW.raised_by_type IS DISTINCT FROM OLD.raised_by_type
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'an accounting break''s finding is frozen from its birth (P14-TSK-010, INV-ACC-12)';
    END IF;
    IF OLD.status = 'RESOLVED' THEN
        RAISE EXCEPTION 'a RESOLVED accounting break is final (P14-TSK-010)';
    END IF;
    IF OLD.ack_proposed_by IS NOT NULL AND (NEW.ack_proposed_by IS DISTINCT FROM OLD.ack_proposed_by
            OR NEW.ack_proposed_by_type IS DISTINCT FROM OLD.ack_proposed_by_type
            OR NEW.ack_proposed_at IS DISTINCT FROM OLD.ack_proposed_at
            OR NEW.disclosure IS DISTINCT FROM OLD.disclosure) THEN
        -- THE REJECTION (P17-TSK-111): the one way a proposal changes once made - cleared whole, on an OPEN break no one
        -- approved, the break staying OPEN and nothing else moving, so a new proposal may follow. What was proposed, by whom
        -- and why it was rejected stay in the case file (break_event), which is append-only.
        IF OLD.status = 'OPEN' AND NEW.status = 'OPEN' AND OLD.ack_approved_by IS NULL
                AND NEW.ack_proposed_by IS NULL AND NEW.ack_proposed_by_type IS NULL AND NEW.ack_proposed_at IS NULL
                AND NEW.disclosure IS NULL AND NEW.ack_approved_by IS NULL AND NEW.resolved_by IS NULL THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'an acknowledgement proposal is frozen once made, but for its rejection, which clears it whole (P14-TSK-010, P17-TSK-111)';
    END IF;
    IF OLD.ack_approved_by IS NOT NULL AND (NEW.ack_approved_by IS DISTINCT FROM OLD.ack_approved_by
            OR NEW.ack_approved_by_type IS DISTINCT FROM OLD.ack_approved_by_type
            OR NEW.ack_approved_at IS DISTINCT FROM OLD.ack_approved_at
            OR NEW.ack_approval_reason IS DISTINCT FROM OLD.ack_approval_reason) THEN
        RAISE EXCEPTION 'an acknowledgement is frozen once approved (P14-TSK-010)';
    END IF;
    IF OLD.status = 'OPEN' AND NEW.status = 'OPEN' THEN
        -- The acknowledgement proposal: recorded once on an OPEN break, nothing else moves.
        IF OLD.ack_proposed_by IS NOT NULL OR NEW.ack_proposed_by IS NULL OR NEW.ack_approved_by IS NOT NULL
                OR NEW.resolved_by IS NOT NULL THEN
            RAISE EXCEPTION 'an OPEN accounting break records one acknowledgement proposal and nothing else (P14-TSK-010)';
        END IF;
        NEW.ack_proposed_at := pg_catalog.clock_timestamp();
        RETURN NEW;
    END IF;
    IF OLD.status = 'OPEN' AND NEW.status = 'ACKNOWLEDGED' THEN
        IF OLD.ack_proposed_by IS NULL OR NEW.ack_approved_by IS NULL OR NEW.resolved_by IS NOT NULL THEN
            RAISE EXCEPTION 'a break is acknowledged only by the approval of its pending proposal (P14-TSK-010, INV-AUD-04)';
        END IF;
        NEW.ack_approved_at := pg_catalog.clock_timestamp();
        RETURN NEW;
    END IF;
    IF OLD.status IN ('OPEN', 'ACKNOWLEDGED') AND NEW.status = 'RESOLVED' THEN
        IF NEW.resolved_by IS NULL OR NEW.resolving_entry_id IS NULL
                OR NEW.ack_approved_by IS DISTINCT FROM OLD.ack_approved_by
                OR (OLD.ack_proposed_by IS NULL AND NEW.ack_proposed_by IS NOT NULL) THEN
            RAISE EXCEPTION 'a break is resolved only by reference to its correcting entry, and nothing else moves (P14-TSK-010)';
        END IF;
        NEW.resolved_at := pg_catalog.clock_timestamp();
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'an accounting break moves OPEN -> ACKNOWLEDGED (four eyes), OPEN | ACKNOWLEDGED -> RESOLVED; % -> % is not an edge (P14-TSK-010)', OLD.status, NEW.status;
END;
$$;
