-- =============================================================================================
-- A counterparty screening is never reviewed by the person who requested it (the Phase 9 -> 10
-- transition gate, 2026-10-07; INV-AUD-04, INV-KYC-04).
--
-- V009 recorded who DECIDED a review but not who ASKED for the screening, so a KYC_REVIEWER could
-- register a beneficiary of their own and then release the hit on it - four eyes that were one
-- person. From here every request names its requester (the actor who registered the beneficiary;
-- a quote-time re-screen inherits it), the column is frozen like the subject, and a person's
-- decision by that same actor is refused at this rank as well as the domain's.
--
-- A screening requested before this migration names no requester: the NULL is admitted (there is
-- no source of truth inside this schema to backfill it from), and such a row is guarded by the
-- domain's rank alone. The NULL is never written again: CounterpartyScreenings requires a
-- requester on every new request, and a re-screen inherits the stored value.
-- =============================================================================================

ALTER TABLE kyc.counterparty_screening ADD COLUMN requested_by text;

ALTER TABLE kyc.counterparty_screening
    ADD CONSTRAINT counterparty_screening_requested_by_is_bounded
        CHECK (requested_by IS NULL OR char_length(requested_by) BETWEEN 1 AND 200),
    -- Four eyes are two persons: the reviewer is never the requester.
    ADD CONSTRAINT counterparty_screening_reviewer_is_not_the_requester
        CHECK (decided_by IS NULL OR requested_by IS NULL OR decided_by <> requested_by);

COMMENT ON COLUMN kyc.counterparty_screening.requested_by IS
    'The actor who requested the screening (the beneficiary''s registrant; a re-screen inherits it) - '
    'never its reviewer (kyc V010). NULL only on rows requested before V010.';

-- The requester joins the frozen columns. V009's function, statement for statement, with one more
-- frozen column; the trigger itself is unchanged and keeps calling it by name.
CREATE OR REPLACE FUNCTION kyc.counterparty_screening_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a counterparty screening is never deleted (INV-HIST-01)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.request_reference IS DISTINCT FROM OLD.request_reference
            OR NEW.subject_ciphertext IS DISTINCT FROM OLD.subject_ciphertext
            OR NEW.subject_nonce IS DISTINCT FROM OLD.subject_nonce
            OR NEW.subject_key_version IS DISTINCT FROM OLD.subject_key_version
            OR NEW.country IS DISTINCT FROM OLD.country
            OR NEW.entity_type IS DISTINCT FROM OLD.entity_type
            OR NEW.payee_verdict IS DISTINCT FROM OLD.payee_verdict
            OR NEW.requested_at IS DISTINCT FROM OLD.requested_at
            OR NEW.requested_by IS DISTINCT FROM OLD.requested_by THEN
        RAISE EXCEPTION 'a counterparty screening''s identity, subject, payee verdict and requester are frozen';
    END IF;
    IF NOT ((OLD.status = 'REQUESTED' AND NEW.status IN ('REQUESTED', 'CLEAR', 'IN_REVIEW', 'UNAVAILABLE'))
            OR (OLD.status = 'UNAVAILABLE' AND NEW.status IN ('UNAVAILABLE', 'CLEAR', 'IN_REVIEW'))
            OR (OLD.status = 'IN_REVIEW' AND NEW.status IN ('RELEASED', 'BLOCKED'))) THEN
        RAISE EXCEPTION 'a counterparty screening cannot move from % to %', OLD.status, NEW.status;
    END IF;
    IF OLD.status = 'IN_REVIEW' AND (NEW.review_reason IS DISTINCT FROM OLD.review_reason
            OR NEW.attempts IS DISTINCT FROM OLD.attempts) THEN
        RAISE EXCEPTION 'a reviewed screening keeps why it was reviewed and how often it was asked';
    END IF;
    RETURN NEW;
END;
$$;

-- No grant changes: V009's table-level INSERT admits the new column at birth, and V009's column-level
-- UPDATE grant does not name it - the application cannot rewrite a requester even before the trigger.
