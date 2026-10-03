-- P8-DOC-001: a RESOLVED break names the APPROVED resolution that closed it, for every writer
-- (ADR-0069 section 4 and ADR-0071 section 1 as claimed; INV-REC-02, INV-AUD-04).
--
-- WHAT THE EXIT REVIEW FOUND
--   ADR-0069 claimed "a RESOLVED break has exactly one APPROVED resolution naming it, for every
--   writer, by a deferred trigger". No such trigger existed: finapp_app holds UPDATE (status,
--   resolved_at, ...) on reconciliation.break (V004) and break_moves_only_on_machine_edges admits
--   OPEN -> RESOLVED, so a raw UPDATE closed a break with no record at all. Nor could the claim be
--   stated over resolution.break_id alone: one approval legitimately closes MORE than the break it
--   names - a remainder disposal closes the remainder's sibling breaks, an OFFSET_SUSPENSE closes
--   the offset item's break, and a batch repudiation (whose resolution names no break) closes
--   every break it emptied. Each of those closures already wrote a RESOLVED break_event, but the
--   resolution it named lived only in the free-text detail.
--
-- WHAT THIS MIGRATION ENFORCES, FOR EVERY WRITER (the application role and the owner alike)
--   1. break_event.resolution_id: the structured link from a RESOLVED edge to the resolution
--      that drove it. NULL on every other event type and on every row written before this
--      migration - history is not edited. The foreign key is DEFERRABLE INITIALLY DEFERRED
--      because the evidence path (JdbcResolutions.evidence) resolves the break and records its
--      history before it writes the EVIDENCED row, in the same transaction.
--   2. break_event_resolved_names_its_resolution (BEFORE INSERT trigger): a NEW RESOLVED event
--      names its resolution. A trigger, deliberately not a CHECK: a validated CHECK would fail on
--      the RESOLVED rows already written with no column to fill.
--   3. break_resolved_names_an_approved_resolution (DEFERRABLE INITIALLY DEFERRED constraint
--      trigger on reconciliation.break, on a status written RESOLVED): at commit, a RESOLVED
--      break_event of this break names a resolution whose status is APPROVED. Deferred because
--      a batch repudiation's REPUDIATE_BATCH row turns APPROVED as its transaction's last write,
--      after the breaks it closed (V013). A break is born OPEN by every producer and a RESOLVED
--      break takes no write (V004), so the trigger fires exactly once per closure; its INSERT
--      branch refuses a raw row born RESOLVED with no record.
--   repudiation_closure (V013) stays the repudiation's own record of the breaks it emptied.

ALTER TABLE reconciliation.break_event
    ADD COLUMN resolution_id UUID,
    ADD CONSTRAINT break_event_resolution_fk FOREIGN KEY (resolution_id)
        REFERENCES reconciliation.resolution (id)
        DEFERRABLE INITIALLY DEFERRED;

CREATE INDEX break_event_by_resolution
    ON reconciliation.break_event (resolution_id)
    WHERE resolution_id IS NOT NULL;

COMMENT ON COLUMN reconciliation.break_event.resolution_id IS
    'The resolution a RESOLVED edge names (P8-DOC-001): required on every RESOLVED event written from V015; NULL on other events and on earlier history.';

CREATE OR REPLACE FUNCTION reconciliation.break_event_resolved_names_its_resolution()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.event_type = 'RESOLVED' AND NEW.resolution_id IS NULL THEN
        RAISE EXCEPTION 'break_event_resolved_names_its_resolution: a RESOLVED edge names the resolution that drove it (ADR-0069 section 4, P8-DOC-001)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER break_event_resolved_names_its_resolution
    BEFORE INSERT ON reconciliation.break_event
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.break_event_resolved_names_its_resolution();

CREATE OR REPLACE FUNCTION reconciliation.break_resolved_names_an_approved_resolution()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
            SELECT 1
              FROM reconciliation.break_event e
              JOIN reconciliation.resolution r ON r.id = e.resolution_id
             WHERE e.break_id = NEW.id
               AND e.event_type = 'RESOLVED'
               AND r.status = 'APPROVED') THEN
        RAISE EXCEPTION 'break_resolved_names_an_approved_resolution: break % is RESOLVED with no RESOLVED event naming an APPROVED resolution (ADR-0069 section 4, P8-DOC-001)', NEW.id;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER break_resolved_names_an_approved_resolution
    AFTER INSERT OR UPDATE OF status ON reconciliation.break
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (NEW.status = 'RESOLVED')
    EXECUTE FUNCTION reconciliation.break_resolved_names_an_approved_resolution();
