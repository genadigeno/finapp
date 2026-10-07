-- The credit data purposes (P10-TSK-002; ADR-0085 section 4, INV-CRD-03, ADR-0037).
--
-- Phase 10 retrieves a person's credit data from two kinds of source, and each needs its own
-- recorded lawful basis: a credit bureau's report and a financial-data provider's account and
-- transaction data are different processings, so a basis for one must never admit the other.
-- Two purposes, then - CREDIT_BUREAU_ACCESS and FINANCIAL_DATA_ACCESS - and deliberately no
-- combined "credit" purpose. The ConsentPurpose javadoc reserved a single Phase 10 member; it
-- arrives as two, and says so.
--
-- THE PURPOSE CHECKS ARE GENERATED - ConsentPurpose.sqlValueList(). An applied migration is
--   history that cannot be edited (ADR-0011), so the wider set is a new definition here, the
--   identity V013 / ledger V011 shape: ConsentMigrationTest reads the NEWEST consent migration
--   defining each constraint and fails the build if it and the enum disagree, and pins V002's
--   original definitions as untouched history. Widening admits every existing row, so nothing
--   already recorded is re-judged.
--
-- PURPOSES ARE NEVER REMOVED (ADR-0037): a purpose that stops being used still names the basis
--   of history rows that must stay interpretable (INV-CNS-02). Both purposes from V002 stay.

ALTER TABLE consent.consent_text
    DROP CONSTRAINT consent_text_purpose_is_known,
    ADD CONSTRAINT consent_text_purpose_is_known
        CHECK (purpose IN ('KYC_PROCESSING', 'SCREENING', 'CREDIT_BUREAU_ACCESS', 'FINANCIAL_DATA_ACCESS'));

ALTER TABLE consent.consent_record
    DROP CONSTRAINT consent_record_purpose_is_known,
    ADD CONSTRAINT consent_record_purpose_is_known
        CHECK (purpose IN ('KYC_PROCESSING', 'SCREENING', 'CREDIT_BUREAU_ACCESS', 'FINANCIAL_DATA_ACCESS'));

-- The first version of each new purpose's text, jurisdiction-neutral (PRODUCT_VISION.md), the
-- V002 shape: requires_reconsent = false because there is no earlier version for it to lapse. A
-- later wording is a NEW version in a later migration, and a grant names the version granted
-- (INV-CNS-04). The texts name what is retrieved, from whom and for what - and that the data
-- serves the credit decision alone.
INSERT INTO consent.consent_text (purpose, version, body, requires_reconsent, published_at)
VALUES
    ('CREDIT_BUREAU_ACCESS', 1,
     'I consent to the platform requesting my credit report from a credit reference agency, '
     'and to that report being used to assess my application for credit.',
     false, TIMESTAMPTZ '2026-10-07 00:00:00+00'),
    ('FINANCIAL_DATA_ACCESS', 1,
     'I consent to the platform retrieving my account and transaction data from a financial '
     'data provider, and to that data being used to assess whether credit I apply for is '
     'affordable for me.',
     false, TIMESTAMPTZ '2026-10-07 00:00:00+00');
