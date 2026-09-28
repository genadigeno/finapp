-- Initialise the `reconciliation` module's schema (P8-TSK-001).
--
-- Schema-per-module (ADR-0006): the `reconciliation` schema is owned exclusively by the
-- reconciliation module. No other module reads or writes tables here; cross-module access goes
-- through this module's published API, and there are **no foreign keys across module schemas** -
-- an expectation names the completion that opened it by identifier and posting key, and an
-- external item its settlement line by identifier, never by REFERENCES (the ADR-0029 rule).
--
-- This schema will hold what the platform EXPECTED to happen and every disagreement with the
-- evidence: the SettlementExpectation (one per externally settling completion, opened in the
-- completing transaction - INV-SET-02, ADR-0067), the ReconciliationBatch and ExternalItem, the
-- MatchDecision with its pinned rule set and snapshot (deterministic and explainable -
-- INV-REC-04, ADR-0068), the Break (a record with a lifecycle, never deleted - INV-REC-01,
-- ADR-0069), the SuspenseItem (each owned by exactly one break - INV-REC-09, ADR-0070), the
-- Resolution (four-eyes wherever value is at issue - INV-REC-03, ADR-0071) and the versioned
-- RuleSet - and **never the evidence itself**: files, batches and lines are `settlement`'s
-- (ADR-0064), and no table here will ever hold a counterparty's bytes. A resolution's
-- compensating entry is COMMANDED through the ledger's adjustment machinery and never written
-- here (INV-LED-04).
--
-- The privilege floor matters before the first table does: PHASE_8_PLAN.md §8 commits the break
-- tables to no-DELETE grants with a refusing trigger beneath (the break-immutability exit
-- criterion), match decisions, candidates and allocations to no UPDATE or DELETE at all, the
-- resolution machine to its generated CHECKs and every-writer transition trigger, and severity
-- to forward-only by trigger. Those grant and constraint shapes are only available because the
-- objects are owned by the migrator - a role that cannot bypass the grants it applies - and
-- because each table's grants arrive with the migration that creates it.
--
-- This migration creates no tables. The expectation register is P8-TSK-004, breaks and
-- suspense P8-TSK-010, the matcher P8-TSK-011, resolutions P8-TSK-012 and P8-TSK-015.

COMMENT ON SCHEMA reconciliation IS
    'Owned by the reconciliation module. The platform''s expectations and every disagreement with the evidence: SettlementExpectation (INV-SET-02), ReconciliationBatch, ExternalItem, MatchDecision (pinned rule set - INV-REC-04), Break (never deleted - INV-REC-01), SuspenseItem (owned by exactly one break - INV-REC-09), Resolution (four-eyes - INV-REC-03), RuleSet. Evidence is settlement''s (ADR-0064); postings are commanded through the ledger and never written (INV-LED-04); no cross-schema foreign keys. See MODULE_ARCHITECTURE.md M8.';

-- Default-deny. PostgreSQL historically granted broad privileges on schemas to PUBLIC, and an
-- object created here must not become readable or writable by every role in the cluster by
-- default.
REVOKE ALL ON SCHEMA reconciliation FROM PUBLIC;

-- USAGE only. Without it the application role cannot see the schema at all, whatever it later
-- holds on the tables in it; with it and nothing else, it can see the schema and touch nothing.
--
-- Deliberately NO `ALTER DEFAULT PRIVILEGES`. A default grant would hand every future table here
-- the same set, and the tables this schema will hold are exactly ones whose grants must be
-- narrowed per table - breaks undeletable by anyone, decisions and allocations immutable once
-- written, severity forward-only. Each table's grants are written by the migration that creates
-- it, where a reviewer reads them beside the table they apply to.
GRANT USAGE ON SCHEMA reconciliation TO finapp_app;
