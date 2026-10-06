-- P9-TSK-025: the operator's FX trade reversal (the lifecycle document section 3.3; INV-REV-01, INV-REV-02, INV-AUD-04).
--
-- A wallet conversion is final for the customer; an erroneous one is corrected by an operator, by compensation alone -
-- the exact mirror entry through ledger's ReversalService, its V009 bound refusing a second - and by two persons:
--
--   PROPOSED --a different approver--> APPROVED   (the mirror, the trade REVERSED, the cover's consequence, one
--                                                  transaction)
--       `----a different rejecter--> REJECTED
--
-- Numbered V009 because the plan's V008 became P9-TSK-021's unwind (recorded as a deviation).
--
-- Held for every writer, beneath the domain:
--   * one live proposal per trade (the partial unique while PROPOSED) and one approval per trade ever;
--   * four-eyes as a CHECK: whoever decides is not whoever proposed - proven alone, beside the domain's refusal;
--   * a reason on every act; the decision's columns exactly when decided, the reversal entry exactly when APPROVED;
--   * the edge trigger: born PROPOSED on a BOOKED conversion trade only (a cross-border trade is never reversed,
--     INV-REV-03); PROPOSED -> APPROVED | REJECTED the only moves; the proposal frozen; never deleted;
--   * the append-only event history.

CREATE TABLE fx.trade_reversal (
    id                 uuid        PRIMARY KEY,
    trade_id           uuid        NOT NULL REFERENCES fx.trade (id),
    status             text        NOT NULL,
    proposed_by        text        NOT NULL,
    proposed_reason    text        NOT NULL,
    proposed_at        timestamptz NOT NULL,
    decided_by         text,
    decided_reason     text,
    decided_at         timestamptz,
    reversal_entry_id  uuid,
    correlation_id     text        NOT NULL,
    CONSTRAINT trade_reversal_status_is_known CHECK (status IN ('PROPOSED', 'APPROVED', 'REJECTED')),
    CONSTRAINT trade_reversal_is_four_eyes CHECK (decided_by IS NULL OR decided_by <> proposed_by),
    CONSTRAINT trade_reversal_reasons_are_given CHECK (
        char_length(btrim(proposed_reason)) BETWEEN 1 AND 500
        AND (decided_reason IS NULL OR char_length(btrim(decided_reason)) BETWEEN 1 AND 500)),
    CONSTRAINT trade_reversal_decision_is_whole CHECK (
        (status = 'PROPOSED') = (decided_by IS NULL)
        AND (decided_by IS NULL) = (decided_reason IS NULL)
        AND (decided_by IS NULL) = (decided_at IS NULL)),
    CONSTRAINT trade_reversal_entry_iff_approved CHECK ((status = 'APPROVED') = (reversal_entry_id IS NOT NULL))
);

CREATE UNIQUE INDEX trade_reversal_one_live_per_trade ON fx.trade_reversal (trade_id) WHERE status = 'PROPOSED';
CREATE UNIQUE INDEX trade_reversal_one_approval_per_trade ON fx.trade_reversal (trade_id) WHERE status = 'APPROVED';

CREATE FUNCTION fx.trade_reversal_edge_is_legal() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a trade reversal is never deleted (P9-TSK-025, INV-HIST-01)';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PROPOSED' THEN
            RAISE EXCEPTION 'a trade reversal is born PROPOSED (P9-TSK-025)';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM fx.trade t WHERE t.id = NEW.trade_id AND t.status = 'BOOKED'
                       AND t.purpose = 'CONVERSION') THEN
            RAISE EXCEPTION 'only a BOOKED conversion trade is reversed - never a cross-border one (P9-TSK-025, INV-REV-03)';
        END IF;
        NEW.proposed_at := statement_timestamp();
        RETURN NEW;
    END IF;
    IF (to_jsonb(NEW) - 'status' - 'decided_by' - 'decided_reason' - 'decided_at' - 'reversal_entry_id')
            IS DISTINCT FROM (to_jsonb(OLD) - 'status' - 'decided_by' - 'decided_reason' - 'decided_at' - 'reversal_entry_id') THEN
        RAISE EXCEPTION 'a trade reversal''s proposal is frozen (P9-TSK-025)';
    END IF;
    IF NOT (OLD.status = 'PROPOSED' AND NEW.status IN ('APPROVED', 'REJECTED')) THEN
        RAISE EXCEPTION 'a trade reversal moves PROPOSED -> APPROVED | REJECTED only; % -> % is not an edge (P9-TSK-025)',
            OLD.status, NEW.status;
    END IF;
    NEW.decided_at := statement_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER trade_reversal_edge_is_legal
    BEFORE INSERT OR UPDATE OR DELETE ON fx.trade_reversal
    FOR EACH ROW
    EXECUTE FUNCTION fx.trade_reversal_edge_is_legal();

CREATE TABLE fx.trade_reversal_event (
    id           uuid        PRIMARY KEY,
    reversal_id  uuid        NOT NULL REFERENCES fx.trade_reversal (id),
    from_status  text,
    to_status    text        NOT NULL,
    actor        text        NOT NULL,
    reason       text        NOT NULL,
    occurred_at  timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT trade_reversal_event_to_is_known CHECK (to_status IN ('PROPOSED', 'APPROVED', 'REJECTED')),
    CONSTRAINT trade_reversal_event_birth_has_no_origin CHECK ((from_status IS NULL) = (to_status = 'PROPOSED'))
);

CREATE INDEX trade_reversal_event_by_reversal ON fx.trade_reversal_event (reversal_id, occurred_at);

CREATE FUNCTION fx.trade_reversal_event_is_append_only() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'a trade reversal''s history is append-only (P9-TSK-025, INV-HIST-01)';
END;
$$;

CREATE TRIGGER trade_reversal_event_is_append_only
    BEFORE UPDATE OR DELETE ON fx.trade_reversal_event
    FOR EACH ROW
    EXECUTE FUNCTION fx.trade_reversal_event_is_append_only();

GRANT SELECT, INSERT ON fx.trade_reversal TO finapp_app;
GRANT UPDATE (status, decided_by, decided_reason, decided_at, reversal_entry_id) ON fx.trade_reversal TO finapp_app;
GRANT SELECT, INSERT ON fx.trade_reversal_event TO finapp_app;
