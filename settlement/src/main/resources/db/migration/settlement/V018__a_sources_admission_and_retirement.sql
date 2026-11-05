-- A settlement source's admission and retirement days (P14-TSK-025; the owner's rulings of 2026-11-06 on
-- P14-TSK-012's (c) and (e); ADR-0117's note of that date; INV-ACC-09, INV-ACC-05).
--
-- WHY: accounting's close asks, for each month, a counting reconciliation run of every source that served the
-- month. Until now the row said only ACTIVE or RETIRED, so a source was required for every month judged after its
-- row existed, months before its admission included, and a retired one for none, months it served included.
-- Served is a dated fact, so it is stored as two days: a month requires a source admitted by its last day and not
-- retired before its first (accounting's ReconciliationRunCoverage.Source#requiredIn; the rule set's day is read
-- from reconciliation's own rows, no reconciliation migration).
--
-- THE BACKFILL: admitted_on 1970-01-01 for every row standing, retired_on NULL. Each of the eight was seeded by a
-- migration (V002, V015, V016, V017), and a deployment runs its migrations before its schedule opens its first
-- accounting period, so every month any deployment holds was served by each of them. The true day is that
-- deployment's migration, recorded nowhere, and no settlement migration reads accounting's periods. Any later
-- fixed day would excuse a month silently - the seeds' stamp, 2026-09-27, excuses the shared test fixture's
-- January 1999 - so the backfill is the day before any month: LocalDate.EPOCH, PostingRuleRegister.ORIGIN's
-- "since the beginning". For the four counterparty sources the rule set's first activation, a person's act, is
-- what then starts the requirement (P14-TSK-012's (k)). No build retires a source, so every row is ACTIVE; a row
-- retired by hand stops this migration on its CHECK rather than have a day guessed for it.
--
-- WHO SETS THEM: a migration, and nobody else. The default is dropped once the backfill stands, so a new source's
-- migration states its admission. finapp_app keeps SELECT on the table and UPDATE (status, next_sequence) alone:
-- no grant here. Retirement keeps its one door, the ACTIVE -> RETIRED edge; a writer that does not state the day
-- has the database's UTC day stamped by the trigger, so a status edge never leaves a retired row undated.
--
-- THE DAYS MOVE ONLY TOWARDS REQUIRING MORE, for every writer: admitted_on never later; retired_on set with the
-- edge, never cleared, never earlier, never past the database's UTC day. A correction can widen what a month
-- requires, and never excuse a month a source served.
--
-- WHAT IS UNCHANGED: settlement's own door. Ingestion refuses a RETIRED source by its status (SOURCE_RETIRED), as
-- before; the days are accounting's reading of which months a source served. So a source retired before its last
-- month's run would leave that month asking a run its door refuses: retire it only once that month's run counts
-- (OPERATIONS_RUNBOOK.md section 10.16).

ALTER TABLE settlement.source
    ADD COLUMN admitted_on DATE NOT NULL DEFAULT DATE '1970-01-01',
    ADD COLUMN retired_on  DATE;

ALTER TABLE settlement.source ALTER COLUMN admitted_on DROP DEFAULT;

ALTER TABLE settlement.source
    ADD CONSTRAINT source_retired_on_iff_retired CHECK ((status = 'RETIRED') = (retired_on IS NOT NULL)),
    ADD CONSTRAINT source_retired_after_admitted CHECK (retired_on IS NULL OR retired_on >= admitted_on);

COMMENT ON COLUMN settlement.source.admitted_on IS
    'The first day the source served (P14-TSK-025): a month requires its run only if admitted by the month''s last day. Set by a migration; never moves later.';
COMMENT ON COLUMN settlement.source.retired_on IS
    'The day the source was retired (P14-TSK-025), present exactly when RETIRED: a month requires its run unless retired before the month''s first day. Set with the edge, never earlier, never past the database''s day.';

-- V002's three rules, verbatim, and the days' (P14-TSK-025). The trigger binding stands unchanged.
CREATE OR REPLACE FUNCTION settlement.source_moves_forward_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.code IS DISTINCT FROM OLD.code
            OR NEW.kind IS DISTINCT FROM OLD.kind THEN
        RAISE EXCEPTION 'a settlement source''s identity is frozen (P8-TSK-002)';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status
            AND NOT (OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED') THEN
        RAISE EXCEPTION 'a settlement source retires once and never returns (P8-TSK-002): a re-opened source is a NEW source';
    END IF;
    IF NEW.next_sequence < OLD.next_sequence THEN
        RAISE EXCEPTION 'a settlement source''s sequence never walks backwards (P8-TSK-002)';
    END IF;
    IF NEW.admitted_on > OLD.admitted_on THEN
        RAISE EXCEPTION 'a settlement source''s admission never moves later (P14-TSK-025): a later day would excuse a month it served';
    END IF;
    IF OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED' AND NEW.retired_on IS NULL THEN
        NEW.retired_on := (now() AT TIME ZONE 'UTC')::date;
    END IF;
    IF NEW.retired_on IS DISTINCT FROM OLD.retired_on THEN
        IF NEW.retired_on IS NULL OR NEW.retired_on < OLD.retired_on THEN
            RAISE EXCEPTION 'a settlement source''s retirement is never cleared or moved earlier (P14-TSK-025): an earlier day would excuse a month it served';
        END IF;
        IF NEW.retired_on > (now() AT TIME ZONE 'UTC')::date THEN
            RAISE EXCEPTION 'a settlement source is retired on a day that has come (P14-TSK-025): its door shuts now';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
