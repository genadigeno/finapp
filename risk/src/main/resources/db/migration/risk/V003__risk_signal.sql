-- The born-once risk signal (P13-TSK-005; PHASE_13_PLAN.md sections 3.1, 8, 12.5; RISK_LIFECYCLES.md section 4.4).
--
-- A signal is an append-only fact about a subject, recorded by an inbox handler in its own transaction (T-sig) from
-- one of the platform's events - the attributes the event carried and nothing read from the producer's tables - and
-- born ONCE per source event: `UNIQUE (source_event_id)` beneath the inbox's dedupe is the arbiter the same event
-- delivered three times, delayed and reordered, on any number of instances meets (INV-RSK-06, INV-IDEM-04,
-- INV-EVT-04). Not a machine: no status, no edge, no UPDATE for any role and no DELETE for any role - the trigger
-- below refuses both for every writer, the migrator included, and the grant admits SELECT and INSERT only.
--
-- WHAT A SIGNAL IS ABOUT. `subject_kind` + `subject_ref`: an identity, an account, a device, a merchant, a
-- beneficiary, a session, or a credit agreement (a loan or a BNPL obligation) - referenced by identifier only, never
-- by constraint (ADR-0029): no foreign key leaves this schema. The reference is text so that ONE column of one shape
-- serves every subject: a canonical UUID for every kind but a device, and for a device identity's SHA-256 of its
-- description as 64 lowercase hex characters - the only shape the CHECK admits, so no writer can store a device
-- description, which has spaces and punctuation (OQ-4; ADR-0107 decision 9).
--
-- WHAT A SIGNAL CARRIES. Typed, bounded attribute columns - a closed code of the producer's vocabulary, an ISO
-- country or currency, a business date, a count of days, a flag, a version, a reference - and for each kind EXACTLY
-- its declared set, non-null, every other attribute null (`risk_signal_attributes_match_kind`, mirrored by
-- SignalKind's schema in the domain). There is no free-text column, no description and no amount: nothing in this
-- table is a balance (INV-RSK-02), and nothing in it could be a name (INV-AUD-02, INV-RSK-08).
--
-- TIME. `observed_at` is the source event's own instant - when the fact happened, as its producer stamped it - so a
-- delayed or reordered event records exactly its own fact and the readings order by it (INV-EVT-04). `recorded_at`
-- is the database's clock, stamped by the insert trigger whatever a writer passes (RISK_LIFECYCLES.md section 3).
--
-- READS. Every reading an evaluation takes - the latest, the first, the count within a lookback, whether the subject
-- was ever seen - is one range scan on `risk_signal_by_subject`.

CREATE TABLE risk.risk_signal (
    id                   uuid        NOT NULL,
    kind                 text        NOT NULL,
    subject_kind         text        NOT NULL,
    subject_ref          text        NOT NULL,
    source_event_id      uuid        NOT NULL,
    source_event_type    text        NOT NULL,
    source_event_version integer     NOT NULL,
    producer             text        NOT NULL,
    observed_at          timestamptz NOT NULL,
    recorded_at          timestamptz NOT NULL,
    correlation_id       text        NOT NULL,
    causation_id         text        NOT NULL,
    -- The attributes: each nullable, typed and bounded; which are set is the kind's.
    product              text,
    status               text,
    previous_status      text,
    bucket               text,
    previous_bucket      text,
    country              char(2),
    currency             char(3),
    business_date        date,
    days_past_due        integer,
    defaulted            boolean,
    agreement_version    integer,
    channel_ref          uuid,
    request_ref          uuid,

    CONSTRAINT risk_signal_pk PRIMARY KEY (id),
    -- Born once per source event: the arbiter beneath the inbox's dedupe (INV-RSK-06).
    CONSTRAINT risk_signal_born_once UNIQUE (source_event_id),
    -- The closed vocabularies, as SignalKind and SubjectKind declare them.
    CONSTRAINT risk_signal_kind_is_known CHECK (kind IN (
        'CREDENTIAL_CHANGED', 'RECOVERY_INITIATED', 'RECOVERY_COMPLETED', 'IDENTITY_SUSPENDED', 'ACCOUNT_OPENED',
        'BENEFICIARY_REGISTERED', 'BENEFICIARY_ACTIVATED', 'BENEFICIARY_BLOCKED', 'MERCHANT_ONBOARDED',
        'LOAN_DELINQUENCY_CHANGED', 'LOAN_DEFAULTED', 'BNPL_DELINQUENCY_CHANGED', 'BNPL_DEFAULTED')),
    CONSTRAINT risk_signal_subject_kind_is_known CHECK (subject_kind IN (
        'PARTY', 'ACCOUNT', 'DEVICE', 'MERCHANT', 'BENEFICIARY', 'SESSION', 'IDENTITY', 'CREDIT_AGREEMENT')),
    -- Each kind is about one subject kind.
    CONSTRAINT risk_signal_subject_matches_kind CHECK (subject_kind = CASE kind
        WHEN 'CREDENTIAL_CHANGED'       THEN 'IDENTITY'
        WHEN 'RECOVERY_INITIATED'       THEN 'IDENTITY'
        WHEN 'RECOVERY_COMPLETED'       THEN 'IDENTITY'
        WHEN 'IDENTITY_SUSPENDED'       THEN 'IDENTITY'
        WHEN 'ACCOUNT_OPENED'           THEN 'ACCOUNT'
        WHEN 'BENEFICIARY_REGISTERED'   THEN 'BENEFICIARY'
        WHEN 'BENEFICIARY_ACTIVATED'    THEN 'BENEFICIARY'
        WHEN 'BENEFICIARY_BLOCKED'      THEN 'BENEFICIARY'
        WHEN 'MERCHANT_ONBOARDED'       THEN 'MERCHANT'
        WHEN 'LOAN_DELINQUENCY_CHANGED' THEN 'CREDIT_AGREEMENT'
        WHEN 'LOAN_DEFAULTED'           THEN 'CREDIT_AGREEMENT'
        WHEN 'BNPL_DELINQUENCY_CHANGED' THEN 'CREDIT_AGREEMENT'
        WHEN 'BNPL_DEFAULTED'           THEN 'CREDIT_AGREEMENT'
        END),
    -- A device is its hash and nothing else; every other subject a canonical UUID (OQ-4).
    CONSTRAINT risk_signal_subject_ref_shaped CHECK (CASE subject_kind
        WHEN 'DEVICE' THEN subject_ref ~ '^[0-9a-f]{64}$'
        ELSE subject_ref ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        END),
    CONSTRAINT risk_signal_source_bounded CHECK (
        char_length(source_event_type) BETWEEN 1 AND 200
        AND source_event_version >= 1
        AND char_length(producer) BETWEEN 1 AND 64),
    CONSTRAINT risk_signal_correlation_bounded CHECK (
        char_length(correlation_id) BETWEEN 1 AND 200 AND char_length(causation_id) BETWEEN 1 AND 200),
    -- The attribute bounds: a code is an enumerated name, a country ISO-2, a currency ISO-3, days 0..36500, a
    -- version at least 1. Bounded, not enumerated: a producer's codes are the producer's.
    CONSTRAINT risk_signal_codes_shaped CHECK (
        (product         IS NULL OR product         ~ '^[A-Z][A-Z0-9_]{0,63}$')
        AND (status          IS NULL OR status          ~ '^[A-Z][A-Z0-9_]{0,63}$')
        AND (previous_status IS NULL OR previous_status ~ '^[A-Z][A-Z0-9_]{0,63}$')
        AND (bucket          IS NULL OR bucket          ~ '^[A-Z][A-Z0-9_]{0,63}$')
        AND (previous_bucket IS NULL OR previous_bucket ~ '^[A-Z][A-Z0-9_]{0,63}$')),
    CONSTRAINT risk_signal_country_shaped CHECK (country IS NULL OR country ~ '^[A-Z]{2}$'),
    CONSTRAINT risk_signal_currency_shaped CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$'),
    CONSTRAINT risk_signal_days_bounded CHECK (days_past_due IS NULL OR days_past_due BETWEEN 0 AND 36500),
    CONSTRAINT risk_signal_version_numbered CHECK (agreement_version IS NULL OR agreement_version >= 1),
    -- Each kind carries exactly its attributes: the named ones non-null, and nothing else - the count over every
    -- attribute column equals the kind's count. An unknown kind is refused above; ELSE false keeps this CHECK from
    -- passing on a NULL case result if that list ever drifts.
    CONSTRAINT risk_signal_attributes_match_kind CHECK (CASE kind
        WHEN 'CREDENTIAL_CHANGED' THEN
            num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                         business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref) = 0
        WHEN 'RECOVERY_INITIATED' THEN
            request_ref IS NOT NULL AND channel_ref IS NOT NULL
            AND num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                             business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref) = 2
        WHEN 'RECOVERY_COMPLETED' THEN
            request_ref IS NOT NULL AND channel_ref IS NOT NULL
            AND num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                             business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref) = 2
        WHEN 'IDENTITY_SUSPENDED' THEN
            status IS NOT NULL
            AND num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                             business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref) = 1
        WHEN 'ACCOUNT_OPENED' THEN
            product IS NOT NULL
            AND num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                             business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref) = 1
        WHEN 'BENEFICIARY_REGISTERED' THEN
            country IS NOT NULL AND currency IS NOT NULL
            AND num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                             business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref) = 2
        WHEN 'BENEFICIARY_ACTIVATED' THEN
            country IS NOT NULL AND currency IS NOT NULL AND previous_status IS NOT NULL
            AND num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                             business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref) = 3
        WHEN 'BENEFICIARY_BLOCKED' THEN
            country IS NOT NULL AND currency IS NOT NULL AND previous_status IS NOT NULL
            AND num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                             business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref) = 3
        WHEN 'MERCHANT_ONBOARDED' THEN
            currency IS NOT NULL
            AND num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                             business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref) = 1
        WHEN 'LOAN_DELINQUENCY_CHANGED' THEN
            product IS NOT NULL AND business_date IS NOT NULL AND days_past_due IS NOT NULL AND bucket IS NOT NULL
            AND previous_bucket IS NOT NULL AND defaulted IS NOT NULL AND agreement_version IS NOT NULL
            AND num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                             business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref) = 7
        WHEN 'LOAN_DEFAULTED' THEN
            product IS NOT NULL AND business_date IS NOT NULL AND days_past_due IS NOT NULL AND bucket IS NOT NULL
            AND defaulted IS NOT NULL AND agreement_version IS NOT NULL
            AND num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                             business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref) = 6
        WHEN 'BNPL_DELINQUENCY_CHANGED' THEN
            product IS NOT NULL AND business_date IS NOT NULL AND days_past_due IS NOT NULL AND bucket IS NOT NULL
            AND previous_bucket IS NOT NULL AND defaulted IS NOT NULL AND agreement_version IS NOT NULL
            AND num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                             business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref) = 7
        WHEN 'BNPL_DEFAULTED' THEN
            product IS NOT NULL AND business_date IS NOT NULL AND days_past_due IS NOT NULL AND bucket IS NOT NULL
            AND defaulted IS NOT NULL AND agreement_version IS NOT NULL
            AND num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                             business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref) = 6
        ELSE false
        END)
);

-- Every reading - the latest, the first, the count within a lookback, whether the subject was ever seen - is one
-- range scan here (SignalIntakeDatabaseTest#theReadingsIndexAnswersInOneScan).
CREATE INDEX risk_signal_by_subject ON risk.risk_signal (subject_kind, subject_ref, kind, observed_at DESC);

COMMENT ON TABLE risk.risk_signal IS
    'A born-once, append-only risk signal: a fact about a subject (identity, account, device by hash, merchant, beneficiary, credit agreement) recorded from one of the platform''s events by an inbox handler in its own transaction, with the typed and bounded attributes the event carried and nothing read from the producer''s tables. UNIQUE (source_event_id) is the arbiter (INV-RSK-06). No UPDATE and no DELETE for any role; no money, no free text, no device description - the hash only (OQ-4). P13-TSK-005.';
COMMENT ON COLUMN risk.risk_signal.subject_ref IS
    'A canonical UUID, or for a DEVICE identity''s SHA-256 of the device description as 64 lowercase hex - never the description (OQ-4; the CHECK admits nothing else).';
COMMENT ON COLUMN risk.risk_signal.observed_at IS
    'When the fact happened: the source event''s own instant, as its producer stamped it. A delayed event records late with its own instant (INV-EVT-04).';
COMMENT ON COLUMN risk.risk_signal.recorded_at IS
    'When this row was written: the database''s clock, stamped by the insert trigger whatever a writer passes.';

-- ---------------------------------------------------------------------------------------------
-- The triggers: the database's clock on every row, and no edit by any writer.
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION risk.risk_signal_is_born_once() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    -- The row's own stamp is the database's clock, not an instance's - whatever the writer passed (RISK_LIFECYCLES.md
    -- section 3). observed_at stays the event's: that one is the producer's fact, not this transaction's.
    NEW.recorded_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE FUNCTION risk.risk_signal_is_never_edited() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    -- For EVERY writer, the migrator included: a signal is evidence, born once and never corrected in place; a
    -- mistaken signal is answered by the rule that reads it, never by an edit (INV-RSK-06, INV-HIST-04).
    RAISE EXCEPTION 'a risk signal is never updated or deleted: it is born once and read as evidence (P13-TSK-005, INV-RSK-06)';
END;
$$;

CREATE TRIGGER risk_signal_is_born_once
    BEFORE INSERT ON risk.risk_signal
    FOR EACH ROW
    EXECUTE FUNCTION risk.risk_signal_is_born_once();

CREATE TRIGGER risk_signal_is_never_edited
    BEFORE UPDATE OR DELETE ON risk.risk_signal
    FOR EACH ROW
    EXECUTE FUNCTION risk.risk_signal_is_never_edited();

CREATE TRIGGER risk_signal_is_never_truncated
    BEFORE TRUNCATE ON risk.risk_signal
    FOR EACH STATEMENT
    EXECUTE FUNCTION risk.risk_signal_is_never_edited();

-- ---------------------------------------------------------------------------------------------
-- The grant: SELECT and INSERT, nothing else - the floor V001 reserved for exactly this review (no default privileges).
-- ---------------------------------------------------------------------------------------------

GRANT SELECT, INSERT ON risk.risk_signal TO finapp_app;
