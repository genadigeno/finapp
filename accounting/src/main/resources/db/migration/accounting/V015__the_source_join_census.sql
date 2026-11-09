-- The source-join census (P15-TSK-035; THREAT_MODEL.md T15, section 5): every journal entry read against the domain record that
-- caused it. These are the census's own records, and the only tables it writes.
--
-- THE HEAD IS ONE ROW, seeded here at the floor: the walk's position, what it has judged, and whether its last run read everything.
-- The position is (txid, id) of the last entry judged, the order ledger V034 gives the journal: every txid below
-- pg_snapshot_xmin(pg_current_snapshot()) is finished, so nothing can be inserted behind the position. No clock enters it.
-- One walker at a time across every instance: each batch claims this row FOR UPDATE SKIP LOCKED, and no other arbitration exists.
-- Seeded at the floor, so the first run judges whatever journal already exists (OPERATIONS_RUNBOOK.md 11.27).
--
-- THE HEAD MOVES FORWARD ONLY, BY TRIGGER: backward is refused, and past every finished transaction is refused, for every writer. The
-- application's credential holds UPDATE on this row because the census advances it, so that credential can still jump it forward within
-- the finished transactions; THREAT_MODEL.md T15 names that residual.
--
-- A FINDING IS BORN ONCE PER ENTRY and never updated, so re-judging what a crash did not commit converges (ON CONFLICT DO NOTHING).
-- It records the entry, what the census found, the door, the namespace and a reason in words - never an amount, never a line.
-- evidence is bounded because it is prose assembled from fixed strings, never from a value read from a row; namespace is the entry's
-- own key text, cut to its bound, and a forged key's text can reach it, so it is classified as such.
-- Append-only and never truncated, for every writer (DATA_RETENTION.md: kept).
--
-- THE CLOCK: found_at is stamped by the database (the trigger below), and the stamps on the head by the writer's statement on the
-- database's clock (JdbcSourceCensusStore). No instance clock enters any of it.
--
-- NO FINANCIAL COLUMN, NO FOREIGN KEY TO THE LEDGER (ADR-0029: schema-per-module, identifiers by value). No DELETE or UPDATE on the
-- findings for any application role; the head is updated, never deleted, by this module's code alone.
--
-- An operator investigating a page reads both in a read-only session (OPERATIONS_RUNBOOK.md section 11.27). The reporting
-- role's grants are report generation's closed register (P15-TSK-021), and this is not a report run's table.

CREATE TABLE accounting.source_census_head (
    id                 boolean     PRIMARY KEY DEFAULT true,
    position_txid      xid8        NOT NULL,
    position_id        uuid        NOT NULL,
    entries_judged     bigint      NOT NULL DEFAULT 0,
    advanced_at        timestamptz NOT NULL,
    last_success_at    timestamptz,
    last_failure_at    timestamptz,
    last_failure_class text,
    updated_at         timestamptz NOT NULL,
    CONSTRAINT source_census_head_is_one_row CHECK (id),
    CONSTRAINT source_census_head_judged_is_not_negative CHECK (entries_judged >= 0),
    CONSTRAINT source_census_head_failure_class_is_bounded CHECK (last_failure_class IS NULL OR char_length(last_failure_class) BETWEEN 1 AND 200)
);

INSERT INTO accounting.source_census_head (position_txid, position_id, advanced_at, updated_at)
VALUES ('0', '00000000-0000-7000-8000-000000000000', statement_timestamp(), statement_timestamp());

COMMENT ON TABLE accounting.source_census_head IS
    'The source-join census''s single head (P15-TSK-035): the journal position (txid, id) judged through, and its health. One walker holds it at a time, FOR UPDATE SKIP LOCKED.';

CREATE OR REPLACE FUNCTION accounting.source_census_head_only_moves_forward()
    RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF TG_OP <> 'UPDATE' THEN
        RAISE EXCEPTION 'the source-join census''s head is never removed (P15-TSK-035)';
    END IF;
    IF (NEW.position_txid, NEW.position_id) IS NOT DISTINCT FROM (OLD.position_txid, OLD.position_id) THEN
        RETURN NEW;
    END IF;
    IF NEW.position_txid < OLD.position_txid
            OR (NEW.position_txid = OLD.position_txid AND NEW.position_id < OLD.position_id) THEN
        RAISE EXCEPTION 'the source-join census''s head only moves forward (P15-TSK-035)';
    END IF;
    IF NEW.position_txid >= pg_catalog.pg_snapshot_xmin(pg_catalog.pg_current_snapshot()) THEN
        RAISE EXCEPTION 'the source-join census''s head cannot pass a transaction still running (P15-TSK-035)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER source_census_head_only_moves_forward
    BEFORE UPDATE OR DELETE ON accounting.source_census_head
    FOR EACH ROW
    EXECUTE FUNCTION accounting.source_census_head_only_moves_forward();

CREATE OR REPLACE FUNCTION accounting.source_census_is_never_truncated()
    RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    RAISE EXCEPTION 'the source-join census''s records are never truncated (P15-TSK-035, INV-HIST-01)';
END;
$$;

CREATE TRIGGER source_census_head_is_never_truncated
    BEFORE TRUNCATE ON accounting.source_census_head
    FOR EACH STATEMENT
    EXECUTE FUNCTION accounting.source_census_is_never_truncated();

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

CREATE OR REPLACE FUNCTION accounting.source_census_finding_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a source-join census finding is append-only (P15-TSK-035, INV-HIST-01)';
    END IF;
    NEW.found_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER source_census_finding_is_append_only
    BEFORE INSERT OR UPDATE OR DELETE ON accounting.source_census_finding
    FOR EACH ROW
    EXECUTE FUNCTION accounting.source_census_finding_is_append_only();

CREATE TRIGGER source_census_finding_is_never_truncated
    BEFORE TRUNCATE ON accounting.source_census_finding
    FOR EACH STATEMENT
    EXECUTE FUNCTION accounting.source_census_is_never_truncated();

GRANT SELECT, UPDATE ON accounting.source_census_head TO finapp_app;
GRANT SELECT, INSERT ON accounting.source_census_finding TO finapp_app;
