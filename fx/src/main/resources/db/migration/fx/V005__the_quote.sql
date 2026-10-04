-- P9-TSK-008 (ADR-0075 sections 3-6, ADR-0076 section 1, ADR-0074; INV-FX-02, INV-FX-04, INV-FX-05,
-- INV-FX-07): the FX quote - a server-authoritative, single-use, frozen posting plan whose expiry is
-- an event.
--
-- CREATION is keyed and two-transaction (PHASE_9_PLAN.md section 12.3): the claim (Tx1) inserts
-- quote_request - our reference QR, requested_at stamped HERE by the database before any provider
-- call, and the PINNED pricing version; the provider is asked with no connection held; Tx2 inserts
-- the quote under that version with its sourcing steps, history and outbox.
--
-- THE QUOTE IS A FROZEN POSTING PLAN (ADR-0076 section 1). Every amount the trade will post is
-- computed once and frozen:
--   * the per-currency plan identity, for every writer:
--       FIXED_SOURCE:      customer_source = position_source
--                          AND position_destination = customer_destination + margin + residual
--       FIXED_DESTINATION: customer_destination = position_destination
--                          AND customer_source = position_source + margin + residual
--   * the residual within +-2 minor units (the universal bound; the domain asserts the policy's);
--   * the freeze trigger: every column but status and closed_at is immutable;
--   * the copied pricing terms must equal the pinned version's pricing_pair row (INV-HIST-04).
--
-- THE WINDOW IS THE DATABASE'S (ADR-0075 section 3): the insert trigger forces issued_at to
-- statement_timestamp() and COMPUTES expires_at = least(requested_at + provider_valid_for -
-- cover_margin, issued_at + window) - no writer supplies an expiry - and a CHECK refuses one less
-- than five seconds after issue. Provider skew and network time can only shorten it.
--
-- THE MACHINE (the lifecycle document section 3.1), held by quote_edge_is_legal for every writer,
-- with complementary clock predicates on the locked row:
--     ISSUED --accept (expires_at > now)--> ACCEPTED --trade--> EXECUTED
--        |                                      \--subject failed--> ABANDONED
--        |--expire (expires_at <= now)--> EXPIRED
--        \--owner cancels (expires_at > now)--> CANCELLED
-- EXECUTED is refused here: no fx.trade exists until P9-TSK-009, which replaces the function with
-- the trade's existence check. Terminal: EXECUTED, ABANDONED, EXPIRED, CANCELLED.
--
-- THE LIVE-QUOTE CAP holds for every writer (ADR-0075 section 6): the insert trigger takes
-- pg_advisory_xact_lock(5, hashtext(owner_party_id)) - advisory namespace 5, which ARBITRATES -
-- and counts status = 'ISSUED' AND expires_at > statement_timestamp() against the pinned version's
-- open_quote_cap, refusing with SQLSTATE FXCAP. A lapsed quote the sweeper has not yet written
-- never counts. Tx1's pre-check only spares provider calls.

-- ------------------------------------------------------------------ the request (the claim's half)

CREATE TABLE fx.quote_request (
    id                         UUID         NOT NULL,
    reference                  TEXT         NOT NULL,
    claim_key                  TEXT         NOT NULL,
    owner_party_id             UUID         NOT NULL,
    purpose                    TEXT         NOT NULL,
    source_currency            CHAR(3)      NOT NULL,
    destination_currency       CHAR(3)      NOT NULL,
    fixed_side                 TEXT         NOT NULL,
    fixed_amount_minor         BIGINT       NOT NULL,
    fixed_scale                SMALLINT     NOT NULL,
    pricing_policy_version_id  UUID         NOT NULL,
    requested_at               TIMESTAMPTZ  NOT NULL DEFAULT statement_timestamp(),
    correlation_id             TEXT         NOT NULL,
    CONSTRAINT quote_request_pk PRIMARY KEY (id),
    CONSTRAINT quote_request_reference_unique UNIQUE (reference),
    CONSTRAINT quote_request_claim_unique UNIQUE (claim_key),
    CONSTRAINT quote_request_policy_fk FOREIGN KEY (pricing_policy_version_id) REFERENCES fx.pricing_policy_version (id),
    CONSTRAINT quote_request_reference_shape CHECK (reference ~ '^QR-[0-9a-f]{32}$'),
    CONSTRAINT quote_request_claim_bounded CHECK (char_length(claim_key) BETWEEN 1 AND 400),
    CONSTRAINT quote_request_purpose_is_known CHECK (purpose IN ('CONVERSION', 'CROSS_BORDER')),
    CONSTRAINT quote_request_converts CHECK (source_currency <> destination_currency),
    CONSTRAINT quote_request_fixed_side_is_known CHECK (fixed_side IN ('FIXED_SOURCE', 'FIXED_DESTINATION')),
    CONSTRAINT quote_request_amount_positive CHECK (fixed_amount_minor > 0),
    CONSTRAINT quote_request_fixed_scale_is_the_currency_s CHECK (fixed_scale = CASE
        CASE fixed_side WHEN 'FIXED_SOURCE' THEN source_currency ELSE destination_currency END
        WHEN 'JPY' THEN 0 WHEN 'BHD' THEN 3 ELSE 2 END),
    CONSTRAINT quote_request_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

COMMENT ON TABLE fx.quote_request IS
    'The quote claim''s durable half (P9-TSK-008): our reference QR, requested_at stamped by the database before any provider call, and the PINNED pricing version. Append-only; a takeover converges on it by claim_key.';

CREATE OR REPLACE FUNCTION fx.quote_request_is_stamped_and_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a quote request is append-only (P9-TSK-008)';
    END IF;
    NEW.requested_at := statement_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER quote_request_is_stamped_and_append_only
    BEFORE INSERT OR UPDATE OR DELETE ON fx.quote_request
    FOR EACH ROW
    EXECUTE FUNCTION fx.quote_request_is_stamped_and_append_only();

-- ------------------------------------------------------------------ the sourcing steps

CREATE TABLE fx.quote_sourcing_step (
    quote_request_id     UUID         NOT NULL,
    attempt              SMALLINT     NOT NULL,
    position             SMALLINT     NOT NULL,
    provider_code        TEXT         NOT NULL,
    declaration_version  INTEGER      NOT NULL,
    outcome              TEXT         NOT NULL,
    detail               TEXT,
    recorded_at          TIMESTAMPTZ  NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT quote_sourcing_step_pk PRIMARY KEY (quote_request_id, attempt, position, outcome),
    CONSTRAINT quote_sourcing_step_request_fk FOREIGN KEY (quote_request_id) REFERENCES fx.quote_request (id),
    CONSTRAINT quote_sourcing_step_attempt_positive CHECK (attempt >= 1),
    CONSTRAINT quote_sourcing_step_position_positive CHECK (position >= 1),
    CONSTRAINT quote_sourcing_step_outcome_is_known CHECK (outcome IN ('QUOTED', 'DECLINED', 'UNAVAILABLE', 'INCOHERENT', 'IMPLAUSIBLE', 'NOTHING_SENT', 'INDETERMINATE', 'CHOSEN')),
    CONSTRAINT quote_sourcing_step_detail_shape CHECK (detail IS NULL OR detail ~ '^[A-Z_]{1,64}$')
);

-- One judged outcome per candidate position, and at most one CHOSEN per attempt - naming its
-- candidate's position, beside that candidate's QUOTED row.
CREATE UNIQUE INDEX quote_sourcing_step_one_outcome_per_position
    ON fx.quote_sourcing_step (quote_request_id, attempt, position) WHERE outcome <> 'CHOSEN';
CREATE UNIQUE INDEX quote_sourcing_step_one_chosen
    ON fx.quote_sourcing_step (quote_request_id, attempt) WHERE outcome = 'CHOSEN';

COMMENT ON TABLE fx.quote_sourcing_step IS
    'One row per candidate provider per attempt (P9-TSK-008), and a CHOSEN row - the routing_decision_step shape, so the selection is recomputable. Append-only.';

CREATE OR REPLACE FUNCTION fx.quote_history_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% is append-only (P9-TSK-008)', TG_TABLE_NAME;
END;
$$;

CREATE TRIGGER quote_sourcing_step_is_append_only
    BEFORE UPDATE OR DELETE ON fx.quote_sourcing_step
    FOR EACH ROW
    EXECUTE FUNCTION fx.quote_history_is_append_only();

-- ------------------------------------------------------------------ the quote

CREATE TABLE fx.quote (
    id                            UUID           NOT NULL,
    quote_request_id              UUID           NOT NULL,
    owner_party_id                UUID           NOT NULL,
    purpose                       TEXT           NOT NULL,
    source_currency               CHAR(3)        NOT NULL,
    destination_currency          CHAR(3)        NOT NULL,
    fixed_side                    TEXT           NOT NULL,
    pricing_policy_version_id     UUID           NOT NULL,
    -- The provider's firm quote (the lock's provenance, INV-FX-05).
    provider_code                 TEXT           NOT NULL,
    provider_quote_reference      TEXT           NOT NULL,
    provider_rate                 NUMERIC(20,10) NOT NULL,
    provider_valid_for_ms         BIGINT         NOT NULL,
    provider_value_date           DATE           NOT NULL,
    obtained_at                   TIMESTAMPTZ    NOT NULL,
    requested_at                  TIMESTAMPTZ    NOT NULL,
    -- The independent reference the band judged.
    reference_snapshot_id         UUID           NOT NULL,
    reference_rate                NUMERIC(20,10) NOT NULL,
    -- The derived rates and the pinned terms they were derived under.
    customer_rate                 NUMERIC(20,10) NOT NULL,
    internal_rate                 NUMERIC(20,10) NOT NULL,
    disclosed_margin              NUMERIC(7,6)   NOT NULL,
    spread                        NUMERIC(8,6)   NOT NULL,
    markup                        NUMERIC(8,6)   NOT NULL,
    rate_scale                    INTEGER        NOT NULL,
    rate_rounding                 TEXT           NOT NULL,
    amount_rounding               TEXT           NOT NULL,
    margin_rounding               TEXT           NOT NULL,
    window_seconds                INTEGER        NOT NULL,
    cover_margin_seconds          INTEGER        NOT NULL,
    -- The plan: every amount the trade will post, in minor units at the currency's scale.
    source_scale                  SMALLINT       NOT NULL,
    destination_scale             SMALLINT       NOT NULL,
    customer_source_minor         BIGINT         NOT NULL,
    customer_destination_minor    BIGINT         NOT NULL,
    position_source_minor         BIGINT         NOT NULL,
    position_destination_minor    BIGINT         NOT NULL,
    margin_minor                  BIGINT         NOT NULL,
    spread_margin_minor           BIGINT         NOT NULL,
    markup_margin_minor           BIGINT         NOT NULL,
    residual_minor                BIGINT         NOT NULL,
    -- The lifecycle.
    status                        TEXT           NOT NULL,
    issued_at                     TIMESTAMPTZ    NOT NULL DEFAULT statement_timestamp(),
    expires_at                    TIMESTAMPTZ    NOT NULL,
    closed_at                     TIMESTAMPTZ,
    issued_event_id               UUID           NOT NULL,
    correlation_id                TEXT           NOT NULL,
    CONSTRAINT quote_pk PRIMARY KEY (id),
    CONSTRAINT quote_request_unique UNIQUE (quote_request_id),
    CONSTRAINT quote_request_fk FOREIGN KEY (quote_request_id) REFERENCES fx.quote_request (id),
    CONSTRAINT quote_policy_fk FOREIGN KEY (pricing_policy_version_id) REFERENCES fx.pricing_policy_version (id),
    CONSTRAINT quote_reference_fk FOREIGN KEY (reference_snapshot_id) REFERENCES fx.rate_snapshot (id),
    CONSTRAINT quote_purpose_is_known CHECK (purpose IN ('CONVERSION', 'CROSS_BORDER')),
    CONSTRAINT quote_converts CHECK (source_currency <> destination_currency),
    CONSTRAINT quote_fixed_side_is_known CHECK (fixed_side IN ('FIXED_SOURCE', 'FIXED_DESTINATION')),
    CONSTRAINT quote_status_is_known CHECK (status IN ('ISSUED', 'ACCEPTED', 'EXECUTED', 'ABANDONED', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT quote_closed_when_terminal CHECK ((closed_at IS NOT NULL) = (status IN ('EXECUTED', 'ABANDONED', 'EXPIRED', 'CANCELLED'))),
    CONSTRAINT quote_provider_reference_bounded CHECK (char_length(provider_quote_reference) BETWEEN 1 AND 64),
    CONSTRAINT quote_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200),
    -- The rates: positive, each at or below its scale (INV-MON-03).
    CONSTRAINT quote_rates_positive CHECK (provider_rate > 0 AND reference_rate > 0 AND customer_rate > 0 AND internal_rate > 0),
    CONSTRAINT quote_customer_rate_at_its_scale CHECK (customer_rate = round(customer_rate, rate_scale)),
    CONSTRAINT quote_customer_rate_below_provider CHECK (customer_rate <= provider_rate),
    CONSTRAINT quote_valid_for_positive CHECK (provider_valid_for_ms > 0),
    -- The copied terms, as V004 bounds them.
    CONSTRAINT quote_margin_parts_non_negative CHECK (spread >= 0 AND markup >= 0 AND spread + markup > 0 AND spread + markup < 1),
    CONSTRAINT quote_rate_scale_bounded CHECK (rate_scale BETWEEN 0 AND 10),
    CONSTRAINT quote_rate_rounding_is_named CHECK (rate_rounding IN ('HALF_EVEN', 'HALF_UP', 'TOWARDS_ZERO', 'AWAY_FROM_ZERO', 'FLOOR', 'CEILING')),
    CONSTRAINT quote_amount_rounding_is_named CHECK (amount_rounding IN ('HALF_EVEN', 'HALF_UP', 'TOWARDS_ZERO', 'AWAY_FROM_ZERO', 'FLOOR', 'CEILING')),
    CONSTRAINT quote_margin_rounding_is_named CHECK (margin_rounding IN ('HALF_EVEN', 'HALF_UP', 'TOWARDS_ZERO', 'AWAY_FROM_ZERO', 'FLOOR', 'CEILING')),
    -- Each scale is its currency's minor units (INV-MON-05; SupportedCurrencyMinorUnitsArePinnedTest).
    CONSTRAINT quote_source_scale_is_the_currency_s CHECK (source_scale = CASE source_currency WHEN 'JPY' THEN 0 WHEN 'BHD' THEN 3 ELSE 2 END),
    CONSTRAINT quote_destination_scale_is_the_currency_s CHECK (destination_scale = CASE destination_currency WHEN 'JPY' THEN 0 WHEN 'BHD' THEN 3 ELSE 2 END),
    -- The plan (ADR-0076 section 1; INV-FX-04, INV-FX-07).
    CONSTRAINT quote_amounts_positive CHECK (customer_source_minor > 0 AND customer_destination_minor > 0 AND position_source_minor > 0 AND position_destination_minor > 0),
    CONSTRAINT quote_margin_non_negative CHECK (margin_minor >= 0 AND spread_margin_minor >= 0 AND markup_margin_minor >= 0),
    CONSTRAINT quote_margin_is_its_parts CHECK (margin_minor = spread_margin_minor + markup_margin_minor),
    CONSTRAINT quote_residual_bounded CHECK (residual_minor BETWEEN -2 AND 2),
    CONSTRAINT quote_plan_identity CHECK (
        (fixed_side = 'FIXED_SOURCE'
            AND customer_source_minor = position_source_minor
            AND position_destination_minor = customer_destination_minor + margin_minor + residual_minor)
        OR (fixed_side = 'FIXED_DESTINATION'
            AND customer_destination_minor = position_destination_minor
            AND customer_source_minor = position_source_minor + margin_minor + residual_minor)),
    -- The window, on the database clock (ADR-0075 section 3).
    CONSTRAINT quote_window_is_the_formula CHECK (expires_at = LEAST(
        requested_at + provider_valid_for_ms * INTERVAL '1 millisecond' - cover_margin_seconds * INTERVAL '1 second',
        issued_at + window_seconds * INTERVAL '1 second')),
    CONSTRAINT quote_window_at_least_five_seconds CHECK (expires_at >= issued_at + INTERVAL '5 seconds')
);

COMMENT ON TABLE fx.quote IS
    'The FX quote (P9-TSK-008): a frozen posting plan under the pinned pricing version, single-use and bounded on the database clock. The plan identity, the residual bound and the window formula hold for every writer; the freeze, edge and cap triggers below.';

CREATE INDEX quote_owner_live ON fx.quote (owner_party_id, expires_at) WHERE status = 'ISSUED';
CREATE INDEX quote_expirable ON fx.quote (expires_at) WHERE status = 'ISSUED';

-- Birth: ISSUED, stamped by the database, its terms the pinned version's, its request's own facts,
-- and under the owner's live-quote cap (namespace 5).
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
    NEW.issued_at := statement_timestamp();
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
          AND expires_at > statement_timestamp();
    IF live >= cap THEN
        RAISE EXCEPTION 'the owner already holds % live quotes, the cap (P9-TSK-008)', live
            USING ERRCODE = 'FXCAP';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER quote_is_born_issued
    BEFORE INSERT ON fx.quote
    FOR EACH ROW
    EXECUTE FUNCTION fx.quote_is_born_issued();

-- Every later write: frozen but for status and closed_at, and only along the machine's edges.
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
        OR (OLD.status = 'ACCEPTED' AND NEW.status = 'ABANDONED')) THEN
        RAISE EXCEPTION 'a quote moves % -> % only along its machine, on the database clock (P9-TSK-008)', OLD.status, NEW.status;
    END IF;
    NEW.closed_at := CASE WHEN NEW.status IN ('EXECUTED', 'ABANDONED', 'EXPIRED', 'CANCELLED') THEN now END;
    RETURN NEW;
END;
$$;

CREATE TRIGGER quote_edge_is_legal
    BEFORE UPDATE OR DELETE ON fx.quote
    FOR EACH ROW
    EXECUTE FUNCTION fx.quote_edge_is_legal();

-- ------------------------------------------------------------------ the history

CREATE TABLE fx.quote_event (
    id              UUID         NOT NULL,
    quote_id        UUID         NOT NULL,
    from_status     TEXT,
    to_status       TEXT         NOT NULL,
    actor_id        TEXT         NOT NULL,
    actor_type      TEXT         NOT NULL,
    detected_by     TEXT,
    occurred_at     TIMESTAMPTZ  NOT NULL DEFAULT statement_timestamp(),
    correlation_id  TEXT         NOT NULL,
    CONSTRAINT quote_event_pk PRIMARY KEY (id),
    CONSTRAINT quote_event_quote_fk FOREIGN KEY (quote_id) REFERENCES fx.quote (id),
    CONSTRAINT quote_event_to_is_known CHECK (to_status IN ('ISSUED', 'ACCEPTED', 'EXECUTED', 'ABANDONED', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT quote_event_from_is_known CHECK (from_status IS NULL OR from_status IN ('ISSUED', 'ACCEPTED')),
    CONSTRAINT quote_event_birth_is_issued CHECK ((from_status IS NULL) = (to_status = 'ISSUED')),
    CONSTRAINT quote_event_detected_by_is_known CHECK (detected_by IS NULL OR (to_status = 'EXPIRED' AND detected_by IN ('SWEEP', 'ACCEPTANCE'))),
    CONSTRAINT quote_event_actor_bounded CHECK (char_length(actor_id) BETWEEN 1 AND 200 AND char_length(actor_type) BETWEEN 1 AND 40),
    CONSTRAINT quote_event_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

CREATE INDEX quote_event_by_quote ON fx.quote_event (quote_id);

CREATE TRIGGER quote_event_is_append_only
    BEFORE UPDATE OR DELETE ON fx.quote_event
    FOR EACH ROW
    EXECUTE FUNCTION fx.quote_history_is_append_only();

GRANT SELECT, INSERT ON fx.quote_request TO finapp_app;
GRANT SELECT, INSERT ON fx.quote_sourcing_step TO finapp_app;
GRANT SELECT, INSERT ON fx.quote TO finapp_app;
GRANT UPDATE (status, closed_at) ON fx.quote TO finapp_app;
GRANT SELECT, INSERT ON fx.quote_event TO finapp_app;
