-- Fee schedules, their immutable versions, and merchant assignment (P6-TSK-004, ADR-0050).
--
-- EVERY GENERATED LIST BELOW HAS ONE DEFINITION: the name bound from FeeSchedule.MAX_NAME_LENGTH,
-- the rate's numeric type and bound from FeeRate.MAX_SCALE, the rounding list from
-- RoundingPolicy.values(), the refund-fee list from RefundFeePolicy.sqlValueList(), the fixed
-- part's three columns from MoneyColumns.columnsFor("fixed").ddl().
-- FeeScheduleMigrationTest fails the build if this file and the code disagree.
--
-- THIS SCHEMA STILL HOLDS NO BALANCE (INV-MER-02). A fee schedule is what the platform
-- CHARGES, not what it OWES; a price is a term of an agreement, owed to nobody and
-- unchanged by any payment. The live-schema sweep flagged fixed_amount_minor the moment
-- this table arrived - correctly, since no name pattern can tell a price from a position
-- - and the three fixed_* columns are now NAMED exemptions in that sweep with this
-- reason. %balance%, %payable% and %owed% keep no exemptions at all.
--
-- WHAT IS IMMUTABLE HERE, AND AT HOW MANY RANKS. A fee schedule version can never change, and
-- three independent things say so:
--   1. the application role holds NO UPDATE and NO DELETE grant on either fee table;
--   2. a trigger refuses every UPDATE and every DELETE unconditionally - binding the migrator
--      too, which is the only writer the grants cannot bind;
--   3. FeeScheduleStore has no method that could express a change.
-- The trigger is UNCONDITIONAL rather than column-aware on purpose: every other machine in this
-- schema needed a trigger that reasons about which column may move in which state, and each of
-- those is a place the reasoning can be subtly wrong. "Never" has no such place.
--
-- HISTORY CANNOT BE REPRICED, AND THIS FILE IS WHAT SAYS SO (INV-MER-03): effective_from may
-- never precede created_at. Both are columns, so the rule is a CHECK rather than a convention
-- every future writer has to remember. A version scheduled for the future may be superseded by
-- a later version at THE SAME INSTANT - ties are legal and broken by the greater version number
-- - so an operator who catches a mistyped rate before it takes effect does not have to leave a
-- second of wrong pricing behind.
--
-- NO CROSS-SCHEMA FOREIGN KEYS (ADR-0029): merchant_id is same-schema, so those FKs are legal
-- and right. The actor columns follow audit_record's model, as V002 and V003 do.

-- -----------------------------------------------------------------------------------------
-- The stable commercial identity a merchant is assigned to.
-- -----------------------------------------------------------------------------------------
CREATE TABLE merchant.fee_schedule (
    -- UUIDv7, minted by the application (ADR-0013).
    id          uuid        PRIMARY KEY,

    name        text        NOT NULL
        CONSTRAINT fee_schedule_name_bounded
            CHECK (length(name) BETWEEN 1 AND 200),

    -- Explicit, always (INV-MON-02). A schedule prices in one currency because its versions'
    -- fixed parts are money; pricing a EUR capture from a USD schedule would need a conversion,
    -- and a conversion inside a fee computation is a fabricated exchange rate (INV-MON-04).
    -- Multi-currency settlement is Phase 9's.
    currency    char(3)     NOT NULL
        CONSTRAINT fee_schedule_currency_shape
            CHECK (currency ~ '^[A-Z]{3}$'),

    -- Application-supplied from one injected Clock, never DEFAULT now().
    created_at  timestamptz NOT NULL,
    created_by  text        NOT NULL
        CONSTRAINT fee_schedule_created_by_bounded
            CHECK (length(created_by) BETWEEN 1 AND 200),

    -- Lets fee_schedule_version carry a COMPOSITE foreign key on (schedule, currency), which
    -- is what makes "a version prices in its schedule's currency" a fact the database holds
    -- rather than a rule the domain remembers. Redundant as a uniqueness claim - id is already
    -- the primary key - and load-bearing as a reference target.
    CONSTRAINT fee_schedule_id_and_currency UNIQUE (id, currency)
);

-- Two schedules with one name is an operator trap: the assignment surface names them, and
-- picking the wrong "Standard" is a mispricing nobody notices until a statement is disputed.
CREATE UNIQUE INDEX fee_schedule_one_row_per_name ON merchant.fee_schedule (lower(name));

-- -----------------------------------------------------------------------------------------
-- One priced version. IMMUTABLE FROM BIRTH - this is the row an assessment pins (INV-HIST-04).
-- -----------------------------------------------------------------------------------------
CREATE TABLE merchant.fee_schedule_version (
    id              uuid        PRIMARY KEY,

    fee_schedule_id uuid        NOT NULL REFERENCES merchant.fee_schedule (id),

    -- Per schedule, never global. Minted optimistically and arbitrated by the unique
    -- constraint below - see the grant note at the foot of this file for why there is
    -- no lock to mint it under.
    version         integer     NOT NULL
        CONSTRAINT fee_schedule_version_number_is_positive
            CHECK (version >= 1),

    -- A RATIO, not money: no currency, and never floating point (INV-MON-01). numeric(7,6)
    -- is generated from FeeRate.MAX_SCALE - one digit of integer part so the CHECK below is
    -- the thing that bounds the value, rather than the type silently overflowing it.
    rate            numeric(7, 6) NOT NULL
        CONSTRAINT fee_schedule_version_rate_is_a_proportion
            CHECK (rate >= 0 AND rate < 1),

    -- The flat part, in the schedule's currency. MoneyColumns' three-column shape (ADR-0003).
    fixed_amount_minor BIGINT NOT NULL, fixed_currency CHAR(3) NOT NULL, fixed_scale SMALLINT NOT NULL, CHECK (fixed_currency ~ '^[A-Z]{3}$'), CHECK (fixed_scale BETWEEN 0 AND 9),
    CONSTRAINT fee_schedule_version_fixed_is_not_negative
        CHECK (fixed_amount_minor >= 0),

    -- INV-MON-03: the rounding mode is a REQUIRED, STORED, NAMED attribute. There is no
    -- default anywhere on the fee path, and this column is what makes that true for every
    -- assessment ever recomputed against this row.
    rounding_policy text        NOT NULL
        CONSTRAINT fee_schedule_version_rounding_policy_is_known
            CHECK (rounding_policy IN ('HALF_EVEN', 'HALF_UP', 'TOWARDS_ZERO', 'AWAY_FROM_ZERO', 'FLOOR', 'CEILING')),

    -- ADR-0050's consequence: what happens to the fee on a refund is DATA, versioned with the
    -- price it was agreed beside, not code a later release can change under a past capture.
    refund_fee_policy text      NOT NULL
        CONSTRAINT fee_schedule_version_refund_fee_policy_is_known
            CHECK (refund_fee_policy IN ('RETAINED', 'RETURNED')),

    effective_from  timestamptz NOT NULL,
    created_at      timestamptz NOT NULL,
    created_by      text        NOT NULL
        CONSTRAINT fee_schedule_version_created_by_bounded
            CHECK (length(created_by) BETWEEN 1 AND 200),

    -- THE KEYSTONE (INV-MER-03). A version cannot be created already effective in the past, so
    -- no version can ever reprice a capture that already happened. Everything else about
    -- immutability protects a version once written; this protects the history from the WRITING.
    CONSTRAINT fee_schedule_version_takes_effect_forward
        CHECK (effective_from >= created_at),

    -- THE VERSION-MINTING ARBITER, and also its queue: a racer inserting a number that an
    -- uncommitted transaction holds WAITS on this index and is refused when that
    -- transaction commits, then re-reads and takes the next. Ten instances therefore
    -- produce ten distinct numbers with no gap, without any row being locked.
    CONSTRAINT fee_schedule_version_number_is_unique_per_schedule
        UNIQUE (fee_schedule_id, version),

    -- A version prices in its schedule's currency, held by the DATABASE (see the composite
    -- unique above). The domain refuses it first; this refuses raw SQL that skipped the domain.
    CONSTRAINT fee_schedule_version_prices_its_schedules_currency
        FOREIGN KEY (fee_schedule_id, fixed_currency)
            REFERENCES merchant.fee_schedule (id, currency)
);

-- The resolution query's index: newest-effective first within a schedule, which is exactly
-- FeeScheduleVersion.EFFECTIVE_ORDER.
CREATE INDEX fee_schedule_version_effective_order
    ON merchant.fee_schedule_version (fee_schedule_id, effective_from DESC, version DESC);

-- -----------------------------------------------------------------------------------------
-- IMMUTABILITY, BOUND TO EVERY WRITER INCLUDING THE MIGRATOR.
-- -----------------------------------------------------------------------------------------
CREATE FUNCTION merchant.fee_definitions_are_immutable() RETURNS trigger
LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'a fee schedule definition is immutable: change creates a NEW version effective forward, repricing nothing (INV-MER-03). Table %, operation %', TG_TABLE_NAME, TG_OP;
END;
$$;

CREATE TRIGGER fee_schedule_is_immutable
    BEFORE UPDATE OR DELETE ON merchant.fee_schedule
    FOR EACH ROW
    EXECUTE FUNCTION merchant.fee_definitions_are_immutable();

CREATE TRIGGER fee_schedule_version_is_immutable
    BEFORE UPDATE OR DELETE ON merchant.fee_schedule_version
    FOR EACH ROW
    EXECUTE FUNCTION merchant.fee_definitions_are_immutable();

-- -----------------------------------------------------------------------------------------
-- Which schedule prices a merchant. THE ONE MUTABLE THING IN THIS FILE, and it is a pointer.
-- -----------------------------------------------------------------------------------------
CREATE TABLE merchant.merchant_fee_schedule (
    -- One live schedule per merchant, total, held by the primary key itself.
    merchant_id     uuid        PRIMARY KEY REFERENCES merchant.merchant (id),

    fee_schedule_id uuid        NOT NULL REFERENCES merchant.fee_schedule (id),

    assigned_at     timestamptz NOT NULL,
    assigned_by     text        NOT NULL
        CONSTRAINT merchant_fee_schedule_assigned_by_bounded
            CHECK (length(assigned_by) BETWEEN 1 AND 200)
);

CREATE INDEX merchant_fee_schedule_by_schedule
    ON merchant.merchant_fee_schedule (fee_schedule_id);

CREATE TABLE merchant.merchant_fee_schedule_event (
    id                   bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    merchant_id          uuid        NOT NULL REFERENCES merchant.merchant (id),

    -- NULL for a first assignment: there was no schedule before, and recording a fabricated
    -- one would be inventing a commercial fact.
    from_fee_schedule_id uuid        REFERENCES merchant.fee_schedule (id),
    to_fee_schedule_id   uuid        NOT NULL REFERENCES merchant.fee_schedule (id),

    -- A recorded move must actually move. An assignment that changes nothing converges
    -- SILENTLY in the command (no row here, no audit record), so a from = to row would be a
    -- history entry for something that did not happen.
    CONSTRAINT merchant_fee_schedule_event_moves
        CHECK (from_fee_schedule_id IS NULL OR from_fee_schedule_id <> to_fee_schedule_id),

    -- Changing what a counterparty is charged is a commercial judgement (INV-AUD-03).
    reason               text        NOT NULL
        CONSTRAINT merchant_fee_schedule_event_reason_bounded
            CHECK (length(reason) BETWEEN 1 AND 4000),

    actor_id             text        NOT NULL
        CONSTRAINT merchant_fee_schedule_event_actor_id_bounded
            CHECK (length(actor_id) BETWEEN 1 AND 200),
    actor_type           text        NOT NULL
        CONSTRAINT merchant_fee_schedule_event_actor_type_bounded
            CHECK (length(actor_type) BETWEEN 1 AND 50),

    occurred_at          timestamptz NOT NULL
);

CREATE INDEX merchant_fee_schedule_event_by_merchant
    ON merchant.merchant_fee_schedule_event (merchant_id);

COMMENT ON TABLE merchant.fee_schedule IS
    'A named commercial pricing identity (ADR-0050 section 5). What a merchant is ASSIGNED to; fee_schedule_version is what an assessment PINS. No status and no deletion: a schedule nobody is assigned to is out of use, and deleting one would orphan every assessment that named its versions.';
COMMENT ON TABLE merchant.fee_schedule_version IS
    'One immutable priced version (INV-MER-03, INV-HIST-04''s first subject). Never updated, never deleted - no grant, an unconditional trigger, and no port method. Which version is effective at an instant is resolved by query, never by a sweeper: a job that "activated" versions would be a second authority on what is effective.';
COMMENT ON COLUMN merchant.fee_schedule_version.rate IS
    'A dimensionless proportion in [0, 1), exact (INV-MON-01). Multiplied by the gross and rounded EXACTLY ONCE under rounding_policy; the merchant''s net is then gross - fee BY SUBTRACTION, so the split conserves by construction (INV-MER-04, ADR-0050 section 4).';
COMMENT ON COLUMN merchant.fee_schedule_version.effective_from IS
    'Never before created_at (CHECK). Ties on this instant are legal and broken by the greater version number, so a mistyped future rate can be superseded at the same instant rather than pricing for a second.';
COMMENT ON TABLE merchant.merchant_fee_schedule IS
    'Which schedule prices a merchant - a pointer, moved under the merchant row''s lock. One per merchant, total, by the primary key.';
COMMENT ON TABLE merchant.merchant_fee_schedule_event IS
    'Append-only assignment history with the operator''s required reason: what a merchant was promised commercially, at each moment. Server-assigned order; SELECT and INSERT only.';

-- THE GRANTS ARRIVE WITH THE TABLES (P6-TSK-001's floor).
--
-- NO UPDATE AND NO DELETE ON EITHER FEE TABLE. This is the first rank of the immutability
-- claim and the cheapest: the trigger exists for the writer the grants cannot bind.
--
-- AND IT HAS A CONSEQUENCE THE APPLICATION HAD TO BE DESIGNED AROUND, recorded here because a
-- later reader will otherwise "fix" it: PostgreSQL requires the UPDATE privilege to take a ROW
-- LOCK, so SELECT ... FOR UPDATE on these tables is refused (SQLState 42501). A fee schedule
-- row therefore cannot be the serialization point for minting version numbers, and the arbiter
-- is the unique index on (fee_schedule_id, version) instead, with the writer re-reading and
-- retrying (FeeSchedules.mintVersion). Granting UPDATE here to make a lock takeable would buy
-- a lock on a table nothing may write twice, at the cost of this grant no longer saying
-- "immutable" - which is the only thing it is here to say.
GRANT SELECT, INSERT ON merchant.fee_schedule TO finapp_app;
GRANT SELECT, INSERT ON merchant.fee_schedule_version TO finapp_app;

-- The pointer moves; nothing else about it does. No DELETE: unassigning is not modelled, and
-- a merchant that should stop trading is suspended, which is a decision with its own audit.
GRANT SELECT, INSERT ON merchant.merchant_fee_schedule TO finapp_app;
GRANT UPDATE (fee_schedule_id, assigned_at, assigned_by) ON merchant.merchant_fee_schedule TO finapp_app;

-- The history is append-only at the privilege (the audit_record model).
GRANT SELECT, INSERT ON merchant.merchant_fee_schedule_event TO finapp_app;
