-- TEST-ONLY (P17-TSK-113): a KYC case in review for the Operator Console's end-to-end tests, planted on a test database by
-- infra/scripts/e2e-seed.sh and by nothing else. Never a migration, never in an image (E2eSeedStaysTestOnlyTest).
--
-- The state no door reaches: a case's checks are run by VerificationRunService, which no production path calls - a case is asked
-- of the verification provider by tests and the kind rehearsal's experiments only - and no test stack configures that provider
-- (finapp.kyc.provider.url, blank everywhere but the test overlay). So the case is written as CaseAssessment leaves one it found
-- BLOCKED, in its one transaction: the case IN_REVIEW, its sanctions check answered HIT, and that check's OPEN review task
-- (INV-KYC-04: the state is never vacuously open). The customer is the live PENDING customer of :'identity' - the shell registered
-- it through the door and granted its KYC_PROCESSING consent through the consent door, as every case's customer has. The doors then act
-- on it as on any other: the console's KYC_REVIEWER resolves the task (ReviewService moves the case to READY_FOR_DECISION once no
-- task is open) and decides the case (DecisionRecording makes the customer ACTIVE or REJECTED).
--
-- Only kyc rows are written, and only new ones: the case is new, and a customer with a case still open gets none, so planting twice
-- for one identity writes nothing the second time (kyc_case_one_open_per_customer is the rank beneath; the shell plants for a fresh
-- customer each run). No evidence row: the provider's answer is sealed under a key the
-- database never holds, and a reviewer's decision rests on the checks, not on reading their evidence (KycDecision.byReviewer).
-- The policy version is the platform's own (KycPolicyVersion.CURRENT, held equal by E2eSeedKycDatabaseTest).
--
-- One statement, so psql and JDBC run the same text. Prints the review task's identifier.
WITH customer AS (
    SELECT c.id
      FROM party.customer c
      JOIN identity.identity i ON i.party_id = c.party_id
     WHERE i.id = :'identity'::uuid AND c.status = 'PENDING'
       AND NOT EXISTS (SELECT 1 FROM kyc.kyc_case k WHERE k.customer_id = c.id AND k.status NOT IN ('APPROVED', 'REJECTED'))
), opened AS (
    INSERT INTO kyc.kyc_case (id, customer_id, status, policy_version, opened_at, status_changed_at, case_kind)
    SELECT uuidv7(), customer.id, 'IN_REVIEW', 'kyc-2026-09', now(), now(), 'KYC'
      FROM customer
    RETURNING id
), hit AS (
    INSERT INTO kyc.verification_check (id, case_id, check_type, status, requested_at, status_changed_at, purpose)
    SELECT uuidv7(), opened.id, 'SANCTIONS', 'HIT', now(), now(), 'ONBOARDING'
      FROM opened
    RETURNING id, case_id
)
INSERT INTO kyc.review_task (id, case_id, check_id, status, opened_at)
SELECT uuidv7(), hit.case_id, hit.id, 'OPEN', now()
  FROM hit
RETURNING id;
