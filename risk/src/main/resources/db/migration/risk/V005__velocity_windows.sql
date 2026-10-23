-- Velocity windows, and the velocity dimension as its own vocabulary (P13-TSK-006; PHASE_13_PLAN.md sections 7.1
-- (T-eval), 7.2 (R2), 7.4, 8, 12.4; RISK_LIFECYCLES.md section 4.3; ADR-0108; INV-RSK-03, INV-CON-03, INV-MON-01).
--
-- A VELOCITY WINDOW is a durable counter: how many permitted movements, and how much money, one subject moved in one
-- window - "ten transfers per day per source account", "EUR 5,000 per day per party". It is a limit, not money: it is
-- never a balance, never posted, never held. One row per primary key (dimension, subject_ref, window_kind,
-- window_start), holding a count and a sum in one currency at one scale. Not a machine (no status, no edge):
--
--     born by the first permitted movement in the window (count 1)  --each later permitted movement-->  count + 1,
--     sum + amount  ...while the database clock is inside the window. Never back, never re-keyed, never deleted.
--
-- WHO WRITES IT, AND WHEN (T-eval, the judged command's own transaction; VelocityWindows in the domain). After the
-- caller's money locks and the policy pin (R1): (R2a) one transaction-scoped advisory lock per (dimension, subject) the
-- movement carries, namespace 131, in sorted order - so the read below and the upsert after the decision are ONE
-- serialised step per subject, whether or not the row exists yet (the plan's unlocked read let two movements from two
-- accounts of one party both read 9 against a threshold of 10; INV-CON-03); then clock_timestamp() - the window start
-- is cut from THIS instant, never an instance clock; then the read; then the engine; then, ONLY when the decision
-- permits the movement (ALLOW, or a satisfied CHALLENGE), (R2) one statement per window in sorted key order:
--
--     INSERT ... ON CONFLICT (dimension, subject_ref, window_kind, window_start) DO UPDATE
--         SET count = count + 1, sum_minor = sum_minor + EXCLUDED.sum_minor  ...  RETURNING count, sum_minor
--
-- The row lock the statement takes is the arbiter beneath; a refused movement never reaches it, so it counts nothing;
-- a rolled-back money transaction rolls its increments back with it.
--
-- WHAT THE DATABASE HOLDS FOR EVERY ROLE, the migrator included (velocity_window_counts_forward): a window is born
-- with count 1; an update is exactly count + 1 with a sum that does not decrease and the key, currency and scale
-- unchanged; a window is written only while the database clock is inside it (one minute's grace after its end for an
-- evaluation in flight at the boundary) - so a start computed from an instance clock running ahead is refused outright;
-- no DELETE and no TRUNCATE (velocity_window_is_never_removed). The application role holds SELECT, INSERT and UPDATE of
-- the two counters alone - the one-statement upsert's columns - and nothing else.
--
-- THE WINDOWS. Tumbling buckets of fixed length anchored at Monday 1970-01-05 00:00 UTC: H1 on the hour, D1 at UTC
-- midnight, D7 ISO weeks, D30 thirty-day buckets from the anchor. One rule, in Java (WindowStarts) and here
-- (date_bin), so a misaligned start is refused by CHECK.
--
-- THE VELOCITY DIMENSION (the coordinator's decision at wave C, from P13-TSK-003's finding). A rule's `dimension`
-- parameter was a SubjectKind in risk V002 (type SUBJECT_KIND, column value_subject_kind); a subject kind says what a
-- thing IS and cannot keep a transfer's source and destination accounts apart, so it becomes VelocityDimension
-- { PARTY, SOURCE_ACCOUNT, DESTINATION, DEVICE, MERCHANT } - the column renamed (it always held "a velocity dimension"),
-- the type and value CHECKs re-made. SUBJECT_KIND leaves the parameter types: no kind uses it any more. V002 is merged
-- and never edited; this migration carries the change.

-- ---------------------------------------------------------------------------------------------
-- 1. The parameter table: VELOCITY_DIMENSION in place of SUBJECT_KIND.
-- ---------------------------------------------------------------------------------------------

-- A policy's content is born once for EVERY role (risk V002's risk_policy_content_is_born_once), so a SUBJECT_KIND row
-- could not be converted in place by any statement here. None exists outside a test container (owner decision C5: no
-- policy version has reached an environment); where one does, the migration refuses and a reviewed data migration is
-- owed - never a silent reinterpretation of a stored policy.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM risk.risk_rule_parameter WHERE type = 'SUBJECT_KIND') THEN
        RAISE EXCEPTION 'risk V005 retypes the velocity dimension, and a SUBJECT_KIND parameter is stored: policy content is born once for every role - this database needs a reviewed data migration (P13-TSK-006)';
    END IF;
END;
$$;

ALTER TABLE risk.risk_rule_parameter DROP CONSTRAINT risk_rule_parameter_value_typed;

ALTER TABLE risk.risk_rule_parameter DROP CONSTRAINT risk_rule_parameter_type_is_known;

ALTER TABLE risk.risk_rule_parameter RENAME COLUMN value_subject_kind TO value_velocity_dimension;

-- RuleKind.ParameterType, in its declaration order.
ALTER TABLE risk.risk_rule_parameter ADD CONSTRAINT risk_rule_parameter_type_is_known CHECK (type IN (
    'COUNT', 'MINOR_AMOUNT', 'BASIS_POINTS', 'DAYS', 'HOURS', 'WINDOW', 'VELOCITY_DIMENSION', 'CORRIDOR_SET', 'ASSURANCE',
    'RATING')
);

-- Exactly the column of the type holds the value, within the type's bounds; every other column NULL - V002's rule with
-- the velocity dimension's column and its closed list.
ALTER TABLE risk.risk_rule_parameter ADD CONSTRAINT risk_rule_parameter_value_typed CHECK (
    (type = 'COUNT') = (value_count IS NOT NULL)
    AND (type = 'MINOR_AMOUNT') = (value_minor IS NOT NULL)
    AND (type = 'BASIS_POINTS') = (value_bps IS NOT NULL)
    AND (type = 'DAYS') = (value_days IS NOT NULL)
    AND (type = 'HOURS') = (value_hours IS NOT NULL)
    AND (type = 'WINDOW') = (value_window IS NOT NULL)
    AND (type = 'VELOCITY_DIMENSION') = (value_velocity_dimension IS NOT NULL)
    AND (type = 'CORRIDOR_SET') = (value_corridors IS NOT NULL)
    AND (type = 'ASSURANCE') = (value_assurance IS NOT NULL)
    AND (type = 'RATING') = (value_rating IS NOT NULL)
    AND (value_count IS NULL OR value_count >= 0)
    AND (value_minor IS NULL OR value_minor >= 0)
    AND (value_bps IS NULL OR value_bps BETWEEN 0 AND 10000)
    AND (value_days IS NULL OR value_days >= 1)
    AND (value_hours IS NULL OR value_hours >= 1)
    AND (value_window IS NULL OR value_window IN ('H1', 'D1', 'D7', 'D30'))
    AND (value_velocity_dimension IS NULL
        OR value_velocity_dimension IN ('PARTY', 'SOURCE_ACCOUNT', 'DESTINATION', 'DEVICE', 'MERCHANT'))
    AND (value_corridors IS NULL OR cardinality(value_corridors) >= 1)
    AND (value_assurance IS NULL OR value_assurance IN ('SINGLE_FACTOR', 'MULTI_FACTOR'))
    AND (value_rating IS NULL OR value_rating IN ('LOW', 'MEDIUM', 'HIGH', 'PROHIBITED'))
);

COMMENT ON COLUMN risk.risk_rule_parameter.value_velocity_dimension IS
    'A VELOCITY_DIMENSION parameter''s value - the role a velocity rule counts per: PARTY, SOURCE_ACCOUNT, DESTINATION (an account or a beneficiary), DEVICE or MERCHANT (P13-TSK-006; value_subject_kind until risk V005).';

-- ---------------------------------------------------------------------------------------------
-- 2. The window's length, and the table.
-- ---------------------------------------------------------------------------------------------

-- WindowKind's lengths, fixed: a day is 86,400 seconds here and in WindowStarts (date_bin reads a day so).
CREATE OR REPLACE FUNCTION risk.velocity_window_length(kind text)
    RETURNS interval
    LANGUAGE sql
    IMMUTABLE
    STRICT
AS $$
    SELECT CASE kind
        WHEN 'H1' THEN interval '1 hour'
        WHEN 'D1' THEN interval '1 day'
        WHEN 'D7' THEN interval '7 days'
        WHEN 'D30' THEN interval '30 days'
    END
$$;

CREATE TABLE risk.velocity_window (
    -- VelocityDimension: the role the subject plays in the movements counted.
    dimension        text        NOT NULL,

    -- The subject, by identifier only (ADR-0029: no constraint leaves this schema): a canonical UUID - a party, an
    -- account, a beneficiary, a merchant - or, for DEVICE, identity's SHA-256 of the description, 64 lowercase hex
    -- characters, never the description (OQ-4).
    subject_ref      text        NOT NULL,

    window_kind      text        NOT NULL,

    -- The window's start, cut from clock_timestamp() read after the caller's locks (R2a) - never an instance clock.
    window_start     timestamptz NOT NULL,

    -- The permitted movements counted, and their sum in minor units (INV-MON-01) - the figures every later decision of
    -- this subject reads. Bounded by the engine's own reading bounds (EvaluationInputs.MAX_COUNT, MAX_MINOR), so no
    -- window can hold a figure the engine could not read.
    count            bigint      NOT NULL,
    sum_minor        bigint      NOT NULL,
    currency         char(3)     NOT NULL,
    scale            smallint    NOT NULL,

    -- The database clock at the latest count, stamped by the trigger - the proof's "pending" horizon.
    last_counted_at  timestamptz NOT NULL,

    CONSTRAINT velocity_window_pk PRIMARY KEY (dimension, subject_ref, window_kind, window_start),

    CONSTRAINT velocity_window_dimension_is_known
        CHECK (dimension IN ('PARTY', 'SOURCE_ACCOUNT', 'DESTINATION', 'DEVICE', 'MERCHANT')),

    CONSTRAINT velocity_window_kind_is_known CHECK (window_kind IN ('H1', 'D1', 'D7', 'D30')),

    CONSTRAINT velocity_window_subject_ref_shape CHECK (
        (dimension = 'DEVICE' AND subject_ref ~ '^[0-9a-f]{64}$')
        OR (dimension <> 'DEVICE'
            AND subject_ref ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')),

    CONSTRAINT velocity_window_start_is_aligned CHECK (
        window_start = date_bin(risk.velocity_window_length(window_kind), window_start,
                                TIMESTAMPTZ '1970-01-05 00:00:00+00')),

    CONSTRAINT velocity_window_count_bounded CHECK (count BETWEEN 1 AND 1000000000),

    CONSTRAINT velocity_window_sum_bounded CHECK (sum_minor BETWEEN 0 AND 100000000000000),

    CONSTRAINT velocity_window_currency_shape CHECK (currency ~ '^[A-Z]{3}$'),

    CONSTRAINT velocity_window_scale_bounded CHECK (scale BETWEEN 0 AND 9),

    CONSTRAINT velocity_window_counted_inside CHECK (last_counted_at >= window_start)
);

-- The proof's read: every live window (its end not yet past).
CREATE INDEX velocity_window_by_start ON risk.velocity_window (window_start);

COMMENT ON TABLE risk.velocity_window IS
    'Durable velocity windows (P13-TSK-006, INV-RSK-03): a count and a sum of the PERMITTED movements of one subject in one tumbling window, written in the judged command''s own transaction after its locks - one INSERT ... ON CONFLICT DO UPDATE ... RETURNING per window in sorted key order, behind the subject''s advisory lock (namespace 131, R2a) - so a refused or rolled-back movement counts nothing and ten concurrent movements of one subject count ten. Never a balance; counts forward only; never deleted. VelocityProof recomputes the provable windows from the monitored transactions and reports, never corrects.';

-- ---------------------------------------------------------------------------------------------
-- 3. Forward only, on the database clock, for every role.
-- ---------------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION risk.velocity_window_counts_forward()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    counted_at timestamptz := clock_timestamp();
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.count <> 1 THEN
            RAISE EXCEPTION 'a velocity window is born by one permitted movement: its count starts at 1 (P13-TSK-006, INV-RSK-03)'
                USING ERRCODE = 'check_violation';
        END IF;
    ELSE
        IF NEW.dimension IS DISTINCT FROM OLD.dimension
           OR NEW.subject_ref IS DISTINCT FROM OLD.subject_ref
           OR NEW.window_kind IS DISTINCT FROM OLD.window_kind
           OR NEW.window_start IS DISTINCT FROM OLD.window_start
           OR NEW.currency IS DISTINCT FROM OLD.currency
           OR NEW.scale IS DISTINCT FROM OLD.scale THEN
            RAISE EXCEPTION 'a velocity window''s key, currency and scale never change (P13-TSK-006, INV-RSK-03)'
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.count <> OLD.count + 1 THEN
            RAISE EXCEPTION 'a velocity window counts forward by exactly one movement per update - never back, never twice (P13-TSK-006, INV-RSK-03)'
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.sum_minor < OLD.sum_minor THEN
            RAISE EXCEPTION 'a velocity window''s sum never decreases (P13-TSK-006, INV-RSK-03)'
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    IF NEW.window_start > counted_at
       OR counted_at >= NEW.window_start + risk.velocity_window_length(NEW.window_kind) + interval '1 minute' THEN
        RAISE EXCEPTION 'a velocity window is counted only while the database clock is inside it - its start is cut from clock_timestamp() after the caller''s locks, never from an instance clock (P13-TSK-006, INV-RSK-03)'
            USING ERRCODE = 'check_violation';
    END IF;
    NEW.last_counted_at := counted_at;
    RETURN NEW;
END;
$$;

CREATE TRIGGER velocity_window_counts_forward
    BEFORE INSERT OR UPDATE ON risk.velocity_window
    FOR EACH ROW
    EXECUTE FUNCTION risk.velocity_window_counts_forward();

CREATE OR REPLACE FUNCTION risk.velocity_window_is_never_removed()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'a velocity window is never deleted or truncated, by any role - it is the count every later decision reads (P13-TSK-006, INV-RSK-03)'
        USING ERRCODE = 'check_violation';
END;
$$;

CREATE TRIGGER velocity_window_is_never_deleted
    BEFORE DELETE ON risk.velocity_window
    FOR EACH ROW
    EXECUTE FUNCTION risk.velocity_window_is_never_removed();

CREATE TRIGGER velocity_window_is_never_truncated
    BEFORE TRUNCATE ON risk.velocity_window
    FOR EACH STATEMENT
    EXECUTE FUNCTION risk.velocity_window_is_never_removed();

-- ---------------------------------------------------------------------------------------------
-- 4. The grants: the upsert's columns and nothing else; no DELETE; nothing seeded.
-- ---------------------------------------------------------------------------------------------

GRANT SELECT, INSERT ON risk.velocity_window TO finapp_app;
GRANT UPDATE (count, sum_minor) ON risk.velocity_window TO finapp_app;
GRANT EXECUTE ON FUNCTION risk.velocity_window_length(text) TO finapp_app;
