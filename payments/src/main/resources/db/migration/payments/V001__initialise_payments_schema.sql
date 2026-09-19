-- Initialise the `payments` module's schema (P5-TSK-001).
--
-- Schema-per-module (ADR-0006): the `payments` schema is owned exclusively by the payments
-- module. No other module reads or writes tables here; cross-module access goes through this
-- module's published API, and there are **no foreign keys across module schemas** - a payment
-- references its owning customer, its wallet ledger account, its instrument token reference and
-- its posted journal entries by value, never by REFERENCES (the ADR-0029 rule, applied at the
-- ADR-0046 boundary).
--
-- This schema will hold the payment DOMAIN - the PaymentIntent, the PaymentAttempt with its
-- per-operation provider idempotency references (INV-PAY-04), the Refund, their append-only
-- lifecycle histories, and the verbatim provider evidence (INV-HIST-02) - and never a posting.
-- The ledger is the sole writer of journal entries (INV-LED-04); a capture commands its posting
-- through the ledger API in the outcome transaction ADR-0046 decides (ADR-0048: authorization
-- posts nothing - the ledger's first touch is capture), and what is recorded here is the payment
-- outcome that transaction committed.
--
-- The privilege floor matters before the first table does: PHASE_5_PLAN.md §8 commits
-- `payments.provider_evidence` to append-only grants, the attempt to insert-carries-outcome with
-- narrowed transition grants, and the refund's sum bound to an in-trigger check binding every
-- writer. Those grant shapes are only available because the objects are owned by the migrator -
-- a role that cannot bypass the grants it applies - and because each table's grants arrive with
-- the migration that creates it.
--
-- This migration creates no tables. The intent, attempt, refund, history and evidence tables
-- are P5-TSK-008.

COMMENT ON SCHEMA payments IS
    'Owned by the payments module. Money movement whose outcome a third party decides: PaymentIntent, PaymentAttempt, Refund, lifecycle histories, verbatim provider evidence. Commands postings through the ledger and writes none (INV-LED-04, ADR-0046, ADR-0048); no cross-schema foreign keys. See MODULE_ARCHITECTURE.md §4.';

-- Default-deny. PostgreSQL historically granted broad privileges on schemas to PUBLIC, and an
-- object created here must not become readable or writable by every role in the cluster by
-- default.
REVOKE ALL ON SCHEMA payments FROM PUBLIC;

-- USAGE only. Without it the application role cannot see the schema at all, whatever it later
-- holds on the tables in it; with it and nothing else, it can see the schema and touch nothing.
--
-- Deliberately NO `ALTER DEFAULT PRIVILEGES`. A default grant would hand every future table here
-- the same set, and the tables this schema will hold are exactly ones whose grants must be
-- narrowed per table - the evidence append-only, the attempt's transitions column-bounded. Each
-- table's grants are written by the migration that creates it, where a reviewer reads them
-- beside the table they apply to.
GRANT USAGE ON SCHEMA payments TO finapp_app;
