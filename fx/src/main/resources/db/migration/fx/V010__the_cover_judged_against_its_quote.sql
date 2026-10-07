-- =============================================================================================
-- The Phase 9 to 10 transition: the cover judged against the firm quote it executed under, and the
-- COVER's birth checked against its quote (ADR-0077 sections 3, 5 and 6; INV-FX-06, INV-FX-08).
--
-- THE GAP (found by the transition gate). V007 checks an execution's currencies, scales and positivity,
-- and flags executed_off_plan on the FIXED leg only. The COMPUTED leg and the executed rate were booked
-- from the provider with no comparison to the firm quote the attempt was executed under: a FIXED_SOURCE
-- EUR 1,000.00 whose firm counter was USD 1,085.02, executed at USD 1,075.00, posted FX_REALISED_LOSSES
-- 10.02 with no alert, the books proof clean - the provider's word, silently. A requote stored only the
-- fresh quote's reference, never the counter it stated, so a later attempt had nothing to be judged by.
--
-- THE REPAIR. Money is still booked exactly as the provider executed it (its confirmation is the truth
-- the leg expectations reconcile against), but never silently:
--   * fx.cover_attempt.stated_counter_minor - the firm quote's stated counter in the computed leg's
--     currency, stored with the attempt BEFORE any send. Required (for every writer, by trigger) for
--     every attempt priced by a fresh firm quote: a requote (attempt > 1) and every attempt of an
--     UNWIND. A COVER's attempt 1 executes its quote's own plan, whose computed position leg IS the
--     provider's stated counter (ADR-0074 section 3), so it stores none.
--   * fx.cover_execution.quoted_computed_minor - the computed leg the attempt was quoted at: the
--     attempt's stated counter, else (a COVER's attempt 1) the plan's computed leg; checked at birth
--     to be exactly that for every writer (NULL only for an attempt stored before this migration).
--   * fx.cover_execution.computed_deviation - by CHECK, whether the executed computed leg differs from
--     quoted_computed_minor. It is NOT executed_off_plan: that flag is the fixed leg's by V007's CHECK
--     and ADR-0077 section 6's definition (the provider changed what we asked for); a computed
--     deviation is the provider executing at another price than its own firm quote. Two different
--     contract breaches, two flags, two meter outcomes (off_plan, computed_deviation), both alerting.
--   * fx.cover_execution.executed_rate_coherent - whether the provider's executed rate, for the pair
--     sold -> bought, explains its executed amounts within one minor unit of the computed leg (the
--     plan's own coherence rule, ConversionPlan). Judged by the applier, required at birth; NULL only
--     for a row recorded before this migration.
--
-- THE COVER'S BIRTH (V006/V007 left it unchecked): fx.cover_is_born_from_its_quote - a COVER is born
-- only for a quote that wants it (ACCEPTED, or EXECUTED with its trade BOOKED - the conversion writes
-- the cover after executing the quote, the cross-border acceptance before booking any trade) and only
-- as that quote's exposure: its provider, its currencies, its fixed side, and the plan's position leg on
-- the fixed side as its fixed amount and scale. An UNWIND's birth stays V008's mirror trigger's.
-- =============================================================================================

-- ------------------------------------------------------------------ the attempt's stated counter

ALTER TABLE fx.cover_attempt
    ADD COLUMN stated_counter_minor BIGINT,
    ADD CONSTRAINT cover_attempt_stated_counter_positive CHECK (stated_counter_minor IS NULL OR stated_counter_minor > 0);

COMMENT ON COLUMN fx.cover_attempt.stated_counter_minor IS
    'The fresh firm quote''s stated counter, in the computed leg''s currency and scale, stored before any send - required for a requote and for every unwind attempt; NULL for a COVER''s attempt 1, which executes its quote''s plan (the Phase 9 to 10 transition).';

CREATE OR REPLACE FUNCTION fx.cover_attempt_states_its_counter()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    kind TEXT;
BEGIN
    SELECT c.kind INTO kind FROM fx.cover c WHERE c.id = NEW.cover_id;
    IF (NEW.attempt > 1 OR kind = 'UNWIND') AND NEW.stated_counter_minor IS NULL THEN
        RAISE EXCEPTION 'an attempt priced by a fresh firm quote stores the counter it stated (the Phase 9 to 10 transition, INV-FX-08)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER cover_attempt_states_its_counter
    BEFORE INSERT ON fx.cover_attempt
    FOR EACH ROW
    EXECUTE FUNCTION fx.cover_attempt_states_its_counter();

-- ------------------------------------------------------------------ the execution judged against its quote

ALTER TABLE fx.cover_execution
    ADD COLUMN quoted_computed_minor BIGINT,
    ADD COLUMN computed_deviation BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN executed_rate_coherent BOOLEAN,
    ADD CONSTRAINT cover_execution_quoted_computed_positive CHECK (quoted_computed_minor IS NULL OR quoted_computed_minor > 0),
    ADD CONSTRAINT cover_execution_deviation_is_the_computed_leg CHECK (computed_deviation = (
        quoted_computed_minor IS NOT NULL AND quoted_computed_minor <> CASE fixed_side
            WHEN 'FIXED_SOURCE' THEN bought_minor
            ELSE sold_minor END));

COMMENT ON COLUMN fx.cover_execution.quoted_computed_minor IS
    'The computed leg the attempt was quoted at - its stated counter, else a COVER''s attempt 1''s plan leg - checked at birth (the Phase 9 to 10 transition).';
COMMENT ON COLUMN fx.cover_execution.computed_deviation IS
    'Whether the executed computed leg differs from the firm quote it was executed under, by CHECK - booked as executed, counted and alerted; distinct from executed_off_plan, the fixed leg''s flag.';
COMMENT ON COLUMN fx.cover_execution.executed_rate_coherent IS
    'Whether the provider''s executed rate, sold -> bought, explains its executed amounts within one minor unit of the computed leg; required at birth, NULL only before the Phase 9 to 10 transition.';

CREATE OR REPLACE FUNCTION fx.cover_execution_is_born_coherent()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    c fx.cover%ROWTYPE;
    q fx.quote%ROWTYPE;
    reference TEXT;
    stated BIGINT;
    expected BIGINT;
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
    SELECT a.client_reference, a.stated_counter_minor INTO reference, stated
        FROM fx.cover_attempt a WHERE a.cover_id = c.id AND a.attempt = c.attempts;
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
    -- The firm quote it executed under: the stated counter of the attempt, else the own plan of a COVER at attempt 1.
    expected := CASE
        WHEN stated IS NOT NULL THEN stated
        WHEN c.kind = 'COVER' AND NEW.attempt = 1 THEN
            CASE NEW.fixed_side WHEN 'FIXED_SOURCE' THEN NEW.plan_bought_minor ELSE NEW.plan_sold_minor END
        END;
    IF NEW.quoted_computed_minor IS DISTINCT FROM expected THEN
        RAISE EXCEPTION 'a cover execution is judged against the firm quote its attempt executed under (the Phase 9 to 10 transition, INV-FX-08)';
    END IF;
    IF NEW.executed_rate_coherent IS NULL THEN
        RAISE EXCEPTION 'a cover execution records whether its executed rate explains its amounts (the Phase 9 to 10 transition)';
    END IF;
    RETURN NEW;
END;
$$;

-- ------------------------------------------------------------------ the birth of a COVER

CREATE OR REPLACE FUNCTION fx.cover_is_born_from_its_quote()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    q fx.quote%ROWTYPE;
    trade_status TEXT;
BEGIN
    IF NEW.kind <> 'COVER' THEN
        RETURN NEW;
    END IF;
    SELECT * INTO q FROM fx.quote WHERE id = NEW.quote_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'a cover is born for its quote (the Phase 9 to 10 transition, ADR-0077)';
    END IF;
    SELECT t.status INTO trade_status FROM fx.trade t WHERE t.quote_id = NEW.quote_id;
    IF NOT (q.status = 'ACCEPTED' OR (q.status = 'EXECUTED' AND trade_status = 'BOOKED')) THEN
        RAISE EXCEPTION 'a cover is born only for a quote that wants it: ACCEPTED, or EXECUTED with its trade BOOKED (the Phase 9 to 10 transition, ADR-0077 section 7)';
    END IF;
    IF NEW.provider_code <> q.provider_code
        OR NEW.source_currency <> q.source_currency OR NEW.destination_currency <> q.destination_currency
        OR NEW.fixed_side <> q.fixed_side
        -- Parenthesised: PL/pgSQL reads an IF condition up to its first THEN outside parentheses.
        OR NEW.fixed_amount_minor <> (CASE q.fixed_side WHEN 'FIXED_SOURCE' THEN q.position_source_minor ELSE q.position_destination_minor END)
        OR NEW.fixed_scale <> (CASE q.fixed_side WHEN 'FIXED_SOURCE' THEN q.source_scale ELSE q.destination_scale END) THEN
        RAISE EXCEPTION 'a cover is its quote''s exposure: its provider, its currencies, its fixed side and the plan''s fixed position leg (the Phase 9 to 10 transition, ADR-0077 section 3)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER cover_is_born_from_its_quote
    BEFORE INSERT ON fx.cover
    FOR EACH ROW
    EXECUTE FUNCTION fx.cover_is_born_from_its_quote();

COMMENT ON FUNCTION fx.cover_is_born_from_its_quote() IS
    'A COVER is born only for a quote that wants it, and only as its exposure: provider, currencies, fixed side and the plan''s fixed position leg (the Phase 9 to 10 transition, ADR-0077).';
