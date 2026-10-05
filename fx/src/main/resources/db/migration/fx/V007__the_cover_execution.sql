-- The cover's execution fact and the machine's last edge (P9-TSK-012; ADR-0077 sections 2-7,
-- PHASE_9_PLAN.md sections 12.4(b)/(f) and 12.5; INV-FX-06, INV-FX-08, INV-PAY-04, INV-IDEM-02).
--
-- ONE PROVIDER EXECUTION, ONE MONEY FACT
--   fx.cover_execution is the arbiter of a cover's execution: PRIMARY KEY (cover_id) - a cover
--   executes once, whichever of the answer, the inquiry or the hinted inquiry applies it -, UNIQUE
--   (provider_code, provider_trade_ref) - one provider trade is one cover's -, and UNIQUE
--   journal_entry_id beside the posting key fx-cover:<coverId>. It holds with every Java guard
--   removed (the counted lock-bypass probe).
--
-- THE COVER CLOSES EXACTLY ITS PLAN
--   The row copies the plan's position legs from the QUOTE (the frozen plan both a conversion and,
--   from P9-TSK-019, a cross-border payment price from), checked at birth for every writer; the
--   realised result per leg is executed - plan by CHECK (the sold leg: plan - sold, what the
--   platform kept; the bought leg: bought - plan, what it gained), and executed_off_plan is the
--   fixed leg's difference by CHECK - a provider deviating on the fixed leg is flagged, counted
--   and alerted, and the books still close exactly (the difference posts as realised result).
--   A requote re-prices the computed leg only, so its executed_off_plan is false.
--
-- BIRTH, FOR EVERY WRITER
--   The cover is DISPATCHED or UNKNOWN at exactly this attempt; the client reference is that
--   attempt's T; the provider, the currencies and the fixed side are the cover's; recorded_at and
--   recorded_on are the database's (the entry's posting date). An UNWIND's execution is refused
--   until P9-TSK-021 builds the unwind. The entry is attached once, in the same transaction, and a
--   DEFERRED constraint trigger refuses a commit without it; otherwise append-only.
--
-- THE MACHINE, RE-STATED (V006's function replaced)
--   * DISPATCHED | UNKNOWN -> EXECUTED is admitted exactly when this cover's execution fact exists
--     for its current attempt;
--   * REJECTED -> DISPATCHED (attempt n+1) requires attempt n+1's row - its T(n+1) - to exist
--     ALREADY, so a new reference is stored before the permit that licenses sending it commits;
--   * requote_failures (new) counts the plausibility refusals of a REJECTED cover's requotes - the
--     backoff the sweeper paces by - and resets to 0 with the requote;
--   * caused_by_event_id (new) is the fx.FxQuoteAccepted event the cover's events are caused by,
--     required at birth.

ALTER TABLE fx.cover
    ADD COLUMN caused_by_event_id UUID,
    ADD COLUMN requote_failures INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT cover_requote_failures_non_negative CHECK (requote_failures >= 0);

COMMENT ON COLUMN fx.cover.caused_by_event_id IS
    'The fx.FxQuoteAccepted event this cover''s events name as their causation (P9-TSK-012); required at birth.';
COMMENT ON COLUMN fx.cover.requote_failures IS
    'How many requotes of this REJECTED cover were refused (declined, unanswered or implausible) - the sweeper''s backoff; reset by the requote (P9-TSK-012, INV-FX-08).';

CREATE OR REPLACE FUNCTION fx.cover_machine_is_legal()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    now TIMESTAMPTZ := statement_timestamp();
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DISPATCHED' OR NEW.attempts <> 1 OR NEW.requote_failures <> 0 THEN
            RAISE EXCEPTION 'a cover is born DISPATCHED at attempt 1 (P9-TSK-009, ADR-0077)';
        END IF;
        IF NEW.caused_by_event_id IS NULL THEN
            RAISE EXCEPTION 'a cover is born naming the acceptance that caused it (P9-TSK-012)';
        END IF;
        NEW.last_dispatched_at := now;
        NEW.created_at := now;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a cover is never deleted (P9-TSK-009)';
    END IF;
    IF (to_jsonb(NEW) - 'status' - 'attempts' - 'last_dispatched_at' - 'requote_failures')
            IS DISTINCT FROM (to_jsonb(OLD) - 'status' - 'attempts' - 'last_dispatched_at' - 'requote_failures') THEN
        RAISE EXCEPTION 'a cover''s exposure is frozen (P9-TSK-009)';
    END IF;
    IF NEW.last_dispatched_at IS DISTINCT FROM OLD.last_dispatched_at THEN
        -- The send permit: stamped by the database, strictly forward, for every writer.
        NEW.last_dispatched_at := GREATEST(OLD.last_dispatched_at + INTERVAL '1 microsecond', now);
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
        (OLD.status = 'DISPATCHED' AND NEW.status IN ('UNKNOWN', 'REJECTED'))
        OR (OLD.status = 'UNKNOWN' AND NEW.status = 'REJECTED')
        OR (OLD.status IN ('DISPATCHED', 'UNKNOWN') AND NEW.status = 'EXECUTED'
            AND EXISTS (SELECT 1 FROM fx.cover_execution e WHERE e.cover_id = OLD.id AND e.attempt = OLD.attempts))
        OR (OLD.status = 'REJECTED' AND NEW.status = 'DISPATCHED' AND NEW.attempts = OLD.attempts + 1
            AND EXISTS (SELECT 1 FROM fx.cover_attempt a WHERE a.cover_id = OLD.id AND a.attempt = NEW.attempts))
        OR (OLD.status = 'REJECTED' AND NEW.status = 'VOIDED')) THEN
        RAISE EXCEPTION 'a cover moves % -> % only along its machine; EXECUTED needs its execution fact, a requote its new reference (P9-TSK-009, P9-TSK-012)', OLD.status, NEW.status;
    END IF;
    IF NEW.attempts <> OLD.attempts AND NOT (OLD.status = 'REJECTED' AND NEW.status = 'DISPATCHED') THEN
        RAISE EXCEPTION 'a cover''s attempt advances only with a requote (P9-TSK-009, INV-FX-08)';
    END IF;
    IF NEW.requote_failures <> OLD.requote_failures AND NOT (
        (OLD.status = 'REJECTED' AND NEW.status = 'REJECTED' AND NEW.requote_failures = OLD.requote_failures + 1)
        OR (OLD.status = 'REJECTED' AND NEW.status = 'DISPATCHED' AND NEW.requote_failures = 0)) THEN
        RAISE EXCEPTION 'a cover''s requote failures count a REJECTED cover''s refusals, reset by its requote (P9-TSK-012)';
    END IF;
    RETURN NEW;
END;
$$;

-- ------------------------------------------------------------------ the execution fact

CREATE TABLE fx.cover_execution (
    cover_id                UUID           NOT NULL,
    attempt                 INTEGER        NOT NULL,
    client_reference        TEXT           NOT NULL,
    provider_code           TEXT           NOT NULL,
    provider_trade_ref      TEXT           NOT NULL,
    fixed_side              TEXT           NOT NULL,
    sold_currency           CHAR(3)        NOT NULL,
    sold_minor              BIGINT         NOT NULL,
    sold_scale              SMALLINT       NOT NULL,
    bought_currency         CHAR(3)        NOT NULL,
    bought_minor            BIGINT         NOT NULL,
    bought_scale            SMALLINT       NOT NULL,
    executed_rate           NUMERIC(20,10) NOT NULL,
    value_date              DATE           NOT NULL,
    plan_sold_minor         BIGINT         NOT NULL,
    plan_bought_minor       BIGINT         NOT NULL,
    realised_sold_minor     BIGINT         NOT NULL,
    realised_bought_minor   BIGINT         NOT NULL,
    executed_off_plan       BOOLEAN        NOT NULL,
    journal_entry_id        UUID,
    recorded_at             TIMESTAMPTZ    NOT NULL DEFAULT statement_timestamp(),
    recorded_on             DATE           NOT NULL,
    correlation_id          TEXT           NOT NULL,
    CONSTRAINT cover_execution_pk PRIMARY KEY (cover_id),
    CONSTRAINT cover_execution_provider_trade_once UNIQUE (provider_code, provider_trade_ref),
    CONSTRAINT cover_execution_entry_unique UNIQUE (journal_entry_id),
    CONSTRAINT cover_execution_cover_fk FOREIGN KEY (cover_id) REFERENCES fx.cover (id),
    CONSTRAINT cover_execution_attempt_fk FOREIGN KEY (cover_id, attempt) REFERENCES fx.cover_attempt (cover_id, attempt),
    CONSTRAINT cover_execution_reference_fk FOREIGN KEY (client_reference) REFERENCES fx.cover_attempt (client_reference),
    CONSTRAINT cover_execution_trade_ref_bounded CHECK (char_length(provider_trade_ref) BETWEEN 1 AND 64),
    CONSTRAINT cover_execution_fixed_side_is_known CHECK (fixed_side IN ('FIXED_SOURCE', 'FIXED_DESTINATION')),
    CONSTRAINT cover_execution_converts CHECK (sold_currency <> bought_currency),
    CONSTRAINT cover_execution_amounts_positive CHECK (sold_minor > 0 AND bought_minor > 0
        AND plan_sold_minor > 0 AND plan_bought_minor > 0),
    CONSTRAINT cover_execution_rate_positive CHECK (executed_rate > 0),
    CONSTRAINT cover_execution_sold_scale_is_the_currency_s CHECK (sold_scale = CASE sold_currency WHEN 'JPY' THEN 0 WHEN 'BHD' THEN 3 ELSE 2 END),
    CONSTRAINT cover_execution_bought_scale_is_the_currency_s CHECK (bought_scale = CASE bought_currency WHEN 'JPY' THEN 0 WHEN 'BHD' THEN 3 ELSE 2 END),
    CONSTRAINT cover_execution_realised_is_the_difference CHECK (
        realised_sold_minor = plan_sold_minor - sold_minor
        AND realised_bought_minor = bought_minor - plan_bought_minor),
    CONSTRAINT cover_execution_off_plan_is_the_fixed_leg CHECK (executed_off_plan = CASE fixed_side
        WHEN 'FIXED_SOURCE' THEN sold_minor <> plan_sold_minor
        ELSE bought_minor <> plan_bought_minor END),
    CONSTRAINT cover_execution_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

COMMENT ON TABLE fx.cover_execution IS
    'A cover''s one execution (P9-TSK-012, ADR-0077 section 5): the arbiter - PK cover_id, UNIQUE (provider_code, provider_trade_ref), UNIQUE journal_entry_id beside the posting key fx-cover:<coverId> -, the plan''s legs copied from the quote and checked at birth, the realised result per leg and the off-plan flag by CHECK. Append-only but for the entry, attached once.';

CREATE OR REPLACE FUNCTION fx.cover_execution_is_born_coherent()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    c fx.cover%ROWTYPE;
    q fx.quote%ROWTYPE;
    reference TEXT;
BEGIN
    IF NEW.journal_entry_id IS NOT NULL THEN
        RAISE EXCEPTION 'a cover execution is born without its entry, attached after (P9-TSK-012)';
    END IF;
    NEW.recorded_at := statement_timestamp();
    NEW.recorded_on := (statement_timestamp() AT TIME ZONE 'UTC')::date;
    SELECT * INTO c FROM fx.cover WHERE id = NEW.cover_id;
    IF NOT FOUND OR c.status NOT IN ('DISPATCHED', 'UNKNOWN') OR c.attempts <> NEW.attempt THEN
        RAISE EXCEPTION 'a cover executes while DISPATCHED or UNKNOWN, at its current attempt (P9-TSK-012, INV-FX-08)';
    END IF;
    IF c.kind <> 'COVER' THEN
        RAISE EXCEPTION 'an unwind''s execution arrives with the unwind (P9-TSK-021)';
    END IF;
    SELECT a.client_reference INTO reference FROM fx.cover_attempt a WHERE a.cover_id = c.id AND a.attempt = c.attempts;
    IF reference IS DISTINCT FROM NEW.client_reference THEN
        RAISE EXCEPTION 'a cover execution names its attempt''s own reference (P9-TSK-012, INV-PAY-04)';
    END IF;
    IF c.provider_code <> NEW.provider_code OR c.fixed_side <> NEW.fixed_side
        OR c.source_currency <> NEW.sold_currency OR c.destination_currency <> NEW.bought_currency THEN
        RAISE EXCEPTION 'a cover execution is the cover''s: its provider, its fixed side and its currencies (P9-TSK-012)';
    END IF;
    SELECT * INTO q FROM fx.quote WHERE id = c.quote_id;
    IF q.position_source_minor <> NEW.plan_sold_minor OR q.position_destination_minor <> NEW.plan_bought_minor THEN
        RAISE EXCEPTION 'a cover closes exactly its plan''s position legs (P9-TSK-012, INV-FX-08)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER cover_execution_is_born_coherent
    BEFORE INSERT ON fx.cover_execution
    FOR EACH ROW
    EXECUTE FUNCTION fx.cover_execution_is_born_coherent();

CREATE OR REPLACE FUNCTION fx.cover_execution_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a cover execution is never deleted (P9-TSK-012)';
    END IF;
    IF (to_jsonb(NEW) - 'journal_entry_id') IS DISTINCT FROM (to_jsonb(OLD) - 'journal_entry_id')
        OR OLD.journal_entry_id IS NOT NULL OR NEW.journal_entry_id IS NULL THEN
        RAISE EXCEPTION 'a cover execution is append-only; its entry is attached once (P9-TSK-012)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER cover_execution_is_append_only
    BEFORE UPDATE OR DELETE ON fx.cover_execution
    FOR EACH ROW
    EXECUTE FUNCTION fx.cover_execution_is_append_only();

-- A cover execution never commits without its entry.
CREATE OR REPLACE FUNCTION fx.cover_execution_has_its_entry()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM fx.cover_execution WHERE cover_id = NEW.cover_id AND journal_entry_id IS NULL) THEN
        RAISE EXCEPTION 'a cover execution commits only with its entry fx-cover:<coverId> (P9-TSK-012, INV-FX-06)';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER cover_execution_has_its_entry
    AFTER INSERT ON fx.cover_execution
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION fx.cover_execution_has_its_entry();

-- The sweeper's reach: the non-terminal covers by their permit.
CREATE INDEX cover_open_by_permit ON fx.cover (last_dispatched_at)
    WHERE status IN ('DISPATCHED', 'UNKNOWN', 'REJECTED');

GRANT SELECT, INSERT ON fx.cover_execution TO finapp_app;
GRANT UPDATE (journal_entry_id) ON fx.cover_execution TO finapp_app;
GRANT UPDATE (requote_failures) ON fx.cover TO finapp_app;
