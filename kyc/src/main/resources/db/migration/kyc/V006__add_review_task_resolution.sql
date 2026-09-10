-- Review-task resolution: who, when and why a person answered what a check raised
-- (P2-TSK-012, INV-KYC-04). The columns V005 deliberately withheld arrive with the capability
-- that populates them - the grant-arrives-with-the-capability rule (P1-TSK-030's V004
-- precedent), which is why the UPDATE grant below is this migration's and not V005's.
--
-- THE RESOLUTION IS PERMANENT EVIDENCE (INV-KYC-02)
--   The decision P2-TSK-013 records will rest on what the reviewer concluded, so a resolution
--   that could be rewritten afterwards would be a decision resting on sand. RESOLVED rows are
--   frozen entirely by the trigger below (the identity.credential precedent), and the identity
--   columns are immutable in any state.
--
-- NO VERDICT COLUMN, DELIBERATELY. The plan's machine is RESOLVED(reason, reviewer) and
--   nothing else (PHASE_2_PLAN.md section 5); a verdict enum here would be shaping
--   P2-TSK-013's decision inputs from inside a review task - the deliberately-few licence's
--   boundary. If the decision task needs one, it arrives with the design that consumes it.
--
-- NO resolved_at >= opened_at CONSTRAINT, DELIBERATELY. Both timestamps are application-
--   supplied from one injected Clock in DIFFERENT transactions, and the local container's
--   clock is corrected backwards (P1-TSK-031's catalogued fragility); nothing about
--   correctness depends on that ordering, so the constraint would buy flaky refusals and no
--   property.

-- The reviewer's IdentityId. No REFERENCES clause: identity.identity is another module's
-- schema, and a cross-schema FK is coupling neither Gradle nor ArchUnit can see (ADR-0029).
-- What ties the value to a real person is that it is copied from a proven Session by the
-- resolution service, and the audit record written in the same transaction names the same
-- actor.
ALTER TABLE kyc.review_task ADD COLUMN resolved_by uuid;
ALTER TABLE kyc.review_task ADD COLUMN resolved_at timestamptz;
ALTER TABLE kyc.review_task ADD COLUMN resolution_reason text;

-- A resolved task without its who/when/why - or an open task carrying any of them - is
-- unrepresentable. Three clauses rather than one triple-equality, so a violation names the
-- field that is wrong.
ALTER TABLE kyc.review_task ADD CONSTRAINT review_task_resolution_is_coherent
    CHECK (
        ((status = 'RESOLVED') = (resolved_by IS NOT NULL))
        AND ((status = 'RESOLVED') = (resolved_at IS NOT NULL))
        AND ((status = 'RESOLVED') = (resolution_reason IS NOT NULL))
    );

-- Mirrors AuditRecord.MAX_REASON_LENGTH, which mirrors the CHECK on platform.audit_record;
-- ReviewTaskMigrationTest reconciles the three so a widened boundary cannot turn a caller's
-- over-long reason into a 500 at the last write.
ALTER TABLE kyc.review_task ADD CONSTRAINT review_task_reason_is_bounded
    CHECK (resolution_reason IS NULL
        OR char_length(resolution_reason) BETWEEN 1 AND 1000);

-- RESOLVED is terminal and the resolution is evidence: the row freezes whole. In any state the
-- identity columns - which question, on which case, opened when - are facts, not fields.
-- The column-level grant below already narrows what the application role may touch; this
-- narrows what ANY writer may do, including a future grant widening nobody re-reviewed
-- (the P0-TST-007 lesson that a widened grant fails silently without a schema-level check).
CREATE OR REPLACE FUNCTION kyc.review_task_resolution_is_final()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.case_id IS DISTINCT FROM OLD.case_id
        OR NEW.check_id IS DISTINCT FROM OLD.check_id
        OR NEW.opened_at IS DISTINCT FROM OLD.opened_at
    THEN
        RAISE EXCEPTION 'a review task''s identity is immutable: only its resolution may be written'
            USING ERRCODE = 'check_violation';
    END IF;

    -- The only permitted transition, one-way (INV-LIFE-04). A wrong resolution is a new review
    -- event on the case, never an edit of the record a decision may already rest on.
    IF OLD.status <> 'OPEN' OR NEW.status <> 'RESOLVED' THEN
        RAISE EXCEPTION 'a review task may only move from OPEN to RESOLVED, and a resolution is never edited'
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER review_task_resolution_is_final
    BEFORE UPDATE ON kyc.review_task
    FOR EACH ROW
    EXECUTE FUNCTION kyc.review_task_resolution_is_final();

-- Column-level on purpose, to NARROW (the V004 precedent): the resolution columns and the
-- status they travel with, nothing else. P0-TST-007's guard compares column grants against
-- table grants for every platform table, which is what keeps this visible to a reader
-- auditing table_privileges.
GRANT UPDATE (status, resolved_by, resolved_at, resolution_reason)
    ON kyc.review_task TO finapp_app;

COMMENT ON COLUMN kyc.review_task.resolved_by IS
    'RESTRICTED-PII ceiling. The reviewer''s IdentityId - who answered what the check raised '
    '(INV-KYC-04: the audit record and this column name the same person, written in one '
    'transaction).';

COMMENT ON COLUMN kyc.review_task.resolution_reason IS
    'RESTRICTED-PII ceiling. Free prose written by a person about somebody''s screening result: '
    'it may name the customer, a list entry or a case number. Bounded by the audit record''s '
    'own reason bound, and never rendered anywhere but the reviewer surface.';
