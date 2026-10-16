-- The order of facts floors every principal value date, and the decisions are stamped after their locks (the Phase 11
-- completion gate's repairs R1 and A2-F3; ADR-0063 decision 2, the V019 payout precedent; INV-LND-02, INV-LND-10). The
-- number is V030: V029 is the gate's first repair set (p11-gate-repair-1), which this runs after (Flyway outOfOrder =
-- false); no function below is one V029 replaces.
--
-- 1. A principal-moving fact is never value-dated a business date the record already closed (R1 - A1-F1 = A2-F4 =
--    A3-I1). The accrual of date d reads the principal lines valued on or before d (V010) and is born only once d has
--    ended on the database clock (INV-LND-10); a statement states every line valued before its cycle end (V017). V028's
--    ranks admitted a repayment's or a draw's value date anywhere in [business_date_earliest, business_date_latest] - the
--    dates the clock showed THIS transaction. After a backward step across a midnight the clock can show a date the
--    record has already passed: d accrued in a transaction just after d + 1's midnight, the clock stepped back, and a
--    repayment then dated d - its principal missing from d's base for ever (a day's interest over-charged), or a draw
--    dated d (a day under-charged). The order of facts outranks the clock (ADR-0063 decision 2): the record says d has
--    ended, so d is closed to principal. lending.principal_value_floor(loan) names the first date the record leaves open
--    to a principal fact - the day after the latest accrual, and for a line the latest statement's cycle end (no line
--    valued before it may join a stated cycle; a payoff's own early statement excluded, the line closing in that
--    transaction) - and both ranks now judge against it: never before it, and never after the later of the clock's
--    latest date and it. The domain values every principal fact max(today, floor), read under L3 (LoanStore
--    #principalValueDate). On a clock that did not step the floor is at most today, so nothing changes.
--
-- 2. The terms version's decision and retirement (V003) and an amendment's decision (V023) were stamped with
--    transaction_timestamp() - the instant the transaction BEGAN, before it waited on the lock (the predecessor's row
--    FOR UPDATE behind every origination's FOR SHARE pin; L3). An offer made under the predecessor and committed while
--    the activation waited stood after the predecessor's effective_to, so "the version in force at the offer's instant"
--    named the successor (GET terms?at=). Each is now stamped clock_timestamp() - the instant written after the lock - as
--    V025 stamps the waiver's and the reversal's decisions; the successor's effective_from is still exactly the retired
--    predecessor's effective_to (the commit-time beside-its-successor check).
--
-- Replaced, each its latest predecessor's body with exactly the change above: lending.credit_line_draw_is_born_once
-- (V028), lending.repayment_is_born_once (V028), lending.loan_terms_version_permits_only_machine_edges (V003),
-- lending.loan_amendment_decision_is_born_once (V023). New: lending.principal_value_floor. Migrator-owned; like their
-- predecessors schema-qualified throughout and invoker's rights, so no search_path is set. Nothing here writes money,
-- adds a column or grants anything.

-- ---------------------------------------------------------------------------------------------
-- The first date the record leaves open to a principal-moving fact.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION lending.principal_value_floor(account uuid)
    RETURNS date
    LANGUAGE sql
    STABLE
AS $$
    SELECT GREATEST(
        (SELECT max(a.accrual_date) + 1 FROM lending.interest_accrual a WHERE a.loan_id = account),
        (SELECT max(s.cycle_end) FROM lending.credit_line_statement s
          WHERE s.loan_id = account
            AND NOT EXISTS (SELECT 1 FROM lending.payoff_execution e WHERE e.loan_id = account)))
$$;

COMMENT ON FUNCTION lending.principal_value_floor(uuid) IS
    'The first business date the record leaves open to a principal-moving fact of the account (the Phase 11 gate''s R1, V030; ADR-0063 decision 2): the day after its latest accrual, and for a credit line its latest statement''s cycle end - the payoff''s own early statement excluded. NULL when neither exists. A repayment or a draw is never value-dated before it, whatever the database clock shows after a backward step.';

-- ---------------------------------------------------------------------------------------------
-- lending.credit_line_draw_is_born_once, replaced: V028's body and the floor alone.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION lending.credit_line_draw_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    account lending.loan%ROWTYPE;
    zone text;
    agreement lending.loan_agreement%ROWTYPE;
    value_floor date;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a credit line draw is immutable from insert, for every role (P11-TSK-021, INV-LND-14)';
    END IF;
    -- An ACTIVE credit line, and its pinned servicing zone.
    SELECT l.* INTO account FROM lending.loan l WHERE l.id = NEW.loan_id;
    SELECT t.servicing_zone INTO zone
      FROM lending.loan l
      JOIN lending.loan_offer o ON o.id = l.offer_id
      JOIN lending.loan_terms t ON t.terms_version_id = o.terms_version_id
     WHERE l.id = NEW.loan_id;
    IF account.id IS NULL OR account.kind <> 'REVOLVING' OR account.status <> 'ACTIVE' OR zone IS NULL THEN
        RAISE EXCEPTION 'a draw is made only on an ACTIVE credit line (P11-TSK-021, INV-LND-14)';
    END IF;
    -- Under the line's governing agreement version - its highest - in its currency and scale, never above its limit.
    SELECT a.* INTO agreement FROM lending.loan_agreement a
     WHERE a.id = NEW.agreement_id AND a.loan_id = NEW.loan_id AND a.version = NEW.agreement_version;
    IF agreement.id IS NULL
            OR NEW.agreement_version <> (SELECT max(x.version) FROM lending.loan_agreement x WHERE x.loan_id = NEW.loan_id)
            OR NEW.currency <> agreement.currency OR NEW.scale <> agreement.scale
            OR NEW.amount_minor > agreement.amount_minor THEN
        RAISE EXCEPTION 'a draw names its line''s governing agreement version, in its currency and scale, and never exceeds its limit (P11-TSK-021, INV-LND-14)';
    END IF;
    -- The value date is this transaction's business date in the pinned servicing zone (A10, INV-LND-10): read by the step
    -- after its locks, so between the transaction's start and this statement - P11-TSK-036: in whichever order the clock
    -- read the two, which a clock stepped back across a midnight reverses (business_date_earliest / business_date_latest).
    -- The Phase 11 gate (R1): never a date the line's record has closed - the floor outranks the clock (ADR-0063 decision
    -- 2), so a step back across a midnight dates the draw on the floor, a date the clock showed before it stepped.
    value_floor := lending.principal_value_floor(NEW.loan_id);
    IF NEW.value_date < lending.business_date_earliest(zone)
            OR NEW.value_date > GREATEST(lending.business_date_latest(zone), value_floor) THEN
        RAISE EXCEPTION 'a draw''s value date is the business date of the database clock in its pinned zone (P11-TSK-021, A10)';
    END IF;
    IF NEW.value_date < value_floor THEN
        RAISE EXCEPTION 'a draw is never value-dated a business date its line''s record has closed - accrued or stated (the Phase 11 gate R1, INV-LND-02, ADR-0063 decision 2)';
    END IF;
    NEW.created_at := statement_timestamp();
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- lending.repayment_is_born_once, replaced: V028's body and the floor alone.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION lending.repayment_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    account lending.loan%ROWTYPE;
    zone text;
    agreement lending.loan_agreement%ROWTYPE;
    value_floor date;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a repayment is immutable from insert, for every role (P11-TSK-018, INV-LND-04)';
    END IF;
    -- (L3) A prepayment's account, for every writer (P11-TSK-028): it re-schedules the loan, so it is judged under the lock
    -- that serialises every act on the account - re-entrant for the domain, which holds it already.
    IF NEW.kind = 'PREPAYMENT' THEN
        PERFORM 1 FROM lending.loan l WHERE l.id = NEW.loan_id FOR UPDATE;
    END IF;
    -- An ACTIVE instalment loan, or a credit line ACTIVE or CLOSING - the customer's repayment, its automatic collection or
    -- its payoff's one repayment (P11-TSK-026) - and its pinned servicing zone. A CLOSED or CANCELLED account takes no money
    -- (INV-LND-08). A prepayment is an instalment loan's alone: a line's repayment already pays its principal down.
    SELECT l.* INTO account FROM lending.loan l WHERE l.id = NEW.loan_id;
    SELECT t.servicing_zone INTO zone
      FROM lending.loan l
      JOIN lending.loan_offer o ON o.id = l.offer_id
      JOIN lending.loan_terms t ON t.terms_version_id = o.terms_version_id
     WHERE l.id = NEW.loan_id;
    IF account.id IS NULL OR zone IS NULL
            OR NOT ((account.kind = 'INSTALMENT' AND account.status = 'ACTIVE')
                    OR (account.kind = 'REVOLVING' AND account.status IN ('ACTIVE', 'CLOSING')
                        AND NEW.kind IN ('CUSTOMER', 'AUTO_COLLECTION', 'PAYOFF'))) THEN
        RAISE EXCEPTION 'a repayment is received only by an ACTIVE instalment loan, or by a credit line ACTIVE or CLOSING (P11-TSK-018, P11-TSK-023, INV-LND-08)';
    END IF;
    -- A payoff's one repayment only beside its execution, its key the quote (P11-TSK-026): UNIQUE (loan_id, kind,
    -- request_key) and the execution's UNIQUE (loan_id) make it one per loan.
    IF NEW.kind = 'PAYOFF' AND NOT EXISTS (SELECT 1 FROM lending.payoff_execution e
                                            WHERE e.loan_id = NEW.loan_id AND e.quote_id::text = NEW.request_key) THEN
        RAISE EXCEPTION 'a payoff''s repayment is received only beside its execution, keyed by its quote (P11-TSK-026, INV-IDEM-01)';
    END IF;
    IF NEW.kind = 'PREPAYMENT' THEN
        -- A scheduled loan only: one awaiting its payout's outcome has no schedule to recalculate.
        IF NOT EXISTS (SELECT 1 FROM lending.repayment_schedule s WHERE s.loan_id = NEW.loan_id) THEN
            RAISE EXCEPTION 'a prepayment re-schedules a scheduled instalment loan (P11-TSK-028)';
        END IF;
        -- ARREARS FIRST: nothing billed may be owing - past due or due today - before principal is paid early.
        IF EXISTS (SELECT 1 FROM lending.instalment_billing b
                    CROSS JOIN (VALUES ('FEES'), ('INTEREST'), ('PRINCIPAL')) AS c (component)
                    WHERE b.loan_id = NEW.loan_id
                      AND lending.billing_component_paid(b.id, c.component)
                          < lending.billing_component_billed(b.id, c.component)) THEN
            RAISE EXCEPTION 'a prepayment waits until nothing billed is owing - arrears first (P11-TSK-028, lending.ArrearsFirst)';
        END IF;
        -- PARTIAL: strictly less than the principal not yet billed - the whole of it is the payoff's.
        IF NEW.amount_minor >= lending.principal_not_yet_billed(NEW.loan_id) THEN
            RAISE EXCEPTION 'a prepayment pays less than the principal not yet billed - the whole is the payoff''s (P11-TSK-028, INV-LND-03)';
        END IF;
    END IF;
    -- Under the loan's governing agreement version - its highest - in its currency and scale, by its pinned allocation
    -- engine (INV-LND-09).
    SELECT a.* INTO agreement FROM lending.loan_agreement a
     WHERE a.id = NEW.agreement_id AND a.loan_id = NEW.loan_id AND a.version = NEW.agreement_version;
    IF agreement.id IS NULL
            OR NEW.agreement_version <> (SELECT max(x.version) FROM lending.loan_agreement x WHERE x.loan_id = NEW.loan_id)
            OR NEW.currency <> agreement.currency OR NEW.scale <> agreement.scale
            OR NEW.allocation_engine_version <> agreement.allocation_engine_version THEN
        RAISE EXCEPTION 'a repayment names its loan''s governing agreement version, its currency and scale and its pinned allocation engine (P11-TSK-018, INV-LND-09)';
    END IF;
    -- The value date is this transaction's business date in the pinned servicing zone (ADR-0094 point 8, INV-LND-10): read
    -- by the repayment after its locks, so between the transaction's start and this statement - P11-TSK-036: in whichever
    -- order the clock read the two, which a clock stepped back across a midnight reverses (business_date_earliest/_latest).
    -- The Phase 11 gate (R1): never a date the loan's record has closed - the floor outranks the clock (ADR-0063 decision
    -- 2), so a step back across a midnight dates the repayment on the floor, a date the clock showed before it stepped.
    value_floor := lending.principal_value_floor(NEW.loan_id);
    IF NEW.value_date < lending.business_date_earliest(zone)
            OR NEW.value_date > GREATEST(lending.business_date_latest(zone), value_floor) THEN
        RAISE EXCEPTION 'a repayment''s value date is the business date of the database clock in its pinned zone (P11-TSK-018, INV-LND-10)';
    END IF;
    IF NEW.value_date < value_floor THEN
        RAISE EXCEPTION 'a repayment is never value-dated a business date its loan''s record has closed - accrued or stated (the Phase 11 gate R1, INV-LND-02, ADR-0063 decision 2)';
    END IF;
    NEW.created_at := statement_timestamp();
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- lending.loan_terms_version_permits_only_machine_edges, replaced: V003's body and the stamps after the lock alone.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION lending.loan_terms_version_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PROPOSED' OR NEW.decided_by IS NOT NULL OR NEW.decided_by_type IS NOT NULL
                OR NEW.decided_at IS NOT NULL OR NEW.decision_reason IS NOT NULL
                OR NEW.effective_from IS NOT NULL OR NEW.effective_to IS NOT NULL THEN
            RAISE EXCEPTION 'a loan terms version is born PROPOSED and undecided: no version is activated by insert (P11-TSK-005)';
        END IF;
        NEW.proposed_at := transaction_timestamp();
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a loan terms version is never deleted (P11-TSK-005, INV-HIST-04)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.product IS DISTINCT FROM OLD.product
            OR NEW.version IS DISTINCT FROM OLD.version
            OR NEW.proposed_by IS DISTINCT FROM OLD.proposed_by
            OR NEW.proposed_by_type IS DISTINCT FROM OLD.proposed_by_type
            OR NEW.proposed_at IS DISTINCT FROM OLD.proposed_at
            OR NEW.proposal_reason IS DISTINCT FROM OLD.proposal_reason
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a loan terms version''s identity is frozen (P11-TSK-005, INV-LND-05)';
    END IF;
    IF NOT ((OLD.status = 'PROPOSED' AND NEW.status IN ('ACTIVE', 'REJECTED'))
            OR (OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED')) THEN
        RAISE EXCEPTION 'a loan terms version moves PROPOSED -> ACTIVE | REJECTED, ACTIVE -> RETIRED; % -> % is not an edge (P11-TSK-005)', OLD.status, NEW.status;
    END IF;
    IF OLD.status = 'PROPOSED' THEN
        -- The Phase 11 gate (A2-F3): the instant the decision is written after the lock it waited on, never the
        -- transaction's start (V025's waiver and reversal decisions).
        NEW.decided_at := clock_timestamp();
        -- Never before the product's latest end - its predecessor's, retired first in this transaction - whatever the
        -- database clock did since (credit V014's lesson, X-TSK-017).
        NEW.effective_from := CASE WHEN NEW.status = 'ACTIVE' THEN GREATEST(transaction_timestamp(),
                (SELECT max(v.effective_to) FROM lending.loan_terms_version v WHERE v.product = NEW.product)) END;
        NEW.effective_to := NULL;
    ELSE
        IF NEW.decided_by IS DISTINCT FROM OLD.decided_by OR NEW.decided_by_type IS DISTINCT FROM OLD.decided_by_type
                OR NEW.decided_at IS DISTINCT FROM OLD.decided_at
                OR NEW.decision_reason IS DISTINCT FROM OLD.decision_reason
                OR NEW.effective_from IS DISTINCT FROM OLD.effective_from THEN
            RAISE EXCEPTION 'an activation''s decision is frozen; a retirement records only its end (P11-TSK-005)';
        END IF;
        -- Never at or before its own start, whatever the clock says (credit V014). The Phase 11 gate (A2-F3): written after
        -- the activation's lock on this row (FOR UPDATE, behind every origination's FOR SHARE pin), so after every offer
        -- made under it - never the transaction's start, before the wait; never before the start either, so the
        -- successor's effective_from (the greater of the start and this) is exactly this end.
        NEW.effective_to := GREATEST(clock_timestamp(), transaction_timestamp(), OLD.effective_from + interval '1 microsecond');
    END IF;
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- lending.loan_amendment_decision_is_born_once, replaced: V023's body and the stamps after the lock alone.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION lending.loan_amendment_decision_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    proposal lending.loan_amendment%ROWTYPE;
    account lending.loan%ROWTYPE;
    validity integer;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'an amendment decision is immutable from insert, for every role (P11-TSK-027, INV-HIST-01)';
    END IF;
    SELECT m.* INTO proposal FROM lending.loan_amendment m WHERE m.id = NEW.amendment_id;
    IF proposal.id IS NULL THEN
        RAISE EXCEPTION 'an amendment decision decides an amendment (P11-TSK-027)';
    END IF;
    -- (L3) The account, for every writer.
    SELECT l.* INTO account FROM lending.loan l WHERE l.id = proposal.loan_id FOR UPDATE;
    -- The Phase 11 gate (A2-F3): the instant written after L3, never the transaction's start (V025's decisions).
    NEW.decided_at := clock_timestamp();
    IF NEW.outcome = 'APPROVED' THEN
        -- Only on an ACTIVE loan whose agreement has not moved since the proposal: otherwise the amendment lapsed.
        IF account.status <> 'ACTIVE'
                OR proposal.amends_version <> (SELECT max(x.version) FROM lending.loan_agreement x
                                                WHERE x.loan_id = proposal.loan_id) THEN
            RAISE EXCEPTION 'an amendment is approved only on an ACTIVE loan whose agreement has not moved - it lapsed (P11-TSK-027)';
        END IF;
        -- The customer's offer: open for the pinned offer validity, on the database clock.
        SELECT t.offer_validity_days INTO validity
          FROM lending.loan_agreement a JOIN lending.loan_terms t ON t.terms_version_id = a.terms_version_id
         WHERE a.id = proposal.amends_agreement_id;
        NEW.expires_at := NEW.decided_at + make_interval(days => validity);
    ELSE
        NEW.expires_at := NULL;
    END IF;
    RETURN NEW;
END;
$$;
