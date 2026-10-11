-- Lending capital: the four-eyes contribution (P11-TSK-004; ADR-0096 section 4, PHASE_11_PLAN.md sections 7.1 (T-cap),
-- 8 and 12.9; INV-LND-13, INV-AUD-04, INV-AUD-02).
--
-- A treasury officer (LENDING_CAPITAL_ADMINISTER) proposes a contribution of the platform's own money - currency,
-- amount, the bank reference its corporate transfer will carry, a reason; a DIFFERENT holder approves it (opening the
-- LENDING_CAPITAL_CONTRIBUTION expectation through reconciliation's opener, in the same transaction) or anyone, the
-- proposer included, rejects it. The machine is PROPOSED -> APPROVED | REJECTED and nothing else. Recognition is NOT a
-- state here: it is reconciliation's fact (bank recognition of the statement line, rule 0), read through app.
--
-- WHY TWO INSERT-ONLY TABLES AND NO UPDATE. The contribution carries money, and every lending table carrying money is
-- INSERT-only for every role but its owner (LendingSchemaHasNoMutableMoneyTest refuses any UPDATE grant on such a
-- table, on any column). So the proposal is a born-once fact (capital_contribution) and its machine is its history
-- (capital_contribution_event): the PROPOSED birth beside it, and at most one decision - APPROVED or REJECTED - born
-- once by a partial unique. The status is the latest event. A decision cannot lock the proposal FOR UPDATE (that needs
-- an UPDATE privilege an INSERT-only table never grants): every proposal and decision takes the currency's advisory
-- lock first (namespace 11, hashtext(currency), DISTRIBUTED_EXECUTION.md section 3), and the decision partial unique is
-- the arbiter beneath it - ten approvers, one approval.
--
-- FOUR EYES FOR EVERY WRITER. The decision row copies the proposer, and the composite foreign key
-- (contribution_id, proposed_by) -> capital_contribution (id, proposed_by) makes the copy the truth; the CHECK then
-- refuses an approval whose actor proposed it. Every reason is bounded and holds no card-number or bank-account shape -
-- the domain screens first (InstrumentShapes), and the PL/pgSQL twin below refuses a writer that bypassed it (credit
-- V016's functions, statement for statement, in this schema: a schema owns its functions, ADR-0006).
--
-- THE REFERENCE is the bank remittance reference the transfer will carry: LCAP- and 4 to 12 digits - a family no
-- settlement source's remittance pattern claims, no digit run of card length, unique for all time (one reference, one
-- transfer). Times are the database's, stamped by trigger.

-- ---------------------------------------------------------------------------------------------
-- The reason screen's twin (credit V016's functions, in this schema).
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION lending.luhn_valid(digits TEXT)
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
CREATE OR REPLACE FUNCTION lending.mod97_check_holds(compact TEXT)
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
CREATE OR REPLACE FUNCTION lending.holds_card_number_shape(body TEXT)
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
                    IF lending.luhn_valid(substr(grp, start_at, width)) THEN
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
                IF char_length(span) >= 12 AND lending.luhn_valid(span) THEN
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
CREATE OR REPLACE FUNCTION lending.holds_account_identifier_shape(body TEXT)
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
                            AND lending.mod97_check_holds(compact) THEN
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

CREATE OR REPLACE FUNCTION lending.holds_instrument_shape(body TEXT)
    RETURNS boolean
    LANGUAGE sql
    IMMUTABLE
    STRICT
AS $$
    SELECT lending.holds_card_number_shape(body)
        OR lending.holds_account_identifier_shape(body)
$$;

-- ---------------------------------------------------------------------------------------------
-- The proposal: a born-once fact carrying the money.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE lending.capital_contribution (
    id               uuid        NOT NULL,
    currency         char(3)     NOT NULL,
    amount_minor     bigint      NOT NULL,
    scale            smallint    NOT NULL,
    reference        text        NOT NULL,
    proposed_by      text        NOT NULL,
    proposed_by_type text        NOT NULL,
    reason           text        NOT NULL,
    proposed_at      timestamptz NOT NULL,
    correlation_id   text        NOT NULL,
    CONSTRAINT capital_contribution_pk PRIMARY KEY (id),
    -- The decision's composite key pins its copy of the proposer to this row's.
    CONSTRAINT capital_contribution_proposer_pinned UNIQUE (id, proposed_by),
    CONSTRAINT capital_contribution_reference_once UNIQUE (reference),
    CONSTRAINT capital_contribution_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT capital_contribution_currency_shape CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT capital_contribution_scale_bounded CHECK (scale BETWEEN 0 AND 9),
    CONSTRAINT capital_contribution_reference_shape CHECK (reference ~ '^LCAP-[0-9]{4,12}$'),
    CONSTRAINT capital_contribution_reason_bounded CHECK (char_length(reason) BETWEEN 1 AND 1000),
    CONSTRAINT capital_contribution_reason_no_instrument_shape CHECK (NOT lending.holds_instrument_shape(reason))
);

COMMENT ON TABLE lending.capital_contribution IS
    'A proposed contribution of the platform''s own money to lending capital (P11-TSK-004, ADR-0096 section 4): born once, INSERT-only for every role but its owner; its machine is capital_contribution_event. Never capital by itself: LENDING_CAPITAL is credited only by bank recognition of the statement line its approved expectation matches (INV-LND-13). Amount RESTRICTED-FINANCIAL.';

CREATE OR REPLACE FUNCTION lending.capital_contribution_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a capital contribution is a born-once fact (P11-TSK-004): its machine is its history';
    END IF;
    NEW.proposed_at := transaction_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER capital_contribution_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON lending.capital_contribution
    FOR EACH ROW
    EXECUTE FUNCTION lending.capital_contribution_is_born_once();

-- ---------------------------------------------------------------------------------------------
-- The machine, as history: the PROPOSED birth and at most one decision.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE lending.capital_contribution_event (
    id              uuid        NOT NULL,
    contribution_id uuid        NOT NULL,
    from_status     text,
    to_status       text        NOT NULL,
    actor           text        NOT NULL,
    actor_type      text        NOT NULL,
    proposed_by     text        NOT NULL,
    reason          text        NOT NULL,
    expectation_ref text,
    occurred_at     timestamptz NOT NULL,
    correlation_id  text        NOT NULL,
    CONSTRAINT capital_contribution_event_pk PRIMARY KEY (id),
    CONSTRAINT capital_contribution_event_contribution_fk FOREIGN KEY (contribution_id, proposed_by)
        REFERENCES lending.capital_contribution (id, proposed_by),
    -- CapitalContributionStatus, and its only edges.
    CONSTRAINT capital_contribution_event_edge CHECK (
        (from_status IS NULL AND to_status = 'PROPOSED')
        OR (from_status = 'PROPOSED' AND to_status IN ('APPROVED', 'REJECTED'))),
    -- The proposal is its proposer's; an approval never is (INV-AUD-04). A rejection may be the proposer's withdrawal.
    CONSTRAINT capital_contribution_event_birth_is_the_proposers CHECK (
        to_status <> 'PROPOSED' OR actor = proposed_by),
    CONSTRAINT capital_contribution_event_four_eyes CHECK (
        to_status <> 'APPROVED' OR actor <> proposed_by),
    -- The approval and only the approval opens the contribution's expectation - one per contribution.
    CONSTRAINT capital_contribution_event_approval_opens_its_expectation CHECK (
        (to_status = 'APPROVED') = (expectation_ref IS NOT NULL)),
    CONSTRAINT capital_contribution_event_expectation_once UNIQUE (expectation_ref),
    CONSTRAINT capital_contribution_event_reason_bounded CHECK (char_length(reason) BETWEEN 1 AND 1000),
    CONSTRAINT capital_contribution_event_reason_no_instrument_shape CHECK (NOT lending.holds_instrument_shape(reason))
);

-- Born once; decided once - the arbiter beneath the currency's advisory lock: ten approvers, one approval.
CREATE UNIQUE INDEX capital_contribution_born_once
    ON lending.capital_contribution_event (contribution_id) WHERE to_status = 'PROPOSED';
CREATE UNIQUE INDEX capital_contribution_decided_once
    ON lending.capital_contribution_event (contribution_id) WHERE to_status <> 'PROPOSED';

COMMENT ON TABLE lending.capital_contribution_event IS
    'A capital contribution''s machine as history (P11-TSK-004): its PROPOSED birth and at most one decision, APPROVED (by someone other than the proposer, naming the reconciliation expectation it opened) or REJECTED. Append-only for every writer.';

CREATE OR REPLACE FUNCTION lending.capital_contribution_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a capital contribution event is append-only (P11-TSK-004, INV-HIST-01)';
    END IF;
    NEW.occurred_at := transaction_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER capital_contribution_event_is_append_only
    BEFORE INSERT OR UPDATE OR DELETE ON lending.capital_contribution_event
    FOR EACH ROW
    EXECUTE FUNCTION lending.capital_contribution_event_is_append_only();

GRANT SELECT, INSERT ON lending.capital_contribution TO finapp_app;
GRANT SELECT, INSERT ON lending.capital_contribution_event TO finapp_app;
