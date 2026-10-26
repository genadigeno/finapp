-- The collections case opened on default (P13-TSK-025; owner decision C3; PHASE_13_PLAN.md sections 3.1, 12.8;
-- RISK_LIFECYCLES.md sections 4.5, 4.6; ADR-0110 decision 9; INV-RSK-06, INV-RSK-02). The entry said "persistence: none";
-- the build found V010's alert CHECKs refuse the alert a default calls for, so they are re-made here by name - V010 and
-- V012 are never edited (Flyway, outOfOrder=false).
--
-- 1. THE FACT'S ALERT
--   lending's LoanDefaulted and bnpl's BnplDefaulted are recorded by P13-TSK-005 as their born-once signals
--   (LOAN_DEFAULTED, BNPL_DEFAULTED - the obligation's kind is the signal's kind, its id the CREDIT_AGREEMENT subject).
--   Each raises ONE alert of the new kind OBLIGATION_DEFAULTED: a fact, neither a policy rule's finding nor an operational
--   signal. V010 coupled "the rule is the kind's own code" with "no policy" and "operational" (opens no case); a fact is
--   own-coded and policy-free like an operational kind but folds into a case like a rule's. So the four CHECKs that name
--   the operational pair are re-made over the OWN-CODE kinds (UNCLASSIFIED_TRANSACTION, VELOCITY_DIVERGENCE,
--   OBLIGATION_DEFAULTED), and the kind and rule code vocabularies gain OBLIGATION_DEFAULTED (AlertKind, AlertRule.codes()).
--   risk_alert_operational_never_attached and risk_alert_evidence_present still name the operational pair alone: a fact
--   attaches, and names its evidence. A new CHECK holds the fact's shape for every writer: about a CREDIT_AGREEMENT, on a
--   D1 window (the default's business date), its evidence exactly its one signal. The born-once unique is V010's,
--   untouched: (agreement, OBLIGATION_DEFAULTED, the day of the default) - one alert per default of an obligation.
--
-- 2. THE COLLECTIONS CASE
--   V012 reserved COLLECTIONS. Its rules, for every writer: about a PARTY (the defaulted agreement's party - one open per
--   (party, COLLECTIONS) by risk_case_one_open, so a second default of one party attaches); opened by its default's alert
--   alone (never by hand, never by an assessment); disposed CLOSED_NO_ACTION or REFERRED only - a default is a fact,
--   never a false positive, a fraud or a suspicion confirmed, and so never PROHIBITED (V012's own CHECK). No action kind
--   is admitted on it: V013's trigger already refuses every one (ActionRules). Nothing here writes money or another
--   schema's row.
--
-- Every row V010 and V012 admitted is admitted here: no alert of the new kind and no COLLECTIONS case exists before this
-- migration, so the widened CHECKs hold every row and the new ones hold vacuously - nothing to backfill. No table,
-- column, index, function, trigger or grant is created. The migration names no other schema.

-- ---------------------------------------------------------------------------------------------------------------------
-- 1. The fact's alert.
-- ---------------------------------------------------------------------------------------------------------------------

ALTER TABLE risk.risk_alert DROP CONSTRAINT risk_alert_kind_is_known;

ALTER TABLE risk.risk_alert ADD CONSTRAINT risk_alert_kind_is_known CHECK (kind IN ('FRAUD', 'ACCOUNT_TAKEOVER',
    'MERCHANT_ABUSE', 'AML', 'SCREENING_HIT', 'UNCLASSIFIED_TRANSACTION', 'VELOCITY_DIVERGENCE', 'OBLIGATION_DEFAULTED'));

ALTER TABLE risk.risk_alert DROP CONSTRAINT risk_alert_rule_code_is_known;

ALTER TABLE risk.risk_alert ADD CONSTRAINT risk_alert_rule_code_is_known CHECK (rule_code IN (
    'AMOUNT_BAND', 'VELOCITY_COUNT', 'VELOCITY_SUM', 'NEW_PAYEE', 'NEW_DEVICE', 'SESSION_ASSURANCE_BELOW',
    'RECENT_AUTH_FAILURES', 'RECENT_CREDENTIAL_CHANGE', 'RECENT_RECOVERY', 'ACCOUNT_AGE_BELOW', 'KYC_NOT_APPROVED',
    'SCREENING_HIT', 'PROFILE_RATING', 'MERCHANT_SUSPENDED', 'CORRIDOR_HIGH_RISK', 'DORMANT_THEN_ACTIVE',
    'RAPID_IN_OUT', 'STRUCTURING', 'ROUND_AMOUNT', 'REFUND_RATIO', 'CHARGEBACK_RATIO', 'BNPL_MULTI_APPLICATION',
    'UNCLASSIFIED_TRANSACTION', 'VELOCITY_DIVERGENCE', 'OBLIGATION_DEFAULTED'));

ALTER TABLE risk.risk_alert DROP CONSTRAINT risk_alert_rule_matches_kind;

-- An own-code kind's rule is its own code; a rule's alert names a rule kind (a SCREENING_HIT alert of the SCREENING_HIT
-- rule kind among them - the two vocabularies share that one name).
ALTER TABLE risk.risk_alert ADD CONSTRAINT risk_alert_rule_matches_kind CHECK (
    (kind IN ('UNCLASSIFIED_TRANSACTION', 'VELOCITY_DIVERGENCE', 'OBLIGATION_DEFAULTED'))
        = (rule_code IN ('UNCLASSIFIED_TRANSACTION', 'VELOCITY_DIVERGENCE', 'OBLIGATION_DEFAULTED'))
    AND (kind NOT IN ('UNCLASSIFIED_TRANSACTION', 'VELOCITY_DIVERGENCE', 'OBLIGATION_DEFAULTED') OR rule_code = kind));

ALTER TABLE risk.risk_alert DROP CONSTRAINT risk_alert_policy_named;

-- A rule's alert names the policy version whose rule raised it; an own-code kind's names none.
ALTER TABLE risk.risk_alert ADD CONSTRAINT risk_alert_policy_named CHECK (
    (kind IN ('UNCLASSIFIED_TRANSACTION', 'VELOCITY_DIVERGENCE', 'OBLIGATION_DEFAULTED')) = (policy_version_id IS NULL));

-- A default's alert: about the agreement, on the day of the default, naming exactly the signal that recorded it.
ALTER TABLE risk.risk_alert ADD CONSTRAINT risk_alert_default_names_its_agreement CHECK (
    kind <> 'OBLIGATION_DEFAULTED'
    OR (subject_kind = 'CREDIT_AGREEMENT' AND window_kind = 'D1'
        AND cardinality(evidence_signal_ids) = 1 AND cardinality(evidence_assessment_ids) = 0
        AND cardinality(evidence_journal_entry_ids) = 0));

COMMENT ON CONSTRAINT risk_alert_default_names_its_agreement ON risk.risk_alert IS
    'An OBLIGATION_DEFAULTED alert (P13-TSK-025) is about the CREDIT_AGREEMENT that defaulted, on the D1 window of its business date, its evidence exactly the one LOAN_DEFAULTED or BNPL_DEFAULTED signal that recorded the default - born once per default by risk_alert_born_once.';

-- ---------------------------------------------------------------------------------------------------------------------
-- 2. The collections case.
-- ---------------------------------------------------------------------------------------------------------------------

ALTER TABLE risk.risk_case ADD CONSTRAINT risk_case_collections_of_a_party CHECK (
    kind <> 'COLLECTIONS' OR subject_kind = 'PARTY');

ALTER TABLE risk.risk_case ADD CONSTRAINT risk_case_collections_opened_by_a_default CHECK (
    kind <> 'COLLECTIONS' OR (opening_alert_id IS NOT NULL AND opening_assessment_id IS NULL));

ALTER TABLE risk.risk_case ADD CONSTRAINT risk_case_collections_disposed_by_facts CHECK (
    kind <> 'COLLECTIONS' OR disposition IS NULL OR disposition IN ('CLOSED_NO_ACTION', 'REFERRED'));

COMMENT ON CONSTRAINT risk_case_collections_disposed_by_facts ON risk.risk_case IS
    'A COLLECTIONS case (P13-TSK-025, C3) holds facts only: its disposition slot - a standing proposal or the decision - is CLOSED_NO_ACTION (one person) or REFERRED (two persons: a referral to the owning domain''s own restructuring or write-off path, never an action). Never CLOSED_FALSE_POSITIVE or CONFIRMED_*: a default is a fact, not a finding.';
