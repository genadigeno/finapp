-- Risk policy versions as data (P13-TSK-002; ADR-0109, PHASE_13_PLAN.md sections 4, 5, 7.1 (T-pol), 7.2 (R1), 8, 12.2,
-- 12.3 and owner decisions C1, C7, C8; RISK_LIFECYCLES.md section 4.1; INV-RSK-05, INV-RSK-07, INV-AUD-04, INV-AUD-02,
-- INV-HIST-04). bnpl V002's shape (P12-TSK-004) - lending V003's with the Phase 11 gate's repairs and the Phase 12 -> 13
-- gate's A3-I1 rule applied forward from the first row.
--
-- A POLICY VERSION is one operation's rules as rows: each a closed RuleKind (code) with typed parameters (data), a weight
-- (its score contribution when it fires) and/or a hard decision, in a declared order; the SCORE BANDS mapping [0, 1000]
-- to the decisions the operation admits; the FAIL-SAFE BANDS mapping amount bands to ALLOW or DENY when the evaluation
-- is unavailable (C1 - the owner's answer, held as data so it changes by activation and never by release); the
-- MONITORING operation's RATING BANDS mapping the party's signal score to LOW, MEDIUM or HIGH; the challenge assurance
-- (MULTI_FACTOR), the override threshold (C7) and the hold validity (the withdrawal's `held_until`, default 72 h). It is
-- written for an engine version (RISK_ENGINE_V1), per operation, and changes only forward and only under four eyes:
--
--     PROPOSED --a different holder, MULTI_FACTOR--> ACTIVE --only beside its successor--> RETIRED
--         \--anyone (a withdrawal or a correction)--> REJECTED
--
-- WHY SIX TABLES FOR ONE VERSION (bnpl V002's reason, PHASE_13_PLAN.md section 8). The content carries amounts - the
-- override threshold, the fail-safe bounds, a rule's amount parameters - and every risk row carrying an amount is
-- INSERT-only (no money column is a balance; amount_minor + currency appear only on INSERT-only rows). The machine,
-- though, needs a row that moves (one ACTIVE per operation by partial unique, the activation locking the active row FOR
-- UPDATE, an evaluation pinning it FOR SHARE - both need an UPDATE privilege on some column). So the version is split
-- along that line, one id for all:
--
--     risk_policy_version       - the machine row: identity, status, who decided, the effective period. NO MONEY, so
--                                 it takes a column-scoped UPDATE of the decision columns, under the edge trigger.
--     risk_policy               - the scalar content (currency, the knobs): born with its version, never changed.
--     risk_rule                 - the rules, in order; risk_rule_parameter - each rule's typed parameters.
--     risk_policy_band          - the score, rating and fail-safe bands.
--     risk_policy_version_event - the history, append-only.
--
-- Held for EVERY writer: the edge trigger (born PROPOSED and undecided - no version is activated by insert or by
-- migration; identity frozen), the content immutable from insert, one ACTIVE version per operation (partial unique),
-- activator <> proposer by CHECK, a retirement committed only beside a later successor (deferred), a version committed
-- only with its content, its rules and its bands (deferred), the effective period stamped by the database AFTER the locks
-- (clock_timestamp(), lending V030's repair) and clamped on a backward clock (credit V014's rule, confirmed here as
-- RISK_LIFECYCLES.md section 8 asks), the engine one this build holds, every band's decision one of the vocabulary's and
-- every reason bounded and screened for instrument shapes (the reason screen's twin, below). Proposals and decisions of
-- one operation serialise on advisory namespace 13 on hashtext(operation) - A0, JdbcRiskPolicyStore.NAMESPACE - taken
-- exclusively by T-pol and SHARED by an evaluation's pin whose first FOR SHARE read found no ACTIVE row (the A3-I1 rule:
-- the pin never answers "neither", INV-RSK-05); the partial unique is the backstop.
--
-- Several PROPOSED versions per operation are admitted (the backlog entry: ten proposals leave ten versions numbered
-- without a gap); activation is monotonic in version number - the domain refuses a proposal numbered below the ACTIVE one
-- (risk.PolicyStale) and the successor trigger holds every writer to a LATER successor. Contiguity and coverage of the
-- bands, the exact parameter set of each kind and the operation's admitted decisions are the domain's judgement
-- (RiskPolicy, RuleParameters - judged whole before anything is locked); what a CHECK can hold without a subquery, it
-- holds here. Money is explicit (INV-MON-01): the content states its currency and scale once, every amount integer minor
-- units in them - no floating point anywhere. NOTHING IS SEEDED: production ships with no version (C8), so no operation is
-- judged until two persons activate one and OPERATIONS_RUNBOOK.md section 9's gate is passed. This migration names no
-- other schema (RISK_LIFECYCLES.md section 3).

-- ---------------------------------------------------------------------------------------------
-- The reason screen's twin (bnpl V002's functions - lending V002's, credit V016's - statement for statement, in this
-- schema: a schema owns its functions, ADR-0006). The domain screens first (RiskReasons, InstrumentShapes).
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION risk.luhn_valid(digits TEXT)
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
CREATE OR REPLACE FUNCTION risk.mod97_check_holds(compact TEXT)
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

-- The card number: InstrumentShapes.cardNumberIn.
CREATE OR REPLACE FUNCTION risk.holds_card_number_shape(body TEXT)
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
                    IF risk.luhn_valid(substr(grp, start_at, width)) THEN
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
                IF char_length(span) >= 12 AND risk.luhn_valid(span) THEN
                    RETURN true;
                END IF;
            END LOOP;
        END LOOP;
    END LOOP;
    RETURN false;
END;
$$;

-- The account identifier: InstrumentShapes.accountIdentifierAt.
CREATE OR REPLACE FUNCTION risk.holds_account_identifier_shape(body TEXT)
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
                            AND risk.mod97_check_holds(compact) THEN
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

CREATE OR REPLACE FUNCTION risk.holds_instrument_shape(body TEXT)
    RETURNS boolean
    LANGUAGE sql
    IMMUTABLE
    STRICT
AS $$
    SELECT risk.holds_card_number_shape(body)
        OR risk.holds_account_identifier_shape(body)
$$;

-- ---------------------------------------------------------------------------------------------
-- The machine row: no money.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE risk.risk_policy_version (
    id                uuid        NOT NULL,
    operation         text        NOT NULL,
    version           integer     NOT NULL,
    engine            text        NOT NULL,
    status            text        NOT NULL,
    proposed_by       text        NOT NULL,
    proposed_by_type  text        NOT NULL,
    proposed_at       timestamptz NOT NULL,
    proposal_reason   text        NOT NULL,
    decided_by        text,
    decided_by_type   text,
    decided_at        timestamptz,
    decision_reason   text,
    effective_from    timestamptz,
    effective_to      timestamptz,
    correlation_id    text        NOT NULL,
    CONSTRAINT risk_policy_version_pk PRIMARY KEY (id),
    -- The content's and the history's composite keys pin their operation to this row's.
    CONSTRAINT risk_policy_version_operation_pinned UNIQUE (id, operation),
    CONSTRAINT risk_policy_version_number_unique UNIQUE (operation, version),
    CONSTRAINT risk_policy_version_number_positive CHECK (version >= 1),
    -- RiskOperation, less CREDIT_SIGNAL: the credit signal carries no policy - it answers the profile's rating, which the
    -- MONITORING version's rating bands set (PHASE_13_PLAN.md section 12.1, RISK_LIFECYCLES.md section 8).
    CONSTRAINT risk_policy_version_operation_carries_a_policy CHECK (
        operation IN ('TRANSFER', 'WITHDRAWAL', 'CARD_PAYMENT', 'WALLET_PAYMENT', 'CROSS_BORDER', 'MONITORING')),
    -- RiskEngine: a version written for an engine version this build no longer holds is refused at activation (ADR-0109
    -- decision 8); here, one the build never held is refused at insert.
    CONSTRAINT risk_policy_version_engine_is_this_builds CHECK (engine IN ('RISK_ENGINE_V1')),
    -- RiskPolicyVersionStatus.
    CONSTRAINT risk_policy_version_status_is_known CHECK (status IN ('PROPOSED', 'ACTIVE', 'RETIRED', 'REJECTED')),
    CONSTRAINT risk_policy_version_proposal_reasoned CHECK (char_length(proposal_reason) BETWEEN 1 AND 1000),
    CONSTRAINT risk_policy_version_proposal_reason_no_instrument_shape CHECK (
        NOT risk.holds_instrument_shape(proposal_reason)),
    CONSTRAINT risk_policy_version_decision_coherent CHECK (
        (status = 'PROPOSED') = (decided_by IS NULL)
        AND (decided_by IS NULL) = (decided_by_type IS NULL)
        AND (decided_by IS NULL) = (decided_at IS NULL)
        AND (decided_by IS NULL) = (decision_reason IS NULL)),
    CONSTRAINT risk_policy_version_decision_reasoned CHECK (
        decision_reason IS NULL OR char_length(decision_reason) BETWEEN 1 AND 1000),
    CONSTRAINT risk_policy_version_decision_reason_no_instrument_shape CHECK (
        decision_reason IS NULL OR NOT risk.holds_instrument_shape(decision_reason)),
    -- FOUR EYES, for every writer and with no seed exemption (INV-AUD-04, INV-RSK-07): whoever activated a version did
    -- not propose it.
    CONSTRAINT risk_policy_version_four_eyes CHECK (status NOT IN ('ACTIVE', 'RETIRED') OR decided_by <> proposed_by),
    CONSTRAINT risk_policy_version_effective_coherent CHECK (
        (status IN ('ACTIVE', 'RETIRED')) = (effective_from IS NOT NULL)
        AND (status = 'RETIRED') = (effective_to IS NOT NULL)
        AND (effective_to IS NULL OR effective_to > effective_from))
);

COMMENT ON TABLE risk.risk_policy_version IS
    'A risk policy version''s machine (P13-TSK-002, ADR-0109): PROPOSED -> ACTIVE (a second person, MULTI_FACTOR) -> RETIRED (only beside its later successor), or PROPOSED -> REJECTED. One ACTIVE per operation; activator <> proposer by CHECK; identity frozen; the effective period stamped by the database after the locks, clamped on a backward clock; never activated by insert or by a migration. The content is risk.risk_policy, risk_rule, risk_rule_parameter and risk_policy_band, born with it and never changed. Every assessment pins the version that judged it (INV-HIST-04). No money.';

-- One ACTIVE per operation - the backstop beneath A0.
CREATE UNIQUE INDEX risk_policy_one_active ON risk.risk_policy_version (operation) WHERE status = 'ACTIVE';
CREATE INDEX risk_policy_version_effective ON risk.risk_policy_version (operation, effective_from)
    WHERE effective_from IS NOT NULL;

-- The edge trigger: bnpl V002's body - every stamp clock_timestamp(), the instant written after the lock the act waited
-- on (the Phase 11 gate's A2-F3), never the transaction's start.
CREATE OR REPLACE FUNCTION risk.risk_policy_version_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PROPOSED' OR NEW.decided_by IS NOT NULL OR NEW.decided_by_type IS NOT NULL
                OR NEW.decided_at IS NOT NULL OR NEW.decision_reason IS NOT NULL
                OR NEW.effective_from IS NOT NULL OR NEW.effective_to IS NOT NULL THEN
            RAISE EXCEPTION 'a risk policy version is born PROPOSED and undecided: no version is activated by insert (P13-TSK-002)';
        END IF;
        NEW.proposed_at := clock_timestamp();
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a risk policy version is never deleted (P13-TSK-002, INV-HIST-04)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.operation IS DISTINCT FROM OLD.operation
            OR NEW.version IS DISTINCT FROM OLD.version
            OR NEW.engine IS DISTINCT FROM OLD.engine
            OR NEW.proposed_by IS DISTINCT FROM OLD.proposed_by
            OR NEW.proposed_by_type IS DISTINCT FROM OLD.proposed_by_type
            OR NEW.proposed_at IS DISTINCT FROM OLD.proposed_at
            OR NEW.proposal_reason IS DISTINCT FROM OLD.proposal_reason
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a risk policy version''s identity is frozen (P13-TSK-002, INV-HIST-04, INV-RSK-05)';
    END IF;
    IF NOT ((OLD.status = 'PROPOSED' AND NEW.status IN ('ACTIVE', 'REJECTED'))
            OR (OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED')) THEN
        RAISE EXCEPTION 'a risk policy version moves PROPOSED -> ACTIVE | REJECTED, ACTIVE -> RETIRED; % -> % is not an edge (P13-TSK-002)', OLD.status, NEW.status;
    END IF;
    IF OLD.status = 'PROPOSED' THEN
        NEW.decided_at := clock_timestamp();
        -- Never before the operation's latest end - its predecessor's, retired first in this transaction - whatever the
        -- database clock did since (credit V014's rule, confirmed for this table).
        NEW.effective_from := CASE WHEN NEW.status = 'ACTIVE' THEN GREATEST(transaction_timestamp(),
                (SELECT max(v.effective_to) FROM risk.risk_policy_version v WHERE v.operation = NEW.operation)) END;
        NEW.effective_to := NULL;
    ELSE
        IF NEW.decided_by IS DISTINCT FROM OLD.decided_by OR NEW.decided_by_type IS DISTINCT FROM OLD.decided_by_type
                OR NEW.decided_at IS DISTINCT FROM OLD.decided_at
                OR NEW.decision_reason IS DISTINCT FROM OLD.decision_reason
                OR NEW.effective_from IS DISTINCT FROM OLD.effective_from THEN
            RAISE EXCEPTION 'an activation''s decision is frozen; a retirement records only its end (P13-TSK-002)';
        END IF;
        -- Written after the activation's lock on this row (FOR UPDATE, behind every pin's FOR SHARE), so after every
        -- assessment made under it; never before the transaction's start, so the successor's effective_from (the greater
        -- of the start and this) is exactly this end; never at or before its own start, whatever the clock says.
        NEW.effective_to := GREATEST(clock_timestamp(), transaction_timestamp(), OLD.effective_from + interval '1 microsecond');
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER risk_policy_version_permits_only_machine_edges
    BEFORE INSERT OR UPDATE OR DELETE ON risk.risk_policy_version
    FOR EACH ROW
    EXECUTE FUNCTION risk.risk_policy_version_permits_only_machine_edges();

CREATE OR REPLACE FUNCTION risk.risk_policy_is_never_truncated()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'the risk policy history is never truncated (P13-TSK-002, INV-HIST-04)';
END;
$$;

CREATE TRIGGER risk_policy_version_is_never_truncated
    BEFORE TRUNCATE ON risk.risk_policy_version
    FOR EACH STATEMENT
    EXECUTE FUNCTION risk.risk_policy_is_never_truncated();

-- A retirement commits only beside its successor: at COMMIT, a LATER version of the operation must be ACTIVE, starting
-- where the retired one ends - so the effective periods meet and one version is active at every instant (INV-RSK-05).
CREATE OR REPLACE FUNCTION risk.risk_policy_version_retires_only_beside_its_successor()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'RETIRED' AND NOT EXISTS (
            SELECT 1 FROM risk.risk_policy_version successor
             WHERE successor.operation = NEW.operation AND successor.status = 'ACTIVE' AND successor.version > NEW.version
               AND successor.effective_from = NEW.effective_to) THEN
        RAISE EXCEPTION 'risk policy version % retired with no later ACTIVE successor starting where it ends (P13-TSK-002)', NEW.version;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER risk_policy_version_retires_only_beside_its_successor
    AFTER UPDATE ON risk.risk_policy_version
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION risk.risk_policy_version_retires_only_beside_its_successor();

-- ---------------------------------------------------------------------------------------------
-- The content: born with its version, never changed. The scalar knobs first.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE risk.risk_policy (
    policy_version_id         uuid     NOT NULL,
    operation                 text     NOT NULL,
    currency                  char(3)  NOT NULL,
    scale                     smallint NOT NULL,
    challenge_assurance       text,
    override_threshold_minor  bigint,
    hold_validity_hours       integer,
    CONSTRAINT risk_policy_pk PRIMARY KEY (policy_version_id),
    CONSTRAINT risk_policy_version_fk FOREIGN KEY (policy_version_id, operation)
        REFERENCES risk.risk_policy_version (id, operation),
    -- The reference currency (the platform's EUR, PHASE_13_PLAN.md section 2.2 C1); every amount in its scale.
    CONSTRAINT risk_policy_currency_is_the_references CHECK (currency = 'EUR' AND scale BETWEEN 0 AND 9),
    -- RiskAssurance: the one assurance that satisfies a CHALLENGE.
    CONSTRAINT risk_policy_challenge_assurance_satisfies_a_challenge CHECK (
        challenge_assurance IS NULL OR challenge_assurance = 'MULTI_FACTOR'),
    CONSTRAINT risk_policy_override_threshold_bounded CHECK (
        override_threshold_minor IS NULL OR override_threshold_minor >= 0),
    -- The hold validity: 1 hour to 30 days (the default 72 h is the door's).
    CONSTRAINT risk_policy_hold_validity_bounded CHECK (
        hold_validity_hours IS NULL OR hold_validity_hours BETWEEN 1 AND 720),
    -- Each operation's own knobs and no other's: a judged operation carries the challenge assurance and the override
    -- threshold (C7); the hold validity exactly where HOLD is admitted (the withdrawal; the card payment joins with
    -- -010's second slice, which widens this CHECK beside RiskDecision.admittedBy); the sweep's policy carries none.
    CONSTRAINT risk_policy_knobs_coherent CHECK (
        (operation = 'MONITORING'
            AND challenge_assurance IS NULL AND override_threshold_minor IS NULL AND hold_validity_hours IS NULL)
        OR (operation IN ('TRANSFER', 'WITHDRAWAL', 'CARD_PAYMENT', 'WALLET_PAYMENT', 'CROSS_BORDER')
            AND challenge_assurance IS NOT NULL AND override_threshold_minor IS NOT NULL
            AND ((operation = 'WITHDRAWAL') = (hold_validity_hours IS NOT NULL))))
);

COMMENT ON TABLE risk.risk_policy IS
    'A risk policy version''s scalar content (P13-TSK-002, ADR-0109): the currency every amount of the version is in, the challenge assurance, the override threshold (C7, RESTRICTED-FINANCIAL) and the hold validity. Born with its version in the proposing transaction and never changed for any role (INV-RSK-05, INV-HIST-04) - INSERT-only.';

-- ---------------------------------------------------------------------------------------------
-- The rules, in the version's declared order, and each rule's typed parameters.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE risk.risk_rule (
    policy_version_id  uuid    NOT NULL,
    ordinal            integer NOT NULL,
    kind               text    NOT NULL,
    weight             integer,
    hard_decision      text,
    CONSTRAINT risk_rule_pk PRIMARY KEY (policy_version_id, ordinal),
    CONSTRAINT risk_rule_policy_fk FOREIGN KEY (policy_version_id) REFERENCES risk.risk_policy (policy_version_id),
    CONSTRAINT risk_rule_ordinal_positive CHECK (ordinal >= 1),
    -- RuleKind: the twenty-two, code never configuration; a kind written for an operation it does not declare is the
    -- domain's refusal (RuleKind.appliesTo).
    CONSTRAINT risk_rule_kind_is_known CHECK (kind IN (
        'AMOUNT_BAND', 'VELOCITY_COUNT', 'VELOCITY_SUM', 'NEW_PAYEE', 'NEW_DEVICE', 'SESSION_ASSURANCE_BELOW',
        'RECENT_AUTH_FAILURES', 'RECENT_CREDENTIAL_CHANGE', 'RECENT_RECOVERY', 'ACCOUNT_AGE_BELOW', 'KYC_NOT_APPROVED',
        'SCREENING_HIT', 'PROFILE_RATING', 'MERCHANT_SUSPENDED', 'CORRIDOR_HIGH_RISK', 'DORMANT_THEN_ACTIVE',
        'RAPID_IN_OUT', 'STRUCTURING', 'ROUND_AMOUNT', 'REFUND_RATIO', 'CHARGEBACK_RATIO', 'BNPL_MULTI_APPLICATION')),
    -- A weight is a score contribution, 1 to the score's ceiling.
    CONSTRAINT risk_rule_weight_bounded CHECK (weight IS NULL OR weight BETWEEN 1 AND 1000),
    -- RiskDecision; whether the operation admits it is the domain's judgement.
    CONSTRAINT risk_rule_hard_decision_is_known CHECK (
        hard_decision IS NULL OR hard_decision IN ('DENY', 'HOLD', 'REVIEW', 'CHALLENGE', 'ALLOW')),
    -- A weight and/or a hard decision.
    CONSTRAINT risk_rule_contributes CHECK (weight IS NOT NULL OR hard_decision IS NOT NULL)
);

COMMENT ON TABLE risk.risk_rule IS
    'A risk policy version''s rules in its declared order (P13-TSK-002, ADR-0109): each a closed RuleKind with a weight (its score contribution when it fires) and/or a hard decision. Born with the version, INSERT-only for every role; the engine (-003) evaluates every row in this order.';

CREATE TABLE risk.risk_rule_parameter (
    policy_version_id  uuid    NOT NULL,
    rule_ordinal       integer NOT NULL,
    name               text    NOT NULL,
    type               text    NOT NULL,
    value_count        bigint,
    value_minor        bigint,
    value_bps          integer,
    value_days         integer,
    value_hours        integer,
    value_window       text,
    value_subject_kind text,
    value_corridors    text[],
    value_assurance    text,
    value_rating       text,
    CONSTRAINT risk_rule_parameter_pk PRIMARY KEY (policy_version_id, rule_ordinal, name),
    CONSTRAINT risk_rule_parameter_rule_fk FOREIGN KEY (policy_version_id, rule_ordinal)
        REFERENCES risk.risk_rule (policy_version_id, ordinal),
    -- RuleKind.Parameter: lower snake case, the kind's own schema (which names exist is the domain's judgement).
    CONSTRAINT risk_rule_parameter_name_shape CHECK (name ~ '^[a-z][a-z0-9_]*$'),
    -- RuleKind.ParameterType.
    CONSTRAINT risk_rule_parameter_type_is_known CHECK (type IN (
        'COUNT', 'MINOR_AMOUNT', 'BASIS_POINTS', 'DAYS', 'HOURS', 'WINDOW', 'SUBJECT_KIND', 'CORRIDOR_SET', 'ASSURANCE',
        'RATING')),
    -- Exactly the column of the type holds the value, within the type's bounds; every other column NULL.
    CONSTRAINT risk_rule_parameter_value_typed CHECK (
        (type = 'COUNT') = (value_count IS NOT NULL)
        AND (type = 'MINOR_AMOUNT') = (value_minor IS NOT NULL)
        AND (type = 'BASIS_POINTS') = (value_bps IS NOT NULL)
        AND (type = 'DAYS') = (value_days IS NOT NULL)
        AND (type = 'HOURS') = (value_hours IS NOT NULL)
        AND (type = 'WINDOW') = (value_window IS NOT NULL)
        AND (type = 'SUBJECT_KIND') = (value_subject_kind IS NOT NULL)
        AND (type = 'CORRIDOR_SET') = (value_corridors IS NOT NULL)
        AND (type = 'ASSURANCE') = (value_assurance IS NOT NULL)
        AND (type = 'RATING') = (value_rating IS NOT NULL)
        AND (value_count IS NULL OR value_count >= 0)
        AND (value_minor IS NULL OR value_minor >= 0)
        AND (value_bps IS NULL OR value_bps BETWEEN 0 AND 10000)
        AND (value_days IS NULL OR value_days >= 1)
        AND (value_hours IS NULL OR value_hours >= 1)
        AND (value_window IS NULL OR value_window IN ('H1', 'D1', 'D7', 'D30'))
        AND (value_subject_kind IS NULL
            -- The whole SubjectKind, as the domain admits it: IDENTITY and CREDIT_AGREEMENT joined the vocabulary with
            -- P13-TSK-005 (merged with this migration in wave B, 2026-10-23, before either had reached any environment
            -- but a test container - C5); -006's velocity dimensions narrow what a VELOCITY_* rule may name.
            OR value_subject_kind IN ('PARTY', 'ACCOUNT', 'DEVICE', 'MERCHANT', 'BENEFICIARY', 'SESSION', 'IDENTITY', 'CREDIT_AGREEMENT'))
        AND (value_corridors IS NULL OR cardinality(value_corridors) >= 1)
        AND (value_assurance IS NULL OR value_assurance IN ('SINGLE_FACTOR', 'MULTI_FACTOR'))
        AND (value_rating IS NULL OR value_rating IN ('LOW', 'MEDIUM', 'HIGH', 'PROHIBITED')))
);

COMMENT ON TABLE risk.risk_rule_parameter IS
    'A risk rule''s typed parameters (P13-TSK-002, ADR-0109): one row per parameter the rule''s kind declares, the value in exactly the column of its type - a count, an amount in minor units of the version''s currency, basis points, days, hours, a window kind, a velocity dimension, a set of corridor codes, an assurance or a rating. Born with the version, INSERT-only.';

-- ---------------------------------------------------------------------------------------------
-- The bands: score (judged operations), rating (the sweep's), fail-safe (judged operations, C1).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE risk.risk_policy_band (
    policy_version_id  uuid    NOT NULL,
    band_kind          text    NOT NULL,
    ordinal            integer NOT NULL,
    lower_bound        bigint  NOT NULL,
    upper_bound        bigint,
    decision           text,
    rating             text,
    CONSTRAINT risk_policy_band_pk PRIMARY KEY (policy_version_id, band_kind, ordinal),
    CONSTRAINT risk_policy_band_policy_fk FOREIGN KEY (policy_version_id) REFERENCES risk.risk_policy (policy_version_id),
    CONSTRAINT risk_policy_band_kind_is_known CHECK (band_kind IN ('SCORE', 'RATING', 'FAIL_SAFE')),
    CONSTRAINT risk_policy_band_ordinal_positive CHECK (ordinal >= 1),
    -- Each kind's own shape: a score band is a closed interval of [0, 1000] to a decision; a rating band the same to a
    -- rating a band may set (never PROHIBITED - a disposition's alone, INV-RSK-07); a fail-safe band an amount interval
    -- [lower, upper) in minor units, the last open-ended, to ALLOW or DENY (C1). Contiguity and coverage are the
    -- domain's judgement (RiskPolicy).
    CONSTRAINT risk_policy_band_shape_coherent CHECK (
        (band_kind = 'SCORE'
            AND lower_bound BETWEEN 0 AND 1000 AND upper_bound BETWEEN lower_bound AND 1000
            AND decision IN ('DENY', 'HOLD', 'REVIEW', 'CHALLENGE', 'ALLOW') AND rating IS NULL)
        OR (band_kind = 'RATING'
            AND lower_bound BETWEEN 0 AND 1000 AND upper_bound BETWEEN lower_bound AND 1000
            AND decision IS NULL AND rating IN ('LOW', 'MEDIUM', 'HIGH'))
        OR (band_kind = 'FAIL_SAFE'
            AND lower_bound >= 0 AND (upper_bound IS NULL OR upper_bound > lower_bound)
            AND decision IN ('ALLOW', 'DENY') AND rating IS NULL))
);

COMMENT ON TABLE risk.risk_policy_band IS
    'A risk policy version''s bands (P13-TSK-002, ADR-0108, ADR-0109): SCORE bands mapping [0, 1000] to the decisions the operation admits; RATING bands (the MONITORING version) mapping the party''s signal score to LOW, MEDIUM or HIGH; FAIL_SAFE bands mapping amount bands (minor units, RESTRICTED-FINANCIAL) to ALLOW or DENY when the evaluation is unavailable (owner decision C1). Born with the version, INSERT-only.';

-- The content is immutable from insert, for every role, and born only with a PROPOSED version - in its proposing
-- transaction, never late: one function, four triggers.
CREATE OR REPLACE FUNCTION risk.risk_policy_content_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a risk policy version''s content is immutable from insert, for every role (P13-TSK-002, INV-RSK-05, INV-HIST-04)';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM risk.risk_policy_version v
                    WHERE v.id = NEW.policy_version_id AND v.status = 'PROPOSED') THEN
        RAISE EXCEPTION 'a risk policy''s content is born with its PROPOSED version (P13-TSK-002)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER risk_policy_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON risk.risk_policy
    FOR EACH ROW
    EXECUTE FUNCTION risk.risk_policy_content_is_born_once();

CREATE TRIGGER risk_rule_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON risk.risk_rule
    FOR EACH ROW
    EXECUTE FUNCTION risk.risk_policy_content_is_born_once();

CREATE TRIGGER risk_rule_parameter_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON risk.risk_rule_parameter
    FOR EACH ROW
    EXECUTE FUNCTION risk.risk_policy_content_is_born_once();

CREATE TRIGGER risk_policy_band_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON risk.risk_policy_band
    FOR EACH ROW
    EXECUTE FUNCTION risk.risk_policy_content_is_born_once();

CREATE TRIGGER risk_policy_is_never_truncated
    BEFORE TRUNCATE ON risk.risk_policy
    FOR EACH STATEMENT
    EXECUTE FUNCTION risk.risk_policy_is_never_truncated();

CREATE TRIGGER risk_rule_is_never_truncated
    BEFORE TRUNCATE ON risk.risk_rule
    FOR EACH STATEMENT
    EXECUTE FUNCTION risk.risk_policy_is_never_truncated();

CREATE TRIGGER risk_rule_parameter_is_never_truncated
    BEFORE TRUNCATE ON risk.risk_rule_parameter
    FOR EACH STATEMENT
    EXECUTE FUNCTION risk.risk_policy_is_never_truncated();

CREATE TRIGGER risk_policy_band_is_never_truncated
    BEFORE TRUNCATE ON risk.risk_policy_band
    FOR EACH STATEMENT
    EXECUTE FUNCTION risk.risk_policy_is_never_truncated();

-- A version commits only whole: at COMMIT, the proposal's content row, at least one rule, and the bands its operation
-- needs (the sweep's rating bands; a judged operation's score and fail-safe bands) must exist.
CREATE OR REPLACE FUNCTION risk.risk_policy_version_has_its_content()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM risk.risk_policy p WHERE p.policy_version_id = NEW.id) THEN
        RAISE EXCEPTION 'risk policy version % committed without its content (P13-TSK-002)', NEW.version;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM risk.risk_rule r WHERE r.policy_version_id = NEW.id) THEN
        RAISE EXCEPTION 'risk policy version % committed without a rule (P13-TSK-002)', NEW.version;
    END IF;
    IF NEW.operation = 'MONITORING' THEN
        IF NOT EXISTS (SELECT 1 FROM risk.risk_policy_band b
                        WHERE b.policy_version_id = NEW.id AND b.band_kind = 'RATING') THEN
            RAISE EXCEPTION 'monitoring policy version % committed without its rating bands (P13-TSK-002)', NEW.version;
        END IF;
    ELSE
        IF NOT EXISTS (SELECT 1 FROM risk.risk_policy_band b
                        WHERE b.policy_version_id = NEW.id AND b.band_kind = 'SCORE')
                OR NOT EXISTS (SELECT 1 FROM risk.risk_policy_band b
                        WHERE b.policy_version_id = NEW.id AND b.band_kind = 'FAIL_SAFE') THEN
            RAISE EXCEPTION 'risk policy version % committed without its score and fail-safe bands (P13-TSK-002, C1)', NEW.version;
        END IF;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER risk_policy_version_has_its_content
    AFTER INSERT ON risk.risk_policy_version
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION risk.risk_policy_version_has_its_content();

-- ---------------------------------------------------------------------------------------------
-- The history.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE risk.risk_policy_version_event (
    id                 uuid        NOT NULL,
    policy_version_id  uuid        NOT NULL,
    operation          text        NOT NULL,
    from_status        text,
    to_status          text        NOT NULL,
    actor              text        NOT NULL,
    actor_type         text        NOT NULL,
    reason             text        NOT NULL,
    occurred_at        timestamptz NOT NULL,
    correlation_id     text        NOT NULL,
    CONSTRAINT risk_policy_version_event_pk PRIMARY KEY (id),
    CONSTRAINT risk_policy_version_event_version_fk FOREIGN KEY (policy_version_id, operation)
        REFERENCES risk.risk_policy_version (id, operation),
    -- RiskPolicyVersionStatus, and its only edges.
    CONSTRAINT risk_policy_version_event_edge CHECK (
        (from_status IS NULL AND to_status = 'PROPOSED')
        OR (from_status = 'PROPOSED' AND to_status IN ('ACTIVE', 'REJECTED'))
        OR (from_status = 'ACTIVE' AND to_status = 'RETIRED')),
    CONSTRAINT risk_policy_version_event_reason_bounded CHECK (char_length(reason) BETWEEN 1 AND 1000),
    CONSTRAINT risk_policy_version_event_reason_no_instrument_shape CHECK (NOT risk.holds_instrument_shape(reason))
);

CREATE INDEX risk_policy_version_event_by_version ON risk.risk_policy_version_event (policy_version_id, occurred_at);

COMMENT ON TABLE risk.risk_policy_version_event IS
    'A risk policy version''s machine as history (P13-TSK-002): every edge, its actor, reason (RESTRICTED-PII) and correlation. Append-only for every writer.';

CREATE OR REPLACE FUNCTION risk.risk_policy_version_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a risk policy version event is append-only (P13-TSK-002, INV-HIST-01)';
    END IF;
    NEW.occurred_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER risk_policy_version_event_is_append_only
    BEFORE INSERT OR UPDATE OR DELETE ON risk.risk_policy_version_event
    FOR EACH ROW
    EXECUTE FUNCTION risk.risk_policy_version_event_is_append_only();

CREATE TRIGGER risk_policy_version_event_is_never_truncated
    BEFORE TRUNCATE ON risk.risk_policy_version_event
    FOR EACH STATEMENT
    EXECUTE FUNCTION risk.risk_policy_is_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The grants (risk's first, written beside the tables they apply to - V001 sets no default privilege): the machine
-- row's decision columns; the content and the history INSERT-only; no DELETE for any role.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON risk.risk_policy_version TO finapp_app;
GRANT UPDATE (status, decided_by, decided_by_type, decided_at, decision_reason, effective_from, effective_to)
    ON risk.risk_policy_version TO finapp_app;
GRANT SELECT, INSERT ON risk.risk_policy TO finapp_app;
GRANT SELECT, INSERT ON risk.risk_rule TO finapp_app;
GRANT SELECT, INSERT ON risk.risk_rule_parameter TO finapp_app;
GRANT SELECT, INSERT ON risk.risk_policy_band TO finapp_app;
GRANT SELECT, INSERT ON risk.risk_policy_version_event TO finapp_app;
