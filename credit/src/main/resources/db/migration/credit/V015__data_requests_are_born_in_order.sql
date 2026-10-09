-- A decision request's data requests are born in order, whatever the database clock does (P10-TST-001; ADR-0085,
-- ADR-0063 decision 2, X-TSK-013's permit form, X-TSK-017's precedent; INV-CRD-08).
--
-- V004 stamped a data request's requested_at with statement_timestamp(), and both readers that ask "the latest data
-- request of this kind" - the freeze (SnapshotFreezer) and the COLLECTING -> READY step (DecisionProgress) - order by
-- it. The database clock steps back (an NTP or hypervisor correction; measured on the local Docker VM, 2026-10-09,
-- about every 30 s by up to 60 ms; ADR-0063 measured 1.7 s). A re-collection opened within such a step - a stale record
-- found at the freeze, its new data request born in the next few milliseconds - was stamped BEFORE the stale one, so
-- the stale one still read as the latest: READY was taken on its answer, the freeze found it stale again and opened yet
-- another request, once per collection until the clock passed the stale birth. The credit decision storm met it as four
-- bureau data requests where one re-collection was due: a redundant paid pull per loop, never a stale decision (the
-- freeze still refused the stale record, INV-CRD-08 held), and liveness only because a step is shorter than the loop.
--
-- The stamp is now the later of the statement's clock and one microsecond after the decision request's latest data
-- request - X-TSK-013's GREATEST(permit + 1 us, statement_timestamp()). Every birth for one decision request happens
-- under that request's FOR UPDATE row lock (lock-order element (2): the progress's opening and the freeze's
-- re-collection both run under it), so the latest requested_at the subquery reads is never another transaction's
-- uncommitted one, and two births in one transaction (both kinds at the opening) see each other. On a clock that is
-- not behind, GREATEST is the statement's clock and nothing changes. The deadline and the first permit follow the
-- stamp, as before; requested_at stays frozen for every writer after birth.
--
-- Only the requested_at assignment changes; the function is otherwise V004's, verbatim.

CREATE OR REPLACE FUNCTION credit.data_request_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'REQUESTED' OR NEW.attempts <> 0 OR NEW.unavailable_reported THEN
            RAISE EXCEPTION 'a data request is born REQUESTED, unasked and unreported (P10-TSK-006)';
        END IF;
        -- P10-TST-001: never at or before an earlier data request of the same decision request, whatever the clock says.
        NEW.requested_at := GREATEST(statement_timestamp(), (SELECT max(d.requested_at) + interval '1 microsecond'
                FROM credit.data_request d WHERE d.decision_request_id = NEW.decision_request_id));
        NEW.deadline_at := NEW.requested_at + NEW.collection_window;
        NEW.next_attempt_at := NEW.requested_at + NEW.retry_cadence;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a data request is never deleted (INV-HIST-01)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.decision_request_id IS DISTINCT FROM OLD.decision_request_id
            OR NEW.party_id IS DISTINCT FROM OLD.party_id
            OR NEW.product IS DISTINCT FROM OLD.product
            OR NEW.source_kind IS DISTINCT FROM OLD.source_kind
            OR NEW.provider_code IS DISTINCT FROM OLD.provider_code
            OR NEW.request_reference IS DISTINCT FROM OLD.request_reference
            OR NEW.retry_cadence IS DISTINCT FROM OLD.retry_cadence
            OR NEW.collection_window IS DISTINCT FROM OLD.collection_window
            OR NEW.requested_at IS DISTINCT FROM OLD.requested_at
            OR NEW.deadline_at IS DISTINCT FROM OLD.deadline_at THEN
        RAISE EXCEPTION 'a data request''s identity, reference, cadence, window and deadline are frozen';
    END IF;
    IF NEW.attempts < OLD.attempts THEN
        RAISE EXCEPTION 'a data request''s attempts only grow';
    END IF;
    IF OLD.unavailable_reported AND NOT NEW.unavailable_reported THEN
        RAISE EXCEPTION 'a reported data request stays reported';
    END IF;
    IF NOT ((OLD.status = 'REQUESTED' AND NEW.status IN ('REQUESTED', 'RECEIVED', 'UNAVAILABLE', 'CONSENT_WITHDRAWN'))
            OR (OLD.status = 'UNAVAILABLE' AND NEW.status IN ('UNAVAILABLE', 'CONSENT_WITHDRAWN'))
            OR (OLD.status = 'UNAVAILABLE' AND NEW.status = 'REQUESTED' AND statement_timestamp() < OLD.deadline_at)) THEN
        RAISE EXCEPTION 'a data request cannot move from % to %', OLD.status, NEW.status;
    END IF;
    RETURN NEW;
END;
$$;
