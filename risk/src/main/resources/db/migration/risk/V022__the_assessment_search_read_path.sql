-- The assessment search's read path (P17-TSK-020, gap G-91; finapp-admin-panel B4.2, ADR-C133).
--
-- WHY
--   GET /v1/operator/risk/assessments reads a required, bounded window of sealed assessments newest first, (assessed_at, id)
--   descending, the id breaking the tie, walked by keyset from the cursor, the reader's own party withheld by the statement:
--   risk_assessment_by_time serves it as one index range per page, with no sort. A party's assessments ride V007's
--   risk_assessment_by_party (party_id, assessed_at), the id ordering only the rows that share an instant - so no second index
--   is added to the platform's busiest risk table for that. The plans are recorded in OPERATIONS_RUNBOOK.md section 11.48 from
--   risk's AssessmentSearchDatabaseTest.
--
-- WHAT IT ADDS, AND ONLY THAT
--   One index. No column, row, constraint, trigger or grant: the sealed assessment stays append-only (V007's trigger, V021's
--   TRUNCATE refusal). Expand-only: the previous release neither reads nor needs it.
--
-- LOCKS: BUILT HERE, OR AHEAD BY THE SAME NAME
--   In the migration's transaction, not CONCURRENTLY: the build refuses a migration outside a transaction
--   (EveryMigrationRunsInATransactionTest; DATA_MIGRATIONS.md section 4.4), because a failed concurrent build leaves an INVALID
--   index no deployed environment can clear. The build holds a SHARE lock on risk.risk_assessment until this migration commits,
--   and every judged transfer, withdrawal and payment seals an assessment there, so on a table of any size the index is built
--   AHEAD, by an operator, CONCURRENTLY and by this name (OPERATIONS_RUNBOOK.md section 11.48), and IF NOT EXISTS makes this
--   migration a no-op over it.

CREATE INDEX IF NOT EXISTS risk_assessment_by_time
    ON risk.risk_assessment (assessed_at, id);
