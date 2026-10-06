-- =============================================================================================
-- P9-TSK-021 - the unwind (ADR-0077 sections 7 and 8, PHASE_9_PLAN.md sections 12.4(h) and 12.5;
-- INV-FX-06, INV-FX-08, INV-XB-01's failure half).
--
-- An UNWIND is a cover whose plan is its quote's plan reversed: it buys back exactly what the executed
-- COVER sold, at a fresh firm quote from the same provider - source and destination swapped, the fixed
-- side opposite, the fixed amount the cover's. No new table: this migration teaches the two triggers
-- that knew only the COVER what an unwind is, for every writer.
--
--   * fx.cover_unwind_mirrors_its_cover (new, BEFORE INSERT): an UNWIND is born only for a quote that
--     no longer wants its cover (neither ACCEPTED nor EXECUTED, or its trade REVERSED) whose COVER has
--     EXECUTED, and only as that cover's mirror. UNIQUE (quote_id, kind) - V006 - makes it exactly one.
--     It is born DISPATCHED at attempt 1 like every cover; its attempt 1 - our reference T1 and the
--     fresh firm quote it executes - is stored by its first dispatch, before any send.
--   * fx.cover_execution_is_born_coherent (replaced): V007 refused every unwind's execution ("an
--     unwind's execution arrives with the unwind"); an unwind now executes at its current attempt with
--     its plan legs judged reversed - it sells the quote's destination position and buys back its source.
-- =============================================================================================

CREATE OR REPLACE FUNCTION fx.cover_unwind_mirrors_its_cover()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    q fx.quote%ROWTYPE;
    c fx.cover%ROWTYPE;
    trade_status TEXT;
BEGIN
    IF NEW.kind <> 'UNWIND' THEN
        RETURN NEW;
    END IF;
    SELECT * INTO q FROM fx.quote WHERE id = NEW.quote_id;
    SELECT status INTO trade_status FROM fx.trade WHERE quote_id = NEW.quote_id;
    IF q.status IN ('ACCEPTED', 'EXECUTED') AND trade_status IS DISTINCT FROM 'REVERSED' THEN
        RAISE EXCEPTION 'an unwind is born only for a quote that no longer wants its cover (P9-TSK-021, ADR-0077 section 7)';
    END IF;
    SELECT * INTO c FROM fx.cover WHERE quote_id = NEW.quote_id AND kind = 'COVER';
    IF NOT FOUND OR c.status <> 'EXECUTED' THEN
        RAISE EXCEPTION 'an unwind unwinds an executed cover (P9-TSK-021, ADR-0077 section 7)';
    END IF;
    IF NEW.provider_code <> c.provider_code
        OR NEW.source_currency <> c.destination_currency OR NEW.destination_currency <> c.source_currency
        OR NEW.fixed_side = c.fixed_side
        OR NEW.fixed_amount_minor <> c.fixed_amount_minor OR NEW.fixed_scale <> c.fixed_scale THEN
        RAISE EXCEPTION 'an unwind is its cover''s mirror: the same provider, the currencies swapped, the fixed side opposite, the fixed amount the cover''s (P9-TSK-021, ADR-0077 section 8)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER cover_unwind_mirrors_its_cover
    BEFORE INSERT ON fx.cover
    FOR EACH ROW
    EXECUTE FUNCTION fx.cover_unwind_mirrors_its_cover();

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
    SELECT a.client_reference INTO reference FROM fx.cover_attempt a WHERE a.cover_id = c.id AND a.attempt = c.attempts;
    IF reference IS DISTINCT FROM NEW.client_reference THEN
        RAISE EXCEPTION 'a cover execution names its attempt''s own reference (P9-TSK-012, INV-PAY-04)';
    END IF;
    IF c.provider_code <> NEW.provider_code OR c.fixed_side <> NEW.fixed_side
        OR c.source_currency <> NEW.sold_currency OR c.destination_currency <> NEW.bought_currency THEN
        RAISE EXCEPTION 'a cover execution is the cover''s: its provider, its fixed side and its currencies (P9-TSK-012)';
    END IF;
    SELECT * INTO q FROM fx.quote WHERE id = c.quote_id;
    IF c.kind = 'COVER'
        AND (q.position_source_minor <> NEW.plan_sold_minor OR q.position_destination_minor <> NEW.plan_bought_minor) THEN
        RAISE EXCEPTION 'a cover closes exactly its plan''s position legs (P9-TSK-012, INV-FX-08)';
    END IF;
    IF c.kind = 'UNWIND'
        AND (q.position_destination_minor <> NEW.plan_sold_minor OR q.position_source_minor <> NEW.plan_bought_minor) THEN
        RAISE EXCEPTION 'an unwind closes exactly its cover''s position legs, reversed (P9-TSK-021, INV-FX-06)';
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION fx.cover_unwind_mirrors_its_cover() IS
    'An UNWIND is born only for a quote that no longer wants its cover, whose COVER executed, and only as that cover''s mirror (P9-TSK-021, ADR-0077 sections 7 and 8).';
