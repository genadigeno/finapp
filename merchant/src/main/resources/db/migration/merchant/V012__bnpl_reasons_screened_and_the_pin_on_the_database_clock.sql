-- The Phase 12 -> 13 transition gate (2026-10-22): merchant's BNPL reasons hold no instrument shape (A5-F5), and a BNPL
-- fee pin's instant is its own transaction's on the database clock (A3-M1).
--
-- ONE. THE REASONS (INV-AUD-02, INV-PAY-02). Every reason a person writes into merchant's BNPL tables - an enablement's
-- proposal, decision and withdrawal, and a BNPL fee schedule assignment's history row - is RESTRICTED-PII prose kept in
-- rows no role can clean (V010's edge trigger freezes each decision; the history is append-only). Until now they were held
-- to a length only. The domain now screens each one first (BnplEnablement.reasoned over InstrumentShapes - bnpl's
-- BnplReasons precedent); this rank refuses a writer that bypassed it, by name.
--
-- THE TWIN is statement for statement credit V016's - itself crossborder V002's, fx V004's, settlement V012's and
-- reconciliation V019's - in this schema, because a schema owns its functions (ADR-0006: no cross-schema dependency for a
-- CHECK). The parity with the Java screen is proven over a corpus by MerchantBnplEnablementDatabaseTest. A NULL reason (an
-- undecided or unwithdrawn enablement) passes: the functions are STRICT and a CHECK admits NULL. The Phase 6 reason columns
-- (the API key's revocation, the card fee schedule's assignment, the payout destination's proposal, the payout's) are not
-- the finding's and are not touched here.
--
-- TWO. THE PIN'S INSTANT. BnplPricing resolved the fee version in force, and stamped bnpl_fee_pin.pinned_at, from the
-- application's clock: an instance whose clock ran ahead priced a purchase with a version not yet in force, and V010's
-- trigger judged the version against that same instance-supplied instant, so no rank saw it. The domain now reads
-- clock_timestamp() after the standing's share locks and resolves and stamps with it; this rank binds every writer to that
-- clock: a pin's instant lies within its own transaction's span - no earlier than the transaction's start
-- (transaction_timestamp()), no later than the database's clock at the insert (clock_timestamp()) - so no writer pins a
-- version "in force" by an instant the database never reached. V010's function verbatim but for that rank and one reading:
-- a version created to take effect IMMEDIATELY (effective_from = created_at, V004's "empty for immediately") is in force
-- from its commit, as FeeScheduleStore.findEffectiveVersion reads it - it is stamped with its creating instance's clock,
-- which may run ahead of the database's, and "immediately" must not wait for the database to catch up. A SCHEDULED version
-- (effective_from > created_at) is judged on the pin's instant alone.

-- ---------------------------------------------------------------------------------------------
-- The checksums.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION merchant.luhn_valid(digits TEXT)
    RETURNS boolean
    LANGUAGE plpgsql
    IMMUTABLE
    STRICT
AS $$
DECLARE
    width INT := char_length(digits);
    total INT := 0;
    digit INT;
    i     INT;
BEGIN
    FOR i IN 1..width LOOP
        digit := ascii(substr(digits, width - i + 1, 1)) - 48;
        IF i % 2 = 0 THEN
            digit := digit * 2;
            IF digit > 9 THEN
                digit := digit - 9;
            END IF;
        END IF;
        total := total + digit;
    END LOOP;
    RETURN total % 10 = 0;
END;
$$;

-- ISO 13616: the opening four moved to the end, letters read as 10..35, the remainder mod 97 is 1.
CREATE OR REPLACE FUNCTION merchant.mod97_check_holds(compact TEXT)
    RETURNS boolean
    LANGUAGE plpgsql
    IMMUTABLE
    STRICT
AS $$
DECLARE
    rearranged TEXT := upper(substr(compact, 5) || substr(compact, 1, 4));
    remainder  INT := 0;
    code       INT;
    i          INT;
BEGIN
    FOR i IN 1..char_length(rearranged) LOOP
        code := ascii(substr(rearranged, i, 1));
        IF code BETWEEN 48 AND 57 THEN
            remainder := (remainder * 10 + code - 48) % 97;
        ELSE
            remainder := (remainder * 100 + code - 55) % 97;
        END IF;
    END LOOP;
    RETURN remainder = 1;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The card number: InstrumentShapes.cardNumberIn.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION merchant.holds_card_number_shape(body TEXT)
    RETURNS boolean
    LANGUAGE plpgsql
    IMMUTABLE
    STRICT
AS $$
DECLARE
    masked   TEXT;
    run      TEXT;
    pieces   TEXT[];
    grp      TEXT;
    span     TEXT;
    len      INT;
    width    INT;
    start_at INT;
    first_at INT;
    last_at  INT;
BEGIN
    masked := regexp_replace(body,
        '(?<![0-9A-Za-z])(?:[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}|[0-9a-f]{12}7[0-9a-f]{3}[89ab][0-9a-f]{15})(?![0-9A-Za-z])',
        '#', 'g');
    FOR run IN SELECT (regexp_matches(masked, '[0-9]+(?:[ -][0-9]+)*', 'g'))[1] LOOP
        pieces := regexp_split_to_array(run, '[ -]');
        -- Every 12..19-digit window of one contiguous group.
        FOREACH grp IN ARRAY pieces LOOP
            len := char_length(grp);
            width := 12;
            WHILE width <= 19 AND width <= len LOOP
                start_at := 1;
                WHILE start_at + width - 1 <= len LOOP
                    IF merchant.luhn_valid(substr(grp, start_at, width)) THEN
                        RETURN true;
                    END IF;
                    start_at := start_at + 1;
                END LOOP;
                width := width + 1;
            END LOOP;
        END LOOP;
        -- Every span of two or more whole pieces, 12..19 digits once joined.
        FOR first_at IN 1..array_length(pieces, 1) LOOP
            span := pieces[first_at];
            FOR last_at IN first_at + 1..array_length(pieces, 1) LOOP
                span := span || pieces[last_at];
                EXIT WHEN char_length(span) > 19;
                IF char_length(span) >= 12 AND merchant.luhn_valid(span) THEN
                    RETURN true;
                END IF;
            END LOOP;
        END LOOP;
    END LOOP;
    RETURN false;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The account identifier: InstrumentShapes.accountIdentifierAt.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION merchant.holds_account_identifier_shape(body TEXT)
    RETURNS boolean
    LANGUAGE plpgsql
    IMMUTABLE
    STRICT
AS $$
DECLARE
    masked   TEXT;
    run      TEXT;
    tokens   TEXT[];
    compact  TEXT;
    grp      TEXT;
    total    INT;
    first_at INT;
    next_at  INT;
BEGIN
    masked := regexp_replace(body,
        '(?<![0-9A-Za-z])(?:[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}|[0-9a-f]{12}7[0-9a-f]{3}[89ab][0-9a-f]{15})(?![0-9A-Za-z])',
        '#', 'g');
    FOR run IN SELECT (regexp_matches(masked, '[A-Za-z0-9]+(?:[ -][A-Za-z0-9]+)*', 'g'))[1] LOOP
        tokens := regexp_split_to_array(run, '[ -]');
        total := array_length(tokens, 1);
        FOR first_at IN 1..total LOOP
            IF tokens[first_at] ~ '^[A-Za-z]{2}[0-9]{2}[A-Za-z0-9]{11,30}$' THEN
                RETURN true;
            END IF;
            IF tokens[first_at] ~ '^[A-Za-z]{2}[0-9]{2}$' THEN
                compact := tokens[first_at];
                FOR next_at IN first_at + 1..total LOOP
                    grp := tokens[next_at];
                    EXIT WHEN char_length(grp) > 4;
                    compact := compact || grp;
                    EXIT WHEN char_length(compact) > 34;
                    IF char_length(compact) >= 15
                            AND merchant.mod97_check_holds(compact) THEN
                        RETURN true;
                    END IF;
                    EXIT WHEN char_length(grp) < 4;
                END LOOP;
            END IF;
        END LOOP;
    END LOOP;
    RETURN false;
END;
$$;

CREATE OR REPLACE FUNCTION merchant.holds_instrument_shape(body TEXT)
    RETURNS boolean
    LANGUAGE sql
    IMMUTABLE
    STRICT
AS $$
    SELECT merchant.holds_card_number_shape(body)
        OR merchant.holds_account_identifier_shape(body)
$$;

-- ---------------------------------------------------------------------------------------------
-- The BNPL reason columns.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE merchant.merchant_bnpl_enablement
    ADD CONSTRAINT merchant_bnpl_enablement_proposal_reason_no_instrument_shape CHECK (
        NOT merchant.holds_instrument_shape(proposal_reason));

ALTER TABLE merchant.merchant_bnpl_enablement
    ADD CONSTRAINT merchant_bnpl_enablement_decision_reason_no_instrument_shape CHECK (
        NOT merchant.holds_instrument_shape(decision_reason));

ALTER TABLE merchant.merchant_bnpl_enablement
    ADD CONSTRAINT merchant_bnpl_enablement_withdrawal_reason_no_instrument_shape CHECK (
        NOT merchant.holds_instrument_shape(withdrawal_reason));

ALTER TABLE merchant.merchant_bnpl_fee_schedule_event
    ADD CONSTRAINT merchant_bnpl_fee_schedule_event_reason_no_instrument_shape CHECK (
        NOT merchant.holds_instrument_shape(reason));

-- ---------------------------------------------------------------------------------------------
-- The pin: V010's function verbatim, plus the instant's rank.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION merchant.bnpl_fee_pin_names_a_returning_version_in_force() RETURNS trigger
LANGUAGE plpgsql AS
$$
DECLARE
    pinned merchant.fee_schedule_version%ROWTYPE;
BEGIN
    -- The Phase 12 -> 13 gate's A3-M1: the pin's instant is this transaction's on the database clock - read by the domain
    -- after the standing's locks, never an instance's own - so "in force by the pin's instant" below is judged on the one
    -- clock every instance shares.
    IF NEW.pinned_at < transaction_timestamp() OR NEW.pinned_at > clock_timestamp() THEN
        RAISE EXCEPTION 'a BNPL fee pin''s instant is its own transaction''s on the database clock (the Phase 12 -> 13 gate''s A3-M1, INV-MER-03)';
    END IF;
    SELECT * INTO pinned FROM merchant.fee_schedule_version WHERE id = NEW.fee_schedule_version_id;
    IF pinned.refund_fee_policy <> 'RETURNED' THEN
        RAISE EXCEPTION 'a BNPL fee pin names a version that returns its fee on refund (P12-TSK-006, ADR-0105)';
    END IF;
    IF pinned.fixed_currency <> NEW.gross_currency THEN
        RAISE EXCEPTION 'a BNPL fee pin''s gross is in its version''s currency (P12-TSK-006, INV-MON-04)';
    END IF;
    IF pinned.effective_from > NEW.pinned_at AND pinned.effective_from <> pinned.created_at THEN
        RAISE EXCEPTION 'a BNPL fee pin names a version in force when it was pinned (P12-TSK-006, INV-MER-03)';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM merchant.merchant_bnpl_fee_schedule a
                    WHERE a.merchant_id = NEW.merchant_id AND a.product = NEW.product
                      AND a.fee_schedule_id = pinned.fee_schedule_id) THEN
        RAISE EXCEPTION 'a BNPL fee pin names a version of the merchant''s BNPL schedule for the product (P12-TSK-006)';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM merchant.merchant_bnpl_enablement e
                    WHERE e.merchant_id = NEW.merchant_id AND e.status = 'ACTIVE') THEN
        RAISE EXCEPTION 'a BNPL fee pin is born only for a BNPL-enabled merchant (P12-TSK-006, INV-AUD-04)';
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON COLUMN merchant.bnpl_fee_pin.pinned_at IS
    'When the version was resolved: the DATABASE''s clock_timestamp(), read after the standing''s share locks in the pinning transaction (the Phase 12 -> 13 gate''s A3-M1, merchant V012 - it was the application clock); the trigger refuses an instant outside that transaction''s span.';
