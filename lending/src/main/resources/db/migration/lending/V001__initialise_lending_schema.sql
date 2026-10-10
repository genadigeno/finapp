-- Initialise the `lending` module's schema (P11-TSK-002).
--
-- Schema-per-module (ADR-0006): the `lending` schema is owned exclusively by the lending module. No other
-- module reads or writes tables here; cross-module access goes through this module's published
-- API, and there are **no foreign keys across module schemas** (the ADR-0029 rule) - a loan names its
-- credit decision, its party and its ledger accounts by identifier, never by constraint.
--
-- This schema will hold the loan contract and its servicing facts for the PERSONAL_LOAN and the
-- revolving CREDIT_LINE (ADR-0090): the application, the four-eyes terms versions, the expiring
-- offer, the immutable versioned agreement and its acceptance evidence, the loan account of either
-- kind - identity, kind, status and permits, **no amount, rate or balance column** - and every
-- born-once servicing fact: schedules and instalments, billings, statements, draws, accruals, fee
-- assessments, repayments and their allocations, collection attempts, conditions, payoffs,
-- amendments and the lending-capital contributions. **Never the money**: every principal, interest,
-- fee and credit-balance figure is a balance of a per-loan ledger account, COMMANDED through the
-- ledger and never written here (INV-LED-04, INV-LND-01).
--
-- The privilege floor matters before the first table does: PHASE_11_PLAN.md section 8 commits every
-- table carrying money to INSERT-only for the application role - SELECT, INSERT on the fact tables, a
-- conditional UPDATE of status and permit columns only on the machine tables, never DELETE, never an
-- UPDATE of a money column - and LendingSchemaHasNoMutableMoneyTest holds every `*_minor` column to
-- an INSERT-only table from the first one. Those shapes are only available because the objects are
-- owned by the migrator - a role that cannot bypass the grants it applies - and because each table's
-- grants arrive with the migration that creates it.
--
-- This migration creates no tables. The capital contribution is P11-TSK-004, the terms version
-- P11-TSK-005, the application P11-TSK-010, the offer P11-TSK-011, the loan and its agreement
-- P11-TSK-012.

COMMENT ON SCHEMA lending IS
    'Owned by the lending module. The loan contract and its servicing facts for PERSONAL_LOAN and the revolving CREDIT_LINE: application, terms version (four-eyes), offer, agreement (immutable, versioned) and acceptance evidence, the loan account (identity, kind, status, permits - no amount, rate or balance column), and born-once servicing facts. Never the money: every figure is a ledger balance, posted through the ledger and never written here (INV-LED-04, INV-LND-01); every table carrying money is INSERT-only (LendingSchemaHasNoMutableMoneyTest); no cross-schema foreign keys. See MODULE_ARCHITECTURE.md lending.';

-- Default-deny. PostgreSQL historically granted broad privileges on schemas to PUBLIC, and an
-- object created here must not become readable or writable by every role in the cluster by
-- default.
REVOKE ALL ON SCHEMA lending FROM PUBLIC;

-- USAGE only. Without it the application role cannot see the schema at all, whatever it later
-- holds on the tables in it; with it and nothing else, it can see the schema and touch nothing.
--
-- Deliberately NO `ALTER DEFAULT PRIVILEGES`. A default grant would hand every future table here
-- the same set - and a default UPDATE or DELETE would make every future money table mutable the
-- moment it was created, which is exactly what INV-LND-01 forbids. Each table's grants are written
-- by the migration that creates it, where a reviewer reads them beside the table they apply to.
GRANT USAGE ON SCHEMA lending TO finapp_app;
