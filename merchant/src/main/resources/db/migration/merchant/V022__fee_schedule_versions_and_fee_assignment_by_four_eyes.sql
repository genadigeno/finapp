-- Fee-schedule versions and a merchant's fee assignment by four eyes (P17-TSK-123; ADR-0154's shape, finapp-admin-panel
-- ADR-C135 decision 3 and ADR-0125 decision 4: a price and a merchant's pricing are financial configuration, four eyes;
-- INV-AUD-04, INV-IDEM-01). V022: V021 is P17-TSK-116's, V019 and V020 P17-TSK-031's.
--
-- A fee schedule version prices every capture after it takes effect, and the merchant's pointer says which schedule
-- prices it - each one operator's act until now. Each is a proposal now, by a holder of FEE_ADMINISTER (POST
-- .../fee-schedules/{id}/versions and PUT .../merchants/{merchantId}/fee-schedule, judged then by the effect's own rules on
-- an unlocked read), and its one decision by a holder of FEE_SCHEDULE_APPROVE who did not propose it. The approval performs
-- the EXISTING effect - FeeSchedules.addVersion (the number minted by V004's unique index; effective forward, judged again
-- at the approval: INV-MER-03) or FeeSchedules.assign (the merchant FOR UPDATE, the pointer and its event) - in the
-- approval's own transaction. A rejection moves nothing. V004's tables are unchanged.
--
-- THE ROW IS THE HISTORY: born PROPOSED, decided once by a conditional UPDATE of the decision's columns alone (the column
-- grant), never again (the trigger), never deleted (no grant). One open proposal per subject - per schedule, per merchant:
-- the partial unique index is the arbiter, the proposer takes no lock. Migrator-owned, schema-qualified throughout.

CREATE TABLE merchant.fee_schedule_version_proposal (
    id                      uuid          PRIMARY KEY,
    fee_schedule_id         uuid          NOT NULL,
    -- The proposed terms, exactly as V004's version row would hold them: a ratio, never floating point (INV-MON-01).
    rate                    numeric(7, 6) NOT NULL
        CONSTRAINT fee_schedule_version_proposal_rate_is_a_proportion
            CHECK (rate >= 0 AND rate < 1),
    -- The flat part, in the schedule's currency. MoneyColumns' three-column shape (ADR-0003).
    fixed_amount_minor BIGINT NOT NULL, fixed_currency CHAR(3) NOT NULL, fixed_scale SMALLINT NOT NULL, CHECK (fixed_currency ~ '^[A-Z]{3}$'), CHECK (fixed_scale BETWEEN 0 AND 9),
    CONSTRAINT fee_schedule_version_proposal_fixed_is_not_negative CHECK (fixed_amount_minor >= 0),
    rounding_policy         text          NOT NULL
        CONSTRAINT fee_schedule_version_proposal_rounding_policy_is_known
            CHECK (rounding_policy IN ('HALF_EVEN', 'HALF_UP', 'TOWARDS_ZERO', 'AWAY_FROM_ZERO', 'FLOOR', 'CEILING')),
    refund_fee_policy       text          NOT NULL
        CONSTRAINT fee_schedule_version_proposal_refund_fee_policy_is_known
            CHECK (refund_fee_policy IN ('RETAINED', 'RETURNED')),
    -- When the version starts pricing; NULL is "when it is approved" - the only way to say "now" (FeeScheduleVersion.create).
    -- A named instant already past at the approval is refused there by the effect (INV-MER-03), the proposal left open.
    effective_from          timestamptz,
    proposal_reason         text          NOT NULL,
    proposed_by             text          NOT NULL,
    proposed_by_type        text          NOT NULL,
    proposed_at             timestamptz   NOT NULL,
    proposal_correlation_id text          NOT NULL,
    status                  text          NOT NULL
        CONSTRAINT fee_schedule_version_proposal_status_is_known
            CHECK (status IN ('PROPOSED', 'APPROVED', 'REJECTED')),
    decided_by              text,
    decided_by_type         text,
    decided_at              timestamptz,
    decision_reason         text,
    decision_correlation_id text,
    -- The version the approval minted: the proposal explains the price (proposal -> approval -> version -> pin).
    fee_schedule_version_id uuid          REFERENCES merchant.fee_schedule_version (id),

    -- A version prices in its schedule's currency (V004's composite key): the proposal names the pair it would mint.
    CONSTRAINT fee_schedule_version_proposal_prices_its_schedules_currency
        FOREIGN KEY (fee_schedule_id, fixed_currency) REFERENCES merchant.fee_schedule (id, currency),
    -- A decision exactly when decided: all five columns, or none.
    CONSTRAINT fee_schedule_version_proposal_decision_is_whole CHECK (
        (status = 'PROPOSED') = (decided_by IS NULL)
        AND (decided_by IS NULL) = (decided_by_type IS NULL)
        AND (decided_by IS NULL) = (decided_at IS NULL)
        AND (decided_by IS NULL) = (decision_reason IS NULL)
        AND (decided_by IS NULL) = (decision_correlation_id IS NULL)),
    -- An approved proposal names the version it minted; a proposed or rejected one names none.
    CONSTRAINT fee_schedule_version_proposal_approval_names_its_version CHECK (
        (status = 'APPROVED') = (fee_schedule_version_id IS NOT NULL)),
    -- FOUR EYES, for every writer (INV-AUD-04): whoever decided a version did not propose it.
    CONSTRAINT fee_schedule_version_proposal_four_eyes CHECK (decided_by IS NULL OR decided_by <> proposed_by),
    CONSTRAINT fee_schedule_version_proposal_decided_after_proposed CHECK (decided_at IS NULL OR decided_at >= proposed_at),
    CONSTRAINT fee_schedule_version_proposal_reasons_bounded CHECK (
        char_length(proposal_reason) BETWEEN 1 AND 1000
        AND (decision_reason IS NULL OR char_length(decision_reason) BETWEEN 1 AND 1000)),
    CONSTRAINT fee_schedule_version_proposal_actors_bounded CHECK (
        char_length(proposed_by) BETWEEN 1 AND 200 AND (decided_by IS NULL OR char_length(decided_by) BETWEEN 1 AND 200)),
    CONSTRAINT fee_schedule_version_proposal_correlations_bounded CHECK (
        char_length(proposal_correlation_id) BETWEEN 1 AND 200
        AND (decision_correlation_id IS NULL OR char_length(decision_correlation_id) BETWEEN 1 AND 200))
);

-- ONE OPEN PROPOSAL PER SCHEDULE: ten proposers racing, one proposal; the rest are refused by the index. It is also the
-- approver's queue's scan of this table (the open rows alone).
CREATE UNIQUE INDEX fee_schedule_version_proposal_one_open ON merchant.fee_schedule_version_proposal (fee_schedule_id)
    WHERE status = 'PROPOSED';

CREATE INDEX fee_schedule_version_proposal_by_schedule
    ON merchant.fee_schedule_version_proposal (fee_schedule_id, proposed_at);

CREATE TABLE merchant.fee_assignment_proposal (
    id                      uuid        PRIMARY KEY,
    merchant_id             uuid        NOT NULL REFERENCES merchant.merchant (id),
    fee_schedule_id         uuid        NOT NULL REFERENCES merchant.fee_schedule (id),
    proposal_reason         text        NOT NULL,
    proposed_by             text        NOT NULL,
    proposed_by_type        text        NOT NULL,
    proposed_at             timestamptz NOT NULL,
    proposal_correlation_id text        NOT NULL,
    status                  text        NOT NULL
        CONSTRAINT fee_assignment_proposal_status_is_known
            CHECK (status IN ('PROPOSED', 'APPROVED', 'REJECTED')),
    decided_by              text,
    decided_by_type         text,
    decided_at              timestamptz,
    decision_reason         text,
    decision_correlation_id text,

    CONSTRAINT fee_assignment_proposal_decision_is_whole CHECK (
        (status = 'PROPOSED') = (decided_by IS NULL)
        AND (decided_by IS NULL) = (decided_by_type IS NULL)
        AND (decided_by IS NULL) = (decided_at IS NULL)
        AND (decided_by IS NULL) = (decision_reason IS NULL)
        AND (decided_by IS NULL) = (decision_correlation_id IS NULL)),
    -- FOUR EYES, for every writer (INV-AUD-04): whoever decided an assignment did not propose it.
    CONSTRAINT fee_assignment_proposal_four_eyes CHECK (decided_by IS NULL OR decided_by <> proposed_by),
    CONSTRAINT fee_assignment_proposal_decided_after_proposed CHECK (decided_at IS NULL OR decided_at >= proposed_at),
    CONSTRAINT fee_assignment_proposal_reasons_bounded CHECK (
        char_length(proposal_reason) BETWEEN 1 AND 1000
        AND (decision_reason IS NULL OR char_length(decision_reason) BETWEEN 1 AND 1000)),
    CONSTRAINT fee_assignment_proposal_actors_bounded CHECK (
        char_length(proposed_by) BETWEEN 1 AND 200 AND (decided_by IS NULL OR char_length(decided_by) BETWEEN 1 AND 200)),
    CONSTRAINT fee_assignment_proposal_correlations_bounded CHECK (
        char_length(proposal_correlation_id) BETWEEN 1 AND 200
        AND (decision_correlation_id IS NULL OR char_length(decision_correlation_id) BETWEEN 1 AND 200))
);

-- ONE OPEN PROPOSAL PER MERCHANT: the merchant is on one schedule, so one change of it is reviewed at a time.
CREATE UNIQUE INDEX fee_assignment_proposal_one_open ON merchant.fee_assignment_proposal (merchant_id)
    WHERE status = 'PROPOSED';

CREATE INDEX fee_assignment_proposal_by_merchant ON merchant.fee_assignment_proposal (merchant_id, proposed_at);

-- A DECIDED PROPOSAL IS HISTORY: the one UPDATE is the decision of a PROPOSED row, changing nothing the proposal recorded.
CREATE FUNCTION merchant.fee_schedule_version_proposal_is_decided_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status <> 'PROPOSED' THEN
        RAISE EXCEPTION 'fee schedule version proposal % is %: a decided proposal is history (P17-TSK-123)', OLD.id, OLD.status;
    END IF;
    IF NEW.id <> OLD.id OR NEW.fee_schedule_id <> OLD.fee_schedule_id OR NEW.rate <> OLD.rate
            OR NEW.fixed_amount_minor <> OLD.fixed_amount_minor OR NEW.fixed_currency <> OLD.fixed_currency
            OR NEW.fixed_scale <> OLD.fixed_scale OR NEW.rounding_policy <> OLD.rounding_policy
            OR NEW.refund_fee_policy <> OLD.refund_fee_policy
            OR NEW.effective_from IS DISTINCT FROM OLD.effective_from OR NEW.proposal_reason <> OLD.proposal_reason
            OR NEW.proposed_by <> OLD.proposed_by OR NEW.proposed_by_type <> OLD.proposed_by_type
            OR NEW.proposed_at <> OLD.proposed_at OR NEW.proposal_correlation_id <> OLD.proposal_correlation_id THEN
        RAISE EXCEPTION 'fee schedule version proposal %: a decision changes the decision alone (P17-TSK-123)', OLD.id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER fee_schedule_version_proposal_is_decided_once
    BEFORE UPDATE ON merchant.fee_schedule_version_proposal
    FOR EACH ROW EXECUTE FUNCTION merchant.fee_schedule_version_proposal_is_decided_once();

CREATE FUNCTION merchant.fee_assignment_proposal_is_decided_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status <> 'PROPOSED' THEN
        RAISE EXCEPTION 'fee assignment proposal % is %: a decided proposal is history (P17-TSK-123)', OLD.id, OLD.status;
    END IF;
    IF NEW.id <> OLD.id OR NEW.merchant_id <> OLD.merchant_id OR NEW.fee_schedule_id <> OLD.fee_schedule_id
            OR NEW.proposal_reason <> OLD.proposal_reason OR NEW.proposed_by <> OLD.proposed_by
            OR NEW.proposed_by_type <> OLD.proposed_by_type OR NEW.proposed_at <> OLD.proposed_at
            OR NEW.proposal_correlation_id <> OLD.proposal_correlation_id THEN
        RAISE EXCEPTION 'fee assignment proposal %: a decision changes the decision alone (P17-TSK-123)', OLD.id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER fee_assignment_proposal_is_decided_once
    BEFORE UPDATE ON merchant.fee_assignment_proposal
    FOR EACH ROW EXECUTE FUNCTION merchant.fee_assignment_proposal_is_decided_once();

COMMENT ON TABLE merchant.fee_schedule_version_proposal IS
    'A fee schedule version by four eyes (P17-TSK-123, ADR-0154''s shape): proposed by a holder of FEE_ADMINISTER, decided once by a holder of FEE_SCHEDULE_APPROVE who did not propose it (four-eyes CHECK). The approval mints the version in its own transaction and names it here. One open proposal per schedule.';
COMMENT ON COLUMN merchant.fee_schedule_version_proposal.proposal_reason IS
    'RESTRICTED-PII ceiling: a person''s prose about the platform''s commercial terms.';
COMMENT ON COLUMN merchant.fee_schedule_version_proposal.decision_reason IS
    'RESTRICTED-PII ceiling: as proposal_reason.';
COMMENT ON TABLE merchant.fee_assignment_proposal IS
    'A merchant''s fee-schedule assignment by four eyes (P17-TSK-123, ADR-0154''s shape): proposed by a holder of FEE_ADMINISTER, decided once by a holder of FEE_SCHEDULE_APPROVE who did not propose it (four-eyes CHECK). The approval performs the existing assignment in its own transaction. One open proposal per merchant.';
COMMENT ON COLUMN merchant.fee_assignment_proposal.proposal_reason IS
    'RESTRICTED-PII ceiling: a person''s prose about a merchant''s commercial terms.';
COMMENT ON COLUMN merchant.fee_assignment_proposal.decision_reason IS
    'RESTRICTED-PII ceiling: as proposal_reason.';

GRANT SELECT, INSERT ON merchant.fee_schedule_version_proposal TO finapp_app;
GRANT UPDATE (status, decided_by, decided_by_type, decided_at, decision_reason, decision_correlation_id,
    fee_schedule_version_id) ON merchant.fee_schedule_version_proposal TO finapp_app;
GRANT SELECT, INSERT ON merchant.fee_assignment_proposal TO finapp_app;
GRANT UPDATE (status, decided_by, decided_by_type, decided_at, decision_reason, decision_correlation_id)
    ON merchant.fee_assignment_proposal TO finapp_app;
