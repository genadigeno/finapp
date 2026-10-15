-- The lending write path under a backward database clock (P11-TSK-036; ADR-0063 decision 2 and its follow-ups, the fifth
-- set of database-own stamps; INV-LND-10, INV-LND-05). The number is V028: first written as V026 (the task as
-- P11-TSK-034), renumbered when PR #9's P11-TSK-034 and P11-TSK-035 took lending V026 and V027 - it runs after both and
-- builds on them (Flyway outOfOrder = false).
--
-- The database clock is not monotonic: an NTP or hypervisor correction steps it back (measured on the local VM by up to
-- 1.7 s, ADR-0063; X-TSK-017, P11-TSK-033). Within ONE transaction a later statement's statement_timestamp() can then
-- read EARLIER than an earlier statement's, and transaction_timestamp() - fixed when the transaction began - can stand
-- after both. Two classes of rank in V005...V025 judged a value one statement read from the clock against ANOTHER single
-- reading, and refused a legitimate act whenever a step fell between the two:
--
--   1. The offering instant (V005, V006): the origination step read the clock (LoanOrigination - after its locks) and
--      the offer trigger refused offered_at > statement_timestamp() or < transaction_timestamp(). A step between the
--      read and the insert refused the origination with an opaque P0001 - the recurring "inserting a loan offer: SQLSTATE
--      P0001" failure in the lending suites' fixtures, and in production an origination rolled back and re-swept until
--      the clock caught up. The offering instant is now the insert's OWN stamp - GREATEST(statement_timestamp(),
--      transaction_timestamp()): this statement's clock, held at the transaction's start when the clock stands behind it
--      (the order of facts over the clock's reading, ADR-0063 decision 2). No writer supplies it, so none can date an
--      offer outside its transaction; the step takes it back from RETURNING and illustrates from its business date.
--
--   2. The business dates (V009, V013, V017...V024): every "never after today" rank read today as
--      business_date(statement_timestamp()) and every "never before today" rank as business_date(transaction_timestamp()),
--      while the step dated its fact by a THIRD reading, its own. A step across a midnight in the pinned zone put the two
--      on different days, so the window [date(start), date(statement)] could be empty, or exclude the step's date - a
--      repayment, a draw, a disbursement, an accrual, a billing, a statement, a collection attempt, a condition, a fee,
--      a payoff quote or execution, a schedule version or an amendment's re-scheduling refused as dated in the future or
--      the past. Two functions now name the transaction's business date as the range it is:
--
--          lending.business_date_earliest(zone)  the earlier of the transaction's start's and this statement's dates
--          lending.business_date_latest(zone)    the later of the two
--
--      and every rank judges against them - a ceiling against the latest, a floor against the earliest. On a clock that
--      did not step both are the one date the ranks read before, so nothing changes; on a clock that stepped they admit
--      exactly the dates the database clock showed this transaction, and a date beyond every reading - a fact genuinely
--      dated in the future or the past - is still refused. The step's own read lies between the transaction's start and
--      this statement, so a single step across a midnight can no longer put it outside; two midnight crossings inside one
--      transaction (one forward, one back) remain a refusal, rolled back and re-run - recorded, never assumed away.
--
-- Kept, deliberately (each judged, not stamped - ADR-0063 decision 4): the expiry and deadline judgements (an offer's
-- expires_at at decline, acceptance and expiry, the loan's birth beside an unexpired offer, an amendment decision's
-- expires_at, a disbursement's deadline_at) - each either judged in the same statement as its conditional write, or made
-- MORE lenient by a step back, or refused fail-closed and re-run; the permit claims (next_*_at <= statement_timestamp()) -
-- a permit stamped at birth is due a step later, a liveness delay of at most the step; and the birth stamps (created_at,
-- opened_at, closed_at, decided_at, ...), which no rank orders (X-TSK-006's reading of the class).
--
-- Replaced, each its latest predecessor's body with exactly the change above: lending.loan_offer_permits_only_machine_edges
-- (V026 - P11-TSK-034's, its customer-side expiry in LNEXP kept), lending.accrual_start_is_born_once (V019), lending.collection_attempt_is_born_once (V018),
-- lending.credit_line_draw_is_born_once (V013), lending.credit_line_statement_is_born_once (V022),
-- lending.fee_assessment_is_born_once (V021), lending.instalment_billing_is_born_once (V024),
-- lending.interest_accrual_is_born_once (V017), lending.loan_condition_event_is_born_once (V020),
-- lending.loan_disbursement_permits_only_machine_edges (V009), lending.payoff_execution_is_born_once (V022),
-- lending.payoff_quote_is_born_once (V022), lending.repayment_is_born_once (V024),
-- lending.repayment_schedule_is_born_once (V024), lending.loan_amendment_rescheduling_is_born_once (V023),
-- lending.repayment_schedule_continues_its_period (V023). Migrator-owned; like their predecessors schema-qualified
-- throughout and invoker's rights, so no search_path is set. Nothing here writes money, adds a column or grants anything.

-- ---------------------------------------------------------------------------------------------
-- The transaction's business date, as the range a stepped clock makes it.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION lending.business_date_earliest(servicing_zone text)
    RETURNS date
    LANGUAGE sql
    STABLE
AS $$
    SELECT lending.business_date(LEAST(statement_timestamp(), transaction_timestamp()), servicing_zone)
$$;

COMMENT ON FUNCTION lending.business_date_earliest(text) IS
    'The earliest business date in the pinned zone the database clock has shown this transaction - its start''s or this statement''s, whichever is earlier (P11-TSK-036, V028): every "never before today" rank''s today. One date on a clock that did not step.';

CREATE OR REPLACE FUNCTION lending.business_date_latest(servicing_zone text)
    RETURNS date
    LANGUAGE sql
    STABLE
AS $$
    SELECT lending.business_date(GREATEST(statement_timestamp(), transaction_timestamp()), servicing_zone)
$$;

COMMENT ON FUNCTION lending.business_date_latest(text) IS
    'The latest business date in the pinned zone the database clock has shown this transaction - its start''s or this statement''s, whichever is later (P11-TSK-036, V028): every "never after today" rank''s today. One date on a clock that did not step.';

COMMENT ON COLUMN lending.loan_offer.offered_at IS
    'The offering instant (P11-TSK-011): the offer insert''s own database stamp, GREATEST(statement_timestamp(), transaction_timestamp()) - never a writer''s value, never a reading an earlier statement made (P11-TSK-036, V028). The expiry and the illustration''s first day are taken from it.';

-- ---------------------------------------------------------------------------------------------
-- lending.loan_offer_permits_only_machine_edges, replaced: V026's body (P11-TSK-034's) and the P11-TSK-036 change alone.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION lending.loan_offer_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    application lending.loan_application%ROWTYPE;
    validity_days integer;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'OFFERED' OR NEW.closed_at IS NOT NULL THEN
            RAISE EXCEPTION 'a loan offer is born OFFERED and open (P11-TSK-011)';
        END IF;
        -- Born under its application's row lock, from AWAITING_DECISION, for the same party and product.
        SELECT * INTO application FROM lending.loan_application a WHERE a.id = NEW.application_id;
        IF NOT FOUND OR application.status <> 'AWAITING_DECISION'
                OR application.party_id <> NEW.party_id OR application.product <> NEW.product THEN
            RAISE EXCEPTION 'a loan offer is born for its own party''s application AWAITING_DECISION, of the same product (P11-TSK-011)';
        END IF;
        -- On the product's terms version ACTIVE now - the one the step pinned FOR SHARE (INV-HIST-04).
        SELECT t.offer_validity_days INTO validity_days
          FROM lending.loan_terms_version v JOIN lending.loan_terms t ON t.terms_version_id = v.id
         WHERE v.id = NEW.terms_version_id AND v.product = NEW.product AND v.status = 'ACTIVE';
        IF NOT FOUND THEN
            RAISE EXCEPTION 'a loan offer pins its product''s ACTIVE terms version (P11-TSK-011, INV-HIST-04)';
        END IF;
        -- The offering instant is a database instant of this transaction, after the step's locks, so the illustrative
        -- schedule's first day and the expiry are one clock's. P11-TSK-036: stamped HERE, by this statement's clock - never
        -- a value an earlier statement read, which a database clock stepped back between the two put after this clock (or
        -- before the transaction's start) and V005/V006 refused - and never before the transaction began (ADR-0063
        -- decision 2, the order of facts over the clock's reading). The step takes it back from RETURNING.
        NEW.offered_at := GREATEST(statement_timestamp(), transaction_timestamp());
        -- The expiry on the database clock: the earlier of the decision's validity and the pinned version's offer
        -- validity (PHASE_11_PLAN.md section 5.2) - stamped here, never written by the step.
        NEW.expires_at := LEAST(NEW.decision_valid_until, NEW.offered_at + make_interval(days => validity_days));
        IF NEW.expires_at <= NEW.offered_at THEN
            RAISE EXCEPTION 'a loan offer is never born expired: the decision has lapsed (P11-TSK-011)';
        END IF;
        -- The expiry sweep's permit: due at the expiry.
        NEW.next_step_at := NEW.expires_at;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a loan offer is never deleted (P11-TSK-011, INV-HIST-01)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.application_id IS DISTINCT FROM OLD.application_id
            OR NEW.party_id IS DISTINCT FROM OLD.party_id OR NEW.product IS DISTINCT FROM OLD.product
            OR NEW.decision_id IS DISTINCT FROM OLD.decision_id
            OR NEW.terms_version_id IS DISTINCT FROM OLD.terms_version_id
            OR NEW.offered_at IS DISTINCT FROM OLD.offered_at
            OR NEW.decision_valid_until IS DISTINCT FROM OLD.decision_valid_until
            OR NEW.expires_at IS DISTINCT FROM OLD.expires_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a loan offer''s identity, pin and expiry are frozen from birth (P11-TSK-011, INV-LND-05)';
    END IF;
    -- A permit-only move: only an open offer has a next step, the move moves it, and nothing but the permit moves.
    IF NEW.status = OLD.status THEN
        IF OLD.status <> 'OFFERED' OR NEW.closed_at IS DISTINCT FROM OLD.closed_at
                OR NEW.next_step_at IS NOT DISTINCT FROM OLD.next_step_at THEN
            RAISE EXCEPTION 'only an OFFERED offer has a permit to move, and only its permit; % is not (P11-TSK-011)', OLD.status;
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status <> 'OFFERED' THEN
        RAISE EXCEPTION 'a % loan offer is terminal (P11-TSK-011)', OLD.status;
    END IF;
    -- The three clock-sided edges: the customer's two while expires_at > the database clock, the expiry once <=  -
    -- exactly one of them at the boundary instant. The customer's side refused past the instant in its own SQLSTATE
    -- (P11-TSK-034), as the loan's birth is.
    IF NEW.status IN ('DECLINED', 'ACCEPTED') THEN
        IF OLD.expires_at <= statement_timestamp() THEN
            RAISE EXCEPTION 'an expired loan offer cannot be % - it expires (P11-TSK-011, P11-TSK-012)', lower(NEW.status)
                USING ERRCODE = 'LNEXP';
        END IF;
        -- ACCEPTED only beside its loan (P11-TSK-012): the loan is born first, in the same transaction, and the deferred
        -- check holds its agreement and evidence beside it at commit.
        IF NEW.status = 'ACCEPTED' AND NOT EXISTS (SELECT 1 FROM lending.loan l WHERE l.offer_id = NEW.id) THEN
            RAISE EXCEPTION 'a loan offer is ACCEPTED only beside its loan (P11-TSK-012)';
        END IF;
    ELSIF NEW.status = 'EXPIRED' THEN
        IF OLD.expires_at > statement_timestamp() THEN
            RAISE EXCEPTION 'a loan offer expires only once expires_at <= the database clock (P11-TSK-011)';
        END IF;
    ELSE
        RAISE EXCEPTION 'a loan offer moves OFFERED -> ACCEPTED | DECLINED | EXPIRED; % -> % is not an edge (P11-TSK-011)', OLD.status, NEW.status;
    END IF;
    NEW.closed_at := statement_timestamp();
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- lending.accrual_start_is_born_once, replaced: V019's body and the P11-TSK-036 change alone.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION lending.accrual_start_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    disbursement lending.loan_disbursement%ROWTYPE;
    payout lending.loan_payout%ROWTYPE;
    zone text;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'an accrual start is immutable from insert, for every role (P11-TSK-014)';
    END IF;
    IF NEW.origin = 'DISBURSEMENT' THEN
        -- Path W: beside its instalment loan ACTIVE and its wallet disbursement POSTED, on that entry's value date.
        SELECT d.* INTO disbursement
          FROM lending.loan_disbursement d JOIN lending.loan l ON l.id = d.loan_id
         WHERE d.loan_id = NEW.loan_id AND l.kind = 'INSTALMENT' AND l.status = 'ACTIVE';
        IF NOT FOUND OR disbursement.status <> 'POSTED' OR disbursement.payout_method_id IS NOT NULL
                OR NEW.start_date <> disbursement.value_date
                OR NEW.journal_entry_id IS DISTINCT FROM disbursement.journal_entry_id THEN
            RAISE EXCEPTION 'a wallet disbursement''s accrual start is its entry''s value date, beside the loan ACTIVE (P11-TSK-014, ADR-0097)';
        END IF;
    ELSE
        -- Path X (A30): beside its instalment loan ACTIVE, its path X disbursement POSTED and its payout terminal, on the
        -- business date of the payout's conclusion in the pinned zone - never before the disbursement's value date, never
        -- after this statement's business date.
        -- P11-TSK-033: "this statement's" read as GREATEST(the clock, the payout's birth) - the conclusion's own reference.
        -- P11-TSK-036: "the clock" the latest business date this transaction read (its start's or this statement's).
        SELECT d.* INTO disbursement
          FROM lending.loan_disbursement d JOIN lending.loan l ON l.id = d.loan_id
         WHERE d.loan_id = NEW.loan_id AND l.kind = 'INSTALMENT' AND l.status = 'ACTIVE';
        SELECT p.* INTO payout FROM lending.loan_payout p WHERE p.loan_id = NEW.loan_id;
        SELECT t.servicing_zone INTO zone
          FROM lending.loan l
          JOIN lending.loan_offer o ON o.id = l.offer_id
          JOIN lending.loan_terms t ON t.terms_version_id = o.terms_version_id
         WHERE l.id = NEW.loan_id;
        IF disbursement.id IS NULL OR disbursement.status <> 'POSTED' OR disbursement.payout_method_id IS NULL
                OR payout.id IS NULL OR payout.status NOT IN ('PAID_OUT', 'FAILED', 'NOT_DISPATCHED')
                OR NEW.journal_entry_id IS NOT NULL
                OR NEW.start_date <> lending.business_date(payout.concluded_at, zone)
                OR NEW.start_date < disbursement.value_date
                OR NEW.start_date > GREATEST(lending.business_date_latest(zone), lending.business_date(payout.created_at, zone)) THEN
            RAISE EXCEPTION 'a payout''s accrual start is the business date of its conclusion, beside the loan ACTIVE and the payout terminal (P11-TSK-015, A30)';
        END IF;
    END IF;
    NEW.created_at := statement_timestamp();
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- lending.collection_attempt_is_born_once, replaced: V018's body and the P11-TSK-036 change alone.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION lending.collection_attempt_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    account lending.loan%ROWTYPE;
    agreement lending.loan_agreement%ROWTYPE;
    terms lending.loan_terms%ROWTYPE;
    item_loan uuid;
    item_due date;
    repayment lending.repayment%ROWTYPE;
    still_due bigint;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a collection attempt is immutable from insert, for every role (P11-TSK-019, INV-IDEM-02)';
    END IF;
    -- An ACTIVE instalment loan or credit line, a CLOSING line (P11-TSK-023) - a closed or cancelled account takes no money,
    -- INV-LND-08 - or an account this attempt's own collection has just closed: the repayment closes it in its transaction
    -- (P11-TSK-018, P11-TSK-023) before the attempt naming it is recorded.
    SELECT l.* INTO account FROM lending.loan l WHERE l.id = NEW.loan_id;
    IF account.id IS NULL
            OR NOT (account.status = 'ACTIVE'
                    OR (account.kind = 'REVOLVING' AND account.status = 'CLOSING')
                    OR (account.status = 'CLOSED' AND NEW.repayment_id IS NOT NULL
                        AND EXISTS (SELECT 1 FROM lending.loan_closure c
                                     WHERE c.loan_id = NEW.loan_id AND c.repayment_id = NEW.repayment_id))) THEN
        RAISE EXCEPTION 'a collection is attempted only for an ACTIVE loan or credit line, or a CLOSING line (P11-TSK-019, P11-TSK-023, INV-LND-08)';
    END IF;
    -- The authority: the governing agreement's pinned terms collect automatically, and the borrower chose the wallet.
    SELECT a.* INTO agreement FROM lending.loan_agreement a
     WHERE a.loan_id = NEW.loan_id
       AND a.version = (SELECT max(x.version) FROM lending.loan_agreement x WHERE x.loan_id = NEW.loan_id);
    SELECT t.* INTO terms FROM lending.loan_terms t WHERE t.terms_version_id = agreement.terms_version_id;
    IF terms.terms_version_id IS NULL OR NOT terms.auto_collection
            OR NOT EXISTS (SELECT 1 FROM lending.loan_offer o JOIN lending.loan_application p ON p.id = o.application_id
                            WHERE o.id = account.offer_id AND p.repayment_method = 'WALLET') THEN
        RAISE EXCEPTION 'a collection is attempted only under an agreement whose pinned terms collect automatically from the wallet (P11-TSK-019, A21)';
    END IF;
    IF NEW.outcome = 'PARTIAL' AND NOT terms.partial_collection THEN
        RAISE EXCEPTION 'a partial collection only where the pinned terms allow it (P11-TSK-019, A21)';
    END IF;
    IF NEW.currency <> agreement.currency OR NEW.scale <> agreement.scale THEN
        RAISE EXCEPTION 'a collection attempt is in its loan''s currency and scale (P11-TSK-019)';
    END IF;
    -- Dated this transaction's business date in the pinned zone (ADR-0094 point 8, INV-LND-10).
    IF NEW.attempt_date < lending.business_date_earliest(terms.servicing_zone)
            OR NEW.attempt_date > lending.business_date_latest(terms.servicing_zone) THEN
        RAISE EXCEPTION 'a collection attempt is dated the business date of the database clock in its pinned zone (P11-TSK-019, INV-LND-10)';
    END IF;
    -- A billed item - a billing or a statement - of its own loan, already due (a statement on its payment due date).
    item_loan := lending.billed_item_loan(NEW.due_item_id);
    item_due := lending.billed_item_due_date(NEW.due_item_id);
    IF item_loan IS DISTINCT FROM NEW.loan_id OR item_due > NEW.attempt_date THEN
        RAISE EXCEPTION 'a collection is attempted on a billed item of its own loan, on or after its due date (P11-TSK-019, P11-TSK-022)';
    END IF;
    -- What was due less what was collected is what the item still has due beside it (every payer's rows counted).
    SELECT sum(lending.billing_component_billed(NEW.due_item_id, c.component)
               - lending.billing_component_paid(NEW.due_item_id, c.component))
      INTO still_due
      FROM (VALUES ('FEES'), ('INTEREST'), ('PRINCIPAL')) AS c (component);
    IF NEW.due_minor - NEW.collected_minor <> still_due THEN
        RAISE EXCEPTION 'a collection attempt records what its item had due and what it collected (P11-TSK-019, INV-LND-04)';
    END IF;
    -- The money, when it moved: an AUTO_COLLECTION repayment of this loan under the attempt's key, dated its date, for the
    -- amount collected, every line paying this billed item - never the credit balance or principal not yet due, so never
    -- more than due.
    IF NEW.repayment_id IS NOT NULL THEN
        SELECT r.* INTO repayment FROM lending.repayment r WHERE r.id = NEW.repayment_id;
        IF repayment.id IS NULL OR repayment.loan_id <> NEW.loan_id OR repayment.kind <> 'AUTO_COLLECTION'
                OR repayment.request_key <> NEW.due_item_id::text || ':' || NEW.attempt_date::text
                OR repayment.value_date <> NEW.attempt_date OR repayment.amount_minor <> NEW.collected_minor
                OR EXISTS (SELECT 1 FROM lending.repayment_allocation a
                            WHERE a.repayment_id = repayment.id
                              AND (a.target <> 'DUE_ITEM' OR a.billing_id <> NEW.due_item_id)) THEN
            RAISE EXCEPTION 'a collection names its own AUTO_COLLECTION repayment, which pays only its billed item (P11-TSK-019, INV-LND-04)';
        END IF;
    END IF;
    NEW.created_at := statement_timestamp();
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- lending.credit_line_draw_is_born_once, replaced: V013's body and the P11-TSK-036 change alone.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION lending.credit_line_draw_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    account lending.loan%ROWTYPE;
    zone text;
    agreement lending.loan_agreement%ROWTYPE;
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
    IF NEW.value_date < lending.business_date_earliest(zone)
            OR NEW.value_date > lending.business_date_latest(zone) THEN
        RAISE EXCEPTION 'a draw''s value date is the business date of the database clock in its pinned zone (P11-TSK-021, A10)';
    END IF;
    NEW.created_at := statement_timestamp();
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- lending.credit_line_statement_is_born_once, replaced: V022's body and the P11-TSK-036 change alone.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION lending.credit_line_statement_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    account lending.loan%ROWTYPE;
    agreement lending.loan_agreement%ROWTYPE;
    terms lending.loan_terms%ROWTYPE;
    previous_end date;
    accrued bigint;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a statement is immutable from insert, for every role (P11-TSK-022, INV-LND-01)';
    END IF;
    -- A line in servicing.
    SELECT l.* INTO account FROM lending.loan l WHERE l.id = NEW.loan_id;
    IF account.id IS NULL OR account.kind <> 'REVOLVING' OR account.status NOT IN ('ACTIVE', 'CLOSING') THEN
        RAISE EXCEPTION 'a statement is issued only for an ACTIVE or CLOSING credit line (P11-TSK-022)';
    END IF;
    -- Under the line's governing agreement version - its highest - in its currency and scale, by its pinned accrual and
    -- statement engines (INV-LND-09); the terms that version pins decide the zone and the payment-due days.
    SELECT a.* INTO agreement FROM lending.loan_agreement a
     WHERE a.id = NEW.agreement_id AND a.loan_id = NEW.loan_id AND a.version = NEW.agreement_version;
    IF agreement.id IS NULL
            OR NEW.agreement_version <> (SELECT max(x.version) FROM lending.loan_agreement x WHERE x.loan_id = NEW.loan_id)
            OR NEW.currency <> agreement.currency OR NEW.scale <> agreement.scale
            OR NEW.accrual_engine_version <> agreement.accrual_engine_version
            OR NEW.statement_engine_version IS DISTINCT FROM agreement.statement_engine_version THEN
        RAISE EXCEPTION 'a statement names its line''s governing agreement version, its currency and scale and its pinned accrual and statement engines (P11-TSK-022, INV-LND-09)';
    END IF;
    SELECT t.* INTO terms FROM lending.loan_terms t WHERE t.terms_version_id = agreement.terms_version_id;
    IF terms.terms_version_id IS NULL OR terms.payment_due_days IS NULL THEN
        RAISE EXCEPTION 'a statement is issued under the credit line terms its agreement pins (P11-TSK-022)';
    END IF;
    -- Never early (INV-LND-10): the statement date has come in the pinned zone on the database clock - except beside the
    -- line's payoff execution (P11-TSK-026), which states its current cycle on the accruals through yesterday in its own
    -- transaction (an execution exists only in the transaction that closes its line).
    IF NEW.cycle_end > lending.business_date_latest(terms.servicing_zone)
            AND NOT EXISTS (SELECT 1 FROM lending.payoff_execution e WHERE e.loan_id = NEW.loan_id) THEN
        RAISE EXCEPTION 'a statement is issued only once its statement date has come on the database clock in its pinned zone, or by its line''s payoff (P11-TSK-022, P11-TSK-026, INV-LND-10)';
    END IF;
    -- Every cycle ends on the statement day and falls due the pinned payment-due days later (A23).
    IF extract(day FROM NEW.cycle_end) <> agreement.repayment_day
            OR NEW.due_date <> NEW.cycle_end + terms.payment_due_days THEN
        RAISE EXCEPTION 'a statement''s cycle ends on the statement day and its payment falls due the pinned days after it (P11-TSK-022, A23)';
    END IF;
    -- Oldest first and contiguous: never before a statement already issued, and each cycle starting where the last ended -
    -- the first at the line's opening business date.
    IF EXISTS (SELECT 1 FROM lending.credit_line_statement s
                WHERE s.loan_id = NEW.loan_id AND s.cycle_end >= NEW.cycle_end) THEN
        RAISE EXCEPTION 'a line''s statements are issued oldest first, one per cycle (P11-TSK-022)';
    END IF;
    SELECT max(s.cycle_end) INTO previous_end FROM lending.credit_line_statement s WHERE s.loan_id = NEW.loan_id;
    IF NEW.cycle_start <> coalesce(previous_end, lending.business_date(account.opened_at, terms.servicing_zone)) THEN
        RAISE EXCEPTION 'a line''s cycles are contiguous from its opening (P11-TSK-022, A23)';
    END IF;
    -- Interest actually accrued, never the projection (ADR-0094 point 6): exactly the sum of the cycle's accruals. The
    -- accrual's own trigger then refuses any later date inside a stated cycle, so the equality holds for ever.
    SELECT coalesce(sum(a.posted_minor), 0) INTO accrued FROM lending.interest_accrual a
     WHERE a.loan_id = NEW.loan_id AND a.period_start = NEW.cycle_start AND a.period_end = NEW.cycle_end;
    IF NEW.interest_billed_minor <> accrued THEN
        RAISE EXCEPTION 'a statement''s interest billed is exactly its cycle''s accrued interest (P11-TSK-022, ADR-0094 point 6)';
    END IF;
    -- A billed item's identity names one item: never an instalment billing's.
    IF EXISTS (SELECT 1 FROM lending.instalment_billing b WHERE b.id = NEW.id) THEN
        RAISE EXCEPTION 'a statement''s identity is its own, never a billing''s (P11-TSK-022)';
    END IF;
    NEW.created_at := statement_timestamp();
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- lending.fee_assessment_is_born_once, replaced: V021's body and the P11-TSK-036 change alone.
-- ---------------------------------------------------------------------------------------------
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
    IF NEW.business_date > lending.business_date_latest(terms.servicing_zone)
            OR NEW.business_date < lending.business_date_earliest(terms.servicing_zone) THEN
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

-- ---------------------------------------------------------------------------------------------
-- lending.instalment_billing_is_born_once, replaced: V024's body and the P11-TSK-036 change alone.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION lending.instalment_billing_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    account lending.loan%ROWTYPE;
    zone text;
    agreement lending.loan_agreement%ROWTYPE;
    schedule lending.repayment_schedule%ROWTYPE;
    instalment lending.loan_instalment%ROWTYPE;
    final_due date;
    accrued bigint;
    billed_principal bigint;
    prepaid_principal bigint;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a billing is immutable from insert, for every role (P11-TSK-017, INV-LND-01)';
    END IF;
    -- An instalment loan in servicing, and its pinned servicing zone.
    SELECT l.* INTO account FROM lending.loan l WHERE l.id = NEW.loan_id;
    SELECT t.servicing_zone INTO zone
      FROM lending.loan l
      JOIN lending.loan_offer o ON o.id = l.offer_id
      JOIN lending.loan_terms t ON t.terms_version_id = o.terms_version_id
     WHERE l.id = NEW.loan_id;
    IF account.id IS NULL OR account.kind <> 'INSTALMENT' OR account.status NOT IN ('ACTIVE', 'CLOSING')
            OR zone IS NULL THEN
        RAISE EXCEPTION 'an instalment is billed only on an ACTIVE or CLOSING instalment loan (P11-TSK-017)';
    END IF;
    -- Never early (INV-LND-10): the due date has come in the pinned zone on the database clock - except beside the loan's
    -- payoff execution (P11-TSK-026), which bills everything unbilled in its own transaction. An execution exists only in
    -- the transaction that closes its loan (payoff_execution_closes_its_loan, deferred), and a billing only for an open
    -- loan, so an execution found here is this transaction's.
    IF NEW.due_date > lending.business_date_latest(zone)
            AND NOT EXISTS (SELECT 1 FROM lending.payoff_execution e WHERE e.loan_id = NEW.loan_id) THEN
        RAISE EXCEPTION 'an instalment is billed only once its due date has come on the database clock in its pinned zone, or by its loan''s payoff (P11-TSK-017, P11-TSK-026, INV-LND-10)';
    END IF;
    -- Under the account's own agreement version, in its currency and scale, by its pinned accrual engine.
    SELECT a.* INTO agreement FROM lending.loan_agreement a
     WHERE a.id = NEW.agreement_id AND a.loan_id = NEW.loan_id AND a.version = NEW.agreement_version;
    IF agreement.id IS NULL OR NEW.currency <> agreement.currency OR NEW.scale <> agreement.scale
            OR NEW.accrual_engine_version <> agreement.accrual_engine_version THEN
        RAISE EXCEPTION 'a billing names its account''s agreement version, its currency and scale and its pinned accrual engine (P11-TSK-017, INV-LND-09)';
    END IF;
    -- Of the loan's LATEST schedule version, by that version's engine (a superseded version's unbilled instalments are
    -- never billed - PHASE_11_PLAN.md section 12.2 step 7) - and under the agreement version that schedule projects
    -- (P11-TSK-027: after an amendment, version n+1's, so the new rate's schedule is billed under the new agreement;
    -- P11-TSK-028: after a prepayment, its version's).
    SELECT s.* INTO schedule FROM lending.repayment_schedule s
     WHERE s.id = NEW.schedule_id AND s.loan_id = NEW.loan_id AND s.schedule_version = NEW.schedule_version
       AND s.schedule_version = (SELECT max(x.schedule_version) FROM lending.repayment_schedule x WHERE x.loan_id = NEW.loan_id);
    IF schedule.id IS NULL OR NEW.schedule_engine_version <> schedule.schedule_engine_version
            OR NEW.currency <> schedule.currency OR NEW.scale <> schedule.scale
            OR NEW.agreement_version <> schedule.agreement_version THEN
        RAISE EXCEPTION 'a billing bills the loan''s latest schedule version, under the agreement version it projects (P11-TSK-017, P11-TSK-027, INV-LND-03)';
    END IF;
    SELECT max(i.due_date) INTO final_due FROM lending.loan_instalment i WHERE i.schedule_id = schedule.id;
    IF NEW.kind = 'INSTALMENT' THEN
        -- The instalment itself: its schedule, its sequence, its period.
        SELECT i.* INTO instalment FROM lending.loan_instalment i
         WHERE i.id = NEW.instalment_id AND i.schedule_id = schedule.id AND i.loan_id = NEW.loan_id;
        IF instalment.id IS NULL OR instalment.sequence <> NEW.sequence
                OR instalment.period_start <> NEW.period_start OR instalment.due_date <> NEW.due_date THEN
            RAISE EXCEPTION 'a billing bills its own instalment''s period (P11-TSK-017)';
        END IF;
        -- Oldest first: every earlier instalment of the version already billed.
        IF EXISTS (SELECT 1 FROM lending.loan_instalment i
                    WHERE i.schedule_id = schedule.id AND i.sequence < NEW.sequence
                      AND NOT EXISTS (SELECT 1 FROM lending.instalment_billing b WHERE b.instalment_id = i.id)) THEN
            RAISE EXCEPTION 'a loan''s instalments are billed oldest first (P11-TSK-017)';
        END IF;
    ELSE
        -- After the final due date, every instalment of the version billed: the calendar continued, interest only.
        IF NEW.period_start < final_due
                OR EXISTS (SELECT 1 FROM lending.loan_instalment i
                            WHERE i.schedule_id = schedule.id
                              AND NOT EXISTS (SELECT 1 FROM lending.instalment_billing b WHERE b.instalment_id = i.id)) THEN
            RAISE EXCEPTION 'a post-maturity period is billed only after every instalment of the schedule (P11-TSK-017)';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM lending.interest_accrual a
                        WHERE a.loan_id = NEW.loan_id AND a.period_start = NEW.period_start
                          AND a.period_end = NEW.due_date) THEN
            RAISE EXCEPTION 'a post-maturity period is billed only when interest accrued in it (P11-TSK-017)';
        END IF;
    END IF;
    -- Never before a billed item already due later: oldest first across both kinds.
    IF EXISTS (SELECT 1 FROM lending.instalment_billing b
                WHERE b.loan_id = NEW.loan_id AND b.due_date >= NEW.due_date) THEN
        RAISE EXCEPTION 'a loan''s billed items are born oldest first, one per due date (P11-TSK-017)';
    END IF;
    -- Interest actually accrued, never the projection (ADR-0094 point 6): exactly the sum of the period's accruals. The
    -- accrual's own trigger then refuses any later date inside a billed period, so the equality holds for ever.
    SELECT coalesce(sum(a.posted_minor), 0) INTO accrued FROM lending.interest_accrual a
     WHERE a.loan_id = NEW.loan_id AND a.period_start = NEW.period_start AND a.period_end = NEW.due_date;
    IF NEW.interest_due_minor <> accrued THEN
        RAISE EXCEPTION 'a billing''s interest due is exactly its period''s accrued interest (P11-TSK-017, ADR-0094 point 6)';
    END IF;
    -- Principal is billed at most once over the loan's life: the principal billed NET OF RE-SCHEDULING (an amendment's
    -- arrears re-scheduled are billed again under the new schedule, P11-TSK-027) PLUS the principal PREPAID (P11-TSK-028 -
    -- paid before it was ever billed) never beyond its agreement's principal - version 1's, which no later version changes
    -- (INV-LND-03).
    SELECT coalesce(sum(lending.billing_component_billed(b.id, 'PRINCIPAL')), 0) INTO billed_principal
      FROM lending.instalment_billing b
     WHERE b.loan_id = NEW.loan_id;
    SELECT coalesce(sum(p.amount_minor), 0) INTO prepaid_principal
      FROM lending.repayment_allocation p
      JOIN lending.repayment r ON r.id = p.repayment_id
     WHERE r.loan_id = NEW.loan_id AND r.kind = 'PREPAYMENT' AND p.target = 'NOT_DUE_PRINCIPAL';
    IF billed_principal + prepaid_principal + NEW.principal_due_minor
            > (SELECT a.amount_minor FROM lending.loan_agreement a WHERE a.loan_id = NEW.loan_id AND a.version = 1) THEN
        RAISE EXCEPTION 'the principal billed and prepaid over a loan''s life never exceeds its principal (P11-TSK-017, P11-TSK-028, INV-LND-03)';
    END IF;
    NEW.created_at := statement_timestamp();
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- lending.interest_accrual_is_born_once, replaced: V017's body and the P11-TSK-036 change alone.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION lending.interest_accrual_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    account lending.loan%ROWTYPE;
    zone text;
    agreement lending.loan_agreement%ROWTYPE;
    started date;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'an interest accrual is immutable from insert, for every role (P11-TSK-016, INV-LND-02)';
    END IF;
    -- An account in servicing, and its pinned servicing zone.
    SELECT l.* INTO account FROM lending.loan l WHERE l.id = NEW.loan_id;
    SELECT t.servicing_zone INTO zone
      FROM lending.loan l
      JOIN lending.loan_offer o ON o.id = l.offer_id
      JOIN lending.loan_terms t ON t.terms_version_id = o.terms_version_id
     WHERE l.id = NEW.loan_id;
    IF account.id IS NULL OR account.status NOT IN ('ACTIVE', 'CLOSING') OR zone IS NULL THEN
        RAISE EXCEPTION 'interest accrues only on an ACTIVE or CLOSING account (P11-TSK-016)';
    END IF;
    -- Never early (INV-LND-10): the date has ended in the pinned zone on the database clock.
    IF NEW.accrual_date >= lending.business_date_latest(zone) THEN
        RAISE EXCEPTION 'a date accrues only once it has ended on the database clock in its pinned zone (P11-TSK-016, INV-LND-10)';
    END IF;
    -- From the accrual start: an instalment loan's accrual_start row (none - nothing accrues: path X awaiting its payout),
    -- a line's opening business date.
    IF account.kind = 'INSTALMENT' THEN
        SELECT s.start_date INTO started FROM lending.accrual_start s WHERE s.loan_id = NEW.loan_id;
    ELSE
        started := lending.business_date(account.opened_at, zone);
    END IF;
    IF started IS NULL OR NEW.accrual_date < started THEN
        RAISE EXCEPTION 'interest accrues from the account''s accrual start, and not before it (P11-TSK-016, ADR-0094)';
    END IF;
    -- Oldest first: never a date at or before one already accrued.
    IF EXISTS (SELECT 1 FROM lending.interest_accrual a
                WHERE a.loan_id = NEW.loan_id AND a.accrual_date >= NEW.accrual_date) THEN
        RAISE EXCEPTION 'an account''s dates accrue oldest first, each once (P11-TSK-016, INV-LND-02)';
    END IF;
    -- Never into a period already billed (P11-TSK-017): a billing made its period's accrued interest due, so a date
    -- before the latest billed due date can never accrue afterwards - billed interest stays the accrued interest.
    IF EXISTS (SELECT 1 FROM lending.instalment_billing b
                WHERE b.loan_id = NEW.loan_id AND b.due_date > NEW.accrual_date) THEN
        RAISE EXCEPTION 'a date inside a period already billed never accrues afterwards (P11-TSK-017, ADR-0094 point 6)';
    END IF;
    -- Never into a cycle already stated (P11-TSK-022): a statement made its cycle's accrued interest due, so a date before
    -- the latest statement date can never accrue afterwards - stated interest stays the accrued interest.
    IF EXISTS (SELECT 1 FROM lending.credit_line_statement s
                WHERE s.loan_id = NEW.loan_id AND s.cycle_end > NEW.accrual_date) THEN
        RAISE EXCEPTION 'a date inside a cycle already stated never accrues afterwards (P11-TSK-022, ADR-0094 point 6)';
    END IF;
    -- Under the account's own agreement version, in its currency and scale, by its pinned accrual engine.
    SELECT a.* INTO agreement FROM lending.loan_agreement a
     WHERE a.id = NEW.agreement_id AND a.loan_id = NEW.loan_id AND a.version = NEW.agreement_version;
    IF agreement.id IS NULL OR NEW.currency <> agreement.currency OR NEW.scale <> agreement.scale
            OR NEW.accrual_engine_version <> agreement.accrual_engine_version THEN
        RAISE EXCEPTION 'an accrual names its account''s agreement version, its currency and scale and its pinned accrual engine (P11-TSK-016, INV-LND-09)';
    END IF;
    NEW.created_at := statement_timestamp();
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- lending.loan_condition_event_is_born_once, replaced: V020's body and the P11-TSK-036 change alone.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION lending.loan_condition_event_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    account lending.loan%ROWTYPE;
    agreement lending.loan_agreement%ROWTYPE;
    terms lending.loan_terms%ROWTYPE;
    derived integer;
    was_bucket text;
    was_defaulted boolean;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a condition change is immutable from insert, for every role (P11-TSK-024, INV-LND-12, INV-HIST-01)';
    END IF;
    -- An account in servicing: a closed or cancelled one takes no condition change (INV-LND-08, scenario 10).
    SELECT l.* INTO account FROM lending.loan l WHERE l.id = NEW.loan_id;
    IF account.id IS NULL OR account.status NOT IN ('ACTIVE', 'CLOSING') THEN
        RAISE EXCEPTION 'a condition is recorded only for an ACTIVE or CLOSING account (P11-TSK-024, INV-LND-08)';
    END IF;
    -- The governing agreement version, whose pinned terms judge the condition.
    SELECT a.* INTO agreement FROM lending.loan_agreement a
     WHERE a.loan_id = NEW.loan_id
       AND a.version = (SELECT max(x.version) FROM lending.loan_agreement x WHERE x.loan_id = NEW.loan_id);
    IF agreement.id IS NULL OR NEW.agreement_id <> agreement.id OR NEW.agreement_version <> agreement.version THEN
        RAISE EXCEPTION 'a condition names its account''s governing agreement version (P11-TSK-024)';
    END IF;
    SELECT t.* INTO terms FROM lending.loan_terms t WHERE t.terms_version_id = agreement.terms_version_id;
    -- Dated this transaction's business date in the pinned zone (INV-LND-10).
    IF NEW.business_date < lending.business_date_earliest(terms.servicing_zone)
            OR NEW.business_date > lending.business_date_latest(terms.servicing_zone) THEN
        RAISE EXCEPTION 'a condition is dated the business date of the database clock in its pinned zone (P11-TSK-024, INV-LND-10)';
    END IF;
    -- DPD derived from the rows, never a writer's figure (INV-LND-12); the bucket that DPD's under the pinned bounds.
    derived := lending.days_past_due(NEW.loan_id, NEW.business_date);
    IF NEW.days_past_due <> derived
            OR NEW.bucket <> lending.delinquency_bucket(derived, terms.delinquency_bounds) THEN
        RAISE EXCEPTION 'a condition''s days past due and bucket are derived from its billed and allocated rows (P11-TSK-024, INV-LND-12)';
    END IF;
    -- The latest recorded condition of each aspect: the bucket of the latest DELINQUENCY row, the flag of the latest
    -- DEFAULT row - CURRENT and not defaulted before any.
    SELECT coalesce((SELECT e.bucket FROM lending.loan_condition_event e
                      WHERE e.loan_id = NEW.loan_id AND e.kind = 'DELINQUENCY'
                      ORDER BY e.business_date DESC LIMIT 1), 'CURRENT'),
           coalesce((SELECT e.defaulted FROM lending.loan_condition_event e
                      WHERE e.loan_id = NEW.loan_id AND e.kind = 'DEFAULT'
                      ORDER BY e.business_date DESC LIMIT 1), false)
      INTO was_bucket, was_defaulted;
    -- The default flag: set at the pinned threshold (L7), held while past due, cleared at cure - no probation (A8).
    IF NEW.defaulted <> (derived >= terms.default_threshold_days OR (was_defaulted AND derived > 0)) THEN
        RAISE EXCEPTION 'a condition''s default flag is set at the default threshold and cleared at cure (P11-TSK-024, L7, A8)';
    END IF;
    -- Only on a change of its kind (ADR-0098 point 3).
    IF (NEW.kind = 'DELINQUENCY' AND NEW.previous_bucket <> was_bucket)
            OR (NEW.kind = 'DEFAULT' AND NEW.defaulted = was_defaulted) THEN
        RAISE EXCEPTION 'a condition is recorded only on a change, from the latest recorded one (P11-TSK-024, INV-LND-12)';
    END IF;
    NEW.created_at := statement_timestamp();
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- lending.loan_disbursement_permits_only_machine_edges, replaced: V009's body and the P11-TSK-036 change alone.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION lending.loan_disbursement_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    deadline_hours integer;
    preference text;
    loan_status text;
    zone text;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PENDING' THEN
            RAISE EXCEPTION 'a disbursement is born PENDING (P11-TSK-012)';
        END IF;
        -- Beside its instalment loan awaiting disbursement; the deadline is its agreement's pinned terms version's.
        SELECT t.disbursement_deadline_hours, a.payout_preference INTO deadline_hours, preference
          FROM lending.loan l
          JOIN lending.loan_offer o ON o.id = l.offer_id
          JOIN lending.loan_application a ON a.id = o.application_id
          JOIN lending.loan_terms t ON t.terms_version_id = o.terms_version_id
         WHERE l.id = NEW.loan_id AND l.kind = 'INSTALMENT' AND l.status = 'PENDING_DISBURSEMENT';
        IF NOT FOUND OR deadline_hours IS NULL THEN
            RAISE EXCEPTION 'a disbursement is born beside its instalment loan PENDING_DISBURSEMENT (P11-TSK-012)';
        END IF;
        -- Path X exactly when the application preferred an external account (A25; ADR-0097).
        IF (preference = 'EXTERNAL_ACCOUNT') <> (NEW.payout_method_id IS NOT NULL) THEN
            RAISE EXCEPTION 'a disbursement names a payout method exactly when its application preferred an external account (P11-TSK-012)';
        END IF;
        NEW.created_at := statement_timestamp();
        NEW.deadline_at := statement_timestamp() + make_interval(hours => deadline_hours);
        -- Due for the disbursement sweep at once (P11-TSK-014).
        NEW.next_attempt_at := statement_timestamp();
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a disbursement is never deleted (P11-TSK-012, INV-HIST-01)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.loan_id IS DISTINCT FROM OLD.loan_id
            OR NEW.payout_method_id IS DISTINCT FROM OLD.payout_method_id
            OR NEW.created_at IS DISTINCT FROM OLD.created_at OR NEW.deadline_at IS DISTINCT FROM OLD.deadline_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a disbursement''s identity, path, birth and deadline are frozen (P11-TSK-014)';
    END IF;
    IF OLD.status <> 'PENDING' THEN
        RAISE EXCEPTION 'a % disbursement is terminal (P11-TSK-014, INV-LIFE-04)', OLD.status;
    END IF;
    -- A permit-only move: the sweep's claim re-stamps the permit and nothing else.
    IF NEW.status = 'PENDING' THEN
        IF NEW.next_attempt_at IS NOT DISTINCT FROM OLD.next_attempt_at OR NEW.journal_entry_id IS NOT NULL
                OR NEW.value_date IS NOT NULL OR NEW.failure_reason IS NOT NULL OR NEW.decided_at IS NOT NULL THEN
            RAISE EXCEPTION 'a PENDING disbursement moves its permit and nothing else (P11-TSK-014)';
        END IF;
        RETURN NEW;
    END IF;
    -- PENDING -> POSTED | FAILED, while its loan still awaits it: the loan moves beside it, in this transaction, and the
    -- deferred check holds the pair at commit (on path X with its payout - loan_disbursed_beside_its_facts).
    SELECT l.status, t.servicing_zone INTO loan_status, zone
      FROM lending.loan l
      JOIN lending.loan_offer o ON o.id = l.offer_id
      JOIN lending.loan_terms t ON t.terms_version_id = o.terms_version_id
     WHERE l.id = OLD.loan_id;
    IF loan_status IS DISTINCT FROM 'PENDING_DISBURSEMENT' THEN
        RAISE EXCEPTION 'a disbursement is decided only while its loan is PENDING_DISBURSEMENT (P11-TSK-014, INV-LND-07)';
    END IF;
    NEW.next_attempt_at := OLD.next_attempt_at;
    NEW.decided_at := statement_timestamp();
    IF NEW.status = 'POSTED' THEN
        IF NEW.journal_entry_id IS NULL OR NEW.value_date IS NULL OR NEW.failure_reason IS NOT NULL THEN
            RAISE EXCEPTION 'a POSTED disbursement names its journal entry and value date, and no failure (P11-TSK-014)';
        END IF;
        -- The value date is this transaction's business date in the pinned servicing zone (A10, INV-LND-10): read by the
        -- step after its locks, so between the transaction's start and this statement - P11-TSK-036: in whichever order
        -- the clock read the two, which a clock stepped back across a midnight reverses (business_date_earliest / _latest).
        IF NEW.value_date < lending.business_date_earliest(zone)
                OR NEW.value_date > lending.business_date_latest(zone) THEN
            RAISE EXCEPTION 'a disbursement''s value date is the business date of the database clock in its pinned zone (P11-TSK-014, A10)';
        END IF;
    ELSIF NEW.status = 'FAILED' THEN
        IF NEW.journal_entry_id IS NOT NULL OR NEW.value_date IS NOT NULL OR NEW.failure_reason IS NULL THEN
            RAISE EXCEPTION 'a FAILED disbursement names its reason and posted nothing (P11-TSK-014, rule 11)';
        END IF;
        -- A wallet that cannot be posted is retried until the deadline: the failure is judged on the database clock.
        IF NEW.failure_reason = 'DISBURSEMENT_FAILED' AND OLD.deadline_at > statement_timestamp() THEN
            RAISE EXCEPTION 'a disbursement fails only once its deadline has passed on the database clock (P11-TSK-014)';
        END IF;
    ELSE
        RAISE EXCEPTION 'a disbursement moves PENDING -> POSTED | FAILED; % -> % is not an edge (P11-TSK-014)', OLD.status, NEW.status;
    END IF;
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- lending.payoff_execution_is_born_once, replaced: V022's body and the P11-TSK-036 change alone.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION lending.payoff_execution_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    account lending.loan%ROWTYPE;
    quote lending.payoff_quote%ROWTYPE;
    zone text;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a payoff execution is immutable from insert, for every role (P11-TSK-026, INV-LIFE-04)';
    END IF;
    -- Its own loan's quote, on an account a payoff can still end.
    SELECT q.* INTO quote FROM lending.payoff_quote q WHERE q.id = NEW.quote_id AND q.loan_id = NEW.loan_id;
    SELECT l.* INTO account FROM lending.loan l WHERE l.id = NEW.loan_id;
    IF quote.id IS NULL OR account.id IS NULL
            OR NOT ((account.kind = 'INSTALMENT' AND account.status = 'ACTIVE')
                    OR (account.kind = 'REVOLVING' AND account.status IN ('ACTIVE', 'CLOSING'))) THEN
        RAISE EXCEPTION 'a payoff executes its own loan''s quote, on an ACTIVE instalment loan or a credit line ACTIVE or CLOSING (P11-TSK-026, INV-LND-08)';
    END IF;
    -- On the quote's good-through date, which is this transaction's business date in the pinned zone (INV-LND-10).
    SELECT t.servicing_zone INTO zone
      FROM lending.loan_agreement a JOIN lending.loan_terms t ON t.terms_version_id = a.terms_version_id
     WHERE a.id = quote.agreement_id;
    IF zone IS NULL OR NEW.business_date <> quote.good_through
            OR NEW.business_date < lending.business_date_earliest(zone)
            OR NEW.business_date > lending.business_date_latest(zone) THEN
        RAISE EXCEPTION 'a payoff quote is executed only on its good-through date, on the database clock in its pinned zone (P11-TSK-026, INV-LND-10)';
    END IF;
    NEW.executed_at := statement_timestamp();
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- lending.payoff_quote_is_born_once, replaced: V022's body and the P11-TSK-036 change alone.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION lending.payoff_quote_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    account lending.loan%ROWTYPE;
    agreement lending.loan_agreement%ROWTYPE;
    terms lending.loan_terms%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a payoff quote is immutable from insert, for every role (P11-TSK-026, INV-LND-08)';
    END IF;
    -- An account a payoff can end: an ACTIVE instalment loan, or a credit line ACTIVE or CLOSING.
    SELECT l.* INTO account FROM lending.loan l WHERE l.id = NEW.loan_id;
    IF account.id IS NULL
            OR NOT ((account.kind = 'INSTALMENT' AND account.status = 'ACTIVE')
                    OR (account.kind = 'REVOLVING' AND account.status IN ('ACTIVE', 'CLOSING'))) THEN
        RAISE EXCEPTION 'a payoff is quoted only for an ACTIVE instalment loan or a credit line ACTIVE or CLOSING (P11-TSK-026, INV-LND-08)';
    END IF;
    -- Under the account's governing agreement version - its highest - in its currency and scale, by its pinned engines
    -- (INV-LND-09); the terms that version pins decide the zone and the horizon.
    SELECT a.* INTO agreement FROM lending.loan_agreement a
     WHERE a.id = NEW.agreement_id AND a.loan_id = NEW.loan_id AND a.version = NEW.agreement_version;
    IF agreement.id IS NULL
            OR NEW.agreement_version <> (SELECT max(x.version) FROM lending.loan_agreement x WHERE x.loan_id = NEW.loan_id)
            OR NEW.currency <> agreement.currency OR NEW.scale <> agreement.scale
            OR NEW.accrual_engine_version <> agreement.accrual_engine_version
            OR NEW.allocation_engine_version <> agreement.allocation_engine_version THEN
        RAISE EXCEPTION 'a payoff quote names its account''s governing agreement version, its currency and scale and its pinned engines (P11-TSK-026, INV-LND-09)';
    END IF;
    SELECT t.* INTO terms FROM lending.loan_terms t WHERE t.terms_version_id = agreement.terms_version_id;
    IF terms.terms_version_id IS NULL THEN
        RAISE EXCEPTION 'a payoff is quoted under the terms its agreement pins (P11-TSK-026)';
    END IF;
    -- Made on this transaction's business date in the pinned zone (read by the quote after its lock), good through that
    -- date up to the pinned horizon (INV-LND-10).
    IF NEW.business_date < lending.business_date_earliest(terms.servicing_zone)
            OR NEW.business_date > lending.business_date_latest(terms.servicing_zone)
            OR NEW.good_through > NEW.business_date + terms.payoff_horizon_days THEN
        RAISE EXCEPTION 'a payoff quote is made on the business date of the database clock in its pinned zone, good through at most the pinned horizon after it (P11-TSK-026, INV-LND-10)';
    END IF;
    NEW.created_at := statement_timestamp();
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- lending.repayment_is_born_once, replaced: V024's body and the P11-TSK-036 change alone.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION lending.repayment_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    account lending.loan%ROWTYPE;
    zone text;
    agreement lending.loan_agreement%ROWTYPE;
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
    IF NEW.value_date < lending.business_date_earliest(zone)
            OR NEW.value_date > lending.business_date_latest(zone) THEN
        RAISE EXCEPTION 'a repayment''s value date is the business date of the database clock in its pinned zone (P11-TSK-018, INV-LND-10)';
    END IF;
    NEW.created_at := statement_timestamp();
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- lending.repayment_schedule_is_born_once, replaced: V024's body and the P11-TSK-036 change alone.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION lending.repayment_schedule_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    agreement lending.loan_agreement%ROWTYPE;
    proposal lending.loan_amendment%ROWTYPE;
    previous lending.repayment_schedule%ROWTYPE;
    unbilled integer;
    started date;
    zone text;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a schedule version is immutable from insert, for every role (P11-TSK-014, INV-LND-03)';
    END IF;
    SELECT s.start_date INTO started FROM lending.accrual_start s WHERE s.loan_id = NEW.loan_id;
    IF NEW.schedule_version = 1 THEN
        -- Version 1: the loan's agreement version 1, by its pinned engine, from the loan's accrual start, while ACTIVE.
        SELECT a.* INTO agreement
          FROM lending.loan_agreement a JOIN lending.loan l ON l.id = a.loan_id
         WHERE a.id = NEW.agreement_id AND a.loan_id = NEW.loan_id AND a.version = 1
           AND l.kind = 'INSTALMENT' AND l.status = 'ACTIVE';
        IF agreement.id IS NULL OR started IS NULL OR NEW.agreement_version <> 1
                OR NEW.schedule_engine_version IS DISTINCT FROM agreement.schedule_engine_version
                OR NEW.currency <> agreement.currency OR NEW.scale <> agreement.scale
                OR NEW.principal_minor <> agreement.amount_minor
                OR NEW.term_months IS DISTINCT FROM agreement.term_months
                OR NEW.intended_day <> agreement.repayment_day
                OR NEW.start_date <> started THEN
            RAISE EXCEPTION 'schedule version 1 projects its loan''s agreement version 1 by the pinned engine from the accrual start (P11-TSK-014, ADR-0093)';
        END IF;
    ELSE
        -- Version n+1 (P11-TSK-027, P11-TSK-028): the loan's latest agreement version - an amendment's or a prepayment's -
        -- by its pinned engine, the next schedule version, while ACTIVE.
        SELECT a.* INTO agreement
          FROM lending.loan_agreement a JOIN lending.loan l ON l.id = a.loan_id
         WHERE a.id = NEW.agreement_id AND a.loan_id = NEW.loan_id AND a.version = NEW.agreement_version
           AND a.version > 1 AND (a.amendment_id IS NOT NULL OR a.prepayment_id IS NOT NULL)
           AND a.version = (SELECT max(x.version) FROM lending.loan_agreement x WHERE x.loan_id = NEW.loan_id)
           AND l.kind = 'INSTALMENT' AND l.status = 'ACTIVE';
        SELECT t.servicing_zone INTO zone FROM lending.loan_terms t WHERE t.terms_version_id = agreement.terms_version_id;
        -- The schedule it supersedes - the latest before this one - and what of it is left unbilled.
        SELECT s.* INTO previous FROM lending.repayment_schedule s WHERE s.loan_id = NEW.loan_id
         ORDER BY s.schedule_version DESC LIMIT 1;
        unbilled := lending.unbilled_instalments(NEW.loan_id);
        IF agreement.id IS NULL OR started IS NULL OR previous.id IS NULL
                OR NEW.schedule_version <> previous.schedule_version + 1
                OR NEW.schedule_engine_version IS DISTINCT FROM agreement.schedule_engine_version
                OR NEW.currency <> agreement.currency OR NEW.scale <> agreement.scale
                OR NEW.intended_day <> agreement.repayment_day THEN
            RAISE EXCEPTION 'a later schedule version projects its loan''s latest agreement version - an amendment''s or a prepayment''s - by the pinned engine, as the next version (P11-TSK-027, P11-TSK-028, ADR-0093)';
        END IF;
        IF agreement.amendment_id IS NOT NULL THEN
            -- An amendment's: over exactly the amendment's remaining instalments.
            SELECT m.* INTO proposal FROM lending.loan_amendment m WHERE m.id = agreement.amendment_id;
            IF proposal.id IS NULL OR NEW.term_months <> proposal.remaining_instalments THEN
                RAISE EXCEPTION 'an amendment''s schedule version runs over the amendment''s remaining instalments (P11-TSK-027, ADR-0093)';
            END IF;
        ELSIF NEW.term_months > unbilled
                OR NOT (NEW.instalment_minor = previous.instalment_minor
                        OR (NEW.term_months = unbilled AND NEW.instalment_minor > previous.instalment_minor)) THEN
            -- A prepayment's (P11-TSK-028): THE SAME LEVEL INSTALMENT over a term never longer than what the superseded
            -- schedule had left - the least such term, which the schedule's own shape (every period but the last closing
            -- positive, the last never above the instalment) makes it. Only when that instalment cannot amortise the
            -- principal within the remaining term (late payments left more outstanding than it can carry) is the term kept
            -- and the instalment the engine's least - necessarily above the old one.
            RAISE EXCEPTION 'a prepayment''s schedule version keeps the level instalment over a term never longer than the remaining one (P11-TSK-028, ADR-0093)';
        END IF;
        -- PRINCIPAL CONSERVED ACROSS VERSIONS (INV-LND-03): exactly the principal outstanding the rows explain - agreement
        -- version 1's principal less the principal billed net of re-scheduling and less what was prepaid - never the
        -- original principal, never more.
        IF NEW.principal_minor <> lending.principal_not_yet_billed(NEW.loan_id) THEN
            RAISE EXCEPTION 'a later schedule version amortises exactly the principal outstanding not yet billed (P11-TSK-027, P11-TSK-028, INV-LND-03)';
        END IF;
        -- From the open period's start: on or after the accrual start and every billed due date, never after today.
        IF NEW.start_date < started
                OR NEW.start_date > lending.business_date_latest(zone)
                OR EXISTS (SELECT 1 FROM lending.instalment_billing b
                            WHERE b.loan_id = NEW.loan_id AND b.due_date > NEW.start_date) THEN
            RAISE EXCEPTION 'a later schedule version starts at the open period''s start - after every billed due date, never after today (P11-TSK-027)';
        END IF;
        -- THE BILLING FIRST, for every writer (P11-TSK-028): no instalment of the superseded schedule already due - its due
        -- date come by the transaction's business date in the pinned zone - is superseded unbilled, so neither it nor the
        -- interest its period accrued is stranded (PHASE_11_PLAN.md section 12.2 step 7). The transaction's start, never a
        -- later statement's: the domain bills through a date read after its lock, never earlier, so this never refuses it.
        -- P11-TSK-036: the earliest business date this transaction read - its start's, or this statement's when a database
        -- clock stepped back across a midnight put the domain's read on the day before its start.
        IF EXISTS (SELECT 1 FROM lending.loan_instalment i
                    WHERE i.schedule_id = previous.id
                      AND i.due_date <= lending.business_date_earliest(zone)
                      AND NOT EXISTS (SELECT 1 FROM lending.instalment_billing b WHERE b.instalment_id = i.id)) THEN
            RAISE EXCEPTION 'a schedule is superseded only once every instalment of it already due is billed - the billing first (P11-TSK-028, INV-LND-03)';
        END IF;
    END IF;
    NEW.created_at := statement_timestamp();
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- lending.loan_amendment_rescheduling_is_born_once, replaced: V023's body and the P11-TSK-036 change alone.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION lending.loan_amendment_rescheduling_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    proposal lending.loan_amendment%ROWTYPE;
    amended lending.loan_agreement%ROWTYPE;
    billing lending.instalment_billing%ROWTYPE;
    zone text;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a re-scheduling is immutable from insert, for every role (P11-TSK-027, INV-HIST-01)';
    END IF;
    -- (L3) The account, for every writer.
    PERFORM 1 FROM lending.loan l WHERE l.id = NEW.loan_id FOR UPDATE;
    SELECT m.* INTO proposal FROM lending.loan_amendment m WHERE m.id = NEW.amendment_id AND m.loan_id = NEW.loan_id;
    -- In the acceptance alone: the amendment re-schedules arrears, its agreement version is the loan's latest, and its
    -- schedule version is not yet born (the deferred check holds the three to one transaction).
    SELECT a.* INTO amended FROM lending.loan_agreement a WHERE a.amendment_id = NEW.amendment_id;
    IF proposal.id IS NULL OR NOT proposal.reschedule_arrears OR amended.id IS NULL
            OR amended.version <> (SELECT max(x.version) FROM lending.loan_agreement x WHERE x.loan_id = NEW.loan_id)
            OR EXISTS (SELECT 1 FROM lending.repayment_schedule s WHERE s.agreement_id = amended.id) THEN
        RAISE EXCEPTION 'arrears are re-scheduled only in the acceptance of an amendment that re-schedules them, before its schedule (P11-TSK-027)';
    END IF;
    -- A billed item of the loan, past due on the business date of this transaction in the pinned zone.
    SELECT b.* INTO billing FROM lending.instalment_billing b WHERE b.id = NEW.billing_id AND b.loan_id = NEW.loan_id;
    SELECT t.servicing_zone INTO zone FROM lending.loan_terms t WHERE t.terms_version_id = amended.terms_version_id;
    IF billing.id IS NULL OR billing.due_date >= lending.business_date_latest(zone)
            OR NEW.currency <> billing.currency OR NEW.scale <> billing.scale THEN
        RAISE EXCEPTION 'arrears re-scheduled are a past-due billed item''s of the loan, in its currency and scale (P11-TSK-027)';
    END IF;
    -- Exactly the item's whole unpaid billed principal - never its interest or fees (never capitalised), never more.
    IF NEW.principal_minor <> lending.billing_component_billed(NEW.billing_id, 'PRINCIPAL')
                              - lending.billing_component_paid(NEW.billing_id, 'PRINCIPAL') THEN
        RAISE EXCEPTION 'a re-scheduling moves exactly its item''s unpaid billed principal (P11-TSK-027, INV-LND-03)';
    END IF;
    -- One rule 13 entry per amendment.
    IF EXISTS (SELECT 1 FROM lending.loan_amendment_rescheduling r
                WHERE r.amendment_id = NEW.amendment_id AND r.journal_entry_id <> NEW.journal_entry_id) THEN
        RAISE EXCEPTION 'an amendment''s re-scheduled arrears name its one rule 13 entry (P11-TSK-027)';
    END IF;
    NEW.created_at := statement_timestamp();
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- lending.repayment_schedule_continues_its_period, replaced: V023's body and the P11-TSK-036 change alone.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION lending.repayment_schedule_continues_its_period()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    first_due date;
    zone text;
BEGIN
    SELECT i.due_date INTO first_due FROM lending.loan_instalment i WHERE i.schedule_id = NEW.id AND i.sequence = 1;
    SELECT t.servicing_zone INTO zone
      FROM lending.loan_agreement a JOIN lending.loan_terms t ON t.terms_version_id = a.terms_version_id
     WHERE a.id = NEW.agreement_id;
    IF first_due IS NULL OR first_due <= lending.business_date_earliest(zone)
            OR EXISTS (SELECT 1 FROM lending.instalment_billing b WHERE b.loan_id = NEW.loan_id AND b.due_date >= first_due)
            OR EXISTS (SELECT 1 FROM lending.interest_accrual a
                        WHERE a.loan_id = NEW.loan_id AND a.accrual_date >= NEW.start_date
                          AND (a.period_start <> NEW.start_date OR a.period_end <> first_due)) THEN
        RAISE EXCEPTION 'a later schedule version continues the open period: its first due date after today and every billed one, every accrual since its start in its first period (P11-TSK-027, INV-LND-03)';
    END IF;
    RETURN NULL;
END;
$$;
