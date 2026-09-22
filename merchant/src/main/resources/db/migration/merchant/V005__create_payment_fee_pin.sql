-- Which merchant a payment is for, and which fee schedule version prices it (P6-TSK-005,
-- ADR-0050 sections 5 and 6).
--
-- WHY THIS TABLE EXISTS AT ALL. A capture can arrive seconds or days after the payment was
-- created: a provider answers on its own schedule (ADR-0046) and the sweeper resolves what
-- never answered. Resolving the fee AT CAPTURE would let a schedule version created in between
-- reprice a payment the customer had already agreed to and the merchant had already been
-- quoted - the restatement risk question 8 carried, arriving one level down. So the version is
-- fixed when the price is agreed, and the capture applies it (INV-MER-03, INV-HIST-04).
--
-- ONE PIN PER PAYMENT, TOTAL: payment_intent_ref is the PRIMARY KEY. A second pin would be a
-- second price for one payment, and there is no reading of that which is not a defect. Refused
-- by the key rather than by a read-then-write, so ten instances cannot both win.
--
-- IMMUTABLE, BY THE SAME FUNCTION V004 DEFINED. A pin that can move is not a pin, and the
-- claim is held at the same three ranks as a fee schedule version: no UPDATE or DELETE grant,
-- an unconditional trigger binding the migrator too, and a port with no method that could
-- express a change. The function is merchant.fee_definitions_are_immutable() reused rather
-- than copied - two functions saying "never" would eventually say it differently.
--
-- NO CROSS-SCHEMA FOREIGN KEY on payment_intent_ref (ADR-0029): it references
-- payments.payment_intent BY VALUE, exactly as merchant.party_ref references party.party.
-- merchant_id and fee_schedule_version_id are same-schema, so those FKs are legal and right -
-- and the version FK is load-bearing: a pin naming a version that does not exist is a payment
-- nobody can reprice, which is the one thing this table exists to prevent.
--
-- THE GROSS IS RECORDED SO THE CAPTURE CAN REFUSE A MISMATCH. Today a capture is the
-- authorized promise in full (ADR-0045 section 4 - no partial capture until its producer
-- exists), so the captured amount always equals this one. That is exactly why it is stored:
-- the assumption is invisible otherwise, and the day it stops holding, a capture priced
-- against a gross nobody agreed is a silent mispricing rather than a loud refusal.
--
-- STILL NO BALANCE IN THIS SCHEMA (INV-MER-02): a pinned gross is what was AGREED, not what is
-- OWED. What the platform owes this merchant is the MERCHANT_PAYABLE ledger position and
-- exists nowhere else - and this row is the input to the entry that moves it, never a copy of
-- its result.

CREATE TABLE merchant.payment_fee_pin (
    -- payments.payment_intent.id by value; no cross-schema FK by design (ADR-0029).
    payment_intent_ref      uuid        PRIMARY KEY,

    merchant_id             uuid        NOT NULL REFERENCES merchant.merchant (id),

    fee_schedule_version_id uuid        NOT NULL
        REFERENCES merchant.fee_schedule_version (id),

    -- What was agreed. MoneyColumns' three-column shape (ADR-0003).
    gross_amount_minor BIGINT NOT NULL, gross_currency CHAR(3) NOT NULL, gross_scale SMALLINT NOT NULL, CHECK (gross_currency ~ '^[A-Z]{3}$'), CHECK (gross_scale BETWEEN 0 AND 9),

    -- A zero-priced payment asserts nothing and a negative one is a refund wearing a payment's
    -- clothes (the payment_intent amount CHECK's reasoning, at the pin).
    CONSTRAINT payment_fee_pin_gross_is_positive
        CHECK (gross_amount_minor > 0),

    -- Application-supplied from one injected Clock, never DEFAULT now().
    pinned_at               timestamptz NOT NULL,
    pinned_by               text        NOT NULL
        CONSTRAINT payment_fee_pin_pinned_by_bounded
            CHECK (length(pinned_by) BETWEEN 1 AND 200)
);

-- Serves "this merchant's payments" - the statement read P6-TSK-009 will want, named now
-- rather than discovered by that task (the index-the-read precedent).
CREATE INDEX payment_fee_pin_by_merchant ON merchant.payment_fee_pin (merchant_id);

-- IMMUTABILITY, BOUND TO EVERY WRITER INCLUDING THE MIGRATOR - V004's function, reused.
CREATE TRIGGER payment_fee_pin_is_immutable
    BEFORE UPDATE OR DELETE ON merchant.payment_fee_pin
    FOR EACH ROW
    EXECUTE FUNCTION merchant.fee_definitions_are_immutable();

COMMENT ON TABLE merchant.payment_fee_pin IS
    'Which merchant a payment is for and which fee schedule version prices it, fixed when the price is agreed and applied when the capture posts (ADR-0050 sections 5 and 6). Immutable: a pin that can move is not a pin. One per payment, by the primary key.';
COMMENT ON COLUMN merchant.payment_fee_pin.fee_schedule_version_id IS
    'INV-HIST-04''s pin on a money decision: recomputing the capture''s fee under this version reproduces it to the minor unit, for as long as the record exists (INV-MER-03).';
COMMENT ON COLUMN merchant.payment_fee_pin.gross_amount_minor IS
    'What was agreed - NOT what is owed (INV-MER-02). The capture refuses to price an amount that is not this one.';

-- THE GRANTS ARRIVE WITH THE TABLE. SELECT and INSERT only: the same first rank of the
-- immutability claim the fee tables hold, for the same reason.
GRANT SELECT, INSERT ON merchant.payment_fee_pin TO finapp_app;
