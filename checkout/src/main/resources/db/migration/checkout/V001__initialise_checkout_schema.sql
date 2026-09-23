-- Initialise the `checkout` module's schema (P6-TSK-001).
--
-- Schema-per-module (ADR-0006): the `checkout` schema is owned exclusively by the checkout
-- module. No other module reads or writes tables here; cross-module access goes through this
-- module's published API, and there are **no foreign keys across module schemas** - a session
-- references its merchant, its payment intent and its pinned fee schedule version by value,
-- never by REFERENCES (the ADR-0029 rule, applied at the ADR-0053 boundary).
--
-- This schema will hold the purchase experience and the commercial fact it produces - the
-- CheckoutSession with its six-state machine (expiry a sweeper-earned transition, never a query
-- filter pretending to be a state; the late-completion edge that keeps landed money from being
-- orphaned by a clock, INV-MER-06) and the Order (one per paid session, append-only: an order
-- is a fact) - and never a payment and never a posting. The payment is Phase 5's record,
-- referenced by identifier; the money is the ledger's.
--
-- The privilege floor matters before the first table does: PHASE_6_PLAN.md §8 commits the
-- session's customer-facing token to hashed-at-rest storage under frozen columns, the session's
-- transitions to every-writer triggers, and the order to append-only grants. Those grant shapes
-- are only available because the objects are owned by the migrator - a role that cannot bypass
-- the grants it applies - and because each table's grants arrive with the migration that
-- creates it.
--
-- This migration creates no tables. The session and order tables are P6-TSK-006.

COMMENT ON SCHEMA checkout IS
    'Owned by the checkout module. The customer-facing purchase experience: CheckoutSession (expiring offer to pay, ADR-0053) and Order (the commercial fact a paid session produces). References merchants, payment intents and fee schedule versions by value; no cross-schema foreign keys; never a posting. See MODULE_ARCHITECTURE.md §4.';

-- Default-deny. PostgreSQL historically granted broad privileges on schemas to PUBLIC, and an
-- object created here must not become readable or writable by every role in the cluster by
-- default.
REVOKE ALL ON SCHEMA checkout FROM PUBLIC;

-- USAGE only. Without it the application role cannot see the schema at all, whatever it later
-- holds on the tables in it; with it and nothing else, it can see the schema and touch nothing.
--
-- Deliberately NO `ALTER DEFAULT PRIVILEGES`. A default grant would hand every future table here
-- the same set, and the tables this schema will hold are exactly ones whose grants must be
-- narrowed per table - the order append-only, the session's transitions column-bounded. Each
-- table's grants are written by the migration that creates it, where a reviewer reads them
-- beside the table they apply to.
GRANT USAGE ON SCHEMA checkout TO finapp_app;
