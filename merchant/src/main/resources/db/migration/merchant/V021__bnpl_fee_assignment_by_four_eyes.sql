-- A merchant's BNPL fee-schedule assignment by four eyes (P17-TSK-116, ADR-0154's shape from finapp-admin-panel ADR-C135
-- decision 3: "merchant fee assignment" is financial configuration, four eyes; INV-AUD-04, INV-IDEM-01). V021: merchant V019
-- and V020 are P17-TSK-031's, V018 P17-TSK-033's.
--
-- Pointing a merchant's BNPL product at a fee schedule prices every purchase the merchant takes after it - one operator's act
-- until now. It is a proposal now, by a holder of FEE_ADMINISTER (PUT .../bnpl-fee-schedules/{product}, judged then: the
-- merchant, the schedule, its currency, every version returning its fee, the pointer not already there), and its one decision
-- by a holder of FEE_SCHEDULE_APPROVE who did not propose it. The approval performs the existing effect - BnplPricing.assign,
-- every rule re-judged under the merchant's FOR UPDATE, the pointer and its event written, merchant V010's trigger for every
-- writer - in the approval's own transaction. A rejection moves nothing. The pointer table is unchanged.
--
-- THE ROW IS THE HISTORY: born PROPOSED, decided once by a conditional UPDATE of the decision's columns alone (the column
-- grant), never again (the trigger), never deleted (no grant). One open proposal per (merchant, product): the partial unique
-- index is the arbiter - the proposer takes no lock. Migrator-owned, schema-qualified throughout.

CREATE TABLE merchant.bnpl_fee_assignment_proposal (
    id                      uuid        PRIMARY KEY,
    merchant_id             uuid        NOT NULL REFERENCES merchant.merchant (id),
    product                 text        NOT NULL
        CONSTRAINT bnpl_fee_assignment_proposal_product_is_known
            CHECK (product IN ('BNPL_PAY_IN_4', 'BNPL_PAY_IN_N')),
    fee_schedule_id         uuid        NOT NULL REFERENCES merchant.fee_schedule (id),
    proposal_reason         text        NOT NULL,
    proposed_by             text        NOT NULL,
    proposed_by_type        text        NOT NULL,
    proposed_at             timestamptz NOT NULL,
    proposal_correlation_id text        NOT NULL,
    status                  text        NOT NULL
        CONSTRAINT bnpl_fee_assignment_proposal_status_is_known
            CHECK (status IN ('PROPOSED', 'APPROVED', 'REJECTED')),
    decided_by              text,
    decided_by_type         text,
    decided_at              timestamptz,
    decision_reason         text,
    decision_correlation_id text,

    -- A decision exactly when decided: all five columns, or none.
    CONSTRAINT bnpl_fee_assignment_proposal_decision_is_whole CHECK (
        (status = 'PROPOSED') = (decided_by IS NULL)
        AND (decided_by IS NULL) = (decided_by_type IS NULL)
        AND (decided_by IS NULL) = (decided_at IS NULL)
        AND (decided_by IS NULL) = (decision_reason IS NULL)
        AND (decided_by IS NULL) = (decision_correlation_id IS NULL)),
    -- FOUR EYES, for every writer (INV-AUD-04): whoever decided an assignment did not propose it.
    CONSTRAINT bnpl_fee_assignment_proposal_four_eyes CHECK (decided_by IS NULL OR decided_by <> proposed_by),
    CONSTRAINT bnpl_fee_assignment_proposal_decided_after_proposed CHECK (decided_at IS NULL OR decided_at >= proposed_at),
    CONSTRAINT bnpl_fee_assignment_proposal_reasons_bounded CHECK (
        char_length(proposal_reason) BETWEEN 1 AND 1000
        AND (decision_reason IS NULL OR char_length(decision_reason) BETWEEN 1 AND 1000)),
    CONSTRAINT bnpl_fee_assignment_proposal_actors_bounded CHECK (
        char_length(proposed_by) BETWEEN 1 AND 200 AND (decided_by IS NULL OR char_length(decided_by) BETWEEN 1 AND 200)),
    CONSTRAINT bnpl_fee_assignment_proposal_correlations_bounded CHECK (
        char_length(proposal_correlation_id) BETWEEN 1 AND 200
        AND (decision_correlation_id IS NULL OR char_length(decision_correlation_id) BETWEEN 1 AND 200))
);

-- ONE OPEN PROPOSAL PER (MERCHANT, PRODUCT): ten proposers racing, one proposal; the rest are refused by the index.
CREATE UNIQUE INDEX bnpl_fee_assignment_proposal_one_open ON merchant.bnpl_fee_assignment_proposal (merchant_id, product)
    WHERE status = 'PROPOSED';

CREATE INDEX bnpl_fee_assignment_proposal_by_merchant
    ON merchant.bnpl_fee_assignment_proposal (merchant_id, proposed_at);

-- A DECIDED PROPOSAL IS HISTORY: the one UPDATE is the decision of a PROPOSED row, changing nothing the proposal recorded.
CREATE FUNCTION merchant.bnpl_fee_assignment_proposal_is_decided_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status <> 'PROPOSED' THEN
        RAISE EXCEPTION 'BNPL fee assignment proposal % is %: a decided proposal is history (P17-TSK-116)', OLD.id, OLD.status;
    END IF;
    IF NEW.id <> OLD.id OR NEW.merchant_id <> OLD.merchant_id OR NEW.product <> OLD.product
            OR NEW.fee_schedule_id <> OLD.fee_schedule_id OR NEW.proposal_reason <> OLD.proposal_reason
            OR NEW.proposed_by <> OLD.proposed_by OR NEW.proposed_by_type <> OLD.proposed_by_type
            OR NEW.proposed_at <> OLD.proposed_at OR NEW.proposal_correlation_id <> OLD.proposal_correlation_id THEN
        RAISE EXCEPTION 'BNPL fee assignment proposal %: a decision changes the decision alone (P17-TSK-116)', OLD.id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER bnpl_fee_assignment_proposal_is_decided_once
    BEFORE UPDATE ON merchant.bnpl_fee_assignment_proposal
    FOR EACH ROW EXECUTE FUNCTION merchant.bnpl_fee_assignment_proposal_is_decided_once();

COMMENT ON TABLE merchant.bnpl_fee_assignment_proposal IS
    'A merchant''s BNPL fee-schedule assignment by four eyes (P17-TSK-116, ADR-0154''s shape): proposed by a holder of FEE_ADMINISTER, decided once by a holder of FEE_SCHEDULE_APPROVE who did not propose it (four-eyes CHECK). The approval performs the existing assignment in its own transaction. One open proposal per merchant and product.';
COMMENT ON COLUMN merchant.bnpl_fee_assignment_proposal.proposal_reason IS
    'RESTRICTED-PII ceiling: a person''s prose about a merchant''s commercial terms.';
COMMENT ON COLUMN merchant.bnpl_fee_assignment_proposal.decision_reason IS
    'RESTRICTED-PII ceiling: as proposal_reason.';

GRANT SELECT, INSERT ON merchant.bnpl_fee_assignment_proposal TO finapp_app;
GRANT UPDATE (status, decided_by, decided_by_type, decided_at, decision_reason, decision_correlation_id)
    ON merchant.bnpl_fee_assignment_proposal TO finapp_app;
