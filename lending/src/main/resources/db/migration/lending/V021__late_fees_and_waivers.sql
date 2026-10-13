-- Late fees and waivers (P11-TSK-025; PHASE_11_PLAN.md sections 7.1 T-svc and T-wvr, 7.2, 7.4, 11, 12.3, 12.6 and 12.8
-- rules 4, 8, 8a and 9; ADR-0099 points 1, 2 and 4; A14, A18; INV-LND-11, INV-IDEM-02, INV-REV-02, INV-REV-04, INV-AUD-04,
-- INV-LND-08, INV-LND-01). The number is V021 - reserved on the wave 5 board (V020 is P11-TSK-024's, V022 P11-TSK-026's);
-- the backlog's "V018" is stale.
--
-- THE LATE FEE (A18). The servicing step (T-svc), under the loan row lock (L3), after the condition step and only on an
-- account it did not close, assesses the pinned late fee once per billed item (an instalment's billing, a line's statement)
-- whose own age - the business date less its due date - has reached the pinned late_fee_day (> grace: grace delays only the
-- fee, never DPD) while it still owes interest or principal; capped per account - the sum of every assessment never above the
-- pinned late_fee_cap (a fee the cap only partly admits is assessed at what it admits; a cap reached assesses nothing):
--
--     fee_assessment  - the fee: the item, the kind (LATE), the business date, the amount, the governing agreement version
--                       whose pinned terms decided it, posting rule 4's entry (DR LOAN_FEES_DUE / CR LOAN_FEE_INCOME under
--                       lending.fee:<item>:<kind> - the assessment's natural identity, so the ledger key is an arbiter of its
--                       own). UNIQUE (due_item_id, kind): one fee per item per kind, for ever.
--
-- A FEE IS PART OF WHAT ITS ITEM OWES: lending.billing_component_billed(item, 'FEES') - replaced here - is the item's assessed
-- fees less its approved fee waivers, so every payer allocates FEES first by the pinned order, the closure's rows-side rule
-- and the never-over-paid rules read it, and lending.days_past_due (V020, unchanged) keeps an item with an unpaid fee past due.
-- A line's statement CARRIES the fees assessed in its cycle into its minimum payment (credit_line_statement.fees_carried_minor,
-- held here by a trigger of its own); the fee stays due on the item it was assessed on.
--
-- THE WAIVER (A14, ADR-0099). Fees and interest are forgiven only by a reasoned, four-eyes act that posts - never an edit. A
-- holder of LOAN_SERVICE proposes; a DIFFERENT holder of LOAN_SERVICE_APPROVE approves (anyone with LOAN_SERVICE rejects, the
-- proposer's withdrawal included). The kinds:
--
--     FEE         - the unpaid remainder of an assessment: ReversalService of rule 4's entry, bounded by it (rule 8,
--                   INV-REV-02) and by what the fee still owes now;
--     FEE_REFUND  - the paid part of an assessment, refunded to the credit balance: DR LOAN_FEE_INCOME / CR LOAN_CREDIT_BALANCE
--                   (rule 8a), bounded by what was paid of it less what was refunded;
--     INTEREST    - billed interest on a billed item: DR LOAN_INTEREST_INCOME / CR LOAN_INTEREST_DUE (rule 9), bounded by what
--                   the item still owes of it. Accrued, unbilled interest is never waived (refinement: a billing's interest is
--                   exactly its period's accruals, for every writer - V012, V017).
--
-- WHY TWO INSERT-ONLY TABLES AND NO UPDATE (P11-TSK-004's capital contribution pattern): a waiver carries money, and no lending
-- table carrying money takes an UPDATE (LendingSchemaHasNoMutableMoneyTest). So the proposal is a born-once fact (waiver) and
-- its decision a born-once row (waiver_decision) - UNIQUE (waiver_id): decided once, the arbiter beneath L3; the status is
-- derived (no decision PROPOSED, else the decision's outcome). FOUR EYES FOR EVERY WRITER: the decision copies the proposer,
-- the composite foreign key (waiver_id, proposed_by) -> waiver (id, proposed_by) makes the copy the truth, and the CHECK
-- refuses an approval by its proposer. Every reason bounded and screened (V002's twin functions).
--
-- Held for EVERY writer: each of the three triggers takes the loan's row lock (L3) itself - re-entrant for the domain, which
-- holds it already - so the cap and the bounds are judged under the lock that serialises every act on the account; a fee only
-- for an ACTIVE or CLOSING account's own billed item, under its governing agreement, at most the pinned fee, the sum within the
-- cap, the item aged at least late_fee_day and still owing, dated the business date of its transaction in the pinned zone, a
-- line's never inside a cycle already stated; a waiver of its own loan's assessment or billed item, one PROPOSED per kind and
-- item; an approval only on an ACTIVE or CLOSING account, within its bound, naming its entry. INSERT-only, never truncated.

-- ---------------------------------------------------------------------------------------------
-- The fee.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE lending.fee_assessment (
    id                 uuid        NOT NULL,
    loan_id            uuid        NOT NULL,
    due_item_id        uuid        NOT NULL,
    kind               text        NOT NULL,
    business_date      date        NOT NULL,
    currency           char(3)     NOT NULL,
    scale              smallint    NOT NULL,
    amount_minor       bigint      NOT NULL,
    agreement_id       uuid        NOT NULL,
    agreement_version  integer     NOT NULL,
    journal_entry_id   uuid        NOT NULL,
    created_at         timestamptz NOT NULL,
    correlation_id     text        NOT NULL,
    CONSTRAINT fee_assessment_pk PRIMARY KEY (id),
    -- One fee per billed item per kind, for ever: ten sweepers, a duplicate tick, a re-run day - one fee (A18).
    CONSTRAINT fee_assessment_once_per_item UNIQUE (due_item_id, kind),
    -- One assessment per journal entry: rule 4's key gives one entry per item and kind.
    CONSTRAINT fee_assessment_entry_unique UNIQUE (journal_entry_id),
    -- A waiver names an assessment of its own loan (the composite key below).
    CONSTRAINT fee_assessment_of_its_loan UNIQUE (id, loan_id),
    CONSTRAINT fee_assessment_loan_fk FOREIGN KEY (loan_id) REFERENCES lending.loan (id),
    CONSTRAINT fee_assessment_agreement_fk FOREIGN KEY (agreement_id) REFERENCES lending.loan_agreement (id),
    CONSTRAINT fee_assessment_kind_is_known CHECK (kind IN ('LATE')),
    CONSTRAINT fee_assessment_currency_is_the_products CHECK (currency = 'EUR' AND scale BETWEEN 0 AND 9),
    -- A fee of nothing is no fee: a zero fee or a cap reached writes no row.
    CONSTRAINT fee_assessment_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT fee_assessment_version_numbered CHECK (agreement_version >= 1),
    CONSTRAINT fee_assessment_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

CREATE INDEX fee_assessment_by_loan ON lending.fee_assessment (loan_id, business_date);

COMMENT ON TABLE lending.fee_assessment IS
    'A fee assessed on a billed item (P11-TSK-025; A18, PHASE_11_PLAN.md sections 12.3 and 12.8 rule 4): kind LATE - the pinned late fee, once per item on the business date its own age reached the pinned late_fee_day (> grace) while it still owed interest or principal, capped per account (the sum never above the pinned late_fee_cap; a fee the cap partly admits is assessed at what it admits). Names the governing agreement version whose terms decided it and posting rule 4''s entry (DR LOAN_FEES_DUE / CR LOAN_FEE_INCOME, key lending.fee:<item>:<kind>). Joins its item''s FEES (lending.billing_component_billed), so an unpaid fee keeps its item past due. UNIQUE (due_item_id, kind). INSERT-only for every role. RESTRICTED-FINANCIAL.';

CREATE OR REPLACE FUNCTION lending.fee_assessment_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    account lending.loan%ROWTYPE;
    agreement lending.loan_agreement%ROWTYPE;
    terms lending.loan_terms%ROWTYPE;
    assessed bigint;
    owing bigint;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a fee assessment is immutable from insert, for every role (P11-TSK-025, INV-HIST-01)';
    END IF;
    -- (L3) The account, for every writer - the lock every act on it serialises on, re-entrant for the servicing step.
    SELECT l.* INTO account FROM lending.loan l WHERE l.id = NEW.loan_id FOR UPDATE;
    IF account.id IS NULL OR account.status NOT IN ('ACTIVE', 'CLOSING') THEN
        RAISE EXCEPTION 'a fee is assessed only on an ACTIVE or CLOSING account (P11-TSK-025, INV-LND-08)';
    END IF;
    IF lending.billed_item_loan(NEW.due_item_id) IS DISTINCT FROM NEW.loan_id THEN
        RAISE EXCEPTION 'a fee is assessed on a billed item of its own account (P11-TSK-025)';
    END IF;
    -- Under the governing agreement version - its highest - in its currency and scale; its pinned terms decide.
    SELECT a.* INTO agreement FROM lending.loan_agreement a
     WHERE a.id = NEW.agreement_id AND a.loan_id = NEW.loan_id AND a.version = NEW.agreement_version;
    IF agreement.id IS NULL
            OR NEW.agreement_version <> (SELECT max(x.version) FROM lending.loan_agreement x WHERE x.loan_id = NEW.loan_id)
            OR NEW.currency <> agreement.currency OR NEW.scale <> agreement.scale THEN
        RAISE EXCEPTION 'a fee names its account''s governing agreement version, in its currency and scale (P11-TSK-025, INV-LND-09)';
    END IF;
    SELECT t.* INTO terms FROM lending.loan_terms t WHERE t.terms_version_id = agreement.terms_version_id;
    -- Dated the business date of its transaction in the pinned zone - never early, never back-dated (INV-LND-10).
    IF NEW.business_date > lending.business_date(statement_timestamp(), terms.servicing_zone)
            OR NEW.business_date < lending.business_date(transaction_timestamp(), terms.servicing_zone) THEN
        RAISE EXCEPTION 'a fee is dated the business date of its transaction in the pinned zone (P11-TSK-025, INV-LND-10)';
    END IF;
    -- On the day the item's own age reaches the pinned late-fee day, or later (a missed day is caught up) - never in grace.
    IF NEW.business_date - lending.billed_item_due_date(NEW.due_item_id) < terms.late_fee_day THEN
        RAISE EXCEPTION 'a late fee is assessed only once its item''s age has reached the pinned late-fee day (P11-TSK-025, A18)';
    END IF;
    -- At most the pinned fee; the account's fees within the pinned cap.
    SELECT coalesce(sum(f.amount_minor), 0) INTO assessed FROM lending.fee_assessment f WHERE f.loan_id = NEW.loan_id;
    IF NEW.amount_minor > terms.late_fee_minor OR assessed + NEW.amount_minor > terms.late_fee_cap_minor THEN
        RAISE EXCEPTION 'a late fee is at most the pinned fee, and an account''s fees at most the pinned cap (P11-TSK-025, A18)';
    END IF;
    -- Only on an item that still owes interest or principal: a paid item is never charged late.
    SELECT sum(lending.billing_component_billed(NEW.due_item_id, c.component)
               - lending.billing_component_paid(NEW.due_item_id, c.component)) INTO owing
      FROM (VALUES ('INTEREST'), ('PRINCIPAL')) AS c (component);
    IF coalesce(owing, 0) <= 0 THEN
        RAISE EXCEPTION 'a late fee is assessed only on an item still owing interest or principal (P11-TSK-025, A18)';
    END IF;
    -- A line's fee is never dated inside a cycle already stated: each fee is carried by exactly one statement.
    IF EXISTS (SELECT 1 FROM lending.credit_line_statement s
                WHERE s.loan_id = NEW.loan_id AND s.cycle_end > NEW.business_date) THEN
        RAISE EXCEPTION 'a line''s fee is never dated inside a cycle already stated (P11-TSK-025, A24)';
    END IF;
    NEW.created_at := statement_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER fee_assessment_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON lending.fee_assessment
    FOR EACH ROW
    EXECUTE FUNCTION lending.fee_assessment_is_born_once();

CREATE TRIGGER fee_assessment_is_never_truncated
    BEFORE TRUNCATE ON lending.fee_assessment
    FOR EACH STATEMENT
    EXECUTE FUNCTION lending.loan_is_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The waiver's proposal: a born-once fact carrying the money.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE lending.waiver (
    id                 uuid        NOT NULL,
    loan_id            uuid        NOT NULL,
    kind               text        NOT NULL,
    fee_assessment_id  uuid,
    due_item_id        uuid        NOT NULL,
    currency           char(3)     NOT NULL,
    scale              smallint    NOT NULL,
    amount_minor       bigint      NOT NULL,
    agreement_version  integer     NOT NULL,
    proposed_by        text        NOT NULL,
    proposed_by_type   text        NOT NULL,
    proposed_at        timestamptz NOT NULL,
    proposal_reason    text        NOT NULL,
    correlation_id     text        NOT NULL,
    CONSTRAINT waiver_pk PRIMARY KEY (id),
    -- The decision's composite key pins its copy of the proposer to this row's.
    CONSTRAINT waiver_proposer_pinned UNIQUE (id, proposed_by),
    CONSTRAINT waiver_loan_fk FOREIGN KEY (loan_id) REFERENCES lending.loan (id),
    -- A fee waiver names an assessment of its own loan.
    CONSTRAINT waiver_assessment_fk FOREIGN KEY (fee_assessment_id, loan_id)
        REFERENCES lending.fee_assessment (id, loan_id),
    CONSTRAINT waiver_kind_is_known CHECK (kind IN ('FEE', 'FEE_REFUND', 'INTEREST')),
    -- A fee's waiver or refund names its assessment; an interest waiver names a billed item alone.
    CONSTRAINT waiver_target_by_kind CHECK ((kind = 'INTEREST') = (fee_assessment_id IS NULL)),
    CONSTRAINT waiver_currency_is_the_products CHECK (currency = 'EUR' AND scale BETWEEN 0 AND 9),
    CONSTRAINT waiver_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT waiver_version_numbered CHECK (agreement_version >= 1),
    CONSTRAINT waiver_actor_type_known CHECK (proposed_by_type IN ('SYSTEM', 'CUSTOMER', 'EMPLOYEE', 'SERVICE', 'MERCHANT')),
    CONSTRAINT waiver_actor_bounded CHECK (char_length(proposed_by) BETWEEN 1 AND 200),
    CONSTRAINT waiver_proposal_reasoned CHECK (char_length(proposal_reason) BETWEEN 1 AND 1000),
    CONSTRAINT waiver_proposal_reason_no_instrument_shape CHECK (NOT lending.holds_instrument_shape(proposal_reason)),
    CONSTRAINT waiver_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

CREATE INDEX waiver_by_loan ON lending.waiver (loan_id, proposed_at);
CREATE INDEX waiver_by_item ON lending.waiver (due_item_id);
CREATE INDEX waiver_by_assessment ON lending.waiver (fee_assessment_id);

COMMENT ON TABLE lending.waiver IS
    'A waiver''s proposal (P11-TSK-025; A14, ADR-0099, INV-LND-11): kind FEE (an assessment''s unpaid remainder - rule 8, ReversalService of rule 4''s entry, bounded), FEE_REFUND (an assessment''s paid part to the credit balance - rule 8a DR LOAN_FEE_INCOME / CR LOAN_CREDIT_BALANCE) or INTEREST (billed interest on a billed item - rule 9 DR LOAN_INTEREST_INCOME / CR LOAN_INTEREST_DUE); the amount, the governing agreement version, the proposer (a holder of LOAN_SERVICE) and the reason. Born once, INSERT-only for every role; its decision is waiver_decision (none: PROPOSED). One PROPOSED waiver per loan, kind and billed item. Amount RESTRICTED-FINANCIAL; the reason a person''s prose, screened for instrument shapes.';

CREATE OR REPLACE FUNCTION lending.waiver_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    account lending.loan%ROWTYPE;
    agreement lending.loan_agreement%ROWTYPE;
    assessment lending.fee_assessment%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a waiver is a born-once fact (P11-TSK-025): its decision is waiver_decision';
    END IF;
    -- (L3) The account, for every writer.
    SELECT l.* INTO account FROM lending.loan l WHERE l.id = NEW.loan_id FOR UPDATE;
    IF account.id IS NULL OR account.status NOT IN ('ACTIVE', 'CLOSING') THEN
        RAISE EXCEPTION 'a waiver is proposed only on an ACTIVE or CLOSING account (P11-TSK-025, INV-LND-08)';
    END IF;
    SELECT a.* INTO agreement FROM lending.loan_agreement a
     WHERE a.loan_id = NEW.loan_id
       AND a.version = (SELECT max(x.version) FROM lending.loan_agreement x WHERE x.loan_id = NEW.loan_id);
    IF NEW.agreement_version <> agreement.version OR NEW.currency <> agreement.currency
            OR NEW.scale <> agreement.scale THEN
        RAISE EXCEPTION 'a waiver names its account''s governing agreement version, in its currency and scale (P11-TSK-025)';
    END IF;
    IF NEW.kind = 'INTEREST' THEN
        IF lending.billed_item_loan(NEW.due_item_id) IS DISTINCT FROM NEW.loan_id THEN
            RAISE EXCEPTION 'an interest waiver names a billed item of its own account (P11-TSK-025)';
        END IF;
    ELSE
        SELECT f.* INTO assessment FROM lending.fee_assessment f WHERE f.id = NEW.fee_assessment_id;
        IF assessment.id IS NULL OR assessment.loan_id <> NEW.loan_id OR assessment.due_item_id <> NEW.due_item_id THEN
            RAISE EXCEPTION 'a fee waiver names an assessment of its own account, on its item (P11-TSK-025)';
        END IF;
    END IF;
    -- One proposal awaiting a decision per kind and item: a second is lending.ProposalPending.
    IF EXISTS (SELECT 1 FROM lending.waiver w
                WHERE w.loan_id = NEW.loan_id AND w.kind = NEW.kind AND w.due_item_id = NEW.due_item_id
                  AND NOT EXISTS (SELECT 1 FROM lending.waiver_decision d WHERE d.waiver_id = w.id)) THEN
        RAISE EXCEPTION 'a waiver of this kind on this item already awaits a decision (P11-TSK-025)';
    END IF;
    NEW.proposed_at := transaction_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER waiver_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON lending.waiver
    FOR EACH ROW
    EXECUTE FUNCTION lending.waiver_is_born_once();

CREATE TRIGGER waiver_is_never_truncated
    BEFORE TRUNCATE ON lending.waiver
    FOR EACH STATEMENT
    EXECUTE FUNCTION lending.loan_is_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The waiver's decision: born once.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE lending.waiver_decision (
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
    CONSTRAINT waiver_decision_pk PRIMARY KEY (id),
    CONSTRAINT waiver_decision_waiver_fk FOREIGN KEY (waiver_id, proposed_by) REFERENCES lending.waiver (id, proposed_by),
    -- Decided once: ten approvers, one decision - the arbiter beneath L3.
    CONSTRAINT waiver_decision_once UNIQUE (waiver_id),
    -- One decision per journal entry: each approval posts its own entry.
    CONSTRAINT waiver_decision_entry_unique UNIQUE (journal_entry_id),
    CONSTRAINT waiver_decision_outcome_is_known CHECK (outcome IN ('APPROVED', 'REJECTED')),
    -- FOUR EYES, for every writer (INV-AUD-04, INV-LND-11): whoever approved a waiver did not propose it. A rejection may
    -- be the proposer's withdrawal.
    CONSTRAINT waiver_decision_four_eyes CHECK (outcome <> 'APPROVED' OR decided_by <> proposed_by),
    -- The approval and only the approval posts: it names its entry (rule 8, 8a or 9).
    CONSTRAINT waiver_decision_approval_posts CHECK ((outcome = 'APPROVED') = (journal_entry_id IS NOT NULL)),
    CONSTRAINT waiver_decision_actor_type_known CHECK (
        decided_by_type IN ('SYSTEM', 'CUSTOMER', 'EMPLOYEE', 'SERVICE', 'MERCHANT')),
    CONSTRAINT waiver_decision_actor_bounded CHECK (
        char_length(decided_by) BETWEEN 1 AND 200 AND char_length(proposed_by) BETWEEN 1 AND 200),
    CONSTRAINT waiver_decision_reasoned CHECK (char_length(decision_reason) BETWEEN 1 AND 1000),
    CONSTRAINT waiver_decision_reason_no_instrument_shape CHECK (NOT lending.holds_instrument_shape(decision_reason)),
    CONSTRAINT waiver_decision_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

COMMENT ON TABLE lending.waiver_decision IS
    'A waiver''s decision (P11-TSK-025; ADR-0099, INV-LND-11, INV-AUD-04): APPROVED by a holder of LOAN_SERVICE_APPROVE who did not propose it (four-eyes CHECK over the proposer copied and pinned by the composite foreign key) - naming the entry it posted (rule 8, 8a or 9) - or REJECTED (the proposer''s withdrawal included), with the decider''s reason. UNIQUE (waiver_id): decided once. An approval only on an ACTIVE or CLOSING account and within the waiver''s bound by what is due now. INSERT-only for every role.';

CREATE OR REPLACE FUNCTION lending.waiver_decision_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    proposal lending.waiver%ROWTYPE;
    loan_status text;
    room bigint;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a waiver decision is immutable from insert, for every role (P11-TSK-025, INV-HIST-01)';
    END IF;
    SELECT w.* INTO proposal FROM lending.waiver w WHERE w.id = NEW.waiver_id;
    IF proposal.id IS NULL THEN
        RAISE EXCEPTION 'a waiver decision decides a waiver (P11-TSK-025)';
    END IF;
    -- (L3) The account, for every writer: the bound below is judged under the lock every payer of the item holds.
    SELECT l.status INTO loan_status FROM lending.loan l WHERE l.id = proposal.loan_id FOR UPDATE;
    IF NEW.outcome = 'APPROVED' THEN
        IF loan_status NOT IN ('ACTIVE', 'CLOSING') THEN
            RAISE EXCEPTION 'a waiver is approved only on an ACTIVE or CLOSING account - a closed account is terminal (P11-TSK-025, INV-LND-08)';
        END IF;
        -- Bounded by what is due now, from the rows (lending.WaiverExceedsDue at the domain): this decision is not yet
        -- counted by billing_component_billed.
        IF proposal.kind = 'FEE' THEN
            room := lending.billing_component_billed(proposal.due_item_id, 'FEES')
                    - lending.billing_component_paid(proposal.due_item_id, 'FEES');
        ELSIF proposal.kind = 'INTEREST' THEN
            room := lending.billing_component_billed(proposal.due_item_id, 'INTEREST')
                    - lending.billing_component_paid(proposal.due_item_id, 'INTEREST');
        ELSE
            room := lending.billing_component_paid(proposal.due_item_id, 'FEES')
                    - (SELECT coalesce(sum(w.amount_minor), 0) FROM lending.waiver w
                         JOIN lending.waiver_decision d ON d.waiver_id = w.id
                        WHERE w.fee_assessment_id = proposal.fee_assessment_id AND w.kind = 'FEE_REFUND'
                          AND d.outcome = 'APPROVED');
        END IF;
        IF proposal.amount_minor > coalesce(room, 0) THEN
            RAISE EXCEPTION 'a waiver is bounded by what is due now (P11-TSK-025, INV-REV-02)';
        END IF;
    END IF;
    NEW.decided_at := transaction_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER waiver_decision_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON lending.waiver_decision
    FOR EACH ROW
    EXECUTE FUNCTION lending.waiver_decision_is_born_once();

CREATE TRIGGER waiver_decision_is_never_truncated
    BEFORE TRUNCATE ON lending.waiver_decision
    FOR EACH STATEMENT
    EXECUTE FUNCTION lending.loan_is_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- What a billed item's component billed, replaced: V017's figures, plus the item's assessed fees, less its approved fee
-- waivers (FEES) and its approved interest waivers (INTEREST). One sum every reader shares - the due state, the closure's
-- rows-side rule, the never-over-paid rules, the collection attempt and days_past_due.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION lending.billing_component_billed(billing uuid, billed_component text)
    RETURNS bigint
    LANGUAGE sql
    STABLE
AS $$
    SELECT (coalesce(
                (SELECT CASE billed_component
                            WHEN 'INTEREST' THEN b.interest_due_minor
                            WHEN 'PRINCIPAL' THEN b.principal_due_minor
                            ELSE 0 END
                   FROM lending.instalment_billing b WHERE b.id = billing),
                (SELECT CASE billed_component
                            WHEN 'INTEREST' THEN s.interest_billed_minor
                            WHEN 'PRINCIPAL' THEN s.principal_billed_minor
                            ELSE 0 END
                   FROM lending.credit_line_statement s WHERE s.id = billing))
          + (SELECT coalesce(sum(f.amount_minor), 0) FROM lending.fee_assessment f
              WHERE f.due_item_id = billing AND billed_component = 'FEES')
          - (SELECT coalesce(sum(w.amount_minor), 0) FROM lending.waiver w
               JOIN lending.waiver_decision d ON d.waiver_id = w.id AND d.outcome = 'APPROVED'
              WHERE w.due_item_id = billing
                AND ((billed_component = 'FEES' AND w.kind = 'FEE')
                     OR (billed_component = 'INTEREST' AND w.kind = 'INTEREST'))))::bigint
$$;

-- ---------------------------------------------------------------------------------------------
-- A line's statement carries the fees assessed in its cycle (A24) - the CHECK V017 held at zero gives way to this trigger.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE lending.credit_line_statement DROP CONSTRAINT credit_line_statement_no_fee_yet;

-- The fees a statement carries: the line's assessments dated in its cycle [cycle_start, cycle_end), less what fee waivers
-- approved before it forgave of them. Each fee is carried once: cycles tile the calendar, and a fee is never dated inside a
-- cycle already stated (fee_assessment_is_born_once).
CREATE OR REPLACE FUNCTION lending.credit_line_statement_carries_its_fees()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    carried bigint;
BEGIN
    SELECT (coalesce(sum(f.amount_minor), 0)
            - coalesce((SELECT sum(w.amount_minor) FROM lending.waiver w
                          JOIN lending.waiver_decision d ON d.waiver_id = w.id AND d.outcome = 'APPROVED'
                          JOIN lending.fee_assessment x ON x.id = w.fee_assessment_id
                         WHERE w.kind = 'FEE' AND x.loan_id = NEW.loan_id
                           AND x.business_date >= NEW.cycle_start AND x.business_date < NEW.cycle_end), 0))::bigint
      INTO carried
      FROM lending.fee_assessment f
     WHERE f.loan_id = NEW.loan_id AND f.business_date >= NEW.cycle_start AND f.business_date < NEW.cycle_end;
    IF NEW.fees_carried_minor <> carried THEN
        RAISE EXCEPTION 'a statement carries exactly the fees assessed in its cycle, less those waived (P11-TSK-025, A24)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER credit_line_statement_carries_its_fees
    BEFORE INSERT ON lending.credit_line_statement
    FOR EACH ROW
    EXECUTE FUNCTION lending.credit_line_statement_carries_its_fees();

-- ---------------------------------------------------------------------------------------------
-- The grants: SELECT and INSERT only - never UPDATE, never DELETE.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON lending.fee_assessment TO finapp_app;
GRANT SELECT, INSERT ON lending.waiver TO finapp_app;
GRANT SELECT, INSERT ON lending.waiver_decision TO finapp_app;
