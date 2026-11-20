-- An operator merchant payout by four eyes above a threshold (P17-TSK-031, ADR-0154 from finapp-admin-panel ADR-C135
-- decision 3: "Operator merchant payout: four-eyes above a threshold, as for refunds"; INV-AUD-04, INV-IDEM-01). V020 (V018 is
-- P17-TSK-033's, V019 this task's closure).
--
-- THE THRESHOLD, versioned per currency: an operator's payout ABOVE the latest version's threshold for its currency is a
-- proposal; at or below it the payout dispatches as before. A merchant's own payout, by its API key, is never judged - the
-- control is on the operator surface (ADR-C135). Judged inside the payout's dispatch transaction under the merchant's
-- FOR UPDATE. The values are the owner's, under the standing ruling of 2026-11-20: 10,000.00 in EUR, GBP and USD; 1,500,000
-- JPY; 4,000.000 BHD. A change is a new version, by a migration, until a four-eyes door for it exists; a currency with no
-- row requires four eyes (fail closed).
--
-- THE PROPOSAL: born PROPOSED by a holder of MERCHANT_PAYOUT, decided once by a holder of MERCHANT_PAYOUT_APPROVE who did not
-- propose it; the approval runs the existing payout command and is written in its dispatch transaction, naming the payout.
-- Migrator-owned, schema-qualified throughout.

CREATE TABLE merchant.payout_four_eyes_threshold (
    version         integer     NOT NULL,
    currency        char(3)     NOT NULL,
    scale           smallint    NOT NULL,
    threshold_minor bigint      NOT NULL,
    recorded_at     timestamptz NOT NULL,
    CONSTRAINT payout_four_eyes_threshold_pk PRIMARY KEY (version, currency),
    CONSTRAINT payout_four_eyes_threshold_versioned CHECK (version >= 1),
    CONSTRAINT payout_four_eyes_threshold_currency_shape CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT payout_four_eyes_threshold_scale_bounded CHECK (scale BETWEEN 0 AND 9),
    CONSTRAINT payout_four_eyes_threshold_not_negative CHECK (threshold_minor >= 0)
);

INSERT INTO merchant.payout_four_eyes_threshold (version, currency, scale, threshold_minor, recorded_at) VALUES
    (1, 'EUR', 2, 1000000, TIMESTAMPTZ '2026-11-20 00:00:00+00'),
    (1, 'GBP', 2, 1000000, TIMESTAMPTZ '2026-11-20 00:00:00+00'),
    (1, 'USD', 2, 1000000, TIMESTAMPTZ '2026-11-20 00:00:00+00'),
    (1, 'JPY', 0, 1500000, TIMESTAMPTZ '2026-11-20 00:00:00+00'),
    (1, 'BHD', 3, 4000000, TIMESTAMPTZ '2026-11-20 00:00:00+00');

COMMENT ON TABLE merchant.payout_four_eyes_threshold IS
    'The operator payout four-eyes threshold per currency, versioned (P17-TSK-031, ADR-0154): above the latest version''s value, an operator''s payout is a proposal decided by a second person. Never edited; a change is a new version. SELECT only for the application.';

CREATE TABLE merchant.payout_proposal (
    id                      uuid        PRIMARY KEY,
    merchant_id             uuid        NOT NULL REFERENCES merchant.merchant (id),
    amount_minor            bigint      NOT NULL,
    currency                char(3)     NOT NULL,
    scale                   smallint    NOT NULL,
    threshold_version       integer     NOT NULL,
    proposal_reason         text        NOT NULL,
    proposed_by             text        NOT NULL,
    proposed_by_type        text        NOT NULL,
    proposed_at             timestamptz NOT NULL,
    proposal_correlation_id text        NOT NULL,
    status                  text        NOT NULL
        CONSTRAINT payout_proposal_status_is_known
            CHECK (status IN ('PROPOSED', 'APPROVED', 'REJECTED')),
    decided_by              text,
    decided_by_type         text,
    decided_at              timestamptz,
    decision_reason         text,
    decision_correlation_id text,
    payout_id               uuid,

    CONSTRAINT payout_proposal_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT payout_proposal_currency_shape CHECK (currency ~ '^[A-Z]{3}$' AND scale BETWEEN 0 AND 9),
    CONSTRAINT payout_proposal_decision_is_whole CHECK (
        (status = 'PROPOSED') = (decided_by IS NULL)
        AND (decided_by IS NULL) = (decided_by_type IS NULL)
        AND (decided_by IS NULL) = (decided_at IS NULL)
        AND (decided_by IS NULL) = (decision_reason IS NULL)
        AND (decided_by IS NULL) = (decision_correlation_id IS NULL)),
    -- The approval and only the approval names the payout it dispatched.
    CONSTRAINT payout_proposal_approval_names_its_payout CHECK ((status = 'APPROVED') = (payout_id IS NOT NULL)),
    -- FOUR EYES, for every writer (INV-AUD-04): whoever decided a payout proposal did not propose it.
    CONSTRAINT payout_proposal_four_eyes CHECK (decided_by IS NULL OR decided_by <> proposed_by),
    CONSTRAINT payout_proposal_decided_after_proposed CHECK (decided_at IS NULL OR decided_at >= proposed_at),
    CONSTRAINT payout_proposal_reasons_bounded CHECK (
        char_length(proposal_reason) BETWEEN 1 AND 1000
        AND (decision_reason IS NULL OR char_length(decision_reason) BETWEEN 1 AND 1000)),
    CONSTRAINT payout_proposal_actors_bounded CHECK (
        char_length(proposed_by) BETWEEN 1 AND 200 AND (decided_by IS NULL OR char_length(decided_by) BETWEEN 1 AND 200)),
    CONSTRAINT payout_proposal_correlations_bounded CHECK (
        char_length(proposal_correlation_id) BETWEEN 1 AND 200
        AND (decision_correlation_id IS NULL OR char_length(decision_correlation_id) BETWEEN 1 AND 200))
);

-- ONE OPEN PROPOSAL PER MERCHANT.
CREATE UNIQUE INDEX payout_proposal_one_open ON merchant.payout_proposal (merchant_id)
    WHERE status = 'PROPOSED';

CREATE INDEX payout_proposal_by_merchant ON merchant.payout_proposal (merchant_id, proposed_at);

-- A DECIDED PROPOSAL IS HISTORY: the one UPDATE is the decision of a PROPOSED row, changing nothing the proposal recorded.
CREATE FUNCTION merchant.payout_proposal_is_decided_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status <> 'PROPOSED' THEN
        RAISE EXCEPTION 'payout proposal % is %: a decided proposal is history (P17-TSK-031)', OLD.id, OLD.status;
    END IF;
    IF NEW.id <> OLD.id OR NEW.merchant_id <> OLD.merchant_id OR NEW.amount_minor <> OLD.amount_minor
            OR NEW.currency <> OLD.currency OR NEW.scale <> OLD.scale
            OR NEW.threshold_version <> OLD.threshold_version OR NEW.proposal_reason <> OLD.proposal_reason
            OR NEW.proposed_by <> OLD.proposed_by OR NEW.proposed_by_type <> OLD.proposed_by_type
            OR NEW.proposed_at <> OLD.proposed_at OR NEW.proposal_correlation_id <> OLD.proposal_correlation_id THEN
        RAISE EXCEPTION 'payout proposal %: a decision changes the decision alone (P17-TSK-031)', OLD.id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER payout_proposal_is_decided_once
    BEFORE UPDATE ON merchant.payout_proposal
    FOR EACH ROW EXECUTE FUNCTION merchant.payout_proposal_is_decided_once();

COMMENT ON TABLE merchant.payout_proposal IS
    'An operator merchant payout above the four-eyes threshold (P17-TSK-031, ADR-0154): proposed by a holder of MERCHANT_PAYOUT, decided once by a holder of MERCHANT_PAYOUT_APPROVE who did not propose it (four-eyes CHECK); the approval dispatches the existing payout and is written in its dispatch transaction, naming the payout. One open proposal per merchant.';
COMMENT ON COLUMN merchant.payout_proposal.amount_minor IS 'RESTRICTED-FINANCIAL: the payout asked.';
COMMENT ON COLUMN merchant.payout_proposal.proposal_reason IS 'RESTRICTED-PII ceiling: a person''s prose about a merchant''s money.';
COMMENT ON COLUMN merchant.payout_proposal.decision_reason IS 'RESTRICTED-PII ceiling: as proposal_reason.';

GRANT SELECT ON merchant.payout_four_eyes_threshold TO finapp_app;
GRANT SELECT, INSERT ON merchant.payout_proposal TO finapp_app;
GRANT UPDATE (status, decided_by, decided_by_type, decided_at, decision_reason, decision_correlation_id, payout_id)
    ON merchant.payout_proposal TO finapp_app;
