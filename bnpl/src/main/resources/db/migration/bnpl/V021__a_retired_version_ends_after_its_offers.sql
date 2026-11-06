-- A retired BNPL terms version ends after every offer made under it, whatever the database clock did (P14-DOC-001, the
-- Phase 14 exit review's database pass; the X-TSK-017 / credit V014 lesson; lending V033's twin).
--
-- WHAT WAS WRONG
--   V002 stamps a retirement's effective_to with GREATEST(clock_timestamp(), transaction_timestamp(), its start + 1us),
--   written after the activation's lock on the version row - behind every pin's FOR SHARE - and its comment concluded "so
--   after every offer made under it". That holds only while the database clock never steps back. An offer's offered_at is
--   clock_timestamp() at its insert (V004, V006); a step back between that offer's commit and the retirement leaves the
--   version ending BEFORE an offer pinned to it, so the offer reads as made outside its own version's effective period. The
--   phase's database pass caught the identical stamp red in lending (LoanTermsDatabaseTest, lending V033); bnpl's suites did
--   not land a step in the window, and the stamp is the same.
--
-- WHAT THIS DOES
--   The same function, V002's body unchanged but for the retirement stamp, which is also never earlier than one microsecond
--   after the latest offered_at pinned to the version - read after the lock, under READ COMMITTED, when every origination
--   pinning the version has committed or rolled back. The successor's effective_from follows it unchanged. GREATEST ignores
--   the NULL of a version no offer pinned.

CREATE OR REPLACE FUNCTION bnpl.bnpl_terms_version_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PROPOSED' OR NEW.decided_by IS NOT NULL OR NEW.decided_by_type IS NOT NULL
                OR NEW.decided_at IS NOT NULL OR NEW.decision_reason IS NOT NULL
                OR NEW.effective_from IS NOT NULL OR NEW.effective_to IS NOT NULL THEN
            RAISE EXCEPTION 'a BNPL terms version is born PROPOSED and undecided: no version is activated by insert (P12-TSK-004)';
        END IF;
        NEW.proposed_at := clock_timestamp();
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a BNPL terms version is never deleted (P12-TSK-004, INV-HIST-04)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.product IS DISTINCT FROM OLD.product
            OR NEW.version IS DISTINCT FROM OLD.version
            OR NEW.proposed_by IS DISTINCT FROM OLD.proposed_by
            OR NEW.proposed_by_type IS DISTINCT FROM OLD.proposed_by_type
            OR NEW.proposed_at IS DISTINCT FROM OLD.proposed_at
            OR NEW.proposal_reason IS DISTINCT FROM OLD.proposal_reason
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a BNPL terms version''s identity is frozen (P12-TSK-004, INV-HIST-04)';
    END IF;
    IF NOT ((OLD.status = 'PROPOSED' AND NEW.status IN ('ACTIVE', 'REJECTED'))
            OR (OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED')) THEN
        RAISE EXCEPTION 'a BNPL terms version moves PROPOSED -> ACTIVE | REJECTED, ACTIVE -> RETIRED; % -> % is not an edge (P12-TSK-004)', OLD.status, NEW.status;
    END IF;
    IF OLD.status = 'PROPOSED' THEN
        NEW.decided_at := clock_timestamp();
        -- Never before the product's latest end - its predecessor's, retired first in this transaction - whatever the
        -- database clock did since (credit V014's lesson).
        NEW.effective_from := CASE WHEN NEW.status = 'ACTIVE' THEN GREATEST(transaction_timestamp(),
                (SELECT max(v.effective_to) FROM bnpl.bnpl_terms_version v WHERE v.product = NEW.product)) END;
        NEW.effective_to := NULL;
    ELSE
        IF NEW.decided_by IS DISTINCT FROM OLD.decided_by OR NEW.decided_by_type IS DISTINCT FROM OLD.decided_by_type
                OR NEW.decided_at IS DISTINCT FROM OLD.decided_at
                OR NEW.decision_reason IS DISTINCT FROM OLD.decision_reason
                OR NEW.effective_from IS DISTINCT FROM OLD.effective_from THEN
            RAISE EXCEPTION 'an activation''s decision is frozen; a retirement records only its end (P12-TSK-004)';
        END IF;
        -- Written after the activation's lock on this row (FOR UPDATE, behind every pin's FOR SHARE), so after every offer
        -- made under it; never before the transaction's start, so the successor's effective_from (the greater of the start
        -- and this) is exactly this end; never at or before its own start, whatever the clock says.
        NEW.effective_to := GREATEST(clock_timestamp(), transaction_timestamp(), OLD.effective_from + interval '1 microsecond',
            (SELECT max(o.offered_at) FROM bnpl.bnpl_offer o WHERE o.terms_version_id = OLD.id) + interval '1 microsecond');
    END IF;
    RETURN NEW;
END;
$$;
