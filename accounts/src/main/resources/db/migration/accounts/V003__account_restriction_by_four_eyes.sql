-- A customer agreement's restriction by four eyes (P17-TSK-028, ADR-0153; the owner's rulings of 2026-11-20: "New outflows
-- only", "Analyst proposes, officer approves"; INV-AUD-04, INV-LIFE-02).
--
-- A restriction is a proposal to move an agreement ACTIVE -> SUSPENDED (SUSPENSION) or SUSPENDED -> ACTIVE (REINSTATEMENT),
-- made by a holder of ACCOUNT_RESTRICT, and its one decision by a holder of ACCOUNT_RESTRICT_APPROVE who did not propose it.
-- The approval moves accounts.customer_account in the same transaction, under that row's FOR UPDATE - the row every new
-- customer-initiated outflow reads FOR SHARE in its own transaction (ADR-0153 decision 3). Nothing here holds money, a
-- balance or a hold, and no ledger account's status changes.
--
-- THE ROW IS THE HISTORY. Born PROPOSED; decided once by a conditional UPDATE of the decision columns alone (the column
-- grant), never again (the trigger); never deleted (no grant). Every generated list has one definition: the kind CHECK is
-- RestrictionKind.sqlValueList(), the status CHECK RestrictionStatus.sqlValueList(), the reason-per-kind CHECK
-- RestrictionReason.sqlPairs(); AccountRestrictionMigrationTest fails the build if this file and the enums disagree.
-- Migrator-owned, schema-qualified throughout.

CREATE TABLE accounts.account_restriction (
    id                      uuid        PRIMARY KEY,
    account_id              uuid        NOT NULL REFERENCES accounts.customer_account (id),
    customer_id             uuid        NOT NULL,
    kind                    text        NOT NULL
        CONSTRAINT account_restriction_kind_is_known
            CHECK (kind IN ('SUSPENSION', 'REINSTATEMENT')),
    reason_code             text        NOT NULL,
    proposal_reason         text        NOT NULL,
    proposed_by             text        NOT NULL,
    proposed_by_type        text        NOT NULL,
    proposed_at             timestamptz NOT NULL,
    proposal_correlation_id text        NOT NULL,
    status                  text        NOT NULL
        CONSTRAINT account_restriction_status_is_known
            CHECK (status IN ('PROPOSED', 'APPROVED', 'REJECTED')),
    decided_by              text,
    decided_by_type         text,
    decided_at              timestamptz,
    decision_reason         text,
    decision_correlation_id text,

    -- Each code explains one kind.
    CONSTRAINT account_restriction_reason_is_the_kinds CHECK (
        (kind = 'SUSPENSION' AND reason_code IN ('FRAUD_SUSPECTED', 'ACCOUNT_TAKEOVER_SUSPECTED', 'AML_INVESTIGATION', 'LEGAL_ORDER', 'CUSTOMER_REQUEST')) OR (kind = 'REINSTATEMENT' AND reason_code IN ('INVESTIGATION_CLEARED', 'LEGAL_ORDER_LIFTED', 'CUSTOMER_REQUESTED_REINSTATEMENT', 'RAISED_IN_ERROR'))),
    -- A decision exactly when decided: all five columns, or none.
    CONSTRAINT account_restriction_decision_is_whole CHECK (
        (status = 'PROPOSED') = (decided_by IS NULL)
        AND (decided_by IS NULL) = (decided_by_type IS NULL)
        AND (decided_by IS NULL) = (decided_at IS NULL)
        AND (decided_by IS NULL) = (decision_reason IS NULL)
        AND (decided_by IS NULL) = (decision_correlation_id IS NULL)),
    -- FOUR EYES, for every writer (INV-AUD-04): whoever decided a restriction did not propose it.
    CONSTRAINT account_restriction_four_eyes CHECK (decided_by IS NULL OR decided_by <> proposed_by),
    CONSTRAINT account_restriction_decided_after_proposed CHECK (decided_at IS NULL OR decided_at >= proposed_at),
    CONSTRAINT account_restriction_reasons_bounded CHECK (
        char_length(proposal_reason) BETWEEN 1 AND 1000
        AND (decision_reason IS NULL OR char_length(decision_reason) BETWEEN 1 AND 1000)),
    CONSTRAINT account_restriction_actors_bounded CHECK (
        char_length(proposed_by) BETWEEN 1 AND 200 AND (decided_by IS NULL OR char_length(decided_by) BETWEEN 1 AND 200)),
    CONSTRAINT account_restriction_correlations_bounded CHECK (
        char_length(proposal_correlation_id) BETWEEN 1 AND 200
        AND (decision_correlation_id IS NULL OR char_length(decision_correlation_id) BETWEEN 1 AND 200))
);

-- ONE OPEN PROPOSAL PER AGREEMENT - the backstop beneath the agreement's FOR UPDATE, which every proposer takes first.
CREATE UNIQUE INDEX account_restriction_one_open ON accounts.account_restriction (account_id) WHERE status = 'PROPOSED';

-- An agreement's restrictions, newest first.
CREATE INDEX account_restriction_by_account ON accounts.account_restriction (account_id, proposed_at);

-- A DECIDED RESTRICTION IS HISTORY: the one UPDATE is the decision of a PROPOSED row, and it changes nothing the proposal
-- recorded (the column grant already keeps the application role off those columns; this holds every role).
CREATE FUNCTION accounts.account_restriction_is_decided_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status <> 'PROPOSED' THEN
        RAISE EXCEPTION 'restriction % is %: a decided restriction is history (P17-TSK-028)', OLD.id, OLD.status;
    END IF;
    IF NEW.id <> OLD.id OR NEW.account_id <> OLD.account_id OR NEW.customer_id <> OLD.customer_id
            OR NEW.kind <> OLD.kind OR NEW.reason_code <> OLD.reason_code OR NEW.proposal_reason <> OLD.proposal_reason
            OR NEW.proposed_by <> OLD.proposed_by OR NEW.proposed_by_type <> OLD.proposed_by_type
            OR NEW.proposed_at <> OLD.proposed_at OR NEW.proposal_correlation_id <> OLD.proposal_correlation_id THEN
        RAISE EXCEPTION 'restriction %: a decision changes the decision alone (P17-TSK-028)', OLD.id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_restriction_is_decided_once
    BEFORE UPDATE ON accounts.account_restriction
    FOR EACH ROW EXECUTE FUNCTION accounts.account_restriction_is_decided_once();

COMMENT ON TABLE accounts.account_restriction IS
    'A customer agreement''s suspension or reinstatement by four eyes (P17-TSK-028, ADR-0153): proposed by a holder of ACCOUNT_RESTRICT, decided once by a holder of ACCOUNT_RESTRICT_APPROVE who did not propose it (four-eyes CHECK). The approval moves customer_account in the same transaction. One open proposal per agreement. Never a balance; no ledger account changes status.';
COMMENT ON COLUMN accounts.account_restriction.proposal_reason IS
    'RESTRICTED-PII ceiling: a person''s prose about a customer''s account, screened for card-number and bank-account shapes by the domain.';
COMMENT ON COLUMN accounts.account_restriction.decision_reason IS
    'RESTRICTED-PII ceiling: as proposal_reason.';

GRANT SELECT, INSERT ON accounts.account_restriction TO finapp_app;
GRANT UPDATE (status, decided_by, decided_by_type, decided_at, decision_reason, decision_correlation_id)
    ON accounts.account_restriction TO finapp_app;
