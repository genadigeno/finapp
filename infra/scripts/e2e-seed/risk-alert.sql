-- TEST-ONLY (P17-TSK-110). An OPEN alert as the alert machine raises one (risk V010's born-once row): ACCOUNT_TAKEOVER on a
-- fresh party, rule RECENT_CREDENTIAL_CHANGE, today's D1 window, naming the version in force - the seed's own ACTIVE TRANSFER
-- version, handed in as psql's :'version' (quoted by psql) and refused unless it is one. Its evidence one assessment id that
-- names no row: the console's acceptances - two persons suppress it, or a case is opened on it - never resolve it. No event is
-- written: nothing consumes an alert's raising. Applied by infra/scripts/e2e-seed.sh; proven unchanged by app's
-- E2eSeedRiskDatabaseTest, through the suppression doors and the case's. Prints the alert's id and its party, '|'-separated.
INSERT INTO risk.risk_alert (id, kind, severity, subject_kind, subject_ref, rule_code, window_kind, window_start,
    policy_version_id, evidence_signal_ids, evidence_assessment_ids, evidence_journal_entry_ids, status, raised_at,
    correlation_id, causation_id)
SELECT uuidv7(), 'ACCOUNT_TAKEOVER', 'HIGH', 'PARTY', seed.party::text, 'RECENT_CREDENTIAL_CHANGE', 'D1',
       date_bin(INTERVAL '1 day', now(), TIMESTAMPTZ '1970-01-05 00:00:00+00'), version.id, '{}', ARRAY[uuidv7()], '{}', 'OPEN',
       now(), 'e2e-seed-' || gen_random_uuid(), 'e2e-seed'
  FROM (SELECT uuidv7() AS party) AS seed
  JOIN risk.risk_policy_version version ON version.id = :'version' AND version.status = 'ACTIVE'
RETURNING id, subject_ref;
