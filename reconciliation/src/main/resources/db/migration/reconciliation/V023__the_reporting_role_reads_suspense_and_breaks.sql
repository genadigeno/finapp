-- The reporting role reads suspense and breaks (P15-TSK-021; PHASE_15_PLAN.md C13; INV-ACC-14 at the privilege level;
-- THREAT_MODEL.md T7, T44).
--
-- WHAT CHANGES
--   Report generation reads through its own pool as finapp_reporting, a role that holds SELECT and nothing else. These
--   are the reconciliation tables a report run reads, recounted from the code: the suspense items born by a cutoff, their
--   releases and the breaks they belong to (the suspense definition, a statement's notes). Not the matcher's runs,
--   decisions, expectations, resolutions or evidence.
--
-- WHAT IT DOES NOT TOUCH
--   No table, column, function or trigger; finapp_app's grants unchanged; no ALTER DEFAULT PRIVILEGES. The role is a
--   cluster object (infra/postgres/initdb/00-roles.sql locally, the one-time bootstrap step in a deployed database,
--   P15-TSK-004) and must exist before this runs.

GRANT USAGE ON SCHEMA reconciliation TO finapp_reporting;

GRANT SELECT ON reconciliation.suspense_item TO finapp_reporting;
GRANT SELECT ON reconciliation.suspense_release TO finapp_reporting;
GRANT SELECT ON reconciliation.break TO finapp_reporting;
