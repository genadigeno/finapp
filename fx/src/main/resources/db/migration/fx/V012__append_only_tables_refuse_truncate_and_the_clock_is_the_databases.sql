-- The owner cannot empty a quote, a trade and a cover as they were made in one statement; fx's clock is the database's whatever a role's search_path holds (P15-TSK-040; THREAT_MODEL.md T13, T33; INV-HIST-01).
-- finapp-contract: first-conforming-release=ANY; waives=REPLACED_BODY; reason=each body is its latest definition byte for byte but for pg_catalog. before each clock call
--
-- WHY
--   Each of these tables refuses DELETE by a row trigger, and a row trigger does not fire on TRUNCATE. The one role
--   that holds TRUNCATE on them, their owner finapp_migrator, could empty the table in a single statement. A
--   statement-level BEFORE TRUNCATE trigger refuses it, to every role, the owner included, as reconciliation V024 and
--   settlement V019 do.
--   A function of this schema is invoker's rights with no search_path of its own, and it called the clock functions
--   unqualified. PostgreSQL searches pg_catalog first only when the path does not name it: a session whose path puts
--   a schema of its own before pg_catalog resolves statement_timestamp() to that schema's function, so what a
--   judgement reads as now could be moved without touching a row or a rule.
--
-- WHAT CHANGES, AND ONLY THAT
--   19 table(s) gain a statement-level BEFORE TRUNCATE trigger, read from the chain by
--   OwnerReachOverHistoryIsClosedTest: availability_enable_request, cover, cover_attempt, cover_execution,
--   fx_provider_evidence, pair_availability, pricing_pair, pricing_policy_event, pricing_policy_version,
--   provider_availability, quote, quote_event, quote_request, quote_sourcing_step, rate_fetch_permit, rate_snapshot,
--   trade, trade_reversal, trade_reversal_event.
--   8 function(s), 12 clock call(s): each function's latest definition across the whole chain, byte for byte but for
--   pg_catalog. written before each clock call in code, never in a comment or a string. Taken from V002, V005, V006,
--   V007, V009, V010, the last definition of each. CURRENT_TIMESTAMP, CURRENT_DATE and LOCALTIMESTAMP are grammar,
--   not functions, and are never resolved through a path.
--
-- WHAT IT DOES NOT CHANGE
--   No table, column, row or grant, and no existing trigger. It moves no money. No judgement on any path a production
--   role has: finapp_app holds no TRUNCATE and no CREATE on any schema, and the pg_catalog clocks are the ones its
--   path already resolved to. CREATE OR REPLACE keeps each function's OID, so every trigger stays bound.
--
-- WHAT IT DOES NOT CLAIM
--   A trigger is an object its owner can drop or disable, and a body can be replaced: this closes the unreviewed
--   path, a TRUNCATE that happens to be run or a role's path, not a deliberate migration, which is reviewed and
--   recorded in the history.
--
-- LOCKS
--   CREATE TRIGGER takes SHARE ROW EXCLUSIVE on each table, held to this transaction's end, which blocks writers for
--   the length of a catalogue change. The migrator's session bounds the wait with lock_timeout and fails rather than
--   queues (DATA_MIGRATIONS.md section 2.1).

CREATE OR REPLACE FUNCTION fx.history_is_never_truncated()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'fx.% is never truncated (P15-TSK-040, INV-HIST-01): a recorded fact is corrected forward, never erased', TG_TABLE_NAME;
END;
$$;

COMMENT ON FUNCTION fx.history_is_never_truncated() IS
    'Refuses TRUNCATE on an append-only fx table, the owner included (P15-TSK-040, INV-HIST-01). A row trigger does not fire on TRUNCATE.';

CREATE TRIGGER availability_enable_request_is_never_truncated
    BEFORE TRUNCATE ON fx.availability_enable_request
    FOR EACH STATEMENT
    EXECUTE FUNCTION fx.history_is_never_truncated();

CREATE TRIGGER cover_is_never_truncated
    BEFORE TRUNCATE ON fx.cover
    FOR EACH STATEMENT
    EXECUTE FUNCTION fx.history_is_never_truncated();

CREATE TRIGGER cover_attempt_is_never_truncated
    BEFORE TRUNCATE ON fx.cover_attempt
    FOR EACH STATEMENT
    EXECUTE FUNCTION fx.history_is_never_truncated();

CREATE TRIGGER cover_execution_is_never_truncated
    BEFORE TRUNCATE ON fx.cover_execution
    FOR EACH STATEMENT
    EXECUTE FUNCTION fx.history_is_never_truncated();

CREATE TRIGGER fx_provider_evidence_is_never_truncated
    BEFORE TRUNCATE ON fx.fx_provider_evidence
    FOR EACH STATEMENT
    EXECUTE FUNCTION fx.history_is_never_truncated();

CREATE TRIGGER pair_availability_is_never_truncated
    BEFORE TRUNCATE ON fx.pair_availability
    FOR EACH STATEMENT
    EXECUTE FUNCTION fx.history_is_never_truncated();

CREATE TRIGGER pricing_pair_is_never_truncated
    BEFORE TRUNCATE ON fx.pricing_pair
    FOR EACH STATEMENT
    EXECUTE FUNCTION fx.history_is_never_truncated();

CREATE TRIGGER pricing_policy_event_is_never_truncated
    BEFORE TRUNCATE ON fx.pricing_policy_event
    FOR EACH STATEMENT
    EXECUTE FUNCTION fx.history_is_never_truncated();

CREATE TRIGGER pricing_policy_version_is_never_truncated
    BEFORE TRUNCATE ON fx.pricing_policy_version
    FOR EACH STATEMENT
    EXECUTE FUNCTION fx.history_is_never_truncated();

CREATE TRIGGER provider_availability_is_never_truncated
    BEFORE TRUNCATE ON fx.provider_availability
    FOR EACH STATEMENT
    EXECUTE FUNCTION fx.history_is_never_truncated();

CREATE TRIGGER quote_is_never_truncated
    BEFORE TRUNCATE ON fx.quote
    FOR EACH STATEMENT
    EXECUTE FUNCTION fx.history_is_never_truncated();

CREATE TRIGGER quote_event_is_never_truncated
    BEFORE TRUNCATE ON fx.quote_event
    FOR EACH STATEMENT
    EXECUTE FUNCTION fx.history_is_never_truncated();

CREATE TRIGGER quote_request_is_never_truncated
    BEFORE TRUNCATE ON fx.quote_request
    FOR EACH STATEMENT
    EXECUTE FUNCTION fx.history_is_never_truncated();

CREATE TRIGGER quote_sourcing_step_is_never_truncated
    BEFORE TRUNCATE ON fx.quote_sourcing_step
    FOR EACH STATEMENT
    EXECUTE FUNCTION fx.history_is_never_truncated();

CREATE TRIGGER rate_fetch_permit_is_never_truncated
    BEFORE TRUNCATE ON fx.rate_fetch_permit
    FOR EACH STATEMENT
    EXECUTE FUNCTION fx.history_is_never_truncated();

CREATE TRIGGER rate_snapshot_is_never_truncated
    BEFORE TRUNCATE ON fx.rate_snapshot
    FOR EACH STATEMENT
    EXECUTE FUNCTION fx.history_is_never_truncated();

CREATE TRIGGER trade_is_never_truncated
    BEFORE TRUNCATE ON fx.trade
    FOR EACH STATEMENT
    EXECUTE FUNCTION fx.history_is_never_truncated();

CREATE TRIGGER trade_reversal_is_never_truncated
    BEFORE TRUNCATE ON fx.trade_reversal
    FOR EACH STATEMENT
    EXECUTE FUNCTION fx.history_is_never_truncated();

CREATE TRIGGER trade_reversal_event_is_never_truncated
    BEFORE TRUNCATE ON fx.trade_reversal_event
    FOR EACH STATEMENT
    EXECUTE FUNCTION fx.history_is_never_truncated();

-- fx.cover_execution_is_born_coherent: V010's definition, 2 clock call(s) qualified.
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
    NEW.recorded_at := pg_catalog.statement_timestamp();
    NEW.recorded_on := (pg_catalog.statement_timestamp() AT TIME ZONE 'UTC')::date;
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

-- fx.cover_machine_is_legal: V007's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION fx.cover_machine_is_legal()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    now TIMESTAMPTZ := pg_catalog.statement_timestamp();
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

-- fx.quote_edge_is_legal: V006's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION fx.quote_edge_is_legal()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    now TIMESTAMPTZ := pg_catalog.statement_timestamp();
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a quote is never deleted (P9-TSK-008)';
    END IF;
    IF (to_jsonb(NEW) - 'status' - 'closed_at') IS DISTINCT FROM (to_jsonb(OLD) - 'status' - 'closed_at') THEN
        RAISE EXCEPTION 'a quote is a frozen plan: only its status moves (P9-TSK-008, INV-FX-04)';
    END IF;
    IF NOT (
        (OLD.status = 'ISSUED' AND NEW.status IN ('ACCEPTED', 'CANCELLED') AND OLD.expires_at > now)
        OR (OLD.status = 'ISSUED' AND NEW.status = 'EXPIRED' AND OLD.expires_at <= now)
        OR (OLD.status = 'ACCEPTED' AND NEW.status = 'ABANDONED')
        OR (OLD.status = 'ACCEPTED' AND NEW.status = 'EXECUTED'
            AND EXISTS (SELECT 1 FROM fx.trade t WHERE t.quote_id = OLD.id))) THEN
        RAISE EXCEPTION 'a quote moves % -> % only along its machine, on the database clock (P9-TSK-008, P9-TSK-009)', OLD.status, NEW.status;
    END IF;
    NEW.closed_at := CASE WHEN NEW.status IN ('EXECUTED', 'ABANDONED', 'EXPIRED', 'CANCELLED') THEN now END;
    RETURN NEW;
END;
$$;

-- fx.quote_is_born_issued: V005's definition, 2 clock call(s) qualified.
CREATE OR REPLACE FUNCTION fx.quote_is_born_issued()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    request fx.quote_request%ROWTYPE;
    terms   fx.pricing_pair%ROWTYPE;
    cap     INTEGER;
    live    INTEGER;
BEGIN
    IF NEW.status <> 'ISSUED' OR NEW.closed_at IS NOT NULL THEN
        RAISE EXCEPTION 'a quote is born ISSUED (P9-TSK-008)';
    END IF;
    NEW.issued_at := pg_catalog.statement_timestamp();
    -- The window is computed HERE, for every writer: no caller supplies an expiry.
    NEW.expires_at := LEAST(
        NEW.requested_at + NEW.provider_valid_for_ms * INTERVAL '1 millisecond' - NEW.cover_margin_seconds * INTERVAL '1 second',
        NEW.issued_at + NEW.window_seconds * INTERVAL '1 second');
    SELECT * INTO request FROM fx.quote_request WHERE id = NEW.quote_request_id;
    IF NOT FOUND
        OR request.owner_party_id <> NEW.owner_party_id
        OR request.purpose <> NEW.purpose
        OR request.source_currency <> NEW.source_currency
        OR request.destination_currency <> NEW.destination_currency
        OR request.fixed_side <> NEW.fixed_side
        OR request.pricing_policy_version_id <> NEW.pricing_policy_version_id
        OR request.requested_at <> NEW.requested_at
        OR request.fixed_amount_minor <> (CASE NEW.fixed_side WHEN 'FIXED_SOURCE' THEN NEW.customer_source_minor ELSE NEW.customer_destination_minor END) THEN
        RAISE EXCEPTION 'a quote carries its request''s owner, pair, fixed leg, pinned version and requested_at (P9-TSK-008)';
    END IF;
    SELECT * INTO terms FROM fx.pricing_pair
        WHERE policy_id = NEW.pricing_policy_version_id
          AND source_currency = NEW.source_currency
          AND destination_currency = NEW.destination_currency
          AND purpose = NEW.purpose;
    IF NOT FOUND
        OR terms.spread <> NEW.spread OR terms.markup <> NEW.markup
        OR terms.rate_scale <> NEW.rate_scale
        OR terms.rate_rounding <> NEW.rate_rounding
        OR terms.amount_rounding <> NEW.amount_rounding
        OR terms.margin_rounding <> NEW.margin_rounding
        OR terms.window_seconds <> NEW.window_seconds
        OR terms.cover_margin_seconds <> NEW.cover_margin_seconds
        OR NOT (NEW.provider_code = ANY (terms.providers)) THEN
        RAISE EXCEPTION 'a quote is priced under its pinned version''s own terms and providers (P9-TSK-008, INV-HIST-04)';
    END IF;
    PERFORM pg_advisory_xact_lock(5, hashtext(NEW.owner_party_id::text));
    SELECT open_quote_cap INTO cap FROM fx.pricing_policy_version WHERE id = NEW.pricing_policy_version_id;
    SELECT count(*) INTO live FROM fx.quote
        WHERE owner_party_id = NEW.owner_party_id
          AND status = 'ISSUED'
          AND expires_at > pg_catalog.statement_timestamp();
    IF live >= cap THEN
        RAISE EXCEPTION 'the owner already holds % live quotes, the cap (P9-TSK-008)', live
            USING ERRCODE = 'FXCAP';
    END IF;
    RETURN NEW;
END;
$$;

-- fx.quote_request_is_stamped_and_append_only: V005's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION fx.quote_request_is_stamped_and_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a quote request is append-only (P9-TSK-008)';
    END IF;
    NEW.requested_at := pg_catalog.statement_timestamp();
    RETURN NEW;
END;
$$;

-- fx.rate_snapshot_is_newer_than_latest: V002's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION fx.rate_snapshot_is_newer_than_latest()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    -- Namespace 6: every writer of one (source, pair) serialises here, raw SQL included.
    PERFORM pg_advisory_xact_lock(
        6, hashtext(NEW.source || ':' || NEW.base_currency || '/' || NEW.quote_currency));
    -- The database's clock, whatever the writer supplied.
    NEW.received_at := pg_catalog.statement_timestamp();
    IF EXISTS (
            SELECT 1 FROM fx.rate_snapshot s
             WHERE s.source = NEW.source
               AND s.base_currency = NEW.base_currency
               AND s.quote_currency = NEW.quote_currency
               AND s.observed_at >= NEW.observed_at) THEN
        -- Equal (a duplicate) or older (a replay): not stored. Silent by design - a stuck feed
        -- is not an error to the writer, it is a pair whose latest row ages (the age gauge).
        RETURN NULL;
    END IF;
    RETURN NEW;
END;
$$;

-- fx.trade_is_born_booked: V006's definition, 2 clock call(s) qualified.
CREATE OR REPLACE FUNCTION fx.trade_is_born_booked()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    q fx.quote%ROWTYPE;
BEGIN
    IF NEW.status <> 'BOOKED' OR NEW.journal_entry_id IS NOT NULL THEN
        RAISE EXCEPTION 'a trade is born BOOKED, its entry attached after (P9-TSK-009)';
    END IF;
    NEW.booked_at := pg_catalog.statement_timestamp();
    NEW.booked_on := (pg_catalog.statement_timestamp() AT TIME ZONE 'UTC')::date;
    SELECT * INTO q FROM fx.quote WHERE id = NEW.quote_id;
    IF NOT FOUND OR q.status <> 'ACCEPTED' THEN
        RAISE EXCEPTION 'a trade books an ACCEPTED quote (P9-TSK-009, INV-FX-04)';
    END IF;
    IF q.owner_party_id <> NEW.owner_party_id OR q.purpose <> NEW.purpose
        OR q.source_currency <> NEW.source_currency OR q.destination_currency <> NEW.destination_currency
        OR q.fixed_side <> NEW.fixed_side OR q.pricing_policy_version_id <> NEW.pricing_policy_version_id
        OR q.provider_code <> NEW.provider_code OR q.customer_rate <> NEW.customer_rate
        OR q.source_scale <> NEW.source_scale OR q.destination_scale <> NEW.destination_scale
        OR q.customer_source_minor <> NEW.customer_source_minor
        OR q.customer_destination_minor <> NEW.customer_destination_minor
        OR q.position_source_minor <> NEW.position_source_minor
        OR q.position_destination_minor <> NEW.position_destination_minor
        OR q.margin_minor <> NEW.margin_minor OR q.spread_margin_minor <> NEW.spread_margin_minor
        OR q.markup_margin_minor <> NEW.markup_margin_minor OR q.residual_minor <> NEW.residual_minor THEN
        RAISE EXCEPTION 'a trade posts exactly its quote''s plan - no re-pricing at execution (P9-TSK-009, INV-FX-04)';
    END IF;
    RETURN NEW;
END;
$$;

-- fx.trade_reversal_edge_is_legal: V009's definition, 2 clock call(s) qualified.
CREATE OR REPLACE FUNCTION fx.trade_reversal_edge_is_legal() RETURNS trigger
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
        NEW.proposed_at := pg_catalog.statement_timestamp();
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
    NEW.decided_at := pg_catalog.statement_timestamp();
    RETURN NEW;
END;
$$;
