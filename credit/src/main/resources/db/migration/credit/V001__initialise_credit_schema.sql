-- Initialise the `credit` module's schema (P10-TSK-001).
--
-- Schema-per-module (ADR-0006): the `credit` schema is owned exclusively by the credit module. No
-- other module reads or writes tables here; cross-module access goes through this module's
-- published API, and there are **no foreign keys across module schemas** (the ADR-0029 rule).
--
-- This schema will hold credit decisioning: the credit profile, the collected credit records and
-- their encrypted evidence, the frozen decision snapshot and its hash, the assessment, the
-- versioned scorecard and policy (four-eyes, rules born with the version), the evaluation, the
-- immutable decision with its ordered reason codes (never updated or deleted by any role -
-- INV-CRD-02), the decision's consumption (written only by Phase 11) and the underwriting case
-- (ADR-0084...ADR-0089). It **moves no money**: nothing here is posted, settled or reconciled, and
-- a decision is never a loan.
--
-- The privilege floor matters before the first table does: PHASE_10_PLAN.md commits the decision
-- tables to INSERT-only grants with an every-writer trigger, the policy rules to immutability from
-- insert, the reason-code catalogue to SELECT-only. Those shapes are only available because the
-- objects are owned by the migrator - a role that cannot bypass the grants it applies - and
-- because each table's grants arrive with the migration that creates it.
--
-- This migration creates no tables. The reason-code catalogue is V002 (this task); the profile is
-- P10-TSK-004's.

COMMENT ON SCHEMA credit IS
    'Owned by the credit module. Credit decisioning: Credit Profile, Credit Record (encrypted evidence), Decision Snapshot (frozen, hashed), Assessment, Scorecard and Policy versions (four-eyes), Evaluation, Credit Decision (immutable, ordered reason codes - INV-CRD-02), Underwriting Case. Moves no money: nothing here is posted, settled or reconciled. No cross-schema foreign keys. See MODULE_ARCHITECTURE.md credit.';

-- Default-deny. PostgreSQL historically granted broad privileges on schemas to PUBLIC, and an
-- object created here must not become readable or writable by every role in the cluster by
-- default.
REVOKE ALL ON SCHEMA credit FROM PUBLIC;

-- USAGE only. Without it the application role cannot see the schema at all, whatever it later
-- holds on the tables in it; with it and nothing else, it can see the schema and touch nothing.
--
-- Deliberately NO `ALTER DEFAULT PRIVILEGES`. A default grant would hand every future table here
-- the same set, and the tables this schema will hold are exactly ones whose grants must be
-- narrowed per table. Each table's grants are written by the migration that creates it, where a
-- reviewer reads them beside the table they apply to.
GRANT USAGE ON SCHEMA credit TO finapp_app;
