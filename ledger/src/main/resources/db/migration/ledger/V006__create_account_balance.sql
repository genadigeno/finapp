-- The balance projection: fast reads that can never be behind (P3-TSK-009, ADR-0041,
-- INV-BAL-01, INV-BAL-05).
--
-- THE ONE DELIBERATELY MUTABLE FINANCIALLY-ADJACENT TABLE, AND WHY THAT IS NOT RULE 5
--   CLAUDE.md rule 5 forbids mutating a balance without an authoritative financial record.
--   This row is only ever mutated INSIDE the transaction of the posting it summarises -- the
--   projection and the postings commit together or neither does (ADR-0041 rule 1), so the
--   mutation always has its record and the row is a documented projection, never an
--   independent authority (INV-BAL-01). The authority stays the derivation from
--   ledger.journal_line; P3-TSK-010's continuous comparison is the evidence they agree.
--
-- NO DECISION READS THIS TABLE (INV-BAL-05)
--   The projection serves display. A hold, an overdraft check, any refusal-or-permit on funds
--   derives from the postings inside the account lock (ADR-0039). Structurally: the ledger
--   module exposes NO read of this table -- the readers arrive with P3-TSK-010 (the
--   verification job) and P3-TSK-018 (the display query), each saying what kind of number it
--   returns.
--
-- THE WATERMARK IS A COUNT, DELIBERATELY NOT AN ENTRY ID
--   Entry ids order by mint while commits interleave (AsOf's recorded caveat), so "everything
--   through entry X" is not a set a later observer can reconstruct -- an id watermark would
--   report false drift or mask real drift. last_entry_seq is the ordinal of the last entry
--   applied to THIS row, serialised by the row's own lock, with no ordering semantics to be
--   wrong about: "is this projection current?" is last_entry_seq = COUNT(DISTINCT entry_id)
--   over the account's lines, and the comparison tolerates in-flight entries by this column,
--   never by a time window (PHASE_3_PLAN section 14.6).
--
-- posted_minor CARRIES ITS SCALE, WHICH ADR-0041's SKETCH OMITTED
--   A persisted monetary value without its scale is uninterpretable (ADR-0003, INV-MON-05),
--   and an accumulating row is exactly where silent cross-scale addition would hide
--   (INV-MON-03). Not the generated MoneyColumns shape: posted_minor and holds_minor share
--   one currency and one scale, and the single-amount shape would store the pair twice.
--   The updater's SET is conditional on the scale matching, so a scale-divergent posting is
--   refused wholly rather than summed across scales.

CREATE TABLE ledger.account_balance (
    ledger_account_id uuid        PRIMARY KEY
                                  REFERENCES ledger.ledger_account (id),

    -- The account's own currency, bound by composite FK below (the V005 mechanism), so the
    -- row cannot disagree with its account for any writer (INV-MON-02).
    currency          char(3)     NOT NULL,

    -- The settled balance (normal-side sum minus opposite-side sum -- the sign convention is
    -- BalanceDerivation.settle's, stated once). Deliberately unconstrained in sign: negative
    -- is a legal state, and refusing it would be overdraft policy (P3-TSK-015's) wearing an
    -- accounting identity's clothes.
    posted_minor      bigint      NOT NULL,

    -- Active holds (INV-BAL-04: available = posted - holds, so the two must stay separable).
    -- Written 0 until P3-TSK-015 populates it; a hold is a reservation and never negative.
    holds_minor       bigint      NOT NULL,

    scale             smallint    NOT NULL,

    -- The applied-entry ordinal (see header). Starts at 1 with the row's first entry.
    last_entry_seq    bigint      NOT NULL,

    -- Application-supplied from one injected Clock, never DEFAULT now().
    updated_at        timestamptz NOT NULL,

    CONSTRAINT account_balance_currency_is_iso4217_shaped
        CHECK (currency ~ '^[A-Z]{3}$'),

    CONSTRAINT account_balance_scale_is_bounded
        CHECK (scale BETWEEN 0 AND 9),

    CONSTRAINT account_balance_holds_are_not_negative
        CHECK (holds_minor >= 0),

    CONSTRAINT account_balance_seq_starts_at_one
        CHECK (last_entry_seq >= 1),

    -- The V005 kind-binding mechanism applied to the projection: the referenced UNIQUE
    -- (id, currency) already exists, so the row's currency IS its account's.
    CONSTRAINT account_balance_currency_matches_account
        FOREIGN KEY (ledger_account_id, currency)
        REFERENCES ledger.ledger_account (id, currency)
);

COMMENT ON TABLE ledger.account_balance IS
    'The balance projection (ADR-0041): updated in the posting''s own transaction, so never '
    'behind; rebuildable from postings; NEVER read by a financial decision (INV-BAL-05) -- '
    'decisions derive from ledger.journal_line under the account lock. The one deliberately '
    'mutable financially-adjacent table, legitimate only because every mutation commits with '
    'the authoritative entry it summarises.';

-- SELECT and INSERT, plus UPDATE narrowed to the accumulating columns (the V004
-- column-narrowing precedent): the identity (ledger_account_id, currency, scale) is
-- unwritable by the application role -- a redenomination is a reviewed migration -- and
-- there is no DELETE, because a rebuild is the migrator's act.
GRANT SELECT, INSERT ON ledger.account_balance TO finapp_app;
GRANT UPDATE (posted_minor, holds_minor, last_entry_seq, updated_at)
    ON ledger.account_balance TO finapp_app;
