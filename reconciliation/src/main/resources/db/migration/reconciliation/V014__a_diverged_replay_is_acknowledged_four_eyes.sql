-- P8-TST-002: a zero-value ACKNOWLEDGE is one person's ONLY on a TIMING_DIFFERENCE raised by a
-- timing detector (ADR-0071 section 3 corrected; ADR-0069 section 9 corrected; INV-REC-03,
-- INV-AUD-04).
--
-- WHAT THE BATTERY FOUND
--   V007 derived four_eyes from the resolution's own row alone: four_eyes = (kind <> 'EVIDENCED'
--   AND NOT (kind = 'ACKNOWLEDGE' AND proposed_amount_minor = 0)). ADR-0071 section 3 named the
--   single-person path "in practice exactly" a TIMING_DIFFERENCE's acknowledgement - true until
--   P8-TSK-022 gave PROCESSING_ERROR (REPLAY_DIVERGED) its one disposal, a zero-value
--   ACKNOWLEDGE that ADR-0068 section 9.1 and ResolutionTemplates' own comment decided FOUR-EYES.
--   As built, a CRITICAL break saying "the matcher's decisions cannot be reproduced" closed on
--   one person's word, and nothing at the database could tell, because the CHECK cannot see the
--   break. The completion gate added: a reclassification moves the TYPE (a diverged replay and
--   a timing difference both stand on a decision) but never the CAUSE, so the derivation reads
--   both.
--
-- WHAT THIS MIGRATION ENFORCES, FOR EVERY WRITER (the application role and the owner alike)
--   1. resolution_four_eyes_derived (CHECK, relaxed): EVIDENCED is never four-eyes, and every
--      resolution but a zero-value ACKNOWLEDGE is. A zero-value ACKNOWLEDGE may carry either
--      flag at this rank - the trigger of 2 decides it.
--   2. resolution_acknowledgement_four_eyes_by_break (AFTER INSERT trigger): a zero-value
--      ACKNOWLEDGE's four_eyes must equal NOT (its break's type = 'TIMING_DIFFERENCE' AND its
--      break's cause IN the timing causes), refusing either disagreement. AFTER, not BEFORE, so
--      the row's own CHECKs answer first and keep their diagnostics; a raise here aborts the
--      statement exactly as a BEFORE trigger's would.
--   3. resolution_unapproved_is_four_eyes (CHECK): a one-person resolution (NOT four_eyes) is
--      born APPROVED - it is never PROPOSED, so no one-person row waits to be decided later.
--      Every writer's rows satisfy it: EVIDENCED is born APPROVED (V006's
--      resolution_evidenced_is_platform), and the one-person acknowledgement is born APPROVED
--      (ResolutionMachine.propose).
--   4. break_type_frozen_under_a_one_person_resolution (BEFORE UPDATE OF type trigger on break):
--      a break that a NOT four_eyes resolution names never changes type. Today such a break is
--      always RESOLVED (V007 already refuses its reclassification); this states the dependency
--      the derivation of 2 rests on - the flag was derived from the type it was born under - for
--      every writer, whatever the break's status.
--   The flag itself is frozen with the proposal by the machine trigger (V007/V013). Existing
--   rows are not rewritten - history is not edited; every row V007 admitted satisfies 1 and 3.

ALTER TABLE reconciliation.resolution
    DROP CONSTRAINT resolution_four_eyes_derived,
    ADD CONSTRAINT resolution_four_eyes_derived CHECK (
        CASE
            WHEN kind = 'EVIDENCED' THEN NOT four_eyes
            WHEN kind = 'ACKNOWLEDGE' AND proposed_amount_minor = 0 THEN true
            ELSE four_eyes
        END),
    ADD CONSTRAINT resolution_unapproved_is_four_eyes CHECK (four_eyes OR status = 'APPROVED');

CREATE OR REPLACE FUNCTION reconciliation.resolution_acknowledgement_four_eyes_by_break()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    break_type  TEXT;
    break_cause TEXT;
    one_person  BOOLEAN;
BEGIN
    IF NEW.kind = 'ACKNOWLEDGE' AND NEW.proposed_amount_minor = 0 THEN
        -- FOR SHARE: a concurrent raw type change waits for this insert's transaction, and this
        -- read waits for its - the flag is derived from the type that stands (the second gate
        -- pass's find; the application path already holds the break FOR UPDATE).
        SELECT b.type, b.cause INTO break_type, break_cause
            FROM reconciliation.break b WHERE b.id = NEW.break_id FOR SHARE;
        -- ResolutionTemplates.timingCause: the causes whose detector raises TIMING_DIFFERENCE.
        one_person := coalesce(break_type = 'TIMING_DIFFERENCE'
                AND break_cause IN ('LATE_MATCH', 'CYCLE_MISMATCH'), false);
        IF NEW.four_eyes = one_person THEN
            RAISE EXCEPTION 'resolution_four_eyes_derived_from_the_break: a zero-value ACKNOWLEDGE is one person''s only on a TIMING_DIFFERENCE raised by a timing detector, four-eyes on a % break raised by % (ADR-0071 section 3, P8-TST-002)', coalesce(break_type, 'missing'), coalesce(break_cause, 'missing');
        END IF;
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER resolution_acknowledgement_four_eyes_by_break
    AFTER INSERT ON reconciliation.resolution
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.resolution_acknowledgement_four_eyes_by_break();

CREATE OR REPLACE FUNCTION reconciliation.break_type_frozen_under_a_one_person_resolution()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.type IS DISTINCT FROM OLD.type
            AND EXISTS (SELECT 1 FROM reconciliation.resolution r
                        WHERE r.break_id = NEW.id AND NOT r.four_eyes) THEN
        RAISE EXCEPTION 'break_type_frozen_under_a_one_person_resolution: a break a one-person resolution names keeps the type its four-eyes flag was derived from (ADR-0071 section 3, P8-TST-002)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER break_type_frozen_under_a_one_person_resolution
    BEFORE UPDATE OF type ON reconciliation.break
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.break_type_frozen_under_a_one_person_resolution();
