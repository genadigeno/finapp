-- Initialise the `risk` module's schema (P13-TSK-001).
--
-- Schema-per-module (ADR-0006): the `risk` schema is owned exclusively by the risk module. No other module reads or
-- writes tables here; cross-module access goes through this module's published API, and there are **no foreign keys
-- across module schemas** (the ADR-0029 rule) - an assessment names its judged command, its subjects and its policy
-- version by identifier, a monitored transaction names its journal entry by identifier, never by constraint.
--
-- This schema will hold the judgement, the evidence and the case, and none of the money (ADR-0107 decision 5,
-- INV-RSK-02): the versioned risk policy and its rules as data (V002), the born-once signal (V003), the monitored
-- transaction read from the ledger's journal and its watermark (V004), the durable velocity windows (V005), the
-- customer risk profile and its versions (V006), the sealed assessment with its inputs, rule hits and reason codes, and
-- the decision override (V007), the born-once alert (V008), the case, its investigator acts and its events (V009), the
-- downstream action's record (V010), the regulatory report (V011) and the screening list version with its rescreening
-- permits (V012) - numbered in the order the waves of PHASE_13_PLAN.md section 17 land them. **Never a balance**:
-- `amount_minor` + `currency` appear only as the judged amount on INSERT-only rows and as the window sums on the
-- velocity rows, whose one UPDATE is the bounded one-statement upsert (PHASE_13_PLAN.md section 8).
--
-- The privilege floor matters before the first table does: PHASE_13_PLAN.md section 8 commits every fact table to
-- SELECT, INSERT for the application role, a conditional UPDATE of status, permit, revision and decision columns only on
-- the machine tables, the velocity window's bounded upsert, and never DELETE for any role. Those shapes are only
-- available because the objects are owned by the migrator - a role that cannot bypass the grants it applies - and
-- because each table's grants arrive with the migration that creates it.
--
-- This migration creates no tables. The policy version is P13-TSK-002's.

COMMENT ON SCHEMA risk IS
    'Owned by the risk module. The judgement, the evidence and the case for risk, fraud, AML monitoring and case management: policy versions (four-eyes) and their rules as data, signals, monitored transactions and the watermark, velocity windows, customer risk profiles, assessments with their decisions and overrides, alerts, cases and investigator acts, downstream action records, screening list versions and regulatory reports. Never the money: nothing here is posted, held or released, and no table holds a balance (INV-RSK-02); fact tables are INSERT-only, no role deletes; no cross-schema foreign keys. See MODULE_ARCHITECTURE.md risk.';

-- Default-deny. PostgreSQL historically granted broad privileges on schemas to PUBLIC, and an object created here must
-- not become readable or writable by every role in the cluster by default.
REVOKE ALL ON SCHEMA risk FROM PUBLIC;

-- USAGE only. Without it the application role cannot see the schema at all, whatever it later holds on the tables in
-- it; with it and nothing else, it can see the schema and touch nothing.
--
-- Deliberately NO `ALTER DEFAULT PRIVILEGES`. A default grant would hand every future table here the same set - and a
-- default UPDATE or DELETE would make every sealed assessment, born-once alert and immutable policy version editable the
-- moment its table was created, which is exactly what INV-RSK-01, INV-RSK-05 and INV-RSK-06 forbid. Each table's grants
-- are written by the migration that creates it, where a reviewer reads them beside the table they apply to.
GRANT USAGE ON SCHEMA risk TO finapp_app;
