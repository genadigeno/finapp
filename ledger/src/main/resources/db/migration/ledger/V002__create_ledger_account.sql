-- The chart of accounts: flat, typed, single-currency (P3-TSK-002, ADR-0040, ADR-0042).
--
-- EVERY GENERATED LIST BELOW HAS ONE DEFINITION
--   The five enum CHECKs come from each enum's sqlValueList(); the normal-balance coherence
--   rule from AccountType.sqlNormalBalanceRule(); the owner-kind coherence rule from
--   AccountPurpose.sqlOwnerKindRule(). LedgerAccountMigrationTest fails the build if this file
--   and the enums disagree on any of them (the P0-TSK-022 pattern) - so a type, purpose or
--   state added in code without its schema half cannot land quietly.
--
-- WHY THE CLASSIFICATION IS FROZEN BY TRIGGER AND NOT ONLY BY THE GRANT
--   INV-LED-06 is conditional - type and normal balance are free until a posting references
--   the account, and permanent after - and a grant cannot express a condition that lives in
--   another table. The column-narrowed grant below stops the APPLICATION touching the
--   classification at all; the trigger stops EVERY writer, the migrator and tools included,
--   once postings exist. Two layers, blind in different directions (the P1-TSK-020 argument).

CREATE TABLE ledger.ledger_account (
    -- UUIDv7, minted by the application (ADR-0013).
    id                uuid        PRIMARY KEY,

    account_type      text        NOT NULL,

    -- Derived from account_type in AccountType.normalBalance() and STORED, because INV-LED-06
    -- constrains something only if it is stored - a derived value cannot be frozen.
    normal_balance    text        NOT NULL,

    -- Explicit, always (INV-MON-02); ONE currency per account (ADR-0040) - a multi-currency
    -- product is n accounts. CHAR(3), the MoneyColumns shape.
    currency          char(3)     NOT NULL,

    owner_kind        text        NOT NULL,

    -- The owning customer account, BY VALUE and OPAQUE: the ledger does not know what a
    -- Customer Account is (ADR-0042), and an FK into accounts.customer_account would be
    -- coupling neither Gradle nor ArchUnit can see (ADR-0029, inherited by every module pair).
    -- NULL exactly for the platform's own accounts - the coherence CHECK below.
    owner_ref         uuid,

    purpose           text        NOT NULL,

    -- The Phase 14 seam (ADR-0040): a free-form external GL classification, nullable, with no
    -- writer in Phase 3. Deliberately NOT frozen by the trigger below - populating it is
    -- Phase 14's act, governed by INV-ACC-04's mapping versioning, not by INV-LED-06.
    gl_code           text,

    status            text        NOT NULL,

    -- Application-supplied from one injected Clock, never DEFAULT now() (DOMAIN_MODEL.md
    -- section Time).
    created_at        timestamptz NOT NULL,
    status_changed_at timestamptz NOT NULL,

    CONSTRAINT ledger_account_type_is_known
        CHECK (account_type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),

    CONSTRAINT ledger_account_normal_balance_is_known
        CHECK (normal_balance IN ('DEBIT', 'CREDIT')),

    CONSTRAINT ledger_account_owner_kind_is_known
        CHECK (owner_kind IN ('CUSTOMER', 'OPERATIONAL', 'SUSPENSE')),

    CONSTRAINT ledger_account_purpose_is_known
        CHECK (purpose IN ('CUSTOMER_WALLET', 'SETTLEMENT_CLEARING', 'FEE_REVENUE', 'FX_POSITION', 'ROUNDING_RESIDUAL', 'SUSPENSE_UNMATCHED')),

    CONSTRAINT ledger_account_status_is_known
        CHECK (status IN ('ACTIVE', 'POSTING_SUSPENDED', 'CLOSED')),

    -- The MoneyColumns discipline: CHAR(3) accepts 'US ', so the type is not the guarantee.
    CONSTRAINT ledger_account_currency_is_iso4217_shaped
        CHECK (currency ~ '^[A-Z]{3}$'),

    -- The type -> normal-balance derivation, stated in the schema so a writer that never ran
    -- AccountType.normalBalance() cannot store the pair the model has no meaning for.
    CONSTRAINT ledger_account_normal_balance_matches_type
        CHECK ((account_type = 'ASSET' AND normal_balance = 'DEBIT') OR (account_type = 'LIABILITY' AND normal_balance = 'CREDIT') OR (account_type = 'EQUITY' AND normal_balance = 'CREDIT') OR (account_type = 'REVENUE' AND normal_balance = 'CREDIT') OR (account_type = 'EXPENSE' AND normal_balance = 'DEBIT')),

    -- The purpose -> owner-kind derivation, same argument.
    CONSTRAINT ledger_account_owner_kind_matches_purpose
        CHECK ((purpose = 'CUSTOMER_WALLET' AND owner_kind = 'CUSTOMER') OR (purpose = 'SETTLEMENT_CLEARING' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FEE_REVENUE' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FX_POSITION' AND owner_kind = 'OPERATIONAL') OR (purpose = 'ROUNDING_RESIDUAL' AND owner_kind = 'OPERATIONAL') OR (purpose = 'SUSPENSE_UNMATCHED' AND owner_kind = 'SUSPENSE')),

    -- A customer account names its owner; a platform account never has one. The equality form
    -- catches both defects with one predicate.
    CONSTRAINT ledger_account_owner_ref_matches_kind
        CHECK ((owner_kind = 'CUSTOMER') = (owner_ref IS NOT NULL)),

    CONSTRAINT ledger_account_status_change_is_not_before_creation
        CHECK (status_changed_at >= created_at)
);

-- ONE ACCOUNT PER OWNER, PURPOSE AND CURRENCY - the concurrency arbiter for owned accounts:
-- "the customer account's GBP wallet account" is one thing however many instances ask, and the
-- store's createOrConverge hands the loser the winner's row (the openOrConverge idiom).
CREATE UNIQUE INDEX ledger_account_one_per_owner_purpose_currency
    ON ledger.ledger_account (owner_ref, purpose, currency)
    WHERE owner_ref IS NOT NULL;

-- ONE OPERATIONAL ACCOUNT PER PURPOSE AND CURRENCY: the ChartOfAccounts lookup (P3-TSK-003)
-- resolves purpose+currency to AN account, and two rows would make that answer ambiguous -
-- which for ROUNDING_RESIDUAL is INV-BAL-03's designated account no longer being designated.
CREATE UNIQUE INDEX ledger_account_one_operational_per_purpose_currency
    ON ledger.ledger_account (purpose, currency)
    WHERE owner_ref IS NULL;

-- Serves "the accounts of this product" (P3-TSK-012's balance view walks them).
CREATE INDEX ledger_account_by_owner ON ledger.ledger_account (owner_ref)
    WHERE owner_ref IS NOT NULL;

-- THE FREEZE (INV-LED-06), in two layers.
--
-- Layer 1 - the identity fields are facts, frozen UNCONDITIONALLY: which account this IS
-- (purpose, currency, owner, creation instant) is fixed at creation, posted to or not. An
-- unposted account with the wrong currency is corrected by closing it and opening another,
-- never by editing it - the laxer rule would buy a convenience nothing needs and cost the
-- certainty that an account's identity never moved.
--
-- Layer 2 - the CLASSIFICATION (account_type, normal_balance) freezes once a journal line
-- references the account. Conditional, exactly as INV-LED-06 states it.
--
-- THE FORWARD REFERENCE IS DELIBERATE AND GUARDED. ledger.journal_line is P3-TSK-005's table;
-- until it exists, nothing can have posted, so the classification is vacuously unposted-to and
-- layer 2 permits. to_regclass() answers NULL for an absent table without erroring, and the
-- EXISTS probe runs through EXECUTE so plpgsql never plans a statement against a table that is
-- not there. When P3-TSK-005 creates the table, the branch goes live with no migration touching
-- this function - and that task's sweep must prove it (recorded in its backlog tests).
CREATE OR REPLACE FUNCTION ledger.ledger_account_classification_is_frozen()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    posted boolean;
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.owner_kind IS DISTINCT FROM OLD.owner_kind
        OR NEW.owner_ref IS DISTINCT FROM OLD.owner_ref
        OR NEW.purpose IS DISTINCT FROM OLD.purpose
        OR NEW.currency IS DISTINCT FROM OLD.currency
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
    THEN
        RAISE EXCEPTION 'a ledger account''s identity is fixed at creation: open another account instead of editing this one'
            USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.account_type IS DISTINCT FROM OLD.account_type
        OR NEW.normal_balance IS DISTINCT FROM OLD.normal_balance
    THEN
        IF to_regclass('ledger.journal_line') IS NOT NULL THEN
            EXECUTE 'SELECT EXISTS (SELECT 1 FROM ledger.journal_line WHERE ledger_account_id = $1)'
                INTO posted
                USING OLD.id;
            IF posted THEN
                RAISE EXCEPTION 'reclassifying a posted-to account retroactively changes every report that summed it (INV-LED-06)'
                    USING ERRCODE = 'check_violation';
            END IF;
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER ledger_account_classification_is_frozen
    BEFORE UPDATE ON ledger.ledger_account
    FOR EACH ROW
    EXECUTE FUNCTION ledger.ledger_account_classification_is_frozen();

COMMENT ON TABLE ledger.ledger_account IS
    'The chart of accounts: flat, typed, one currency per account (ADR-0040). An accounting '
    'position, never a customer product (ADR-0042). Type and normal balance freeze once a line '
    'references the account (INV-LED-06, by trigger); identity fields are frozen always. '
    'Roll-up is GROUP BY purpose/type, never a tree.';

-- The DML this table genuinely requires and nothing more. UPDATE column-narrowed to the pair
-- that legitimately changes (the V004 narrowing): the application role cannot reach the
-- classification, the identity fields or gl_code AT ALL - the trigger above is for writers
-- this grant does not bind. No DELETE: an account ends by closing, and its history survives it
-- (INV-HIST-01).
GRANT SELECT, INSERT ON ledger.ledger_account TO finapp_app;
GRANT UPDATE (status, status_changed_at) ON ledger.ledger_account TO finapp_app;
