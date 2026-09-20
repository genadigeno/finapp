-- The tokenised instrument (P5-TSK-004, PHASE_5_PLAN.md section 8) - the PCI boundary's
-- subject (INV-PAY-02): what the platform holds INSTEAD of card data, plus exactly what a
-- person needs to recognise it in a list.
--
-- A PAN CANNOT PHYSICALLY BE STORED IN ANY COLUMN OF THIS TABLE, and that is the file's
-- defining property (the INV-IDN-01 credential_derivation_is_encoded pattern, applied to the
-- instrument boundary): the token CHECK refuses digits-and-separators shapes, the brand's
-- charset holds no digits at all, the display suffix is exactly four - last4, displayable by
-- PCI's own definition and the most instrument this schema ever discloses - and the expiry is
-- two bounded integers. PaymentMethodDatabaseTest sweeps every text column, derived from
-- information_schema, with PAN-shaped plants.
--
-- EVERY GENERATED LIST BELOW HAS ONE DEFINITION. The status CHECK comes from
-- PaymentMethodStatus.sqlValueList() and the one-live index predicate from
-- PaymentMethodStatus.sqlTerminalValueList(); PaymentMethodMigrationTest fails the build if
-- this file and the enum disagree (the P0-TSK-022 pattern).
--
-- THE TOKEN COLUMN IS STORED PLAINTEXT, AND THE DECISION IS RECORDED RATHER THAN SILENT.
-- Hashing is unavailable - the token must be PRESENTED to the provider, not compared - and
-- what bounds a leaked column is that a token alone charges nothing: the provider requires
-- the confined API credential, held outside the database (P5-TSK-002/P5-TSK-003), and tokens
-- are revocable by detach. Encryption at rest under the generalised key mechanism is the
-- recorded seam if a later review demands it (one migration plus the DocumentCipher shape);
-- the plan mandates it for provider_evidence and deliberately not here.
--
-- NO CROSS-SCHEMA FOREIGN KEY: party_id references party.party by value (ADR-0029's rule).

CREATE TABLE paymentmethods.payment_method (
    -- UUIDv7, minted by the application (ADR-0013).
    id              uuid        PRIMARY KEY,

    -- The owning PARTY - a saved instrument is a person's, not a commercial relationship's
    -- (the beneficiary/consent-record ownership argument). The ownership predicate's column
    -- (ADR-0031; P5-TSK-005's surface).
    party_id        uuid        NOT NULL,

    -- The tokenisation provider's reference - what authorize will present (P5-TSK-009). The
    -- domain rule is TokenReference's; these CHECKs carry it for every writer: bounded, the
    -- token charset, and NEVER a digits-and-separators shape, which is a card number however
    -- it is formatted (INV-PAY-02 at DB-CONSTRAINT rank).
    token_reference text        NOT NULL
        CONSTRAINT payment_method_token_is_bounded
            CHECK (length(token_reference) <= 128),
    CONSTRAINT payment_method_token_charset
        CHECK (token_reference ~ '^[A-Za-z0-9_-]+$'),
    CONSTRAINT payment_method_token_is_not_a_pan
        CHECK (token_reference !~ '^[0-9-]+$'),

    -- Display metadata, each shape unable to carry a PAN: letters-and-spaces brand, exactly
    -- four suffix digits, two bounded integers.
    brand           text        NOT NULL
        CONSTRAINT payment_method_brand_shape
            CHECK (brand ~ '^[A-Za-z][A-Za-z ]{0,29}$'),
    display_suffix  text        NOT NULL
        CONSTRAINT payment_method_suffix_is_last4
            CHECK (display_suffix ~ '^[0-9]{4}$'),
    expiry_month    integer     NOT NULL
        CONSTRAINT payment_method_expiry_month_is_a_month
            CHECK (expiry_month BETWEEN 1 AND 12),
    expiry_year     integer     NOT NULL
        CONSTRAINT payment_method_expiry_year_is_sane
            CHECK (expiry_year BETWEEN 2000 AND 2100),

    -- Generated from PaymentMethodStatus.sqlValueList(). The machine lives on the enum and in
    -- the aggregate (INV-LIFE-02); this CHECK bounds what any writer can store, and the
    -- trigger below bounds how a stored value may move.
    status          text        NOT NULL
        CONSTRAINT payment_method_status_is_known
            CHECK (status IN ('ACTIVE', 'DETACHED')),

    -- Application-supplied from the injected Clock, never DEFAULT now() (P0-TSK-015's rule).
    created_at      timestamptz NOT NULL,
    detached_at     timestamptz,

    -- The status and the detachment instant are one fact, unsplittable for any writer.
    CONSTRAINT payment_method_detachment_instant_matches_status
        CHECK ((status = 'ACTIVE') = (detached_at IS NULL)),

    CONSTRAINT payment_method_detachment_follows_creation
        CHECK (detached_at IS NULL OR detached_at >= created_at)
);

-- ONE LIVE ROW PER (PARTY, TOKEN) - the concurrency arbiter (P5-TSK-004's distributed
-- requirement): "attach this instrument" is one live row however many instances say it, and
-- the store's attachOrConverge hands the loser the winner's row. PARTIAL, over the
-- non-terminal states - the predicate generated from sqlTerminalValueList() - because
-- detaching frees the slot: attaching the instrument again afterwards is legitimate and is a
-- NEW aggregate (INV-LIFE-04, the beneficiary asymmetry), while the detached row survives as
-- evidence.
CREATE UNIQUE INDEX payment_method_one_live_per_party_token
    ON paymentmethods.payment_method (party_id, token_reference)
    WHERE status NOT IN ('DETACHED');

-- Serves "this party's payment methods" - P5-TSK-005's listing, named now rather than
-- discovered by that task (the index-the-read precedent).
CREATE INDEX payment_method_by_party ON paymentmethods.payment_method (party_id);

-- DETACHED is terminal for every writer, the migrator included (INV-LIFE-04 at DB-CONSTRAINT
-- rank; the beneficiary V003 shape). Exactly one edge exists: ACTIVE -> DETACHED, with the
-- detachment instant arriving in the same statement; identity, token and display metadata are
-- immutable in any state - updated metadata is detach-and-reattach, never an edit. This is
-- what makes "raw SQL cannot resurrect a DETACHED row" true rather than asserted: the
-- column-narrowed grant below binds the application role, and this binds everyone.
CREATE OR REPLACE FUNCTION paymentmethods.payment_method_permits_only_detachment()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.id <> NEW.id
        OR OLD.party_id <> NEW.party_id
        OR OLD.token_reference <> NEW.token_reference
        OR OLD.brand <> NEW.brand
        OR OLD.display_suffix <> NEW.display_suffix
        OR OLD.expiry_month <> NEW.expiry_month
        OR OLD.expiry_year <> NEW.expiry_year
        OR OLD.created_at <> NEW.created_at THEN
        RAISE EXCEPTION 'a payment method''s identity, token and display metadata are immutable: updating is detach-and-reattach, never an edit (P5-TSK-004)';
    END IF;
    IF NOT (OLD.status = 'ACTIVE' AND NEW.status IN ('DETACHED')) THEN
        RAISE EXCEPTION 'the only edge a payment method has is ACTIVE -> DETACHED (INV-LIFE-04)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER payment_method_permits_only_detachment
    BEFORE UPDATE ON paymentmethods.payment_method
    FOR EACH ROW
    EXECUTE FUNCTION paymentmethods.payment_method_permits_only_detachment();

COMMENT ON TABLE paymentmethods.payment_method IS
    'A party''s tokenised instrument: a token reference plus display metadata, never anything reconstructable (INV-PAY-02; PHASE_5_PLAN.md section 8). DETACHED is terminal; the row survives as evidence, and attaching the instrument again is a new row through the freed slot.';
COMMENT ON COLUMN paymentmethods.payment_method.party_id IS
    'party.party.id by value; no cross-schema FK by design (ADR-0029''s rule). A payment method belongs to the PARTY: it outlives any one customer relationship.';
COMMENT ON COLUMN paymentmethods.payment_method.token_reference IS
    'The tokenisation provider''s reference - presented on authorize, held instead of card data. Plaintext by recorded decision (see the file header): a token alone charges nothing without the confined API credential, and encryption at rest is the recorded seam.';

-- THE GRANTS ARRIVE WITH THE TABLE (P5-TSK-001's floor made this available; PHASE_5_PLAN.md
-- section 8 names the discipline). SELECT and INSERT for the row's life; UPDATE narrowed to
-- exactly the detachment transition's columns - identity, token and display columns are
-- facts, not fields, and stay unwritable by the application role. No DELETE: a payment
-- method's end is a status, never an absence.
GRANT SELECT, INSERT ON paymentmethods.payment_method TO finapp_app;
GRANT UPDATE (status, detached_at) ON paymentmethods.payment_method TO finapp_app;
