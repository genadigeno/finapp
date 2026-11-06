-- A retired terms version ends after every offer made under it, whatever the database clock did (P14-DOC-001, the Phase 14
-- exit review's database pass; INV-LND-05; the X-TSK-017 / credit V014 lesson, a fourth time).
--
-- WHAT WAS WRONG
--   V030 stamps a retirement's effective_to with GREATEST(clock_timestamp(), transaction_timestamp(), its start + 1us),
--   written after the activation's lock on the version row - FOR UPDATE, behind every origination's FOR SHARE pin - and its
--   comment concluded "so after every offer made under it". That holds only while the database clock never steps back. An
--   offer's offered_at is GREATEST(statement_timestamp(), transaction_timestamp()) (V028); a step back between that offer's
--   commit and the retirement leaves the version ending BEFORE an offer pinned to it, so the offer reads as made outside its
--   own version's effective period (INV-LND-05's "every offer inside the version it pins"). The phase's database pass caught
--   it red once (LoanTermsDatabaseTest#anOfferMadeDuringAnActivationStandsInsideItsVersion) on a container whose clock stepped
--   back 0.57 s and 2.15 s about every 25 s, and the repo's ClockStep shadow reproduces it deterministically
--   (TermsRetirementClockDatabaseTest). Docker Desktop's VM clock is ADR-0063's; production's can step too.
--
-- WHAT THIS DOES
--   The same function, V030's body unchanged but for the retirement stamp, which is also never earlier than one microsecond
--   after the latest offered_at pinned to the version. Read after the lock: every origination pinning the version holds it FOR
--   SHARE, so the activation's FOR UPDATE waits until each has committed or rolled back, and under READ COMMITTED - the
--   application's isolation for the activation - this statement's snapshot sees every one. The successor's effective_from
--   (the greater of its transaction's start and this end) follows it unchanged. GREATEST ignores the NULL of a version no
--   offer pinned.

CREATE OR REPLACE FUNCTION lending.loan_terms_version_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PROPOSED' OR NEW.decided_by IS NOT NULL OR NEW.decided_by_type IS NOT NULL
                OR NEW.decided_at IS NOT NULL OR NEW.decision_reason IS NOT NULL
                OR NEW.effective_from IS NOT NULL OR NEW.effective_to IS NOT NULL THEN
            RAISE EXCEPTION 'a loan terms version is born PROPOSED and undecided: no version is activated by insert (P11-TSK-005)';
        END IF;
        NEW.proposed_at := transaction_timestamp();
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a loan terms version is never deleted (P11-TSK-005, INV-HIST-04)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.product IS DISTINCT FROM OLD.product
            OR NEW.version IS DISTINCT FROM OLD.version
            OR NEW.proposed_by IS DISTINCT FROM OLD.proposed_by
            OR NEW.proposed_by_type IS DISTINCT FROM OLD.proposed_by_type
            OR NEW.proposed_at IS DISTINCT FROM OLD.proposed_at
            OR NEW.proposal_reason IS DISTINCT FROM OLD.proposal_reason
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a loan terms version''s identity is frozen (P11-TSK-005, INV-LND-05)';
    END IF;
    IF NOT ((OLD.status = 'PROPOSED' AND NEW.status IN ('ACTIVE', 'REJECTED'))
            OR (OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED')) THEN
        RAISE EXCEPTION 'a loan terms version moves PROPOSED -> ACTIVE | REJECTED, ACTIVE -> RETIRED; % -> % is not an edge (P11-TSK-005)', OLD.status, NEW.status;
    END IF;
    IF OLD.status = 'PROPOSED' THEN
        -- The Phase 11 gate (A2-F3): the instant the decision is written after the lock it waited on, never the
        -- transaction's start (V025's waiver and reversal decisions).
        NEW.decided_at := clock_timestamp();
        -- Never before the product's latest end - its predecessor's, retired first in this transaction - whatever the
        -- database clock did since (credit V014's lesson, X-TSK-017).
        NEW.effective_from := CASE WHEN NEW.status = 'ACTIVE' THEN GREATEST(transaction_timestamp(),
                (SELECT max(v.effective_to) FROM lending.loan_terms_version v WHERE v.product = NEW.product)) END;
        NEW.effective_to := NULL;
    ELSE
        IF NEW.decided_by IS DISTINCT FROM OLD.decided_by OR NEW.decided_by_type IS DISTINCT FROM OLD.decided_by_type
                OR NEW.decided_at IS DISTINCT FROM OLD.decided_at
                OR NEW.decision_reason IS DISTINCT FROM OLD.decision_reason
                OR NEW.effective_from IS DISTINCT FROM OLD.effective_from THEN
            RAISE EXCEPTION 'an activation''s decision is frozen; a retirement records only its end (P11-TSK-005)';
        END IF;
        -- Never at or before its own start, whatever the clock says (credit V014). The Phase 11 gate (A2-F3): written after
        -- the activation's lock on this row (FOR UPDATE, behind every origination's FOR SHARE pin), so after every offer
        -- made under it - never the transaction's start, before the wait; never before the start either, so the
        -- successor's effective_from (the greater of the start and this) is exactly this end. P14-DOC-001: and never before
        -- the latest offer pinned to it - the clock can step back between that offer's stamp and this one.
        NEW.effective_to := GREATEST(clock_timestamp(), transaction_timestamp(), OLD.effective_from + interval '1 microsecond',
            (SELECT max(o.offered_at) FROM lending.loan_offer o WHERE o.terms_version_id = OLD.id) + interval '1 microsecond');
    END IF;
    RETURN NEW;
END;
$$;
