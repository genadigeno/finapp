-- A transfer's reversal by four eyes (P17-TSK-031, ADR-0154 from finapp-admin-panel ADR-C135 decisions 3 and 4; INV-AUD-04,
-- INV-IDEM-01).
--
-- A reversal used to be one operator's act. It is now a proposal, by a holder of TRANSFER_REVERSE, and its one decision by a
-- holder of TRANSFER_REVERSE_APPROVE who did not propose it. The approval performs the existing effect - TransferReversal,
-- the same ledger reversal under the same key, the same COMPLETED -> REVERSED move - in the approval's own transaction. No
-- financial rule changes: the same journal entry, one step later. A rejection moves nothing.
--
-- THE ROW IS THE HISTORY: born PROPOSED, decided once by a conditional UPDATE of the decision's columns alone (the column
-- grant), never again (the trigger), never deleted (no grant). The status CHECK is ReversalProposalStatus.sqlValueList()
-- (TransferReversalProposalMigrationTest). Migrator-owned, schema-qualified throughout.

CREATE TABLE transfers.transfer_reversal_proposal (
    id                      uuid        PRIMARY KEY,
    transfer_id             uuid        NOT NULL REFERENCES transfers.transfer (id),
    proposal_reason         text        NOT NULL,
    proposed_by             text        NOT NULL,
    proposed_by_type        text        NOT NULL,
    proposed_at             timestamptz NOT NULL,
    proposal_correlation_id text        NOT NULL,
    status                  text        NOT NULL
        CONSTRAINT transfer_reversal_proposal_status_is_known
            CHECK (status IN ('PROPOSED', 'APPROVED', 'REJECTED')),
    decided_by              text,
    decided_by_type         text,
    decided_at              timestamptz,
    decision_reason         text,
    decision_correlation_id text,

    -- A decision exactly when decided: all five columns, or none.
    CONSTRAINT transfer_reversal_proposal_decision_is_whole CHECK (
        (status = 'PROPOSED') = (decided_by IS NULL)
        AND (decided_by IS NULL) = (decided_by_type IS NULL)
        AND (decided_by IS NULL) = (decided_at IS NULL)
        AND (decided_by IS NULL) = (decision_reason IS NULL)
        AND (decided_by IS NULL) = (decision_correlation_id IS NULL)),
    -- FOUR EYES, for every writer (INV-AUD-04): whoever decided a reversal did not propose it.
    CONSTRAINT transfer_reversal_proposal_four_eyes CHECK (decided_by IS NULL OR decided_by <> proposed_by),
    CONSTRAINT transfer_reversal_proposal_decided_after_proposed CHECK (decided_at IS NULL OR decided_at >= proposed_at),
    CONSTRAINT transfer_reversal_proposal_reasons_bounded CHECK (
        char_length(proposal_reason) BETWEEN 1 AND 1000
        AND (decision_reason IS NULL OR char_length(decision_reason) BETWEEN 1 AND 1000)),
    CONSTRAINT transfer_reversal_proposal_actors_bounded CHECK (
        char_length(proposed_by) BETWEEN 1 AND 200 AND (decided_by IS NULL OR char_length(decided_by) BETWEEN 1 AND 200)),
    CONSTRAINT transfer_reversal_proposal_correlations_bounded CHECK (
        char_length(proposal_correlation_id) BETWEEN 1 AND 200
        AND (decision_correlation_id IS NULL OR char_length(decision_correlation_id) BETWEEN 1 AND 200))
);

-- ONE OPEN PROPOSAL PER TRANSFER - beneath the transfer's FOR UPDATE, which every proposer takes first.
CREATE UNIQUE INDEX transfer_reversal_proposal_one_open ON transfers.transfer_reversal_proposal (transfer_id)
    WHERE status = 'PROPOSED';

CREATE INDEX transfer_reversal_proposal_by_transfer ON transfers.transfer_reversal_proposal (transfer_id, proposed_at);

-- A DECIDED PROPOSAL IS HISTORY: the one UPDATE is the decision of a PROPOSED row, changing nothing the proposal recorded.
CREATE FUNCTION transfers.transfer_reversal_proposal_is_decided_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status <> 'PROPOSED' THEN
        RAISE EXCEPTION 'reversal proposal % is %: a decided proposal is history (P17-TSK-031)', OLD.id, OLD.status;
    END IF;
    IF NEW.id <> OLD.id OR NEW.transfer_id <> OLD.transfer_id OR NEW.proposal_reason <> OLD.proposal_reason
            OR NEW.proposed_by <> OLD.proposed_by OR NEW.proposed_by_type <> OLD.proposed_by_type
            OR NEW.proposed_at <> OLD.proposed_at OR NEW.proposal_correlation_id <> OLD.proposal_correlation_id THEN
        RAISE EXCEPTION 'reversal proposal %: a decision changes the decision alone (P17-TSK-031)', OLD.id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER transfer_reversal_proposal_is_decided_once
    BEFORE UPDATE ON transfers.transfer_reversal_proposal
    FOR EACH ROW EXECUTE FUNCTION transfers.transfer_reversal_proposal_is_decided_once();

COMMENT ON TABLE transfers.transfer_reversal_proposal IS
    'A transfer''s reversal by four eyes (P17-TSK-031, ADR-0154): proposed by a holder of TRANSFER_REVERSE, decided once by a holder of TRANSFER_REVERSE_APPROVE who did not propose it (four-eyes CHECK). The approval performs the existing reversal in its own transaction. One open proposal per transfer.';
COMMENT ON COLUMN transfers.transfer_reversal_proposal.proposal_reason IS
    'RESTRICTED-PII ceiling: a person''s prose about a customer''s money movement.';
COMMENT ON COLUMN transfers.transfer_reversal_proposal.decision_reason IS
    'RESTRICTED-PII ceiling: as proposal_reason.';

GRANT SELECT, INSERT ON transfers.transfer_reversal_proposal TO finapp_app;
GRANT UPDATE (status, decided_by, decided_by_type, decided_at, decision_reason, decision_correlation_id)
    ON transfers.transfer_reversal_proposal TO finapp_app;
