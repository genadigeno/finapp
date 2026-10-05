-- =============================================================================================
-- P9-TSK-016 - counterparty screening is kyc's (ADR-0081; INV-KYC-01, INV-KYC-04, INV-KYC-05).
--
-- A counterparty (a payee abroad, never a customer) is screened by kyc, the platform's one
-- verification authority. Every outcome is kyc's RECORDED DECISION - its basis (AUTOMATIC |
-- REVIEWER), the kyc policy version and the time - never the provider's verdict alone; the
-- provider's answer is evidence. A hit, an indeterminate answer or an unverified payee (the payee
-- check handed in as NO_MATCH or UNAVAILABLE) always meets a person: never auto-cleared, never
-- auto-rejected. A provider that cannot be asked is UNAVAILABLE - retried, and nothing is cleared.
--
-- The machine (the lifecycle document 3.8):
--   REQUESTED   -> CLEAR | IN_REVIEW | UNAVAILABLE
--   UNAVAILABLE -> CLEAR | IN_REVIEW | UNAVAILABLE   (a retry; the attempt counter grows)
--   IN_REVIEW   -> RELEASED | BLOCKED                (a person, a reason code and a narrative)
-- CLEAR, RELEASED and BLOCKED are terminal; a re-screen is a new row.
--
-- The name is RESTRICTED-PII and rests only here: AES-256-GCM under kyc's evidence key with the
-- screening id's sixteen bytes as associated data, so a ciphertext moved onto another row refuses
-- to decrypt instead of naming the wrong counterparty. No bank identifier is ever held.
-- =============================================================================================

CREATE TABLE kyc.counterparty_screening (
    -- UUIDv7, minted by the application (ADR-0013).
    id                    uuid        PRIMARY KEY,
    -- The caller's identifier for this screening request: a duplicate request converges on it.
    request_reference     text        NOT NULL,
    subject_ciphertext    bytea       NOT NULL,
    subject_nonce         bytea       NOT NULL,
    subject_key_version   integer     NOT NULL,
    country               text        NOT NULL,
    entity_type           text        NOT NULL,
    -- The beneficiary's payee check, as handed in - part of the decision's basis.
    payee_verdict         text        NOT NULL,
    status                text        NOT NULL,
    review_reason         text,
    decision_basis        text,
    policy_version        text,
    decided_at            timestamptz,
    decided_by            text,
    decision_reason_code  text,
    decision_narrative    text,
    attempts              integer     NOT NULL,
    next_attempt_at       timestamptz,
    requested_at          timestamptz NOT NULL,
    CONSTRAINT counterparty_screening_request_reference_is_bounded
        CHECK (char_length(request_reference) BETWEEN 1 AND 200),
    CONSTRAINT counterparty_screening_one_per_request UNIQUE (request_reference),
    CONSTRAINT counterparty_screening_nonce_is_gcm_sized CHECK (octet_length(subject_nonce) = 12),
    CONSTRAINT counterparty_screening_key_version_is_positive CHECK (subject_key_version > 0),
    -- A name of at most 140 characters is at most 560 UTF-8 bytes, plus the 16-byte tag.
    CONSTRAINT counterparty_screening_ciphertext_is_bounded
        CHECK (octet_length(subject_ciphertext) BETWEEN 17 AND 576),
    CONSTRAINT counterparty_screening_country_is_alpha2 CHECK (country ~ '^[A-Z]{2}$'),
    CONSTRAINT counterparty_screening_entity_type_is_known
        CHECK (entity_type IN ('INDIVIDUAL', 'BUSINESS')),
    CONSTRAINT counterparty_screening_payee_verdict_is_known
        CHECK (payee_verdict IN ('MATCH', 'NO_MATCH', 'UNAVAILABLE')),
    CONSTRAINT counterparty_screening_status_is_known
        CHECK (status IN ('REQUESTED', 'CLEAR', 'IN_REVIEW', 'UNAVAILABLE', 'RELEASED', 'BLOCKED')),
    CONSTRAINT counterparty_screening_review_reason_is_known
        CHECK (review_reason IN ('HIT', 'INDETERMINATE', 'PAYEE_UNVERIFIED')),
    CONSTRAINT counterparty_screening_basis_is_known
        CHECK (decision_basis IN ('AUTOMATIC', 'REVIEWER')),
    CONSTRAINT counterparty_screening_reason_code_is_known
        CHECK (decision_reason_code IN ('FALSE_POSITIVE', 'PAYEE_CONFIRMED', 'TRUE_MATCH', 'PAYEE_NOT_CONFIRMED', 'INSUFFICIENT_INFORMATION')),
    -- Every outcome is a recorded decision: a basis, a policy version and a time exactly when the
    -- screening is no longer REQUESTED.
    CONSTRAINT counterparty_screening_every_outcome_is_decided
        CHECK ((status = 'REQUESTED') = (decision_basis IS NULL)
            AND (decision_basis IS NULL) = (policy_version IS NULL)
            AND (decision_basis IS NULL) = (decided_at IS NULL)),
    CONSTRAINT counterparty_screening_decided_by_is_bounded
        CHECK (decided_by IS NULL OR char_length(decided_by) BETWEEN 1 AND 200),
    CONSTRAINT counterparty_screening_policy_version_is_bounded
        CHECK (policy_version IS NULL OR char_length(policy_version) BETWEEN 1 AND 64),
    -- A person decides exactly the reviewed outcomes; the machine decides the rest.
    CONSTRAINT counterparty_screening_basis_matches_the_outcome
        CHECK (decision_basis IS NULL
            OR (decision_basis = 'REVIEWER') = (status IN ('RELEASED', 'BLOCKED'))),
    -- The onboarding CHECK, reused (kyc V007): REVIEWER exactly when a deciding person is named.
    CONSTRAINT counterparty_screening_reviewer_names_a_person
        CHECK ((decision_basis = 'REVIEWER') = (decided_by IS NOT NULL)),
    CONSTRAINT counterparty_screening_no_person_without_a_decision
        CHECK (decided_by IS NULL OR decision_basis IS NOT NULL),
    -- A person's decision is reasoned: a reason code and a narrative, exactly with a person.
    CONSTRAINT counterparty_screening_reviewer_is_reasoned
        CHECK ((decided_by IS NULL) = (decision_reason_code IS NULL)
            AND (decided_by IS NULL) = (decision_narrative IS NULL)),
    CONSTRAINT counterparty_screening_narrative_is_bounded
        CHECK (decision_narrative IS NULL OR char_length(decision_narrative) BETWEEN 1 AND 1000),
    CONSTRAINT counterparty_screening_release_reasons
        CHECK (status <> 'RELEASED' OR decision_reason_code IN ('FALSE_POSITIVE', 'PAYEE_CONFIRMED')),
    CONSTRAINT counterparty_screening_block_reasons
        CHECK (status <> 'BLOCKED'
            OR decision_reason_code IN ('TRUE_MATCH', 'PAYEE_NOT_CONFIRMED', 'INSUFFICIENT_INFORMATION')),
    -- ADR-0081 point 2's new CHECK: an AUTOMATIC CLEAR exists only with a payee MATCH.
    CONSTRAINT counterparty_screening_automatic_clear_needs_a_payee_match
        CHECK (NOT (decision_basis = 'AUTOMATIC' AND status = 'CLEAR') OR payee_verdict = 'MATCH'),
    -- Why a person was asked, exactly on the reviewed path.
    CONSTRAINT counterparty_screening_review_reason_on_the_review_path
        CHECK ((review_reason IS NOT NULL) = (status IN ('IN_REVIEW', 'RELEASED', 'BLOCKED'))),
    CONSTRAINT counterparty_screening_attempts_are_counted CHECK (attempts >= 0),
    -- Only an unanswered screening is due; an answered one is never asked again.
    CONSTRAINT counterparty_screening_due_only_while_unanswered
        CHECK ((next_attempt_at IS NOT NULL) = (status IN ('REQUESTED', 'UNAVAILABLE'))),
    CONSTRAINT counterparty_screening_decided_after_requested
        CHECK (decided_at IS NULL OR decided_at >= requested_at)
);

CREATE INDEX counterparty_screening_due
    ON kyc.counterparty_screening (next_attempt_at) WHERE status IN ('REQUESTED', 'UNAVAILABLE');
CREATE INDEX counterparty_screening_awaiting_review
    ON kyc.counterparty_screening (decided_at) WHERE status = 'IN_REVIEW';

COMMENT ON TABLE kyc.counterparty_screening IS
    'One counterparty screening (P9-TSK-016, ADR-0081): REQUESTED -> CLEAR | IN_REVIEW | UNAVAILABLE; '
    'UNAVAILABLE -> CLEAR | IN_REVIEW | UNAVAILABLE; IN_REVIEW -> RELEASED | BLOCKED. Every outcome '
    'records its basis, kyc policy version and time (INV-KYC-01); an AUTOMATIC CLEAR only with a payee '
    'MATCH, and a hit, an indeterminate answer or an unverified payee always meets a person (INV-KYC-04). '
    'The name is RESTRICTED-PII, AES-256-GCM with the screening id as associated data.';

-- The identity, the subject and the payee verdict are frozen; the status moves only along the
-- machine (a self-edge only while unanswered: the claim and the UNAVAILABLE retry); no row is
-- ever deleted.
CREATE OR REPLACE FUNCTION kyc.counterparty_screening_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a counterparty screening is never deleted (INV-HIST-01)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.request_reference IS DISTINCT FROM OLD.request_reference
            OR NEW.subject_ciphertext IS DISTINCT FROM OLD.subject_ciphertext
            OR NEW.subject_nonce IS DISTINCT FROM OLD.subject_nonce
            OR NEW.subject_key_version IS DISTINCT FROM OLD.subject_key_version
            OR NEW.country IS DISTINCT FROM OLD.country
            OR NEW.entity_type IS DISTINCT FROM OLD.entity_type
            OR NEW.payee_verdict IS DISTINCT FROM OLD.payee_verdict
            OR NEW.requested_at IS DISTINCT FROM OLD.requested_at THEN
        RAISE EXCEPTION 'a counterparty screening''s identity, subject and payee verdict are frozen';
    END IF;
    IF NOT ((OLD.status = 'REQUESTED' AND NEW.status IN ('REQUESTED', 'CLEAR', 'IN_REVIEW', 'UNAVAILABLE'))
            OR (OLD.status = 'UNAVAILABLE' AND NEW.status IN ('UNAVAILABLE', 'CLEAR', 'IN_REVIEW'))
            OR (OLD.status = 'IN_REVIEW' AND NEW.status IN ('RELEASED', 'BLOCKED'))) THEN
        RAISE EXCEPTION 'a counterparty screening cannot move from % to %', OLD.status, NEW.status;
    END IF;
    IF OLD.status = 'IN_REVIEW' AND (NEW.review_reason IS DISTINCT FROM OLD.review_reason
            OR NEW.attempts IS DISTINCT FROM OLD.attempts) THEN
        RAISE EXCEPTION 'a reviewed screening keeps why it was reviewed and how often it was asked';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER counterparty_screening_permits_only_machine_edges
    BEFORE UPDATE OR DELETE ON kyc.counterparty_screening
    FOR EACH ROW
    EXECUTE FUNCTION kyc.counterparty_screening_permits_only_machine_edges();

-- ---------------------------------------------------------------------------------------------
-- One row per provider attempt, append-only by grant: the verdict the provider gave and, when an
-- answer arrived, its bytes verbatim (INV-HIST-02) - encrypted with the screening id as associated
-- data, under the subject's key. The primary key is the second arbiter of a retry: two writers of
-- the same attempt number cannot both commit.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE kyc.counterparty_screening_attempt (
    screening_id          uuid        NOT NULL REFERENCES kyc.counterparty_screening (id),
    attempt               integer     NOT NULL,
    verdict               text        NOT NULL,
    evidence_ciphertext   bytea,
    evidence_nonce        bytea,
    evidence_key_version  integer,
    evidence_checksum     bytea,
    evidence_length       integer,
    answered_at           timestamptz NOT NULL,
    PRIMARY KEY (screening_id, attempt),
    CONSTRAINT counterparty_screening_attempt_is_positive CHECK (attempt > 0),
    CONSTRAINT counterparty_screening_attempt_verdict_is_known
        CHECK (verdict IN ('CLEAR', 'HIT', 'INDETERMINATE', 'UNAVAILABLE')),
    CONSTRAINT counterparty_screening_attempt_evidence_is_whole
        CHECK ((evidence_ciphertext IS NULL) = (evidence_nonce IS NULL)
            AND (evidence_ciphertext IS NULL) = (evidence_key_version IS NULL)
            AND (evidence_ciphertext IS NULL) = (evidence_checksum IS NULL)
            AND (evidence_ciphertext IS NULL) = (evidence_length IS NULL)),
    CONSTRAINT counterparty_screening_attempt_nonce_is_gcm_sized
        CHECK (evidence_nonce IS NULL OR octet_length(evidence_nonce) = 12),
    CONSTRAINT counterparty_screening_attempt_checksum_is_sha256
        CHECK (evidence_checksum IS NULL OR octet_length(evidence_checksum) = 32),
    CONSTRAINT counterparty_screening_attempt_length_is_bounded
        CHECK (evidence_length IS NULL OR evidence_length BETWEEN 1 AND 524288),
    CONSTRAINT counterparty_screening_attempt_ciphertext_carries_the_tag
        CHECK (evidence_ciphertext IS NULL OR octet_length(evidence_ciphertext) = evidence_length + 16)
);

COMMENT ON TABLE kyc.counterparty_screening_attempt IS
    'Every provider attempt of a counterparty screening (P9-TSK-016): the verdict and, when an answer '
    'arrived, its bytes verbatim, encrypted (INV-HIST-02). Append-only by grant; the primary key '
    'arbitrates two writers of one attempt.';

-- =============================================================================================
-- Person-written prose holds no instrument shape, for every writer (INV-AUD-02; fx V004,
-- settlement V012, reconciliation V019 and crossborder V002 its precedents).
--   The reviewer's narrative is free prose, kept in a table that can never be cleaned. The domain
--   screens it first (CounterpartyScreenings over InstrumentShapes); this rank refuses a writer
--   that bypassed it. The twin below is statement for statement fx V004's; the parity is proven
--   over a corpus by KycReasonScreenDatabaseTest. A NULL narrative (an automatic outcome) passes:
--   the function is STRICT and a CHECK admits NULL.
-- =============================================================================================
-- ---------------------------------------------------------------------------------------------
-- The checksums.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION kyc.luhn_valid(digits TEXT)
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
CREATE OR REPLACE FUNCTION kyc.mod97_check_holds(compact TEXT)
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
CREATE OR REPLACE FUNCTION kyc.holds_card_number_shape(body TEXT)
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
                    IF kyc.luhn_valid(substr(grp, start_at, width)) THEN
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
                IF char_length(span) >= 12 AND kyc.luhn_valid(span) THEN
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
CREATE OR REPLACE FUNCTION kyc.holds_account_identifier_shape(body TEXT)
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
                            AND kyc.mod97_check_holds(compact) THEN
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

CREATE OR REPLACE FUNCTION kyc.holds_instrument_shape(body TEXT)
    RETURNS boolean
    LANGUAGE sql
    IMMUTABLE
    STRICT
AS $$
    SELECT kyc.holds_card_number_shape(body)
        OR kyc.holds_account_identifier_shape(body)
$$;


-- ---------------------------------------------------------------------------------------------
-- The reason column.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE kyc.counterparty_screening
    ADD CONSTRAINT counterparty_screening_decision_narrative_no_instrument_shape CHECK (
        NOT kyc.holds_instrument_shape(decision_narrative));

GRANT SELECT, INSERT ON kyc.counterparty_screening TO finapp_app;
GRANT UPDATE (status, review_reason, decision_basis, policy_version, decided_at, decided_by,
              decision_reason_code, decision_narrative, attempts, next_attempt_at)
    ON kyc.counterparty_screening TO finapp_app;
GRANT SELECT, INSERT ON kyc.counterparty_screening_attempt TO finapp_app;
