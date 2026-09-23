-- MERCHANT_PAYABLE joins the chart of accounts (P6-TSK-003, ADR-0050, ADR-0051).
--
-- The seventh purpose and the fourth owner kind: what the platform owes one merchant -
-- captured minus fees minus refunds minus payouts - as a ledger POSITION and nothing else
-- (INV-MER-02). The capture will credit it gross with the fee assessed in the same entry;
-- the payout will debit it under a hold. owner_ref names the merchant, by value and opaque,
-- exactly as CUSTOMER rows name the customer account (ADR-0029, ADR-0042).
--
-- WHY THIS IS A NEW MIGRATION AND NOT AN EDIT OF V002
--   V002 is applied history (ADR-0011): its generated CHECKs were correct for the enums of
--   their day, and an edited applied migration means the database and the repository disagree.
--   Adding an enum member regenerates four rules, so the four constraints are RECREATED here
--   from the enums' current definitions, and LedgerAccountMigrationTest now reconciles these
--   four against THIS file (the latest definition) while V002 keeps the rest. The drop/add
--   pairs run in one transaction; existing rows satisfy the widened rules by construction
--   (every old value remains legal - the lists only grew).
--
-- WHY THE OWNER-REF RULE IS NOW GENERATED
--   V002 hand-wrote (owner_kind = 'CUSTOMER') = (owner_ref IS NOT NULL) - correct while
--   exactly one kind had an owner, and a generated rule the moment a second did. The
--   replacement comes from OwnerKind.sqlOwnerRefRule(), so a future owned kind cannot land
--   without this constraint moving with it (one definition, two artefacts).

ALTER TABLE ledger.ledger_account
    DROP CONSTRAINT ledger_account_owner_kind_is_known,
    ADD CONSTRAINT ledger_account_owner_kind_is_known
        CHECK (owner_kind IN ('CUSTOMER', 'MERCHANT', 'OPERATIONAL', 'SUSPENSE')),

    DROP CONSTRAINT ledger_account_purpose_is_known,
    ADD CONSTRAINT ledger_account_purpose_is_known
        CHECK (purpose IN ('CUSTOMER_WALLET', 'MERCHANT_PAYABLE', 'SETTLEMENT_CLEARING', 'FEE_REVENUE', 'FX_POSITION', 'ROUNDING_RESIDUAL', 'SUSPENSE_UNMATCHED')),

    DROP CONSTRAINT ledger_account_owner_kind_matches_purpose,
    ADD CONSTRAINT ledger_account_owner_kind_matches_purpose
        CHECK ((purpose = 'CUSTOMER_WALLET' AND owner_kind = 'CUSTOMER') OR (purpose = 'MERCHANT_PAYABLE' AND owner_kind = 'MERCHANT') OR (purpose = 'SETTLEMENT_CLEARING' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FEE_REVENUE' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FX_POSITION' AND owner_kind = 'OPERATIONAL') OR (purpose = 'ROUNDING_RESIDUAL' AND owner_kind = 'OPERATIONAL') OR (purpose = 'SUSPENSE_UNMATCHED' AND owner_kind = 'SUSPENSE')),

    DROP CONSTRAINT ledger_account_owner_ref_matches_kind,
    ADD CONSTRAINT ledger_account_owner_ref_matches_kind
        CHECK ((owner_kind IN ('CUSTOMER', 'MERCHANT')) = (owner_ref IS NOT NULL));

-- The one-per-owner-purpose-currency index is untouched: it keys on owner_ref alone and is
-- kind-agnostic by construction, so "the merchant's EUR payable account" is arbitrated by
-- exactly the mechanism that arbitrates "the customer account's EUR wallet".
