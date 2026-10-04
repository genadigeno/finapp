-- The counterparty registry, the COUNTERPARTY owner kind and the first counterparty-owned purpose
-- (P9-TSK-010; ADR-0078 sections 1-5; INV-RAIL-04, INV-SET-05, INV-LED-04, INV-LED-06).
--
-- WHY: Phase 8's three clearings are one OPERATIONAL account per currency because each has exactly
-- one counterparty. An FX provider (and later a corridor provider) is a counterparty with money owed
-- in both directions, and M9.8 adds a second of each kind: two providers on one shared account would
-- NET - a receivable from one silently paying a payable to the other - and a position proof over
-- their sum would prove nothing (INV-RAIL-04). So the account itself says who owes: a
-- COUNTERPARTY-owned account keyed (owner_ref = the counterparty's registry id, purpose, currency),
-- under V002's existing owned-account unique index. There is no shared account for two
-- counterparties to meet in.
--
-- THE REGISTRY (ADR-0078 section 2)
--   ledger.counterparty: a uuid identity (owner_ref is a uuid; a code does not fit it), a code in
--   the declarations' shape, a kind. Seeded by the migration that admits each counterparty - never
--   minted at runtime - with SELECT and INSERT grants only, and append-only by trigger for every
--   writer, the migrator included: renaming or re-pointing a counterparty under existing accounts
--   is impossible by construction (a new code is a new counterparty with new accounts).
--
-- THE FOUR RULES, RECREATED (the V011-V020 pattern; LedgerAccountMigrationTest reconciles them
-- against THIS file, each generated from the enums)
--   owner_kind_is_known        + COUNTERPARTY
--   purpose_is_known           + FX_PROVIDER_CLEARING
--   owner_kind_matches_purpose   FX_PROVIDER_CLEARING => COUNTERPARTY
--   owner_ref_matches_kind       COUNTERPARTY names an owner, like CUSTOMER and MERCHANT
--
-- THE REFERENCE, BY TRIGGER (ADR-0078 section 3)
--   owner_ref carries no FK by design (V002: it names a customer product, a merchant or now a
--   counterparty - a polymorphic reference no single FK can state). For COUNTERPARTY the reference
--   is held by a BEFORE INSERT trigger, for every writer, raw SQL included; V002's classification
--   freeze already refuses any later change to owner_kind or owner_ref, and the registry is
--   append-only, so the reference can never dangle. A NULL owner_ref is left to
--   owner_ref_matches_kind, so each rank refuses its own defect alone.
--
-- WHY FX_PROVIDER_CLEARING IS ADMITTED HERE, WITH NO ACCOUNT (a recorded deviation from
-- PHASE_9_PLAN.md's V021/V022 split)
--   Every counterparty rule - the reference trigger's acceptance, resolution by counterparty, the
--   keyed source register, the startup guard - is proven against a real counterparty-owned purpose,
--   not a vacuous one. Its type is decided now (ASSET, DEBIT-normal - pinned in
--   OperationalChartMigrationTest, INV-LED-06), before any account exists. Its counterparty
--   (fx-sim-a) and its five accounts arrive together in V022 (P9-TSK-011) - "every counterparty
--   clearing account is seeded by the migration that admits its counterparty" (ADR-0078 section 4)
--   - and it joins reconciledPositions() there, with its source. Nothing is seeded here.

CREATE TABLE ledger.counterparty (
    id         uuid        PRIMARY KEY,
    code       text        NOT NULL,
    kind       text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT counterparty_code_unique UNIQUE (code),
    CONSTRAINT counterparty_code_is_shaped CHECK (code ~ '^[a-z][a-z0-9-]{0,31}$'),
    CONSTRAINT counterparty_kind_is_known CHECK (kind IN ('FX_PROVIDER', 'CORRIDOR_PROVIDER'))
);

COMMENT ON TABLE ledger.counterparty IS
    'Declared external parties owning clearing positions (ADR-0078): FX and corridor providers. '
    'Seeded by the migration admitting each, never minted at runtime, never updated or deleted. '
    'A COUNTERPARTY ledger account''s owner_ref names a row here, by trigger.';

CREATE OR REPLACE FUNCTION ledger.counterparty_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'a counterparty is registered once and never changed: a new code is a new counterparty with new accounts (ADR-0078)'
        USING ERRCODE = 'check_violation';
END;
$$;

CREATE TRIGGER counterparty_is_append_only
    BEFORE UPDATE OR DELETE ON ledger.counterparty
    FOR EACH ROW
    EXECUTE FUNCTION ledger.counterparty_is_append_only();

GRANT SELECT, INSERT ON ledger.counterparty TO finapp_app;

ALTER TABLE ledger.ledger_account
    DROP CONSTRAINT ledger_account_owner_kind_is_known,
    ADD CONSTRAINT ledger_account_owner_kind_is_known
        CHECK (owner_kind IN ('CUSTOMER', 'MERCHANT', 'OPERATIONAL', 'SUSPENSE', 'COUNTERPARTY')),

    DROP CONSTRAINT ledger_account_purpose_is_known,
    ADD CONSTRAINT ledger_account_purpose_is_known
        CHECK (purpose IN ('CUSTOMER_WALLET', 'MERCHANT_PAYABLE', 'SETTLEMENT_CLEARING', 'PAYOUT_CLEARING', 'INSTANT_CLEARING', 'CASH_AT_BANK', 'CHARGEBACK_RECOVERABLE', 'DISPUTE_COSTS', 'PROCESSING_COSTS', 'RECONCILIATION_LOSSES', 'FEE_REVENUE', 'RECONCILIATION_GAINS', 'FX_POSITION', 'ROUNDING_RESIDUAL', 'FX_SPREAD_REVENUE', 'FX_PROVIDER_CLEARING', 'SUSPENSE_UNMATCHED')),

    DROP CONSTRAINT ledger_account_owner_kind_matches_purpose,
    ADD CONSTRAINT ledger_account_owner_kind_matches_purpose
        CHECK ((purpose = 'CUSTOMER_WALLET' AND owner_kind = 'CUSTOMER') OR (purpose = 'MERCHANT_PAYABLE' AND owner_kind = 'MERCHANT') OR (purpose = 'SETTLEMENT_CLEARING' AND owner_kind = 'OPERATIONAL') OR (purpose = 'PAYOUT_CLEARING' AND owner_kind = 'OPERATIONAL') OR (purpose = 'INSTANT_CLEARING' AND owner_kind = 'OPERATIONAL') OR (purpose = 'CASH_AT_BANK' AND owner_kind = 'OPERATIONAL') OR (purpose = 'CHARGEBACK_RECOVERABLE' AND owner_kind = 'OPERATIONAL') OR (purpose = 'DISPUTE_COSTS' AND owner_kind = 'OPERATIONAL') OR (purpose = 'PROCESSING_COSTS' AND owner_kind = 'OPERATIONAL') OR (purpose = 'RECONCILIATION_LOSSES' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FEE_REVENUE' AND owner_kind = 'OPERATIONAL') OR (purpose = 'RECONCILIATION_GAINS' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FX_POSITION' AND owner_kind = 'OPERATIONAL') OR (purpose = 'ROUNDING_RESIDUAL' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FX_SPREAD_REVENUE' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FX_PROVIDER_CLEARING' AND owner_kind = 'COUNTERPARTY') OR (purpose = 'SUSPENSE_UNMATCHED' AND owner_kind = 'SUSPENSE')),

    DROP CONSTRAINT ledger_account_owner_ref_matches_kind,
    ADD CONSTRAINT ledger_account_owner_ref_matches_kind
        CHECK ((owner_kind IN ('CUSTOMER', 'MERCHANT', 'COUNTERPARTY')) = (owner_ref IS NOT NULL));

CREATE OR REPLACE FUNCTION ledger.ledger_account_counterparty_is_registered()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.owner_kind = 'COUNTERPARTY' AND NEW.owner_ref IS NOT NULL
        AND NOT EXISTS (SELECT 1 FROM ledger.counterparty WHERE id = NEW.owner_ref) THEN
        RAISE EXCEPTION 'a counterparty account names a registered counterparty: owner_ref % is not in ledger.counterparty (ADR-0078, INV-LED-04)', NEW.owner_ref
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER ledger_account_counterparty_is_registered
    BEFORE INSERT ON ledger.ledger_account
    FOR EACH ROW
    EXECUTE FUNCTION ledger.ledger_account_counterparty_is_registered();
