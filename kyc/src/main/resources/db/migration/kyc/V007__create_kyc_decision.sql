-- The KYC/KYB decision: the platform's own recorded act on a case (P2-TSK-013, INV-KYC-02,
-- ADR-0035/0038). Immutable, attributable, reason-carrying, policy-pinned,
-- evidence-referencing - the record every later financial phase gates on.
--
-- APPEND-ONLY AT DB-PRIVILEGE (INV-KYC-02's own enforcement rank). The application role holds
--   SELECT and INSERT and nothing else on both tables - the audit_record model. Unlike
--   review_task there is no UPDATE grant at all, so no freeze trigger is needed: an edit is a
--   statement the database refuses whoever issues it, and P0-TST-007's column-versus-table
--   privilege sweep is what keeps a later column-level widening visible.
--
-- ONE DECISION PER CASE, EVER - the unique index on (case_id) is TOTAL. Changed circumstances
--   (new evidence, a list update, a periodic re-verification) open a NEW case (INV-LIFE-04);
--   a wrong decision is corrected by a new case's decision, never an edit of this row. The
--   index is defence in depth behind the service's conditional case move
--   (READY_FOR_DECISION -> terminal), which is the concurrency arbiter: if this index ever
--   fires, an invariant was already violated upstream.
--
-- NO decided_at ordering constraint against the case's timestamps, deliberately: both are
--   application-supplied from one injected Clock in different transactions, and the local
--   container's clock is corrected backwards (P1-TSK-031). Correctness never depends on that
--   ordering.

CREATE TABLE kyc.kyc_decision (
    id uuid PRIMARY KEY,

    -- In-schema FK: the case this decision decides. Cross-schema references stay forbidden
    -- (ADR-0029's reasoning).
    case_id uuid NOT NULL REFERENCES kyc.kyc_case (id),

    -- Generated from DecisionOutcome.sqlValueList(); DecisionMigrationTest reconciles.
    outcome text NOT NULL
        CONSTRAINT kyc_decision_outcome_is_known
        CHECK (outcome IN ('APPROVED', 'REJECTED')),

    -- Generated from DecisionBasis.sqlValueList(); INV-KYC-02's two actor cases - a reviewer,
    -- or the platform under the stated automatic policy.
    decision_basis text NOT NULL
        CONSTRAINT kyc_decision_basis_is_known
        CHECK (decision_basis IN ('AUTOMATIC', 'REVIEWER')),

    -- The reviewer's IdentityId. No REFERENCES clause: identity.identity is another module's
    -- schema (ADR-0029). What ties the value to a real person is that it is copied from a
    -- proven Session by the recording service, and the audit record written in the same
    -- transaction names the same actor.
    decided_by uuid,

    -- Mirrors AuditRecord.MAX_REASON_LENGTH, which mirrors the CHECK on platform.audit_record;
    -- DecisionMigrationTest reconciles the copies so a widened boundary cannot turn a caller's
    -- accepted reason into a 500 at the last write.
    reason text NOT NULL
        CONSTRAINT kyc_decision_reason_is_bounded
        CHECK (char_length(reason) BETWEEN 1 AND 1000),

    -- The CASE's pinned regime, copied - never CURRENT re-read at decision time (INV-HIST-04:
    -- the case fixed which rules it is assessed under when it was born, P2-TSK-005).
    policy_version text NOT NULL,

    decided_at timestamptz NOT NULL,

    -- A REVIEWER decision names its person; an AUTOMATIC one cannot. Mirrored by the
    -- KycDecision constructor, so the domain and the schema cannot disagree.
    CONSTRAINT kyc_decision_actor_is_coherent
        CHECK ((decision_basis = 'REVIEWER') = (decided_by IS NOT NULL))
);

CREATE UNIQUE INDEX kyc_decision_one_per_case ON kyc.kyc_decision (case_id);

-- The checks this decision rested on (INV-KYC-02's evidence references), explicit rather than
-- implied by case_id: evidence appended AFTER the decision - the accepted upload race
-- P2-TSK-008 recorded - is visibly not what the decision rested on. A join table rather than
-- an array so referential integrity is the database's. Its non-emptiness is the one clause
-- this schema cannot express; the KycDecision constructor refuses an empty reference set, and
-- the acceptance test asserts rows exist.
CREATE TABLE kyc.kyc_decision_check (
    decision_id uuid NOT NULL REFERENCES kyc.kyc_decision (id),
    check_id uuid NOT NULL REFERENCES kyc.verification_check (id),
    PRIMARY KEY (decision_id, check_id)
);

GRANT SELECT, INSERT ON kyc.kyc_decision TO finapp_app;
GRANT SELECT, INSERT ON kyc.kyc_decision_check TO finapp_app;

COMMENT ON TABLE kyc.kyc_decision IS
    'The platform''s recorded KYC/KYB decision on a case (INV-KYC-02): immutable at the '
    'privilege level, attributable to a reviewer or to the stated automatic policy, '
    'policy-pinned, and referencing its evidence through kyc_decision_check.';

COMMENT ON COLUMN kyc.kyc_decision.outcome IS
    'CONFIDENTIAL ceiling. Whether a named person was approved or refused onboarding is a '
    'fact about them - kyc_case.status''s reasoning.';

COMMENT ON COLUMN kyc.kyc_decision.decision_basis IS
    'CONFIDENTIAL ceiling. AUTOMATIC discloses that no review was raised; REVIEWER that one '
    'was - the review_task.status tipping-off reasoning, one join away.';

COMMENT ON COLUMN kyc.kyc_decision.decided_by IS
    'RESTRICTED-PII ceiling. The reviewer''s IdentityId - as audit_record.actor_id: from '
    'Phase 1 an identity names a person. NULL exactly when the basis is AUTOMATIC.';

COMMENT ON COLUMN kyc.kyc_decision.reason IS
    'RESTRICTED-PII ceiling. On the reviewer path this is free prose written by a person '
    'about somebody''s verification - it may name the customer, a list entry or a case '
    'number. Bounded by the audit record''s own reason bound.';

COMMENT ON COLUMN kyc.kyc_decision.decided_at IS
    'CONFIDENTIAL ceiling. Dates the decision on a named person''s case - '
    'kyc_case.status_changed_at''s reasoning.';

COMMENT ON TABLE kyc.kyc_decision_check IS
    'The evidence references of a decision (INV-KYC-02): which checks it rested on, fixed at '
    'decision time. Append-only for the application role, as the decision itself.';
