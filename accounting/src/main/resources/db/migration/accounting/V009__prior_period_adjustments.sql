-- Prior-period adjustments: accounting's one door into the journal (P14-TSK-013).
--
-- ADR-0113 decision 4, ADR-0114 decision 5, INV-ACC-10, INV-REV-04, INV-AUD-04; PHASE_14_PLAN.md sections 4.2, 8.1 T-ppa, 8.2, 8.3 row 12, 13.5.
-- An error found in a CLOSED month is corrected by a four-eyes adjustment dated today in a month still OPEN, naming the month and the revision of
-- the figures it corrects. The ledger's adjustment_proposal (ledger V030, origin ACCOUNTING) holds the lines and posts the entry; this row is what
-- ties them to the month, and it moves with the proposal in one transaction, through app's one implementation of PriorPeriodPostings.
--
-- THE MACHINE: PROPOSED -> POSTED (a second person; the entry commits in the same transaction); PROPOSED -> REJECTED (the way on for one that can
-- no longer post: it asks nothing of either month). Both final. A refused approval rolls back and leaves both rows PROPOSED.
--
-- THE TWO WORST FAILURES, AND WHAT HERE HOLDS THEM
--   A correction that changes what a closed month issued: the posting date lies after the corrected month's end and inside an OPEN month, for every
--   writer; the ledger's cutoff refuses the rest (V028). No row here is read by a report definition.
--   A correction that leaves its month unable to close: the domain refuses a purpose closed to free adjustments and one unmapped in the version in
--   force for the target, and the register carries the origin's rule. The ledger's trigger (V030) refuses the closed purposes for every writer.
--
-- NO FOREIGN KEY TO THE LEDGER: adjustment_proposal_id and journal_entry_id name the ledger's rows by identifier (accounting V001's rule; the
-- migration names no other schema). An entry of this origin with no POSTED row is reported by PriorPeriodCorrections.untraced.
--
-- THE LOCKS (DISTRIBUTED_EXECUTION.md section 3, the Phase 14 order): (A1) the corrected period FOR SHARE, then the target FOR SHARE, oldest first.
-- A lock of the target, a reopen of the corrected month and an approval here therefore serialise on the period row, and each sees what the other
-- committed. The trigger takes the same two rows in the same order, on rows the domain's path already holds; a raw writer holding neither takes them
-- here. A rejection takes no period row. The application role holds the column UPDATE that FOR SHARE needs (V003, V008).
--
-- NO MONEY: no column here holds an amount. Lines and amounts are the ledger's. Reasons are a person's, screened, never logged. No DELETE for any
-- role; never truncated.

CREATE TABLE accounting.prior_period_adjustment (
    id                     uuid        NOT NULL,
    corrected_period_id    uuid        NOT NULL,
    corrected_revision     integer     NOT NULL,
    target_period_id       uuid        NOT NULL,
    posting_date           date        NOT NULL,
    adjustment_proposal_id uuid        NOT NULL,
    journal_entry_id       uuid,
    status                 text        NOT NULL,
    proposed_by            text        NOT NULL,
    proposed_by_type       text        NOT NULL,
    proposed_at            timestamptz NOT NULL,
    proposal_reason        text        NOT NULL,
    decided_by             text,
    decided_by_type        text,
    decided_at             timestamptz,
    decision_reason        text,
    correlation_id         text        NOT NULL,
    CONSTRAINT prior_period_adjustment_pk PRIMARY KEY (id),
    CONSTRAINT prior_period_adjustment_corrected_fk FOREIGN KEY (corrected_period_id) REFERENCES accounting.accounting_period (id),
    CONSTRAINT prior_period_adjustment_target_fk FOREIGN KEY (target_period_id) REFERENCES accounting.accounting_period (id),
    -- One ledger proposal, one entry, each named by one row - the backstops beneath row 12.
    CONSTRAINT prior_period_adjustment_one_proposal UNIQUE (adjustment_proposal_id),
    CONSTRAINT prior_period_adjustment_one_entry UNIQUE (journal_entry_id),
    -- PriorPeriodAdjustmentStatus.
    CONSTRAINT prior_period_adjustment_status_is_known CHECK (status IN ('PROPOSED', 'POSTED', 'REJECTED')),
    CONSTRAINT prior_period_adjustment_revision_positive CHECK (corrected_revision >= 1),
    -- Persons only: an authenticated human proposes and decides.
    CONSTRAINT prior_period_adjustment_persons CHECK (
        proposed_by_type IN ('CUSTOMER', 'EMPLOYEE')
        AND (decided_by_type IS NULL OR decided_by_type IN ('CUSTOMER', 'EMPLOYEE'))),
    -- A decision is whole and reasoned exactly when the row is no longer PROPOSED; an entry exactly when POSTED.
    CONSTRAINT prior_period_adjustment_decision_coherent CHECK (
        (status = 'PROPOSED') = (decided_by IS NULL)
        AND (decided_by IS NULL) = (decided_by_type IS NULL)
        AND (decided_by IS NULL) = (decided_at IS NULL)
        AND (decided_by IS NULL) = (decision_reason IS NULL)),
    CONSTRAINT prior_period_adjustment_entry_coherent CHECK ((status = 'POSTED') = (journal_entry_id IS NOT NULL)),
    CONSTRAINT prior_period_adjustment_reasoned CHECK (
        char_length(proposal_reason) BETWEEN 1 AND 1000
        AND (decision_reason IS NULL OR char_length(decision_reason) BETWEEN 1 AND 1000)),
    -- FOUR EYES, for every writer (INV-AUD-04): whoever posted it did not propose it. A rejection may be the proposer's. The row is the record, so
    -- there is no history beside it to hold the same rule.
    CONSTRAINT prior_period_adjustment_four_eyes CHECK (status <> 'POSTED' OR decided_by <> proposed_by)
);

COMMENT ON TABLE accounting.prior_period_adjustment IS
    'A prior-period adjustment (P14-TSK-013, ADR-0114, INV-ACC-10): a correction of a CLOSED period at a named revision, dated in an OPEN one, its lines and entry the ledger''s. PROPOSED -> POSTED by a second person, in the transaction that posts the entry | REJECTED. Never deleted. No money.';
COMMENT ON COLUMN accounting.prior_period_adjustment.corrected_revision IS
    'The revision of the figures this corrects: a reopen moves the period''s revision, so an approval is refused once it has moved.';
COMMENT ON COLUMN accounting.prior_period_adjustment.posting_date IS
    'The entry''s date, both of them (ledger V030): the day of the proposal, stored, what the approver reads and names. Never in or before the corrected period.';

-- What a person reads of a month's corrections, and what an approval locks in id order.
CREATE INDEX prior_period_adjustment_of_corrected ON accounting.prior_period_adjustment (corrected_period_id, id);
-- What a controller finds standing.
CREATE INDEX prior_period_adjustment_pending ON accounting.prior_period_adjustment (proposed_at, id) WHERE status = 'PROPOSED';
-- What a month's statements disclose (PriorPeriodCorrections, P14-TSK-016).
CREATE INDEX prior_period_adjustment_posted_on ON accounting.prior_period_adjustment (posting_date, id) WHERE status = 'POSTED';

CREATE OR REPLACE FUNCTION accounting.prior_period_adjustment_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    held_corrected_status   text;
    held_corrected_revision integer;
    held_corrected_end      date;
    held_target_status      text;
    held_target_start       date;
    held_target_end         date;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a prior-period adjustment is never deleted (P14-TSK-013, INV-ACC-10)';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF NEW.id IS DISTINCT FROM OLD.id OR NEW.corrected_period_id IS DISTINCT FROM OLD.corrected_period_id
                OR NEW.corrected_revision IS DISTINCT FROM OLD.corrected_revision
                OR NEW.target_period_id IS DISTINCT FROM OLD.target_period_id
                OR NEW.posting_date IS DISTINCT FROM OLD.posting_date
                OR NEW.adjustment_proposal_id IS DISTINCT FROM OLD.adjustment_proposal_id
                OR NEW.proposed_by IS DISTINCT FROM OLD.proposed_by OR NEW.proposed_by_type IS DISTINCT FROM OLD.proposed_by_type
                OR NEW.proposed_at IS DISTINCT FROM OLD.proposed_at OR NEW.proposal_reason IS DISTINCT FROM OLD.proposal_reason
                OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
            RAISE EXCEPTION 'a prior-period adjustment is frozen but for its one decision: the approver approves what they read (P14-TSK-013, INV-AUD-04)';
        END IF;
        IF NOT (OLD.status = 'PROPOSED' AND NEW.status IN ('POSTED', 'REJECTED')) THEN
            RAISE EXCEPTION 'a prior-period adjustment moves PROPOSED -> POSTED | REJECTED, once; % -> % is not an edge (P14-TSK-013)', OLD.status, NEW.status;
        END IF;
        IF NEW.status = 'REJECTED' THEN
            -- The way on for one that can no longer post: it asks nothing of either month.
            NEW.decided_at := clock_timestamp();
            RETURN NEW;
        END IF;
    ELSE
        IF NEW.status <> 'PROPOSED' OR NEW.journal_entry_id IS NOT NULL OR NEW.decided_by IS NOT NULL OR NEW.decided_by_type IS NOT NULL
                OR NEW.decided_at IS NOT NULL OR NEW.decision_reason IS NOT NULL THEN
            RAISE EXCEPTION 'a prior-period adjustment is born PROPOSED and undecided (P14-TSK-013)';
        END IF;
    END IF;
    -- A birth and a POST, oldest first: the corrected month CLOSED at the revision named, the target OPEN - what can move between the two.
    SELECT p.status, p.revision, p.period_end
      INTO held_corrected_status, held_corrected_revision, held_corrected_end
      FROM accounting.accounting_period p WHERE p.id = NEW.corrected_period_id FOR SHARE;
    IF NOT FOUND OR held_corrected_status <> 'CLOSED' OR held_corrected_revision <> NEW.corrected_revision THEN
        RAISE EXCEPTION 'a prior-period adjustment corrects a CLOSED period at the revision it names (P14-TSK-013, INV-ACC-10)';
    END IF;
    SELECT p.status, p.period_start, p.period_end
      INTO held_target_status, held_target_start, held_target_end
      FROM accounting.accounting_period p WHERE p.id = NEW.target_period_id FOR SHARE;
    IF NOT FOUND OR held_target_status <> 'OPEN' THEN
        RAISE EXCEPTION 'a prior-period adjustment lands in an OPEN period (P14-TSK-013, INV-ACC-10)';
    END IF;
    -- NEVER IN OR BEFORE THE MONTH IT CORRECTS, AND NEVER SOMEWHERE ELSE THAN THE MONTH IT NAMES. Frozen, so a POST judges the same dates.
    IF held_target_start <= held_corrected_end OR NEW.posting_date < held_target_start OR NEW.posting_date > held_target_end THEN
        RAISE EXCEPTION 'a prior-period adjustment is dated inside its OPEN target, after the period it corrects (P14-TSK-013, INV-ACC-10)';
    END IF;
    IF TG_OP = 'INSERT' THEN
        -- NEVER THE FUTURE. A day's grace for an instance's clock across a midnight: the domain dates it, and the database only bounds that.
        IF NEW.posting_date > (now() AT TIME ZONE 'UTC')::date + 1 THEN
            RAISE EXCEPTION 'a prior-period adjustment is not dated in the future (P14-TSK-013, INV-ACC-10)';
        END IF;
        NEW.proposed_at := clock_timestamp();
    ELSE
        NEW.decided_at := clock_timestamp();
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER prior_period_adjustment_permits_only_machine_edges
    BEFORE INSERT OR UPDATE OR DELETE ON accounting.prior_period_adjustment
    FOR EACH ROW
    EXECUTE FUNCTION accounting.prior_period_adjustment_permits_only_machine_edges();

CREATE TRIGGER prior_period_adjustment_is_never_truncated
    BEFORE TRUNCATE ON accounting.prior_period_adjustment
    FOR EACH STATEMENT
    EXECUTE FUNCTION accounting.accounting_period_is_never_truncated();

-- The grants: the decision's columns only; the rest INSERT-only; no DELETE for any role.
GRANT SELECT, INSERT ON accounting.prior_period_adjustment TO finapp_app;
GRANT UPDATE (status, journal_entry_id, decided_by, decided_by_type, decided_at, decision_reason) ON accounting.prior_period_adjustment TO finapp_app;
