-- Initialise the platform module's schema.
--
-- Schema-per-module (ADR-0006): the `platform` schema is owned exclusively by the platform
-- module. No other module reads or writes tables here; cross-module access goes through
-- platform's published API. There are no foreign keys across module schemas.
--
-- This migration creates no tables. The idempotency record, outbox, inbox and audit tables
-- are P0-TSK-015, -019, -021 and -022 respectively.

COMMENT ON SCHEMA platform IS
    'Owned by the platform module. Correctness primitives only: idempotency, outbox, inbox, '
    'audit. No business tables. No cross-schema foreign keys. See '
    'docs/architecture/MODULE_ARCHITECTURE.md.';

-- Default-deny. PostgreSQL historically granted broad privileges on schemas to PUBLIC, and
-- an object created here must not become readable or writable by every role in the cluster
-- by default. Access is granted explicitly, per role, per object.
--
-- This matters more here than in most schemas: INV-LED-03, INV-HIST-01 and INV-HIST-03 are
-- enforced at the DB-PRIVILEGE level, which is only meaningful if the baseline is deny.
REVOKE ALL ON SCHEMA platform FROM PUBLIC;
