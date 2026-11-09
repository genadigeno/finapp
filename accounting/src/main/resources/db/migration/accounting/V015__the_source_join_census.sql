-- The source-join census (P15-TSK-035; THREAT_MODEL.md T15, section 5): every journal entry read against the domain record that
-- caused it. These are the census's own records, and the only tables it writes.
--
-- THE HEAD IS ONE ROW, seeded here at the floor (the UUIDv7 nil-time position of ledger.journal_entry's keyset, the first
-- position before any entry): the walk's position, what it has judged, and whether its last run read everything. One walker at a
-- time across every instance: each batch claims this row FOR UPDATE SKIP LOCKED, and no other arbitration exists.
--
-- A FINDING IS BORN ONCE PER ENTRY and never updated, so re-judging what a crash did not commit converges (ON CONFLICT DO NOTHING).
-- It records the entry, what the census found, the door, the namespace and a reason in words - never an amount, never a line.
-- evidence is bounded because it is prose assembled from fixed strings, never from a value read from a row.
--
-- THE CLOCK: the writers stamp clock_timestamp() on the database (JdbcSourceCensusStore). No column default stands in for it.
--
-- NO FINANCIAL COLUMN, NO FOREIGN KEY TO THE LEDGER (ADR-0029: schema-per-module, identifiers by value). No DELETE or UPDATE on the
-- findings for any application role; the head is updated, never deleted, by this module's code alone.
--
-- An operator investigating a page reads both in a read-only session (OPERATIONS_RUNBOOK.md section 11.27). The reporting
-- role's grants are report generation's closed register (P15-TSK-021), and this is not a report run's table.

CREATE TABLE accounting.source_census_head (
    id                 boolean     PRIMARY KEY DEFAULT true,
    position           uuid        NOT NULL,
    entries_judged     bigint      NOT NULL DEFAULT 0,
    last_success_at    timestamptz,
    last_failure_at    timestamptz,
    last_failure_class text,
    updated_at         timestamptz NOT NULL,
    CONSTRAINT source_census_head_is_one_row CHECK (id),
    CONSTRAINT source_census_head_judged_is_not_negative CHECK (entries_judged >= 0),
    CONSTRAINT source_census_head_failure_class_is_bounded CHECK (last_failure_class IS NULL OR char_length(last_failure_class) BETWEEN 1 AND 200)
);

INSERT INTO accounting.source_census_head (position, updated_at)
VALUES ('00000000-0000-7000-8000-000000000000', statement_timestamp());

COMMENT ON TABLE accounting.source_census_head IS
    'The source-join census''s single head (P15-TSK-035): the journal position judged through, and its health. One walker holds it at a time, FOR UPDATE SKIP LOCKED.';

CREATE TABLE accounting.source_census_finding (
    entry_id  uuid        PRIMARY KEY,
    kind      text        NOT NULL,
    door      text        NOT NULL,
    namespace text        NOT NULL,
    evidence  text        NOT NULL,
    found_at  timestamptz NOT NULL,
    CONSTRAINT source_census_finding_kind_is_known CHECK (kind IN ('UNSOURCED', 'MISMATCHED')),
    CONSTRAINT source_census_finding_door_is_known CHECK (door IN ('POSTING', 'REVERSAL', 'ADJUSTMENT')),
    CONSTRAINT source_census_finding_namespace_is_bounded CHECK (char_length(namespace) BETWEEN 1 AND 100),
    CONSTRAINT source_census_finding_evidence_is_bounded CHECK (char_length(evidence) BETWEEN 1 AND 500)
);

CREATE INDEX source_census_finding_by_found_at ON accounting.source_census_finding (found_at);

COMMENT ON TABLE accounting.source_census_finding IS
    'An entry the source-join census counted (P15-TSK-035): no record behind it, or a record whose figures differ. Born once, never updated, kept for the investigation; never an amount.';

GRANT SELECT, UPDATE ON accounting.source_census_head TO finapp_app;
GRANT SELECT, INSERT ON accounting.source_census_finding TO finapp_app;
