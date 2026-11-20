-- TEST-ONLY (P17-TSK-113): a counterparty screening in review for the Operator Console's end-to-end tests, planted on a test database
-- by infra/scripts/e2e-seed.sh and by nothing else. Never a migration, never in an image (E2eSeedStaysTestOnlyTest).
--
-- The state no door reaches: a screening is requested only by a cross-border beneficiary's registration, which needs a corridor
-- provider, and answered only by the verification provider - no test stack configures either, so every screening there would be
-- UNAVAILABLE. So the row is written as CounterpartyScreenings leaves one the provider answered HIT: IN_REVIEW for a HIT, decided
-- AUTOMATIC under the platform's policy version (KycPolicyVersion.CURRENT, held equal by E2eSeedKycDatabaseTest), asked once and
-- that one attempt recorded, nothing due. Requested by :'requester', a customer the shell registered through the door, so the
-- console's reviewer - another person - may release or block it (CounterpartyScreenings refuses the requester; kyc V010 beneath).
--
-- What is not real, and why it is harmless: the subject. Its name is sealed under a key the database never holds, so the plant
-- seals nothing - random bytes of a sealed name's size. The review door never opens it: a release or block reads the status, the
-- requester and why it was reviewed. The reference begins e2e-seed-, never xb-beneficiary-, so the beneficiary listener that a
-- decision notifies ignores it (Beneficiaries), and nothing re-screens an IN_REVIEW screening. The attempt holds no evidence, as an
-- attempt may (counterparty_screening_attempt_evidence_is_whole).
--
-- Only kyc rows are written, and only new ones: a fresh id and a reference made from it. One statement, so psql and JDBC run the same
-- text. Prints the screening's identifier.
WITH screened AS (
    INSERT INTO kyc.counterparty_screening (
        id, request_reference, subject_ciphertext, subject_nonce, subject_key_version, country, entity_type, payee_verdict,
        status, review_reason, decision_basis, policy_version, decided_at, attempts, next_attempt_at, requested_at, requested_by)
    SELECT seed.id, 'e2e-seed-' || seed.id, uuid_send(gen_random_uuid()) || uuid_send(gen_random_uuid()),
           substring(uuid_send(gen_random_uuid()) FROM 1 FOR 12), 1, 'GB', 'INDIVIDUAL', 'MATCH',
           'IN_REVIEW', 'HIT', 'AUTOMATIC', 'kyc-2026-09', now(), 1, NULL, now(), :'requester'
      FROM (SELECT uuidv7() AS id) AS seed
    RETURNING id, decided_at
)
INSERT INTO kyc.counterparty_screening_attempt (screening_id, attempt, verdict, answered_at)
SELECT screened.id, 1, 'HIT', screened.decided_at
  FROM screened
RETURNING screening_id;
