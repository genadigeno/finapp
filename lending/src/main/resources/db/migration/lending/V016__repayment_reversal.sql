-- Repayment reversal under four eyes (P11-TSK-020; PHASE_11_PLAN.md sections 7.1 T-rev, 7.2, 7.4 scenario 6, 11, 12.5 and
-- 12.8 rule 10, LENDING_LIFECYCLES.md sections 4.8 and 4.9; ADR-0099 points 1, 2 and 4; INV-LND-11, INV-REV-01, INV-REV-02,
-- INV-AUD-04, INV-HIST-01, INV-CRD-09, INV-LND-13). The number is V016: V015 is P11-TSK-019's (auto-collection), reserved on
-- the wave 4 board; the backlog's "V013" is stale (P11-TSK-021's draw).
--
-- An operator's mistake in applying money is undone by an EXACT reversal of the repayment's own entry - never an edit. A
-- holder of LOAN_SERVICE proposes the reversal of one repayment with a reason; a DIFFERENT holder of LOAN_SERVICE_APPROVE
-- approves it (or anyone with LOAN_SERVICE rejects it, the proposer's withdrawal included). The approval, in ONE
-- transaction (T-rev): the party's credit profile (L0 - outstanding rises), the loan (L3), the proposal (L5), the capital
-- row and its headroom when principal is re-instated (L6), the wallet's ledger row (L7); ReversalService swaps rule 5's
-- entry exactly under lending.repayment-reversal:<reversal> (rule 10 - the original's credits debited back, the wallet
-- credited; bounded by the original, INV-REV-02); the proposal APPROVED naming the reversal entry; one negating row per
-- allocation row; lending.RepaymentReversed; the audit record.
--
--     repayment_reversal            - the proposal's machine: PROPOSED -> APPROVED (posted) | REJECTED, nothing else. NO
--                                     MONEY (the amount is the repayment's), so it takes the terms version's column-scoped
--                                     UPDATE of its decision columns under the edge trigger (P11-TSK-005's split;
--                                     LendingSchemaHasNoMutableMoneyTest). The proposer, the decider, both reasons and
--                                     both instants stay on the row: one edge out of PROPOSED, so nothing is overwritten.
--                                     One LIVE proposal per repayment - the partial UNIQUE (repayment_id) over PROPOSED
--                                     and APPROVED - so a rejected proposal can be proposed again and a repayment is
--                                     reversed at most once. FOUR EYES by CHECK: an approver never proposed it.
--     repayment_allocation_reversal - the negating rows: one per allocation row of the reversed repayment, the same
--                                     amount, never a delete (INV-HIST-01). INSERT-only; an allocation is negated once,
--                                     ever (UNIQUE (allocation_id)).
--
-- The repayment row stays INSERT-only: its REVERSED status is DERIVED from the approved reversal naming it.
-- lending.billing_component_paid is replaced to subtract the negating rows of the allocations that paid billed items: the
-- reversed amount simply becomes due again, every later allocation untouched - never re-cut (ADR-0099 point 4). V014's and
-- V012's never-over-paid rules, the closure's rows-side rule and the schedule read all read that one sum.
--
-- Held for EVERY writer: a proposal born PROPOSED and undecided, for a repayment of its own loan under the repayment's
-- agreement version, only while the loan is ACTIVE; identity frozen; PROPOSED -> APPROVED | REJECTED only, an approval only
-- while the loan is ACTIVE (a closed loan is terminal - A12, INV-LIFE-04: the closing repayment, and every repayment of a
-- loan closed since, is never reversed); APPROVED exactly when it names its reversal entry, one proposal per entry; every
-- reason bounded and screened for card and account numbers (V002's twin functions); the negating rows only for an APPROVED
-- reversal, each mirroring an allocation row of its repayment exactly; at commit, an APPROVED reversal negates EVERY
-- allocation row of its repayment. Lending's triggers never read the ledger (P11-TSK-014's rule): the entry itself - the
-- exact swap - is ReversalService's and V009's bound's.

-- ---------------------------------------------------------------------------------------------
-- The proposal: a money-free machine row.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE lending.repayment_reversal (
    id                 uuid        NOT NULL,
    loan_id            uuid        NOT NULL,
    repayment_id       uuid        NOT NULL,
    agreement_version  integer     NOT NULL,
    status             text        NOT NULL,
    proposed_by        text        NOT NULL,
    proposed_by_type   text        NOT NULL,
    proposed_at        timestamptz NOT NULL,
    proposal_reason    text        NOT NULL,
    decided_by         text,
    decided_by_type    text,
    decided_at         timestamptz,
    decision_reason    text,
    journal_entry_id   uuid,
    correlation_id     text        NOT NULL,
    CONSTRAINT repayment_reversal_pk PRIMARY KEY (id),
    CONSTRAINT repayment_reversal_loan_fk FOREIGN KEY (loan_id) REFERENCES lending.loan (id),
    CONSTRAINT repayment_reversal_repayment_fk FOREIGN KEY (repayment_id) REFERENCES lending.repayment (id),
    -- One reversal per journal entry: the ledger key lending.repayment-reversal:<id> gives one entry per reversal.
    CONSTRAINT repayment_reversal_entry_unique UNIQUE (journal_entry_id),
    CONSTRAINT repayment_reversal_status_is_known CHECK (status IN ('PROPOSED', 'APPROVED', 'REJECTED')),
    CONSTRAINT repayment_reversal_version_numbered CHECK (agreement_version >= 1),
    CONSTRAINT repayment_reversal_actor_types_known CHECK (
        proposed_by_type IN ('SYSTEM', 'CUSTOMER', 'EMPLOYEE', 'SERVICE', 'MERCHANT')
        AND (decided_by_type IS NULL OR decided_by_type IN ('SYSTEM', 'CUSTOMER', 'EMPLOYEE', 'SERVICE', 'MERCHANT'))),
    CONSTRAINT repayment_reversal_actors_bounded CHECK (
        char_length(proposed_by) BETWEEN 1 AND 200 AND (decided_by IS NULL OR char_length(decided_by) BETWEEN 1 AND 200)),
    CONSTRAINT repayment_reversal_proposal_reasoned CHECK (char_length(proposal_reason) BETWEEN 1 AND 1000),
    CONSTRAINT repayment_reversal_proposal_reason_no_instrument_shape CHECK (
        NOT lending.holds_instrument_shape(proposal_reason)),
    CONSTRAINT repayment_reversal_decision_coherent CHECK (
        (status = 'PROPOSED') = (decided_by IS NULL)
        AND (decided_by IS NULL) = (decided_by_type IS NULL)
        AND (decided_by IS NULL) = (decided_at IS NULL)
        AND (decided_by IS NULL) = (decision_reason IS NULL)),
    CONSTRAINT repayment_reversal_decision_reasoned CHECK (
        decision_reason IS NULL OR char_length(decision_reason) BETWEEN 1 AND 1000),
    CONSTRAINT repayment_reversal_decision_reason_no_instrument_shape CHECK (
        decision_reason IS NULL OR NOT lending.holds_instrument_shape(decision_reason)),
    -- FOUR EYES, for every writer (INV-AUD-04, INV-LND-11): whoever approved a reversal did not propose it. A rejection may
    -- be the proposer's withdrawal.
    CONSTRAINT repayment_reversal_four_eyes CHECK (status <> 'APPROVED' OR decided_by <> proposed_by),
    -- The approval and only the approval posts: it names rule 10's entry.
    CONSTRAINT repayment_reversal_approval_posts CHECK ((status = 'APPROVED') = (journal_entry_id IS NOT NULL)),
    CONSTRAINT repayment_reversal_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

-- One live proposal per repayment (ADR-0099 point 4): a PROPOSED or an APPROVED one - so a rejected proposal can be
-- proposed again, and a repayment is reversed at most once. The arbiter beneath the loan's row lock (L3).
CREATE UNIQUE INDEX repayment_reversal_one_live
    ON lending.repayment_reversal (repayment_id) WHERE status IN ('PROPOSED', 'APPROVED');
CREATE INDEX repayment_reversal_by_loan ON lending.repayment_reversal (loan_id, proposed_at);

COMMENT ON TABLE lending.repayment_reversal IS
    'A repayment reversal''s machine (P11-TSK-020; ADR-0099 point 4, INV-LND-11): PROPOSED by a holder of LOAN_SERVICE with a reason, then APPROVED by a DIFFERENT holder of LOAN_SERVICE_APPROVE (four-eyes CHECK) - posting rule 10, the exact ReversalService swap of the repayment''s rule 5 entry under lending.repayment-reversal:<id>, named here - or REJECTED. One live (PROPOSED or APPROVED) proposal per repayment; only while the loan is ACTIVE (a closed loan is terminal, A12). No money: the amount is the repayment''s; the repayment''s REVERSED status is derived from the APPROVED row. Identity frozen; decision columns updatable once, under the edge trigger.';

CREATE OR REPLACE FUNCTION lending.repayment_reversal_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    repayment lending.repayment%ROWTYPE;
    loan_status text;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a repayment reversal is never deleted (P11-TSK-020, INV-HIST-01)';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PROPOSED' OR NEW.decided_by IS NOT NULL OR NEW.decided_by_type IS NOT NULL
                OR NEW.decided_at IS NOT NULL OR NEW.decision_reason IS NOT NULL OR NEW.journal_entry_id IS NOT NULL THEN
            RAISE EXCEPTION 'a repayment reversal is born PROPOSED and undecided: nothing is reversed by insert (P11-TSK-020)';
        END IF;
        -- A repayment of its own loan, named under the repayment's own agreement version (nothing is recomputed).
        SELECT r.* INTO repayment FROM lending.repayment r WHERE r.id = NEW.repayment_id;
        IF repayment.id IS NULL OR repayment.loan_id <> NEW.loan_id
                OR repayment.agreement_version <> NEW.agreement_version THEN
            RAISE EXCEPTION 'a repayment reversal names a repayment of its own loan, under its agreement version (P11-TSK-020)';
        END IF;
        SELECT l.status INTO loan_status FROM lending.loan l WHERE l.id = NEW.loan_id;
        IF loan_status IS DISTINCT FROM 'ACTIVE' THEN
            RAISE EXCEPTION 'a repayment of a loan not ACTIVE is never reversed - a closed loan is terminal (P11-TSK-020, A12, INV-LIFE-04)';
        END IF;
        NEW.proposed_at := transaction_timestamp();
        RETURN NEW;
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.loan_id IS DISTINCT FROM OLD.loan_id
            OR NEW.repayment_id IS DISTINCT FROM OLD.repayment_id
            OR NEW.agreement_version IS DISTINCT FROM OLD.agreement_version
            OR NEW.proposed_by IS DISTINCT FROM OLD.proposed_by
            OR NEW.proposed_by_type IS DISTINCT FROM OLD.proposed_by_type
            OR NEW.proposed_at IS DISTINCT FROM OLD.proposed_at
            OR NEW.proposal_reason IS DISTINCT FROM OLD.proposal_reason
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a repayment reversal''s identity and proposal are frozen (P11-TSK-020)';
    END IF;
    IF NOT (OLD.status = 'PROPOSED' AND NEW.status IN ('APPROVED', 'REJECTED')) THEN
        RAISE EXCEPTION 'a repayment reversal moves PROPOSED -> APPROVED | REJECTED; % -> % is not an edge (P11-TSK-020)', OLD.status, NEW.status;
    END IF;
    IF NEW.status = 'APPROVED' THEN
        SELECT l.status INTO loan_status FROM lending.loan l WHERE l.id = NEW.loan_id;
        IF loan_status IS DISTINCT FROM 'ACTIVE' THEN
            RAISE EXCEPTION 'a repayment of a loan not ACTIVE is never reversed - a closed loan is terminal (P11-TSK-020, A12, INV-LIFE-04)';
        END IF;
    END IF;
    NEW.decided_at := transaction_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER repayment_reversal_permits_only_machine_edges
    BEFORE INSERT OR UPDATE OR DELETE ON lending.repayment_reversal
    FOR EACH ROW
    EXECUTE FUNCTION lending.repayment_reversal_permits_only_machine_edges();

CREATE TRIGGER repayment_reversal_is_never_truncated
    BEFORE TRUNCATE ON lending.repayment_reversal
    FOR EACH STATEMENT
    EXECUTE FUNCTION lending.loan_is_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The negating rows.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE lending.repayment_allocation_reversal (
    id              uuid     NOT NULL,
    reversal_id     uuid     NOT NULL,
    allocation_id   uuid     NOT NULL,
    currency        char(3)  NOT NULL,
    scale           smallint NOT NULL,
    amount_minor    bigint   NOT NULL,
    CONSTRAINT repayment_allocation_reversal_pk PRIMARY KEY (id),
    -- An allocation is negated once, ever: a repayment is reversed at most once, and wholly.
    CONSTRAINT repayment_allocation_reversal_allocation_unique UNIQUE (allocation_id),
    CONSTRAINT repayment_allocation_reversal_reversal_fk FOREIGN KEY (reversal_id)
        REFERENCES lending.repayment_reversal (id),
    CONSTRAINT repayment_allocation_reversal_allocation_fk FOREIGN KEY (allocation_id)
        REFERENCES lending.repayment_allocation (id),
    CONSTRAINT repayment_allocation_reversal_currency_is_the_products CHECK (currency = 'EUR' AND scale BETWEEN 0 AND 9),
    CONSTRAINT repayment_allocation_reversal_amount_positive CHECK (amount_minor > 0)
);

CREATE INDEX repayment_allocation_reversal_by_reversal ON lending.repayment_allocation_reversal (reversal_id);

COMMENT ON TABLE lending.repayment_allocation_reversal IS
    'One negating row of an approved repayment reversal (P11-TSK-020; ADR-0099 point 4, INV-HIST-01): it undoes one repayment_allocation row exactly - the same amount, in its currency and scale - and never deletes it. Every allocation row of the reversed repayment has one, at commit; each allocation is negated once, ever. lending.billing_component_paid subtracts the rows of allocations that paid billed items, so what the reversal un-paid is due again and later allocations stand. INSERT-only for every role. RESTRICTED-FINANCIAL.';

CREATE OR REPLACE FUNCTION lending.repayment_allocation_reversal_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    reversal lending.repayment_reversal%ROWTYPE;
    allocation lending.repayment_allocation%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a repayment allocation reversal is immutable from insert, for every role (P11-TSK-020, INV-HIST-01)';
    END IF;
    SELECT x.* INTO reversal FROM lending.repayment_reversal x WHERE x.id = NEW.reversal_id;
    IF reversal.id IS NULL OR reversal.status <> 'APPROVED' THEN
        RAISE EXCEPTION 'only an APPROVED repayment reversal negates allocations (P11-TSK-020, INV-LND-11)';
    END IF;
    SELECT a.* INTO allocation FROM lending.repayment_allocation a WHERE a.id = NEW.allocation_id;
    IF allocation.id IS NULL OR allocation.repayment_id <> reversal.repayment_id
            OR NEW.currency <> allocation.currency OR NEW.scale <> allocation.scale
            OR NEW.amount_minor <> allocation.amount_minor THEN
        RAISE EXCEPTION 'a negating row mirrors an allocation row of the reversed repayment exactly (P11-TSK-020, INV-REV-02)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER repayment_allocation_reversal_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON lending.repayment_allocation_reversal
    FOR EACH ROW
    EXECUTE FUNCTION lending.repayment_allocation_reversal_is_born_once();

CREATE TRIGGER repayment_allocation_reversal_is_never_truncated
    BEFORE TRUNCATE ON lending.repayment_allocation_reversal
    FOR EACH STATEMENT
    EXECUTE FUNCTION lending.loan_is_never_truncated();

-- At commit, for every writer: an APPROVED reversal negates EVERY allocation row of its repayment - a reversal is whole.
CREATE OR REPLACE FUNCTION lending.repayment_reversal_is_whole_at_commit()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    reversal uuid;
BEGIN
    -- NEW is a different row type per table: each branch names only its own table's field.
    IF TG_TABLE_NAME = 'repayment_reversal' THEN
        reversal := NEW.id;
    ELSE
        reversal := NEW.reversal_id;
    END IF;
    IF EXISTS (SELECT 1 FROM lending.repayment_reversal x
                 JOIN lending.repayment_allocation a ON a.repayment_id = x.repayment_id
                WHERE x.id = reversal AND x.status = 'APPROVED'
                  AND NOT EXISTS (SELECT 1 FROM lending.repayment_allocation_reversal n
                                   WHERE n.reversal_id = x.id AND n.allocation_id = a.id)) THEN
        RAISE EXCEPTION 'an approved repayment reversal negates every allocation row of its repayment (P11-TSK-020, INV-LND-04)';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER repayment_reversal_is_whole
    AFTER UPDATE ON lending.repayment_reversal
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION lending.repayment_reversal_is_whole_at_commit();

CREATE CONSTRAINT TRIGGER repayment_allocation_reversal_is_whole
    AFTER INSERT ON lending.repayment_allocation_reversal
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION lending.repayment_reversal_is_whole_at_commit();

-- ---------------------------------------------------------------------------------------------
-- What a billed item's component has had paid, replaced: every payer's allocations (V014's) less the negating rows of the
-- repayment allocations an approved reversal undid - so the reversed amount is due again, and every later allocation stands.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION lending.billing_component_paid(billing uuid, paid_component text)
    RETURNS bigint
    LANGUAGE sql
    STABLE
AS $$
    SELECT ((SELECT coalesce(sum(a.amount_minor), 0) FROM lending.credit_balance_allocation a
              WHERE a.billing_id = billing AND a.component = paid_component)
          + (SELECT coalesce(sum(r.amount_minor), 0) FROM lending.repayment_allocation r
              WHERE r.target = 'DUE_ITEM' AND r.billing_id = billing AND r.component = paid_component)
          - (SELECT coalesce(sum(n.amount_minor), 0) FROM lending.repayment_allocation_reversal n
               JOIN lending.repayment_allocation r ON r.id = n.allocation_id
              WHERE r.target = 'DUE_ITEM' AND r.billing_id = billing AND r.component = paid_component))::bigint
$$;

-- ---------------------------------------------------------------------------------------------
-- The grants: the negating rows SELECT and INSERT only; the proposal's decision columns beside its status (the decision
-- instant is the trigger's - stamped, never written by the caller). Never DELETE, never TRUNCATE.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON lending.repayment_reversal TO finapp_app;
GRANT UPDATE (status, decided_by, decided_by_type, decided_at, decision_reason, journal_entry_id)
    ON lending.repayment_reversal TO finapp_app;
GRANT SELECT, INSERT ON lending.repayment_allocation_reversal TO finapp_app;
