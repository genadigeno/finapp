-- A BNPL fee pin's instant is its own insert's stamp on the database clock (P14-DOC-001, the Phase 14 exit review's
-- database pass; INV-MER-03, ADR-0063 decision 2 - the order of facts over the clock's reading; lending V028's precedent,
-- P11-TSK-036).
--
-- WHAT WAS WRONG. V012 (the Phase 12 -> 13 gate's A3-M1) moved the pin's instant to the database clock: BnplPricing reads
-- clock_timestamp() after the standing's share locks, resolves the version in force at that instant and hands it to the
-- insert as pinned_at; V012's trigger refused a pin whose instant lay outside its transaction's span - before
-- transaction_timestamp() or after clock_timestamp() at the insert. The database clock is not monotonic: an NTP or
-- hypervisor correction steps it back (measured on this machine's container about 2.8 s every 27 s; lending's VM 1.7 s;
-- a production host's can step too). A step between the domain's read and the insert put the read AFTER the insert's
-- clock_timestamp(), and V012 refused a correctly judged pin with an opaque P0001: the purchase's whole acceptance rolled
-- back - no money moved - and a legitimate acceptance answered 500 api.InternalError (BnplStormDatabaseTest, red twice:
-- "MerchantStorageException: pinning a BNPL purchase's fee (SQLState P0001)"). A step before the read had the same shape
-- on the other bound: the read before transaction_timestamp().
--
-- WHAT THIS DOES. The pin's instant is the insert's OWN stamp - GREATEST(clock_timestamp(), transaction_timestamp()):
-- this statement's clock, held at the transaction's start when the clock stands behind it - written by this trigger over
-- whatever the writer supplied, and "in force by the pin's instant" is judged against that stamp. No writer supplies the
-- instant any more, so none can date a pin outside its transaction (V012's window holds by construction, not by refusal),
-- and none can pin a version "in force" by an instant the database never reached (A3-M1 still holds: a scheduled version
-- not yet in force at the stamp is refused by the in-force rank). The domain reads the stamp back from RETURNING, and its
-- resolution reads GREATEST(clock_timestamp(), transaction_timestamp()) too, so a step before the read cannot resolve a
-- version superseded at the transaction's start. Every other rank of V012's function is kept exactly: a returning version,
-- in the gross's currency, a version created to take effect immediately (effective_from = created_at) in force from its
-- commit, of the merchant's BNPL schedule for the product, for a BNPL-enabled merchant.
--
-- WHAT IT DOES NOT. (1) The resolution and the stamp are still two readings of the clock. On a clock that did not step the
-- stamp is no earlier than the resolution's instant, so the version resolved is in force by it. On a clock stepped back
-- between them the stamp can fall before the read: a SCHEDULED version whose effective_from lies between the two (a window
-- of at most the step, around a scheduled change of price) is then refused by the in-force rank - rolled back, nothing
-- written, the acceptance re-judged on its retry - never mispriced (lending V028's residual, recorded the same way).
-- (2) A version scheduled to take effect in the milliseconds between the resolution and the insert does not price this
-- purchase - exactly as under V012 - but the pin's recorded instant is now the insert's, after that effective_from: the
-- version it names was in force at its resolution and is in force by its stamp, and a later one was too. No rank or proof
-- reads "the version in force at pinned_at" back; the pin names its version, and INV-MER-03 recomputes from that.
-- (3) Nothing here touches the card side's payment_fee_pin (PI-002), writes money, adds a column or grants anything. The
-- function stays migrator-owned, invoker's rights, schema-qualified, with no search_path of its own, as V010's and V012's.

-- ---------------------------------------------------------------------------------------------
-- The pin: V012's function, its instant's rank replaced by the insert's own stamp.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION merchant.bnpl_fee_pin_names_a_returning_version_in_force() RETURNS trigger
LANGUAGE plpgsql AS
$$
DECLARE
    pinned merchant.fee_schedule_version%ROWTYPE;
BEGIN
    -- P14-DOC-001 (V013): the pin's instant is stamped HERE, by this insert's clock - never a value a writer supplies or
    -- an earlier statement read, which a database clock stepped back between the two put after this clock (V012 refused
    -- it) - and never before the transaction began (ADR-0063 decision 2). "In force by the pin's instant" below is judged
    -- on this stamp, on the one clock every instance shares (the Phase 12 -> 13 gate's A3-M1). The domain takes it back
    -- from RETURNING.
    NEW.pinned_at := GREATEST(clock_timestamp(), transaction_timestamp());
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
    'When the pin was recorded: the insert''s OWN stamp on the database clock, GREATEST(clock_timestamp(), transaction_timestamp()), written by the pin''s trigger over any writer''s value - never a reading an earlier statement made (P14-DOC-001, merchant V013; it was the domain''s clock_timestamp() read after the standing''s share locks, V012, and the application clock before that). The version the pin names is in force by it.';
