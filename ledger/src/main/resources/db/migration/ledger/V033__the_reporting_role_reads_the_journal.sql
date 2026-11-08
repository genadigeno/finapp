-- The reporting role reads the journal (P15-TSK-021; PHASE_15_PLAN.md C13; INV-ACC-14 at the privilege level;
-- THREAT_MODEL.md T7, T44).
--
-- WHAT CHANGES
--   Report generation reads through its own pool as finapp_reporting, a role that holds SELECT and nothing else. These
--   are the ledger tables a report run reads, recounted from the code: the journal's entries and lines and the chart
--   (the trial balance and every definition built on it), the counterparty a position names (the activity, settlement
--   and statement definitions, the safeguarding return) and the adjustment proposal a prior-period correction's origin
--   names (a statement's notes). Not the balance projection, the holds, the posting cutoff or the rebuild records.
--
-- WHAT IT DOES NOT TOUCH
--   No table, column, function or trigger; finapp_app's grants unchanged; no ALTER DEFAULT PRIVILEGES. The role is a
--   cluster object (infra/postgres/initdb/00-roles.sql locally, the one-time bootstrap step in a deployed database,
--   P15-TSK-004) and must exist before this runs.

GRANT USAGE ON SCHEMA ledger TO finapp_reporting;

GRANT SELECT ON ledger.journal_entry TO finapp_reporting;
GRANT SELECT ON ledger.journal_line TO finapp_reporting;
GRANT SELECT ON ledger.ledger_account TO finapp_reporting;
GRANT SELECT ON ledger.counterparty TO finapp_reporting;
GRANT SELECT ON ledger.adjustment_proposal TO finapp_reporting;
