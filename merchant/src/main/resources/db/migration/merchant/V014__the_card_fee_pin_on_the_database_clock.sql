-- The card-side fee pin's instant is its own insert's stamp on the database clock, and the version it names was in force
-- by that stamp (P15-TSK-038, PI-002, X-TSK-008's fee version; INV-MER-03, ADR-0014).
--
-- WHAT WAS WRONG. CheckoutSessions.open resolved the version in force on the instance's clock and V005 took pinned_at from
-- that instance too, so two instances whose clocks disagreed by a delta could, within the delta of a version's
-- effective_from, price one session by the outgoing version and another by the incoming one at the same database time,
-- and no rank saw it. The resolution now reads the database's clock after the merchant's share lock, in its statement
-- (JdbcFeeScheduleStore.findEffectiveVersionFor). This rank binds every writer of the pin to the same clock.
--
-- WHY THE STAMP AND NOT V012'S WINDOW. V012 bound the BNPL pin to [transaction_timestamp(), clock_timestamp()] and
-- refused anything else; V013 replaced that refusal after it refused correctly judged pins when the database clock
-- stepped back between the domain's read and the insert (a legitimate acceptance rolled back and answered 500). This
-- function is V013's shape from the start: the insert's own stamp, GREATEST(clock_timestamp(), transaction_timestamp()),
-- written over whatever the writer supplied, and the in-force rank judged against it. So no writer can date a pin outside
-- its transaction, and a pin cannot name a scheduled version not yet in force.
--
-- THE RANK AND ITS ORDER. BEFORE triggers of one event fire in name order, so a stamp and a judgement in two triggers
-- would depend on their names. There is one trigger on this table's INSERT and it does both, stamp first, so no ordering
-- exists to get wrong; V005's payment_fee_pin_is_immutable is BEFORE UPDATE OR DELETE and is not on this event.
--
-- A VERSION CREATED TO TAKE EFFECT IMMEDIATELY (effective_from = created_at, V004's "empty for immediately") is in force
-- from its commit, as FeeScheduleStore reads it: it is stamped with its creating instance's clock, which may run ahead of
-- the database's, and "immediately" must not wait for the database to catch up. A SCHEDULED version
-- (effective_from > created_at) is judged on the pin's instant alone. So is a version whose start the insert floored at an
-- earlier immediate version's (P15-TSK-037, its effective_from above its created_at): "in force from its commit" does not
-- hold for that row until the clock reaches the floor, bounded by the skew or step that made the floor necessary. A
-- session may name a version superseded since it was opened: that is what a pin is for, and the rank asks only that the
-- version had begun. V013's residual applies: a database clock stepped back between the session's opening and this insert
-- can refuse a correctly resolved scheduled version with P0001 - rolled back, nothing written, the confirmation retried.
--
-- WHAT IT DOES NOT. It rewrites no row and touches no pin already written, and the existing rows keep the instants their
-- instances gave them (no statement compares those). The pin's writer still binds a value: the column stays NOT NULL and
-- this BEFORE trigger replaces it before the constraint is checked. A pin naming a version that does not exist reads NULL
-- here, passes both comparisons and is refused by V005's foreign key. Across the deploy: a session opened before this
-- migration by a fast instance can name a scheduled version the database's clock has not reached; its confirmation is
-- refused here until it has (rolled back, nothing written, the customer's retry succeeds) - a skew-sized window around one
-- scheduled price change, fail-closed. The function is migrator-owned, invoker's rights, its clock calls named in
-- pg_catalog so no role's search_path can move them (OwnerReachOverHistoryIsClosedTest).

CREATE FUNCTION merchant.payment_fee_pin_names_a_version_in_force() RETURNS trigger
LANGUAGE plpgsql AS
$$
DECLARE
    pinned merchant.fee_schedule_version%ROWTYPE;
BEGIN
    -- The pin's instant is stamped HERE, by this insert's clock, never a value a writer supplies, and never before the
    -- transaction began (V013's reasoning, merchant's own). "In force by the pin's instant" below is judged on this stamp.
    NEW.pinned_at := GREATEST(pg_catalog.clock_timestamp(), pg_catalog.transaction_timestamp());
    SELECT * INTO pinned FROM merchant.fee_schedule_version WHERE id = NEW.fee_schedule_version_id;
    IF pinned.effective_from > NEW.pinned_at AND pinned.effective_from <> pinned.created_at THEN
        RAISE EXCEPTION 'a payment fee pin names a version in force when it was pinned (PI-002, INV-MER-03)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER payment_fee_pin_names_a_version_in_force
    BEFORE INSERT ON merchant.payment_fee_pin
    FOR EACH ROW
    EXECUTE FUNCTION merchant.payment_fee_pin_names_a_version_in_force();

COMMENT ON COLUMN merchant.payment_fee_pin.pinned_at IS
    'When the pin was recorded: the insert''s OWN stamp on the database clock, GREATEST(clock_timestamp(), transaction_timestamp()), written by the pin''s trigger over any writer''s value (PI-002, merchant V014; it was the application clock). The version the pin names was in force by it.';
