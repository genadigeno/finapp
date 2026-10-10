-- The Phase 10 to 11 transition: credit's person-written reasons hold no instrument shape (INV-AUD-02, INV-PAY-02;
-- the transition audit's finding "free-text reasons never screened for card or account numbers").
--
-- Every reason a person writes into this schema - a policy or scorecard proposal and its decision, their history, an
-- underwriter's first decision and a refused second approval - is CONFIDENTIAL prose kept in tables no role can ever
-- clean (INV-HIST-01). Until now they were held to a length only. The domain now screens each one first
-- (CreditReasons.screen over InstrumentShapes, the fx / crossborder / kyc / reconciliation precedent); this rank refuses a
-- writer that bypassed it, by name.
--
-- THE TWIN is statement for statement crossborder V002's - itself fx V004's, settlement V012's and reconciliation
-- V019's - in this schema, because a schema owns its functions (ADR-0006: no cross-schema dependency for a CHECK). The
-- parity with the Java screen is proven over a corpus by CreditReasonScreenDatabaseTest. A NULL reason (a pending row,
-- a system edge with none) passes: the functions are STRICT and a CHECK admits NULL.
--
-- AND THE EVIDENCE READ: credit.read_evidence(evidence_id, reason) - the one door to a ciphertext - refuses a reason
-- holding a shape too, before anything is read; that reason becomes the read's audit record's (platform.audit_record,
-- which this schema cannot constrain), so the definer is this schema's last rank for it.
-- ---------------------------------------------------------------------------------------------
-- The checksums.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION credit.luhn_valid(digits TEXT)
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
CREATE OR REPLACE FUNCTION credit.mod97_check_holds(compact TEXT)
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
CREATE OR REPLACE FUNCTION credit.holds_card_number_shape(body TEXT)
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
                    IF credit.luhn_valid(substr(grp, start_at, width)) THEN
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
                IF char_length(span) >= 12 AND credit.luhn_valid(span) THEN
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
CREATE OR REPLACE FUNCTION credit.holds_account_identifier_shape(body TEXT)
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
                            AND credit.mod97_check_holds(compact) THEN
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

CREATE OR REPLACE FUNCTION credit.holds_instrument_shape(body TEXT)
    RETURNS boolean
    LANGUAGE sql
    IMMUTABLE
    STRICT
AS $$
    SELECT credit.holds_card_number_shape(body)
        OR credit.holds_account_identifier_shape(body)
$$;

-- ---------------------------------------------------------------------------------------------
-- The reason columns.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE credit.credit_policy_version
    ADD CONSTRAINT credit_policy_version_proposal_reason_no_instrument_shape CHECK (
        NOT credit.holds_instrument_shape(proposal_reason));

ALTER TABLE credit.credit_policy_version
    ADD CONSTRAINT credit_policy_version_decision_reason_no_instrument_shape CHECK (
        NOT credit.holds_instrument_shape(decision_reason));

ALTER TABLE credit.credit_policy_event
    ADD CONSTRAINT credit_policy_event_reason_no_instrument_shape CHECK (
        NOT credit.holds_instrument_shape(reason));

ALTER TABLE credit.scorecard_model_version
    ADD CONSTRAINT scorecard_model_version_proposal_reason_no_instrument_shape CHECK (
        NOT credit.holds_instrument_shape(proposal_reason));

ALTER TABLE credit.scorecard_model_version
    ADD CONSTRAINT scorecard_model_version_decision_reason_no_instrument_shape CHECK (
        NOT credit.holds_instrument_shape(decision_reason));

ALTER TABLE credit.scorecard_model_event
    ADD CONSTRAINT scorecard_model_event_reason_no_instrument_shape CHECK (
        NOT credit.holds_instrument_shape(reason));

ALTER TABLE credit.underwriting_case
    ADD CONSTRAINT underwriting_case_first_reason_no_instrument_shape CHECK (
        NOT credit.holds_instrument_shape(first_reason));

ALTER TABLE credit.underwriting_case_event
    ADD CONSTRAINT underwriting_case_event_reason_no_instrument_shape CHECK (
        NOT credit.holds_instrument_shape(reason));

ALTER TABLE credit.decision_request_event
    ADD CONSTRAINT decision_request_event_reason_no_instrument_shape CHECK (
        NOT credit.holds_instrument_shape(reason));

-- ---------------------------------------------------------------------------------------------
-- The evidence read: V004's definer, with the screen beside its blank-reason refusal.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION credit.read_evidence(evidence_id uuid, reason text)
    RETURNS TABLE (content_ciphertext bytea, content_nonce bytea, key_version integer, checksum_sha256 bytea,
                   content_length integer, consent_withdrawn boolean)
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF reason IS NULL OR btrim(reason) = '' THEN
        RAISE EXCEPTION 'reading credit evidence requires a reason (P10-TSK-006)';
    END IF;
    IF credit.holds_instrument_shape(reason) THEN
        RAISE EXCEPTION 'a credit evidence read''s reason holds no card-number or bank-account shape (INV-AUD-02)'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN QUERY
        SELECT e.content_ciphertext, e.content_nonce, e.key_version, e.checksum_sha256, e.content_length,
               e.consent_withdrawn
        FROM credit.credit_evidence e
        WHERE e.id = evidence_id;
END;
$$;

REVOKE ALL ON FUNCTION credit.read_evidence(uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION credit.read_evidence(uuid, text) TO finapp_app;
