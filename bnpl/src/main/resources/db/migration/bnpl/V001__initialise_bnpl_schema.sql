-- Initialise the `bnpl` module's schema (P12-TSK-002).
--
-- Schema-per-module (ADR-0006): the `bnpl` schema is owned exclusively by the bnpl module. No other module reads or
-- writes tables here; cross-module access goes through this module's published API, and there are **no foreign keys
-- across module schemas** (the ADR-0029 rule) - a BNPL purchase names its checkout session, its merchant, its credit
-- decision, its party and its ledger accounts by identifier, never by constraint.
--
-- This schema will hold the BNPL agreement and its servicing facts for pay-in-4 and pay-in-N (ADR-0101): the
-- application, the four-eyes terms versions, the expiring offer, the immutable versioned agreement and its acceptance
-- evidence, the BNPL purchase and its merchant financing record, the customer repayment obligation - identity, product,
-- status and permits, **no amount, rate or balance column** - and every born-once servicing fact: schedules and BNPL
-- instalments, billings, pay-in-N's accruals, repayments and their allocations, collection attempts, conditions, late
-- fees, payoffs, refunds and their adjustments, disputes, waivers and repayment reversals. **Never the money**: every
-- principal, interest, fee and credit-balance figure is a balance of a per-obligation ledger account, COMMANDED through
-- the ledger and never written here (INV-LED-04, INV-BNPL-01); the merchant's payable is the ledger's position and its
-- payout merchant's.
--
-- The privilege floor matters before the first table does: PHASE_12_PLAN.md section 8 commits every table carrying
-- money to INSERT-only for the application role - SELECT, INSERT on the fact tables, a conditional UPDATE of status and
-- permit columns only on the machine tables, never DELETE, never an UPDATE of a money column - and
-- BnplSchemaHasNoMutableMoneyTest holds every `*_minor` column to an INSERT-only table from the first one. Those shapes
-- are only available because the objects are owned by the migrator - a role that cannot bypass the grants it applies -
-- and because each table's grants arrive with the migration that creates it.
--
-- This migration creates no tables. The terms version is P12-TSK-004, the application P12-TSK-010, the offer
-- P12-TSK-011, the purchase, its financing, the agreement and the obligation P12-TSK-013.

COMMENT ON SCHEMA bnpl IS
    'Owned by the bnpl module. The BNPL agreement and its servicing facts for pay-in-4 and pay-in-N: application, terms version (four-eyes), offer, agreement (immutable, versioned) and acceptance evidence, the BNPL purchase and its merchant financing record, the customer repayment obligation (identity, product, status, permits - no amount, rate or balance column), refunds, disputes and born-once servicing facts. Never the money: every figure is a ledger balance, posted through the ledger and never written here (INV-LED-04, INV-BNPL-01); every table carrying money is INSERT-only (BnplSchemaHasNoMutableMoneyTest); no cross-schema foreign keys. See MODULE_ARCHITECTURE.md bnpl.';

-- Default-deny. PostgreSQL historically granted broad privileges on schemas to PUBLIC, and an object created here must
-- not become readable or writable by every role in the cluster by default.
REVOKE ALL ON SCHEMA bnpl FROM PUBLIC;

-- USAGE only. Without it the application role cannot see the schema at all, whatever it later holds on the tables in
-- it; with it and nothing else, it can see the schema and touch nothing.
--
-- Deliberately NO `ALTER DEFAULT PRIVILEGES`. A default grant would hand every future table here the same set - and a
-- default UPDATE or DELETE would make every future money table mutable the moment it was created, which is exactly what
-- INV-BNPL-01 forbids. Each table's grants are written by the migration that creates it, where a reviewer reads them
-- beside the table they apply to.
GRANT USAGE ON SCHEMA bnpl TO finapp_app;
