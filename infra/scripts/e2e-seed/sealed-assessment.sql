-- TEST-ONLY (P17-TSK-110). A sealed REVIEW assessment as T-eval leaves one (risk V007's four rows: the head, its sealed inputs,
-- its rule hit, its reason), so the console's two officers can override it. A transfer of 20,000,000.00 EUR from a fresh party's
-- account, judged under the seed's own ACTIVE TRANSFER version - handed in as psql's :'version' (quoted by psql) and refused
-- unless it is one - whose AMOUNT_BAND rule (weight 600, above 10,000,000.00) fires: score 600, REVIEW, the velocity not
-- permitted. The inputs are the canonical form T-eval renders for that request with no readings gathered (CanonicalEvaluation
-- format 1), the party and the account substituted, their digest recomputed by the table's own CHECK; the verdict's digest is
-- the engine's for that version and amount, a constant because the verdict names no party. Locally no door reaches a judged
-- movement: it needs a funded, KYC-approved customer, which only the database makes on a test stack (P17-TSK-106's gap). No
-- transfer exists for the command, so the override's RESUBMISSION, once approved, finds nothing to resubmit, and the risk
-- decisions report's census counts an assessment of no command - on a test stack, by design. Applied by
-- infra/scripts/e2e-seed.sh; proven unchanged by app's E2eSeedRiskDatabaseTest: the seal holds, a replay is IDENTICAL to the
-- engine, and two officers override it. Prints the assessment's id.
WITH seed AS (
    SELECT uuidv7() AS id, uuidv7() AS party, uuidv7() AS source, uuidv7() AS destination,
           'e2e-seed-' || replace(gen_random_uuid()::text, '-', '') AS command, version.id AS version_id,
           version.version AS version_number
      FROM risk.risk_policy_version version
     WHERE version.id = :'version' AND version.operation = 'TRANSFER' AND version.status = 'ACTIVE'
), sealed AS (
    SELECT seed.*,
           '{"format":"1","kind":"inputs","operation":"TRANSFER","subjects":[{"kind":"PARTY","ref":"' || seed.party
               || '"},{"kind":"ACCOUNT","ref":"' || seed.source || '"}],"amount":{"minor":"2000000000","currency":"EUR",'
               || '"scale":"2"},"velocity":[],"payeeFirstSeen":{"absent":"true"},"deviceFirstSeen":{"absent":"true"},'
               || '"sessionAssurance":{"absent":"true"},"authFailures":[],"credentialChange":{"absent":"true"},'
               || '"recovery":{"absent":"true"},"accountAgeDays":{"absent":"true"},"kycApproved":{"absent":"true"},'
               || '"screeningHit":{"absent":"true"},"profileRating":{"absent":"true"},"merchantSuspended":{"absent":"true"},'
               || '"corridor":{"absent":"true"},"monitoring":{"dormantDays":{"absent":"true"},"flows":[],"amounts":[],'
               || '"refunds":[],"chargebacks":[],"bnplApplications":[]}}' AS canonical
      FROM seed
), head AS (
    INSERT INTO risk.risk_assessment (id, operation, command_ref, party_id, source_account_id, destination_kind, destination_id,
        amount_minor, currency, scale, policy_version_id, policy_operation, policy_version, engine, score, decision,
        velocity_outcome, canonical_format, inputs_sha256, verdict_sha256, latency_micros, correlation_id, causation_id,
        assessed_at)
    SELECT id, 'TRANSFER', command, party, source, 'ACCOUNT', destination, 2000000000, 'EUR', 2, version_id, 'TRANSFER',
           version_number, 'RISK_ENGINE_V1', 600, 'REVIEW', 'NOT_PERMITTED', 1,
           encode(sha256(convert_to(canonical, 'UTF8')), 'hex'),
           'd669fd58cad61580a2ad137ae19f73d880c9996fe45d75cb2307a7368e773e46', 1000, command, 'e2e-seed', now()
      FROM sealed
    RETURNING id
), inputs AS (
    INSERT INTO risk.risk_assessment_input (assessment_id, inputs_canonical, inputs_sha256)
    SELECT id, canonical, encode(sha256(convert_to(canonical, 'UTF8')), 'hex') FROM sealed
), hit AS (
    INSERT INTO risk.risk_rule_hit (assessment_id, ordinal, kind, outcome, contribution, hard_decision, judged)
    SELECT id, 1, 'AMOUNT_BAND', 'FIRED', 600, NULL, 'amount=2000000000' FROM sealed
), reason AS (
    INSERT INTO risk.risk_reason (assessment_id, position, code)
    SELECT id, 1, 'AMOUNT_BAND' FROM sealed
)
SELECT id FROM head;
