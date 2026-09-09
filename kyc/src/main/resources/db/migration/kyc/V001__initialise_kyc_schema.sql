-- Initialise the `kyc` module's schema.
--
-- Schema-per-module (ADR-0006): the `kyc` schema is owned exclusively by the kyc module. No
-- other module reads or writes tables here; cross-module access goes through this module's
-- published API, and there are **no foreign keys across module schemas** — `kyc` references a
-- Customer by identifier only (ADR-0029's rule, inherited by every later module pair).
--
-- The privilege floor matters more here than anywhere Phase 2 touches: this schema will hold
-- immutable decisions (INV-KYC-02), append-only evidence retained verbatim (INV-HIST-02,
-- INV-KYC-01) and encrypted document content whose every read is audited (INV-KYC-06) — all
-- enforced at DB-PRIVILEGE, which is only a real rank because the migrator owns the objects and
-- the application role receives exactly the grants each table's migration states.
--
-- This migration creates no tables. The KycCase table is P2-TSK-005.

COMMENT ON SCHEMA kyc IS
    'Owned by the kyc module. KYC/KYB cases, checks, screening evidence, document references and the verification decision - the ONE authority for "may this party transact?" (INV-KYC-05). RESTRICTED-PII ceiling. No cross-schema foreign keys. See docs/architecture/MODULE_ARCHITECTURE.md.';

-- Default-deny. PostgreSQL historically granted broad privileges on schemas to PUBLIC, and an
-- object created here must not become readable or writable by every role in the cluster by
-- default. Access is granted explicitly, per role, per object — and each table's grants arrive
-- with the migration that creates it, never by ALTER DEFAULT PRIVILEGES, so a table that must
-- NOT receive the usual set cannot be given it by a default nobody revisited.
REVOKE ALL ON SCHEMA kyc FROM PUBLIC;

-- USAGE only. Without it the application role cannot see the schema at all, whatever it later
-- holds on the tables in it; with it and nothing else, it can see the schema and touch nothing.
GRANT USAGE ON SCHEMA kyc TO finapp_app;
