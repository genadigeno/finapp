-- P6-TSK-002: the audit trail's actor vocabulary learns MERCHANT.
--
-- V009's own comment says it in as many words: "Adding an actor type to a financial
-- platform's audit trail SHOULD be a deliberate act." This is that act, performed as a NEW
-- migration because V009 is applied history and an edited applied migration means the
-- database and the repository disagree (ADR-0011) - the ledger V011 and identity V015
-- ceremony, third instance in this phase.
--
-- WHY THE FAILURE WOULD OTHERWISE BE EXPENSIVE, restated from V009's comment because it is
-- exactly what this migration prevents: a merchant acting without this constraint widened
-- would hit a CHECK violation on the AUDIT WRITE, and under ADR-0010 that rolls back the
-- action it was meant to record. The audit trail would take down the operation.
--
-- The list below is what ActorType.sqlValueList() generates; AuditEnumMigrationTest
-- reconciles the LATEST definition of this constraint against the enum, so the two cannot
-- drift in either direction.
--
-- Existing rows all read one of the first four names, all in the new list, so the constraint
-- revalidates without touching a row.
ALTER TABLE platform.audit_record
    DROP CONSTRAINT audit_record_actor_type_known;

ALTER TABLE platform.audit_record
    ADD CONSTRAINT audit_record_actor_type_known
        CHECK (actor_type IN ('SYSTEM', 'CUSTOMER', 'EMPLOYEE', 'SERVICE', 'MERCHANT'));
