-- The Phase 8 -> 9 transition: person-written prose holds no instrument shape, for every writer
-- (INV-PAY-02, INV-RAIL-03, INV-AUD-02; ADR-0066 sections 4 and 8 as corrected).
--
-- WHAT THE GATE'S AUDIT FOUND (SEC-04, and the recorded debt row "Person-written reasons reach
-- the database unscreened")
--   The decline, readmission, verification and content-read reasons were blank- and length-
--   checked only, and file_event.reason and batch_event.reason - where the decline's and the
--   readmission's reasons rest - had a length CHECK alone. A card number or an account identifier
--   pasted into a reason was stored in plaintext in append-only history that can never be
--   cleaned.
--
-- WHAT THIS MIGRATION ENFORCES
--   1. The twin of com.finapp.sharedkernel.security.InstrumentShapes in this schema, statement for
--      statement and identical to reconciliation V019's: holds_card_number_shape (digit groups
--      joined by single spaces or dashes; any 12..19-digit window of one group, or any span of two
--      or more whole groups of 12..19 digits once joined, Luhn-valid),
--      holds_account_identifier_shape (a token of the contiguous international shape, or the ISO
--      13616 printed form in groups of four whose mod-97 check holds) and holds_instrument_shape,
--      either - each masking the platform's own UUIDs, standing as whole tokens, first. The parity
--      of the two ranks is proven over a corpus by SettlementV012MigrationTest.
--   2. file_event_reason_no_instrument_shape and batch_event_reason_no_instrument_shape. The
--      platform's own reasons (rejection codes, "parse failed: <class>", "resolution=<uuid>")
--      pass; a person's are screened at the domain first (FileReadmission.requireReason, shared by
--      all four doors), so the CHECK refuses only a writer that bypassed it. A stored row that
--      fails fails this migration, for a human to look at.
--   The references a counterparty sends (line_reference.value, batch.external_batch_ref) are not
--   prose and take no such CHECK: they legitimately hold digit runs - a 23-digit ARN, a
--   Luhn-valid 15-digit network transaction id - that this screen's windows would refuse at
--   random. Each format's screen holds their classes at the door, and the batch read withholds a
--   stored batch reference that is not a reference shape (com.finapp.settlement.ReferenceShape).
--   platform.audit_record.reason is platform's schema; every door screens before it writes there.

-- ---------------------------------------------------------------------------------------------
-- The checksums.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION settlement.luhn_valid(digits TEXT)
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
CREATE OR REPLACE FUNCTION settlement.mod97_check_holds(compact TEXT)
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
CREATE OR REPLACE FUNCTION settlement.holds_card_number_shape(body TEXT)
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
                    IF settlement.luhn_valid(substr(grp, start_at, width)) THEN
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
                IF char_length(span) >= 12 AND settlement.luhn_valid(span) THEN
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
CREATE OR REPLACE FUNCTION settlement.holds_account_identifier_shape(body TEXT)
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
                            AND settlement.mod97_check_holds(compact) THEN
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

CREATE OR REPLACE FUNCTION settlement.holds_instrument_shape(body TEXT)
    RETURNS boolean
    LANGUAGE sql
    IMMUTABLE
    STRICT
AS $$
    SELECT settlement.holds_card_number_shape(body)
        OR settlement.holds_account_identifier_shape(body)
$$;

-- ---------------------------------------------------------------------------------------------
-- The reason columns.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE settlement.file_event
    ADD CONSTRAINT file_event_reason_no_instrument_shape CHECK (
        NOT settlement.holds_instrument_shape(reason));

ALTER TABLE settlement.batch_event
    ADD CONSTRAINT batch_event_reason_no_instrument_shape CHECK (
        NOT settlement.holds_instrument_shape(reason));
