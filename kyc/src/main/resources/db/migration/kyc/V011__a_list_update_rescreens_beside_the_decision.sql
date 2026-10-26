-- =============================================================================================
-- A list update re-screens a decided party - evidence beside the decision, never a re-decision
-- (P13-TSK-022, the owner's decision O1 "add the kyc door", 2026-10-26; PHASE_13_PLAN.md section
-- 12.10; INV-LIFE-04 as amended; INV-KYC-01, INV-HIST-02, INV-RSK-02).
--
-- WHY
--   kyc never re-asks an answered question: the run converges on a case's CLEAR or HIT check of a
--   type. So when a sanctions or PEP list changes, nothing in kyc could screen an already-decided
--   party against it - risk's rescreening (risk V018's permits) read kyc's answer, found it older
--   than the list version, and every such permit failed: a list version rescreened nobody in the
--   existing base. A new case was not the answer either (consent-gated, re-running identity and
--   documents, customer-visible, undecidable for an ACTIVE customer).
--
-- WHAT
--   A verification check gains its PURPOSE: ONBOARDING (every check before this migration, and
--   every check of a case's own run) or RESCREEN - a SANCTIONS or PEP screening asked again
--   because the list changed, keyed by risk's permit (rescreen_key, unique: one key is one
--   question, whichever instance asks and however often). A rescreen never answers a run's
--   required type, never holds a run in flight and never spends its retry budget: the run's reads
--   (the convergence, the budget's count) name purpose = 'ONBOARDING' and the one-in-flight index
--   below holds onboarding checks only - so a stranded rescreen never deadlocks an onboarding. Its
--   HIT is what counts (P13-TSK-022's review, fail-closed): on a case not yet decided a rescreen
--   HIT blocks the automatic approval, opens a review task and moves the case to IN_REVIEW exactly
--   as an onboarding HIT does, and the decision's evidence names every rescreen answered CLEAR or
--   HIT; on a DECIDED case it is evidence beside the decision, never a re-decision - nothing
--   moves, no task opens, the customer is untouched. Its instants are the database's (the
--   insert's clock_timestamp()), the clock risk's list version is activated on: "asked after the
--   activation" is one clock.
--
-- THE LEGAL COMBINATIONS, stated (not enforced here): a RESCREEN check is a review task's subject
-- only when HIT (ChecksAssessment.needingReview - an unknown rescreen is never a task), and a
-- decision's evidence only when CLEAR or HIT (ChecksAssessment.decisionEvidence). A composite FK
-- (the V008 precedent) cannot hold either: it would have to reference the check's status, which
-- moves until terminal - so the domain is the one statement, ChecksAssessmentTest pins it, and the
-- review task's and the evidence row's own check_id FKs hold the rest.
--
-- RISK'S READ (the review's MINOR 6): verification_check_decided_latest serves CheckStore
-- .latestDecidedScreenings - per screening type the decided check asked last, one index probe per
-- type on T-eval's hot path however many rescreens a case accumulates.
--
-- Every row before this migration is ONBOARDING (the column's default) with no key - the new
-- CHECKs hold them as written. The purpose and the key are frozen: the table-wide UPDATE V004
-- granted is narrowed to the two columns the machine moves (the V008 precedent on kyc_case), so
-- no writer can turn a rescreen into onboarding evidence or re-key a question.
--
-- DEPLOY-TIME LOCKS: the two ADD COLUMNs are catalogue-only (a constant default since PG 11);
-- the unique and the CHECKs validate every row of kyc.verification_check under ACCESS EXCLUSIVE,
-- the in-flight index is rebuilt and the decided-latest index built - one scan and three index
-- builds, linear in the table. kyc's runs and callbacks wait on the lock and retry; nothing
-- fails. The migration names no other schema.
-- =============================================================================================

ALTER TABLE kyc.verification_check ADD COLUMN purpose text NOT NULL DEFAULT 'ONBOARDING';

ALTER TABLE kyc.verification_check ADD COLUMN rescreen_key text;

ALTER TABLE kyc.verification_check
    -- CheckPurpose, in its declaration order (KycRescreenMigrationTest holds it to the enum).
    ADD CONSTRAINT verification_check_purpose_is_known
        CHECK (purpose IN ('ONBOARDING', 'RESCREEN')),
    -- A rescreen is keyed, and only a rescreen is: the key is risk's permit key and its ordinal.
    ADD CONSTRAINT verification_check_rescreen_is_keyed
        CHECK ((purpose = 'RESCREEN') = (rescreen_key IS NOT NULL)),
    ADD CONSTRAINT verification_check_rescreen_key_is_bounded
        CHECK (rescreen_key IS NULL OR char_length(rescreen_key) BETWEEN 1 AND 200),
    -- A list update re-asks a screening and nothing else.
    ADD CONSTRAINT verification_check_rescreen_is_a_screening
        CHECK (purpose = 'ONBOARDING' OR check_type IN ('SANCTIONS', 'PEP')),
    -- ONE QUESTION PER KEY: the arbiter every asker meets (ON CONFLICT (rescreen_key) DO NOTHING).
    ADD CONSTRAINT verification_check_rescreen_once UNIQUE (rescreen_key);

-- The run's one-in-flight-per-type arbiter, now over the run's own checks: a rescreen in flight
-- neither refuses an onboarding request of its type nor is converged on by it.
DROP INDEX kyc.verification_check_one_in_flight_per_type;

CREATE UNIQUE INDEX verification_check_one_in_flight_per_type
    ON kyc.verification_check (case_id, check_type)
    WHERE status NOT IN ('CLEAR', 'HIT', 'INDETERMINATE') AND purpose = 'ONBOARDING';

-- Risk's screening-hit read: per (case, type) the decided checks newest first, so the lateral's
-- LIMIT 1 is one probe. Both purposes - a list update's answer is the latest when it is.
CREATE INDEX verification_check_decided_latest
    ON kyc.verification_check (case_id, check_type, requested_at DESC, status_changed_at DESC, id DESC)
    WHERE status IN ('CLEAR', 'HIT');

COMMENT ON COLUMN kyc.verification_check.purpose IS
    'Why the check was asked: ONBOARDING (the case''s own run - what answers its required types) or '
    'RESCREEN (a sanctions or PEP screening asked again on a list update; its HIT needs a person while '
    'the case is undecided, and on a decided case it is evidence beside the decision, never a '
    're-decision - kyc V011, P13-TSK-022). Frozen.';

COMMENT ON COLUMN kyc.verification_check.rescreen_key IS
    'The key a RESCREEN was asked under - risk''s permit key (risk.rescreen:<permit id>) and its retry '
    'ordinal; unique, so one key is one question. NULL for every ONBOARDING check. Frozen.';

-- The frozen columns: UPDATE narrowed to what the check's machine moves.
REVOKE UPDATE ON kyc.verification_check FROM finapp_app;

GRANT UPDATE (status, status_changed_at) ON kyc.verification_check TO finapp_app;
