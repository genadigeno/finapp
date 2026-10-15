-- The amendment's lapse at the agreement's birth refused in its own SQLSTATE (P11-TSK-035, a Phase 11 remediation; lending
-- V023 / P11-TSK-027, V024 / P11-TSK-028; PHASE_11_PLAN.md section 7.1 T-amd, LENDING_LIFECYCLES.md section 4.9; INV-LND-10,
-- INV-LND-11; found by P11-TSK-034's completion gate - the same race P11-TSK-034 closed for the offer, on the amendment).
--
-- The acceptance (LoanAmendments.accept) judges the amendment's offer unexpired on the database clock at its L5 read, under
-- the loan's row lock (L3), then accrues every elapsed date, bills everything due and plans the new schedule - posting to
-- the loan's ledger accounts, so it can wait on them - and only then births agreement version n+1. The agreement's trigger
-- re-judges the decision's expires_at at the INSERT, as the strict backstop it is: an agreement version is never born for
-- an amendment whose offer expired at that instant. But it refused in the generic P0001 that every invariant breach of the
-- trigger shares, so when the clock crossed expires_at during that work the customer's lapse surfaced as a storage
-- failure - 500 api.InternalError - where the same lapse judged a moment earlier is 409 lending.AmendmentLapsed. No money
-- was at risk (the transaction rolled back, the catch-up with it); the error contract was broken.
--
-- The decision (P11-TSK-034's shape, owner-approved for the offer): the lapse gets its own SQLSTATE, LNAEX - a custom code
-- outside every class PostgreSQL defines, after fx V005's FXCAP and lending V026's LNEXP, and a sibling of LNEXP rather
-- than LNEXP itself: the two windows belong to two aggregates and answer two refusals (OfferNotAcceptable,
-- AmendmentLapsed), so one code names one refusal and no mapping depends on which statement raised it. The amendment's
-- acceptance maps exactly that code to AmendmentLapsed (JdbcLoanStore.insertAgreement). Every other refusal of this
-- trigger stays P0001 and a 500: a version for a closed loan, a foreign, undecided or rejected amendment, a moved
-- agreement, more credit, a malformed version 1 or a prepayment's version is an invariant breach, never a client error -
-- and the lapse is judged LAST, after every one of them, so an amendment both expired and breached is refused as the
-- breach.
--
-- One function is replaced, verbatim but for that split (migrator-owned; like V023's and V024's, schema-qualified and
-- invoker's rights, so no search_path is set): lending.loan_agreement_is_born_once, V024's - the latest. Its amendment
-- branch's first check loses its expiry arm (the message unchanged) and the expiry is judged after "never more credit",
-- carrying LNAEX. Version 1 and a prepayment's version judge no expiry and are unchanged. What is refused, for every
-- writer, is unchanged; only the code the lapse carries is. Flyway never edits an applied migration: this replaces, it does
-- not amend.

-- ---------------------------------------------------------------------------------------------
-- The agreement version's birth, replaced: V024's, the amendment's lapse judged last in its own SQLSTATE.
-- ---------------------------------------------------------------------------------------------
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
        IF proposal.id IS NULL OR decision.id IS NULL OR decision.outcome <> 'APPROVED' THEN
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
        -- The lapse, judged last - after the loan, the amendment's identity and decision, the version conditional and
        -- never more credit, so a breach of any of them is never answered as a lapse - and in its own SQLSTATE
        -- (P11-TSK-035): the acceptance judged the amendment unexpired at its L5 read and then accrued, billed and
        -- planned, so the database clock may cross the offer's expires_at before this statement - the customer's 409,
        -- not a breach.
        IF decision.expires_at <= statement_timestamp() THEN
            RAISE EXCEPTION 'an agreement version is never born for a lapsed amendment - its offer expired (P11-TSK-027, P11-TSK-035, INV-LND-11)'
                USING ERRCODE = 'LNAEX';
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
