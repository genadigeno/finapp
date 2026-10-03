-- The Phase 8 -> 9 transition: person-written prose holds no instrument shape, for every writer
-- (INV-PAY-02, INV-RAIL-03, INV-AUD-02; ADR-0069 section 7 and ADR-0068 section 9 as corrected).
--
-- WHAT THE GATE'S AUDIT FOUND
--   SEC-03: V004's holds_luhn_valid_digit_run scanned CONTIGUOUS digit runs and the account CHECKs
--   matched the CONTIGUOUS international shape alone, so a card number written
--   '4111 1111 1111 1111' or '4111-1111-1111-1111', and an account identifier in its printed
--   groups of four, passed break_note, break_evidence_link and resolution.narrative - the same
--   blind spot as the domain's NoteScreen, and the opposite of the settlement door, which has
--   always collapsed single spaces and dashes.
--   SEC-04 / the recorded debt row: the reprocess and requeue reasons were blank- and length-
--   checked only, and no reason column here had a shape CHECK at all - the reclassification,
--   rejection and rule-set reasons were screened at the domain alone.
--
-- WHAT THIS MIGRATION ENFORCES
--   1. The twin of com.finapp.sharedkernel.security.InstrumentShapes, statement for statement:
--      holds_card_number_shape (digit groups joined by single spaces or dashes; any 12..19-digit
--      window of one group, or any span of two or more whole groups of 12..19 digits once
--      joined, Luhn-valid), holds_account_identifier_shape (a token of the contiguous
--      international shape, or the ISO 13616 printed form in groups of four whose mod-97 check
--      holds), and holds_instrument_shape, either. Both mask the platform's own UUIDs first -
--      canonical dashed ones, and lower-case version-7 ones without dashes - standing as whole
--      tokens: a UUID is no instrument, and unmasked its dash-joined digits are a Luhn-valid
--      span about once in two hundred and a dashless one opens like an account about once in
--      fifteen. The parity of the two ranks is proven over a corpus by
--      ReconciliationV019MigrationTest.
--   2. holds_luhn_valid_digit_run keeps its name (other migrations may name it) and now answers
--      holds_card_number_shape.
--   3. The six V004/V006 screen CHECKs are dropped and re-added under their own names over the
--      twin, so every stored row is re-validated under the corrected screen.
--   4. Every reason column in this schema gains <table>_reason_no_instrument_shape: rule_set,
--      rule_set_event, reconciliation_batch, reconciliation_batch_event, external_item_event,
--      break_event and resolution_event. The platform's own reasons (identifiers, enum names,
--      UUIDs - masked) pass; a person's are screened at the domain first, so the CHECK refuses
--      only a writer that bypassed it. A stored row that fails fails this migration, for a human
--      to look at: such a value can never be cleaned from these append-only tables.
--   platform.audit_record.reason is platform's schema; every door screens before it writes there.

-- ---------------------------------------------------------------------------------------------
-- The checksums.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION reconciliation.luhn_valid(digits TEXT)
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
CREATE OR REPLACE FUNCTION reconciliation.mod97_check_holds(compact TEXT)
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
CREATE OR REPLACE FUNCTION reconciliation.holds_card_number_shape(body TEXT)
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
                    IF reconciliation.luhn_valid(substr(grp, start_at, width)) THEN
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
                IF char_length(span) >= 12 AND reconciliation.luhn_valid(span) THEN
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
CREATE OR REPLACE FUNCTION reconciliation.holds_account_identifier_shape(body TEXT)
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
                            AND reconciliation.mod97_check_holds(compact) THEN
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

CREATE OR REPLACE FUNCTION reconciliation.holds_instrument_shape(body TEXT)
    RETURNS boolean
    LANGUAGE sql
    IMMUTABLE
    STRICT
AS $$
    SELECT reconciliation.holds_card_number_shape(body)
        OR reconciliation.holds_account_identifier_shape(body)
$$;

-- V004's name, kept for anything that names it: it now answers the corrected card scan.
CREATE OR REPLACE FUNCTION reconciliation.holds_luhn_valid_digit_run(body TEXT)
    RETURNS boolean
    LANGUAGE sql
    IMMUTABLE
AS $$
    SELECT reconciliation.holds_card_number_shape(body)
$$;

-- ---------------------------------------------------------------------------------------------
-- The case file's screens, re-added over the twin - every stored row re-validated.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE reconciliation.break_note
    DROP CONSTRAINT break_note_no_card_number,
    DROP CONSTRAINT break_note_no_account_shape,
    ADD CONSTRAINT break_note_no_card_number CHECK (
        NOT reconciliation.holds_card_number_shape(body)),
    ADD CONSTRAINT break_note_no_account_shape CHECK (
        NOT reconciliation.holds_account_identifier_shape(body));

ALTER TABLE reconciliation.break_evidence_link
    DROP CONSTRAINT break_evidence_link_no_card_number,
    DROP CONSTRAINT break_evidence_link_no_account_shape,
    ADD CONSTRAINT break_evidence_link_no_card_number CHECK (
        NOT reconciliation.holds_card_number_shape(target_ref)),
    ADD CONSTRAINT break_evidence_link_no_account_shape CHECK (
        NOT reconciliation.holds_account_identifier_shape(target_ref));

ALTER TABLE reconciliation.resolution
    DROP CONSTRAINT resolution_narrative_no_pan,
    DROP CONSTRAINT resolution_narrative_no_iban,
    ADD CONSTRAINT resolution_narrative_no_pan CHECK (
        NOT reconciliation.holds_card_number_shape(narrative)),
    ADD CONSTRAINT resolution_narrative_no_iban CHECK (
        NOT reconciliation.holds_account_identifier_shape(narrative));

-- ---------------------------------------------------------------------------------------------
-- Every reason column: a person's reason, or the platform's own.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE reconciliation.rule_set
    ADD CONSTRAINT rule_set_reason_no_instrument_shape CHECK (
        NOT reconciliation.holds_instrument_shape(reason));

ALTER TABLE reconciliation.rule_set_event
    ADD CONSTRAINT rule_set_event_reason_no_instrument_shape CHECK (
        NOT reconciliation.holds_instrument_shape(reason));

ALTER TABLE reconciliation.reconciliation_batch
    ADD CONSTRAINT reconciliation_batch_reason_no_instrument_shape CHECK (
        NOT reconciliation.holds_instrument_shape(reason));

ALTER TABLE reconciliation.reconciliation_batch_event
    ADD CONSTRAINT reconciliation_batch_event_reason_no_instrument_shape CHECK (
        NOT reconciliation.holds_instrument_shape(reason));

ALTER TABLE reconciliation.external_item_event
    ADD CONSTRAINT external_item_event_reason_no_instrument_shape CHECK (
        NOT reconciliation.holds_instrument_shape(reason));

ALTER TABLE reconciliation.break_event
    ADD CONSTRAINT break_event_reason_no_instrument_shape CHECK (
        NOT reconciliation.holds_instrument_shape(reason));

ALTER TABLE reconciliation.resolution_event
    ADD CONSTRAINT resolution_event_reason_no_instrument_shape CHECK (
        NOT reconciliation.holds_instrument_shape(reason));
