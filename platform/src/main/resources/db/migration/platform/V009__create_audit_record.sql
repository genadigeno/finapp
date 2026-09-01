-- The audit trail: what happened, who did it, and what came of it.
--
-- INVARIANTS THIS MIGRATION ENFORCES
--   INV-HIST-03  Audit records are append-only. The application role holds INSERT and SELECT
--                and nothing else; UPDATE and DELETE are denied at the privilege level, which
--                is the only level at which this can be said honestly. An audit trail that can
--                be edited is worth less than none, because it invites false confidence.
--   INV-AUD-01   Every financial and administrative action of consequence produces a record
--                carrying actor, time, operation, target, reason where applicable, correlation
--                and outcome. All of those are NOT NULL columns here except reason, which is
--                conditional and is constrained below.
--   INV-AUD-02   No credential, token, PAN or unnecessary PII is stored. This table records
--                THAT an action occurred and by whom - never the payload it carried.
--
-- WHY A TABLE AND NOT A LOG LINE (ADR-0010)
--   Application logs fail as an audit trail structurally, not incidentally: they are retained
--   for weeks rather than years, sampled and dropped under load, free to change format, not
--   queryable as records, not transactional with the action - and, decisively, mutable by the
--   process that writes them. Every one of those is a property this table must not have.
--
-- MUTABILITY: NONE
--   This is the first table in the platform where the application's LACK of a privilege is the
--   entire point. The three tables before it are all legitimately mutable - an idempotency
--   claim advances, an outbox row is marked published - and each said so explicitly. This one
--   does not advance. A correction is a NEW record describing the correction, which is the same
--   rule financial history follows (INV-HIST-01) and for the same reason: the error and its
--   correction must both remain visible.
--
--   Grants are at the foot of this file. There is no UPDATE and no DELETE, for anyone but the
--   owner - and the owner is the migrator, which the application never connects as.

CREATE TABLE platform.audit_record
(
    -- A UUIDv7 (ADR-0013), so the table is written in roughly ascending order. An audit trail
    -- is pure insert traffic with time-ordered reads, which is the access pattern that ordering
    -- helps most.
    audit_id        UUID        NOT NULL,

    -- WHO. Both parts are required: an identifier without its type is ambiguous the moment a
    -- customer id and an employee id can look alike, and "who approved this" is the first
    -- question asked about any privileged action.
    --
    -- Until Phase 1 supplies real identity the actor is a system actor. The schema does not
    -- change when identity arrives - that is why actor_type exists now rather than later
    -- (ADR-0010).
    actor_id        TEXT        NOT NULL,
    actor_type      TEXT        NOT NULL,

    -- WHEN. Application-supplied from one injected Clock (P0-TSK-013), and deliberately no
    -- DEFAULT now(): the time an action occurred is a business fact belonging to the action,
    -- not the time its row happened to reach the database.
    occurred_at     TIMESTAMPTZ NOT NULL,

    -- WHAT. A stable action identifier from the auditable-action registry (P0-TSK-023), not a
    -- free-text description. Phase 15 must verify audit completeness against that registry,
    -- which is only possible if this column holds values the registry can enumerate.
    operation       TEXT        NOT NULL,

    -- WHAT IT WAS DONE TO. Type as well as id, for the same reason as the actor: an identifier
    -- alone does not say what it identifies, and audit queries are written across aggregates.
    target_type     TEXT        NOT NULL,
    target_id       TEXT        NOT NULL,

    -- WHY. Nullable, because most actions do not need one - but see the constraint below: for
    -- the actions that do, absence is not permitted, and the domain decides which those are.
    reason          TEXT,

    -- WHAT CAME OF IT. A refused action is more interesting to an auditor than a successful
    -- one, so a record is written for both. An audit trail containing only successes describes
    -- a system nobody attacked.
    outcome         TEXT        NOT NULL,

    -- WHICH FLOW. The join from this record to the request, the postings and the events that
    -- belong to the same operation. Without it an audit record is an isolated assertion.
    correlation_id  TEXT        NOT NULL,

    -- WHAT CHANGED, where materially useful. A short human-readable summary, never a payload
    -- dump: INV-AUD-02 forbids credentials, tokens, PANs and unnecessary PII, and a
    -- before/after of an arbitrary object is exactly how those arrive somewhere they should not
    -- be. Bounded, so "we will just put the whole request in" is not available.
    change_summary  TEXT,

    CONSTRAINT audit_record_pk PRIMARY KEY (audit_id),

    CONSTRAINT audit_record_actor_id_bounded
        CHECK (length(actor_id) BETWEEN 1 AND 200),
    CONSTRAINT audit_record_actor_type_bounded
        CHECK (length(actor_type) BETWEEN 1 AND 50),
    CONSTRAINT audit_record_operation_bounded
        CHECK (length(operation) BETWEEN 1 AND 200),
    CONSTRAINT audit_record_target_type_bounded
        CHECK (length(target_type) BETWEEN 1 AND 200),
    CONSTRAINT audit_record_target_id_bounded
        CHECK (length(target_id) BETWEEN 1 AND 200),
    CONSTRAINT audit_record_outcome_bounded
        CHECK (length(outcome) BETWEEN 1 AND 50),
    CONSTRAINT audit_record_correlation_bounded
        CHECK (length(correlation_id) BETWEEN 1 AND 128),

    -- Nullable, but never blank. A reason column full of empty strings satisfies a NOT NULL and
    -- answers nothing; the distinction between "no reason was required" and "a reason was
    -- required and is empty" must survive.
    CONSTRAINT audit_record_reason_bounded
        CHECK (reason IS NULL OR length(reason) BETWEEN 1 AND 1000),
    CONSTRAINT audit_record_change_summary_bounded
        CHECK (change_summary IS NULL OR length(change_summary) BETWEEN 1 AND 4000),

    -- The enumerated columns are constrained, not merely bounded, and the literal lists are
    -- generated from ActorType and AuditOutcome by their sqlValueList() methods - the technique
    -- V002 established for IdempotencyState, guarded by a hermetic test so the enum and the
    -- schema cannot drift.
    --
    -- Constraining these is worth a migration every time the sets grow, which is the objection
    -- to doing it. An unconstrained text column accumulates near-duplicates - 'EMPLOYEE',
    -- 'Employee', 'employe' - and each one silently splits an audit query's results without
    -- failing anything. In a trail that must be interpretable years later and defensible to
    -- someone who did not write it, that is not a tidiness problem. Adding an actor type to a
    -- financial platform's audit trail SHOULD be a deliberate act.
    CONSTRAINT audit_record_actor_type_known
        CHECK (actor_type IN ('SYSTEM', 'CUSTOMER', 'EMPLOYEE', 'SERVICE')),
    CONSTRAINT audit_record_outcome_known
        CHECK (outcome IN ('SUCCEEDED', 'FAILED', 'DENIED'))
);

COMMENT ON TABLE platform.audit_record IS
    'Append-only audit trail (INV-HIST-03, ADR-0010). The application role holds INSERT and '
    'SELECT only. A correction is a new record, never an edit.';

COMMENT ON COLUMN platform.audit_record.operation IS
    'A stable identifier from the auditable-action registry (P0-TSK-023), not free text: '
    'Phase 15 verifies audit completeness against that registry.';

COMMENT ON COLUMN platform.audit_record.change_summary IS
    'A short summary, never a payload dump. INV-AUD-02 forbids credentials, tokens, PANs and '
    'unnecessary PII, and a before/after of an arbitrary object is how those escape.';

-- Reading the trail. Two access patterns exist and neither is served by the primary key:
-- "what happened to this thing" and "what did this actor do". Unlike the indexes V002 and V007
-- deliberately omit, these are not guesses - they are the two questions an audit trail exists
-- to answer, and an audit query that cannot be run is an audit trail that cannot be used.
CREATE INDEX audit_record_by_target ON platform.audit_record (target_type, target_id, audit_id);
CREATE INDEX audit_record_by_actor ON platform.audit_record (actor_id, audit_id);

-- INV-HIST-03, AND THE REASON IT IS EXPRESSED HERE RATHER THAN IN CODE.
--
-- INSERT and SELECT. No UPDATE. No DELETE. Not because the application has no reason to use
-- them, but because it must be unable to: the next writer is a job, an operator tool, or a psql
-- session, and none of those read our Java. A guarantee held only in application code is a
-- guarantee held until the first caller who did not know about it.
--
-- This is also why the roles are NOSUPERUSER (infra/postgres/initdb/00-roles.sql). A superuser
-- ignores every permission check, so these two lines would be decorative if the application
-- connected as one -- and, worse, the test asserting they work would pass anyway.
GRANT SELECT, INSERT ON platform.audit_record TO finapp_app;
