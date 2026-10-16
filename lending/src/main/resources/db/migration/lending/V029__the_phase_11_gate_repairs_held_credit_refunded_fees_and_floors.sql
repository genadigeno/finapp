-- The Phase 11 completion gate's repairs, lending's first half (repair-1; the gate's findings R2, R3, A1-F4 and A3-M1;
-- PHASE_11_PLAN.md section 12.8 rules 6, 8 and 8a, ADR-0095 point 5, ADR-0099 point 3; INV-LND-01, INV-LND-04,
-- INV-REV-02, INV-HIST-01, INV-LND-09). The second half is V030 (repair-2). Flyway never edits an applied migration: this
-- replaces two functions, alters two tables and adds two triggers; it amends nothing.
--
--   R2 - money already held settles what is ALREADY due, not only what becomes due. Rule 6 was applied at a billed item's
--        birth only (credit_balance_application UNIQUE (billing_id)), so credit that arrived while an item was past due -
--        a fee refunded (rule 8a), a repayment reversed (rule 10) re-opening an item beside other credit, the
--        post-maturity loan whose principal is paid and whose interest is due - stayed held while DPD, late fees and
--        default accrued on the arrears it covered, and a matured loan with no new billing could never close. The servicing
--        step now applies rule 6 over the due state whenever credit is held and something is due - after billing, before
--        the collection, the condition and the late fee - and the payoff applies it before its recomputation (A4-M1: the
--        wallet was debited the gross receivable while the credit balance was refunded beside it). Such an application is
--        born at no billed item: a CATCH-UP application - billing_id NULL, catch_up_sequence its place among the loan's
--        catch-up applications (1, 2, ...), each born under L3 at the next number (the trigger below, UNIQUE (loan_id,
--        catch_up_sequence) beneath it), its ledger key lending.credit-application:<loan>:catch-up:<n>. The per-billing
--        application is unchanged; both are held at commit by V012's conservation (the rows sum to the application, no
--        billed component paid beyond what it billed), unchanged.
--
--   R3 - a refunded fee is never forgiven a second time. A fee paid by a repayment, refunded (rule 8a: DR LOAN_FEE_INCOME /
--        CR LOAN_CREDIT_BALANCE), then the repayment reversed: the fee is due again on its item, and V025's bound for a FEE
--        waiver (billed - paid) admitted waiving it - LOAN_FEE_INCOME negative for the loan, the borrower holding the refund
--        as credit too. The FEE arm is now least(billed - paid, assessed - fee waived - refunded) for its assessment: what
--        was refunded was already forgiven. (The fee due again is then settled by the refund held - R2's catch-up
--        application - so the loan's books end where a waiver alone would have left them.)
--
--   A1-F4 - lending.capital_contribution and lending.capital_contribution_event (V002) refuse TRUNCATE, as every other
--        lending fact table does: a row-level trigger never fires on TRUNCATE, so INSERT-only was a promise one statement
--        could break for the owning role.
--
--   A3-M1 - a terms version names an engine version this build holds: every engine column = 1 (V003 admitted any >= 1, so a
--        raw writer could pin a version 2 no code computes - EngineVersion.of refuses it only when it is read).
--
-- Replaced, each its latest predecessor plus only the change named (migrator-owned, schema-qualified, invoker's rights - no
-- search_path set): lending.credit_balance_application_is_born_once (V017's) and lending.waiver_decision_is_born_once
-- (V025's). New: lending.capital_contribution_is_never_truncated.

-- ---------------------------------------------------------------------------------------------
-- R2: the catch-up application.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE lending.credit_balance_application ALTER COLUMN billing_id DROP NOT NULL;
ALTER TABLE lending.credit_balance_application ADD COLUMN catch_up_sequence integer;
ALTER TABLE lending.credit_balance_application ADD CONSTRAINT credit_balance_application_occasion CHECK (
    (billing_id IS NULL) = (catch_up_sequence IS NOT NULL) AND (catch_up_sequence IS NULL OR catch_up_sequence >= 1));
-- Born once per number per loan: two writers of one loan's next catch-up application cannot both commit (beneath L3 and
-- the ledger key lending.credit-application:<loan>:catch-up:<n>).
ALTER TABLE lending.credit_balance_application ADD CONSTRAINT credit_balance_application_catch_up_unique
    UNIQUE (loan_id, catch_up_sequence);

COMMENT ON COLUMN lending.credit_balance_application.billing_id IS
    'The billed item (a billing or a statement) at whose birth the credit balance was applied - at most one application each (UNIQUE (billing_id)); NULL for a catch-up application (V029).';
COMMENT ON COLUMN lending.credit_balance_application.catch_up_sequence IS
    'A catch-up application''s place among its loan''s (Phase 11 gate R2, V029): credit held applied over the items already due - at a servicing step, before the collection, the condition and the late fee, or at a payoff before its recomputation - not at a billed item''s birth. Born under L3 at one more than the loan''s latest, UNIQUE (loan_id, catch_up_sequence); its ledger key lending.credit-application:<loan>:catch-up:<n>. NULL at a billed item.';
COMMENT ON TABLE lending.credit_balance_application IS
    'Money already held applied (P11-TSK-017; ADR-0095 point 5, PHASE_11_PLAN.md section 12.8 rule 6): what of the loan''s LOAN_CREDIT_BALANCE ALLOCATION_ENGINE_V1 applied over the billed items, oldest first - at a billed item''s birth (billing_id, at most one per item, key lending.credit-application:<item>) or, credit held while something is already due, as a catch-up application (catch_up_sequence, V029, key lending.credit-application:<loan>:catch-up:<n>) - the amount (its credit_balance_allocation rows sum to it at commit), the agreement and allocation engine versions, and posting rule 6''s journal entry by value (DR LOAN_CREDIT_BALANCE / CR <due accounts>). INSERT-only for every role. RESTRICTED-FINANCIAL.';

-- ---------------------------------------------------------------------------------------------
-- R2: the application's birth, replaced - V017's, plus the catch-up arm.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION lending.credit_balance_application_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    account lending.loan%ROWTYPE;
    agreement lending.loan_agreement%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a credit-balance application is immutable from insert, for every role (P11-TSK-017, INV-LND-01)';
    END IF;
    -- V029: (L3) the account, for every writer - a catch-up application's number is judged under the lock every payer of
    -- the loan holds (the platform's writer holds it already; re-taking it is free).
    SELECT l.* INTO account FROM lending.loan l WHERE l.id = NEW.loan_id FOR UPDATE;
    IF account.id IS NULL OR account.status NOT IN ('ACTIVE', 'CLOSING') THEN
        RAISE EXCEPTION 'a credit balance is applied only on an ACTIVE or CLOSING account (P11-TSK-017)';
    END IF;
    IF NEW.billing_id IS NOT NULL THEN
        -- At its own loan's billed item, in its transaction's servicing step: the billing or the statement is the loan's.
        IF lending.billed_item_loan(NEW.billing_id) IS DISTINCT FROM NEW.loan_id THEN
            RAISE EXCEPTION 'a credit-balance application is born at its own loan''s billing or statement (P11-TSK-017, P11-TSK-022)';
        END IF;
    ELSIF NEW.catch_up_sequence IS DISTINCT FROM 1 + coalesce(
            (SELECT max(c.catch_up_sequence) FROM lending.credit_balance_application c WHERE c.loan_id = NEW.loan_id), 0) THEN
        -- V029: a catch-up application is the loan's next - numbered in order, never a gap, never a second at one number.
        RAISE EXCEPTION 'a catch-up credit-balance application is its loan''s next (Phase 11 gate R2, INV-LND-04)';
    END IF;
    SELECT a.* INTO agreement FROM lending.loan_agreement a
     WHERE a.id = NEW.agreement_id AND a.loan_id = NEW.loan_id AND a.version = NEW.agreement_version;
    IF agreement.id IS NULL OR NEW.currency <> agreement.currency OR NEW.scale <> agreement.scale
            OR NEW.allocation_engine_version <> agreement.allocation_engine_version THEN
        RAISE EXCEPTION 'an application names its account''s agreement version, its currency and scale and its pinned allocation engine (P11-TSK-017, INV-LND-09)';
    END IF;
    NEW.created_at := statement_timestamp();
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- R3: the waiver's decision, replaced - V025's, plus the FEE arm's second bound.
-- ---------------------------------------------------------------------------------------------
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
            -- V029 (R3): and by what of the fee was never forgiven - its assessment less what was waived or refunded of it.
            -- A refund forgave the fee already; a reversal re-opening it on the item does not make it forgivable twice.
            room := least(
                lending.billing_component_billed(proposal.due_item_id, 'FEES')
                    - lending.billing_component_paid(proposal.due_item_id, 'FEES'),
                (SELECT f.amount_minor FROM lending.fee_assessment f WHERE f.id = proposal.fee_assessment_id)
                    - (SELECT coalesce(sum(w.amount_minor), 0) FROM lending.waiver w
                         JOIN lending.waiver_decision d ON d.waiver_id = w.id
                        WHERE w.fee_assessment_id = proposal.fee_assessment_id AND w.kind IN ('FEE', 'FEE_REFUND')
                          AND d.outcome = 'APPROVED'));
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
    -- P11-TSK-029: the decision's place in the loan's history and the instant it is written, both after L3 (taken above,
    -- for every writer) - never the transaction's start, which precedes the wait for the lock.
    NEW.recorded_order := nextval('lending.record_order');
    NEW.decided_at := clock_timestamp();
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- A1-F4: the capital contribution and its history are never truncated.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION lending.capital_contribution_is_never_truncated()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'the lending capital contributions and their history are never truncated (P11-TSK-004, INV-HIST-01)';
END;
$$;

CREATE TRIGGER capital_contribution_is_never_truncated
    BEFORE TRUNCATE ON lending.capital_contribution
    FOR EACH STATEMENT
    EXECUTE FUNCTION lending.capital_contribution_is_never_truncated();

CREATE TRIGGER capital_contribution_event_is_never_truncated
    BEFORE TRUNCATE ON lending.capital_contribution_event
    FOR EACH STATEMENT
    EXECUTE FUNCTION lending.capital_contribution_is_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- A3-M1: a terms version names only the engine versions this build holds.
-- ---------------------------------------------------------------------------------------------
-- A version 2 of any engine is a new engine AND a migration widening this, beside the code that computes it (INV-LND-09).
ALTER TABLE lending.loan_terms DROP CONSTRAINT loan_terms_engines_numbered;
ALTER TABLE lending.loan_terms ADD CONSTRAINT loan_terms_engines_numbered CHECK (
    accrual_engine_version = 1 AND allocation_engine_version = 1
    AND (schedule_engine_version IS NULL OR schedule_engine_version = 1)
    AND (statement_engine_version IS NULL OR statement_engine_version = 1));
