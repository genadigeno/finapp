-- A journal line's currency is its account's currency, at DB-CONSTRAINT (P3-TSK-006).
--
-- Found by asking what the posting command must validate, and answered one rank stronger than
-- a service check: a USD line on a JPY account corrupts every later balance derivation - the
-- derivation sums an account's lines in the account's one currency (ADR-0040) - and the rows
-- are unfixable history once written (INV-HIST-01). A check in the command binds the command;
-- this binds every writer, including the raw SQL P3-TSK-005's triggers exist for.
--
-- The mechanism is the kyc V008 kind-binding precedent: widen the referenced key so the FK
-- carries the fact. The original single-column FK stays - it is what makes ledger_account_id
-- NOT NULL semantics simple - and this composite one adds the currency agreement.

ALTER TABLE ledger.ledger_account
    ADD CONSTRAINT ledger_account_id_currency_is_referenceable
        UNIQUE (id, currency);

ALTER TABLE ledger.journal_line
    ADD CONSTRAINT journal_line_currency_matches_account
        FOREIGN KEY (ledger_account_id, currency)
        REFERENCES ledger.ledger_account (id, currency);
