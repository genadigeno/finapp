-- A merchant's closure by four eyes (P17-TSK-031, ADR-0154 from finapp-admin-panel ADR-C135 decisions 3 and 4; INV-AUD-04,
-- INV-IDEM-01). V019, not V018: P17-TSK-033's branch holds merchant V018 (merchant_payout_by_merchant); renumber at the merge
-- only if the order matters (it does not: the two touch different tables).
--
-- A closure used to be one operator's act. It is terminal, so it is now a proposal, by a holder of MERCHANT_ADMINISTER, and
-- its one decision by a holder of MERCHANT_CLOSE_APPROVE who did not propose it. The approval performs the existing effect -
-- MerchantAdministration.close, its settled-money judgement and the payable's closing - in the approval's own transaction.
-- Suspension and reinstatement stay one person's (with a reason, a factor and now a key). A rejection moves nothing.
--
-- THE ROW IS THE HISTORY: born PROPOSED, decided once by a conditional UPDATE of the decision's columns alone (the column
-- grant), never again (the trigger), never deleted (no grant). The status CHECK is ClosureProposalStatus.sqlValueList()
-- (MerchantClosureProposalMigrationTest). Migrator-owned, schema-qualified throughout.

CREATE TABLE merchant.merchant_closure_proposal (
    id                      uuid        PRIMARY KEY,
    merchant_id             uuid        NOT NULL REFERENCES merchant.merchant (id),
    proposal_reason         text        NOT NULL,
    proposed_by             text        NOT NULL,
    proposed_by_type        text        NOT NULL,
    proposed_at             timestamptz NOT NULL,
    proposal_correlation_id text        NOT NULL,
    status                  text        NOT NULL
        CONSTRAINT merchant_closure_proposal_status_is_known
            CHECK (status IN ('PROPOSED', 'APPROVED', 'REJECTED')),
    decided_by              text,
    decided_by_type         text,
    decided_at              timestamptz,
    decision_reason         text,
    decision_correlation_id text,

    -- A decision exactly when decided: all five columns, or none.
    CONSTRAINT merchant_closure_proposal_decision_is_whole CHECK (
        (status = 'PROPOSED') = (decided_by IS NULL)
        AND (decided_by IS NULL) = (decided_by_type IS NULL)
        AND (decided_by IS NULL) = (decided_at IS NULL)
        AND (decided_by IS NULL) = (decision_reason IS NULL)
        AND (decided_by IS NULL) = (decision_correlation_id IS NULL)),
    -- FOUR EYES, for every writer (INV-AUD-04): whoever decided a closure did not propose it.
    CONSTRAINT merchant_closure_proposal_four_eyes CHECK (decided_by IS NULL OR decided_by <> proposed_by),
    CONSTRAINT merchant_closure_proposal_decided_after_proposed CHECK (decided_at IS NULL OR decided_at >= proposed_at),
    CONSTRAINT merchant_closure_proposal_reasons_bounded CHECK (
        char_length(proposal_reason) BETWEEN 1 AND 1000
        AND (decision_reason IS NULL OR char_length(decision_reason) BETWEEN 1 AND 1000)),
    CONSTRAINT merchant_closure_proposal_actors_bounded CHECK (
        char_length(proposed_by) BETWEEN 1 AND 200 AND (decided_by IS NULL OR char_length(decided_by) BETWEEN 1 AND 200)),
    CONSTRAINT merchant_closure_proposal_correlations_bounded CHECK (
        char_length(proposal_correlation_id) BETWEEN 1 AND 200
        AND (decision_correlation_id IS NULL OR char_length(decision_correlation_id) BETWEEN 1 AND 200))
);

-- ONE OPEN PROPOSAL PER MERCHANT - beneath the merchant's FOR UPDATE, which every proposer takes first.
CREATE UNIQUE INDEX merchant_closure_proposal_one_open ON merchant.merchant_closure_proposal (merchant_id)
    WHERE status = 'PROPOSED';

CREATE INDEX merchant_closure_proposal_by_merchant ON merchant.merchant_closure_proposal (merchant_id, proposed_at);

-- A DECIDED PROPOSAL IS HISTORY: the one UPDATE is the decision of a PROPOSED row, changing nothing the proposal recorded.
CREATE FUNCTION merchant.merchant_closure_proposal_is_decided_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status <> 'PROPOSED' THEN
        RAISE EXCEPTION 'closure proposal % is %: a decided proposal is history (P17-TSK-031)', OLD.id, OLD.status;
    END IF;
    IF NEW.id <> OLD.id OR NEW.merchant_id <> OLD.merchant_id OR NEW.proposal_reason <> OLD.proposal_reason
            OR NEW.proposed_by <> OLD.proposed_by OR NEW.proposed_by_type <> OLD.proposed_by_type
            OR NEW.proposed_at <> OLD.proposed_at OR NEW.proposal_correlation_id <> OLD.proposal_correlation_id THEN
        RAISE EXCEPTION 'closure proposal %: a decision changes the decision alone (P17-TSK-031)', OLD.id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER merchant_closure_proposal_is_decided_once
    BEFORE UPDATE ON merchant.merchant_closure_proposal
    FOR EACH ROW EXECUTE FUNCTION merchant.merchant_closure_proposal_is_decided_once();

COMMENT ON TABLE merchant.merchant_closure_proposal IS
    'A merchant''s closure by four eyes (P17-TSK-031, ADR-0154): proposed by a holder of MERCHANT_ADMINISTER, decided once by a holder of MERCHANT_CLOSE_APPROVE who did not propose it (four-eyes CHECK). The approval performs the existing closure in its own transaction. One open proposal per merchant.';
COMMENT ON COLUMN merchant.merchant_closure_proposal.proposal_reason IS
    'RESTRICTED-PII ceiling: a person''s prose about a merchant''s relationship.';
COMMENT ON COLUMN merchant.merchant_closure_proposal.decision_reason IS
    'RESTRICTED-PII ceiling: as proposal_reason.';

GRANT SELECT, INSERT ON merchant.merchant_closure_proposal TO finapp_app;
GRANT UPDATE (status, decided_by, decided_by_type, decided_at, decision_reason, decision_correlation_id)
    ON merchant.merchant_closure_proposal TO finapp_app;
