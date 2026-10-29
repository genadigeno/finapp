-- A case holding an alert is closed by two persons (the Phase 13 completion gate, 2026-10-29; owner item 78, the exit
-- review's A6-3, ratified by the owner as "fix before production use"; INV-RSK-07, INV-AUD-04; ADR-0110 decision 6).
-- V012 is never edited (Flyway, outOfOrder=false).
--
-- THE GAP
--   V012 made CLOSED_FALSE_POSITIVE and CLOSED_NO_ACTION one person's act, decided by its own proposal, from OPEN too
--   (risk_case_disposition_four_eyes exempts them). An alert's suppression is four eyes (V010's
--   risk_alert_suppression_four_eyes) - but one RISK_INVESTIGATE holder could open a case by hand on an open alert and
--   close it CLOSED_* alone, dismissing the alert without the second person its suppression needs. AML alerts included.
--
-- THE RULE (the domain's half is risk.DispositionRules#needsSecondPerson(disposition, prohibits, kind, holdsAlerts))
--   A case that holds an alert (any risk_alert ATTACHED to it - an attachment is never undone) is disposed CLOSED_* only
--   by a second person: a standing proposal on a taken case (V012's trigger already requires the take), approved by
--   someone other than its proposer. Every kind but COLLECTIONS: its one-person CLOSED_NO_ACTION is the owner's ratified
--   C3 (a default is a fact, never a suspicion an alert's suppression controls).
--
-- WHY A TRIGGER, NOT A CHECK
--   The fact that decides it - an alert attached to the case - is another table's row; a CHECK sees one row. The
--   trigger reads risk_alert by its attached_case_id on the same row lock the closure holds: an alert attaches to a case
--   only by moving the case row (Cases#attachedTo steps the locked case), so an attach racing a closure either commits
--   first and is seen here, or waits on the case row and then finds it DISPOSED. Its refusal names its own constraint
--   (risk_case_alerts_closed_by_two, SQLSTATE 23514), so a probe can assert it alone.

CREATE FUNCTION risk.risk_case_alerts_closed_by_two() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'DISPOSED' AND OLD.status <> 'DISPOSED'
            AND NEW.kind <> 'COLLECTIONS'
            AND NEW.disposition IN ('CLOSED_FALSE_POSITIVE', 'CLOSED_NO_ACTION')
            AND NEW.disposition_approved_by IS NOT DISTINCT FROM NEW.disposition_proposed_by
            AND EXISTS (SELECT 1 FROM risk.risk_alert WHERE attached_case_id = NEW.id) THEN
        RAISE EXCEPTION 'a case holding an alert is closed by a second person (item 78)'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'risk_case_alerts_closed_by_two';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER risk_case_alerts_closed_by_two
    BEFORE UPDATE ON risk.risk_case
    FOR EACH ROW
    EXECUTE FUNCTION risk.risk_case_alerts_closed_by_two();

COMMENT ON FUNCTION risk.risk_case_alerts_closed_by_two() IS
    'A case holding an alert (every kind but COLLECTIONS, C3) is disposed CLOSED_* only by a second person - the alert''s four-eyes suppression not reachable by one person''s closure (item 78, the Phase 13 gate). Refuses 23514 naming risk_case_alerts_closed_by_two.';
