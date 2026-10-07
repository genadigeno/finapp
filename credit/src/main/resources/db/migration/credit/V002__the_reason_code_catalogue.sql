-- The reason-code catalogue (P10-TSK-001; ADR-0084 section 7, ADR-0086 section 2, INV-CRD-02).
--
-- A reason code is the explanation a triggered policy rule gives: the decision carries its reason
-- codes in rule order, and every adverse decision carries at least one. The customer is told the
-- adverse reasons' CUSTOMER TEXTS, in order - never a score, a threshold, an attribute or a
-- bureau's data - so no text below names a number.
--
-- CLOSED. The rows mirror the `ReasonCode` enum field for field, and the guards hold the two to
-- each other in both directions (ReasonCodeCatalogueTest against this file, CreditMigrationTest
-- against the live rows). A new code is a reviewed code change with its migration, never a string
-- (ADR-0084 section 6); the policy's rules reference these codes (P10-TSK-012).
--
-- IMMUTABLE. A decision replayed years later must name the same code with the same meaning, so a
-- seeded row is never updated or deleted - by any role: the application holds SELECT alone, and
-- the triggers below refuse UPDATE, DELETE and TRUNCATE for the owner too. A code the platform
-- stops using is retired by no longer being named by any active rule; its row stays, for replay.
--
-- `adverse`: whether the code explains a judgement of the applicant that went against them (a
-- decline, a referral, a reduction). CRD-AUTO-APPROVAL-CEILING is the one non-adverse code: it
-- caps an automated approval at the policy's automation limit and says nothing about the
-- applicant (PHASE_10_PLAN.md section 12.6). A policy's adverse effects must carry adverse codes
-- (validated at proposal, P10-TSK-012).

CREATE TABLE credit.reason_code (
    code          TEXT    PRIMARY KEY,
    category      TEXT    NOT NULL,
    customer_text TEXT    NOT NULL,
    adverse       BOOLEAN NOT NULL,

    CONSTRAINT reason_code_shape CHECK (code ~ '^CRD-[A-Z]+(-[A-Z]+)*$'),
    CONSTRAINT reason_code_category CHECK (category IN (
        'ELIGIBILITY', 'CREDIT_HISTORY', 'SCORE', 'AFFORDABILITY', 'EXPOSURE', 'DATA', 'RISK', 'POLICY')),
    CONSTRAINT reason_code_customer_text CHECK (
        btrim(customer_text) <> '' AND char_length(customer_text) <= 300)
);

COMMENT ON TABLE credit.reason_code IS
    'The closed, migration-seeded reason-code catalogue (P10-TSK-001, INV-CRD-02). Mirrors the ReasonCode enum both ways; never updated or deleted by any role; the application holds SELECT only.';

INSERT INTO credit.reason_code (code, category, customer_text, adverse) VALUES
    ('CRD-AGE-INELIGIBLE', 'ELIGIBILITY',
        'You do not meet the minimum age requirement for this product.', TRUE),
    ('CRD-RESIDENCY-INELIGIBLE', 'ELIGIBILITY',
        'This product is not available in your country of residence.', TRUE),
    ('CRD-INSOLVENCY', 'CREDIT_HISTORY',
        'Your credit report shows an insolvency proceeding.', TRUE),
    ('CRD-PRIOR-DEFAULT', 'CREDIT_HISTORY',
        'Your credit report shows a default on a previous credit agreement.', TRUE),
    ('CRD-RECENT-DELINQUENCY', 'CREDIT_HISTORY',
        'Your credit report shows recent late or missed payments.', TRUE),
    ('CRD-INSUFFICIENT-CREDIT-HISTORY', 'CREDIT_HISTORY',
        'Your credit report does not show enough credit history for us to assess this application.', TRUE),
    ('CRD-SCORE-INSUFFICIENT', 'SCORE',
        'Your overall credit assessment does not meet the requirements for this product.', TRUE),
    ('CRD-AFFORDABILITY-INSUFFICIENT', 'AFFORDABILITY',
        'Your income after expenses and existing commitments is not sufficient for the repayments.', TRUE),
    ('CRD-EXPOSURE-LIMIT', 'EXPOSURE',
        'The amount requested, together with your existing credit, exceeds the total credit we can offer you.', TRUE),
    ('CRD-INCOME-UNVERIFIED', 'DATA',
        'We could not verify your income.', TRUE),
    ('CRD-SOURCE-UNAVAILABLE', 'DATA',
        'We could not obtain the information we need to assess your application.', TRUE),
    ('CRD-CURRENCY-NOT-SUPPORTED', 'DATA',
        'Some of your financial information is in a currency we cannot assess for this product.', TRUE),
    ('CRD-RISK-REFERRAL', 'RISK',
        'Your application did not pass our internal checks.', TRUE),
    ('CRD-AUTO-APPROVAL-CEILING', 'POLICY',
        'The amount approved is the most we can approve automatically for this product.', FALSE);

-- The application reads the catalogue and nothing else. No INSERT, UPDATE, DELETE or TRUNCATE.
GRANT SELECT ON credit.reason_code TO finapp_app;

-- For EVERY role, the owner included: a grant can be widened by a later migration or by hand, and
-- the owner holds every privilege on the table it owns. The trigger is the control that holds
-- whatever the grants say (the evidence-table idiom, fx V002).
CREATE OR REPLACE FUNCTION credit.reason_code_is_immutable()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'a reason code is part of the decisions that cite it: it is never updated or deleted (P10-TSK-001, INV-CRD-02)';
END;
$$;

CREATE TRIGGER reason_code_is_immutable
    BEFORE UPDATE OR DELETE ON credit.reason_code
    FOR EACH ROW
    EXECUTE FUNCTION credit.reason_code_is_immutable();

-- TRUNCATE fires no row trigger; a statement trigger refuses it.
CREATE TRIGGER reason_code_is_never_truncated
    BEFORE TRUNCATE ON credit.reason_code
    FOR EACH STATEMENT
    EXECUTE FUNCTION credit.reason_code_is_immutable();
