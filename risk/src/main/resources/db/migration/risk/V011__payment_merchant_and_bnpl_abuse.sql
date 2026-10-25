-- Payment, merchant and BNPL abuse (P13-TSK-014; PHASE_13_PLAN.md sections 10, 12.2, 12.6; RISK_LIFECYCLES.md
-- sections 4.3, 4.4, 4.5) - the next free number. Four widenings, nothing narrowed, no row touched:
--
-- 1. THREE SIGNAL KINDS, consumed from events that exist (consumer risk.signals): REFUND_COMPLETED
--    (payments.RefundCompleted), CHARGEBACK_RECEIVED (payments.ChargebackReceived), BNPL_APPLICATION_SUBMITTED
--    (bnpl.BnplApplicationSubmitted). Each is about the PARTY - the payer, or the applicant. bnpl's event names its
--    party; payments' refund and dispute events name the refund or dispute and the payment intent, never the party or
--    the merchant (EventPayload, INV-AUD-02), so the handler attributes them from RISK'S OWN sealed assessment of that
--    intent (risk V007's risk_assessment: operation CARD_PAYMENT or WALLET_PAYMENT, command_ref = the intent id) -
--    nothing is read from payments' tables. The merchant the payment credited rides as merchant_subject_ref, the intent as
--    intent_ref: identifiers only, never an amount. The two new attribute columns join every kind's count in
--    risk_signal_attributes_match_kind, so no older kind may carry them.
--
-- 2. THE MERCHANT'S NUMERATOR INDEX: a merchant's refunds and chargebacks in a window are one range scan
--    (risk_signal_by_merchant), the party's and the BNPL applications ride risk_signal_by_subject (V003).
--
-- 3. THE SESSION VELOCITY DIMENSION: a session confirming many checkouts is counted per identity session - identity
--    holds no per-device identifier, so DEVICE could not count it. SESSION joins the window table's and the parameter
--    table's closed lists (its subject a canonical UUID by velocity_window_subject_ref_shape as it stands).
--
-- 4. THE ASSESSMENT NAMES THE SESSION: risk_assessment.session_id, nullable - the windows' evidence of record names
--    every subject a count was taken in (P13-TSK-004's AssessmentVelocityEvidence: every dimension provable).
--
-- Every constraint is re-created under its own name (the effective definition is this migration's from now on; V003
-- and V005 are merged and never edited). The tables' edit triggers are untouched: ADD COLUMN writes no row.
--
-- 0. THE GUARD (the gate's finding, V005's precedent): REFUND_RATIO and CHARGEBACK_RATIO gained a required parameter,
-- threshold_count, in the code. A policy's content is born once for every role (V002's risk_policy_content_is_born_once),
-- so a version stored before this migration with a ratio rule lacking it could never be read again - every T-eval of its
-- operation would pin nothing and answer the unpinned fail-safe. None exists outside a test container (owner decision
-- C5: no policy version has reached an environment); where one does, the migration refuses and a reviewed data
-- migration is owed - never a silent reinterpretation of a stored policy.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM risk.risk_rule stored_rule
               WHERE stored_rule.kind IN ('REFUND_RATIO', 'CHARGEBACK_RATIO')
                 AND NOT EXISTS (SELECT 1 FROM risk.risk_rule_parameter stored_parameter
                                 WHERE stored_parameter.policy_version_id = stored_rule.policy_version_id
                                   AND stored_parameter.rule_ordinal = stored_rule.ordinal
                                   AND stored_parameter.name = 'threshold_count')) THEN
        RAISE EXCEPTION 'risk V011 gives REFUND_RATIO and CHARGEBACK_RATIO a required threshold_count, and a stored ratio rule has none: policy content is born once for every role - this database needs a reviewed data migration (P13-TSK-014)';
    END IF;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- 1. The signal: two reference columns and three kinds.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE risk.risk_signal ADD COLUMN merchant_subject_ref uuid;

ALTER TABLE risk.risk_signal ADD COLUMN intent_ref uuid;

ALTER TABLE risk.risk_signal DROP CONSTRAINT risk_signal_kind_is_known;

ALTER TABLE risk.risk_signal DROP CONSTRAINT risk_signal_subject_matches_kind;

ALTER TABLE risk.risk_signal DROP CONSTRAINT risk_signal_attributes_match_kind;

-- SignalKind, in its declaration order.
ALTER TABLE risk.risk_signal ADD CONSTRAINT risk_signal_kind_is_known CHECK (kind IN (
    'CREDENTIAL_CHANGED', 'RECOVERY_INITIATED', 'RECOVERY_COMPLETED', 'IDENTITY_SUSPENDED', 'ACCOUNT_OPENED',
    'BENEFICIARY_REGISTERED', 'BENEFICIARY_ACTIVATED', 'BENEFICIARY_BLOCKED', 'MERCHANT_ONBOARDED',
    'LOAN_DELINQUENCY_CHANGED', 'LOAN_DEFAULTED', 'BNPL_DELINQUENCY_CHANGED', 'BNPL_DEFAULTED', 'REFUND_COMPLETED',
    'CHARGEBACK_RECEIVED', 'BNPL_APPLICATION_SUBMITTED'));

-- Each kind is about one subject kind.
ALTER TABLE risk.risk_signal ADD CONSTRAINT risk_signal_subject_matches_kind CHECK (subject_kind = CASE kind
    WHEN 'CREDENTIAL_CHANGED'         THEN 'IDENTITY'
    WHEN 'RECOVERY_INITIATED'         THEN 'IDENTITY'
    WHEN 'RECOVERY_COMPLETED'         THEN 'IDENTITY'
    WHEN 'IDENTITY_SUSPENDED'         THEN 'IDENTITY'
    WHEN 'ACCOUNT_OPENED'             THEN 'ACCOUNT'
    WHEN 'BENEFICIARY_REGISTERED'     THEN 'BENEFICIARY'
    WHEN 'BENEFICIARY_ACTIVATED'      THEN 'BENEFICIARY'
    WHEN 'BENEFICIARY_BLOCKED'        THEN 'BENEFICIARY'
    WHEN 'MERCHANT_ONBOARDED'         THEN 'MERCHANT'
    WHEN 'LOAN_DELINQUENCY_CHANGED'   THEN 'CREDIT_AGREEMENT'
    WHEN 'LOAN_DEFAULTED'             THEN 'CREDIT_AGREEMENT'
    WHEN 'BNPL_DELINQUENCY_CHANGED'   THEN 'CREDIT_AGREEMENT'
    WHEN 'BNPL_DEFAULTED'             THEN 'CREDIT_AGREEMENT'
    WHEN 'REFUND_COMPLETED'           THEN 'PARTY'
    WHEN 'CHARGEBACK_RECEIVED'        THEN 'PARTY'
    WHEN 'BNPL_APPLICATION_SUBMITTED' THEN 'PARTY'
    END);

-- Each kind carries exactly its attributes: the named ones non-null, and nothing else - the count over every attribute
-- column (now fifteen) equals the kind's count. ELSE false keeps the CHECK from passing on a NULL case result.
ALTER TABLE risk.risk_signal ADD CONSTRAINT risk_signal_attributes_match_kind CHECK (CASE kind
    WHEN 'CREDENTIAL_CHANGED' THEN
        num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                     business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref,
                     merchant_subject_ref, intent_ref) = 0
    WHEN 'RECOVERY_INITIATED' THEN
        request_ref IS NOT NULL AND channel_ref IS NOT NULL
        AND num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                         business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref,
                         merchant_subject_ref, intent_ref) = 2
    WHEN 'RECOVERY_COMPLETED' THEN
        request_ref IS NOT NULL AND channel_ref IS NOT NULL
        AND num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                         business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref,
                         merchant_subject_ref, intent_ref) = 2
    WHEN 'IDENTITY_SUSPENDED' THEN
        status IS NOT NULL
        AND num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                         business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref,
                         merchant_subject_ref, intent_ref) = 1
    WHEN 'ACCOUNT_OPENED' THEN
        product IS NOT NULL
        AND num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                         business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref,
                         merchant_subject_ref, intent_ref) = 1
    WHEN 'BENEFICIARY_REGISTERED' THEN
        country IS NOT NULL AND currency IS NOT NULL
        AND num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                         business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref,
                         merchant_subject_ref, intent_ref) = 2
    WHEN 'BENEFICIARY_ACTIVATED' THEN
        country IS NOT NULL AND currency IS NOT NULL AND previous_status IS NOT NULL
        AND num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                         business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref,
                         merchant_subject_ref, intent_ref) = 3
    WHEN 'BENEFICIARY_BLOCKED' THEN
        country IS NOT NULL AND currency IS NOT NULL AND previous_status IS NOT NULL
        AND num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                         business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref,
                         merchant_subject_ref, intent_ref) = 3
    WHEN 'MERCHANT_ONBOARDED' THEN
        currency IS NOT NULL
        AND num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                         business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref,
                         merchant_subject_ref, intent_ref) = 1
    WHEN 'LOAN_DELINQUENCY_CHANGED' THEN
        product IS NOT NULL AND business_date IS NOT NULL AND days_past_due IS NOT NULL AND bucket IS NOT NULL
        AND previous_bucket IS NOT NULL AND defaulted IS NOT NULL AND agreement_version IS NOT NULL
        AND num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                         business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref,
                         merchant_subject_ref, intent_ref) = 7
    WHEN 'LOAN_DEFAULTED' THEN
        product IS NOT NULL AND business_date IS NOT NULL AND days_past_due IS NOT NULL AND bucket IS NOT NULL
        AND defaulted IS NOT NULL AND agreement_version IS NOT NULL
        AND num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                         business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref,
                         merchant_subject_ref, intent_ref) = 6
    WHEN 'BNPL_DELINQUENCY_CHANGED' THEN
        product IS NOT NULL AND business_date IS NOT NULL AND days_past_due IS NOT NULL AND bucket IS NOT NULL
        AND previous_bucket IS NOT NULL AND defaulted IS NOT NULL AND agreement_version IS NOT NULL
        AND num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                         business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref,
                         merchant_subject_ref, intent_ref) = 7
    WHEN 'BNPL_DEFAULTED' THEN
        product IS NOT NULL AND business_date IS NOT NULL AND days_past_due IS NOT NULL AND bucket IS NOT NULL
        AND defaulted IS NOT NULL AND agreement_version IS NOT NULL
        AND num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                         business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref,
                         merchant_subject_ref, intent_ref) = 6
    WHEN 'REFUND_COMPLETED' THEN
        merchant_subject_ref IS NOT NULL AND intent_ref IS NOT NULL
        AND num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                         business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref,
                         merchant_subject_ref, intent_ref) = 2
    WHEN 'CHARGEBACK_RECEIVED' THEN
        merchant_subject_ref IS NOT NULL AND intent_ref IS NOT NULL
        AND num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                         business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref,
                         merchant_subject_ref, intent_ref) = 2
    WHEN 'BNPL_APPLICATION_SUBMITTED' THEN
        product IS NOT NULL
        AND num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                         business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref,
                         merchant_subject_ref, intent_ref) = 1
    ELSE false
    END);

-- The merchant's refunds and chargebacks in a window - T-eval's numerator, under the 250 ms budget - one range scan.
CREATE INDEX risk_signal_by_merchant ON risk.risk_signal (merchant_subject_ref, kind, observed_at DESC)
    WHERE merchant_subject_ref IS NOT NULL;

COMMENT ON COLUMN risk.risk_signal.merchant_subject_ref IS
    'The merchant a refunded or charged-back payment credited, as risk''s own sealed assessment of that payment judged it (P13-TSK-014) - an identifier, never an amount; NULL for every other kind. The merchant as a judged subject, never a tenant scope - so named apart from the tenant column (risk_assessment.merchant_subject_id''s rule).';
COMMENT ON COLUMN risk.risk_signal.intent_ref IS
    'The payment intent a refund or chargeback was of, as the event named it (P13-TSK-014) - an identifier; NULL for every other kind.';

-- ---------------------------------------------------------------------------------------------
-- 2. The SESSION dimension, on the windows and on the policy's parameters.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE risk.velocity_window DROP CONSTRAINT velocity_window_dimension_is_known;

-- VelocityDimension, in its declaration order (the lock and upsert order).
ALTER TABLE risk.velocity_window ADD CONSTRAINT velocity_window_dimension_is_known
    CHECK (dimension IN ('PARTY', 'SOURCE_ACCOUNT', 'DESTINATION', 'DEVICE', 'MERCHANT', 'SESSION'));

ALTER TABLE risk.risk_rule_parameter DROP CONSTRAINT risk_rule_parameter_value_typed;

-- Exactly the column of the type holds the value, within the type's bounds; every other column NULL - V005's rule with
-- SESSION in the velocity dimension's closed list.
ALTER TABLE risk.risk_rule_parameter ADD CONSTRAINT risk_rule_parameter_value_typed CHECK (
    (type = 'COUNT') = (value_count IS NOT NULL)
    AND (type = 'MINOR_AMOUNT') = (value_minor IS NOT NULL)
    AND (type = 'BASIS_POINTS') = (value_bps IS NOT NULL)
    AND (type = 'DAYS') = (value_days IS NOT NULL)
    AND (type = 'HOURS') = (value_hours IS NOT NULL)
    AND (type = 'WINDOW') = (value_window IS NOT NULL)
    AND (type = 'VELOCITY_DIMENSION') = (value_velocity_dimension IS NOT NULL)
    AND (type = 'CORRIDOR_SET') = (value_corridors IS NOT NULL)
    AND (type = 'ASSURANCE') = (value_assurance IS NOT NULL)
    AND (type = 'RATING') = (value_rating IS NOT NULL)
    AND (value_count IS NULL OR value_count >= 0)
    AND (value_minor IS NULL OR value_minor >= 0)
    AND (value_bps IS NULL OR value_bps BETWEEN 0 AND 10000)
    AND (value_days IS NULL OR value_days >= 1)
    AND (value_hours IS NULL OR value_hours >= 1)
    AND (value_window IS NULL OR value_window IN ('H1', 'D1', 'D7', 'D30'))
    AND (value_velocity_dimension IS NULL
        OR value_velocity_dimension IN ('PARTY', 'SOURCE_ACCOUNT', 'DESTINATION', 'DEVICE', 'MERCHANT', 'SESSION'))
    AND (value_corridors IS NULL OR cardinality(value_corridors) >= 1)
    AND (value_assurance IS NULL OR value_assurance IN ('SINGLE_FACTOR', 'MULTI_FACTOR'))
    AND (value_rating IS NULL OR value_rating IN ('LOW', 'MEDIUM', 'HIGH', 'PROHIBITED'))
);

-- ---------------------------------------------------------------------------------------------
-- 3. The assessment names the session its count was taken in.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE risk.risk_assessment ADD COLUMN session_id uuid;

COMMENT ON COLUMN risk.risk_assessment.session_id IS
    'The identity session that confirmed the judged movement - the SESSION velocity window''s subject (P13-TSK-014); an identifier, never a token; NULL where none acted (a system flow) or the seam carries none.';
