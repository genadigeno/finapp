-- Repayment reversal and fee waiver under four eyes (P12-TSK-024; PHASE_12_PLAN.md sections 7.1 T-rev / T-wvr, 7.2, 7.4,
-- 12.11 F7 and F8, 13.2 scenario 12; BNPL_LIFECYCLES.md section 4.11; ADR-0099's shape applied to bnpl - lending V016 and
-- V021, P11-TSK-020 and -025, mirrored; INV-REV-01, INV-REV-02, INV-REV-04, INV-AUD-04, INV-HIST-01, INV-CRD-09,
-- INV-LND-13 and INV-LND-11 by analogy, INV-BNPL-10).
--
-- bnpl V016 is this task's - the Phase 12 wave F board's reservation (P12-TSK-023 takes V015; Flyway runs with
-- out-of-order off, so V015 merges first).
--
-- An operator's mistake in applying a customer's money is undone by an EXACT reversal of the repayment's own entry, and a
-- late fee is forgone only by a bounded waiver - each a reasoned, four-eyes act that POSTS, never an edit. A holder of
-- BNPL_SERVICE proposes with a reason; a DIFFERENT holder of BNPL_SERVICE_APPROVE approves (or anyone with BNPL_SERVICE
-- rejects, the proposer's withdrawal included):
--
--     bnpl_repayment_reversal            - the reversal's machine: PROPOSED -> APPROVED (posted) | REJECTED, nothing else.
--                                          NO MONEY (the amount is the repayment's), so it takes a column-scoped UPDATE of
--                                          its decision columns under the edge trigger (the terms version's split;
--                                          BnplSchemaHasNoMutableMoneyTest). One LIVE proposal per repayment - the partial
--                                          UNIQUE (repayment_id) over PROPOSED and APPROVED - so a rejected one may be
--                                          proposed again and a repayment is reversed at most once. FOUR EYES by CHECK.
--                                          APPROVED exactly when it names F8's entry and its value date. Never a PAYOFF
--                                          repayment's (P12-TSK-022: a payoff closes its obligation and is not reversible),
--                                          never on an obligation not ACTIVE (a closed obligation is terminal - lending's
--                                          A12, INV-LIFE-04: the closing repayment and every repayment of an obligation
--                                          closed since).
--     bnpl_repayment_allocation_reversal - the negating rows: one per allocation row of the reversed repayment, the same
--                                          amount, never a delete (INV-HIST-01). INSERT-only; an allocation is negated
--                                          once, ever (UNIQUE (allocation_id)); at commit an APPROVED reversal negates
--                                          EVERY allocation row of its repayment - a reversal is whole.
--     bnpl_waiver / bnpl_waiver_decision - the fee waiver's proposal (kind FEE; the assessment and its instalment; the
--                                          amount - money, so INSERT-only) and its decision, born once (UNIQUE
--                                          (waiver_id)): APPROVED by a holder who did not propose it (four-eyes CHECK over
--                                          the proposer copied and pinned by the composite foreign key) naming F7's entry,
--                                          or REJECTED. One PROPOSED waiver per assessment at a time.
--
-- THE TWO SUMS EVERY READER SHARES, REPLACED (board rule 3 - posted on the wave F board):
--
--     bnpl.billing_component_paid   - V013's body, LESS the negating rows of the reversed repayments' due-item lines: the
--                                     reversed amount simply becomes due again, every later allocation untouched -
--                                     never re-cut (ADR-0099 point 4). Its instalment is past due again from the next
--                                     derivation (bnpl.days_past_due reads this sum), DPD recomputed by the next step.
--     bnpl.billing_component_billed - V013's body, FEES LESS the approved waivers of the instalment's assessments.
--
-- NOTHING IS FORGIVEN TWICE (the Phase 11 gate's R3, applied forward): a waiver is bounded by what its fee still has due
-- - billed less settled, where billed already nets every approved waiver and settled already counts every payer, every
-- negation and every refund's release of the fee (OQ-14, V013) - AND by what of the assessment was never forgiven:
-- assessed less approved waivers less released (bnpl.fee_waiver_room). Either alone holds today (billed - settled =
-- assessed - waived - released - net payments, the net payments never negative); both are judged, by the domain under B4
-- and by the triggers for every writer, so a later change to one sum cannot reopen a fee already forgiven.
--
-- Held for EVERY writer: each trigger re-takes B4 (re-entrant - the domain holds it already), so every judgement is made
-- under the lock that serialises every act on the obligation; identities frozen; reasons bounded and screened for card and
-- account numbers (V002's twin functions); every instant the database's, clock_timestamp() after the act's locks. No bnpl
-- trigger reads a ledger table (the P12-TSK-013 refinement): the entries themselves are ReversalService's and ledger
-- V009's bound's (INV-REV-02), and the domain's exact-swap check.

-- ---------------------------------------------------------------------------------------------
-- The repayment reversal: a money-free machine row.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE bnpl.bnpl_repayment_reversal (
    id                 uuid        NOT NULL,
    obligation_id      uuid        NOT NULL,
    repayment_id       uuid        NOT NULL,
    agreement_id       uuid        NOT NULL,
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
    value_date         date,
    correlation_id     text        NOT NULL,
    CONSTRAINT bnpl_repayment_reversal_pk PRIMARY KEY (id),
    CONSTRAINT bnpl_repayment_reversal_obligation_fk FOREIGN KEY (obligation_id) REFERENCES bnpl.bnpl_obligation (id),
    CONSTRAINT bnpl_repayment_reversal_repayment_fk FOREIGN KEY (repayment_id) REFERENCES bnpl.bnpl_repayment (id),
    CONSTRAINT bnpl_repayment_reversal_agreement_fk FOREIGN KEY (agreement_id, agreement_version)
        REFERENCES bnpl.bnpl_agreement (agreement_id, version),
    -- One reversal per journal entry: the ledger key bnpl.repayment-reversal:<id> gives one entry per reversal.
    CONSTRAINT bnpl_repayment_reversal_entry_unique UNIQUE (journal_entry_id),
    CONSTRAINT bnpl_repayment_reversal_status_is_known CHECK (status IN ('PROPOSED', 'APPROVED', 'REJECTED')),
    CONSTRAINT bnpl_repayment_reversal_version_numbered CHECK (agreement_version >= 1),
    CONSTRAINT bnpl_repayment_reversal_actor_types_known CHECK (
        proposed_by_type IN ('SYSTEM', 'CUSTOMER', 'EMPLOYEE', 'SERVICE', 'MERCHANT')
        AND (decided_by_type IS NULL OR decided_by_type IN ('SYSTEM', 'CUSTOMER', 'EMPLOYEE', 'SERVICE', 'MERCHANT'))),
    CONSTRAINT bnpl_repayment_reversal_actors_bounded CHECK (
        char_length(proposed_by) BETWEEN 1 AND 200 AND (decided_by IS NULL OR char_length(decided_by) BETWEEN 1 AND 200)),
    CONSTRAINT bnpl_repayment_reversal_proposal_reasoned CHECK (char_length(proposal_reason) BETWEEN 1 AND 1000),
    CONSTRAINT bnpl_repayment_reversal_proposal_reason_no_instrument_shape CHECK (
        NOT bnpl.holds_instrument_shape(proposal_reason)),
    CONSTRAINT bnpl_repayment_reversal_decision_coherent CHECK (
        (status = 'PROPOSED') = (decided_by IS NULL)
        AND (decided_by IS NULL) = (decided_by_type IS NULL)
        AND (decided_by IS NULL) = (decided_at IS NULL)
        AND (decided_by IS NULL) = (decision_reason IS NULL)),
    CONSTRAINT bnpl_repayment_reversal_decision_reasoned CHECK (
        decision_reason IS NULL OR char_length(decision_reason) BETWEEN 1 AND 1000),
    CONSTRAINT bnpl_repayment_reversal_decision_reason_no_instrument_shape CHECK (
        decision_reason IS NULL OR NOT bnpl.holds_instrument_shape(decision_reason)),
    -- FOUR EYES, for every writer (INV-AUD-04, INV-LND-11 by analogy): whoever approved a reversal did not propose it. A
    -- rejection may be the proposer's withdrawal.
    CONSTRAINT bnpl_repayment_reversal_four_eyes CHECK (status <> 'APPROVED' OR decided_by <> proposed_by),
    -- The approval and only the approval posts: it names F8's entry and the date that entry is valued.
    CONSTRAINT bnpl_repayment_reversal_approval_posts CHECK (
        (status = 'APPROVED') = (journal_entry_id IS NOT NULL)
        AND (status = 'APPROVED') = (value_date IS NOT NULL)),
    CONSTRAINT bnpl_repayment_reversal_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

-- One live proposal per repayment (ADR-0099 point 4): a PROPOSED or an APPROVED one - so a rejected proposal can be
-- proposed again, and a repayment is reversed at most once. The arbiter beneath the obligation's row lock (B4).
CREATE UNIQUE INDEX bnpl_repayment_reversal_one_live
    ON bnpl.bnpl_repayment_reversal (repayment_id) WHERE status IN ('PROPOSED', 'APPROVED');
CREATE INDEX bnpl_repayment_reversal_by_obligation ON bnpl.bnpl_repayment_reversal (obligation_id, proposed_at);

COMMENT ON TABLE bnpl.bnpl_repayment_reversal IS
    'A BNPL repayment reversal''s machine (P12-TSK-024; ADR-0099 point 4, INV-LND-11 by analogy): PROPOSED by a holder of BNPL_SERVICE with a reason, then APPROVED by a DIFFERENT holder of BNPL_SERVICE_APPROVE (four-eyes CHECK) - posting F8, the exact ReversalService swap of the repayment''s F3 entry under bnpl.repayment-reversal:<id>, named here with its value date - or REJECTED. One live (PROPOSED or APPROVED) proposal per repayment; only while the obligation is ACTIVE (a closed obligation is terminal); never a PAYOFF repayment''s. No money: the amount is the repayment''s, and the repayment''s REVERSED status is derived from the APPROVED row. Identity frozen; decision columns updatable once, under the edge trigger. The reasons RESTRICTED-PII (a person''s prose), screened for instrument shapes.';

CREATE OR REPLACE FUNCTION bnpl.bnpl_repayment_reversal_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    obligation bnpl.bnpl_obligation%ROWTYPE;
    repayment bnpl.bnpl_repayment%ROWTYPE;
    value_floor date;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a BNPL repayment reversal is never deleted (P12-TSK-024, INV-HIST-01)';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PROPOSED' OR NEW.decided_by IS NOT NULL OR NEW.decided_by_type IS NOT NULL
                OR NEW.decided_at IS NOT NULL OR NEW.decision_reason IS NOT NULL OR NEW.journal_entry_id IS NOT NULL
                OR NEW.value_date IS NOT NULL THEN
            RAISE EXCEPTION 'a BNPL repayment reversal is born PROPOSED and undecided: nothing is reversed by insert (P12-TSK-024)';
        END IF;
        -- (B4) The obligation, for every writer: an ACTIVE one.
        SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id FOR UPDATE;
        IF NOT FOUND OR obligation.status <> 'ACTIVE' THEN
            RAISE EXCEPTION 'a repayment of a BNPL obligation not ACTIVE is never reversed - a closed obligation is terminal (P12-TSK-024, INV-LIFE-04)';
        END IF;
        -- A repayment of its own obligation, named under the repayment's own agreement version (nothing is recomputed) -
        -- never a payoff's (P12-TSK-022).
        SELECT r.* INTO repayment FROM bnpl.bnpl_repayment r WHERE r.id = NEW.repayment_id;
        IF repayment.id IS NULL OR repayment.obligation_id <> NEW.obligation_id
                OR repayment.agreement_id <> NEW.agreement_id OR repayment.agreement_version <> NEW.agreement_version THEN
            RAISE EXCEPTION 'a BNPL repayment reversal names a repayment of its own obligation, under its agreement version (P12-TSK-024)';
        END IF;
        IF repayment.kind = 'PAYOFF' THEN
            RAISE EXCEPTION 'a PAYOFF repayment closed its obligation and is never reversed (P12-TSK-022, P12-TSK-024)';
        END IF;
        NEW.proposed_at := clock_timestamp();
        RETURN NEW;
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.obligation_id IS DISTINCT FROM OLD.obligation_id
            OR NEW.repayment_id IS DISTINCT FROM OLD.repayment_id
            OR NEW.agreement_id IS DISTINCT FROM OLD.agreement_id
            OR NEW.agreement_version IS DISTINCT FROM OLD.agreement_version
            OR NEW.proposed_by IS DISTINCT FROM OLD.proposed_by
            OR NEW.proposed_by_type IS DISTINCT FROM OLD.proposed_by_type
            OR NEW.proposed_at IS DISTINCT FROM OLD.proposed_at
            OR NEW.proposal_reason IS DISTINCT FROM OLD.proposal_reason
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a BNPL repayment reversal''s identity and proposal are frozen (P12-TSK-024)';
    END IF;
    IF NOT (OLD.status = 'PROPOSED' AND NEW.status IN ('APPROVED', 'REJECTED')) THEN
        RAISE EXCEPTION 'a BNPL repayment reversal moves PROPOSED -> APPROVED | REJECTED; % -> % is not an edge (P12-TSK-024)', OLD.status, NEW.status;
    END IF;
    IF NEW.status = 'APPROVED' THEN
        -- (B4) The obligation, for every writer: still ACTIVE.
        SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id FOR UPDATE;
        IF NOT FOUND OR obligation.status <> 'ACTIVE' THEN
            RAISE EXCEPTION 'a repayment of a BNPL obligation not ACTIVE is never reversed - a closed obligation is terminal (P12-TSK-024, INV-LIFE-04)';
        END IF;
        -- The principal re-instated never joins a date already accrued (the Phase 11 gate's R1): valued on or after the
        -- floor read under B4, never after the later of the latest date the clock showed and it.
        value_floor := bnpl.principal_value_floor(NEW.obligation_id);
        IF NEW.value_date < value_floor
                OR NEW.value_date > GREATEST(bnpl.business_date_latest(obligation.servicing_zone), value_floor) THEN
            RAISE EXCEPTION 'a BNPL repayment reversal is valued on or after its obligation''s floor and never after today (P12-TSK-024, the Phase 11 gate''s R1)';
        END IF;
    END IF;
    NEW.decided_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER bnpl_repayment_reversal_permits_only_machine_edges
    BEFORE INSERT OR UPDATE OR DELETE ON bnpl.bnpl_repayment_reversal
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_repayment_reversal_permits_only_machine_edges();

CREATE TRIGGER bnpl_repayment_reversal_is_never_truncated
    BEFORE TRUNCATE ON bnpl.bnpl_repayment_reversal
    FOR EACH STATEMENT
    EXECUTE FUNCTION bnpl.acceptance_rows_are_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The negating rows.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE bnpl.bnpl_repayment_allocation_reversal (
    id              uuid     NOT NULL,
    reversal_id     uuid     NOT NULL,
    allocation_id   uuid     NOT NULL,
    currency        char(3)  NOT NULL,
    scale           smallint NOT NULL,
    amount_minor    bigint   NOT NULL,
    CONSTRAINT bnpl_repayment_allocation_reversal_pk PRIMARY KEY (id),
    -- An allocation is negated once, ever: a repayment is reversed at most once, and wholly.
    CONSTRAINT bnpl_repayment_allocation_reversal_allocation_unique UNIQUE (allocation_id),
    CONSTRAINT bnpl_repayment_allocation_reversal_reversal_fk FOREIGN KEY (reversal_id)
        REFERENCES bnpl.bnpl_repayment_reversal (id),
    CONSTRAINT bnpl_repayment_allocation_reversal_allocation_fk FOREIGN KEY (allocation_id)
        REFERENCES bnpl.bnpl_repayment_allocation (id),
    CONSTRAINT bnpl_repayment_allocation_reversal_currency_is_the_products CHECK (currency = 'EUR' AND scale BETWEEN 0 AND 9),
    CONSTRAINT bnpl_repayment_allocation_reversal_amount_positive CHECK (amount_minor > 0)
);

CREATE INDEX bnpl_repayment_allocation_reversal_by_reversal ON bnpl.bnpl_repayment_allocation_reversal (reversal_id);

COMMENT ON TABLE bnpl.bnpl_repayment_allocation_reversal IS
    'One negating row of an approved BNPL repayment reversal (P12-TSK-024; ADR-0099 point 4, INV-HIST-01): it undoes one bnpl_repayment_allocation row exactly - the same amount, in its currency and scale - and never deletes it. Every allocation row of the reversed repayment has one, at commit; each allocation is negated once, ever. bnpl.billing_component_paid subtracts the rows of due-item allocations, so what the reversal un-paid is due again and every later allocation stands. INSERT-only for every role. RESTRICTED-FINANCIAL.';

CREATE OR REPLACE FUNCTION bnpl.bnpl_repayment_allocation_reversal_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    reversal bnpl.bnpl_repayment_reversal%ROWTYPE;
    allocation bnpl.bnpl_repayment_allocation%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL repayment allocation reversal is immutable from insert, for every role (P12-TSK-024, INV-HIST-01)';
    END IF;
    SELECT x.* INTO reversal FROM bnpl.bnpl_repayment_reversal x WHERE x.id = NEW.reversal_id;
    IF reversal.id IS NULL OR reversal.status <> 'APPROVED' THEN
        RAISE EXCEPTION 'only an APPROVED BNPL repayment reversal negates allocations (P12-TSK-024, INV-LND-11)';
    END IF;
    SELECT a.* INTO allocation FROM bnpl.bnpl_repayment_allocation a WHERE a.id = NEW.allocation_id;
    IF allocation.id IS NULL OR allocation.repayment_id <> reversal.repayment_id
            OR NEW.currency <> allocation.currency OR NEW.scale <> allocation.scale
            OR NEW.amount_minor <> allocation.amount_minor THEN
        RAISE EXCEPTION 'a negating row mirrors an allocation row of the reversed repayment exactly (P12-TSK-024, INV-REV-02)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER bnpl_repayment_allocation_reversal_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON bnpl.bnpl_repayment_allocation_reversal
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_repayment_allocation_reversal_is_born_once();

CREATE TRIGGER bnpl_repayment_allocation_reversal_is_never_truncated
    BEFORE TRUNCATE ON bnpl.bnpl_repayment_allocation_reversal
    FOR EACH STATEMENT
    EXECUTE FUNCTION bnpl.acceptance_rows_are_never_truncated();

-- At commit, for every writer: an APPROVED reversal negates EVERY allocation row of its repayment - a reversal is whole.
CREATE OR REPLACE FUNCTION bnpl.bnpl_repayment_reversal_is_whole_at_commit()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    reversal uuid;
BEGIN
    -- NEW is a different row type per table: each branch names only its own table's field.
    IF TG_TABLE_NAME = 'bnpl_repayment_reversal' THEN
        reversal := NEW.id;
    ELSE
        reversal := NEW.reversal_id;
    END IF;
    IF EXISTS (SELECT 1 FROM bnpl.bnpl_repayment_reversal x
                 JOIN bnpl.bnpl_repayment_allocation a ON a.repayment_id = x.repayment_id
                WHERE x.id = reversal AND x.status = 'APPROVED'
                  AND NOT EXISTS (SELECT 1 FROM bnpl.bnpl_repayment_allocation_reversal n
                                   WHERE n.reversal_id = x.id AND n.allocation_id = a.id)) THEN
        RAISE EXCEPTION 'an approved BNPL repayment reversal negates every allocation row of its repayment (P12-TSK-024, INV-LND-04)';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER bnpl_repayment_reversal_is_whole
    AFTER UPDATE ON bnpl.bnpl_repayment_reversal
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_repayment_reversal_is_whole_at_commit();

CREATE CONSTRAINT TRIGGER bnpl_repayment_allocation_reversal_is_whole
    AFTER INSERT ON bnpl.bnpl_repayment_allocation_reversal
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_repayment_reversal_is_whole_at_commit();

-- ---------------------------------------------------------------------------------------------
-- The fee waiver's proposal: born once (it carries money).
-- ---------------------------------------------------------------------------------------------
-- The waiver's composite foreign key names an assessment of its own obligation and instalment.
ALTER TABLE bnpl.bnpl_fee_assessment
    ADD CONSTRAINT bnpl_fee_assessment_of_its_instalment UNIQUE (id, obligation_id, instalment_id);

CREATE TABLE bnpl.bnpl_waiver (
    id                 uuid        NOT NULL,
    obligation_id      uuid        NOT NULL,
    kind               text        NOT NULL,
    fee_assessment_id  uuid        NOT NULL,
    instalment_id      uuid        NOT NULL,
    currency           char(3)     NOT NULL,
    scale              smallint    NOT NULL,
    amount_minor       bigint      NOT NULL,
    agreement_id       uuid        NOT NULL,
    agreement_version  integer     NOT NULL,
    proposed_by        text        NOT NULL,
    proposed_by_type   text        NOT NULL,
    proposed_at        timestamptz NOT NULL,
    proposal_reason    text        NOT NULL,
    correlation_id     text        NOT NULL,
    CONSTRAINT bnpl_waiver_pk PRIMARY KEY (id),
    -- The decision's composite key pins its copy of the proposer to this row's.
    CONSTRAINT bnpl_waiver_proposer_pinned UNIQUE (id, proposed_by),
    CONSTRAINT bnpl_waiver_obligation_fk FOREIGN KEY (obligation_id) REFERENCES bnpl.bnpl_obligation (id),
    -- A fee waiver names an assessment of its own obligation, on its instalment.
    CONSTRAINT bnpl_waiver_assessment_fk FOREIGN KEY (fee_assessment_id, obligation_id, instalment_id)
        REFERENCES bnpl.bnpl_fee_assessment (id, obligation_id, instalment_id),
    CONSTRAINT bnpl_waiver_agreement_fk FOREIGN KEY (agreement_id, agreement_version)
        REFERENCES bnpl.bnpl_agreement (agreement_id, version),
    -- BnplWaiverKind: a fee's unpaid remainder only (no interest waiver - PHASE_12_PLAN.md section 12.11 has no rule).
    CONSTRAINT bnpl_waiver_kind_is_known CHECK (kind IN ('FEE')),
    CONSTRAINT bnpl_waiver_currency_is_the_products CHECK (currency = 'EUR' AND scale BETWEEN 0 AND 9),
    CONSTRAINT bnpl_waiver_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT bnpl_waiver_version_numbered CHECK (agreement_version >= 1),
    CONSTRAINT bnpl_waiver_actor_type_known CHECK (
        proposed_by_type IN ('SYSTEM', 'CUSTOMER', 'EMPLOYEE', 'SERVICE', 'MERCHANT')),
    CONSTRAINT bnpl_waiver_actor_bounded CHECK (char_length(proposed_by) BETWEEN 1 AND 200),
    CONSTRAINT bnpl_waiver_proposal_reasoned CHECK (char_length(proposal_reason) BETWEEN 1 AND 1000),
    CONSTRAINT bnpl_waiver_proposal_reason_no_instrument_shape CHECK (NOT bnpl.holds_instrument_shape(proposal_reason)),
    CONSTRAINT bnpl_waiver_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

CREATE INDEX bnpl_waiver_by_obligation ON bnpl.bnpl_waiver (obligation_id, proposed_at);
CREATE INDEX bnpl_waiver_by_assessment ON bnpl.bnpl_waiver (fee_assessment_id);
CREATE INDEX bnpl_waiver_by_instalment ON bnpl.bnpl_waiver (instalment_id);

COMMENT ON TABLE bnpl.bnpl_waiver IS
    'A BNPL fee waiver''s proposal (P12-TSK-024; ADR-0099, INV-LND-11 by analogy, the Phase 11 gate''s R3): kind FEE - an amount of a late fee''s unpaid remainder, F7 the ReversalService of the assessment''s F4 entry (DR BNPL_FEE_INCOME / CR BNPL_FEES_DUE) for the amount, bounded; the assessment and its instalment, the agreement version in force, the proposer (a holder of BNPL_SERVICE) and the reason. Born once, INSERT-only for every role; its decision is bnpl_waiver_decision (none: PROPOSED). One PROPOSED waiver per assessment. The amount RESTRICTED-FINANCIAL; the reason RESTRICTED-PII (a person''s prose), screened for instrument shapes.';

-- ---------------------------------------------------------------------------------------------
-- The waiver's decision: born once.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE bnpl.bnpl_waiver_decision (
    id                 uuid        NOT NULL,
    waiver_id          uuid        NOT NULL,
    proposed_by        text        NOT NULL,
    outcome            text        NOT NULL,
    decided_by         text        NOT NULL,
    decided_by_type    text        NOT NULL,
    decided_at         timestamptz NOT NULL,
    decision_reason    text        NOT NULL,
    journal_entry_id   uuid,
    correlation_id     text        NOT NULL,
    CONSTRAINT bnpl_waiver_decision_pk PRIMARY KEY (id),
    CONSTRAINT bnpl_waiver_decision_waiver_fk FOREIGN KEY (waiver_id, proposed_by)
        REFERENCES bnpl.bnpl_waiver (id, proposed_by),
    -- Decided once: ten approvers, one decision - the arbiter beneath B4.
    CONSTRAINT bnpl_waiver_decision_once UNIQUE (waiver_id),
    -- One decision per journal entry: each approval posts its own entry.
    CONSTRAINT bnpl_waiver_decision_entry_unique UNIQUE (journal_entry_id),
    CONSTRAINT bnpl_waiver_decision_outcome_is_known CHECK (outcome IN ('APPROVED', 'REJECTED')),
    -- FOUR EYES, for every writer (INV-AUD-04, INV-LND-11 by analogy): whoever approved a waiver did not propose it. A
    -- rejection may be the proposer's withdrawal.
    CONSTRAINT bnpl_waiver_decision_four_eyes CHECK (outcome <> 'APPROVED' OR decided_by <> proposed_by),
    -- The approval and only the approval posts: it names F7's entry.
    CONSTRAINT bnpl_waiver_decision_approval_posts CHECK ((outcome = 'APPROVED') = (journal_entry_id IS NOT NULL)),
    CONSTRAINT bnpl_waiver_decision_actor_type_known CHECK (
        decided_by_type IN ('SYSTEM', 'CUSTOMER', 'EMPLOYEE', 'SERVICE', 'MERCHANT')),
    CONSTRAINT bnpl_waiver_decision_actor_bounded CHECK (
        char_length(decided_by) BETWEEN 1 AND 200 AND char_length(proposed_by) BETWEEN 1 AND 200),
    CONSTRAINT bnpl_waiver_decision_reasoned CHECK (char_length(decision_reason) BETWEEN 1 AND 1000),
    CONSTRAINT bnpl_waiver_decision_reason_no_instrument_shape CHECK (NOT bnpl.holds_instrument_shape(decision_reason)),
    CONSTRAINT bnpl_waiver_decision_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

COMMENT ON TABLE bnpl.bnpl_waiver_decision IS
    'A BNPL fee waiver''s decision (P12-TSK-024; ADR-0099, INV-LND-11 by analogy, INV-AUD-04): APPROVED by a holder of BNPL_SERVICE_APPROVE who did not propose it (four-eyes CHECK over the proposer copied and pinned by the composite foreign key) - naming the F7 entry it posted - or REJECTED (the proposer''s withdrawal included), with the decider''s reason (RESTRICTED-PII). UNIQUE (waiver_id): decided once. An approval only on an ACTIVE obligation and within bnpl.fee_waiver_room. INSERT-only for every role.';

-- ---------------------------------------------------------------------------------------------
-- What a billing's component billed, replaced: V013's interest and principal, and its instalment's assessed fees less
-- their approved waivers (FEES). One sum every reader shares - the due state, the closure's rows-side rule, the
-- never-over-paid rules, the collection attempt, days_past_due and the waiver's room.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION bnpl.billing_component_billed(billing uuid, billed_component text)
    RETURNS bigint
    LANGUAGE sql
    STABLE
AS $$
    SELECT (CASE billed_component
                WHEN 'INTEREST' THEN b.interest_minor
                WHEN 'PRINCIPAL' THEN b.principal_minor
                ELSE (SELECT coalesce(sum(f.amount_minor), 0) FROM bnpl.bnpl_fee_assessment f
                       WHERE f.instalment_id = b.instalment_id)
                   - (SELECT coalesce(sum(w.amount_minor), 0) FROM bnpl.bnpl_waiver w
                        JOIN bnpl.bnpl_waiver_decision d ON d.waiver_id = w.id AND d.outcome = 'APPROVED'
                       WHERE w.instalment_id = b.instalment_id)
            END)::bigint
      FROM bnpl.bnpl_billing b WHERE b.id = billing
$$;

COMMENT ON FUNCTION bnpl.billing_component_billed(uuid, text) IS
    'What a billing''s component (FEES, INTEREST, PRINCIPAL) billed (P12-TSK-015): its interest and principal, and - FEES - its instalment''s assessed late fees (P12-TSK-020) less their approved waivers (P12-TSK-024). What is still due is this less bnpl.billing_component_paid.';

-- ---------------------------------------------------------------------------------------------
-- What a billing's component has had settled, replaced: V013's - every payer's and every refund's - less the negating rows
-- of the reversed repayments' due-item lines.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION bnpl.billing_component_paid(billing uuid, paid_component text)
    RETURNS bigint
    LANGUAGE sql
    STABLE
AS $$
    SELECT ((SELECT coalesce(sum(a.amount_minor), 0) FROM bnpl.bnpl_credit_balance_allocation a
              WHERE a.billing_id = billing AND a.component = paid_component)
          + (SELECT coalesce(sum(r.amount_minor), 0) FROM bnpl.bnpl_repayment_allocation r
               JOIN bnpl.bnpl_billing b ON b.instalment_id = r.instalment_id
              WHERE b.id = billing AND r.target = 'DUE_ITEM' AND r.component = paid_component)
          - (SELECT coalesce(sum(n.amount_minor), 0) FROM bnpl.bnpl_repayment_allocation_reversal n
               JOIN bnpl.bnpl_repayment_allocation r ON r.id = n.allocation_id
               JOIN bnpl.bnpl_billing b ON b.instalment_id = r.instalment_id
              WHERE b.id = billing AND r.target = 'DUE_ITEM' AND r.component = paid_component)
          + (SELECT coalesce(sum(CASE paid_component WHEN 'PRINCIPAL' THEN x.principal_cancelled_minor
                                                     WHEN 'INTEREST' THEN x.interest_released_minor
                                                     WHEN 'FEES' THEN x.late_fee_released_minor
                                                     ELSE 0 END), 0)
               FROM bnpl.bnpl_refund_adjustment x
               JOIN bnpl.bnpl_billing b ON b.instalment_id = x.instalment_id
              WHERE b.id = billing AND x.kind = 'BILLED_UNPAID'))::bigint
$$;

COMMENT ON FUNCTION bnpl.billing_component_paid(uuid, text) IS
    'What a billing''s component (FEES, INTEREST, PRINCIPAL) has had settled - by every payer (credit-balance applications, P12-TSK-015; repayments, P12-TSK-018) less what an approved reversal un-paid (the negating rows, P12-TSK-024), and by every refund''s cancellation of its principal, release of its interest (P12-TSK-021) and release of its late fees (OQ-14, P12-TSK-020). What is still due is billed less this.';

-- ---------------------------------------------------------------------------------------------
-- What of an assessment may still be forgiven: its fee's unpaid remainder (billed less settled, through the one pair of
-- sums every reader shares) and never more than was never forgiven (assessed less approved waivers less released) - the
-- Phase 11 gate's R3. One rule, read by the proposal's and the decision's triggers.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION bnpl.fee_waiver_room(assessment uuid)
    RETURNS bigint
    LANGUAGE sql
    STABLE
AS $$
    SELECT LEAST(
        bnpl.billing_component_billed(b.id, 'FEES') - bnpl.billing_component_paid(b.id, 'FEES'),
        f.amount_minor
            - (SELECT coalesce(sum(w.amount_minor), 0) FROM bnpl.bnpl_waiver w
                 JOIN bnpl.bnpl_waiver_decision d ON d.waiver_id = w.id AND d.outcome = 'APPROVED'
                WHERE w.fee_assessment_id = f.id)
            - (SELECT coalesce(sum(x.late_fee_released_minor), 0) FROM bnpl.bnpl_refund_adjustment x
                WHERE x.instalment_id = f.instalment_id AND x.kind = 'BILLED_UNPAID'))::bigint
      FROM bnpl.bnpl_fee_assessment f
      JOIN bnpl.bnpl_billing b ON b.instalment_id = f.instalment_id
     WHERE f.id = assessment
$$;

COMMENT ON FUNCTION bnpl.fee_waiver_room(uuid) IS
    'What of a BNPL fee assessment may still be waived (P12-TSK-024; the Phase 11 gate''s R3): least(its instalment''s FEES billed less settled, the assessment less its approved waivers less the refunds'' releases on its instalment) - so nothing released, paid or waived is ever forgiven again. Read under B4.';

CREATE OR REPLACE FUNCTION bnpl.bnpl_waiver_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    obligation bnpl.bnpl_obligation%ROWTYPE;
    assessment bnpl.bnpl_fee_assessment%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL waiver is a born-once fact (P12-TSK-024): its decision is bnpl_waiver_decision';
    END IF;
    -- (B4) The obligation, for every writer: an ACTIVE one, under its agreement version in force.
    SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id FOR UPDATE;
    IF NOT FOUND OR obligation.status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'a BNPL waiver is proposed only on an ACTIVE obligation (P12-TSK-024, INV-LIFE-04)';
    END IF;
    IF NEW.agreement_id <> obligation.agreement_id
            OR NEW.agreement_version <> (SELECT max(x.version) FROM bnpl.bnpl_agreement x
                                          WHERE x.obligation_id = NEW.obligation_id) THEN
        RAISE EXCEPTION 'a BNPL waiver names its obligation''s agreement version in force (P12-TSK-024)';
    END IF;
    SELECT f.* INTO assessment FROM bnpl.bnpl_fee_assessment f WHERE f.id = NEW.fee_assessment_id;
    IF assessment.id IS NULL OR NEW.currency <> assessment.currency OR NEW.scale <> assessment.scale THEN
        RAISE EXCEPTION 'a BNPL waiver is in its assessment''s currency and scale (P12-TSK-024)';
    END IF;
    -- A proposal beyond what may still be forgiven is never born (bnpl.WaiverExceedsDue at the domain).
    IF NEW.amount_minor > coalesce(bnpl.fee_waiver_room(NEW.fee_assessment_id), 0) THEN
        RAISE EXCEPTION 'a BNPL waiver is bounded by what its fee still has due and was never forgiven (P12-TSK-024, INV-REV-02, the Phase 11 gate''s R3)';
    END IF;
    -- One proposal awaiting a decision per assessment: a second is bnpl.ProposalPending.
    IF EXISTS (SELECT 1 FROM bnpl.bnpl_waiver w
                WHERE w.fee_assessment_id = NEW.fee_assessment_id
                  AND NOT EXISTS (SELECT 1 FROM bnpl.bnpl_waiver_decision d WHERE d.waiver_id = w.id)) THEN
        RAISE EXCEPTION 'a BNPL waiver of this fee already awaits a decision (P12-TSK-024)';
    END IF;
    NEW.proposed_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER bnpl_waiver_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON bnpl.bnpl_waiver
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_waiver_is_born_once();

CREATE TRIGGER bnpl_waiver_is_never_truncated
    BEFORE TRUNCATE ON bnpl.bnpl_waiver
    FOR EACH STATEMENT
    EXECUTE FUNCTION bnpl.acceptance_rows_are_never_truncated();

CREATE OR REPLACE FUNCTION bnpl.bnpl_waiver_decision_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    proposal bnpl.bnpl_waiver%ROWTYPE;
    obligation_status text;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL waiver decision is immutable from insert, for every role (P12-TSK-024, INV-HIST-01)';
    END IF;
    SELECT w.* INTO proposal FROM bnpl.bnpl_waiver w WHERE w.id = NEW.waiver_id;
    IF proposal.id IS NULL THEN
        RAISE EXCEPTION 'a BNPL waiver decision decides a waiver (P12-TSK-024)';
    END IF;
    -- (B4) The obligation, for every writer: the bound below is judged under the lock every payer of the fee holds.
    SELECT o.status INTO obligation_status FROM bnpl.bnpl_obligation o WHERE o.id = proposal.obligation_id FOR UPDATE;
    IF NEW.outcome = 'APPROVED' THEN
        IF obligation_status IS DISTINCT FROM 'ACTIVE' THEN
            RAISE EXCEPTION 'a BNPL waiver is approved only on an ACTIVE obligation - a closed obligation is terminal (P12-TSK-024, INV-LIFE-04)';
        END IF;
        -- Bounded by what may still be forgiven, from the rows (bnpl.WaiverExceedsDue at the domain): this decision is
        -- not yet counted by billing_component_billed or by the room's approved waivers.
        IF proposal.amount_minor > coalesce(bnpl.fee_waiver_room(proposal.fee_assessment_id), 0) THEN
            RAISE EXCEPTION 'a BNPL waiver is bounded by what its fee still has due and was never forgiven (P12-TSK-024, INV-REV-02, the Phase 11 gate''s R3)';
        END IF;
    END IF;
    NEW.decided_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER bnpl_waiver_decision_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON bnpl.bnpl_waiver_decision
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_waiver_decision_is_born_once();

CREATE TRIGGER bnpl_waiver_decision_is_never_truncated
    BEFORE TRUNCATE ON bnpl.bnpl_waiver_decision
    FOR EACH STATEMENT
    EXECUTE FUNCTION bnpl.acceptance_rows_are_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The grants: the reversal's decision columns beside its status (the decision instant is the trigger's - stamped, never
-- granted); the negating rows, the waiver and its decision SELECT and INSERT only. Never DELETE, never TRUNCATE.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON bnpl.bnpl_repayment_reversal TO finapp_app;
GRANT UPDATE (status, decided_by, decided_by_type, decision_reason, journal_entry_id, value_date)
    ON bnpl.bnpl_repayment_reversal TO finapp_app;
GRANT SELECT, INSERT ON bnpl.bnpl_repayment_allocation_reversal TO finapp_app;
GRANT SELECT, INSERT ON bnpl.bnpl_waiver TO finapp_app;
GRANT SELECT, INSERT ON bnpl.bnpl_waiver_decision TO finapp_app;
