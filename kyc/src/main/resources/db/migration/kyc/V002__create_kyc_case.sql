-- The KYC case: one verification of one customer, opening to terminal decision (P2-TSK-005).
--
-- THE ONE-OPEN-CASE RULE IS THE DATABASE'S TO ENFORCE
--   "A customer has at most one open case" is a rule ACROSS aggregates of the same type: an
--   aggregate sees only itself, so only the database can arbitrate it between two concurrent
--   transactions (P1-TSK-005's reasoning, verbatim). The partial unique index below is that
--   arbitration, and the store's openOrConverge hands the loser the winner's case rather than
--   an error.
--
-- EVERY GENERATED LIST BELOW HAS ONE DEFINITION
--   The status CHECK is KycCaseStatus.sqlValueList() and the index predicate is
--   KycCaseStatus.sqlTerminalValueList(); KycCaseMigrationTest fails the build if this file and
--   the enum disagree on either - so a state added to the machine without a decision about
--   whether it frees the open-case slot cannot land quietly (the P0-TSK-022 pattern, applied to
--   a predicate as well as a constraint).

CREATE TABLE kyc.kyc_case (
    -- UUIDv7, minted by the application (ADR-0013).
    id                uuid        PRIMARY KEY,

    -- The customer under verification, BY VALUE. No REFERENCES clause: party.customer is
    -- another module's schema, and an FK across that boundary is coupling neither Gradle nor
    -- ArchUnit can see (ADR-0029, inherited by every later module pair). What guarantees the
    -- customer exists is the operation that opens the case, which is a property a test can
    -- assert - not the schema.
    customer_id       uuid        NOT NULL,

    status            text        NOT NULL,

    -- The policy regime this case is assessed under, pinned at open (INV-HIST-04): a decision
    -- recorded under a version nobody wrote down cannot be reproduced or defended
    -- (INV-KYC-02), and the fact is unrecoverable if not recorded when the case is born.
    policy_version    text        NOT NULL,

    -- Application-supplied from one injected Clock, never DEFAULT now() (DOMAIN_MODEL.md
    -- section Time).
    opened_at         timestamptz NOT NULL,
    status_changed_at timestamptz NOT NULL,

    CONSTRAINT kyc_case_status_is_known
        CHECK (status IN ('OPEN', 'CHECKS_IN_PROGRESS', 'IN_REVIEW', 'READY_FOR_DECISION', 'APPROVED', 'REJECTED')),

    CONSTRAINT kyc_case_policy_version_is_bounded
        CHECK (length(policy_version) BETWEEN 1 AND 50),

    CONSTRAINT kyc_case_status_change_is_not_before_opening
        CHECK (status_changed_at >= opened_at)
);

-- AT MOST ONE OPEN CASE PER CUSTOMER; decided ones unrestricted.
--
-- Partial on the NON-terminal states, so a terminal decision frees the slot: changed
-- circumstances open a NEW case (INV-LIFE-04 - the decided one stays decided and stays true,
-- which is what makes it defensible years later), and the successor must be insertable without
-- touching its predecessor.
CREATE UNIQUE INDEX kyc_case_one_open_per_customer
    ON kyc.kyc_case (customer_id)
    WHERE status NOT IN ('APPROVED', 'REJECTED');

-- Serves "the cases of this customer", including decided ones, which the partial index cannot.
CREATE INDEX kyc_case_by_customer ON kyc.kyc_case (customer_id);

COMMENT ON TABLE kyc.kyc_case IS
    'One verification of one customer. OPEN -> CHECKS_IN_PROGRESS -> {READY_FOR_DECISION | '
    'IN_REVIEW} -> READY_FOR_DECISION -> {APPROVED | REJECTED}; the terminal pair is terminal '
    '(INV-LIFE-04) and changed circumstances open a new case. The verification outcome has one '
    'authority and this is it (INV-KYC-05); party.customer.status is a projection.';

-- The DML this table genuinely requires and nothing more (V008's principle). UPDATE because a
-- status legitimately changes - the case is current state, not history; the decision record
-- (P2-TSK-013) is the immutable artefact, and it gets an append-only table of its own. No
-- DELETE: a case ends by becoming terminal, and an investigator asking "was there a case in
-- March?" needs the row.
GRANT SELECT, INSERT, UPDATE ON kyc.kyc_case TO finapp_app;
