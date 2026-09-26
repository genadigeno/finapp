-- The bank account joins the instrument registry (P7-TSK-007, ADR-0062 section 2) - the
-- INV-PAY-02 doctrine applied to INV-RAIL-03's subject: what the platform holds INSTEAD of
-- bank details is the rail provider's opaque destination reference, a four-character display
-- suffix and the confirmation-of-payee word, and NOTHING ELSE.
--
-- A BANK ACCOUNT IDENTIFIER CANNOT PHYSICALLY BE STORED IN THE DESTINATION COLUMN, and that
-- is this file's defining property (V002's PAN stance, restated for the second kind): the
-- charset is the provider-reference charset, a value containing NO LETTER is refused (an
-- account number, a sort-coded account string and a phone number are digits and separators
-- however punctuated), and the international-identifier shape - two letters, two digits,
-- then alphanumerics, at most 34 characters in all, an IBAN's own bound - is refused. The
-- recorded limit rides DestinationReference's javadoc: a reference colliding with that shape
-- must be prefixed at the adapter. token_reference deliberately does NOT gain the
-- international-identifier refusal: a 32-hex card token starting letter-letter-digit-digit
-- collides with it, and hex-shaped tokens exist in the wild and in this repository's own
-- fixtures - INV-RAIL-03's subject is the bank columns.
--
-- THE KIND IS A FROZEN BIRTH FACT (the payments V012 discipline): backfilled CARD_TOKEN -
-- every existing row is a card - then NOT NULL, generated from
-- PaymentMethodKind.sqlValueList(); the payee CHECK from PayeeCheck.sqlValueList();
-- PaymentMethodMigrationTest fails the build if this file and the enums disagree. The
-- migrate-then-deploy note (V009/V011's precedent): the deploy carrying kind-aware INSERTs
-- follows this migration as one unit; no old-application INSERT runs against the NOT NULL.
--
-- THE TRIGGER IS RECREATED NULL-SAFE, and this is a correctness repair, not a courtesy:
-- V002's function compared OLD.col <> NEW.col, which is NULL-blind - on a bank row, editing
-- brand from NULL to a value would have sailed through. Every immutability comparison is now
-- IS DISTINCT FROM, and the kind plus the three bank columns join the frozen set.
--
-- THE NO_MATCH CONSENT IS ONE FACT WITH THE CHECK'S WORD (ADR-0062 section 2): an
-- unacknowledged NO_MATCH instrument cannot exist for any writer, and no other result may
-- carry an acknowledgement instant.
--
-- Grants are unchanged deliberately: SELECT and INSERT for the row's life, UPDATE narrowed to
-- exactly the detachment transition's columns - the new columns are facts, not fields.

ALTER TABLE paymentmethods.payment_method
    ADD COLUMN kind                     text,
    ADD COLUMN destination_reference    text,
    ADD COLUMN payee_check              text,
    ADD COLUMN no_match_acknowledged_at timestamptz;

-- Every pre-P7-TSK-007 row is a card: the only factory that existed minted them.
UPDATE paymentmethods.payment_method SET kind = 'CARD_TOKEN';

ALTER TABLE paymentmethods.payment_method
    ALTER COLUMN kind SET NOT NULL,

    -- The card facts become the CARD_TOKEN kind's own; the coherence CHECKs below carry
    -- presence per kind for every writer. V002's shape CHECKs on these columns remain and
    -- pass NULL by SQL's own CHECK semantics.
    ALTER COLUMN token_reference DROP NOT NULL,
    ALTER COLUMN brand           DROP NOT NULL,
    ALTER COLUMN expiry_month    DROP NOT NULL,
    ALTER COLUMN expiry_year     DROP NOT NULL,

    -- Generated from PaymentMethodKind.sqlValueList().
    ADD CONSTRAINT payment_method_kind_is_known
        CHECK (kind IN ('CARD_TOKEN', 'BANK_ACCOUNT')),

    -- A card is exactly its card facts, a bank account exactly its bank facts - both
    -- directions, for every writer (the aggregate's constructor at DB-CONSTRAINT rank).
    ADD CONSTRAINT payment_method_card_facts_match_kind
        CHECK (((kind = 'CARD_TOKEN') = (token_reference IS NOT NULL))
           AND ((kind = 'CARD_TOKEN') = (brand IS NOT NULL))
           AND ((kind = 'CARD_TOKEN') = (expiry_month IS NOT NULL))
           AND ((kind = 'CARD_TOKEN') = (expiry_year IS NOT NULL))),
    ADD CONSTRAINT payment_method_bank_facts_match_kind
        CHECK (((kind = 'BANK_ACCOUNT') = (destination_reference IS NOT NULL))
           AND ((kind = 'BANK_ACCOUNT') = (payee_check IS NOT NULL))),

    -- Generated from PayeeCheck.sqlValueList(); NULL (a card row) passes by CHECK semantics.
    ADD CONSTRAINT payment_method_payee_check_is_known
        CHECK (payee_check IN ('MATCH', 'CLOSE_MATCH', 'NO_MATCH', 'UNAVAILABLE')),

    -- The consent rule, NULL-safe both ways: NO_MATCH exactly with an acknowledgement
    -- instant, and no other result (nor any card row) carrying one.
    ADD CONSTRAINT payment_method_no_match_is_acknowledged
        CHECK ((payee_check IS NOT DISTINCT FROM 'NO_MATCH')
             = (no_match_acknowledged_at IS NOT NULL)),

    -- INV-RAIL-03 at DB-CONSTRAINT rank: the provider-reference charset, then the two shape
    -- refusals the file header records.
    ADD CONSTRAINT payment_method_destination_charset
        CHECK (destination_reference ~ '^[A-Za-z0-9_.:-]{1,128}$'),
    ADD CONSTRAINT payment_method_destination_has_a_letter
        CHECK (destination_reference !~ '^[0-9_.:-]+$'),
    ADD CONSTRAINT payment_method_destination_is_not_an_account_identifier
        CHECK (NOT (char_length(destination_reference) <= 34
                AND destination_reference ~ '^[A-Za-z]{2}[0-9]{2}[A-Za-z0-9]{1,30}$'));

-- The display suffix stays NOT NULL for both kinds, but its charset is the kind's: exactly
-- four digits for a card (last4, PCI's own displayable), exactly four alphanumerics for a
-- bank account (the exchange's own bound, ADR-0062 section 2). Replaces V002's digits-only
-- rule, which would refuse a lawful bank suffix.
ALTER TABLE paymentmethods.payment_method
    DROP CONSTRAINT payment_method_suffix_is_last4,
    ADD CONSTRAINT payment_method_suffix_matches_kind
        CHECK ((kind = 'CARD_TOKEN' AND display_suffix ~ '^[0-9]{4}$')
            OR (kind = 'BANK_ACCOUNT' AND display_suffix ~ '^[A-Za-z0-9]{4}$'));

-- ONE LIVE ROW PER (PARTY, DESTINATION) - the bank slot's concurrency arbiter, the exact
-- V002 (party, token) reasoning: "register this account" is one live row however many
-- instances say it, the store's attachOrConverge hands the loser the winner's row, and
-- detaching frees the slot for a NEW aggregate (INV-LIFE-04). PARTIAL over the non-terminal
-- states, the predicate generated from sqlTerminalValueList(). NULLs never collide, so card
-- rows are invisible to this index exactly as bank rows are to V002's.
CREATE UNIQUE INDEX payment_method_one_live_per_party_destination
    ON paymentmethods.payment_method (party_id, destination_reference)
    WHERE status NOT IN ('DETACHED');

-- DETACHED remains terminal for every writer, and identity, kind, references and display
-- metadata remain immutable in any state - NOW NULL-SAFELY (the header's recorded repair).
CREATE OR REPLACE FUNCTION paymentmethods.payment_method_permits_only_detachment()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.id IS DISTINCT FROM NEW.id
        OR OLD.party_id IS DISTINCT FROM NEW.party_id
        OR OLD.kind IS DISTINCT FROM NEW.kind
        OR OLD.token_reference IS DISTINCT FROM NEW.token_reference
        OR OLD.brand IS DISTINCT FROM NEW.brand
        OR OLD.display_suffix IS DISTINCT FROM NEW.display_suffix
        OR OLD.expiry_month IS DISTINCT FROM NEW.expiry_month
        OR OLD.expiry_year IS DISTINCT FROM NEW.expiry_year
        OR OLD.destination_reference IS DISTINCT FROM NEW.destination_reference
        OR OLD.payee_check IS DISTINCT FROM NEW.payee_check
        OR OLD.no_match_acknowledged_at IS DISTINCT FROM NEW.no_match_acknowledged_at
        OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'a payment method''s identity, kind, references and display metadata are immutable: updating is detach-and-reattach, never an edit (P5-TSK-004; P7-TSK-007)';
    END IF;
    IF NOT (OLD.status = 'ACTIVE' AND NEW.status IN ('DETACHED')) THEN
        RAISE EXCEPTION 'the only edge a payment method has is ACTIVE -> DETACHED (INV-LIFE-04)';
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON COLUMN paymentmethods.payment_method.kind IS
    'The instrument kind, a frozen birth fact (P7-TSK-007): CARD_TOKEN carries token, brand and expiry; BANK_ACCOUNT carries destination_reference and payee_check. Every per-kind rule keys on it.';
COMMENT ON COLUMN paymentmethods.payment_method.destination_reference IS
    'The rail provider''s opaque reference to the customer''s external account (INV-RAIL-03; ADR-0062 section 2) - presented on every push that uses it, held instead of bank details. Plaintext by V002''s recorded decision, same reasoning: a reference alone moves nothing without the confined scheme credential, and it is revocable by detach.';
COMMENT ON COLUMN paymentmethods.payment_method.payee_check IS
    'The scheme directory''s confirmation-of-payee word, one of the exactly three values the grant exchange may keep (ADR-0062 section 2). NO_MATCH exists only acknowledged.';
COMMENT ON COLUMN paymentmethods.payment_method.no_match_acknowledged_at IS
    'When the customer explicitly acknowledged registering despite NO_MATCH - present exactly when payee_check = NO_MATCH (the consent CHECK). Risk scoring of the result is Phase 13''s.';
