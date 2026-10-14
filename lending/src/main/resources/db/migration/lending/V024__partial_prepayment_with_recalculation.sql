-- Partial prepayment with recalculation (P11-TSK-028; PHASE_11_PLAN.md sections 7.1, 7.2, 7.4, 12.2 step 7, 12.5 and 12.8
-- rule 5; ADR-0099 point 6, ADR-0095, ADR-0093; INV-LND-03, INV-LND-05, INV-LND-04, INV-IDEM-01). The number is V024 - the
-- next free lending version (the backlog's "V021" is stale: P11-TSK-025's).
--
-- The borrower pays principal not yet due early, from the wallet, in ONE transaction under the loan's row lock (L3): the
-- servicing step's own catch-up first (every elapsed date accrued, everything due billed, each billing followed by the
-- credit-balance application); refused while anything billed is owing - past due or due today - (409 lending.ArrearsFirst);
-- then posting rule 5 with its one line to LOAN_PRINCIPAL (DR CUSTOMER_WALLET / CR LOAN_PRINCIPAL, the repayment's own key
-- lending.repayment:<id>), and - in the same transaction - agreement version n+1 and schedule version n+1:
--
--     repayment (kind PREPAYMENT)   - the money, its one allocation row NOT_DUE_PRINCIPAL; strictly less than the principal
--                                     not yet due (the whole of it is the payoff's, which also settles the open period's
--                                     interest), only on an ACTIVE, scheduled instalment loan with nothing billed owing.
--     loan_agreement version n+1    - a PREPAYMENT's: prepayment_id names the repayment (a third origin beside offer and
--                                     amendment). The same principal, decision, terms version, rate, day, engines and
--                                     template as version n; term_months the instalments billed plus the new schedule's;
--                                     canonical terms FORMAT 4 (the version's own self-contained bytes) and their hash. No
--                                     acceptance evidence: a prepayment is a right the agreement already grants, exercised by
--                                     the customer's own keyed MULTI_FACTOR command - the repayment row is the act's record.
--     repayment_schedule n+1        - from the principal outstanding not yet billed, now net of what was prepaid (exactly
--                                     LOAN_PRINCIPAL after rule 5), over the open period (its start the latest billed due
--                                     date), with the SAME level instalment and the least term that amortises it - never more
--                                     instalments than the current schedule has left; only when late payments have left more
--                                     principal outstanding than that instalment can amortise in the remaining term is the
--                                     term kept and the instalment the engine's (ScheduleEngineV1.recalculateAtInstalment).
--
-- Why a new agreement version and not only a new schedule: a schedule is named for the agreement version it projects
-- (UNIQUE (agreement_id), V023); the agreement states the contract's term, which the prepayment shortens; a pending
-- amendment of version n lapses by derivation and a payoff quote made under version n is stale by derivation (P11-TSK-027,
-- P11-TSK-026); and replay re-derives each version from its own bytes.
--
-- Replaced, each its predecessor's body plus exactly the prepayment's change: repayment_is_born_once (V022) - a PREPAYMENT
-- under L3 on an ACTIVE, scheduled instalment loan, nothing billed owing, less than the principal not yet billed;
-- repayment_allocation_is_born_once (V017) - an instalment loan's principal not yet due is paid by a PREPAYMENT's one line
-- only; loan_agreement_is_born_once (V023) - version n+1 a prepayment's; loan_agreement_amended_beside_its_facts (V023) - a
-- prepayment's version beside its schedule, its term the instalments billed plus the schedule's; principal_not_yet_billed
-- (V023) - less what was prepaid; repayment_schedule_is_born_once (V023) - version n+1 of a prepayment's agreement, and for
-- every later version (an amendment's too) no instalment of the superseded schedule already due superseded unbilled;
-- instalment_billing_is_born_once (V023) - the principal billed plus prepaid never beyond the principal;
-- repayment_reversal_permits_only_machine_edges (V016) - a prepayment is never reversed (it moved the agreement: a correction
-- is an amendment). The CHECKs repayment_kind_is_known (V014), loan_agreement_origin (V006) and
-- loan_agreement_canonical_shape (V023) are replaced the same way. billing_component_billed (V023) is unchanged: a
-- prepayment touches no billed item.
--
-- Held for EVERY writer: the new branches take the loan's row lock (L3) themselves - re-entrant for the domain; UNIQUE
-- (loan_id, kind, request_key) on the repayment (ten prepayments under one key, one), UNIQUE (prepayment_id) and UNIQUE
-- (loan_id, version) on the agreement, UNIQUE (agreement_id) and UNIQUE (loan_id, schedule_version) on the schedule; at
-- commit a PREPAYMENT commits beside its agreement version, which commits beside its schedule. INSERT-only, never truncated.

-- ---------------------------------------------------------------------------------------------
-- The repayment kind PREPAYMENT, and the composite key an agreement version names it by.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE lending.repayment DROP CONSTRAINT repayment_kind_is_known;

ALTER TABLE lending.repayment
    ADD CONSTRAINT repayment_kind_is_known CHECK (kind IN ('CUSTOMER', 'AUTO_COLLECTION', 'PAYOFF', 'PREPAYMENT')),
    -- An agreement version names a prepayment of its own loan.
    ADD CONSTRAINT repayment_of_its_loan UNIQUE (id, loan_id);

COMMENT ON TABLE lending.repayment IS
    'Money received for a loan (P11-TSK-018; ADR-0095 point 3, PHASE_11_PLAN.md section 12.5, posting rule 5): born only once it moved - the kind (CUSTOMER; AUTO_COLLECTION P11-TSK-019, PAYOFF P11-TSK-026, PREPAYMENT P11-TSK-028 - principal not yet due paid early, which an agreement and schedule version n+1 name), the requester''s key (UNIQUE per loan and kind), the amount in the agreement''s currency and scale, the agreement and allocation engine versions it was allocated under, posting rule 5''s journal entry by value (DR CUSTOMER_WALLET / CR <due accounts> / CR LOAN_PRINCIPAL a line''s paydown or a prepayment / CR LOAN_CREDIT_BALANCE rest, key lending.repayment:<id>) and its value date. Its repayment_allocation rows sum to it at commit. REVERSED is derived from an approved reversal (P11-TSK-020), never written here; a PREPAYMENT is never reversed. Explains the due accounts'', LOAN_PRINCIPAL''s and the credit balance''s credits with the applications (the subledger proof, P11-TSK-029). INSERT-only for every role. RESTRICTED-FINANCIAL.';

-- V022's body, plus: a PREPAYMENT (P11-TSK-028) under the loan's row lock, on an ACTIVE scheduled instalment loan, with
-- nothing billed owing, strictly less than the principal not yet billed.
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
    -- by the repayment after its locks, so between the transaction's start and this statement.
    IF NEW.value_date < lending.business_date(transaction_timestamp(), zone)
            OR NEW.value_date > lending.business_date(statement_timestamp(), zone) THEN
        RAISE EXCEPTION 'a repayment''s value date is the business date of the database clock in its pinned zone (P11-TSK-018, INV-LND-10)';
    END IF;
    NEW.created_at := statement_timestamp();
    RETURN NEW;
END;
$$;

-- V017's body, plus: an instalment loan's principal not yet due is paid by a PREPAYMENT's line alone, and a PREPAYMENT's
-- every line pays principal not yet due (P11-TSK-028).
CREATE OR REPLACE FUNCTION lending.repayment_allocation_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    repayment lending.repayment%ROWTYPE;
    loan_kind text;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a repayment allocation is immutable from insert, for every role (P11-TSK-018, INV-LND-04)';
    END IF;
    SELECT r.* INTO repayment FROM lending.repayment r WHERE r.id = NEW.repayment_id;
    IF repayment.id IS NULL OR NEW.currency <> repayment.currency OR NEW.scale <> repayment.scale THEN
        RAISE EXCEPTION 'a repayment allocation is a line of its repayment, in its currency and scale (P11-TSK-018)';
    END IF;
    -- A billed item - a billing or a statement - of the repayment's own loan.
    IF NEW.target = 'DUE_ITEM' AND lending.billed_item_loan(NEW.billing_id) IS DISTINCT FROM repayment.loan_id THEN
        RAISE EXCEPTION 'a repayment allocation pays a billed item of its repayment''s own loan (P11-TSK-018, P11-TSK-022)';
    END IF;
    -- A prepayment pays principal not yet due, and nothing else (P11-TSK-028, AllocationEngineV1.allocatePrepayment).
    IF repayment.kind = 'PREPAYMENT' AND NEW.target <> 'NOT_DUE_PRINCIPAL' THEN
        RAISE EXCEPTION 'a prepayment''s one line pays principal not yet due (P11-TSK-028, INV-LND-04)';
    END IF;
    -- An instalment loan holds the remainder as credit (HOLD_AS_CREDIT, ADR-0095 point 2): an overpayment never reduces its
    -- principal - only a prepayment, its own command with its own re-schedule, does (P11-TSK-028).
    SELECT l.kind INTO loan_kind FROM lending.loan l WHERE l.id = repayment.loan_id;
    IF NEW.target = 'NOT_DUE_PRINCIPAL' AND loan_kind = 'INSTALMENT' AND repayment.kind <> 'PREPAYMENT' THEN
        RAISE EXCEPTION 'an instalment loan''s repayment never pays principal not yet due - a prepayment is its own command (HOLD_AS_CREDIT, P11-TSK-018, P11-TSK-028, ADR-0095)';
    END IF;
    RETURN NEW;
END;
$$;

-- At commit, for every writer: a prepayment commits beside the agreement version it made - money never moves onto
-- principal not yet due without the re-schedule that accounts for it (INV-LND-03).
CREATE OR REPLACE FUNCTION lending.repayment_prepayment_reschedules_at_commit()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM lending.loan_agreement a WHERE a.prepayment_id = NEW.id AND a.loan_id = NEW.loan_id) THEN
        RAISE EXCEPTION 'a prepayment committed without the agreement version it makes (P11-TSK-028, INV-LND-05)';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER repayment_prepayment_reschedules
    AFTER INSERT ON lending.repayment
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (NEW.kind = 'PREPAYMENT')
    EXECUTE FUNCTION lending.repayment_prepayment_reschedules_at_commit();

-- ---------------------------------------------------------------------------------------------
-- The agreement: a third origin - a prepayment's version.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE lending.loan_agreement
    ADD COLUMN prepayment_id uuid,
    ADD CONSTRAINT loan_agreement_prepayment_fk FOREIGN KEY (prepayment_id, loan_id)
        REFERENCES lending.repayment (id, loan_id),
    -- A prepayment re-schedules once: ten prepayments under one key, one version.
    ADD CONSTRAINT loan_agreement_prepayment_unique UNIQUE (prepayment_id);

ALTER TABLE lending.loan_agreement DROP CONSTRAINT loan_agreement_origin;

-- Version 1 is the offer's, accepted; every later version exactly one of an amendment's (P11-TSK-027) or a prepayment's
-- (P11-TSK-028).
ALTER TABLE lending.loan_agreement ADD CONSTRAINT loan_agreement_origin CHECK (
    (version = 1 AND offer_id IS NOT NULL AND amendment_id IS NULL AND prepayment_id IS NULL)
    OR (version > 1 AND offer_id IS NULL AND num_nonnulls(amendment_id, prepayment_id) = 1));

-- Format 4 - a prepayment's version's canonical terms (CanonicalOfferTerms.renderPrepayment) - for a prepayment's version,
-- and only there; format 3 for an amendment's; version 1 keeps V011's formats 1 and 2. The token set of formats 3 and 4 is
-- format 2's.
ALTER TABLE lending.loan_agreement DROP CONSTRAINT loan_agreement_canonical_shape;

ALTER TABLE lending.loan_agreement ADD CONSTRAINT loan_agreement_canonical_shape CHECK (
    char_length(canonical_terms) BETWEEN 2 AND 16384
    AND starts_with(canonical_terms, '{"format":"' || canonical_format || '",')
    AND ((version = 1
          AND ((canonical_format = 1 AND canonical_terms ~ '^\{[A-Za-z0-9_.:",{}\[\]-]*\}$')
               OR (canonical_format = 2 AND canonical_terms ~ '^\{[A-Za-z0-9_.:+",{}\[\]-]*\}$')))
         OR (version > 1 AND amendment_id IS NOT NULL AND canonical_format = 3
             AND canonical_terms ~ '^\{[A-Za-z0-9_.:+",{}\[\]-]*\}$')
         OR (version > 1 AND prepayment_id IS NOT NULL AND canonical_format = 4
             AND canonical_terms ~ '^\{[A-Za-z0-9_.:+",{}\[\]-]*\}$')));

COMMENT ON COLUMN lending.loan_agreement.prepayment_id IS
    'The prepayment (a repayment of kind PREPAYMENT of this loan) whose re-schedule this version is (P11-TSK-028) - NULL for version 1 and for an amendment''s version. UNIQUE: a prepayment makes one version.';

COMMENT ON TABLE lending.loan_agreement IS
    'A loan agreement''s versions (P11-TSK-012, ADR-0092 point 5, INV-LND-05): version 1 born in the acceptance as an exact copy of its offer''s terms - the canonical terms (in the offer''s format, 1 or 2) and their SHA-256, held to the bytes by CHECK, with the principal or limit, term, rate, day, engines and template beside them; version n+1 an accepted amendment''s (P11-TSK-027) - the same principal, decision, terms version, day, engines and template, the amended rate, the instalments billed plus the remaining ones, canonical terms format 3 and their hash - once per amendment, beside its evidence and its schedule; or a prepayment''s (P11-TSK-028) - everything version n''s but the term (the instalments billed plus the new schedule''s, never longer), canonical terms format 4 and their hash - once per prepayment, beside its schedule. INSERT-only and immutable for every role: it carries money (RESTRICTED-FINANCIAL). Every servicing row names the version it was computed under.';

-- V023's body, plus: version n+1 of a prepayment's (P11-TSK-028).
CREATE OR REPLACE FUNCTION lending.loan_agreement_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    account lending.loan%ROWTYPE;
    proposal lending.loan_amendment%ROWTYPE;
    decision lending.loan_amendment_decision%ROWTYPE;
    previous lending.loan_agreement%ROWTYPE;
    prepaid lending.repayment%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a loan agreement version is immutable from insert, for every role (P11-TSK-012, INV-LND-05)';
    END IF;
    IF NEW.version = 1 THEN
        -- Version 1: born beside its loan, for the loan's own offer and decision, as an EXACT copy of the offer's terms
        -- (INV-LND-05) - the bytes the customer was shown and hashed, never re-rendered.
        IF NOT EXISTS (
                SELECT 1
                  FROM lending.loan l
                  JOIN lending.loan_offer o ON o.id = l.offer_id
                  JOIN lending.loan_offer_terms t ON t.offer_id = o.id
                 WHERE l.id = NEW.loan_id AND l.offer_id = NEW.offer_id AND l.decision_id = NEW.decision_id
                   AND o.terms_version_id = NEW.terms_version_id AND o.product = NEW.product
                   AND t.currency = NEW.currency AND t.scale = NEW.scale AND t.amount_minor = NEW.amount_minor
                   AND t.term_months IS NOT DISTINCT FROM NEW.term_months
                   AND t.nominal_annual_rate = NEW.nominal_annual_rate AND t.repayment_day = NEW.repayment_day
                   AND t.schedule_engine_version IS NOT DISTINCT FROM NEW.schedule_engine_version
                   AND t.accrual_engine_version = NEW.accrual_engine_version
                   AND t.allocation_engine_version = NEW.allocation_engine_version
                   AND t.statement_engine_version IS NOT DISTINCT FROM NEW.statement_engine_version
                   AND t.agreement_template_id = NEW.agreement_template_id
                   AND t.agreement_template_version = NEW.agreement_template_version
                   AND t.canonical_format = NEW.canonical_format
                   AND t.canonical_terms = NEW.canonical_terms
                   AND t.terms_sha256 = NEW.terms_sha256) THEN
            RAISE EXCEPTION 'agreement version 1 is born beside its loan as an exact copy of its offer''s terms (P11-TSK-012, INV-LND-05)';
        END IF;
    ELSIF NEW.amendment_id IS NOT NULL THEN
        -- Version n+1 (P11-TSK-027): an accepted amendment's, under the loan's row lock (L3), on an ACTIVE instalment loan.
        SELECT l.* INTO account FROM lending.loan l WHERE l.id = NEW.loan_id FOR UPDATE;
        IF account.id IS NULL OR account.kind <> 'INSTALMENT' OR account.status <> 'ACTIVE' THEN
            RAISE EXCEPTION 'an agreement is amended only on an ACTIVE instalment loan - a closed loan lapses its amendment (P11-TSK-027, INV-LND-08)';
        END IF;
        -- APPROVED and unexpired on the database clock, of this loan.
        SELECT m.* INTO proposal FROM lending.loan_amendment m WHERE m.id = NEW.amendment_id AND m.loan_id = NEW.loan_id;
        SELECT d.* INTO decision FROM lending.loan_amendment_decision d WHERE d.amendment_id = NEW.amendment_id;
        IF proposal.id IS NULL OR decision.id IS NULL OR decision.outcome <> 'APPROVED'
                OR decision.expires_at <= statement_timestamp() THEN
            RAISE EXCEPTION 'an agreement version after the first is an APPROVED, unexpired amendment''s (P11-TSK-027, INV-LND-11)';
        END IF;
        -- THE VERSION CONDITIONAL: the agreement it amends is still the governing one, and this is the next version - a
        -- moved agreement (another amendment, a prepayment) lapses it.
        SELECT a.* INTO previous FROM lending.loan_agreement a
         WHERE a.loan_id = NEW.loan_id
           AND a.version = (SELECT max(x.version) FROM lending.loan_agreement x WHERE x.loan_id = NEW.loan_id);
        IF previous.id IS DISTINCT FROM proposal.amends_agreement_id OR previous.version <> proposal.amends_version
                OR NEW.version <> previous.version + 1 THEN
            RAISE EXCEPTION 'an amendment applies only to the agreement version it amends, as the next version - it lapsed (P11-TSK-027, INV-LND-05)';
        END IF;
        -- NEVER MORE CREDIT: the same principal, decision, terms version, product, currency, day, engines and template; the
        -- amended rate (never above the previous); the term the instalments billed plus the remaining instalments, never
        -- fewer than the current schedule has left.
        IF NEW.offer_id IS NOT NULL OR NEW.decision_id <> previous.decision_id
                OR NEW.terms_version_id <> previous.terms_version_id OR NEW.product <> previous.product
                OR NEW.currency <> previous.currency OR NEW.scale <> previous.scale
                OR NEW.amount_minor <> previous.amount_minor OR NEW.repayment_day <> previous.repayment_day
                OR NEW.schedule_engine_version IS DISTINCT FROM previous.schedule_engine_version
                OR NEW.accrual_engine_version <> previous.accrual_engine_version
                OR NEW.allocation_engine_version <> previous.allocation_engine_version
                OR NEW.statement_engine_version IS DISTINCT FROM previous.statement_engine_version
                OR NEW.agreement_template_id <> previous.agreement_template_id
                OR NEW.agreement_template_version <> previous.agreement_template_version
                OR NEW.nominal_annual_rate <> proposal.nominal_annual_rate
                OR NEW.nominal_annual_rate::numeric > previous.nominal_annual_rate::numeric
                OR proposal.remaining_instalments < lending.unbilled_instalments(NEW.loan_id)
                OR NEW.term_months IS DISTINCT FROM ((SELECT count(*) FROM lending.instalment_billing b
                                                       WHERE b.loan_id = NEW.loan_id AND b.kind = 'INSTALMENT')
                                                     + proposal.remaining_instalments)::smallint THEN
            RAISE EXCEPTION 'an amended agreement keeps its principal, decision, terms version, day, engines and template and takes exactly the amendment''s rate and term - never more credit (P11-TSK-027)';
        END IF;
    ELSE
        -- Version n+1 (P11-TSK-028): a prepayment's, under the loan's row lock (L3), on an ACTIVE instalment loan.
        SELECT l.* INTO account FROM lending.loan l WHERE l.id = NEW.loan_id FOR UPDATE;
        IF account.id IS NULL OR account.kind <> 'INSTALMENT' OR account.status <> 'ACTIVE' THEN
            RAISE EXCEPTION 'a prepayment re-schedules only an ACTIVE instalment loan (P11-TSK-028, INV-LND-08)';
        END IF;
        -- The prepayment was received under the governing version, and this is the next one: a prepayment re-schedules the
        -- agreement it was paid under, once (UNIQUE (prepayment_id)).
        SELECT a.* INTO previous FROM lending.loan_agreement a
         WHERE a.loan_id = NEW.loan_id
           AND a.version = (SELECT max(x.version) FROM lending.loan_agreement x WHERE x.loan_id = NEW.loan_id);
        SELECT r.* INTO prepaid FROM lending.repayment r
         WHERE r.id = NEW.prepayment_id AND r.loan_id = NEW.loan_id AND r.kind = 'PREPAYMENT';
        IF prepaid.id IS NULL OR prepaid.agreement_id IS DISTINCT FROM previous.id
                OR prepaid.agreement_version <> previous.version OR NEW.version <> previous.version + 1 THEN
            RAISE EXCEPTION 'a prepayment''s version is the next version of the agreement it was received under (P11-TSK-028, INV-LND-05)';
        END IF;
        -- NOTHING BUT THE TERM: version n's principal, decision, terms version, product, currency, RATE, day, engines and
        -- template; the term never longer (exactly the instalments billed plus the new schedule's - held at commit).
        IF NEW.offer_id IS NOT NULL OR NEW.decision_id <> previous.decision_id
                OR NEW.terms_version_id <> previous.terms_version_id OR NEW.product <> previous.product
                OR NEW.currency <> previous.currency OR NEW.scale <> previous.scale
                OR NEW.amount_minor <> previous.amount_minor OR NEW.repayment_day <> previous.repayment_day
                OR NEW.nominal_annual_rate <> previous.nominal_annual_rate
                OR NEW.schedule_engine_version IS DISTINCT FROM previous.schedule_engine_version
                OR NEW.accrual_engine_version <> previous.accrual_engine_version
                OR NEW.allocation_engine_version <> previous.allocation_engine_version
                OR NEW.statement_engine_version IS DISTINCT FROM previous.statement_engine_version
                OR NEW.agreement_template_id <> previous.agreement_template_id
                OR NEW.agreement_template_version <> previous.agreement_template_version
                OR NEW.term_months IS NULL OR NEW.term_months > previous.term_months THEN
            RAISE EXCEPTION 'a prepayment''s version keeps everything of the version it follows but a term never longer (P11-TSK-028)';
        END IF;
    END IF;
    NEW.created_at := statement_timestamp();
    NEW.effective_from := statement_timestamp();
    RETURN NEW;
END;
$$;

-- V023's body, plus: a prepayment's version commits beside its schedule version, its term the instalments billed plus that
-- schedule's (P11-TSK-028). An amendment's version still commits beside its acceptance evidence and its schedule.
CREATE OR REPLACE FUNCTION lending.loan_agreement_amended_beside_its_facts()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    schedule lending.repayment_schedule%ROWTYPE;
BEGIN
    IF NEW.amendment_id IS NOT NULL THEN
        IF NOT EXISTS (SELECT 1 FROM lending.loan_acceptance e WHERE e.agreement_id = NEW.id)
                OR NOT EXISTS (SELECT 1 FROM lending.repayment_schedule s WHERE s.agreement_id = NEW.id) THEN
            RAISE EXCEPTION 'an amended agreement version committed without its acceptance evidence and its schedule version (P11-TSK-027, INV-LND-05)';
        END IF;
        RETURN NULL;
    END IF;
    SELECT s.* INTO schedule FROM lending.repayment_schedule s WHERE s.agreement_id = NEW.id;
    IF schedule.id IS NULL
            OR NEW.term_months IS DISTINCT FROM ((SELECT count(*) FROM lending.instalment_billing b
                                                   WHERE b.loan_id = NEW.loan_id AND b.kind = 'INSTALMENT')
                                                 + schedule.term_months)::smallint THEN
        RAISE EXCEPTION 'a prepayment''s agreement version committed without its schedule version, or with a term not the instalments billed plus that schedule''s (P11-TSK-028, INV-LND-05)';
    END IF;
    RETURN NULL;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The principal not yet billed, replaced: V023's body less what was prepaid.
-- ---------------------------------------------------------------------------------------------

-- The principal an instalment loan has not yet billed, from its rows: agreement version 1's principal less the principal
-- billed net of re-scheduling (lending.billing_component_billed) less the principal prepaid (P11-TSK-028: a PREPAYMENT's
-- NOT_DUE_PRINCIPAL lines; a prepayment is never reversed). Exactly LOAN_PRINCIPAL for an instalment loan: that account moves
-- only by the disbursement (+P), rule 3 (- billed), rule 13 (+ re-scheduled) and a prepayment's rule 5 (- prepaid) - the
-- subledger proof's arm.
CREATE OR REPLACE FUNCTION lending.principal_not_yet_billed(account uuid)
    RETURNS bigint
    LANGUAGE sql
    STABLE
AS $$
    SELECT ((SELECT a.amount_minor FROM lending.loan_agreement a WHERE a.loan_id = account AND a.version = 1)
            - coalesce((SELECT sum(lending.billing_component_billed(b.id, 'PRINCIPAL'))
                          FROM lending.instalment_billing b WHERE b.loan_id = account), 0)
            - coalesce((SELECT sum(p.amount_minor)
                          FROM lending.repayment_allocation p
                          JOIN lending.repayment r ON r.id = p.repayment_id
                         WHERE r.loan_id = account AND r.kind = 'PREPAYMENT' AND p.target = 'NOT_DUE_PRINCIPAL'), 0))::bigint
$$;

-- ---------------------------------------------------------------------------------------------
-- The schedule, replaced: V023's body plus version n+1 of a prepayment's agreement.
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
                OR NEW.start_date > lending.business_date(statement_timestamp(), zone)
                OR EXISTS (SELECT 1 FROM lending.instalment_billing b
                            WHERE b.loan_id = NEW.loan_id AND b.due_date > NEW.start_date) THEN
            RAISE EXCEPTION 'a later schedule version starts at the open period''s start - after every billed due date, never after today (P11-TSK-027)';
        END IF;
        -- THE BILLING FIRST, for every writer (P11-TSK-028): no instalment of the superseded schedule already due - its due
        -- date come by the transaction's business date in the pinned zone - is superseded unbilled, so neither it nor the
        -- interest its period accrued is stranded (PHASE_11_PLAN.md section 12.2 step 7). The transaction's start, never a
        -- later statement's: the domain bills through a date read after its lock, never earlier, so this never refuses it.
        IF EXISTS (SELECT 1 FROM lending.loan_instalment i
                    WHERE i.schedule_id = previous.id
                      AND i.due_date <= lending.business_date(transaction_timestamp(), zone)
                      AND NOT EXISTS (SELECT 1 FROM lending.instalment_billing b WHERE b.instalment_id = i.id)) THEN
            RAISE EXCEPTION 'a schedule is superseded only once every instalment of it already due is billed - the billing first (P11-TSK-028, INV-LND-03)';
        END IF;
    END IF;
    NEW.created_at := statement_timestamp();
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- V023's billing trigger, replaced: the principal billed net of re-scheduling PLUS the principal prepaid never beyond the
-- principal (P11-TSK-017's carry, for a prepayment).
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
    IF NEW.due_date > lending.business_date(statement_timestamp(), zone)
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
-- V016's reversal edge function, replaced: a prepayment is never reversed.
-- ---------------------------------------------------------------------------------------------
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
        -- A prepayment moved the agreement and the schedule (P11-TSK-028): swapping its entry would leave the schedule
        -- amortising less principal than the loan owes. Its correction is an amendment, never a reversal.
        IF repayment.kind = 'PREPAYMENT' THEN
            RAISE EXCEPTION 'a prepayment is never reversed - it moved the agreement; a correction is an amendment (P11-TSK-028, ADR-0099)';
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
