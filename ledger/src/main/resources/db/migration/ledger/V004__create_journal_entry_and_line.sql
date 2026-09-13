-- The journal: the authoritative financial record's rows (P3-TSK-005, INV-LED-01..05,
-- INV-HIST-01).
--
-- IMMUTABLE BY PRIVILEGE, AND THE GRANTS ARE THE HEADLINE
--   The application role receives SELECT and INSERT and NOTHING ELSE - no UPDATE, no DELETE.
--   This is the moment the mechanism P0-TSK-022 built finally carries the invariants it was
--   built for: financial history cannot be edited by the application because the operation
--   does not exist for its role. A BEFORE UPDATE OR DELETE trigger below extends the same
--   refusal to every OTHER writer - the migrator, an operator, a tool - the freeze-trigger
--   precedent; archival that ever needs to move rows drops it by reviewed migration.
--
-- BALANCED BY CONSTRAINT, AND WHY IT IS A CONSTRAINT TRIGGER
--   INV-LED-01 spans an entry's sibling lines, and a CHECK sees one row. PostgreSQL closes
--   the other doors too: CHECK constraints cannot be DEFERRABLE (only UNIQUE/PK/FK/EXCLUDE
--   can), and SQL ASSERTION is unimplemented. Sum columns on the entry row would fail
--   multi-currency entries and need UPDATE on an insert-only table to maintain. So: two
--   constraint triggers, deferrable and initially deferred, firing at COMMIT when the entry is
--   whole - the only mechanism that sees all the lines AND binds a writer that never runs our
--   code, which is the writer the constraint exists for.
--
--   TWO triggers, and the split is P3-TSK-004's own finding arriving at the schema: an entry
--   with ZERO lines balances vacuously - every currency's two sums are equal at nothing - and
--   a trigger on journal_line never fires for it. So the line trigger owns balance-and-scale,
--   and a second trigger anchored to the ENTRY row owns INV-LED-02's line count.
--
-- THE LINE'S ACCOUNT REFERENCE IS A REAL FOREIGN KEY
--   Same schema, same module - the no-cross-schema-FK rule (ADR-0029) does not apply, and a
--   line naming an account that does not exist is corrupt on arrival. The FK also arms the
--   classification freeze: from this migration on, ledger_account_classification_is_frozen's
--   posted branch is live (its to_regclass guard resolves), and the tests that proved it
--   against a stand-in table now prove it against this one.

CREATE TABLE ledger.journal_entry (
    -- UUIDv7, minted by the application (ADR-0013): time-ordered, so the journal's
    -- append-only write pattern stays an append-only index pattern.
    id                uuid        PRIMARY KEY,

    -- The three dates (DOMAIN_MODEL.md section Time). posting_date decides the accounting
    -- period; value_date is when value is available; both are DOMAIN INPUTS the command
    -- demands - created_at alone is system time. No ordering constraint between them, on
    -- purpose: a back-dated correction posts today about then, and late settlement's value
    -- date precedes its system time.
    posting_date      date        NOT NULL,
    value_date        date        NOT NULL,

    entry_type        text        NOT NULL,

    -- The originating economic event (INV-LED-05): what caused this posting, as an
    -- identifier-shaped reference. NOT NULL - a posting nobody can explain is a posting
    -- nobody can defend. The reversal's link to its ORIGINAL entry is a different fact and
    -- arrives as its own column with P3-TSK-016.
    reference         text        NOT NULL,

    -- Required for an adjustment (INV-REV-04): a person moving value the system would not
    -- have moved is legitimate only as far as its justification. An implication rather than
    -- an equality, so P3-TSK-016 decides freely whether a reversal carries one. The bound is
    -- AuditRecord.MAX_REASON_LENGTH, reconciled by JournalEntryMigrationTest.
    reason            text,

    -- Attribution (INV-LED-05), bounds mirroring platform.audit_record's columns.
    actor_id          text        NOT NULL,
    correlation_id    text        NOT NULL,
    causation_id      text        NOT NULL,

    -- The idempotency scope of the command that wrote this entry (P3-TSK-006). NOT NULL from
    -- birth: an entry not tied to a command is exactly what INV-LED-04 forbids, and the
    -- column existing nullable "until the command lands" would be a week of rows nobody can
    -- tie back.
    idempotency_scope text        NOT NULL,

    -- Application-supplied from one injected Clock, never DEFAULT now().
    created_at        timestamptz NOT NULL,

    CONSTRAINT journal_entry_type_is_known
        CHECK (entry_type IN ('POSTING', 'REVERSAL', 'ADJUSTMENT')),

    CONSTRAINT journal_entry_adjustment_has_reason
        CHECK (entry_type <> 'ADJUSTMENT' OR reason IS NOT NULL),

    CONSTRAINT journal_entry_reason_is_bounded
        CHECK (reason IS NULL OR length(reason) BETWEEN 1 AND 1000),

    CONSTRAINT journal_entry_reference_is_bounded
        CHECK (length(reference) BETWEEN 1 AND 200),

    CONSTRAINT journal_entry_actor_id_is_bounded
        CHECK (length(actor_id) BETWEEN 1 AND 200),

    CONSTRAINT journal_entry_correlation_id_is_bounded
        CHECK (length(correlation_id) BETWEEN 1 AND 128),

    CONSTRAINT journal_entry_causation_id_is_bounded
        CHECK (length(causation_id) BETWEEN 1 AND 128),

    CONSTRAINT journal_entry_idempotency_scope_is_bounded
        CHECK (length(idempotency_scope) BETWEEN 1 AND 200)
);

CREATE TABLE ledger.journal_line (
    -- UUIDv7, minted by the store per line.
    id                uuid     PRIMARY KEY,

    entry_id          uuid     NOT NULL REFERENCES ledger.journal_entry (id),

    -- The real FK (see header). Takes FOR KEY SHARE on the account row per insert, which is
    -- share-compatible across concurrent postings - two instances posting to one account
    -- both succeed, and nothing is lost because nothing is updated (ADR-0039).
    ledger_account_id uuid     NOT NULL REFERENCES ledger.ledger_account (id),

    direction         text     NOT NULL,

    -- The ADR-0003 three-column monetary shape, generated by MoneyColumns.ColumnNames.ddl()
    -- and pinned verbatim by JournalEntryMigrationTest so the shape cannot drift per table.
    amount_minor BIGINT NOT NULL, currency CHAR(3) NOT NULL, scale SMALLINT NOT NULL, CHECK (currency ~ '^[A-Z]{3}$'), CHECK (scale BETWEEN 0 AND 9),

    -- The entry's line order, persisted; the domain's list order is the order.
    seq               integer  NOT NULL,

    CONSTRAINT journal_line_direction_is_known
        CHECK (direction IN ('DEBIT', 'CREDIT')),

    -- Direction carries the sign (P3-TSK-004): a signed amount would make "unbalanced" a
    -- subtraction that happens to be non-zero, and a zero line asserts nothing.
    CONSTRAINT journal_line_amount_is_positive
        CHECK (amount_minor > 0),

    CONSTRAINT journal_line_seq_is_natural
        CHECK (seq >= 0),

    CONSTRAINT journal_line_seq_is_unique_per_entry
        UNIQUE (entry_id, seq)
);

-- The read every balance derivation performs (P3-TSK-008): an account's lines, in order.
CREATE INDEX journal_line_by_account ON ledger.journal_line (ledger_account_id, entry_id);

-- INV-LED-01, at commit, for every writer. Per-currency sums over the whole entry, and ONE
-- scale per currency - summing raw minor units across scales is meaningless, the domain's
-- mixed-scale refusal restated where the domain cannot reach. Fires once per inserted line,
-- so an entry's validation runs N times; correctness-first redundancy, recorded.
CREATE OR REPLACE FUNCTION ledger.journal_entry_balances()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    offending text;
BEGIN
    SELECT per_currency.currency INTO offending
    FROM (
        SELECT
            line.currency,
            sum(CASE WHEN line.direction = 'DEBIT' THEN line.amount_minor ELSE 0 END)
                AS debits,
            sum(CASE WHEN line.direction = 'CREDIT' THEN line.amount_minor ELSE 0 END)
                AS credits,
            count(DISTINCT line.scale) AS scales
        FROM ledger.journal_line line
        WHERE line.entry_id = NEW.entry_id
        GROUP BY line.currency
    ) per_currency
    WHERE per_currency.debits <> per_currency.credits OR per_currency.scales > 1
    LIMIT 1;

    IF offending IS NOT NULL THEN
        -- The currency and the fact - never the sums (INV-AUD-02: this message reaches logs).
        RAISE EXCEPTION 'journal entry does not balance in % (INV-LED-01): debits must equal credits per currency, at one scale per currency', offending
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER journal_entry_balances
    AFTER INSERT ON ledger.journal_line
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION ledger.journal_entry_balances();

-- INV-LED-02, anchored to the ENTRY row because the line trigger never fires for an entry
-- with no lines - and a zero-line entry balances vacuously (P3-TSK-004's finding, at the
-- schema).
CREATE OR REPLACE FUNCTION ledger.journal_entry_has_lines()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF (SELECT count(*) FROM ledger.journal_line WHERE entry_id = NEW.id) < 2 THEN
        RAISE EXCEPTION 'a journal entry has at least two lines (INV-LED-02): a single-sided posting is unbalanced value movement by definition'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER journal_entry_has_lines
    AFTER INSERT ON ledger.journal_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION ledger.journal_entry_has_lines();

-- INV-LED-03 / INV-HIST-01 for the writers the grants below do not bind. Unconditional: a
-- posted row is never edited by anybody, and a correction is a new entry (INV-REV-01).
CREATE OR REPLACE FUNCTION ledger.journal_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'financial history is never edited (INV-HIST-01): corrections are new entries - reversals or adjustments - and the original stays byte-identical'
        USING ERRCODE = 'check_violation';
END;
$$;

CREATE TRIGGER journal_entry_is_append_only
    BEFORE UPDATE OR DELETE ON ledger.journal_entry
    FOR EACH ROW
    EXECUTE FUNCTION ledger.journal_is_append_only();

CREATE TRIGGER journal_line_is_append_only
    BEFORE UPDATE OR DELETE ON ledger.journal_line
    FOR EACH ROW
    EXECUTE FUNCTION ledger.journal_is_append_only();

COMMENT ON TABLE ledger.journal_entry IS
    'The authoritative financial record: balanced immutable double-entry postings '
    '(INV-LED-01..05). Append-only at DB-PRIVILEGE for the application role and by trigger '
    'for everyone else; corrections are new entries. Balance is enforced at COMMIT by '
    'deferred constraint triggers, because a CHECK cannot see sibling rows.';

-- The DML the journal genuinely requires and NOTHING more. This pair of lines is what the
-- phase exists for: no UPDATE, no DELETE, so INV-LED-03 and INV-HIST-01 hold at DB-PRIVILEGE.
GRANT SELECT, INSERT ON ledger.journal_entry TO finapp_app;
GRANT SELECT, INSERT ON ledger.journal_line TO finapp_app;
