-- The reporting role reads committed lending (P15-TSK-021; PHASE_15_PLAN.md C13; INV-ACC-14 at the privilege level;
-- THREAT_MODEL.md T7, T44).
--
-- WHAT CHANGES
--   Report generation reads through its own pool as finapp_reporting, a role that holds SELECT and nothing else. These
--   are the lending tables a report run reads, recounted from the code: the safeguarding return's committed lending
--   facts (app's LendingFundedAsOfCutoffReads) - a disbursement, its loan, the loan's agreement, offer and pinned terms.
--   lending.business_date(timestamptz, text) is called by the same statement: an SQL function that reads no table, whose
--   EXECUTE PUBLIC holds by default, so USAGE on the schema is all it needs. Not the schedule, repayments, accrual,
--   capital, applications or the party exposure view.
--
-- WHAT IT DOES NOT TOUCH
--   No table, column, function or trigger; finapp_app's grants unchanged; no ALTER DEFAULT PRIVILEGES. The role is a
--   cluster object (infra/postgres/initdb/00-roles.sql locally, the one-time bootstrap step in a deployed database,
--   P15-TSK-004) and must exist before this runs.

GRANT USAGE ON SCHEMA lending TO finapp_reporting;

GRANT SELECT ON lending.loan_disbursement TO finapp_reporting;
GRANT SELECT ON lending.loan TO finapp_reporting;
GRANT SELECT ON lending.loan_agreement TO finapp_reporting;
GRANT SELECT ON lending.loan_offer TO finapp_reporting;
GRANT SELECT ON lending.loan_terms TO finapp_reporting;
