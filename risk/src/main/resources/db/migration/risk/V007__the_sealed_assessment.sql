-- The sealed risk assessment, and the decision override's table (P13-TSK-004; PHASE_13_PLAN.md sections 4, 7.1 T-eval,
-- 7.2 R3, 7.6, 8, 12.3; RISK_LIFECYCLES.md sections 4.2, 4.2a; ADR-0108, ADR-0109; INV-RSK-01, INV-RSK-03, INV-RSK-04).
--
-- ONE ASSESSMENT PER JUDGED COMMAND, BORN SEALED
--   risk_assessment is the platform's record of one judgement: born once per (operation, command_ref) by T-eval in the
--   judged command's OWN transaction, after the caller's locks - the pin (R1), the subjects' serialisation (R2a), the
--   reads, the engine, the windows counted only when the decision permits the movement (R2), then this insert (R3).
--   It has one state, DECIDED, and no edge: every writer is refused an UPDATE, a DELETE and a TRUNCATE (the triggers
--   below), and the application role holds SELECT and INSERT alone. UNIQUE (operation, command_ref) is the arbiter of
--   "exactly one": a retry of the command finds the sealed row and is answered with it, never re-evaluated.
--
-- WHAT IS SEALED
--   The head names the movement's subjects (exactly the velocity movement's: the party, the acting identity, the
--   source account and the destination - a customer account or a beneficiary - by the customer account's id, the device
--   by identity's hash, the merchant), the judged amount with its currency and scale (the only money here, on an
--   INSERT-only row - never a balance, INV-RSK-02), the pinned policy version (a composite key to risk_policy_version
--   (id, operation), so the database proves the version is the operation's own), the engine, the score, the decision,
--   the fail-safe reason and its cause, whether a CHALLENGE was satisfied, whether the windows counted the movement and
--   at which database instant, the digests of the canonical inputs and of the verdict (CanonicalEvaluation format 1),
--   the latency, the correlation and the database clock at the insert. risk_assessment_input holds the canonical inputs
--   text itself (CONFIDENTIAL - the Transaction Risk Profile), its digest recomputed by a CHECK; risk_rule_hit every
--   rule of the version in declared order with its outcome, contribution, hard decision and the reading it judged;
--   risk_reason the ordered reason codes. The verdict is the head with its hits and reasons; verdict_sha256 is the
--   digest of its canonical text, so a read can re-render the rows and say whether the seal still holds.
--
-- THE FAIL-SAFE IS A ROW, NEVER AN ABSENCE (INV-RSK-04)
--   An evaluation that could not run - no ACTIVE policy (NO_POLICY), the pin's read failing (PIN_UNREADABLE), a read
--   failing (READ_FAILED), the 250 ms statement timeout (TIMEOUT), the engine refusing (ENGINE_REFUSED) - is answered by
--   the policy's fail-safe band or the compiled ALLOW and recorded here like any other: fail_safe_reason and
--   unavailable_cause together, score 0, the policy version absent exactly when none could be pinned.
--
-- THE VELOCITY EVIDENCE (P13-TSK-006's finding, owed here)
--   velocity_outcome says whether the judged movement was counted in its windows (COUNTED, at velocity_cut_at), refused
--   so never counted (NOT_PERMITTED), or permitted while its count failed (UNCOUNTED - recorded, never silent). The
--   COUNTED rows ARE the evidence every window must equal, for every dimension, in the windows' own transaction and on
--   their own database instant (AssessmentVelocityEvidence; risk_assessment_counted below).
--
-- THE OVERRIDE'S TABLE (the machine is P13-TSK-012's)
--   decision_override lands here so the assessment and its override are one migration's: the four-eyes CHECK (C7: a
--   second holder when the override requires one), one live proposal per assessment, a birth over a DENY, HOLD or REVIEW
--   decision only. Every UPDATE is refused until P13-TSK-012 replaces decision_override_permits_only_machine_edges with
--   the machine's edges and grants the decision columns; nothing is ever deleted.

-- ---------------------------------------------------------------------------------------------------------------------
-- 1. The assessment's head.
-- ---------------------------------------------------------------------------------------------------------------------

CREATE TABLE risk.risk_assessment (
    id                    uuid        NOT NULL,

    -- The judged operation, and the judged command's identity within it (the seam adapter's: a transfer's id, a
    -- withdrawal's...) - the pair born once.
    operation             text        NOT NULL,
    command_ref           text        NOT NULL,

    -- The movement's subjects, by value (ADR-0029: no cross-schema foreign key). The party always; the rest where the
    -- operation has the role. Accounts by the CUSTOMER account's id (the ledger's owner_ref), as the windows count them.
    party_id              uuid        NOT NULL,
    identity_id           uuid,
    source_account_id     uuid,
    destination_kind      text,
    destination_id        uuid,
    device_hash           text,
    -- The merchant as a judged subject (the MERCHANT velocity role), never a tenant scope: no merchant reads an
    -- assessment. Named apart from the tenant column merchant_id, which OwnershipIsScopedTest confines to the
    -- merchant's, checkout's and bnpl's own statements (P6-TST-001).
    merchant_subject_id   uuid,

    -- The judged amount, minor units with its currency and scale - the only money in the row, and the row is INSERT-only.
    amount_minor          bigint      NOT NULL,
    currency              text        NOT NULL,
    scale                 integer     NOT NULL,

    -- The pinned policy version (R1), by id, operation and number; absent exactly when none could be pinned.
    policy_version_id     uuid,
    policy_operation      text,
    policy_version        integer,

    -- The engine that judged (or answered the fail-safe) - always named.
    engine                text        NOT NULL,

    score                 integer     NOT NULL,
    decision              text        NOT NULL,

    -- The fail-safe: its reason code and the cause of the unavailability, together or not at all.
    fail_safe_reason      text,
    unavailable_cause     text,

    -- A CHALLENGE's answer: the sealed session assurance against the policy's challenge assurance; NULL otherwise.
    challenge_satisfied   boolean,

    -- Whether the windows counted the movement, and the database instant of the cut they were counted at.
    velocity_outcome      text        NOT NULL,
    velocity_cut_at       timestamptz,

    -- The seal: CanonicalEvaluation's format and the digests of the inputs and of the verdict.
    canonical_format      integer     NOT NULL,
    inputs_sha256         text        NOT NULL,
    verdict_sha256        text        NOT NULL,

    -- How long T-eval took, from the pin to the insert - telemetry, never an input.
    latency_micros        bigint      NOT NULL,

    correlation_id        text        NOT NULL,
    causation_id          text        NOT NULL,

    -- The database clock at the insert, stamped by the trigger.
    assessed_at           timestamptz NOT NULL,

    CONSTRAINT risk_assessment_pk PRIMARY KEY (id),

    -- INV-RSK-01: exactly one assessment per judged command, under any concurrency.
    CONSTRAINT risk_assessment_command_once UNIQUE (operation, command_ref),

    CONSTRAINT risk_assessment_policy_fk
        FOREIGN KEY (policy_version_id, policy_operation) REFERENCES risk.risk_policy_version (id, operation),

    -- The judged operations: the five that move money (CREDIT_SIGNAL and MONITORING decide none).
    CONSTRAINT risk_assessment_operation_is_judged CHECK (
        operation IN ('TRANSFER', 'WITHDRAWAL', 'CARD_PAYMENT', 'WALLET_PAYMENT', 'CROSS_BORDER')),

    CONSTRAINT risk_assessment_command_ref_shape CHECK (command_ref ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$'),

    CONSTRAINT risk_assessment_destination_coherent CHECK (
        (destination_kind IS NULL) = (destination_id IS NULL)
        AND (destination_kind IS NULL OR destination_kind IN ('ACCOUNT', 'BENEFICIARY'))),

    -- A device only ever by identity's hash, never a description (OQ-4).
    CONSTRAINT risk_assessment_device_is_a_hash CHECK (device_hash IS NULL OR device_hash ~ '^[0-9a-f]{64}$'),

    CONSTRAINT risk_assessment_amount_bounded CHECK (amount_minor BETWEEN 0 AND 100000000000000),

    CONSTRAINT risk_assessment_currency_shape CHECK (currency ~ '^[A-Z]{3}$'),

    CONSTRAINT risk_assessment_scale_bounded CHECK (scale BETWEEN 0 AND 9),

    CONSTRAINT risk_assessment_policy_coherent CHECK (
        (policy_version_id IS NULL) = (policy_operation IS NULL)
        AND (policy_version_id IS NULL) = (policy_version IS NULL)
        AND (policy_operation IS NULL OR policy_operation = operation)
        AND (policy_version IS NULL OR policy_version >= 1)),

    CONSTRAINT risk_assessment_engine_is_known CHECK (engine IN ('RISK_ENGINE_V1')),

    CONSTRAINT risk_assessment_score_bounded CHECK (score BETWEEN 0 AND 1000),

    -- RiskDecision.admittedBy: a decision the operation's consumer can honour (HOLD only where hold-then-dispatch is
    -- native: the withdrawal; CARD_PAYMENT's arrives with P13-TSK-010's second slice and its own migration).
    CONSTRAINT risk_assessment_decision_admitted CHECK (
        (operation IN ('TRANSFER', 'CROSS_BORDER') AND decision IN ('ALLOW', 'CHALLENGE', 'DENY', 'REVIEW'))
        OR (operation = 'WITHDRAWAL' AND decision IN ('ALLOW', 'CHALLENGE', 'DENY', 'HOLD', 'REVIEW'))
        OR (operation IN ('CARD_PAYMENT', 'WALLET_PAYMENT') AND decision IN ('ALLOW', 'CHALLENGE', 'DENY'))),

    -- INV-RSK-04: the fail-safe recorded whole - its reason and its cause together; NO_POLICY_ACTIVE exactly when no
    -- policy was active; a fail-safe evaluated no rule, so its score is 0.
    CONSTRAINT risk_assessment_fail_safe_coherent CHECK (
        (fail_safe_reason IS NULL) = (unavailable_cause IS NULL)
        AND (fail_safe_reason IS NULL OR fail_safe_reason IN ('RISK_UNAVAILABLE', 'NO_POLICY_ACTIVE'))
        AND (unavailable_cause IS NULL
             OR unavailable_cause IN ('NO_POLICY', 'PIN_UNREADABLE', 'READ_FAILED', 'TIMEOUT', 'ENGINE_REFUSED'))
        AND (fail_safe_reason IS DISTINCT FROM 'NO_POLICY_ACTIVE') = (unavailable_cause IS DISTINCT FROM 'NO_POLICY')
        AND (fail_safe_reason IS NULL OR score = 0)),

    -- INV-RSK-01, INV-RSK-04: the policy version is named unless none could be pinned - none ACTIVE, or the pin's own
    -- read failed (the fail-safe then had no band to read and answered the compiled ALLOW).
    CONSTRAINT risk_assessment_pinned_unless_unpinnable CHECK (
        (policy_version_id IS NULL) = COALESCE(unavailable_cause IN ('NO_POLICY', 'PIN_UNREADABLE'), false)),

    CONSTRAINT risk_assessment_challenge_coherent CHECK ((challenge_satisfied IS NOT NULL) = (decision = 'CHALLENGE')),

    -- INV-RSK-03: a refused movement is never counted; a permitted one is counted, or recorded UNCOUNTED.
    CONSTRAINT risk_assessment_velocity_coherent CHECK (
        velocity_outcome IN ('COUNTED', 'NOT_PERMITTED', 'UNCOUNTED')
        AND (velocity_outcome = 'NOT_PERMITTED')
            = (NOT (decision = 'ALLOW' OR (decision = 'CHALLENGE' AND challenge_satisfied IS TRUE)))
        AND (velocity_outcome <> 'COUNTED' OR velocity_cut_at IS NOT NULL)),

    CONSTRAINT risk_assessment_format_known CHECK (canonical_format = 1),

    CONSTRAINT risk_assessment_digests_shaped CHECK (
        inputs_sha256 ~ '^[0-9a-f]{64}$' AND verdict_sha256 ~ '^[0-9a-f]{64}$'),

    CONSTRAINT risk_assessment_latency_bounded CHECK (latency_micros BETWEEN 0 AND 3600000000),

    CONSTRAINT risk_assessment_correlation_bounded CHECK (
        length(correlation_id) BETWEEN 1 AND 128 AND length(causation_id) BETWEEN 1 AND 128)
);

-- The velocity proof's evidence: the counted assessments by the instant their windows were cut at.
CREATE INDEX risk_assessment_counted ON risk.risk_assessment (velocity_cut_at) WHERE velocity_outcome = 'COUNTED';

-- The analyst's and the census's reads by party.
CREATE INDEX risk_assessment_by_party ON risk.risk_assessment (party_id, assessed_at);

COMMENT ON TABLE risk.risk_assessment IS
    'The sealed risk assessment (P13-TSK-004; INV-RSK-01, INV-RSK-04): one per (operation, command_ref), born DECIDED by T-eval in the judged command''s own transaction after its locks, never updated, deleted or truncated by any writer. The subjects, the judged amount, the pinned policy version and engine, the score, the decision, the fail-safe reason and cause, the challenge''s answer, the velocity outcome and cut instant, the digests of the canonical inputs and verdict, the latency. The amount is the judged amount, never a balance.';

-- ---------------------------------------------------------------------------------------------------------------------
-- 2. The sealed inputs, the rule hits and the reasons - born with the head, never changed.
-- ---------------------------------------------------------------------------------------------------------------------

CREATE TABLE risk.risk_assessment_input (
    assessment_id         uuid        NOT NULL,

    -- CanonicalEvaluation format 1: the readings the engine judged, byte for byte - CONFIDENTIAL.
    inputs_canonical      text        NOT NULL,
    inputs_sha256         text        NOT NULL,

    CONSTRAINT risk_assessment_input_pk PRIMARY KEY (assessment_id),

    CONSTRAINT risk_assessment_input_head_fk FOREIGN KEY (assessment_id) REFERENCES risk.risk_assessment (id),

    CONSTRAINT risk_assessment_input_bounded CHECK (octet_length(inputs_canonical) BETWEEN 2 AND 262144),

    -- The digest is the database's too: whatever a writer says, it is the text's own.
    CONSTRAINT risk_assessment_input_digest_holds CHECK (
        inputs_sha256 = encode(sha256(convert_to(inputs_canonical, 'UTF8')), 'hex'))
);

COMMENT ON TABLE risk.risk_assessment_input IS
    'The sealed inputs of one risk assessment (P13-TSK-004; INV-RSK-01): CanonicalEvaluation format 1, the digest recomputed by a CHECK - what replay parses and re-evaluates under the pinned policy and engine. CONFIDENTIAL. INSERT-only for every writer.';

CREATE TABLE risk.risk_rule_hit (
    assessment_id         uuid        NOT NULL,
    ordinal               integer     NOT NULL,
    kind                  text        NOT NULL,
    outcome               text        NOT NULL,
    contribution          integer     NOT NULL,
    hard_decision         text,

    -- The canonical token naming what the rule read (amount=150000, ABSENT) - a reading, never a threshold.
    judged                text        NOT NULL,

    CONSTRAINT risk_rule_hit_pk PRIMARY KEY (assessment_id, ordinal),

    CONSTRAINT risk_rule_hit_head_fk FOREIGN KEY (assessment_id) REFERENCES risk.risk_assessment (id),

    CONSTRAINT risk_rule_hit_ordinal_positive CHECK (ordinal >= 1),

    CONSTRAINT risk_rule_hit_kind_is_known CHECK (kind IN (
        'AMOUNT_BAND', 'VELOCITY_COUNT', 'VELOCITY_SUM', 'NEW_PAYEE', 'NEW_DEVICE', 'SESSION_ASSURANCE_BELOW',
        'RECENT_AUTH_FAILURES', 'RECENT_CREDENTIAL_CHANGE', 'RECENT_RECOVERY', 'ACCOUNT_AGE_BELOW', 'KYC_NOT_APPROVED',
        'SCREENING_HIT', 'PROFILE_RATING', 'MERCHANT_SUSPENDED', 'CORRIDOR_HIGH_RISK', 'DORMANT_THEN_ACTIVE',
        'RAPID_IN_OUT', 'STRUCTURING', 'ROUND_AMOUNT', 'REFUND_RATIO', 'CHARGEBACK_RATIO', 'BNPL_MULTI_APPLICATION')),

    CONSTRAINT risk_rule_hit_outcome_is_known CHECK (outcome IN ('FIRED', 'NOT_FIRED', 'ABSENT')),

    CONSTRAINT risk_rule_hit_contribution_bounded CHECK (contribution BETWEEN 0 AND 1000),

    CONSTRAINT risk_rule_hit_hard_decision_is_known CHECK (
        hard_decision IS NULL OR hard_decision IN ('DENY', 'HOLD', 'REVIEW', 'CHALLENGE', 'ALLOW')),

    -- A rule that did not fire contributes nothing and decides nothing.
    CONSTRAINT risk_rule_hit_only_fired_counts CHECK (
        outcome = 'FIRED' OR (contribution = 0 AND hard_decision IS NULL)),

    CONSTRAINT risk_rule_hit_judged_shape CHECK (
        judged ~ '^[A-Za-z0-9_.:,=-]{1,128}$' AND ((outcome = 'ABSENT') = (judged = 'ABSENT')))
);

COMMENT ON TABLE risk.risk_rule_hit IS
    'Every rule of the pinned policy version as one risk assessment judged it, in declared order (P13-TSK-004; INV-RSK-04): fired, not fired or ABSENT, its contribution, its hard decision and the reading it judged. CONFIDENTIAL. INSERT-only for every writer.';

CREATE TABLE risk.risk_reason (
    assessment_id         uuid        NOT NULL,
    position              integer     NOT NULL,
    code                  text        NOT NULL,

    -- INV-RSK-04's UNIQUE (assessment_id, position): the ordered reason codes.
    CONSTRAINT risk_reason_pk PRIMARY KEY (assessment_id, position),

    CONSTRAINT risk_reason_head_fk FOREIGN KEY (assessment_id) REFERENCES risk.risk_assessment (id),

    CONSTRAINT risk_reason_position_positive CHECK (position >= 1),

    -- ReasonCode, less the seam's CHALLENGE_REQUIRED - never a verdict's reason.
    CONSTRAINT risk_reason_code_is_known CHECK (code IN (
        'NO_POLICY_ACTIVE', 'RISK_UNAVAILABLE',
        'AMOUNT_BAND', 'VELOCITY_COUNT', 'VELOCITY_SUM', 'NEW_PAYEE', 'NEW_DEVICE', 'SESSION_ASSURANCE_BELOW',
        'RECENT_AUTH_FAILURES', 'RECENT_CREDENTIAL_CHANGE', 'RECENT_RECOVERY', 'ACCOUNT_AGE_BELOW', 'KYC_NOT_APPROVED',
        'SCREENING_HIT', 'PROFILE_RATING', 'MERCHANT_SUSPENDED', 'CORRIDOR_HIGH_RISK', 'DORMANT_THEN_ACTIVE',
        'RAPID_IN_OUT', 'STRUCTURING', 'ROUND_AMOUNT', 'REFUND_RATIO', 'CHARGEBACK_RATIO', 'BNPL_MULTI_APPLICATION'))
);

COMMENT ON TABLE risk.risk_reason IS
    'The ordered reason codes of one risk assessment (P13-TSK-004; INV-RSK-04): one per fired rule in evaluation order, or the fail-safe''s one reason. A closed vocabulary, never text. INSERT-only for every writer.';

-- ---------------------------------------------------------------------------------------------------------------------
-- 3. The seal, for every writer: stamped on the database clock, born with its inputs, never edited, never removed.
-- ---------------------------------------------------------------------------------------------------------------------

CREATE FUNCTION risk.risk_assessment_is_born_stamped() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    NEW.assessed_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER risk_assessment_is_born_stamped
    BEFORE INSERT ON risk.risk_assessment
    FOR EACH ROW
    EXECUTE FUNCTION risk.risk_assessment_is_born_stamped();

CREATE FUNCTION risk.risk_assessment_is_sealed() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    -- For EVERY writer, the migrator included: a judgement once made is the explanation of a money movement; a
    -- correction is an override (P13-TSK-012), never an edit (INV-RSK-01, INV-RSK-04).
    RAISE EXCEPTION 'a risk assessment is sealed: never updated, deleted or truncated (P13-TSK-004, INV-RSK-01)';
END;
$$;

CREATE TRIGGER risk_assessment_is_sealed
    BEFORE UPDATE OR DELETE ON risk.risk_assessment FOR EACH ROW EXECUTE FUNCTION risk.risk_assessment_is_sealed();
CREATE TRIGGER risk_assessment_is_never_truncated
    BEFORE TRUNCATE ON risk.risk_assessment FOR EACH STATEMENT EXECUTE FUNCTION risk.risk_assessment_is_sealed();
CREATE TRIGGER risk_assessment_input_is_sealed
    BEFORE UPDATE OR DELETE ON risk.risk_assessment_input FOR EACH ROW EXECUTE FUNCTION risk.risk_assessment_is_sealed();
CREATE TRIGGER risk_assessment_input_is_never_truncated
    BEFORE TRUNCATE ON risk.risk_assessment_input FOR EACH STATEMENT EXECUTE FUNCTION risk.risk_assessment_is_sealed();
CREATE TRIGGER risk_rule_hit_is_sealed
    BEFORE UPDATE OR DELETE ON risk.risk_rule_hit FOR EACH ROW EXECUTE FUNCTION risk.risk_assessment_is_sealed();
CREATE TRIGGER risk_rule_hit_is_never_truncated
    BEFORE TRUNCATE ON risk.risk_rule_hit FOR EACH STATEMENT EXECUTE FUNCTION risk.risk_assessment_is_sealed();
CREATE TRIGGER risk_reason_is_sealed
    BEFORE UPDATE OR DELETE ON risk.risk_reason FOR EACH ROW EXECUTE FUNCTION risk.risk_assessment_is_sealed();
CREATE TRIGGER risk_reason_is_never_truncated
    BEFORE TRUNCATE ON risk.risk_reason FOR EACH STATEMENT EXECUTE FUNCTION risk.risk_assessment_is_sealed();

-- At COMMIT: an assessment has its sealed inputs, their digest its own, and a fail-safe its reason first - a head alone
-- explains nothing.
CREATE FUNCTION risk.risk_assessment_has_its_inputs() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM risk.risk_assessment_input i
                    WHERE i.assessment_id = NEW.id AND i.inputs_sha256 = NEW.inputs_sha256) THEN
        RAISE EXCEPTION 'a risk assessment is born with its sealed inputs, their digest its own (P13-TSK-004, INV-RSK-01)'
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.fail_safe_reason IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM risk.risk_reason r
             WHERE r.assessment_id = NEW.id AND r.position = 1 AND r.code = NEW.fail_safe_reason) THEN
        RAISE EXCEPTION 'a fail-safe assessment carries its fail-safe reason first (P13-TSK-004, INV-RSK-04)'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER risk_assessment_has_its_inputs
    AFTER INSERT ON risk.risk_assessment
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION risk.risk_assessment_has_its_inputs();

-- ---------------------------------------------------------------------------------------------------------------------
-- 4. The decision override's table - the machine is P13-TSK-012's.
-- ---------------------------------------------------------------------------------------------------------------------

CREATE TABLE risk.decision_override (
    id                      uuid        NOT NULL,
    assessment_id           uuid        NOT NULL,
    status                  text        NOT NULL,

    -- Why: a closed code (P13-TSK-012's vocabulary) and the proposer's text - RESTRICTED-PII, screened.
    reason_code             text        NOT NULL,
    reason                  text        NOT NULL,

    -- C7, fixed at the proposal: a second RISK_OVERRIDE holder approves at or above the threshold or for a DENY.
    requires_second_person  boolean     NOT NULL,

    -- The action the approval issued and its outcome (P13-TSK-012; RELEASE_HELD_WITHDRAWAL, a one-time allowance...).
    action                  text,
    action_outcome          text,

    proposed_by             text        NOT NULL,
    proposed_by_type        text        NOT NULL,
    proposed_at             timestamptz NOT NULL,
    decided_by              text,
    decided_by_type         text,
    decided_at              timestamptz,
    decision_reason         text,
    correlation_id          text        NOT NULL,

    CONSTRAINT decision_override_pk PRIMARY KEY (id),

    CONSTRAINT decision_override_assessment_fk FOREIGN KEY (assessment_id) REFERENCES risk.risk_assessment (id),

    CONSTRAINT decision_override_status_is_known CHECK (status IN ('PROPOSED', 'APPROVED', 'REJECTED')),

    CONSTRAINT decision_override_reason_code_shape CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),

    CONSTRAINT decision_override_reasoned CHECK (char_length(reason) BETWEEN 1 AND 1000),

    CONSTRAINT decision_override_reason_no_instrument_shape CHECK (NOT risk.holds_instrument_shape(reason)),

    CONSTRAINT decision_override_decision_coherent CHECK (
        (status = 'PROPOSED') = (decided_by IS NULL)
        AND (decided_by IS NULL) = (decided_by_type IS NULL)
        AND (decided_by IS NULL) = (decided_at IS NULL)
        AND (decision_reason IS NULL OR decided_by IS NOT NULL)),

    CONSTRAINT decision_override_decision_reasoned CHECK (
        decision_reason IS NULL
        OR (char_length(decision_reason) BETWEEN 1 AND 1000 AND NOT risk.holds_instrument_shape(decision_reason))),

    CONSTRAINT decision_override_action_shape CHECK (
        (action IS NULL OR action ~ '^[A-Z][A-Z0-9_]{0,63}$')
        AND (action_outcome IS NULL OR action_outcome ~ '^[A-Z][A-Z0-9_]{0,63}$')
        AND (action_outcome IS NULL OR action IS NOT NULL)
        AND (action IS NULL OR status = 'APPROVED')),

    -- FOUR EYES, for every writer (INV-RSK-07, INV-AUD-04): where the override requires a second person, whoever
    -- approved it did not propose it.
    CONSTRAINT decision_override_four_eyes CHECK (
        status <> 'APPROVED' OR NOT requires_second_person OR decided_by <> proposed_by),

    CONSTRAINT decision_override_correlation_bounded CHECK (length(correlation_id) BETWEEN 1 AND 128)
);

-- One live proposal per assessment.
CREATE UNIQUE INDEX decision_override_one_live ON risk.decision_override (assessment_id) WHERE status = 'PROPOSED';

COMMENT ON TABLE risk.decision_override IS
    'An override of a DENY, HOLD or REVIEW risk decision (the table: P13-TSK-004; the machine PROPOSED -> APPROVED | REJECTED: P13-TSK-012; ADR-0108 decision 7, C7): never an edit of the assessment, which stays DECIDED; one live proposal per assessment; a second RISK_OVERRIDE holder where requires_second_person, by CHECK. Every UPDATE refused until P13-TSK-012''s machine; never deleted.';

CREATE FUNCTION risk.decision_override_permits_only_machine_edges() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    overridden text;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PROPOSED' OR NEW.decided_by IS NOT NULL OR NEW.decided_by_type IS NOT NULL
                OR NEW.decided_at IS NOT NULL OR NEW.decision_reason IS NOT NULL OR NEW.action IS NOT NULL
                OR NEW.action_outcome IS NOT NULL THEN
            RAISE EXCEPTION 'a decision override is born PROPOSED and undecided (P13-TSK-004; the machine P13-TSK-012)'
                USING ERRCODE = 'check_violation';
        END IF;
        SELECT a.decision INTO overridden FROM risk.risk_assessment a WHERE a.id = NEW.assessment_id;
        IF overridden IS NULL OR overridden NOT IN ('DENY', 'HOLD', 'REVIEW') THEN
            -- An ALLOW is never overridden - the money has moved, a case is the remedy (OQ-5); a CHALLENGE is the
            -- customer's to satisfy.
            RAISE EXCEPTION 'only a DENY, HOLD or REVIEW decision is overridden (ADR-0108 decision 7)'
                USING ERRCODE = 'check_violation';
        END IF;
        NEW.proposed_at := clock_timestamp();
        RETURN NEW;
    END IF;
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'a decision override is never deleted or truncated (P13-TSK-004)';
    END IF;
    -- The machine's edges are P13-TSK-012's: until it replaces this function, no override moves.
    RAISE EXCEPTION 'a decision override does not move until P13-TSK-012 builds its machine';
END;
$$;

CREATE TRIGGER decision_override_permits_only_machine_edges
    BEFORE INSERT OR UPDATE OR DELETE ON risk.decision_override
    FOR EACH ROW
    EXECUTE FUNCTION risk.decision_override_permits_only_machine_edges();

CREATE TRIGGER decision_override_is_never_truncated
    BEFORE TRUNCATE ON risk.decision_override
    FOR EACH STATEMENT
    EXECUTE FUNCTION risk.decision_override_permits_only_machine_edges();

-- ---------------------------------------------------------------------------------------------------------------------
-- 5. The grants: SELECT and INSERT; no UPDATE, no DELETE, for any of the five.
-- ---------------------------------------------------------------------------------------------------------------------

GRANT SELECT, INSERT ON risk.risk_assessment TO finapp_app;
GRANT SELECT, INSERT ON risk.risk_assessment_input TO finapp_app;
GRANT SELECT, INSERT ON risk.risk_rule_hit TO finapp_app;
GRANT SELECT, INSERT ON risk.risk_reason TO finapp_app;
GRANT SELECT, INSERT ON risk.decision_override TO finapp_app;
