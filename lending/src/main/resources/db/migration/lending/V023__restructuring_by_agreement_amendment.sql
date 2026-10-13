-- Restructuring by agreement amendment (P11-TSK-027; PHASE_11_PLAN.md sections 5.3, 5.4, 7.1 T-amd, 7.2, 7.4 scenario 8,
-- 11, 12.1, 12.2 step 7, 12.7 and 12.8 rule 13; ADR-0099 point 5; INV-LND-05, INV-LND-11, INV-LND-03, INV-AUD-04,
-- INV-HIST-04). The number is V023 - the next free lending version (the backlog's "V020" is stale: P11-TSK-024's).
--
-- A restructuring is not a state: it is agreement version n+1 on an ACTIVE instalment loan. A holder of LOAN_SERVICE
-- proposes it - a lower or equal rate, a remaining term never shorter than what the current schedule has left, and
-- whether the arrears (billed principal past due) are re-scheduled - with a reason; a DIFFERENT holder of
-- LOAN_SERVICE_APPROVE approves it, which opens the customer's offer for the pinned offer validity; the CUSTOMER accepts
-- it (MULTI_FACTOR, the acceptance evidence as P11-TSK-012's). No amendment names an amount: it never raises principal
-- outstanding or a limit - more credit is a new application and a new decision.
--
--     loan_amendment                - the proposal: the agreement version it amends, the proposed rate, the remaining
--                                     instalments, the arrears flag, the SHA-256 of the document the customer is shown,
--                                     the proposer and the reason. Born once.
--     loan_amendment_decision       - APPROVED (with the offer's expiry, stamped by the database) or REJECTED - born once,
--                                     UNIQUE (amendment_id); four eyes by CHECK over the proposer copied and pinned by the
--                                     composite key (P11-TSK-025's waiver pattern).
--     loan_amendment_rescheduling   - an accepted amendment's re-scheduled arrears, one row per billed item: exactly that
--                                     item's unpaid billed principal, named for posting rule 13's entry (DR LOAN_PRINCIPAL /
--                                     CR LOAN_PRINCIPAL_DUE, lending.amendment:<amendment>) - overdue principal re-classified
--                                     as not due. lending.billing_component_billed(item, 'PRINCIPAL') - replaced here - is
--                                     the item's billed principal less what was re-scheduled, so the due state, DPD, the
--                                     closure's rows-side rule and every never-over-paid rule read the re-classification.
--
-- The status is derived, never written: no decision PROPOSED; REJECTED; APPROVED and accepted (agreement version n+1 names
-- it) ACCEPTED; otherwise LAPSED once the agreement it amends is no longer the loan's latest, the loan is no longer ACTIVE
-- or (APPROVED) the offer expired on the database clock - else PROPOSED or APPROVED. A lapse is a fact of the clock and the
-- rows, so no sweeper writes it.
--
-- THE ACCEPTANCE (the customer's act, one transaction under the loan's row lock L3): every elapsed date accrued and
-- everything due billed first (the servicing step's own accrual and billing, so the billed instalments kept include today's);
-- then agreement version n+1 (an amendment's: the same principal, decision, terms version, day, engines and template; the
-- amended rate; term_months the instalments billed plus the remaining instalments; canonical terms FORMAT 3 - the amended
-- agreement's own self-contained bytes - and their hash; effective_from the database's instant), its evidence, the
-- re-scheduled arrears with rule 13, and schedule version n+1 by the pinned engine's recalculation (P11-TSK-006) from the
-- principal outstanding - LOAN_PRINCIPAL after rule 13, which the rows explain as agreement v1's principal less the
-- principal billed net of re-scheduling - over the period that holds the business date (its start the latest billed due
-- date, so the accruals already posted in the open period are the new schedule's first period's), billed instalments kept,
-- unbilled superseded (nothing updated). Overdue interest and fees stay due on their items - never capitalised.
--
-- Replaced, each its predecessor's body plus exactly the amendment's change: loan_agreement_is_born_once (V006) - version
-- n+1, an accepted amendment's; loan_acceptance_is_born_once (V006) - the evidence of an amendment's acceptance binds the
-- amendment's document hash; repayment_schedule_is_born_once (V008) - version n+1 from the principal outstanding;
-- instalment_billing_is_born_once (V022) - the principal billed over a loan's life net of re-scheduling never beyond its
-- principal (P11-TSK-017's carry), and a billing names its schedule's agreement version; billing_component_billed (V021) -
-- less what was re-scheduled. The canonical-shape CHECK of loan_agreement (V011) admits format 3 for a version after the
-- first, and only there.
--
-- Held for EVERY writer: each new trigger takes the loan's row lock (L3) itself - re-entrant for the domain - so the "one
-- open amendment", the version conditional and the bounds are judged under the lock that serialises every act on the
-- account; UNIQUE (loan_id, version) and UNIQUE (amendment_id) on the agreement, UNIQUE (agreement_id) and UNIQUE (loan_id,
-- schedule_version) on the schedule; two deferred checks - an amended agreement commits only beside its evidence and its
-- schedule, and a later schedule only continuing the open period's accruals, its first due date after every billed one.
-- INSERT-only, never truncated.

-- ---------------------------------------------------------------------------------------------
-- Helpers: what the rows say the loan has not yet billed, and the current schedule's unbilled instalments.
-- ---------------------------------------------------------------------------------------------

-- The principal an instalment loan has not yet billed, from its rows: agreement version 1's principal less the principal
-- billed net of re-scheduling (lending.billing_component_billed). Exactly LOAN_PRINCIPAL for an instalment loan: that
-- account moves only by the disbursement (+P), rule 3 (- billed) and rule 13 (+ re-scheduled) - the subledger proof's arm.
CREATE OR REPLACE FUNCTION lending.principal_not_yet_billed(account uuid)
    RETURNS bigint
    LANGUAGE sql
    STABLE
AS $$
    SELECT ((SELECT a.amount_minor FROM lending.loan_agreement a WHERE a.loan_id = account AND a.version = 1)
            - coalesce((SELECT sum(lending.billing_component_billed(b.id, 'PRINCIPAL'))
                          FROM lending.instalment_billing b WHERE b.loan_id = account), 0))::bigint
$$;

-- The instalments of a loan's latest schedule version not yet billed.
CREATE OR REPLACE FUNCTION lending.unbilled_instalments(account uuid)
    RETURNS integer
    LANGUAGE sql
    STABLE
AS $$
    SELECT count(*)::integer
      FROM lending.loan_instalment i
     WHERE i.schedule_id = (SELECT s.id FROM lending.repayment_schedule s WHERE s.loan_id = account
                             ORDER BY s.schedule_version DESC LIMIT 1)
       AND NOT EXISTS (SELECT 1 FROM lending.instalment_billing b WHERE b.instalment_id = i.id)
$$;

-- ---------------------------------------------------------------------------------------------
-- The proposal.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE lending.loan_amendment (
    id                     uuid        NOT NULL,
    loan_id                uuid        NOT NULL,
    amends_agreement_id    uuid        NOT NULL,
    amends_version         integer     NOT NULL,
    nominal_annual_rate    text        NOT NULL,
    remaining_instalments  smallint    NOT NULL,
    reschedule_arrears     boolean     NOT NULL,
    document_sha256        bytea       NOT NULL,
    proposed_by            text        NOT NULL,
    proposed_by_type       text        NOT NULL,
    proposed_at            timestamptz NOT NULL,
    proposal_reason        text        NOT NULL,
    correlation_id         text        NOT NULL,
    CONSTRAINT loan_amendment_pk PRIMARY KEY (id),
    -- The decision's composite key pins its copy of the proposer to this row's.
    CONSTRAINT loan_amendment_proposer_pinned UNIQUE (id, proposed_by),
    -- An agreement version and a re-scheduling name an amendment of their own loan.
    CONSTRAINT loan_amendment_of_its_loan UNIQUE (id, loan_id),
    CONSTRAINT loan_amendment_loan_fk FOREIGN KEY (loan_id) REFERENCES lending.loan (id),
    CONSTRAINT loan_amendment_agreement_fk FOREIGN KEY (amends_agreement_id) REFERENCES lending.loan_agreement (id),
    CONSTRAINT loan_amendment_version_numbered CHECK (amends_version >= 1),
    CONSTRAINT loan_amendment_rate_canonical CHECK (nominal_annual_rate ~ '^(0|0\.[0-9]{0,7}[1-9]|1)$'),
    -- At least one instalment, and within the schedule engine's range (ScheduleEngineV1.MAXIMUM_TERM_MONTHS).
    CONSTRAINT loan_amendment_term_in_range CHECK (remaining_instalments BETWEEN 1 AND 600),
    CONSTRAINT loan_amendment_document_hash_shape CHECK (octet_length(document_sha256) = 32),
    CONSTRAINT loan_amendment_actor_type_known CHECK (
        proposed_by_type IN ('SYSTEM', 'CUSTOMER', 'EMPLOYEE', 'SERVICE', 'MERCHANT')),
    CONSTRAINT loan_amendment_actor_bounded CHECK (char_length(proposed_by) BETWEEN 1 AND 200),
    CONSTRAINT loan_amendment_proposal_reasoned CHECK (char_length(proposal_reason) BETWEEN 1 AND 1000),
    CONSTRAINT loan_amendment_proposal_reason_no_instrument_shape CHECK (NOT lending.holds_instrument_shape(proposal_reason)),
    CONSTRAINT loan_amendment_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

CREATE INDEX loan_amendment_by_loan ON lending.loan_amendment (loan_id, proposed_at);

COMMENT ON TABLE lending.loan_amendment IS
    'A restructuring''s proposal (P11-TSK-027; ADR-0099 point 5, INV-LND-11): an ACTIVE instalment loan''s agreement version it amends, the proposed nominal annual rate (never above that version''s), the remaining instalments of the new schedule (never fewer than the current schedule has left), whether the arrears are re-scheduled, the SHA-256 of the document the customer is shown, the proposer (a holder of LOAN_SERVICE) and the reason. Names no amount: an amendment never raises principal outstanding or a limit. Born once, INSERT-only for every role; its decision is loan_amendment_decision, its acceptance agreement version n+1. One open per loan. Rate RESTRICTED-FINANCIAL; the reason a person''s prose, screened for instrument shapes.';

CREATE OR REPLACE FUNCTION lending.loan_amendment_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    account lending.loan%ROWTYPE;
    agreement lending.loan_agreement%ROWTYPE;
    unbilled integer;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'an amendment is a born-once fact (P11-TSK-027): its decision is loan_amendment_decision';
    END IF;
    -- (L3) The account, for every writer.
    SELECT l.* INTO account FROM lending.loan l WHERE l.id = NEW.loan_id FOR UPDATE;
    IF account.id IS NULL OR account.kind <> 'INSTALMENT' OR account.status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'an amendment is proposed only on an ACTIVE instalment loan - a line has no schedule to restructure (P11-TSK-027, INV-LND-08)';
    END IF;
    -- It amends the loan's governing agreement version - its highest.
    SELECT a.* INTO agreement FROM lending.loan_agreement a
     WHERE a.loan_id = NEW.loan_id
       AND a.version = (SELECT max(x.version) FROM lending.loan_agreement x WHERE x.loan_id = NEW.loan_id);
    IF agreement.id IS DISTINCT FROM NEW.amends_agreement_id OR agreement.version <> NEW.amends_version THEN
        RAISE EXCEPTION 'an amendment amends its loan''s governing agreement version (P11-TSK-027, INV-LND-05)';
    END IF;
    -- A scheduled loan only: one awaiting its payout's outcome has nothing to restructure (P11-TSK-015's carry).
    IF NOT EXISTS (SELECT 1 FROM lending.repayment_schedule s WHERE s.loan_id = NEW.loan_id) THEN
        RAISE EXCEPTION 'an amendment restructures a scheduled loan (P11-TSK-027)';
    END IF;
    -- Never a higher rate (a new decision), never a shorter remaining term, and something changes.
    unbilled := lending.unbilled_instalments(NEW.loan_id);
    IF NEW.nominal_annual_rate::numeric > agreement.nominal_annual_rate::numeric
            OR NEW.remaining_instalments < unbilled
            OR NOT (NEW.nominal_annual_rate::numeric < agreement.nominal_annual_rate::numeric
                    OR NEW.remaining_instalments > unbilled OR NEW.reschedule_arrears) THEN
        RAISE EXCEPTION 'an amendment lowers the rate, lengthens the remaining term or re-schedules the arrears - never a higher rate or a shorter term (P11-TSK-027)';
    END IF;
    -- One open amendment per loan: none undecided, none approved, unaccepted and unexpired, on the governing version.
    IF EXISTS (SELECT 1 FROM lending.loan_amendment m
                 LEFT JOIN lending.loan_amendment_decision d ON d.amendment_id = m.id
                WHERE m.loan_id = NEW.loan_id AND m.amends_version = agreement.version
                  AND (d.id IS NULL
                       OR (d.outcome = 'APPROVED' AND d.expires_at > statement_timestamp()
                           AND NOT EXISTS (SELECT 1 FROM lending.loan_agreement x WHERE x.amendment_id = m.id)))) THEN
        RAISE EXCEPTION 'an amendment of this loan is already open (P11-TSK-027)';
    END IF;
    NEW.proposed_at := transaction_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER loan_amendment_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON lending.loan_amendment
    FOR EACH ROW
    EXECUTE FUNCTION lending.loan_amendment_is_born_once();

CREATE TRIGGER loan_amendment_is_never_truncated
    BEFORE TRUNCATE ON lending.loan_amendment
    FOR EACH STATEMENT
    EXECUTE FUNCTION lending.loan_is_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The decision: born once.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE lending.loan_amendment_decision (
    id               uuid        NOT NULL,
    amendment_id     uuid        NOT NULL,
    proposed_by      text        NOT NULL,
    outcome          text        NOT NULL,
    decided_by       text        NOT NULL,
    decided_by_type  text        NOT NULL,
    decided_at       timestamptz NOT NULL,
    expires_at       timestamptz,
    decision_reason  text        NOT NULL,
    correlation_id   text        NOT NULL,
    CONSTRAINT loan_amendment_decision_pk PRIMARY KEY (id),
    CONSTRAINT loan_amendment_decision_amendment_fk FOREIGN KEY (amendment_id, proposed_by)
        REFERENCES lending.loan_amendment (id, proposed_by),
    -- Decided once: ten approvers, one decision - the arbiter beneath L3.
    CONSTRAINT loan_amendment_decision_once UNIQUE (amendment_id),
    CONSTRAINT loan_amendment_decision_outcome_is_known CHECK (outcome IN ('APPROVED', 'REJECTED')),
    -- FOUR EYES, for every writer (INV-AUD-04, INV-LND-11): whoever approved an amendment did not propose it. A rejection
    -- may be the proposer's withdrawal.
    CONSTRAINT loan_amendment_decision_four_eyes CHECK (outcome <> 'APPROVED' OR decided_by <> proposed_by),
    -- An approval opens the customer's offer until its expiry; a rejection opens nothing.
    CONSTRAINT loan_amendment_decision_expiry_by_outcome CHECK (
        (outcome = 'APPROVED') = (expires_at IS NOT NULL) AND (expires_at IS NULL OR expires_at > decided_at)),
    CONSTRAINT loan_amendment_decision_actor_type_known CHECK (
        decided_by_type IN ('SYSTEM', 'CUSTOMER', 'EMPLOYEE', 'SERVICE', 'MERCHANT')),
    CONSTRAINT loan_amendment_decision_actor_bounded CHECK (
        char_length(decided_by) BETWEEN 1 AND 200 AND char_length(proposed_by) BETWEEN 1 AND 200),
    CONSTRAINT loan_amendment_decision_reasoned CHECK (char_length(decision_reason) BETWEEN 1 AND 1000),
    CONSTRAINT loan_amendment_decision_reason_no_instrument_shape CHECK (NOT lending.holds_instrument_shape(decision_reason)),
    CONSTRAINT loan_amendment_decision_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

COMMENT ON TABLE lending.loan_amendment_decision IS
    'An amendment''s decision (P11-TSK-027; ADR-0099, INV-LND-11, INV-AUD-04): APPROVED by a holder of LOAN_SERVICE_APPROVE who did not propose it (four-eyes CHECK over the proposer copied and pinned by the composite foreign key) - only on an ACTIVE loan while the agreement version it amends is still the latest, opening the customer''s offer until expires_at (the database''s instant plus the pinned offer validity) - or REJECTED (the proposer''s withdrawal included), with the decider''s reason. UNIQUE (amendment_id): decided once. INSERT-only for every role.';

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
    NEW.decided_at := transaction_timestamp();
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
        NEW.expires_at := transaction_timestamp() + make_interval(days => validity);
    ELSE
        NEW.expires_at := NULL;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER loan_amendment_decision_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON lending.loan_amendment_decision
    FOR EACH ROW
    EXECUTE FUNCTION lending.loan_amendment_decision_is_born_once();

CREATE TRIGGER loan_amendment_decision_is_never_truncated
    BEFORE TRUNCATE ON lending.loan_amendment_decision
    FOR EACH STATEMENT
    EXECUTE FUNCTION lending.loan_is_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The agreement: version n+1 is an accepted amendment's, once.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE lending.loan_agreement
    ADD CONSTRAINT loan_agreement_amendment_fk FOREIGN KEY (amendment_id, loan_id)
        REFERENCES lending.loan_amendment (id, loan_id),
    -- An amendment is accepted once: ten acceptances, one version.
    ADD CONSTRAINT loan_agreement_amendment_unique UNIQUE (amendment_id);

-- Format 3 - the amended agreement's canonical terms (CanonicalOfferTerms.renderAmendment) - for a version after the first,
-- and only there; version 1 keeps V011's formats 1 and 2. The token set of format 3 is format 2's.
ALTER TABLE lending.loan_agreement DROP CONSTRAINT loan_agreement_canonical_shape;

ALTER TABLE lending.loan_agreement ADD CONSTRAINT loan_agreement_canonical_shape CHECK (
    char_length(canonical_terms) BETWEEN 2 AND 16384
    AND starts_with(canonical_terms, '{"format":"' || canonical_format || '",')
    AND ((version = 1
          AND ((canonical_format = 1 AND canonical_terms ~ '^\{[A-Za-z0-9_.:",{}\[\]-]*\}$')
               OR (canonical_format = 2 AND canonical_terms ~ '^\{[A-Za-z0-9_.:+",{}\[\]-]*\}$')))
         OR (version > 1 AND canonical_format = 3 AND canonical_terms ~ '^\{[A-Za-z0-9_.:+",{}\[\]-]*\}$')));

COMMENT ON TABLE lending.loan_agreement IS
    'A loan agreement''s versions (P11-TSK-012, ADR-0092 point 5, INV-LND-05): version 1 born in the acceptance as an exact copy of its offer''s terms - the canonical terms (in the offer''s format, 1 or 2) and their SHA-256, held to the bytes by CHECK, with the principal or limit, term, rate, day, engines and template beside them; version n+1 an accepted amendment''s (P11-TSK-027) - the same principal, decision, terms version, day, engines and template, the amended rate, the instalments billed plus the remaining ones, canonical terms format 3 and their hash - once per amendment, beside its evidence and its schedule. INSERT-only and immutable for every role: it carries money (RESTRICTED-FINANCIAL). Every servicing row names the version it was computed under.';

CREATE OR REPLACE FUNCTION lending.loan_agreement_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    account lending.loan%ROWTYPE;
    proposal lending.loan_amendment%ROWTYPE;
    decision lending.loan_amendment_decision%ROWTYPE;
    previous lending.loan_agreement%ROWTYPE;
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
    ELSE
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
    END IF;
    NEW.created_at := statement_timestamp();
    NEW.effective_from := statement_timestamp();
    RETURN NEW;
END;
$$;

-- At commit, for every writer: an amended agreement version is born beside its acceptance evidence and its schedule
-- version - the contract, its evidence and its projection commit together or not at all.
CREATE OR REPLACE FUNCTION lending.loan_agreement_amended_beside_its_facts()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM lending.loan_acceptance e WHERE e.agreement_id = NEW.id)
            OR NOT EXISTS (SELECT 1 FROM lending.repayment_schedule s WHERE s.agreement_id = NEW.id) THEN
        RAISE EXCEPTION 'an amended agreement version committed without its acceptance evidence and its schedule version (P11-TSK-027, INV-LND-05)';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER loan_agreement_amended_beside_its_facts
    AFTER INSERT ON lending.loan_agreement
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (NEW.version > 1)
    EXECUTE FUNCTION lending.loan_agreement_amended_beside_its_facts();

-- ---------------------------------------------------------------------------------------------
-- The evidence: an amendment's acceptance binds the amendment's document.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION lending.loan_acceptance_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'acceptance evidence is immutable from insert, for every role (P11-TSK-012, INV-LND-05)';
    END IF;
    IF NEW.agreement_version = 1 THEN
        -- The evidence binds what was accepted: its agreement's version, hash and template, and the offer's own document
        -- hash, the bytes the customer was shown.
        IF NOT EXISTS (
                SELECT 1
                  FROM lending.loan_agreement a
                  JOIN lending.loan_offer_terms t ON t.offer_id = a.offer_id
                 WHERE a.id = NEW.agreement_id AND a.loan_id = NEW.loan_id AND a.version = NEW.agreement_version
                   AND a.offer_id = NEW.offer_id AND a.terms_sha256 = NEW.terms_sha256
                   AND a.agreement_template_id = NEW.agreement_template_id
                   AND a.agreement_template_version = NEW.agreement_template_version
                   AND t.document_sha256 = NEW.document_sha256) THEN
            RAISE EXCEPTION 'acceptance evidence binds its agreement''s version, terms hash, template and document hash (P11-TSK-012)';
        END IF;
    ELSIF NEW.offer_id IS NOT NULL OR NOT EXISTS (
            SELECT 1
              FROM lending.loan_agreement a
              JOIN lending.loan_amendment m ON m.id = a.amendment_id
             WHERE a.id = NEW.agreement_id AND a.loan_id = NEW.loan_id AND a.version = NEW.agreement_version
               AND a.terms_sha256 = NEW.terms_sha256
               AND a.agreement_template_id = NEW.agreement_template_id
               AND a.agreement_template_version = NEW.agreement_template_version
               AND m.document_sha256 = NEW.document_sha256) THEN
        -- An amended version (P11-TSK-027): the amendment's own document hash - the document the customer was shown.
        RAISE EXCEPTION 'an amendment''s acceptance evidence binds its agreement''s version, terms hash and template and the amendment''s document hash (P11-TSK-027)';
    END IF;
    NEW.accepted_at := statement_timestamp();
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The re-scheduled arrears: overdue principal re-classified as not due (posting rule 13).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE lending.loan_amendment_rescheduling (
    id                uuid        NOT NULL,
    amendment_id      uuid        NOT NULL,
    loan_id           uuid        NOT NULL,
    billing_id        uuid        NOT NULL,
    currency          char(3)     NOT NULL,
    scale             smallint    NOT NULL,
    principal_minor   bigint      NOT NULL,
    journal_entry_id  uuid        NOT NULL,
    created_at        timestamptz NOT NULL,
    correlation_id    text        NOT NULL,
    CONSTRAINT loan_amendment_rescheduling_pk PRIMARY KEY (id),
    -- One row per amendment and billed item.
    CONSTRAINT loan_amendment_rescheduling_once_per_item UNIQUE (amendment_id, billing_id),
    CONSTRAINT loan_amendment_rescheduling_amendment_fk FOREIGN KEY (amendment_id, loan_id)
        REFERENCES lending.loan_amendment (id, loan_id),
    CONSTRAINT loan_amendment_rescheduling_billing_fk FOREIGN KEY (billing_id) REFERENCES lending.instalment_billing (id),
    CONSTRAINT loan_amendment_rescheduling_currency_is_the_products CHECK (currency = 'EUR' AND scale BETWEEN 0 AND 9),
    CONSTRAINT loan_amendment_rescheduling_positive CHECK (principal_minor > 0),
    CONSTRAINT loan_amendment_rescheduling_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

CREATE INDEX loan_amendment_rescheduling_by_billing ON lending.loan_amendment_rescheduling (billing_id);

COMMENT ON TABLE lending.loan_amendment_rescheduling IS
    'An accepted amendment''s re-scheduled arrears (P11-TSK-027; PHASE_11_PLAN.md section 12.8 rule 13): one billed item''s whole unpaid billed principal, the item past due on the acceptance''s business date, re-classified as not due by posting rule 13 (DR LOAN_PRINCIPAL / CR LOAN_PRINCIPAL_DUE, key lending.amendment:<amendment>) - the entry named. lending.billing_component_billed(item, ''PRINCIPAL'') is the item''s billed principal less these rows; the principal joins the new schedule version. Never interest or fees: overdue interest is never capitalised. Born only in the acceptance, before the schedule version. INSERT-only for every role. RESTRICTED-FINANCIAL.';

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
    IF billing.id IS NULL OR billing.due_date >= lending.business_date(transaction_timestamp(), zone)
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

CREATE TRIGGER loan_amendment_rescheduling_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON lending.loan_amendment_rescheduling
    FOR EACH ROW
    EXECUTE FUNCTION lending.loan_amendment_rescheduling_is_born_once();

CREATE TRIGGER loan_amendment_rescheduling_is_never_truncated
    BEFORE TRUNCATE ON lending.loan_amendment_rescheduling
    FOR EACH STATEMENT
    EXECUTE FUNCTION lending.loan_is_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- What a billed item's component billed, replaced: V021's figures, its principal less what amendments re-scheduled.
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
                     OR (billed_component = 'INTEREST' AND w.kind = 'INTEREST')))
          - (SELECT coalesce(sum(r.principal_minor), 0) FROM lending.loan_amendment_rescheduling r
              WHERE r.billing_id = billing AND billed_component = 'PRINCIPAL'))::bigint
$$;

-- ---------------------------------------------------------------------------------------------
-- The schedule: version n+1 from the principal outstanding, once per agreement version.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE lending.repayment_schedule
    -- One schedule per agreement version: an amendment's projection is born once.
    ADD CONSTRAINT repayment_schedule_agreement_unique UNIQUE (agreement_id);

CREATE OR REPLACE FUNCTION lending.repayment_schedule_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    agreement lending.loan_agreement%ROWTYPE;
    proposal lending.loan_amendment%ROWTYPE;
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
        -- Version n+1 (P11-TSK-027): the loan's latest agreement version - an amendment's - by its pinned engine, the next
        -- schedule version, while ACTIVE.
        SELECT a.* INTO agreement
          FROM lending.loan_agreement a JOIN lending.loan l ON l.id = a.loan_id
         WHERE a.id = NEW.agreement_id AND a.loan_id = NEW.loan_id AND a.version = NEW.agreement_version
           AND a.version > 1 AND a.amendment_id IS NOT NULL
           AND a.version = (SELECT max(x.version) FROM lending.loan_agreement x WHERE x.loan_id = NEW.loan_id)
           AND l.kind = 'INSTALMENT' AND l.status = 'ACTIVE';
        SELECT m.* INTO proposal FROM lending.loan_amendment m WHERE m.id = agreement.amendment_id;
        SELECT t.servicing_zone INTO zone FROM lending.loan_terms t WHERE t.terms_version_id = agreement.terms_version_id;
        IF agreement.id IS NULL OR proposal.id IS NULL OR started IS NULL
                OR NEW.schedule_version <> (SELECT max(x.schedule_version) FROM lending.repayment_schedule x
                                             WHERE x.loan_id = NEW.loan_id) + 1
                OR NEW.schedule_engine_version IS DISTINCT FROM agreement.schedule_engine_version
                OR NEW.currency <> agreement.currency OR NEW.scale <> agreement.scale
                OR NEW.intended_day <> agreement.repayment_day
                OR NEW.term_months <> proposal.remaining_instalments THEN
            RAISE EXCEPTION 'a later schedule version projects its loan''s latest, amended agreement version by the pinned engine over the amendment''s remaining instalments, as the next version (P11-TSK-027, ADR-0093)';
        END IF;
        -- PRINCIPAL CONSERVED ACROSS VERSIONS (INV-LND-03): exactly the principal outstanding the rows explain - agreement
        -- version 1's principal less the principal billed net of re-scheduling - never the original principal, never more.
        IF NEW.principal_minor <> lending.principal_not_yet_billed(NEW.loan_id) THEN
            RAISE EXCEPTION 'a later schedule version amortises exactly the principal outstanding not yet billed (P11-TSK-027, INV-LND-03)';
        END IF;
        -- From the open period's start: on or after the accrual start and every billed due date, never after today.
        IF NEW.start_date < started
                OR NEW.start_date > lending.business_date(statement_timestamp(), zone)
                OR EXISTS (SELECT 1 FROM lending.instalment_billing b
                            WHERE b.loan_id = NEW.loan_id AND b.due_date > NEW.start_date) THEN
            RAISE EXCEPTION 'a later schedule version starts at the open period''s start - after every billed due date, never after today (P11-TSK-027)';
        END IF;
    END IF;
    NEW.created_at := statement_timestamp();
    RETURN NEW;
END;
$$;

-- At commit, for every writer: a later schedule version continues the open period - its first due date after today and
-- after every billed one, and every accrual already posted on or after its start rounded within its first period - so the
-- interest accrued in the open period is billed by its first instalment, never stranded.
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
    IF first_due IS NULL OR first_due <= lending.business_date(transaction_timestamp(), zone)
            OR EXISTS (SELECT 1 FROM lending.instalment_billing b WHERE b.loan_id = NEW.loan_id AND b.due_date >= first_due)
            OR EXISTS (SELECT 1 FROM lending.interest_accrual a
                        WHERE a.loan_id = NEW.loan_id AND a.accrual_date >= NEW.start_date
                          AND (a.period_start <> NEW.start_date OR a.period_end <> first_due)) THEN
        RAISE EXCEPTION 'a later schedule version continues the open period: its first due date after today and every billed one, every accrual since its start in its first period (P11-TSK-027, INV-LND-03)';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER repayment_schedule_continues_its_period
    AFTER INSERT ON lending.repayment_schedule
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (NEW.schedule_version > 1)
    EXECUTE FUNCTION lending.repayment_schedule_continues_its_period();

-- ---------------------------------------------------------------------------------------------
-- V022's billing trigger, replaced: principal billed net of re-scheduling (P11-TSK-017's carry), and a billing names the
-- agreement version its schedule projects.
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
    -- (P11-TSK-027: after an amendment, version n+1's, so the new rate's schedule is billed under the new agreement).
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
    -- arrears re-scheduled are billed again under the new schedule, P11-TSK-027) never beyond its agreement's principal -
    -- version 1's, which no amendment raises (INV-LND-03).
    SELECT coalesce(sum(lending.billing_component_billed(b.id, 'PRINCIPAL')), 0) INTO billed_principal
      FROM lending.instalment_billing b
     WHERE b.loan_id = NEW.loan_id;
    IF billed_principal + NEW.principal_due_minor > (SELECT a.amount_minor FROM lending.loan_agreement a
                                                       WHERE a.loan_id = NEW.loan_id AND a.version = 1) THEN
        RAISE EXCEPTION 'the principal billed over a loan''s life never exceeds its principal (P11-TSK-017, INV-LND-03)';
    END IF;
    NEW.created_at := statement_timestamp();
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The grants: SELECT and INSERT only - never UPDATE, never DELETE.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON lending.loan_amendment TO finapp_app;
GRANT SELECT, INSERT ON lending.loan_amendment_decision TO finapp_app;
GRANT SELECT, INSERT ON lending.loan_amendment_rescheduling TO finapp_app;
