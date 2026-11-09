-- Bnpl's clock is the database's whatever a role's search_path holds (P15-TSK-040; THREAT_MODEL.md T13, T33; INV-HIST-01).
-- finapp-contract: first-conforming-release=ANY; waives=REPLACED_BODY; reason=each body is its latest definition byte for byte but for pg_catalog. before each clock call
--
-- WHY
--   A function of this schema is invoker's rights with no search_path of its own, and it called the clock functions
--   unqualified. PostgreSQL searches pg_catalog first only when the path does not name it: a session whose path puts
--   a schema of its own before pg_catalog resolves statement_timestamp() to that schema's function, so what a
--   judgement reads as now could be moved without touching a row or a rule.
--
-- WHAT CHANGES, AND ONLY THAT
--   33 function(s), 62 clock call(s): each function's latest definition across the whole chain, byte for byte but for
--   pg_catalog. written before each clock call in code, never in a comment or a string. Taken from V002, V003, V004,
--   V006, V008, V009, V011, V012, V013, V014, V015, V016, V018, V019, V020, V021, the last definition of each.
--   CURRENT_TIMESTAMP, CURRENT_DATE and LOCALTIMESTAMP are grammar, not functions, and are never resolved through a
--   path.
--
-- WHAT IT DOES NOT CHANGE
--   No table, column, row or grant, and no existing trigger. It moves no money. No judgement on any path a production
--   role has: finapp_app holds no TRUNCATE and no CREATE on any schema, and the pg_catalog clocks are the ones its
--   path already resolved to. CREATE OR REPLACE keeps each function's OID, so every trigger stays bound.
--
-- WHAT IT DOES NOT CLAIM
--   A trigger is an object its owner can drop or disable, and a body can be replaced: this closes the unreviewed
--   path, a TRUNCATE that happens to be run or a role's path, not a deliberate migration, which is reviewed and
--   recorded in the history.

-- bnpl.acceptance_history_is_append_only: V006's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION bnpl.acceptance_history_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL purchase or obligation event is append-only (P12-TSK-013, INV-HIST-01)';
    END IF;
    NEW.occurred_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

-- bnpl.bnpl_acceptance_is_born_once: V006's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION bnpl.bnpl_acceptance_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL acceptance''s evidence is immutable from insert, for every role (P12-TSK-013)';
    END IF;
    -- Bound to its agreement version: the same offer and the same two hashes.
    IF NOT EXISTS (SELECT 1 FROM bnpl.bnpl_agreement a
                    WHERE a.agreement_id = NEW.agreement_id AND a.version = NEW.agreement_version
                      AND a.offer_id = NEW.offer_id AND a.terms_sha256 = NEW.terms_sha256
                      AND a.document_sha256 = NEW.document_sha256) THEN
        RAISE EXCEPTION 'a BNPL acceptance is its agreement version''s, over its offer''s two hashes (P12-TSK-013)';
    END IF;
    NEW.accepted_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

-- bnpl.bnpl_agreement_is_born_once: V012's definition, 2 clock call(s) qualified.
CREATE OR REPLACE FUNCTION bnpl.bnpl_agreement_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    obligation bnpl.bnpl_obligation%ROWTYPE;
    offer bnpl.bnpl_offer%ROWTYPE;
    terms bnpl.bnpl_offer_terms%ROWTYPE;
    pinned bnpl.bnpl_terms%ROWTYPE;
    previous bnpl.bnpl_agreement%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL agreement is immutable from insert, for every role (P12-TSK-013, INV-HIST-04)';
    END IF;
    SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id;
    IF NOT FOUND OR obligation.agreement_id <> NEW.agreement_id OR obligation.decision_id <> NEW.decision_id
            OR obligation.product <> NEW.product OR obligation.plan <> NEW.plan THEN
        RAISE EXCEPTION 'a BNPL agreement is its own obligation''s, for its decision, product and plan (P12-TSK-013)';
    END IF;
    IF NEW.version > 1 THEN
        -- A refund's version n+1 (P12-TSK-021): the next, of an ACTIVE obligation, an exact copy of version n's terms,
        -- engines, pins and template - the change is the schedule version it governs, the next one (INV-HIST-04).
        SELECT * INTO previous FROM bnpl.bnpl_agreement a
         WHERE a.obligation_id = NEW.obligation_id AND a.version = NEW.version - 1;
        IF previous.agreement_id IS NULL OR obligation.status <> 'ACTIVE'
                OR NEW.version <> (SELECT max(x.version) + 1 FROM bnpl.bnpl_agreement x
                                    WHERE x.obligation_id = NEW.obligation_id)
                OR NEW.schedule_version <> previous.schedule_version + 1
                OR NEW.offer_id <> previous.offer_id OR NEW.terms_version_id <> previous.terms_version_id
                OR NEW.schedule_engine <> previous.schedule_engine
                OR NEW.accrual_engine IS DISTINCT FROM previous.accrual_engine
                OR NEW.allocation_engine <> previous.allocation_engine
                OR NEW.refund_allocation_engine <> previous.refund_allocation_engine
                OR NEW.fee_schedule_version_id <> previous.fee_schedule_version_id
                OR NEW.agreement_template_id <> previous.agreement_template_id
                OR NEW.agreement_template_version <> previous.agreement_template_version
                OR NEW.canonical_format <> previous.canonical_format OR NEW.canonical_terms <> previous.canonical_terms
                OR NEW.terms_sha256 <> previous.terms_sha256 OR NEW.document_sha256 <> previous.document_sha256 THEN
            RAISE EXCEPTION 'a BNPL agreement version n+1 is the next version of an ACTIVE obligation, a copy of version n governing the next schedule version (P12-TSK-021, INV-HIST-04)';
        END IF;
        NEW.created_at := pg_catalog.clock_timestamp();
        NEW.effective_from := NEW.created_at;
        RETURN NEW;
    END IF;
    -- Version 1 is an EXACT copy of the OFFERED offer's terms the obligation's purchase accepts (INV-HIST-04).
    SELECT * INTO offer FROM bnpl.bnpl_offer o WHERE o.id = NEW.offer_id;
    SELECT * INTO terms FROM bnpl.bnpl_offer_terms t WHERE t.offer_id = NEW.offer_id;
    IF offer.status IS DISTINCT FROM 'OFFERED'
            OR NOT EXISTS (SELECT 1 FROM bnpl.purchase p WHERE p.id = obligation.purchase_id AND p.offer_id = NEW.offer_id)
            OR offer.decision_id <> NEW.decision_id OR offer.terms_version_id <> NEW.terms_version_id
            OR terms.canonical_format <> NEW.canonical_format OR terms.canonical_terms <> NEW.canonical_terms
            OR terms.terms_sha256 <> NEW.terms_sha256 OR terms.document_sha256 <> NEW.document_sha256
            OR terms.schedule_engine <> NEW.schedule_engine THEN
        RAISE EXCEPTION 'a BNPL agreement''s version 1 is an exact copy of its purchase''s OFFERED offer''s terms (P12-TSK-013, INV-HIST-04)';
    END IF;
    -- The engines and the template the pinned terms version names.
    SELECT * INTO pinned FROM bnpl.bnpl_terms t WHERE t.terms_version_id = NEW.terms_version_id;
    IF pinned.allocation_engine <> NEW.allocation_engine
            OR pinned.refund_allocation_engine <> NEW.refund_allocation_engine
            OR pinned.accrual_engine IS DISTINCT FROM NEW.accrual_engine
            OR pinned.agreement_template_id <> NEW.agreement_template_id
            OR pinned.agreement_template_version <> NEW.agreement_template_version THEN
        RAISE EXCEPTION 'a BNPL agreement pins the engines and the template of its terms version (P12-TSK-013, INV-HIST-04)';
    END IF;
    NEW.created_at := pg_catalog.clock_timestamp();
    NEW.effective_from := NEW.created_at;
    RETURN NEW;
END;
$$;

-- bnpl.bnpl_application_event_is_append_only: V003's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION bnpl.bnpl_application_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL application event is append-only (P12-TSK-010, INV-HIST-01)';
    END IF;
    NEW.occurred_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

-- bnpl.bnpl_application_permits_only_machine_edges: V004's definition, 2 clock call(s) qualified.
CREATE OR REPLACE FUNCTION bnpl.bnpl_application_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'SUBMITTED' OR NEW.closed_at IS NOT NULL OR NEW.closure_reason IS NOT NULL THEN
            RAISE EXCEPTION 'a BNPL application is born SUBMITTED and open (P12-TSK-010)';
        END IF;
        -- Born on the product's ACTIVE terms version - the one the submission read FOR SHARE.
        IF NOT EXISTS (SELECT 1 FROM bnpl.bnpl_terms_version v
                        WHERE v.id = NEW.terms_version_id AND v.product = NEW.product AND v.status = 'ACTIVE') THEN
            RAISE EXCEPTION 'a BNPL application records its product''s ACTIVE terms version (P12-TSK-010)';
        END IF;
        -- On a plan that version offers: pay-in-4's one fixed plan, or a pay-in-N term among its allowed terms.
        IF NOT EXISTS (SELECT 1 FROM bnpl.bnpl_terms t
                        WHERE t.terms_version_id = NEW.terms_version_id
                          AND ((NEW.plan = 'PAY_IN_4' AND t.instalment_count = 4)
                               OR (CASE NEW.plan WHEN 'PAY_IN_3_MONTHS' THEN 3 WHEN 'PAY_IN_6_MONTHS' THEN 6
                                       WHEN 'PAY_IN_12_MONTHS' THEN 12 END) = ANY (t.allowed_terms))) THEN
            RAISE EXCEPTION 'a BNPL application''s plan is one its terms version offers (P12-TSK-010)';
        END IF;
        -- The database stamps the submission after the act's locks, and the permit is due at once (the sweep's first
        -- look, P12-TSK-011).
        NEW.submitted_at := pg_catalog.clock_timestamp();
        NEW.next_step_at := NEW.submitted_at;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a BNPL application is never deleted (P12-TSK-010, INV-HIST-01)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.party_id IS DISTINCT FROM OLD.party_id
            OR NEW.product IS DISTINCT FROM OLD.product
            OR NEW.plan IS DISTINCT FROM OLD.plan
            OR NEW.checkout_session_id IS DISTINCT FROM OLD.checkout_session_id
            OR NEW.merchant_id IS DISTINCT FROM OLD.merchant_id
            OR NEW.decision_request_id IS DISTINCT FROM OLD.decision_request_id
            OR NEW.terms_version_id IS DISTINCT FROM OLD.terms_version_id
            OR NEW.submitted_by IS DISTINCT FROM OLD.submitted_by
            OR NEW.submitted_by_type IS DISTINCT FROM OLD.submitted_by_type
            OR NEW.submitted_at IS DISTINCT FROM OLD.submitted_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a BNPL application''s identity and choices are frozen from birth (P12-TSK-010)';
    END IF;
    IF NEW.status = OLD.status THEN
        -- A permit-only update (the sweep's claim, a hint - P12-TSK-011), admitted only while a decision is awaited.
        IF OLD.status <> 'AWAITING_DECISION' OR NEW.closure_reason IS DISTINCT FROM OLD.closure_reason
                OR NEW.closed_at IS DISTINCT FROM OLD.closed_at THEN
            RAISE EXCEPTION 'a BNPL application moves only its permit, and only while AWAITING_DECISION (P12-TSK-010)';
        END IF;
        RETURN NEW;
    END IF;
    -- The machine's edges and no other - a terminal application never moves, and SUBMITTED is never skipped.
    IF NOT ((OLD.status = 'SUBMITTED' AND NEW.status = 'AWAITING_DECISION')
            OR (OLD.status = 'AWAITING_DECISION'
                AND NEW.status IN ('OFFERED', 'DECLINED', 'CLOSED_UNDECIDED', 'WITHDRAWN'))) THEN
        RAISE EXCEPTION 'a BNPL application moves SUBMITTED -> AWAITING_DECISION -> OFFERED | DECLINED | CLOSED_UNDECIDED | WITHDRAWN; % -> % is not an edge (P12-TSK-010)', OLD.status, NEW.status;
    END IF;
    -- -> OFFERED only beside its offer (P12-TSK-011): the offer is born first, in the same transaction.
    IF NEW.status = 'OFFERED' AND NOT EXISTS (SELECT 1 FROM bnpl.bnpl_offer o WHERE o.application_id = NEW.id) THEN
        RAISE EXCEPTION 'a BNPL application is OFFERED only beside its offer (P12-TSK-011)';
    END IF;
    -- The database stamps the closure after the act's locks, never before the submission on a backward clock; nobody
    -- writes it.
    NEW.closed_at := CASE WHEN NEW.status = 'AWAITING_DECISION' THEN NULL
                          ELSE GREATEST(pg_catalog.clock_timestamp(), OLD.submitted_at) END;
    RETURN NEW;
END;
$$;

-- bnpl.bnpl_billing_is_born_once: V014's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION bnpl.bnpl_billing_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    instalment bnpl.bnpl_instalment%ROWTYPE;
    obligation bnpl.bnpl_obligation%ROWTYPE;
    schedule bnpl.bnpl_schedule%ROWTYPE;
    agreement bnpl.bnpl_agreement%ROWTYPE;
    payoff bnpl.bnpl_payoff_execution%ROWTYPE;
    period_from date;
    accrued bigint;
    accrued_through date;
    reach date;
    remaining bigint;
    expected bigint;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL billing is immutable from insert, for every role (P12-TSK-013)';
    END IF;
    -- (B4) The obligation, for every writer: the order of billings and the principal billed are judged under the lock
    -- every servicing act on it holds (the step, the acceptance and the payoff hold it already; re-taking it is free).
    SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id FOR UPDATE;
    IF NOT FOUND OR obligation.status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'a BNPL billing bills an ACTIVE obligation (P12-TSK-015)';
    END IF;
    SELECT * INTO instalment FROM bnpl.bnpl_instalment i
     WHERE i.id = NEW.instalment_id AND i.obligation_id = NEW.obligation_id;
    SELECT * INTO schedule FROM bnpl.bnpl_schedule s WHERE s.id = instalment.schedule_id;
    -- The obligation's payoff execution, if this is its payoff's transaction (an ACTIVE obligation has one only there:
    -- the execution's deferred check closes it in the same commit).
    SELECT * INTO payoff FROM bnpl.bnpl_payoff_execution e WHERE e.obligation_id = NEW.obligation_id;
    -- Of the obligation's LATEST schedule version, on that version's engine, in its currency and scale.
    IF instalment.id IS NULL OR schedule.id IS NULL OR NEW.schedule_id IS DISTINCT FROM schedule.id
            OR NEW.schedule_version IS DISTINCT FROM schedule.schedule_version
            OR schedule.schedule_version <> (SELECT max(x.schedule_version) FROM bnpl.bnpl_schedule x
                                              WHERE x.obligation_id = NEW.obligation_id)
            OR NEW.schedule_engine IS DISTINCT FROM schedule.engine
            OR NEW.currency <> instalment.currency OR NEW.scale <> instalment.scale THEN
        RAISE EXCEPTION 'a BNPL billing bills an instalment of its obligation''s latest schedule version, on its engine, in its currency (P12-TSK-015, INV-BNPL-05)';
    END IF;
    -- Under the agreement version in force - the obligation's highest - and its pinned accrual engine.
    SELECT * INTO agreement FROM bnpl.bnpl_agreement a
     WHERE a.agreement_id = NEW.agreement_id AND a.version = NEW.agreement_version AND a.obligation_id = NEW.obligation_id;
    IF agreement.agreement_id IS NULL
            OR NEW.agreement_version <> (SELECT max(x.version) FROM bnpl.bnpl_agreement x
                                          WHERE x.obligation_id = NEW.obligation_id)
            OR NEW.accrual_engine IS DISTINCT FROM agreement.accrual_engine
            OR NEW.correlation_id IS NULL THEN
        RAISE EXCEPTION 'a BNPL billing names its obligation''s agreement version in force and its pinned accrual engine (P12-TSK-015, INV-LND-09)';
    END IF;
    -- Dated its due date, which has come on the database clock in the pinned zone - never early, except beside the
    -- obligation's payoff execution (P12-TSK-022: the payoff bills every unbilled instalment, whatever its due date).
    IF NEW.value_date IS DISTINCT FROM instalment.due_date
            OR (instalment.due_date > bnpl.business_date_latest(obligation.servicing_zone) AND payoff.id IS NULL) THEN
        RAISE EXCEPTION 'a BNPL billing is dated its instalment''s due date, once that date has come on the database clock - early only beside its obligation''s payoff (P12-TSK-015, P12-TSK-022)';
    END IF;
    -- Oldest first: every earlier instalment of the version billed - judged by its due date (P12-TSK-021: a schedule
    -- version n+1 carries its billed instalments as new rows beside the billings that name version n's) - and never
    -- before the latest billing's date.
    IF EXISTS (SELECT 1 FROM bnpl.bnpl_instalment i
                WHERE i.schedule_id = schedule.id AND i.sequence < instalment.sequence
                  AND NOT EXISTS (SELECT 1 FROM bnpl.bnpl_billing b
                                   WHERE b.obligation_id = NEW.obligation_id AND b.value_date = i.due_date))
            OR EXISTS (SELECT 1 FROM bnpl.bnpl_billing b
                        WHERE b.obligation_id = NEW.obligation_id AND b.value_date >= NEW.value_date) THEN
        RAISE EXCEPTION 'an obligation''s instalments are billed oldest first, one per due date (P12-TSK-015)';
    END IF;
    -- The period it closes: from the previous instalment's due date, the first from the schedule's start.
    period_from := COALESCE((SELECT p.due_date FROM bnpl.bnpl_instalment p
                              WHERE p.schedule_id = schedule.id AND p.sequence = instalment.sequence - 1),
                            schedule.start_date);
    IF NEW.period_start IS DISTINCT FROM period_from THEN
        RAISE EXCEPTION 'a BNPL billing closes its instalment''s own period (P12-TSK-015)';
    END IF;
    -- The interest: pay-in-4 none (A4); pay-in-N exactly the period's posted accruals - interest actually accrued, never the
    -- projection - and only once every date of the period that has elapsed by the billing's reach has accrued (the accrual
    -- gate, for every writer): the due date itself, or, for a payoff's early billing, the payoff's own date - every
    -- elapsed date through G - 1, the record caught up (no rebate arithmetic: what has not accrued is not owed).
    IF schedule.engine = 'BNPL_SCHEDULE_ENGINE_V1' THEN
        accrued := 0;
    ELSE
        SELECT coalesce(sum(a.posted_minor), 0) INTO accrued FROM bnpl.bnpl_interest_accrual a
         WHERE a.obligation_id = NEW.obligation_id AND a.accrual_date >= period_from
           AND a.accrual_date < instalment.due_date;
        SELECT max(a.accrual_date) INTO accrued_through FROM bnpl.bnpl_interest_accrual a
         WHERE a.obligation_id = NEW.obligation_id;
        reach := CASE WHEN payoff.id IS NULL THEN instalment.due_date
                      ELSE LEAST(instalment.due_date, payoff.business_date) END;
        IF reach > period_from AND (accrued_through IS NULL OR accrued_through < reach - 1) THEN
            RAISE EXCEPTION 'a pay-in-N billing waits for every accrual of its period (P12-TSK-015, INV-LND-02)';
        END IF;
    END IF;
    IF NEW.interest_minor <> accrued THEN
        RAISE EXCEPTION 'a BNPL billing''s interest is exactly its period''s accrued interest (P12-TSK-015, INV-LND-02)';
    END IF;
    -- The principal (INV-BNPL-05): never beyond the version's principal not yet billed; the last instalment all of it, every
    -- other least(remaining, max(0, amount - interest)) - for pay-in-4 exactly its scheduled principal.
    remaining := schedule.principal_minor - (SELECT coalesce(sum(b.principal_minor), 0) FROM bnpl.bnpl_billing b
                                               WHERE b.obligation_id = NEW.obligation_id);
    IF instalment.sequence = schedule.instalment_count THEN
        expected := remaining;
    ELSE
        expected := LEAST(remaining, GREATEST(0, instalment.amount_minor - NEW.interest_minor));
    END IF;
    IF remaining < 0 OR NEW.principal_minor IS DISTINCT FROM expected THEN
        RAISE EXCEPTION 'a BNPL billing bills least(remaining, amount - interest) of principal, the last instalment the remainder (P12-TSK-015, INV-BNPL-05)';
    END IF;
    -- The business date it was made on and its instant - the database's, after the lock; a payoff's early billing is
    -- made on the payoff's date, never its future due date, and names the execution beside which it was born.
    IF payoff.id IS NOT NULL AND instalment.due_date > payoff.business_date THEN
        NEW.payoff_execution_id := payoff.id;
        NEW.business_date := GREATEST(bnpl.business_date_latest(obligation.servicing_zone), payoff.business_date);
    ELSE
        NEW.payoff_execution_id := NULL;
        NEW.business_date := GREATEST(bnpl.business_date_latest(obligation.servicing_zone), NEW.value_date);
    END IF;
    NEW.billed_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

-- bnpl.bnpl_collection_attempt_is_born_once: V011's definition, 3 clock call(s) qualified.
CREATE OR REPLACE FUNCTION bnpl.bnpl_collection_attempt_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    obligation bnpl.bnpl_obligation%ROWTYPE;
    terms bnpl.bnpl_terms%ROWTYPE;
    instalment bnpl.bnpl_instalment%ROWTYPE;
    billing bnpl.bnpl_billing%ROWTYPE;
    repayment bnpl.bnpl_repayment%ROWTYPE;
    still_due bigint;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL collection attempt is immutable from insert, for every role (P12-TSK-019, INV-IDEM-02)';
    END IF;
    -- (B4) The obligation, for every writer (T-svc holds it already; re-taking it is free): an ACTIVE one - a closed
    -- obligation takes no money (INV-LIFE-04). The step's closure comes after its collection.
    SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id FOR UPDATE;
    IF NOT FOUND OR obligation.status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'a BNPL collection is attempted only on an ACTIVE obligation (P12-TSK-019, INV-LIFE-04)';
    END IF;
    -- The authority: the agreement version in force pins terms that collect automatically (A7) - the repayment method
    -- the customer accepted with the agreement; there is no door.
    IF NEW.agreement_version <> (SELECT max(x.version) FROM bnpl.bnpl_agreement x WHERE x.obligation_id = NEW.obligation_id) THEN
        RAISE EXCEPTION 'a BNPL collection attempt names its obligation''s agreement version in force (P12-TSK-019)';
    END IF;
    SELECT t.* INTO terms FROM bnpl.bnpl_terms t
      JOIN bnpl.bnpl_agreement a ON a.terms_version_id = t.terms_version_id
     WHERE a.obligation_id = NEW.obligation_id AND a.version = NEW.agreement_version;
    IF terms.terms_version_id IS NULL OR NOT terms.auto_collection THEN
        RAISE EXCEPTION 'a BNPL collection is attempted only under an agreement whose pinned terms collect automatically (P12-TSK-019, A7)';
    END IF;
    IF NEW.outcome = 'PARTIAL' AND NOT terms.partial_collection THEN
        RAISE EXCEPTION 'a partial BNPL collection only where the pinned terms allow it (P12-TSK-019, A7)';
    END IF;
    -- Dated the business date the transaction's database clock showed, in the pinned zone (the range it read).
    IF NEW.attempt_date < LEAST(bnpl.business_date(pg_catalog.transaction_timestamp(), obligation.servicing_zone),
                                bnpl.business_date(pg_catalog.statement_timestamp(), obligation.servicing_zone))
            OR NEW.attempt_date > bnpl.business_date_latest(obligation.servicing_zone) THEN
        RAISE EXCEPTION 'a BNPL collection attempt is dated the business date of the database clock in its pinned zone (P12-TSK-019)';
    END IF;
    -- A billed instalment of its own obligation, due by the attempt's date, in its currency and scale.
    SELECT i.* INTO instalment FROM bnpl.bnpl_instalment i
     WHERE i.id = NEW.instalment_id AND i.obligation_id = NEW.obligation_id;
    SELECT b.* INTO billing FROM bnpl.bnpl_billing b WHERE b.instalment_id = NEW.instalment_id;
    IF instalment.id IS NULL OR billing.id IS NULL OR instalment.due_date > NEW.attempt_date
            OR NEW.currency <> billing.currency OR NEW.scale <> billing.scale THEN
        RAISE EXCEPTION 'a BNPL collection is attempted on a billed instalment of its own obligation, on or after its due date (P12-TSK-019)';
    END IF;
    -- Never back-filled: no attempt of the instalment is dated after this one (the same date is the unique's).
    IF EXISTS (SELECT 1 FROM bnpl.bnpl_collection_attempt c
                WHERE c.instalment_id = NEW.instalment_id AND c.attempt_date > NEW.attempt_date) THEN
        RAISE EXCEPTION 'a BNPL collection attempt is never dated before an earlier attempt of its instalment (P12-TSK-019)';
    END IF;
    -- What was due less what was collected is what the instalment still has due beside it (every payer's rows counted,
    -- this collection's included).
    SELECT sum(bnpl.billing_component_billed(billing.id, c.component) - bnpl.billing_component_paid(billing.id, c.component))
      INTO still_due
      FROM (VALUES ('FEES'), ('INTEREST'), ('PRINCIPAL')) AS c (component);
    IF NEW.due_minor - NEW.collected_minor <> still_due THEN
        RAISE EXCEPTION 'a BNPL collection attempt records what its instalment had due and what it collected (P12-TSK-019, INV-LND-04)';
    END IF;
    -- The money, when it moved: an AUTO_COLLECTION repayment of this obligation under the attempt's key, for the amount
    -- collected, under the same agreement version, never valued before the attempt's date, every line paying this
    -- instalment - never the credit balance, so never more than due.
    IF NEW.repayment_id IS NOT NULL THEN
        SELECT r.* INTO repayment FROM bnpl.bnpl_repayment r WHERE r.id = NEW.repayment_id;
        IF repayment.id IS NULL OR repayment.obligation_id <> NEW.obligation_id OR repayment.kind <> 'AUTO_COLLECTION'
                OR repayment.request_key <> NEW.instalment_id::text || ':' || NEW.attempt_date::text
                OR repayment.amount_minor <> NEW.collected_minor OR repayment.agreement_version <> NEW.agreement_version
                OR repayment.value_date < NEW.attempt_date
                OR EXISTS (SELECT 1 FROM bnpl.bnpl_repayment_allocation a
                            WHERE a.repayment_id = repayment.id
                              AND (a.target <> 'DUE_ITEM' OR a.instalment_id <> NEW.instalment_id)) THEN
            RAISE EXCEPTION 'a BNPL collection names its own AUTO_COLLECTION repayment, which pays only its instalment (P12-TSK-019, INV-LND-04)';
        END IF;
    END IF;
    NEW.created_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

-- bnpl.bnpl_condition_event_is_born_once: V013's definition, 3 clock call(s) qualified.
CREATE OR REPLACE FUNCTION bnpl.bnpl_condition_event_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    obligation bnpl.bnpl_obligation%ROWTYPE;
    terms bnpl.bnpl_terms%ROWTYPE;
    derived integer;
    was_bucket text;
    was_defaulted boolean;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL condition change is immutable from insert, for every role (P12-TSK-020, INV-HIST-01)';
    END IF;
    -- (B4) The obligation, for every writer (T-svc holds it already; re-taking it is free): an ACTIVE one - a closed
    -- obligation takes no condition change (INV-LND-08 by analogy).
    SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id FOR UPDATE;
    IF NOT FOUND OR obligation.status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'a BNPL condition is recorded only for an ACTIVE obligation (P12-TSK-020, INV-LND-08)';
    END IF;
    -- The agreement version in force, whose pinned terms judge the condition.
    IF NEW.agreement_id <> obligation.agreement_id
            OR NEW.agreement_version <> (SELECT max(x.version) FROM bnpl.bnpl_agreement x
                                          WHERE x.obligation_id = NEW.obligation_id) THEN
        RAISE EXCEPTION 'a BNPL condition names its obligation''s agreement version in force (P12-TSK-020)';
    END IF;
    SELECT t.* INTO terms FROM bnpl.bnpl_terms t
      JOIN bnpl.bnpl_agreement a ON a.terms_version_id = t.terms_version_id
     WHERE a.obligation_id = NEW.obligation_id AND a.version = NEW.agreement_version;
    -- Dated the business date the transaction's database clock showed, in the pinned zone.
    IF NEW.business_date < LEAST(bnpl.business_date(pg_catalog.transaction_timestamp(), obligation.servicing_zone),
                                 bnpl.business_date(pg_catalog.statement_timestamp(), obligation.servicing_zone))
            OR NEW.business_date > bnpl.business_date_latest(obligation.servicing_zone) THEN
        RAISE EXCEPTION 'a BNPL condition is dated the business date of the database clock in its pinned zone (P12-TSK-020)';
    END IF;
    -- Appended in date order: never dated before a change already recorded (the same date is the unique's, per kind).
    IF EXISTS (SELECT 1 FROM bnpl.bnpl_condition_event e
                WHERE e.obligation_id = NEW.obligation_id AND e.business_date > NEW.business_date) THEN
        RAISE EXCEPTION 'a BNPL condition is never dated before a change already recorded (P12-TSK-020, INV-HIST-01)';
    END IF;
    -- DPD derived from the rows, never a writer's figure; the bucket that DPD's under the pinned bounds.
    derived := bnpl.days_past_due(NEW.obligation_id, NEW.business_date);
    IF NEW.days_past_due <> derived
            OR NEW.bucket <> bnpl.delinquency_bucket(derived, terms.delinquency_bounds) THEN
        RAISE EXCEPTION 'a BNPL condition''s days past due and bucket are derived from its billed and settled rows (P12-TSK-020, INV-LND-12)';
    END IF;
    -- The latest recorded condition of each aspect: CURRENT and not defaulted before any.
    SELECT coalesce((SELECT e.bucket FROM bnpl.bnpl_condition_event e
                      WHERE e.obligation_id = NEW.obligation_id AND e.kind = 'DELINQUENCY'
                      ORDER BY e.business_date DESC LIMIT 1), 'CURRENT'),
           coalesce((SELECT e.defaulted FROM bnpl.bnpl_condition_event e
                      WHERE e.obligation_id = NEW.obligation_id AND e.kind = 'DEFAULT'
                      ORDER BY e.business_date DESC LIMIT 1), false)
      INTO was_bucket, was_defaulted;
    -- The default flag: set at the pinned threshold (A14), held while past due, cleared at cure - no probation.
    IF NEW.defaulted <> (derived >= terms.default_threshold_days OR (was_defaulted AND derived > 0)) THEN
        RAISE EXCEPTION 'a BNPL condition''s default flag is set at the default threshold and cleared at cure (P12-TSK-020, A14)';
    END IF;
    -- Only on a change of its kind, from the latest recorded one.
    IF (NEW.kind = 'DELINQUENCY' AND (NEW.previous_bucket <> was_bucket OR NEW.bucket = was_bucket))
            OR (NEW.kind = 'DEFAULT' AND NEW.defaulted = was_defaulted) THEN
        RAISE EXCEPTION 'a BNPL condition is recorded only on a change, from the latest recorded one (P12-TSK-020, INV-LND-12)';
    END IF;
    NEW.created_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

-- bnpl.bnpl_credit_balance_application_is_born_once: V008's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION bnpl.bnpl_credit_balance_application_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    obligation bnpl.bnpl_obligation%ROWTYPE;
    value_floor date;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a credit-balance application is immutable from insert, for every role (P12-TSK-015)';
    END IF;
    -- (B4) The obligation, for every writer: a catch-up's number and the floor are judged under the lock every payer of it
    -- holds (lending V029's shape; the step holds it already, re-taking it is free).
    SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id FOR UPDATE;
    IF NOT FOUND OR obligation.status <> 'ACTIVE' OR obligation.currency <> NEW.currency THEN
        RAISE EXCEPTION 'a credit balance is applied only on an ACTIVE obligation, in its currency (P12-TSK-015)';
    END IF;
    IF NEW.billing_id IS NOT NULL THEN
        IF NOT EXISTS (SELECT 1 FROM bnpl.bnpl_billing b WHERE b.id = NEW.billing_id AND b.obligation_id = NEW.obligation_id) THEN
            RAISE EXCEPTION 'a credit-balance application is born at its own obligation''s billing (P12-TSK-015)';
        END IF;
    ELSIF NEW.catch_up_sequence IS DISTINCT FROM 1 + coalesce(
            (SELECT max(c.catch_up_sequence) FROM bnpl.bnpl_credit_balance_application c
              WHERE c.obligation_id = NEW.obligation_id), 0) THEN
        -- The obligation's next - numbered in order, never a gap, never a second at one number.
        RAISE EXCEPTION 'a catch-up credit-balance application is its obligation''s next (P12-TSK-015, the Phase 11 gate''s R2)';
    END IF;
    -- Under the agreement version in force and its pinned allocation engine.
    IF obligation.agreement_id <> NEW.agreement_id
            OR NEW.agreement_version <> (SELECT max(x.version) FROM bnpl.bnpl_agreement x
                                          WHERE x.obligation_id = NEW.obligation_id)
            OR NOT EXISTS (SELECT 1 FROM bnpl.bnpl_agreement a WHERE a.agreement_id = NEW.agreement_id
                              AND a.version = NEW.agreement_version AND a.allocation_engine = NEW.allocation_engine) THEN
        RAISE EXCEPTION 'an application names its obligation''s agreement version in force and its pinned allocation engine (P12-TSK-015, INV-LND-09)';
    END IF;
    -- The Phase 11 gate's R1 from the first table: never a date the record has closed, never after the later of the latest
    -- date the clock showed and the floor.
    value_floor := bnpl.principal_value_floor(NEW.obligation_id);
    IF NEW.value_date < value_floor
            OR NEW.value_date > GREATEST(bnpl.business_date_latest(obligation.servicing_zone), value_floor) THEN
        RAISE EXCEPTION 'a credit-balance application is value-dated on or after its obligation''s floor and never after today (P12-TSK-015, the Phase 11 gate''s R1)';
    END IF;
    NEW.created_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

-- bnpl.bnpl_dispute_event_is_append_only: V015's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION bnpl.bnpl_dispute_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL dispute''s history is append-only, for every role (P12-TSK-023, INV-HIST-01)';
    END IF;
    -- The state it records is the dispute's own, written in the edge's transaction after the edge.
    IF NOT EXISTS (SELECT 1 FROM bnpl.bnpl_dispute d WHERE d.id = NEW.dispute_id AND d.status = NEW.to_status) THEN
        RAISE EXCEPTION 'a BNPL dispute''s history records the state its dispute is in (P12-TSK-023)';
    END IF;
    NEW.occurred_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

-- bnpl.bnpl_dispute_permits_only_machine_edges: V018's definition, 2 clock call(s) qualified.
CREATE OR REPLACE FUNCTION bnpl.bnpl_dispute_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    purchase bnpl.purchase%ROWTYPE;
    obligation bnpl.bnpl_obligation%ROWTYPE;
    terms bnpl.bnpl_terms%ROWTYPE;
    proposal bnpl.bnpl_dispute_proposal%ROWTYPE;
    disputed bigint;
    now_at timestamptz;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a BNPL dispute is never deleted (P12-TSK-023, INV-HIST-01)';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'OPEN' OR NEW.merchant_statement IS NOT NULL OR NEW.merchant_responded_at IS NOT NULL
                OR NEW.resolved_by IS NOT NULL OR NEW.resolution_reason IS NOT NULL OR NEW.resolved_at IS NOT NULL
                OR NEW.resolved_business_date IS NOT NULL OR NEW.refund_id IS NOT NULL OR NEW.proposal_id IS NOT NULL THEN
            RAISE EXCEPTION 'a BNPL dispute is born OPEN, unanswered and unresolved (P12-TSK-023)';
        END IF;
        -- (B5) then (B4), for every writer: T-dsp holds both already; re-taking them is free.
        SELECT * INTO purchase FROM bnpl.purchase p WHERE p.id = NEW.purchase_id FOR UPDATE;
        SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.purchase_id = NEW.purchase_id FOR UPDATE;
        IF purchase.id IS NULL OR purchase.status <> 'FINANCED' OR purchase.merchant_id <> NEW.merchant_id
                OR purchase.party_id <> NEW.party_id OR obligation.id IS DISTINCT FROM NEW.obligation_id
                OR obligation.agreement_id <> NEW.agreement_id THEN
            RAISE EXCEPTION 'a BNPL dispute contests a FINANCED purchase of its own party and merchant, against its own obligation and agreement (P12-TSK-023, INV-MER-01)';
        END IF;
        -- The terms version the agreement in force pins: its response window (OQ-10) and its four-eyes threshold.
        IF NEW.terms_version_id <> (SELECT a.terms_version_id FROM bnpl.bnpl_agreement a
                                     WHERE a.obligation_id = obligation.id ORDER BY a.version DESC LIMIT 1) THEN
            RAISE EXCEPTION 'a BNPL dispute names the terms version its agreement in force pins (P12-TSK-023, INV-HIST-04)';
        END IF;
        SELECT * INTO terms FROM bnpl.bnpl_terms t WHERE t.terms_version_id = NEW.terms_version_id;
        -- The database stamps the opening after the locks, its business date in the pinned zone, the deadline and the
        -- sweep's permit at it.
        NEW.opened_at := pg_catalog.clock_timestamp();
        NEW.opened_business_date := bnpl.business_date(NEW.opened_at, obligation.servicing_zone);
        NEW.response_deadline_at := NEW.opened_at + make_interval(days => terms.dispute_response_days);
        NEW.next_sweep_at := NEW.response_deadline_at;
        RETURN NEW;
    END IF;
    -- UPDATE: the identity, the claim's context and the opening are frozen from birth.
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.purchase_id IS DISTINCT FROM OLD.purchase_id
            OR NEW.obligation_id IS DISTINCT FROM OLD.obligation_id OR NEW.merchant_id IS DISTINCT FROM OLD.merchant_id
            OR NEW.party_id IS DISTINCT FROM OLD.party_id OR NEW.agreement_id IS DISTINCT FROM OLD.agreement_id
            OR NEW.terms_version_id IS DISTINCT FROM OLD.terms_version_id
            OR NEW.reason_category IS DISTINCT FROM OLD.reason_category OR NEW.reason IS DISTINCT FROM OLD.reason
            OR NEW.request_key IS DISTINCT FROM OLD.request_key OR NEW.opened_at IS DISTINCT FROM OLD.opened_at
            OR NEW.opened_business_date IS DISTINCT FROM OLD.opened_business_date
            OR NEW.response_deadline_at IS DISTINCT FROM OLD.response_deadline_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a BNPL dispute''s identity, reason and deadline are frozen from birth (P12-TSK-023)';
    END IF;
    -- A permit-only move: only an OPEN dispute has a permit, and nothing but the permit moves.
    IF NEW.status = OLD.status THEN
        IF OLD.status <> 'OPEN' OR NEW.next_sweep_at IS NOT DISTINCT FROM OLD.next_sweep_at
                OR NEW.merchant_statement IS DISTINCT FROM OLD.merchant_statement
                OR NEW.merchant_responded_at IS DISTINCT FROM OLD.merchant_responded_at
                OR NEW.resolved_by IS DISTINCT FROM OLD.resolved_by
                OR NEW.resolution_reason IS DISTINCT FROM OLD.resolution_reason
                OR NEW.resolved_at IS DISTINCT FROM OLD.resolved_at
                OR NEW.resolved_business_date IS DISTINCT FROM OLD.resolved_business_date
                OR NEW.refund_id IS DISTINCT FROM OLD.refund_id OR NEW.proposal_id IS DISTINCT FROM OLD.proposal_id THEN
            RAISE EXCEPTION 'only an OPEN BNPL dispute has a permit to move, and only its permit; % is not (P12-TSK-023)', OLD.status;
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status NOT IN ('OPEN', 'MERCHANT_RESPONDED') THEN
        RAISE EXCEPTION 'a % BNPL dispute is terminal (P12-TSK-023, INV-LIFE-04)', OLD.status;
    END IF;
    IF NEW.next_sweep_at IS DISTINCT FROM OLD.next_sweep_at THEN
        RAISE EXCEPTION 'a BNPL dispute''s edge moves no permit (P12-TSK-023)';
    END IF;
    SELECT o.* INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = OLD.obligation_id;
    -- V018 (A5-F7): the cumulative refunded-by-dispute amount of the purchase, this dispute's claim included.
    disputed := bnpl.dispute_four_eyes_amount(OLD.id);
    SELECT * INTO terms FROM bnpl.bnpl_terms t WHERE t.terms_version_id = OLD.terms_version_id;
    now_at := pg_catalog.clock_timestamp();
    -- The merchant's answer, before the deadline: its statement recorded, the hold standing.
    IF NEW.status = 'MERCHANT_RESPONDED' THEN
        IF OLD.status <> 'OPEN' OR NEW.merchant_statement IS NULL OR OLD.merchant_statement IS NOT NULL
                OR OLD.response_deadline_at <= now_at OR NEW.resolved_by IS NOT NULL OR NEW.refund_id IS NOT NULL
                OR NEW.proposal_id IS NOT NULL OR NEW.resolution_reason IS NOT NULL THEN
            RAISE EXCEPTION 'a BNPL dispute moves OPEN -> MERCHANT_RESPONDED only by the merchant''s statement, before the response deadline on the database clock (P12-TSK-023, OQ-10)';
        END IF;
        NEW.merchant_responded_at := now_at;
        RETURN NEW;
    END IF;
    IF NEW.status = 'RESOLVED_REFUND' AND OLD.status = 'OPEN' THEN
        -- The merchant's acceptance before the deadline, or its silence at it: complementary conditionals on one clock,
        -- judged under the dispute's row lock - exactly one of the response and the sweep wins (OQ-10).
        IF NEW.resolved_by = 'MERCHANT:' || OLD.merchant_id::text THEN
            IF OLD.response_deadline_at <= now_at OR NEW.merchant_statement IS NULL THEN
                RAISE EXCEPTION 'a merchant accepts a BNPL dispute only before the response deadline, with its statement (P12-TSK-023, OQ-10)';
            END IF;
            NEW.merchant_responded_at := now_at;
        ELSIF NEW.resolved_by = 'SYSTEM' THEN
            IF OLD.response_deadline_at > now_at OR NEW.merchant_statement IS NOT NULL THEN
                RAISE EXCEPTION 'a BNPL dispute lapses to a refund only once its response deadline has passed on the database clock (P12-TSK-023, OQ-10)';
            END IF;
        ELSE
            RAISE EXCEPTION 'an OPEN BNPL dispute resolves as a refund only by its merchant or at its deadline (P12-TSK-023)';
        END IF;
        IF NEW.proposal_id IS NOT NULL OR NEW.refund_id IS NULL OR NEW.resolution_reason IS NOT NULL THEN
            RAISE EXCEPTION 'a BNPL dispute resolved as a refund names its refund, and no proposal (P12-TSK-023, INV-BNPL-08)';
        END IF;
    ELSIF NEW.status IN ('RESOLVED_REFUND', 'RESOLVED_UPHELD') THEN
        -- The operator's decision after the merchant answered, reasoned - four-eyes above the pinned threshold.
        IF OLD.status <> 'MERCHANT_RESPONDED' OR NEW.resolved_by !~ '^OPERATOR:.{1,200}$'
                OR NEW.resolution_reason IS NULL
                OR NEW.merchant_statement IS DISTINCT FROM OLD.merchant_statement
                OR NEW.merchant_responded_at IS DISTINCT FROM OLD.merchant_responded_at THEN
            RAISE EXCEPTION 'a BNPL dispute the merchant answered is resolved by an operator, reasoned (P12-TSK-023, ADR-0106 decision 5)';
        END IF;
        IF disputed > terms.dispute_four_eyes_threshold_minor THEN
            SELECT * INTO proposal FROM bnpl.bnpl_dispute_proposal x WHERE x.id = NEW.proposal_id;
            IF proposal.id IS NULL OR proposal.dispute_id <> OLD.id OR proposal.status <> 'APPROVED'
                    OR proposal.outcome <> NEW.status OR proposal.decided_by <> NEW.resolved_by
                    OR proposal.proposed_by = proposal.decided_by THEN
                RAISE EXCEPTION 'a BNPL dispute above its four-eyes threshold - its purchase''s refunds by dispute and its own claim - is resolved by a proposal another operator approved (P12-TSK-023, bnpl V018, INV-AUD-04)';
            END IF;
        ELSIF NEW.proposal_id IS NOT NULL THEN
            RAISE EXCEPTION 'a BNPL dispute at or below its four-eyes threshold is resolved by one operator, no proposal (P12-TSK-023)';
        END IF;
    ELSIF NEW.status = 'WITHDRAWN' THEN
        IF NEW.resolved_by <> 'CUSTOMER:' || OLD.party_id::text OR NEW.refund_id IS NOT NULL
                OR NEW.proposal_id IS NOT NULL OR NEW.merchant_statement IS DISTINCT FROM OLD.merchant_statement
                OR NEW.merchant_responded_at IS DISTINCT FROM OLD.merchant_responded_at THEN
            RAISE EXCEPTION 'a BNPL dispute is withdrawn only by its own customer, refunding nothing (P12-TSK-023)';
        END IF;
    ELSE
        RAISE EXCEPTION 'a BNPL dispute moves % -> %: not an edge (P12-TSK-023, INV-LIFE-02)', OLD.status, NEW.status;
    END IF;
    -- The resolution's instant and business date: the database's, after the act's locks.
    NEW.resolved_at := GREATEST(now_at, OLD.opened_at);
    NEW.resolved_business_date := bnpl.business_date(NEW.resolved_at, obligation.servicing_zone);
    RETURN NEW;
END;
$$;

-- bnpl.bnpl_dispute_proposal_permits_only_machine_edges: V018's definition, 2 clock call(s) qualified.
CREATE OR REPLACE FUNCTION bnpl.bnpl_dispute_proposal_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    dispute bnpl.bnpl_dispute%ROWTYPE;
    disputed bigint;
    threshold bigint;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a BNPL dispute proposal is never deleted (P12-TSK-023, INV-HIST-01)';
    END IF;
    SELECT * INTO dispute FROM bnpl.bnpl_dispute d WHERE d.id = NEW.dispute_id;
    IF TG_OP = 'INSERT' THEN
        -- V018 (A5-F7): the cumulative refunded-by-dispute amount of the purchase, this dispute's claim included.
        disputed := bnpl.dispute_four_eyes_amount(NEW.dispute_id);
        SELECT t.dispute_four_eyes_threshold_minor INTO threshold FROM bnpl.bnpl_terms t
         WHERE t.terms_version_id = dispute.terms_version_id;
        IF NEW.status <> 'PROPOSED' OR NEW.decided_by IS NOT NULL OR dispute.id IS NULL
                OR dispute.status <> 'MERCHANT_RESPONDED' OR disputed <= threshold THEN
            RAISE EXCEPTION 'a BNPL dispute proposal is born PROPOSED, for a dispute the merchant answered whose amount - its purchase''s refunds by dispute and its own claim - is above its four-eyes threshold (P12-TSK-023, bnpl V018)';
        END IF;
        NEW.proposed_at := pg_catalog.clock_timestamp();
        RETURN NEW;
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.dispute_id IS DISTINCT FROM OLD.dispute_id
            OR NEW.outcome IS DISTINCT FROM OLD.outcome OR NEW.proposed_by IS DISTINCT FROM OLD.proposed_by
            OR NEW.proposal_reason IS DISTINCT FROM OLD.proposal_reason OR NEW.proposed_at IS DISTINCT FROM OLD.proposed_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a BNPL dispute proposal''s identity is frozen from birth (P12-TSK-023)';
    END IF;
    IF OLD.status <> 'PROPOSED' OR NEW.status NOT IN ('APPROVED', 'REJECTED') THEN
        RAISE EXCEPTION 'a BNPL dispute proposal moves PROPOSED -> APPROVED | REJECTED only (P12-TSK-023, INV-LIFE-02)';
    END IF;
    IF NEW.status = 'APPROVED' AND dispute.status <> 'MERCHANT_RESPONDED' THEN
        RAISE EXCEPTION 'a BNPL dispute proposal is approved only while its dispute awaits resolution (P12-TSK-023)';
    END IF;
    NEW.decided_at := GREATEST(pg_catalog.clock_timestamp(), OLD.proposed_at);
    RETURN NEW;
END;
$$;

-- bnpl.bnpl_fee_assessment_is_born_once: V013's definition, 3 clock call(s) qualified.
CREATE OR REPLACE FUNCTION bnpl.bnpl_fee_assessment_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    obligation bnpl.bnpl_obligation%ROWTYPE;
    terms bnpl.bnpl_terms%ROWTYPE;
    billing bnpl.bnpl_billing%ROWTYPE;
    assessed bigint;
    owing bigint;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL fee assessment is immutable from insert, for every role (P12-TSK-020, INV-HIST-01)';
    END IF;
    -- (B4) The obligation, for every writer: the cap is judged under the lock every act on it holds.
    SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id FOR UPDATE;
    IF NOT FOUND OR obligation.status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'a BNPL fee is assessed only on an ACTIVE obligation (P12-TSK-020, INV-LIFE-04)';
    END IF;
    -- A billed instalment of its own obligation, in its currency and scale.
    SELECT b.* INTO billing FROM bnpl.bnpl_billing b
     WHERE b.instalment_id = NEW.instalment_id AND b.obligation_id = NEW.obligation_id;
    IF billing.id IS NULL OR NEW.currency <> billing.currency OR NEW.scale <> billing.scale THEN
        RAISE EXCEPTION 'a BNPL fee is assessed on a billed instalment of its own obligation, in its currency (P12-TSK-020)';
    END IF;
    -- Under the agreement version in force; its pinned terms decide.
    IF NEW.agreement_id <> obligation.agreement_id
            OR NEW.agreement_version <> (SELECT max(x.version) FROM bnpl.bnpl_agreement x
                                          WHERE x.obligation_id = NEW.obligation_id) THEN
        RAISE EXCEPTION 'a BNPL fee names its obligation''s agreement version in force (P12-TSK-020)';
    END IF;
    SELECT t.* INTO terms FROM bnpl.bnpl_terms t
      JOIN bnpl.bnpl_agreement a ON a.terms_version_id = t.terms_version_id
     WHERE a.obligation_id = NEW.obligation_id AND a.version = NEW.agreement_version;
    -- Dated the business date the transaction's database clock showed, in the pinned zone - never early, never back-dated.
    IF NEW.business_date < LEAST(bnpl.business_date(pg_catalog.transaction_timestamp(), obligation.servicing_zone),
                                 bnpl.business_date(pg_catalog.statement_timestamp(), obligation.servicing_zone))
            OR NEW.business_date > bnpl.business_date_latest(obligation.servicing_zone) THEN
        RAISE EXCEPTION 'a BNPL fee is dated the business date of the database clock in its pinned zone (P12-TSK-020)';
    END IF;
    -- On the day the instalment's own age reaches the pinned late-fee day, or later (a missed day is caught up) - never in
    -- grace (late_fee_day > grace_days, bnpl V002).
    IF NEW.business_date - billing.value_date < terms.late_fee_day THEN
        RAISE EXCEPTION 'a BNPL late fee is assessed only once its instalment''s age has reached the pinned late-fee day (P12-TSK-020, A13)';
    END IF;
    -- At most the pinned fee; the agreement's fees within the pinned cap.
    SELECT coalesce(sum(f.amount_minor), 0) INTO assessed FROM bnpl.bnpl_fee_assessment f
     WHERE f.obligation_id = NEW.obligation_id;
    IF NEW.amount_minor > terms.late_fee_minor OR assessed + NEW.amount_minor > terms.late_fee_cap_minor THEN
        RAISE EXCEPTION 'a BNPL late fee is at most the pinned fee, and an agreement''s fees at most the pinned cap (P12-TSK-020, A13)';
    END IF;
    -- Only on an instalment still owing interest or principal: a settled instalment is never charged late.
    SELECT sum(bnpl.billing_component_billed(billing.id, c.component) - bnpl.billing_component_paid(billing.id, c.component))
      INTO owing
      FROM (VALUES ('INTEREST'), ('PRINCIPAL')) AS c (component);
    IF coalesce(owing, 0) <= 0 THEN
        RAISE EXCEPTION 'a BNPL late fee is assessed only on an instalment still owing interest or principal (P12-TSK-020, A13)';
    END IF;
    NEW.created_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

-- bnpl.bnpl_interest_accrual_is_born_once: V009's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION bnpl.bnpl_interest_accrual_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    obligation bnpl.bnpl_obligation%ROWTYPE;
    agreement bnpl.bnpl_agreement%ROWTYPE;
    schedule bnpl.bnpl_schedule%ROWTYPE;
    previous_cumulative numeric(40, 20);
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'an interest accrual is immutable from insert, for every role (P12-TSK-015, INV-LND-02)';
    END IF;
    -- (B4) The obligation, for every writer: every rank below is judged under the lock every servicing act on it holds
    -- (T-acc holds it already; re-taking it is free).
    SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id FOR UPDATE;
    -- A pay-in-N obligation in servicing - pay-in-4 is interest-free (A4) and never accrues.
    IF NOT FOUND OR obligation.status <> 'ACTIVE' OR obligation.product <> 'BNPL_PAY_IN_N'
            OR obligation.currency <> NEW.currency OR obligation.agreement_id <> NEW.agreement_id THEN
        RAISE EXCEPTION 'interest accrues only on an ACTIVE pay-in-N obligation, in its currency, under its agreement (P12-TSK-015)';
    END IF;
    -- Under the agreement version in force - the obligation's highest - and the accrual engine it pins (INV-LND-09).
    SELECT * INTO agreement FROM bnpl.bnpl_agreement a
     WHERE a.agreement_id = NEW.agreement_id AND a.version = NEW.agreement_version AND a.obligation_id = NEW.obligation_id;
    IF agreement.agreement_id IS NULL
            OR NEW.agreement_version <> (SELECT max(x.version) FROM bnpl.bnpl_agreement x
                                          WHERE x.obligation_id = NEW.obligation_id)
            OR agreement.accrual_engine IS DISTINCT FROM NEW.accrual_engine THEN
        RAISE EXCEPTION 'an accrual names its obligation''s agreement version in force and the accrual engine it pins (P12-TSK-017, INV-LND-09)';
    END IF;
    -- Never early (INV-LND-10 by analogy): the date has ended on the database clock in the pinned zone; never before the
    -- purchase's business date.
    IF NEW.accrual_date >= bnpl.business_date_latest(obligation.servicing_zone)
            OR NEW.accrual_date < obligation.purchase_date THEN
        RAISE EXCEPTION 'a date accrues only once it has ended on the database clock, from the purchase''s business date (P12-TSK-015)';
    END IF;
    -- Within an instalment period of the latest schedule version: [the previous due date or the schedule's start, the
    -- instalment's due date) - so nothing accrues at or after the final due date (P12-TSK-017).
    SELECT * INTO schedule FROM bnpl.bnpl_schedule s
     WHERE s.obligation_id = NEW.obligation_id
       AND s.schedule_version = (SELECT max(x.schedule_version) FROM bnpl.bnpl_schedule x
                                  WHERE x.obligation_id = NEW.obligation_id);
    IF schedule.id IS NULL OR schedule.start_date <> obligation.purchase_date OR NOT EXISTS (
            SELECT 1 FROM bnpl.bnpl_instalment i
             WHERE i.schedule_id = schedule.id AND i.due_date = NEW.period_end
               AND NEW.period_start = COALESCE((SELECT p.due_date FROM bnpl.bnpl_instalment p
                                                 WHERE p.schedule_id = schedule.id AND p.sequence = i.sequence - 1),
                                               schedule.start_date)) THEN
        RAISE EXCEPTION 'an accrual lies in an instalment period of its obligation''s latest schedule version, never at or after the final due date (P12-TSK-017)';
    END IF;
    -- Oldest first, each date once.
    IF EXISTS (SELECT 1 FROM bnpl.bnpl_interest_accrual a
                WHERE a.obligation_id = NEW.obligation_id AND a.accrual_date >= NEW.accrual_date) THEN
        RAISE EXCEPTION 'an obligation''s dates accrue oldest first, each once (P12-TSK-015, INV-LND-02)';
    END IF;
    -- Never into a period already billed: a billing made its period's accrued interest due, so billed interest stays the
    -- accrued interest for ever (lending V012's rule).
    IF EXISTS (SELECT 1 FROM bnpl.bnpl_billing b
                WHERE b.obligation_id = NEW.obligation_id AND b.value_date > NEW.accrual_date) THEN
        RAISE EXCEPTION 'a date inside a period already billed never accrues afterwards (P12-TSK-015)';
    END IF;
    -- ACCRUAL_ENGINE_V1's chain: the period's exact cumulative figure never falls within it.
    SELECT a.cumulative_exact INTO previous_cumulative FROM bnpl.bnpl_interest_accrual a
     WHERE a.obligation_id = NEW.obligation_id AND a.period_start = NEW.period_start
     ORDER BY a.accrual_date DESC LIMIT 1;
    IF previous_cumulative IS NOT NULL AND NEW.cumulative_exact < previous_cumulative THEN
        RAISE EXCEPTION 'a period''s cumulative accrued interest never falls within it (P12-TSK-017, INV-LND-09)';
    END IF;
    NEW.created_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

-- bnpl.bnpl_obligation_closure_is_born_once: V014's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION bnpl.bnpl_obligation_closure_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    obligation bnpl.bnpl_obligation%ROWTYPE;
    schedule bnpl.bnpl_schedule%ROWTYPE;
    purchase_status text;
    paid_off boolean;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL closure is immutable from insert, for every role (P12-TSK-018, INV-LIFE-04)';
    END IF;
    -- (B4) The obligation, for every writer; an ACTIVE obligation closing REPAID, or - P12-TSK-021 - REFUNDED when its
    -- purchase is FULLY_REFUNDED, or REFUNDED_PARTIALLY_REPAID beside a partial refund of a purchase not fully refunded,
    -- or - P12-TSK-022 - PAID_OFF_EARLY beside its payoff execution; beside an execution nothing else.
    SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id FOR UPDATE;
    SELECT p.status INTO purchase_status FROM bnpl.purchase p WHERE p.id = obligation.purchase_id;
    paid_off := EXISTS (SELECT 1 FROM bnpl.bnpl_payoff_execution e WHERE e.obligation_id = NEW.obligation_id);
    IF obligation.id IS NULL OR obligation.status <> 'ACTIVE'
            OR NOT ((paid_off AND NEW.reason = 'PAID_OFF_EARLY' AND NEW.refund_id IS NULL)
                    OR (NOT paid_off
                        AND ((NEW.reason = 'REPAID' AND purchase_status <> 'FULLY_REFUNDED')
                             OR (NEW.reason = 'REFUNDED' AND purchase_status = 'FULLY_REFUNDED')
                             OR (NEW.reason = 'REFUNDED_PARTIALLY_REPAID' AND purchase_status = 'FINANCED'
                                 AND NEW.refund_id IS NOT NULL)))) THEN
        RAISE EXCEPTION 'an ACTIVE BNPL obligation closes REPAID, REFUNDED (its purchase fully refunded), REFUNDED_PARTIALLY_REPAID (beside a partial refund) or PAID_OFF_EARLY (beside its payoff execution), and nothing else does (P12-TSK-022, INV-LND-08)';
    END IF;
    SELECT * INTO schedule FROM bnpl.bnpl_schedule s
     WHERE s.obligation_id = NEW.obligation_id
       AND s.schedule_version = (SELECT max(x.schedule_version) FROM bnpl.bnpl_schedule x
                                  WHERE x.obligation_id = NEW.obligation_id);
    IF schedule.id IS NULL OR NEW.currency <> obligation.currency OR NEW.scale <> schedule.scale THEN
        RAISE EXCEPTION 'a BNPL closure refunds in its obligation''s currency and scale (P12-TSK-018)';
    END IF;
    IF NEW.repayment_id IS NOT NULL
            AND NOT EXISTS (SELECT 1 FROM bnpl.bnpl_repayment r
                             WHERE r.id = NEW.repayment_id AND r.obligation_id = NEW.obligation_id
                               AND (NEW.reason <> 'PAID_OFF_EARLY' OR r.kind = 'PAYOFF')) THEN
        RAISE EXCEPTION 'a BNPL closure names a repayment of its own obligation - a payoff''s its PAYOFF repayment (P12-TSK-018, P12-TSK-022)';
    END IF;
    IF NEW.refund_id IS NOT NULL
            AND NOT EXISTS (SELECT 1 FROM bnpl.bnpl_refund r
                             WHERE r.id = NEW.refund_id AND r.obligation_id = NEW.obligation_id) THEN
        RAISE EXCEPTION 'a BNPL closure names a refund of its own obligation (P12-TSK-021)';
    END IF;
    -- The rows' side of "every receivable zero": every instalment of the latest schedule billed - by its due date
    -- (P12-TSK-021) - and every billed component settled in full by every payer and every refund. The ledger's side is
    -- the closing transaction's own, under B4.
    IF EXISTS (SELECT 1 FROM bnpl.bnpl_instalment i
                WHERE i.schedule_id = schedule.id
                  AND NOT EXISTS (SELECT 1 FROM bnpl.bnpl_billing b
                                   WHERE b.obligation_id = NEW.obligation_id AND b.value_date = i.due_date)) THEN
        RAISE EXCEPTION 'a BNPL obligation closes only once every instalment of its schedule is billed (P12-TSK-018, INV-LND-08)';
    END IF;
    IF EXISTS (SELECT 1 FROM bnpl.bnpl_billing b
                 CROSS JOIN (VALUES ('FEES'), ('INTEREST'), ('PRINCIPAL')) AS c (component)
                WHERE b.obligation_id = NEW.obligation_id
                  AND bnpl.billing_component_paid(b.id, c.component)
                      <> bnpl.billing_component_billed(b.id, c.component)) THEN
        RAISE EXCEPTION 'a BNPL obligation closes only once every billed item is paid in full (P12-TSK-018, INV-LND-08)';
    END IF;
    NEW.created_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

-- bnpl.bnpl_obligation_permits_only_machine_edges: V020's definition, 2 clock call(s) qualified.
CREATE OR REPLACE FUNCTION bnpl.bnpl_obligation_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    purchase bnpl.purchase%ROWTYPE;
    offer bnpl.bnpl_offer%ROWTYPE;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'ACTIVE' OR NEW.closed_at IS NOT NULL OR NEW.closure_reason IS NOT NULL THEN
            RAISE EXCEPTION 'a BNPL obligation is born ACTIVE (P12-TSK-013)';
        END IF;
        -- Born beside its AUTHORISED purchase, for the purchase's party, product, plan and currency and the offer's
        -- decision; its purchase date and zone the purchase's own.
        SELECT * INTO purchase FROM bnpl.purchase p WHERE p.id = NEW.purchase_id;
        IF NOT FOUND OR purchase.status <> 'AUTHORISED' OR purchase.party_id <> NEW.party_id
                OR purchase.product <> NEW.product OR purchase.plan <> NEW.plan OR purchase.currency <> NEW.currency THEN
            RAISE EXCEPTION 'a BNPL obligation is born beside its AUTHORISED purchase, for its party, product, plan and currency (P12-TSK-013)';
        END IF;
        SELECT * INTO offer FROM bnpl.bnpl_offer o WHERE o.id = purchase.offer_id;
        IF offer.decision_id <> NEW.decision_id THEN
            RAISE EXCEPTION 'a BNPL obligation names the decision its offer was made on (P12-TSK-013)';
        END IF;
        NEW.servicing_zone := purchase.servicing_zone;
        NEW.purchase_date := purchase.business_date;
        NEW.opened_at := pg_catalog.clock_timestamp();
        -- The servicing sweep's first look is due at once (P12-TSK-015).
        NEW.next_servicing_at := NEW.opened_at;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a BNPL obligation is never deleted (P12-TSK-013, INV-HIST-01)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.purchase_id IS DISTINCT FROM OLD.purchase_id
            OR NEW.agreement_id IS DISTINCT FROM OLD.agreement_id OR NEW.party_id IS DISTINCT FROM OLD.party_id
            OR NEW.decision_id IS DISTINCT FROM OLD.decision_id OR NEW.product IS DISTINCT FROM OLD.product
            OR NEW.plan IS DISTINCT FROM OLD.plan OR NEW.currency IS DISTINCT FROM OLD.currency
            OR NEW.servicing_zone IS DISTINCT FROM OLD.servicing_zone
            OR NEW.purchase_date IS DISTINCT FROM OLD.purchase_date OR NEW.opened_at IS DISTINCT FROM OLD.opened_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a BNPL obligation''s identity, product and origin are frozen from birth (P12-TSK-015)';
    END IF;
    -- The servicing permit alone (P12-TSK-015, BNPL_LIFECYCLES.md section 4.7): an ACTIVE obligation moves its permit and
    -- nothing else - the sweep's claim and a completed servicing step. Never a CLOSED obligation's, never beside another
    -- column.
    IF NEW.status = OLD.status AND OLD.status = 'ACTIVE'
            AND NEW.closed_at IS NOT DISTINCT FROM OLD.closed_at
            AND NEW.closure_reason IS NOT DISTINCT FROM OLD.closure_reason THEN
        RETURN NEW;
    END IF;
    -- The closure (P12-TSK-018): ACTIVE -> CLOSED only beside its bnpl_obligation_closure row born in this transaction for
    -- the same reason (which holds the rows' side of "every receivable zero"), the permit unmoved; the terminal instant
    -- is the database's, after the lock. A refund's (P12-TSK-021) and a payoff's (P12-TSK-022) reasons are admitted by
    -- their closure rows' own triggers.
    IF OLD.status = 'ACTIVE' AND NEW.status = 'CLOSED' THEN
        IF NEW.closure_reason IS NULL OR NEW.next_servicing_at IS DISTINCT FROM OLD.next_servicing_at
                OR NOT EXISTS (SELECT 1 FROM bnpl.bnpl_obligation_closure c
                                WHERE c.obligation_id = OLD.id AND c.reason = NEW.closure_reason) THEN
            RAISE EXCEPTION 'a BNPL obligation is CLOSED only beside its closure, for the same reason (P12-TSK-018, INV-LND-08)';
        END IF;
        NEW.closed_at := pg_catalog.clock_timestamp();
        RETURN NEW;
    END IF;
    -- The write-off (P14-TSK-021): ACTIVE -> WRITTEN_OFF, only beside its APPROVED decision born in this transaction; never closed -
    -- closed_at and the closure reason stay NULL, the permit unmoved: nothing was paid or forgiven, and nothing services it again.
    IF OLD.status = 'ACTIVE' AND NEW.status = 'WRITTEN_OFF' THEN
        IF NEW.closed_at IS NOT NULL OR NEW.closure_reason IS NOT NULL
                OR NEW.next_servicing_at IS DISTINCT FROM OLD.next_servicing_at
                OR NOT EXISTS (SELECT 1 FROM bnpl.bnpl_write_off_decision d
                                WHERE d.obligation_id = OLD.id AND d.outcome = 'APPROVED') THEN
            RAISE EXCEPTION 'a BNPL obligation is WRITTEN_OFF only beside its approved write-off, and is never closed by it (P14-TSK-021, INV-LIFE-04)';
        END IF;
        RETURN NEW;
    END IF;
    -- Out of CLOSED and WRITTEN_OFF nothing, ever (INV-LIFE-04); and no other edge exists.
    RAISE EXCEPTION 'a BNPL obligation moves only its servicing permit, or ACTIVE -> CLOSED beside its closure; % -> % is not an edge (P12-TSK-018, INV-LIFE-04)', OLD.status, NEW.status;
END;
$$;

-- bnpl.bnpl_offer_event_is_append_only: V004's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION bnpl.bnpl_offer_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL offer event is append-only (P12-TSK-011, INV-HIST-01)';
    END IF;
    NEW.occurred_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

-- bnpl.bnpl_offer_permits_only_machine_edges: V021's definition, 5 clock call(s) qualified.
CREATE OR REPLACE FUNCTION bnpl.bnpl_offer_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    application bnpl.bnpl_application%ROWTYPE;
    validity_minutes integer;
    version_start timestamptz;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'OFFERED' OR NEW.closed_at IS NOT NULL THEN
            RAISE EXCEPTION 'a BNPL offer is born OFFERED and open (P12-TSK-011)';
        END IF;
        -- Born under its application's row lock, from AWAITING_DECISION, for the same party, product, plan and session.
        SELECT * INTO application FROM bnpl.bnpl_application a WHERE a.id = NEW.application_id;
        IF NOT FOUND OR application.status <> 'AWAITING_DECISION'
                OR application.party_id <> NEW.party_id OR application.product <> NEW.product
                OR application.plan <> NEW.plan OR application.checkout_session_id <> NEW.checkout_session_id THEN
            RAISE EXCEPTION 'a BNPL offer is born for its own party''s application AWAITING_DECISION, of the same product, plan and checkout session (P12-TSK-011)';
        END IF;
        -- On the product's terms version ACTIVE now - the one the step pinned FOR SHARE (INV-HIST-04).
        SELECT t.offer_validity_minutes, v.effective_from INTO validity_minutes, version_start
          FROM bnpl.bnpl_terms_version v JOIN bnpl.bnpl_terms t ON t.terms_version_id = v.id
         WHERE v.id = NEW.terms_version_id AND v.product = NEW.product AND v.status = 'ACTIVE';
        IF NOT FOUND THEN
            RAISE EXCEPTION 'a BNPL offer pins its product''s ACTIVE terms version (P12-TSK-011, INV-HIST-04)';
        END IF;
        -- The offering instant is the database's, after the step's locks - never the step's read, never an instance's.
        -- P15-TSK-033 (PI-014): and never before the pinned version began, which a step can put ahead of this clock.
        NEW.offered_at := GREATEST(pg_catalog.clock_timestamp(), version_start);
        -- The expiry on the database clock (A17): the earliest of the decision's validity, the checkout session's
        -- deadline and the pinned offer validity - stamped here, never written by the step.
        NEW.expires_at := LEAST(NEW.decision_valid_until, NEW.session_expires_at,
                                NEW.offered_at + make_interval(mins => validity_minutes));
        IF NEW.expires_at <= NEW.offered_at THEN
            RAISE EXCEPTION 'a BNPL offer is never born expired: its decision or its checkout session has lapsed (P12-TSK-011)';
        END IF;
        -- The expiry sweep's permit: due at the expiry.
        NEW.next_step_at := NEW.expires_at;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a BNPL offer is never deleted (P12-TSK-011, INV-HIST-01)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.application_id IS DISTINCT FROM OLD.application_id
            OR NEW.party_id IS DISTINCT FROM OLD.party_id OR NEW.product IS DISTINCT FROM OLD.product
            OR NEW.plan IS DISTINCT FROM OLD.plan
            OR NEW.decision_id IS DISTINCT FROM OLD.decision_id
            OR NEW.terms_version_id IS DISTINCT FROM OLD.terms_version_id
            OR NEW.checkout_session_id IS DISTINCT FROM OLD.checkout_session_id
            OR NEW.offered_at IS DISTINCT FROM OLD.offered_at
            OR NEW.decision_valid_until IS DISTINCT FROM OLD.decision_valid_until
            OR NEW.session_expires_at IS DISTINCT FROM OLD.session_expires_at
            OR NEW.expires_at IS DISTINCT FROM OLD.expires_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a BNPL offer''s identity, pin and expiry are frozen from birth (P12-TSK-011)';
    END IF;
    -- A permit-only move: only an open offer has a next step, the move moves it, and nothing but the permit moves.
    IF NEW.status = OLD.status THEN
        IF OLD.status <> 'OFFERED' OR NEW.closed_at IS DISTINCT FROM OLD.closed_at
                OR NEW.next_step_at IS NOT DISTINCT FROM OLD.next_step_at THEN
            RAISE EXCEPTION 'only an OFFERED offer has a permit to move, and only its permit; % is not (P12-TSK-011)', OLD.status;
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status <> 'OFFERED' THEN
        RAISE EXCEPTION 'a % BNPL offer is terminal (P12-TSK-011)', OLD.status;
    END IF;
    -- The clock-sided edges, bracketing the domain's conditional (its clock_timestamp() read after the row lock,
    -- between this statement's start and this trigger's clock): exactly one of them at the boundary instant.
    IF NEW.status = 'DECLINED' THEN
        IF OLD.expires_at <= pg_catalog.statement_timestamp() THEN
            RAISE EXCEPTION 'an expired BNPL offer cannot be declined: it expires (P12-TSK-011)';
        END IF;
    ELSIF NEW.status = 'EXPIRED' THEN
        IF OLD.expires_at > pg_catalog.clock_timestamp() THEN
            RAISE EXCEPTION 'a BNPL offer expires only once expires_at <= the database clock (P12-TSK-011)';
        END IF;
    ELSIF NEW.status = 'ACCEPTED' THEN
        -- P12-TSK-013: the acceptance's side of the clock, the decline's bracket - and only beside its FINANCED purchase,
        -- born in the same transaction with its financing, obligation and agreement (INV-BNPL-02).
        IF OLD.expires_at <= pg_catalog.statement_timestamp() THEN
            RAISE EXCEPTION 'an expired BNPL offer cannot be accepted: it expires (P12-TSK-013)';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM bnpl.purchase p WHERE p.offer_id = NEW.id AND p.status = 'FINANCED') THEN
            RAISE EXCEPTION 'a BNPL offer is ACCEPTED only beside its FINANCED purchase (P12-TSK-013, INV-BNPL-02)';
        END IF;
    ELSE
        RAISE EXCEPTION 'a BNPL offer moves OFFERED -> ACCEPTED | DECLINED | EXPIRED; % -> % is not an edge (P12-TSK-013)', OLD.status, NEW.status;
    END IF;
    -- The database stamps the closure after the lock, never before the offering on a backward clock.
    NEW.closed_at := GREATEST(pg_catalog.clock_timestamp(), OLD.offered_at);
    RETURN NEW;
END;
$$;

-- bnpl.bnpl_payoff_execution_is_born_once: V014's definition, 4 clock call(s) qualified.
CREATE OR REPLACE FUNCTION bnpl.bnpl_payoff_execution_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    obligation bnpl.bnpl_obligation%ROWTYPE;
    quote bnpl.bnpl_payoff_quote%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL payoff execution is immutable from insert, for every role (P12-TSK-022, INV-LIFE-04)';
    END IF;
    -- (B4) The obligation, for every writer: its own quote, on an ACTIVE obligation, under the agreement version the
    -- quote names - still the one in force.
    SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id FOR UPDATE;
    SELECT * INTO quote FROM bnpl.bnpl_payoff_quote q WHERE q.id = NEW.quote_id AND q.obligation_id = NEW.obligation_id;
    IF obligation.id IS NULL OR obligation.status <> 'ACTIVE' OR quote.id IS NULL
            OR quote.agreement_version <> (SELECT max(x.version) FROM bnpl.bnpl_agreement x
                                            WHERE x.obligation_id = NEW.obligation_id) THEN
        RAISE EXCEPTION 'a BNPL payoff executes its own obligation''s quote, on an ACTIVE obligation, under the agreement version it names (P12-TSK-022, INV-LND-08)';
    END IF;
    -- On the quote's good-through date, which is this transaction's business date in the pinned zone.
    IF NEW.business_date <> quote.good_through
            OR NEW.business_date < bnpl.business_date(LEAST(pg_catalog.statement_timestamp(), pg_catalog.transaction_timestamp(),
                                                            pg_catalog.clock_timestamp()), obligation.servicing_zone)
            OR NEW.business_date > bnpl.business_date_latest(obligation.servicing_zone) THEN
        RAISE EXCEPTION 'a BNPL payoff quote is executed only on its good-through date, on the database clock in its pinned zone (P12-TSK-022)';
    END IF;
    -- The value date every fact of the payoff takes: max(today, floor), the floor read now - before the payoff writes
    -- anything - so no billing, application or repayment of the payoff is dated before it (RG-M1).
    IF NEW.value_date <> GREATEST(NEW.business_date, bnpl.principal_value_floor(NEW.obligation_id)) THEN
        RAISE EXCEPTION 'a BNPL payoff is value-dated max(its date, the obligation''s floor), the floor read before the payoff writes (P12-TSK-022, the Phase 11 gate''s RG-M1)';
    END IF;
    NEW.executed_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

-- bnpl.bnpl_payoff_quote_is_born_once: V014's definition, 4 clock call(s) qualified.
CREATE OR REPLACE FUNCTION bnpl.bnpl_payoff_quote_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    obligation bnpl.bnpl_obligation%ROWTYPE;
    agreement bnpl.bnpl_agreement%ROWTYPE;
    horizon integer;
    schedule_scale smallint;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL payoff quote is immutable from insert, for every role (P12-TSK-022, INV-LND-08)';
    END IF;
    -- (B4) The obligation, for every writer (the quote holds it already; re-taking it is free): an ACTIVE obligation.
    SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id FOR UPDATE;
    IF NOT FOUND OR obligation.status <> 'ACTIVE' OR obligation.currency <> NEW.currency THEN
        RAISE EXCEPTION 'a BNPL payoff is quoted only for an ACTIVE obligation, in its currency (P12-TSK-022, INV-LND-08)';
    END IF;
    -- Under the agreement version in force - the obligation's highest - by its pinned engines (INV-LND-09), in the latest
    -- schedule version's scale.
    SELECT * INTO agreement FROM bnpl.bnpl_agreement a
     WHERE a.agreement_id = NEW.agreement_id AND a.version = NEW.agreement_version
       AND a.obligation_id = NEW.obligation_id;
    SELECT s.scale INTO schedule_scale FROM bnpl.bnpl_schedule s
     WHERE s.obligation_id = NEW.obligation_id
     ORDER BY s.schedule_version DESC LIMIT 1;
    IF agreement.agreement_id IS NULL
            OR NEW.agreement_version <> (SELECT max(x.version) FROM bnpl.bnpl_agreement x
                                          WHERE x.obligation_id = NEW.obligation_id)
            OR NEW.schedule_engine IS DISTINCT FROM agreement.schedule_engine
            OR NEW.accrual_engine IS DISTINCT FROM agreement.accrual_engine
            OR NEW.allocation_engine IS DISTINCT FROM agreement.allocation_engine
            OR schedule_scale IS NULL OR NEW.scale <> schedule_scale THEN
        RAISE EXCEPTION 'a BNPL payoff quote names its obligation''s agreement version in force, its pinned engines and its scale (P12-TSK-022, INV-LND-09)';
    END IF;
    SELECT t.payoff_horizon_days INTO horizon FROM bnpl.bnpl_terms t WHERE t.terms_version_id = agreement.terms_version_id;
    IF horizon IS NULL THEN
        RAISE EXCEPTION 'a BNPL payoff is quoted under the terms its agreement pins (P12-TSK-022)';
    END IF;
    -- Made on the business date of the database clock in the pinned zone (read by the quote after its lock), good through
    -- that date up to the pinned horizon.
    IF NEW.business_date < bnpl.business_date(LEAST(pg_catalog.statement_timestamp(), pg_catalog.transaction_timestamp(), pg_catalog.clock_timestamp()),
                                              obligation.servicing_zone)
            OR NEW.business_date > bnpl.business_date_latest(obligation.servicing_zone)
            OR NEW.business_date < obligation.purchase_date
            OR NEW.good_through > NEW.business_date + horizon THEN
        RAISE EXCEPTION 'a BNPL payoff quote is made on the business date of the database clock in its pinned zone, good through at most the pinned horizon after it (P12-TSK-022)';
    END IF;
    -- The instant the database's, after the lock.
    NEW.created_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

-- bnpl.bnpl_refund_is_born_once: V012's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION bnpl.bnpl_refund_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    purchase bnpl.purchase%ROWTYPE;
    obligation bnpl.bnpl_obligation%ROWTYPE;
    financing bnpl.merchant_financing%ROWTYPE;
    refunded bigint;
    debited bigint;
    returned bigint;
    value_floor date;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL refund is immutable from insert, for every role (P12-TSK-021, INV-HIST-01)';
    END IF;
    -- (B5) then (B4), for every writer: the bound and the cancellations are judged under the locks every refund and
    -- every dispute holds (T-ref holds both already; re-taking them is free).
    SELECT * INTO purchase FROM bnpl.purchase p WHERE p.id = NEW.purchase_id FOR UPDATE;
    SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.purchase_id = NEW.purchase_id FOR UPDATE;
    SELECT * INTO financing FROM bnpl.merchant_financing f WHERE f.purchase_id = NEW.purchase_id;
    IF purchase.id IS NULL OR purchase.status <> 'FINANCED' OR purchase.merchant_id <> NEW.merchant_id
            OR purchase.currency <> NEW.currency OR financing.scale <> NEW.scale
            OR obligation.id IS DISTINCT FROM NEW.obligation_id THEN
        RAISE EXCEPTION 'a BNPL refund refunds a FINANCED purchase of its merchant, in its currency and scale, against its own obligation (P12-TSK-021, INV-BNPL-07)';
    END IF;
    -- INV-BNPL-07's second rank: what was refunded before, exactly, and never more than the gross in all.
    SELECT coalesce(sum(r.amount_minor), 0), coalesce(sum(r.payable_debit_minor), 0), coalesce(sum(r.fee_returned_minor), 0)
      INTO refunded, debited, returned
      FROM bnpl.bnpl_refund r WHERE r.purchase_id = NEW.purchase_id;
    IF NEW.refunded_before_minor <> refunded OR refunded + NEW.amount_minor > financing.gross_minor THEN
        RAISE EXCEPTION 'a BNPL purchase''s refunds never total more than its gross (P12-TSK-021, INV-BNPL-07)';
    END IF;
    -- B7's bound (INV-MER-07 as amended): the purchase's refunds debit the payable by at most what its financing credited
    -- it, and return at most its fee.
    IF debited + NEW.payable_debit_minor > financing.net_minor OR returned + NEW.fee_returned_minor > financing.fee_minor THEN
        RAISE EXCEPTION 'a BNPL purchase''s refunds take back at most its financed net and return at most its fee (P12-TSK-021, B7, INV-MER-07)';
    END IF;
    -- Under the agreement version in force (the obligation's highest - a v n+1 born before this row in the transaction).
    IF NEW.agreement_id <> obligation.agreement_id
            OR NEW.agreement_version <> (SELECT max(x.version) FROM bnpl.bnpl_agreement x
                                          WHERE x.obligation_id = obligation.id)
            OR NEW.schedule_version <> (SELECT max(x.schedule_version) FROM bnpl.bnpl_schedule x
                                         WHERE x.obligation_id = obligation.id) THEN
        RAISE EXCEPTION 'a BNPL refund names its obligation''s agreement and schedule versions in force (P12-TSK-021, INV-HIST-04)';
    END IF;
    IF obligation.status = 'ACTIVE' THEN
        -- The Phase 11 gate's R1: a principal fact is never value-dated before the floor, nor after the later of today
        -- and it.
        value_floor := bnpl.principal_value_floor(obligation.id);
        IF NEW.value_date < value_floor
                OR NEW.value_date > GREATEST(bnpl.business_date_latest(obligation.servicing_zone), value_floor) THEN
            RAISE EXCEPTION 'a BNPL refund is value-dated on or after its obligation''s floor and never after today (P12-TSK-021, the Phase 11 gate''s R1)';
        END IF;
    ELSIF NEW.cancelled_unbilled_minor <> 0 OR NEW.cancelled_billed_minor <> 0
            OR NEW.interest_accrued_released_minor <> 0 OR NEW.interest_due_released_minor <> 0
            OR NEW.excess_to <> 'WALLET'
            OR NEW.value_date > bnpl.business_date_latest(obligation.servicing_zone) THEN
        -- A refund after closure (OQ-15): nothing left to cancel or release, the whole of R to the wallet - a CLOSED
        -- obligation holds no credit balance (INV-BNPL-09).
        RAISE EXCEPTION 'a refund of a CLOSED obligation cancels nothing and pays the customer''s wallet only (P12-TSK-021, OQ-15, INV-BNPL-09)';
    END IF;
    NEW.created_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

-- bnpl.bnpl_repayment_is_born_once: V014's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION bnpl.bnpl_repayment_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    obligation bnpl.bnpl_obligation%ROWTYPE;
    value_floor date;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL repayment is immutable from insert, for every role (P12-TSK-013)';
    END IF;
    -- (B4) The obligation, for every writer: the repayment, its floor and its allocation are judged under the lock every
    -- servicing act on it holds (T-pur, T-rep, T-svc and T-pay hold it already; re-taking it is free).
    SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id FOR UPDATE;
    IF NOT FOUND OR obligation.status <> 'ACTIVE' OR obligation.currency <> NEW.currency
            OR obligation.agreement_id <> NEW.agreement_id
            OR NEW.agreement_version <> (SELECT max(x.version) FROM bnpl.bnpl_agreement x
                                          WHERE x.obligation_id = NEW.obligation_id)
            OR NOT EXISTS (SELECT 1 FROM bnpl.bnpl_agreement a WHERE a.agreement_id = NEW.agreement_id
                              AND a.version = NEW.agreement_version AND a.allocation_engine = NEW.allocation_engine) THEN
        RAISE EXCEPTION 'a BNPL repayment repays its ACTIVE obligation, in its currency, under its agreement version in force and its allocation engine (P12-TSK-018, INV-LND-08)';
    END IF;
    -- AT_PURCHASE: pay-in-4's instalment 1, inside the purchase, keyed by the purchase, dated the purchase's date.
    IF NEW.kind = 'AT_PURCHASE' AND (obligation.product <> 'BNPL_PAY_IN_4'
            OR NEW.request_key <> obligation.purchase_id::text OR NEW.value_date <> obligation.purchase_date) THEN
        RAISE EXCEPTION 'an AT_PURCHASE repayment is a pay-in-4 purchase''s own, keyed by it and dated its business date (P12-TSK-013, A5)';
    END IF;
    -- AUTO_COLLECTION (P12-TSK-019) is admitted here and held to its attempt - its key, amount, lines and version - by the
    -- attempt's trigger, and to having one at all by bnpl_repayment_auto_collection_has_its_attempt at commit.
    -- PAYOFF (P12-TSK-022): only beside its obligation's payoff execution, keyed by the executed quote and dated the
    -- execution's value date - one per obligation, since the execution is (and the deferred check closes it with it).
    IF NEW.kind = 'PAYOFF' AND NOT EXISTS (SELECT 1 FROM bnpl.bnpl_payoff_execution e
                                            WHERE e.obligation_id = NEW.obligation_id
                                              AND e.quote_id::text = NEW.request_key
                                              AND e.value_date = NEW.value_date) THEN
        RAISE EXCEPTION 'a PAYOFF repayment is born only beside its obligation''s payoff execution, keyed by the quote and dated its value date (P12-TSK-022, INV-LND-08)';
    END IF;
    -- The Phase 11 gate's R1 from the first table: never a date the record has closed, never after the later of the
    -- latest date the clock showed and the floor.
    value_floor := bnpl.principal_value_floor(NEW.obligation_id);
    IF NEW.value_date < value_floor
            OR NEW.value_date > GREATEST(bnpl.business_date_latest(obligation.servicing_zone), value_floor) THEN
        RAISE EXCEPTION 'a BNPL repayment is value-dated on or after its obligation''s floor and never after today (P12-TSK-013, the Phase 11 gate''s R1)';
    END IF;
    NEW.received_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

-- bnpl.bnpl_repayment_reversal_permits_only_machine_edges: V019's definition, 2 clock call(s) qualified.
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
        NEW.proposed_at := pg_catalog.clock_timestamp();
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
        SELECT r.* INTO repayment FROM bnpl.bnpl_repayment r WHERE r.id = NEW.repayment_id;
        -- The principal re-instated never joins a date already accrued (the Phase 11 gate's R1): valued on or after the
        -- floor read under B4, never after the later of the latest date the clock showed, the floor and the repayment's
        -- own date (A2-1 - the one date a clock stepped back may leave ahead of both).
        value_floor := bnpl.principal_value_floor(NEW.obligation_id);
        IF NEW.value_date < value_floor
                OR NEW.value_date > GREATEST(bnpl.business_date_latest(obligation.servicing_zone), value_floor,
                                             repayment.value_date) THEN
            RAISE EXCEPTION 'a BNPL repayment reversal is valued on or after its obligation''s floor and never after today (P12-TSK-024, the Phase 11 gate''s R1)';
        END IF;
        -- A2-1: never before the repayment it reverses - a date between the two would carry the re-opened amount twice.
        IF NEW.value_date < repayment.value_date THEN
            RAISE EXCEPTION 'a BNPL repayment reversal is never valued before the repayment it reverses (the Phase 12 transition gate''s A2-1)';
        END IF;
    END IF;
    NEW.decided_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

-- bnpl.bnpl_schedule_is_born_once: V012's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION bnpl.bnpl_schedule_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    obligation bnpl.bnpl_obligation%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL schedule is immutable from insert, for every role (P12-TSK-013, INV-BNPL-05)';
    END IF;
    SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id;
    -- Every version starts on the purchase's own business date (the accrual span's start, V009), in the obligation's
    -- currency, on its agreement's engine.
    IF NOT FOUND OR NEW.start_date <> obligation.purchase_date OR NEW.currency <> obligation.currency
            OR NOT EXISTS (SELECT 1 FROM bnpl.bnpl_agreement a WHERE a.obligation_id = NEW.obligation_id
                              AND a.version = 1 AND a.schedule_engine = NEW.engine) THEN
        RAISE EXCEPTION 'a BNPL schedule version starts on its obligation''s purchase date, in its currency, on its agreement''s engine (P12-TSK-013)';
    END IF;
    -- A version n+1 (P12-TSK-021): the next, of an ACTIVE obligation, beside its refund (held at commit by the deferred
    -- foreign key and bnpl.bnpl_refund_conserves).
    IF NEW.schedule_version > 1 AND (obligation.status <> 'ACTIVE' OR NEW.refund_id IS NULL
            OR NEW.schedule_version <> (SELECT max(x.schedule_version) + 1 FROM bnpl.bnpl_schedule x
                                         WHERE x.obligation_id = NEW.obligation_id)) THEN
        RAISE EXCEPTION 'a BNPL schedule version n+1 is the next version of an ACTIVE obligation, beside its refund (P12-TSK-021, INV-HIST-04)';
    END IF;
    NEW.created_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

-- bnpl.bnpl_terms_version_event_is_append_only: V002's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION bnpl.bnpl_terms_version_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL terms version event is append-only (P12-TSK-004, INV-HIST-01)';
    END IF;
    NEW.occurred_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

-- bnpl.bnpl_terms_version_permits_only_machine_edges: V021's definition, 5 clock call(s) qualified.
CREATE OR REPLACE FUNCTION bnpl.bnpl_terms_version_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PROPOSED' OR NEW.decided_by IS NOT NULL OR NEW.decided_by_type IS NOT NULL
                OR NEW.decided_at IS NOT NULL OR NEW.decision_reason IS NOT NULL
                OR NEW.effective_from IS NOT NULL OR NEW.effective_to IS NOT NULL THEN
            RAISE EXCEPTION 'a BNPL terms version is born PROPOSED and undecided: no version is activated by insert (P12-TSK-004)';
        END IF;
        NEW.proposed_at := pg_catalog.clock_timestamp();
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a BNPL terms version is never deleted (P12-TSK-004, INV-HIST-04)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.product IS DISTINCT FROM OLD.product
            OR NEW.version IS DISTINCT FROM OLD.version
            OR NEW.proposed_by IS DISTINCT FROM OLD.proposed_by
            OR NEW.proposed_by_type IS DISTINCT FROM OLD.proposed_by_type
            OR NEW.proposed_at IS DISTINCT FROM OLD.proposed_at
            OR NEW.proposal_reason IS DISTINCT FROM OLD.proposal_reason
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a BNPL terms version''s identity is frozen (P12-TSK-004, INV-HIST-04)';
    END IF;
    IF NOT ((OLD.status = 'PROPOSED' AND NEW.status IN ('ACTIVE', 'REJECTED'))
            OR (OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED')) THEN
        RAISE EXCEPTION 'a BNPL terms version moves PROPOSED -> ACTIVE | REJECTED, ACTIVE -> RETIRED; % -> % is not an edge (P12-TSK-004)', OLD.status, NEW.status;
    END IF;
    IF OLD.status = 'PROPOSED' THEN
        NEW.decided_at := pg_catalog.clock_timestamp();
        -- P15-TSK-033 (PI-014): exactly where its predecessor ends - the product's highest-numbered RETIRED version, retired
        -- first in this transaction - chosen by row order, never by a stamp; the product's first version at the start.
        NEW.effective_from := CASE WHEN NEW.status = 'ACTIVE' THEN COALESCE(
                (SELECT v.effective_to FROM bnpl.bnpl_terms_version v
                  WHERE v.product = NEW.product AND v.status = 'RETIRED' AND v.version < NEW.version
                  ORDER BY v.version DESC LIMIT 1),
                pg_catalog.transaction_timestamp()) END;
        NEW.effective_to := NULL;
    ELSE
        IF NEW.decided_by IS DISTINCT FROM OLD.decided_by OR NEW.decided_by_type IS DISTINCT FROM OLD.decided_by_type
                OR NEW.decided_at IS DISTINCT FROM OLD.decided_at
                OR NEW.decision_reason IS DISTINCT FROM OLD.decision_reason
                OR NEW.effective_from IS DISTINCT FROM OLD.effective_from THEN
            RAISE EXCEPTION 'an activation''s decision is frozen; a retirement records only its end (P12-TSK-004)';
        END IF;
        -- Written after the activation's lock on this row (FOR UPDATE, behind every pin's FOR SHARE); never before the
        -- transaction's start; never at or before its own start, whatever the clock says. P15-TSK-033 (PI-014): and never
        -- before one microsecond after the latest offer pinned to it - the clock can step back between that offer's stamp
        -- and this one.
        NEW.effective_to := GREATEST(pg_catalog.clock_timestamp(), pg_catalog.transaction_timestamp(), OLD.effective_from + interval '1 microsecond',
                (SELECT max(o.offered_at) FROM bnpl.bnpl_offer o WHERE o.terms_version_id = OLD.id) + interval '1 microsecond');
    END IF;
    RETURN NEW;
END;
$$;

-- bnpl.bnpl_waiver_decision_is_born_once: V016's definition, 1 clock call(s) qualified.
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
    NEW.decided_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

-- bnpl.bnpl_waiver_is_born_once: V016's definition, 1 clock call(s) qualified.
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
    NEW.proposed_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

-- bnpl.bnpl_write_off_decision_is_born_once: V020's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION bnpl.bnpl_write_off_decision_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    proposal bnpl.bnpl_write_off%ROWTYPE;
    account bnpl.bnpl_obligation%ROWTYPE;
    default_change bnpl.bnpl_condition_event%ROWTYPE;
    value_floor date;
    due_principal numeric;
    due_interest numeric;
    due_fees numeric;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a write-off decision is immutable from insert, for every role (P14-TSK-021, INV-HIST-01)';
    END IF;
    SELECT w.* INTO proposal FROM bnpl.bnpl_write_off w WHERE w.id = NEW.write_off_id;
    IF proposal.id IS NULL THEN
        RAISE EXCEPTION 'a write-off decision decides a write-off (P14-TSK-021)';
    END IF;
    -- (B4) The obligation, for every writer: everything below is judged under the lock every act on it holds.
    SELECT o.* INTO account FROM bnpl.bnpl_obligation o WHERE o.id = proposal.obligation_id FOR UPDATE;
    IF NEW.outcome = 'APPROVED' THEN
        IF account.status <> 'ACTIVE' THEN
            RAISE EXCEPTION 'a write-off is approved only on an ACTIVE obligation (P14-TSK-021, INV-LIFE-04)';
        END IF;
        -- A condition that can leave and come back is compared by its revision: the same DEFAULT change, still set.
        SELECT e.* INTO default_change FROM bnpl.bnpl_condition_event e
         WHERE e.obligation_id = proposal.obligation_id AND e.kind = 'DEFAULT'
         ORDER BY e.business_date DESC LIMIT 1;
        IF default_change.id IS DISTINCT FROM proposal.default_event_id OR default_change.defaulted IS NOT TRUE THEN
            RAISE EXCEPTION 'a write-off is approved only while the DEFAULT change it stands on is the latest and still set (P14-TSK-021)';
        END IF;
        IF EXISTS (SELECT 1 FROM bnpl.bnpl_dispute d
                    WHERE d.obligation_id = proposal.obligation_id AND d.status IN ('OPEN', 'MERCHANT_RESPONDED')) THEN
            RAISE EXCEPTION 'a write-off is approved only with no dispute open (P14-TSK-021, ADR-0106)';
        END IF;
        -- The agreement in force is the one it was proposed under (lending V032's rank; a BNPL version moves only with a refund's lines).
        IF proposal.agreement_version <> (SELECT max(a.version) FROM bnpl.bnpl_agreement a WHERE a.agreement_id = account.agreement_id) THEN
            RAISE EXCEPTION 'a write-off is approved only under the agreement version it was proposed on (P14-TSK-021, INV-BNPL-01)';
        END IF;
        -- What the billed items say is due is what the proposal named (the rows' side; the ledger's is the domain's under B4).
        SELECT coalesce(sum(bnpl.billing_component_billed(b.id, 'PRINCIPAL') - bnpl.billing_component_paid(b.id, 'PRINCIPAL')), 0),
               coalesce(sum(bnpl.billing_component_billed(b.id, 'INTEREST') - bnpl.billing_component_paid(b.id, 'INTEREST')), 0),
               coalesce(sum(bnpl.billing_component_billed(b.id, 'FEES') - bnpl.billing_component_paid(b.id, 'FEES')), 0)
          INTO due_principal, due_interest, due_fees
          FROM bnpl.bnpl_billing b WHERE b.obligation_id = proposal.obligation_id;
        IF due_principal <> proposal.principal_due_minor OR due_interest <> proposal.interest_due_minor
                OR due_fees <> proposal.fees_due_minor THEN
            RAISE EXCEPTION 'a write-off takes exactly what the billed items have due (P14-TSK-021, INV-BNPL-01)';
        END IF;
        -- The value date: never one the record has closed (R1's rule), never after the clock.
        value_floor := bnpl.principal_value_floor(proposal.obligation_id);
        IF NEW.value_date < value_floor OR NEW.value_date > bnpl.business_date_latest(account.servicing_zone) THEN
            RAISE EXCEPTION 'a write-off is valued on a date the record leaves open, never after today (P14-TSK-021, ADR-0063 decision 2)';
        END IF;
    END IF;
    NEW.recorded_order := nextval('bnpl.record_order');
    NEW.decided_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

-- bnpl.bnpl_write_off_is_born_once: V020's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION bnpl.bnpl_write_off_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    account bnpl.bnpl_obligation%ROWTYPE;
    agreement bnpl.bnpl_agreement%ROWTYPE;
    default_change bnpl.bnpl_condition_event%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a write-off proposal is a born-once fact (P14-TSK-021): its decision is bnpl_write_off_decision';
    END IF;
    -- (B4) The obligation, for every writer.
    SELECT o.* INTO account FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id FOR UPDATE;
    IF account.id IS NULL OR account.status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'a write-off is proposed only on an ACTIVE obligation (P14-TSK-021)';
    END IF;
    SELECT a.* INTO agreement FROM bnpl.bnpl_agreement a
     WHERE a.agreement_id = account.agreement_id
     ORDER BY a.version DESC LIMIT 1;
    IF NEW.agreement_id <> account.agreement_id OR NEW.agreement_version <> agreement.version
            OR NEW.currency <> account.currency THEN
        RAISE EXCEPTION 'a write-off names its obligation''s agreement version in force, in its currency (P14-TSK-021)';
    END IF;
    -- The default it stands on is the obligation's latest DEFAULT change, and that change set the flag.
    SELECT e.* INTO default_change FROM bnpl.bnpl_condition_event e
     WHERE e.obligation_id = NEW.obligation_id AND e.kind = 'DEFAULT'
     ORDER BY e.business_date DESC LIMIT 1;
    IF default_change.id IS DISTINCT FROM NEW.default_event_id OR default_change.defaulted IS NOT TRUE THEN
        RAISE EXCEPTION 'a write-off stands on its obligation''s latest DEFAULT change, which set the flag (P14-TSK-021)';
    END IF;
    -- A dispute's outcome may be a refund, which needs the receivable: it is resolved first.
    IF EXISTS (SELECT 1 FROM bnpl.bnpl_dispute d
                WHERE d.obligation_id = NEW.obligation_id AND d.status IN ('OPEN', 'MERCHANT_RESPONDED')) THEN
        RAISE EXCEPTION 'a write-off is proposed only with no dispute open (P14-TSK-021, ADR-0106)';
    END IF;
    -- One proposal awaiting a decision per obligation: a second is bnpl.ProposalPending.
    IF EXISTS (SELECT 1 FROM bnpl.bnpl_write_off w
                WHERE w.obligation_id = NEW.obligation_id
                  AND NOT EXISTS (SELECT 1 FROM bnpl.bnpl_write_off_decision d WHERE d.write_off_id = w.id)) THEN
        RAISE EXCEPTION 'a write-off of this obligation already awaits a decision (P14-TSK-021)';
    END IF;
    NEW.proposed_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

-- bnpl.business_date_latest: V006's definition, 3 clock call(s) qualified.
CREATE OR REPLACE FUNCTION bnpl.business_date_latest(servicing_zone text)
    RETURNS date
    LANGUAGE sql
    STABLE
AS $$
    SELECT bnpl.business_date(GREATEST(pg_catalog.statement_timestamp(), pg_catalog.transaction_timestamp(), pg_catalog.clock_timestamp()), servicing_zone)
$$;

-- bnpl.merchant_financing_is_born_once: V006's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION bnpl.merchant_financing_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    purchase bnpl.purchase%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a merchant financing is immutable from insert, for every role (P12-TSK-013)';
    END IF;
    SELECT * INTO purchase FROM bnpl.purchase p WHERE p.id = NEW.purchase_id;
    IF NOT FOUND OR purchase.status <> 'AUTHORISED' OR purchase.merchant_id <> NEW.merchant_id
            OR purchase.currency <> NEW.currency THEN
        RAISE EXCEPTION 'a merchant financing is born beside its AUTHORISED purchase, for its merchant and currency (P12-TSK-013)';
    END IF;
    NEW.financed_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

-- bnpl.purchase_permits_only_machine_edges: V012's definition, 2 clock call(s) qualified.
CREATE OR REPLACE FUNCTION bnpl.purchase_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    offer bnpl.bnpl_offer%ROWTYPE;
    application bnpl.bnpl_application%ROWTYPE;
    zone text;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'AUTHORISED' OR NEW.financed_at IS NOT NULL OR NEW.order_id IS NOT NULL THEN
            RAISE EXCEPTION 'a BNPL purchase is born AUTHORISED (P12-TSK-013)';
        END IF;
        -- Born under its offer's row lock (B2), while it is OFFERED, for the offer's own party, product, plan and session,
        -- at the application's merchant. The expiry is judged at the offer's ACCEPTED edge, the last write of the act.
        SELECT * INTO offer FROM bnpl.bnpl_offer o WHERE o.id = NEW.offer_id;
        IF NOT FOUND OR offer.status <> 'OFFERED' OR offer.party_id <> NEW.party_id OR offer.product <> NEW.product
                OR offer.plan <> NEW.plan OR offer.checkout_session_id <> NEW.checkout_session_id THEN
            RAISE EXCEPTION 'a BNPL purchase is born for its own party''s OFFERED offer, of the same product, plan and checkout session (P12-TSK-013)';
        END IF;
        SELECT * INTO application FROM bnpl.bnpl_application a WHERE a.id = offer.application_id;
        IF application.merchant_id <> NEW.merchant_id THEN
            RAISE EXCEPTION 'a BNPL purchase pays the merchant its checkout session pays (P12-TSK-013)';
        END IF;
        SELECT t.servicing_zone INTO zone FROM bnpl.bnpl_terms t WHERE t.terms_version_id = offer.terms_version_id;
        -- The instant and the business date are the database's, after the act's locks - never an instance's clock.
        NEW.servicing_zone := zone;
        NEW.authorised_at := pg_catalog.clock_timestamp();
        NEW.business_date := bnpl.business_date(NEW.authorised_at, zone);
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a BNPL purchase is never deleted (P12-TSK-013, INV-HIST-01)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.offer_id IS DISTINCT FROM OLD.offer_id
            OR NEW.checkout_session_id IS DISTINCT FROM OLD.checkout_session_id
            OR NEW.party_id IS DISTINCT FROM OLD.party_id OR NEW.merchant_id IS DISTINCT FROM OLD.merchant_id
            OR NEW.product IS DISTINCT FROM OLD.product OR NEW.plan IS DISTINCT FROM OLD.plan
            OR NEW.currency IS DISTINCT FROM OLD.currency OR NEW.servicing_zone IS DISTINCT FROM OLD.servicing_zone
            OR NEW.business_date IS DISTINCT FROM OLD.business_date
            OR NEW.authorised_at IS DISTINCT FROM OLD.authorised_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a BNPL purchase''s identity is frozen from birth (P12-TSK-013)';
    END IF;
    -- FINANCED -> FULLY_REFUNDED (P12-TSK-021): once, when the purchase's refunds total its gross - the refund that does
    -- it born before this edge in its transaction (held again at commit by bnpl.bnpl_refund_conserves); the financed
    -- instant and the order frozen. Terminal: nothing out of FULLY_REFUNDED, ever.
    IF OLD.status = 'FINANCED' AND NEW.status = 'FULLY_REFUNDED' THEN
        IF NEW.order_id IS DISTINCT FROM OLD.order_id OR NEW.financed_at IS DISTINCT FROM OLD.financed_at
                OR (SELECT coalesce(sum(r.amount_minor), 0) FROM bnpl.bnpl_refund r WHERE r.purchase_id = OLD.id)
                   <> (SELECT f.gross_minor FROM bnpl.merchant_financing f WHERE f.purchase_id = OLD.id) THEN
            RAISE EXCEPTION 'a BNPL purchase is FULLY_REFUNDED only when its refunds total its gross (P12-TSK-021, INV-BNPL-07)';
        END IF;
        RETURN NEW;
    END IF;
    -- AUTHORISED -> FINANCED, once, beside its merchant financing and with checkout's order. Never FINANCED -> AUTHORISED.
    IF NOT (OLD.status = 'AUTHORISED' AND NEW.status = 'FINANCED') THEN
        RAISE EXCEPTION 'a BNPL purchase moves AUTHORISED -> FINANCED -> FULLY_REFUNDED; % -> % is not an edge (P12-TSK-021)', OLD.status, NEW.status;
    END IF;
    IF NEW.order_id IS NULL THEN
        RAISE EXCEPTION 'a BNPL purchase is FINANCED with checkout''s order (P12-TSK-013)';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM bnpl.merchant_financing f WHERE f.purchase_id = NEW.id) THEN
        RAISE EXCEPTION 'a BNPL purchase is FINANCED only beside its merchant financing (P12-TSK-013, INV-BNPL-02)';
    END IF;
    NEW.financed_at := GREATEST(pg_catalog.clock_timestamp(), OLD.authorised_at);
    RETURN NEW;
END;
$$;
