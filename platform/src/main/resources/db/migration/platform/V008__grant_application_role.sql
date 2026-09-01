-- Grants the application role what each existing platform table genuinely requires.
--
-- V002, V005 and V007 each stated the grants their table needed and deferred applying them to
-- P0-TSK-022, because no application role existed yet. This is that task paying those debts.
--
-- WHY GRANTS ARE HERE AND ROLES ARE NOT
--   A role is a cluster object; a grant is a privilege on a schema object. The role is created
--   by infrastructure (infra/postgres/initdb/00-roles.sql, and Terraform or a managed-database
--   operator in a deployed environment); the privileges on our tables belong with the tables,
--   so a table's privileges arrive with it rather than being applied later by hand
--   (DATA_MIGRATIONS.md §6).
--
-- THE PRINCIPLE
--   Each table gets the DML it genuinely requires and nothing more. "Nothing more" is the part
--   that does work: INV-LED-03, INV-HIST-01 and INV-HIST-03 are all statements about a
--   privilege the application must NOT hold, and a privilege granted "just in case" silently
--   converts one of them from enforced to intended.
--
-- NO DEFAULT PRIVILEGES ARE SET, DELIBERATELY
--   ALTER DEFAULT PRIVILEGES would grant the application role something on every future table
--   automatically. That is the opposite of what this model needs: a new table's privileges must
--   be a decision recorded in the migration that creates it, and the audit table in V009 is
--   precisely a table that must NOT receive the usual set. Defaults would have granted it
--   UPDATE and DELETE before anyone noticed.

-- Without USAGE the role cannot see the schema at all, whatever it holds on the tables in it.
GRANT USAGE ON SCHEMA platform TO finapp_app;

-- Idempotency records are claimed, then updated with an outcome, then swept when they expire.
-- All four verbs are genuinely required; this table is a concurrency-control artefact, not
-- financial history, and V002 sets out why it is legitimately mutable.
GRANT SELECT, INSERT, UPDATE, DELETE ON platform.idempotency_record TO finapp_app;

-- Outbox rows are inserted by the writer, updated once by the relay to record publication or a
-- failed attempt, and deleted by the retention sweep. UPDATE is required and is not a weakening:
-- an outbox row records that something must be announced, never what is true (INV-EVT-02).
GRANT SELECT, INSERT, UPDATE, DELETE ON platform.outbox_event TO finapp_app;

-- Inbox records get no UPDATE. There is no state to advance: the row is written with the side
-- effect and is never modified afterwards (V007). DELETE is for the retention sweep only.
--
-- This is the narrow grant the table's own comment promised, and it is worth noticing that the
-- narrowness is free here - nothing wanted UPDATE - which is exactly when least privilege is
-- easiest to establish and hardest to argue against later.
GRANT SELECT, INSERT, DELETE ON platform.inbox_message TO finapp_app;
