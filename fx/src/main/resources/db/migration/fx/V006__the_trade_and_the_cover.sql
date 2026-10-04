-- P9-TSK-009 (ADR-0076 sections 1-2, ADR-0077; INV-FX-01, INV-FX-04, INV-FX-05, INV-FX-07,
-- INV-FX-08): the wallet conversion's booked trade, and the cover it wants.
--
-- THE TRADE is the quote's frozen plan, executed once (ADR-0076 section 1). It is born BOOKED in
-- the conversion's own transaction (T-a, PHASE_9_PLAN.md section 7), beside its entry
-- fx-trade:<tradeId>:
--   * UNIQUE (quote_id): a plan is executable at most once (INV-FX-04);
--   * executed_rate = customer_rate by CHECK, and every copied column must EQUAL the quote's,
--     whose status must be ACCEPTED, by the birth trigger - no re-pricing at execution;
--   * the plan identity and the residual bound repeated as CHECKs, for every writer;
--   * booked_at and booked_on are the database's; the entry's posting and value date are the
--     stored booked_on;
--   * journal_entry_id is set once, NULL -> the entry, in the same transaction, and a DEFERRED
--     constraint trigger refuses a commit that leaves a trade without its entry;
--   * frozen otherwise; BOOKED -> REVERSED is the one edge (its producer, the four-eyes reversal,
--     is P9-TSK-025's).
--
-- THE COVER (ADR-0077): one of kind COVER per accepted quote - born in the same transaction,
-- DISPATCHED, with attempt 1's client reference T1 minted and stored BEFORE anything is ever sent
-- (UNIQUE NOT NULL; INV-PAY-04, INV-FX-08) and the first send permit stamped by the database.
-- The full machine is held here for every writer:
--     DISPATCHED --confirmed--> EXECUTED          UNKNOWN --inquiry--> EXECUTED | REJECTED
--     DISPATCHED --lost--> UNKNOWN                REJECTED --requote--> DISPATCHED (attempt n+1)
--     DISPATCHED --definitive refusal--> REJECTED REJECTED --no longer wanted--> VOIDED
-- EXECUTED needs the execution fact (fx.cover_execution), which P9-TSK-012 creates with the
-- sender; until then this function refuses it. The permit only moves forward, to the database's
-- statement_timestamp(). One cover per (quote, kind): UNIQUE (quote_id, kind).
--
-- THE QUOTE'S EDGE FUNCTION is replaced: ACCEPTED -> EXECUTED is admitted exactly when the
-- quote's trade exists (the lifecycle document section 3.1).

-- ------------------------------------------------------------------ the trade

CREATE TABLE fx.trade (
    id                          UUID           NOT NULL,
    quote_id                    UUID           NOT NULL,
    owner_party_id              UUID           NOT NULL,
    purpose                     TEXT           NOT NULL,
    source_currency             CHAR(3)        NOT NULL,
    destination_currency        CHAR(3)        NOT NULL,
    fixed_side                  TEXT           NOT NULL,
    pricing_policy_version_id   UUID           NOT NULL,
    provider_code               TEXT           NOT NULL,
    customer_rate               NUMERIC(20,10) NOT NULL,
    executed_rate               NUMERIC(20,10) NOT NULL,
    source_scale                SMALLINT       NOT NULL,
    destination_scale           SMALLINT       NOT NULL,
    customer_source_minor       BIGINT         NOT NULL,
    customer_destination_minor  BIGINT         NOT NULL,
    position_source_minor       BIGINT         NOT NULL,
    position_destination_minor  BIGINT         NOT NULL,
    margin_minor                BIGINT         NOT NULL,
    spread_margin_minor         BIGINT         NOT NULL,
    markup_margin_minor         BIGINT         NOT NULL,
    residual_minor              BIGINT         NOT NULL,
    status                      TEXT           NOT NULL,
    booked_at                   TIMESTAMPTZ    NOT NULL DEFAULT statement_timestamp(),
    booked_on                   DATE           NOT NULL,
    journal_entry_id            UUID,
    correlation_id              TEXT           NOT NULL,
    CONSTRAINT trade_pk PRIMARY KEY (id),
    CONSTRAINT trade_quote_unique UNIQUE (quote_id),
    CONSTRAINT trade_entry_unique UNIQUE (journal_entry_id),
    CONSTRAINT trade_quote_fk FOREIGN KEY (quote_id) REFERENCES fx.quote (id),
    CONSTRAINT trade_purpose_is_known CHECK (purpose IN ('CONVERSION', 'CROSS_BORDER')),
    CONSTRAINT trade_converts CHECK (source_currency <> destination_currency),
    CONSTRAINT trade_fixed_side_is_known CHECK (fixed_side IN ('FIXED_SOURCE', 'FIXED_DESTINATION')),
    CONSTRAINT trade_status_is_known CHECK (status IN ('BOOKED', 'REVERSED')),
    CONSTRAINT trade_executed_at_the_customer_rate CHECK (executed_rate = customer_rate),
    CONSTRAINT trade_source_scale_is_the_currency_s CHECK (source_scale = CASE source_currency WHEN 'JPY' THEN 0 WHEN 'BHD' THEN 3 ELSE 2 END),
    CONSTRAINT trade_destination_scale_is_the_currency_s CHECK (destination_scale = CASE destination_currency WHEN 'JPY' THEN 0 WHEN 'BHD' THEN 3 ELSE 2 END),
    CONSTRAINT trade_amounts_positive CHECK (customer_source_minor > 0 AND customer_destination_minor > 0 AND position_source_minor > 0 AND position_destination_minor > 0),
    CONSTRAINT trade_margin_non_negative CHECK (margin_minor >= 0 AND spread_margin_minor >= 0 AND markup_margin_minor >= 0),
    CONSTRAINT trade_margin_is_its_parts CHECK (margin_minor = spread_margin_minor + markup_margin_minor),
    CONSTRAINT trade_residual_bounded CHECK (residual_minor BETWEEN -2 AND 2),
    CONSTRAINT trade_plan_identity CHECK (
        (fixed_side = 'FIXED_SOURCE'
            AND customer_source_minor = position_source_minor
            AND position_destination_minor = customer_destination_minor + margin_minor + residual_minor)
        OR (fixed_side = 'FIXED_DESTINATION'
            AND customer_destination_minor = position_destination_minor
            AND customer_source_minor = position_source_minor + margin_minor + residual_minor)),
    CONSTRAINT trade_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

COMMENT ON TABLE fx.trade IS
    'The booked FX trade (P9-TSK-009): the quote''s frozen plan executed once (UNIQUE quote_id), at the customer rate by CHECK, its copies equal to the ACCEPTED quote''s by trigger, its entry fx-trade:<id> attached in the same transaction (deferred check).';

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
    NEW.booked_at := statement_timestamp();
    NEW.booked_on := (statement_timestamp() AT TIME ZONE 'UTC')::date;
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

CREATE TRIGGER trade_is_born_booked
    BEFORE INSERT ON fx.trade
    FOR EACH ROW
    EXECUTE FUNCTION fx.trade_is_born_booked();

CREATE OR REPLACE FUNCTION fx.trade_edge_is_legal()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a trade is never deleted (P9-TSK-009)';
    END IF;
    IF (to_jsonb(NEW) - 'status' - 'journal_entry_id') IS DISTINCT FROM (to_jsonb(OLD) - 'status' - 'journal_entry_id') THEN
        RAISE EXCEPTION 'a trade is a frozen plan: only its status moves (P9-TSK-009, INV-FX-04)';
    END IF;
    IF NEW.journal_entry_id IS DISTINCT FROM OLD.journal_entry_id
        AND (OLD.journal_entry_id IS NOT NULL OR NEW.journal_entry_id IS NULL) THEN
        RAISE EXCEPTION 'a trade''s entry is attached once (P9-TSK-009)';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status AND NOT (OLD.status = 'BOOKED' AND NEW.status = 'REVERSED') THEN
        RAISE EXCEPTION 'a trade moves BOOKED -> REVERSED only; % -> % is not an edge (P9-TSK-009)', OLD.status, NEW.status;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trade_edge_is_legal
    BEFORE UPDATE OR DELETE ON fx.trade
    FOR EACH ROW
    EXECUTE FUNCTION fx.trade_edge_is_legal();

-- A trade never commits without its entry.
CREATE OR REPLACE FUNCTION fx.trade_has_its_entry()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM fx.trade WHERE id = NEW.id AND journal_entry_id IS NULL) THEN
        RAISE EXCEPTION 'a trade commits only with its entry fx-trade:<id> (P9-TSK-009, INV-FX-01)';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trade_has_its_entry
    AFTER INSERT ON fx.trade
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION fx.trade_has_its_entry();

CREATE INDEX trade_by_owner ON fx.trade (owner_party_id);

-- ------------------------------------------------------------------ the quote's edges, with the trade

CREATE OR REPLACE FUNCTION fx.quote_edge_is_legal()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    now TIMESTAMPTZ := statement_timestamp();
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

-- ------------------------------------------------------------------ the cover

CREATE TABLE fx.cover (
    id                  UUID         NOT NULL,
    quote_id            UUID         NOT NULL,
    kind                TEXT         NOT NULL,
    status              TEXT         NOT NULL,
    provider_code       TEXT         NOT NULL,
    source_currency     CHAR(3)      NOT NULL,
    destination_currency CHAR(3)     NOT NULL,
    fixed_side          TEXT         NOT NULL,
    fixed_amount_minor  BIGINT       NOT NULL,
    fixed_scale         SMALLINT     NOT NULL,
    attempts            INTEGER      NOT NULL,
    last_dispatched_at  TIMESTAMPTZ  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT statement_timestamp(),
    correlation_id      TEXT         NOT NULL,
    CONSTRAINT cover_pk PRIMARY KEY (id),
    CONSTRAINT cover_one_per_quote_and_kind UNIQUE (quote_id, kind),
    CONSTRAINT cover_quote_fk FOREIGN KEY (quote_id) REFERENCES fx.quote (id),
    CONSTRAINT cover_kind_is_known CHECK (kind IN ('COVER', 'UNWIND')),
    CONSTRAINT cover_status_is_known CHECK (status IN ('DISPATCHED', 'UNKNOWN', 'EXECUTED', 'REJECTED', 'VOIDED')),
    CONSTRAINT cover_converts CHECK (source_currency <> destination_currency),
    CONSTRAINT cover_fixed_side_is_known CHECK (fixed_side IN ('FIXED_SOURCE', 'FIXED_DESTINATION')),
    CONSTRAINT cover_amount_positive CHECK (fixed_amount_minor > 0),
    CONSTRAINT cover_fixed_scale_is_the_currency_s CHECK (fixed_scale = CASE
        CASE fixed_side WHEN 'FIXED_SOURCE' THEN source_currency ELSE destination_currency END
        WHEN 'JPY' THEN 0 WHEN 'BHD' THEN 3 ELSE 2 END),
    CONSTRAINT cover_attempts_positive CHECK (attempts >= 1),
    CONSTRAINT cover_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

COMMENT ON TABLE fx.cover IS
    'The cover a booked quote wants (P9-TSK-009, ADR-0077): one per (quote, kind), born DISPATCHED with attempt 1''s reference stored before any send and the first database-stamped permit; the full machine held by trigger. P9-TSK-012 sends it.';

CREATE OR REPLACE FUNCTION fx.cover_machine_is_legal()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    now TIMESTAMPTZ := statement_timestamp();
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DISPATCHED' OR NEW.attempts <> 1 THEN
            RAISE EXCEPTION 'a cover is born DISPATCHED at attempt 1 (P9-TSK-009, ADR-0077)';
        END IF;
        NEW.last_dispatched_at := now;
        NEW.created_at := now;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a cover is never deleted (P9-TSK-009)';
    END IF;
    IF (to_jsonb(NEW) - 'status' - 'attempts' - 'last_dispatched_at')
            IS DISTINCT FROM (to_jsonb(OLD) - 'status' - 'attempts' - 'last_dispatched_at') THEN
        RAISE EXCEPTION 'a cover''s exposure is frozen (P9-TSK-009)';
    END IF;
    IF NEW.last_dispatched_at IS DISTINCT FROM OLD.last_dispatched_at THEN
        -- The send permit: stamped by the database, strictly forward, for every writer.
        NEW.last_dispatched_at := GREATEST(OLD.last_dispatched_at + INTERVAL '1 microsecond', now);
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
        (OLD.status = 'DISPATCHED' AND NEW.status IN ('UNKNOWN', 'REJECTED'))
        OR (OLD.status = 'UNKNOWN' AND NEW.status = 'REJECTED')
        OR (OLD.status = 'REJECTED' AND NEW.status = 'DISPATCHED' AND NEW.attempts = OLD.attempts + 1)
        OR (OLD.status = 'REJECTED' AND NEW.status = 'VOIDED')) THEN
        RAISE EXCEPTION 'a cover moves % -> % only along its machine; EXECUTED waits for its execution fact (P9-TSK-009, P9-TSK-012)', OLD.status, NEW.status;
    END IF;
    IF NEW.attempts <> OLD.attempts AND NOT (OLD.status = 'REJECTED' AND NEW.status = 'DISPATCHED') THEN
        RAISE EXCEPTION 'a cover''s attempt advances only with a requote (P9-TSK-009, INV-FX-08)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER cover_machine_is_legal
    BEFORE INSERT OR UPDATE OR DELETE ON fx.cover
    FOR EACH ROW
    EXECUTE FUNCTION fx.cover_machine_is_legal();

CREATE TABLE fx.cover_attempt (
    cover_id            UUID         NOT NULL,
    attempt             INTEGER      NOT NULL,
    client_reference    TEXT         NOT NULL,
    provider_quote_ref  TEXT         NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT cover_attempt_pk PRIMARY KEY (cover_id, attempt),
    CONSTRAINT cover_attempt_reference_unique UNIQUE (client_reference),
    CONSTRAINT cover_attempt_cover_fk FOREIGN KEY (cover_id) REFERENCES fx.cover (id),
    CONSTRAINT cover_attempt_positive CHECK (attempt >= 1),
    CONSTRAINT cover_attempt_reference_shape CHECK (client_reference ~ '^T-[0-9a-f]{32}$'),
    CONSTRAINT cover_attempt_provider_reference_bounded CHECK (char_length(provider_quote_ref) BETWEEN 1 AND 64)
);

COMMENT ON TABLE fx.cover_attempt IS
    'One row per cover attempt (P9-TSK-009, ADR-0077): our client reference T, minted and stored before any send (UNIQUE), and the provider quote it executes. Append-only.';

CREATE TRIGGER cover_attempt_is_append_only
    BEFORE UPDATE OR DELETE ON fx.cover_attempt
    FOR EACH ROW
    EXECUTE FUNCTION fx.quote_history_is_append_only();

GRANT SELECT, INSERT ON fx.trade TO finapp_app;
GRANT UPDATE (status, journal_entry_id) ON fx.trade TO finapp_app;
GRANT SELECT, INSERT ON fx.cover TO finapp_app;
GRANT UPDATE (status, attempts, last_dispatched_at) ON fx.cover TO finapp_app;
GRANT SELECT, INSERT ON fx.cover_attempt TO finapp_app;
