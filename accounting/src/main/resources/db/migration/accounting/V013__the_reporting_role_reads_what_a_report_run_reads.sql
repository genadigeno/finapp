-- The reporting role reads what a report run reads (P15-TSK-021; PHASE_15_PLAN.md C13; INV-ACC-14 at the privilege
-- level; THREAT_MODEL.md T7, T44).
--
-- WHAT CHANGES
--   Report generation (app's ReportRunGenerator: a run's generation and a FINAL run's regeneration) reads through its
--   own pool as finapp_reporting, a role that holds SELECT and nothing else. These are the accounting tables that path
--   reads, recounted from the code: the run's period (and the previous one, for the bridge), the run itself (the
--   regeneration), the pinned GL mapping, the pinned valuation rate set, the prior-period adjustments a statement's
--   notes name and a FINAL statement's close record. Nothing else in this schema is granted.
--
-- WHAT IT DOES NOT TOUCH
--   No table, column, function or trigger. finapp_app's grants are unchanged. No ALTER DEFAULT PRIVILEGES: a table a
--   later migration creates is not readable by the reporting role until a migration says so. The role itself is a
--   cluster object - infra/postgres/initdb/00-roles.sql locally, the one-time bootstrap step in a deployed database
--   (P15-TSK-004) - and must exist before this runs; finapp_migrator is NOCREATEROLE and creates no role.

GRANT USAGE ON SCHEMA accounting TO finapp_reporting;

GRANT SELECT ON accounting.accounting_period TO finapp_reporting;
GRANT SELECT ON accounting.report_run TO finapp_reporting;
GRANT SELECT ON accounting.gl_mapping_version TO finapp_reporting;
GRANT SELECT ON accounting.gl_account TO finapp_reporting;
GRANT SELECT ON accounting.gl_mapping_rule TO finapp_reporting;
GRANT SELECT ON accounting.valuation_rate_set TO finapp_reporting;
GRANT SELECT ON accounting.valuation_rate TO finapp_reporting;
GRANT SELECT ON accounting.prior_period_adjustment TO finapp_reporting;
GRANT SELECT ON accounting.period_close TO finapp_reporting;
GRANT SELECT ON accounting.period_close_check TO finapp_reporting;
