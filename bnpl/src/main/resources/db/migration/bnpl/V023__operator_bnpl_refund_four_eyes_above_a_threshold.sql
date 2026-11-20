-- An operator's BNPL refund by four eyes above a threshold (P17-TSK-116, ADR-0154 decision 4's shape, from finapp-admin-panel
-- ADR-C135 decision 3: a refund is a refund, so the operator's BNPL refund door takes the refund rule; INV-AUD-04,
-- INV-IDEM-01). The merchant's door is unchanged (its own sale, ADR-0105 decision 9); the dispute's door is the dispute's.
--
-- THE THRESHOLD, versioned per currency, seeded as payments V043: 1,000.00 in EUR, GBP and USD; 150,000 JPY; 400.000 BHD.
-- An operator's refund whose amount, with the purchase's committed refunds of every door before it, is ABOVE the latest
-- version's threshold for its currency is a proposal; at or below it the refund applies as before. Judged in T-ref, under the
-- purchase's FOR UPDATE (B5) that already serialises every refund of the purchase. A change is a new version, by a migration
-- until a four-eyes door for it exists; a row is never edited. A currency with no row requires four eyes (fail closed).
--
-- THE PROPOSAL: born PROPOSED by a holder of PAYMENT_REFUND, decided once by a holder of PAYMENT_REFUND_APPROVE who did not
-- propose it. The approval runs the existing refund (T-ref, one transaction); its decision is written inside it, naming the
-- refund. Migrator-owned, schema-qualified throughout.

CREATE TABLE bnpl.refund_four_eyes_threshold (
    version         integer     NOT NULL,
    currency        char(3)     NOT NULL,
    scale           smallint    NOT NULL,
    threshold_minor bigint      NOT NULL,
    recorded_at     timestamptz NOT NULL,
    CONSTRAINT refund_four_eyes_threshold_pk PRIMARY KEY (version, currency),
    CONSTRAINT refund_four_eyes_threshold_versioned CHECK (version >= 1),
    CONSTRAINT refund_four_eyes_threshold_currency_shape CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT refund_four_eyes_threshold_scale_bounded CHECK (scale BETWEEN 0 AND 9),
    CONSTRAINT refund_four_eyes_threshold_not_negative CHECK (threshold_minor >= 0)
);

INSERT INTO bnpl.refund_four_eyes_threshold (version, currency, scale, threshold_minor, recorded_at) VALUES
    (1, 'EUR', 2, 100000, TIMESTAMPTZ '2026-11-20 00:00:00+00'),
    (1, 'GBP', 2, 100000, TIMESTAMPTZ '2026-11-20 00:00:00+00'),
    (1, 'USD', 2, 100000, TIMESTAMPTZ '2026-11-20 00:00:00+00'),
    (1, 'JPY', 0, 150000, TIMESTAMPTZ '2026-11-20 00:00:00+00'),
    (1, 'BHD', 3, 400000, TIMESTAMPTZ '2026-11-20 00:00:00+00');

COMMENT ON TABLE bnpl.refund_four_eyes_threshold IS
    'The operator''s BNPL refund four-eyes threshold per currency, versioned (P17-TSK-116, ADR-0154 decision 3''s shape): above the latest version''s value, an operator''s refund (with the purchase''s committed refunds of every door) is a proposal decided by a second person. Never edited; a change is a new version. SELECT only for the application.';

CREATE TABLE bnpl.refund_proposal (
    id                      uuid        PRIMARY KEY,
    purchase_id             uuid        NOT NULL REFERENCES bnpl.purchase (id),
    amount_minor            bigint      NOT NULL,
    currency                char(3)     NOT NULL,
    scale                   smallint    NOT NULL,
    cumulative_minor        bigint      NOT NULL,
    threshold_version       integer     NOT NULL,
    proposal_reason         text        NOT NULL,
    proposed_by             text        NOT NULL,
    proposed_by_type        text        NOT NULL,
    proposed_at             timestamptz NOT NULL,
    proposal_correlation_id text        NOT NULL,
    status                  text        NOT NULL
        CONSTRAINT refund_proposal_status_is_known
            CHECK (status IN ('PROPOSED', 'APPROVED', 'REJECTED')),
    decided_by              text,
    decided_by_type         text,
    decided_at              timestamptz,
    decision_reason         text,
    decision_correlation_id text,
    refund_id               uuid,

    CONSTRAINT refund_proposal_amount_positive CHECK (amount_minor > 0 AND cumulative_minor >= amount_minor),
    CONSTRAINT refund_proposal_currency_shape CHECK (currency ~ '^[A-Z]{3}$' AND scale BETWEEN 0 AND 9),
    CONSTRAINT refund_proposal_decision_is_whole CHECK (
        (status = 'PROPOSED') = (decided_by IS NULL)
        AND (decided_by IS NULL) = (decided_by_type IS NULL)
        AND (decided_by IS NULL) = (decided_at IS NULL)
        AND (decided_by IS NULL) = (decision_reason IS NULL)
        AND (decided_by IS NULL) = (decision_correlation_id IS NULL)),
    -- The approval and only the approval names the refund it applied.
    CONSTRAINT refund_proposal_approval_names_its_refund CHECK ((status = 'APPROVED') = (refund_id IS NOT NULL)),
    -- FOUR EYES, for every writer (INV-AUD-04): whoever decided a refund proposal did not propose it.
    CONSTRAINT refund_proposal_four_eyes CHECK (decided_by IS NULL OR decided_by <> proposed_by),
    CONSTRAINT refund_proposal_decided_after_proposed CHECK (decided_at IS NULL OR decided_at >= proposed_at),
    CONSTRAINT refund_proposal_reasons_bounded CHECK (
        char_length(proposal_reason) BETWEEN 1 AND 1000
        AND (decision_reason IS NULL OR char_length(decision_reason) BETWEEN 1 AND 1000)),
    CONSTRAINT refund_proposal_actors_bounded CHECK (
        char_length(proposed_by) BETWEEN 1 AND 200 AND (decided_by IS NULL OR char_length(decided_by) BETWEEN 1 AND 200)),
    CONSTRAINT refund_proposal_correlations_bounded CHECK (
        char_length(proposal_correlation_id) BETWEEN 1 AND 200
        AND (decision_correlation_id IS NULL OR char_length(decision_correlation_id) BETWEEN 1 AND 200))
);

-- ONE OPEN PROPOSAL PER PURCHASE.
CREATE UNIQUE INDEX refund_proposal_one_open ON bnpl.refund_proposal (purchase_id)
    WHERE status = 'PROPOSED';

CREATE INDEX refund_proposal_by_purchase ON bnpl.refund_proposal (purchase_id, proposed_at);

-- A DECIDED PROPOSAL IS HISTORY: the one UPDATE is the decision of a PROPOSED row, changing nothing the proposal recorded.
CREATE FUNCTION bnpl.refund_proposal_is_decided_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status <> 'PROPOSED' THEN
        RAISE EXCEPTION 'refund proposal % is %: a decided proposal is history (P17-TSK-116)', OLD.id, OLD.status;
    END IF;
    IF NEW.id <> OLD.id OR NEW.purchase_id <> OLD.purchase_id OR NEW.amount_minor <> OLD.amount_minor
            OR NEW.currency <> OLD.currency OR NEW.scale <> OLD.scale OR NEW.cumulative_minor <> OLD.cumulative_minor
            OR NEW.threshold_version <> OLD.threshold_version OR NEW.proposal_reason <> OLD.proposal_reason
            OR NEW.proposed_by <> OLD.proposed_by OR NEW.proposed_by_type <> OLD.proposed_by_type
            OR NEW.proposed_at <> OLD.proposed_at OR NEW.proposal_correlation_id <> OLD.proposal_correlation_id THEN
        RAISE EXCEPTION 'refund proposal %: a decision changes the decision alone (P17-TSK-116)', OLD.id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER refund_proposal_is_decided_once
    BEFORE UPDATE ON bnpl.refund_proposal
    FOR EACH ROW EXECUTE FUNCTION bnpl.refund_proposal_is_decided_once();

COMMENT ON TABLE bnpl.refund_proposal IS
    'An operator''s BNPL refund above the four-eyes threshold (P17-TSK-116, ADR-0154 decision 3''s shape): proposed by a holder of PAYMENT_REFUND, decided once by a holder of PAYMENT_REFUND_APPROVE who did not propose it (four-eyes CHECK); the approval applies the existing refund (T-ref) and is written in its transaction, naming the refund. One open proposal per purchase.';
COMMENT ON COLUMN bnpl.refund_proposal.amount_minor IS 'RESTRICTED-FINANCIAL: the refund asked.';
COMMENT ON COLUMN bnpl.refund_proposal.proposal_reason IS 'RESTRICTED-PII ceiling: a person''s prose about a purchase.';
COMMENT ON COLUMN bnpl.refund_proposal.decision_reason IS 'RESTRICTED-PII ceiling: as proposal_reason.';

GRANT SELECT ON bnpl.refund_four_eyes_threshold TO finapp_app;
GRANT SELECT, INSERT ON bnpl.refund_proposal TO finapp_app;
GRANT UPDATE (status, decided_by, decided_by_type, decided_at, decision_reason, decision_correlation_id, refund_id)
    ON bnpl.refund_proposal TO finapp_app;
