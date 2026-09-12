-- KYB: the case kind and the beneficial-ownership graph (P2-TSK-015, PHASE_2_PLAN.md s4/s5).
--
-- ONE MACHINE, TWO KINDS. The lifecycle is shared (the plan: "one machine; KYB adds the
--   ownership precondition to decisioning"), so KYB is not a second table - it is a kind on
--   the case plus everything the kind arms: the owner rows below, the composite FKs that bind
--   them to the right kinds, and the ownership predicate on every transition into
--   READY_FOR_DECISION (JdbcKycCaseStore.moveToReadyForDecision).
--
-- THE KIND IS FIXED AT OPEN and immutable at DB-PRIVILEGE: the grant narrowing below removes
--   the application role's ability to write it. A KYB -> KYC flip would silently disarm the
--   ownership gate, which is why immutability here is a security property, not tidiness.
--
-- THE KIND CHECK IS GENERATED - KycCaseKind.sqlValueList(); KycCaseMigrationTest fails the
--   build if this file and the enum disagree (the P0-TSK-022 pattern).

ALTER TABLE kyc.kyc_case ADD COLUMN case_kind text NOT NULL DEFAULT 'KYC';

-- The default exists only to backfill the rows that predate the column - every case so far is
-- a person's, because nothing could open an organisation's. Dropped immediately: an INSERT
-- that does not state the kind is an INSERT that has not decided it.
ALTER TABLE kyc.kyc_case ALTER COLUMN case_kind DROP DEFAULT;

ALTER TABLE kyc.kyc_case ADD CONSTRAINT kyc_case_kind_is_known
    CHECK (case_kind IN ('KYC', 'KYB'));

-- The target for the composite FKs below: referencing (id, case_kind) is what lets the schema
-- itself insist that owner rows attach only to KYB cases and that a verification reference
-- names only a KYC case.
ALTER TABLE kyc.kyc_case ADD CONSTRAINT kyc_case_id_and_kind UNIQUE (id, case_kind);

-- GRANT NARROWING (the party V004/V005 precedent): V002 granted table-wide UPDATE when the
-- only writer moved status. Column-level grants can widen a privilege invisibly (P0-TST-007);
-- used here to NARROW: id, customer_id, policy_version, opened_at and the new case_kind become
-- unwritable by the application role - they are facts about the case, not fields of it.
REVOKE UPDATE ON kyc.kyc_case FROM finapp_app;
GRANT UPDATE (status, status_changed_at) ON kyc.kyc_case TO finapp_app;

-- The beneficial-ownership graph: one row per (case, owner party) EVER.
--
-- THE ROW IS EVIDENCE (INV-KYC-02): the owner set is part of what the organisation's decision
--   rests on. SELECT and INSERT and nothing else - no UPDATE, no DELETE, the audit_record
--   model - and declarations are refused from READY_FOR_DECISION on (the frozen set, enforced
--   by the store under the case-row lock). Together those make "the owner rows of this case"
--   exactly and immutably the set the decision was taken over. A wrong declaration is
--   corrected by a NEW case, never an edit (INV-LIFE-04's asymmetry).
--
-- THE GRAPH TERMINATES IN PERSONS, BY CONSTRAINT: verification_case_kind is pinned 'KYC', so
--   an owner's verification can only ever be a person's case - the "recursive, terminates in
--   Parties" rule bounded at depth one for Phase 2 (an organisation owner is refused at
--   declaration; lifting the bound means letting KYB appear here, a deliberate later act).
--   Symmetrically case_kind is pinned 'KYB', so a person's case cannot grow owners.
CREATE TABLE kyc.beneficial_owner (
    -- UUIDv7, minted by the application (ADR-0013).
    id                     uuid        PRIMARY KEY,

    case_id                uuid        NOT NULL,
    case_kind              text        NOT NULL DEFAULT 'KYB' CHECK (case_kind = 'KYB'),

    -- The person, as a Party: a value, never a cross-schema FK (ADR-0029, the
    -- kyc_case.customer_id pattern).
    owner_party_id         uuid        NOT NULL,

    verification_case_id   uuid        NOT NULL,
    verification_case_kind text        NOT NULL DEFAULT 'KYC'
                                       CHECK (verification_case_kind = 'KYC'),

    -- Basis points, 1..10000: a percentage is a number that must never be floating point
    -- (INV-MON-01's hygiene outside money). Nullable - an owner may qualify by role alone.
    stake_basis_points     integer
        CONSTRAINT beneficial_owner_stake_in_range
            CHECK (stake_basis_points BETWEEN 1 AND 10000),

    -- ControlRole.sqlValueList(); KycCaseMigrationTest reconciles.
    control_role           text
        CONSTRAINT beneficial_owner_role_is_known
            CHECK (control_role IN ('DIRECTOR', 'SENIOR_MANAGING_OFFICIAL', 'TRUSTEE')),

    -- Application-supplied from one injected Clock, never DEFAULT now().
    declared_at            timestamptz NOT NULL,

    -- A stake, a role, or both: an owner with neither is a person on the graph for no stated
    -- reason, which is exactly what a reviewer cannot defend.
    CONSTRAINT beneficial_owner_qualifies
        CHECK (stake_basis_points IS NOT NULL OR control_role IS NOT NULL),

    CONSTRAINT beneficial_owner_once_per_party UNIQUE (case_id, owner_party_id),

    CONSTRAINT beneficial_owner_case_is_kyb
        FOREIGN KEY (case_id, case_kind) REFERENCES kyc.kyc_case (id, case_kind),
    CONSTRAINT beneficial_owner_verification_is_kyc
        FOREIGN KEY (verification_case_id, verification_case_kind)
            REFERENCES kyc.kyc_case (id, case_kind)
);

-- The re-route read: when a verification case reaches its terminal decision, the KYB cases
-- whose graphs pin it are re-assessed (CaseAssessment.reRouteParentsOf).
CREATE INDEX beneficial_owner_by_verification_case
    ON kyc.beneficial_owner (verification_case_id);

COMMENT ON TABLE kyc.beneficial_owner IS
    'The beneficial-ownership graph of a KYB case (INV-KYC-02): one row per (case, owner '
    'party), append-only at the privilege level, frozen from READY_FOR_DECISION on. Each row '
    'pins the KYC-kind case whose terminal outcome is that owner''s verification; the '
    'composite FKs bound the graph at depth one for Phase 2.';

GRANT SELECT, INSERT ON kyc.beneficial_owner TO finapp_app;
