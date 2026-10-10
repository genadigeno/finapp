-- The decision consumption port's database guard (P11-TSK-001; PHASE_11_PLAN.md section 12.10, ADR-0091; INV-LND-06,
-- INV-CRD-02, INV-CRD-09, INV-IDEM-01, INV-HIST-01).
--
-- credit_decision_consumption (V011) was created empty for Phase 11 with nothing writing it. It now gets its one owner
-- and writer - credit - and a database guard that makes the port the ONLY path to the table:
--
--   1. the consumer: consumer_kind (a closed set - LOAN_AGREEMENT; BNPL is Phase 12's and not a member) and
--      consumer_ref, NOT NULL, with UNIQUE (consumer_kind, consumer_ref) beside V011's UNIQUE (decision_id) - one
--      decision funds at most one consumer, and one consumer takes up at most one decision;
--   2. credit.decision_reserves(valid_until, at) - THE one predicate of "still valid": an approval reserves exposure,
--      and is consumable, exactly while valid_until > at. JdbcReservedExposure reads the same inequality
--      (valid_until > statement_timestamp()); the function below judges a lapse as its NEGATION, so at any instant
--      exactly one of "reserves (and is consumable)" and "lapsed" holds - at valid_until itself, lapsed;
--   3. credit.consume_decision(...) - SECURITY DEFINER, owned by the migrator (credit's owner role), search_path pinned:
--      it takes the party's credit_profile row FOR UPDATE (lock-order element (1), Phase 11's L0) and only then reads
--      the database clock and re-judges the decision itself - APPROVED, the consumer already present, still valid -
--      before it inserts. The Java port judges the same rules for its typed answer; this function judges them so no
--      future (or compromised) caller can consume a declined or lapsed decision, or consume one off the lock;
--   4. INSERT revoked from the application role, which keeps SELECT (the reservation read) and gains EXECUTE;
--   5. a trigger refusing any insert not made inside credit.consume_decision - for EVERY role, the owner included -
--      read from the PL/pgSQL call stack, which no caller can forge (a name in another schema renders qualified or
--      quoted differently, and only the migrator can create in credit);
--   6. consumed_at stamped by the function with that instant, no longer re-stamped by V011's trigger.
--
-- The row stays append-only for every role (V011's trigger: no UPDATE, DELETE or TRUNCATE; the application holds none
-- of those privileges either), and consumed_at becomes the instant the function judged under the lock (6). A consumption
-- is final - there is no release.

-- ------------------------------------------------------------------ the table holds no rows (asserted)

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM credit.credit_decision_consumption) THEN
        RAISE EXCEPTION 'credit_decision_consumption must be empty before V021 gives it a consumer: nothing wrote it in Phase 10, and a row here has no consumer to name (P11-TSK-001)';
    END IF;
END;
$$;

-- ------------------------------------------------------------------ the consumer

ALTER TABLE credit.credit_decision_consumption
    ADD COLUMN consumer_kind text NOT NULL,
    ADD COLUMN consumer_ref  uuid NOT NULL,
    ADD CONSTRAINT credit_decision_consumption_once_per_consumer UNIQUE (consumer_kind, consumer_ref),
    ADD CONSTRAINT credit_decision_consumption_kind_is_known CHECK (consumer_kind IN ('LOAN_AGREEMENT'));

COMMENT ON TABLE credit.credit_decision_consumption IS
    'An approval taken up by its consumer (P10-TSK-016 created it empty; P11-TSK-001 gives it its writer): once per decision and once per consumer, append-only, written ONLY by credit.consume_decision under the party''s profile lock. A consumed approval reserves no exposure - the consumer''s commitment replaces it in the same transaction.';

-- ------------------------------------------------------------------ the one predicate of "still valid"

-- IMMUTABLE and plain SQL so a planner may inline it; the operator is schema-qualified because the body resolves names
-- on its caller's search_path.
CREATE FUNCTION credit.decision_reserves(valid_until timestamptz, at timestamptz)
    RETURNS boolean
    LANGUAGE sql
    IMMUTABLE
    PARALLEL SAFE
AS $$
    SELECT valid_until OPERATOR(pg_catalog.>) at
$$;

COMMENT ON FUNCTION credit.decision_reserves(timestamptz, timestamptz) IS
    'Whether an approval valid until valid_until still reserves exposure - and is still consumable - at instant at (P11-TSK-001, ADR-0091): valid_until > at. Its negation is the lapse; at valid_until itself, lapsed. JdbcReservedExposure reads the same inequality on statement_timestamp().';

REVOKE ALL ON FUNCTION credit.decision_reserves(timestamptz, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION credit.decision_reserves(timestamptz, timestamptz) TO finapp_app;

-- ------------------------------------------------------------------ the only path to the table

-- The answers: CONSUMED (this call wrote the row), REPLAYED (the same consumer's row was already there), ALREADY_CONSUMED
-- (another consumer's), NOT_APPROVED, LAPSED, NOT_FOUND. A consumer already bound to ANOTHER decision is a caller's
-- defect, not a decision's outcome: the insert meets UNIQUE (consumer_kind, consumer_ref) and fails (23505).
--
-- The lapse is judged on clock_timestamp() read AFTER the profile lock is held - not statement_timestamp(), which is
-- the calling statement's start and so, for a caller that waited on the lock inside this function, an instant from
-- before the wait: a decider holding the lock meanwhile could have read the approval lapsed (reserving nothing) while
-- this call judged it valid on the older instant. Through the Java port the lock is already held when the calling
-- statement starts, and the two instants coincide.
--
-- An existing consumption is judged BEFORE the lapse: a consumption committed while the approval was valid is a fact,
-- and its own consumer's retry after valid_until replays it (INV-IDEM-01) rather than reading a lapse.
CREATE FUNCTION credit.consume_decision(decision uuid, consumption uuid, kind text, ref uuid)
    RETURNS TABLE (answer text, consumption_id uuid, decision_valid_until timestamptz, held_by_kind text,
                   held_by_ref uuid)
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_party       uuid;
    v_outcome     text;
    v_valid_until timestamptz;
    v_now         timestamptz;
    v_held_id     uuid;
    v_held_kind   text;
    v_held_ref    uuid;
BEGIN
    IF decision IS NULL OR consumption IS NULL OR kind IS NULL OR ref IS NULL THEN
        RAISE EXCEPTION 'a consumption names its decision, its own id and its consumer (P11-TSK-001)'
            USING ERRCODE = 'null_value_not_allowed';
    END IF;

    -- (a) The decision's party, read plain: the party is what the lock is on.
    SELECT d.party_id INTO v_party FROM credit.credit_decision d WHERE d.id = decision;
    IF NOT FOUND THEN
        RETURN QUERY SELECT 'NOT_FOUND'::text, NULL::uuid, NULL::timestamptz, NULL::text, NULL::uuid;
        RETURN;
    END IF;

    -- (b) The party's profile FOR UPDATE - lock-order element (1): a consumption and a decision for one party serialise.
    -- Re-entrant when the caller (the Java port) already holds it.
    PERFORM 1 FROM credit.credit_profile p WHERE p.party_id = v_party FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'a decision''s party has a credit profile (credit_decision_profile_fk)';
    END IF;

    -- The database clock, read under the lock.
    v_now := clock_timestamp();

    -- (c) The decision re-read under the lock (it never changes - INV-CRD-02 - but it is judged here, after the lock).
    SELECT d.outcome, d.valid_until INTO v_outcome, v_valid_until FROM credit.credit_decision d WHERE d.id = decision;

    -- (d) Only an approval is consumable.
    IF v_outcome <> 'APPROVED' THEN
        RETURN QUERY SELECT 'NOT_APPROVED'::text, NULL::uuid, v_valid_until, NULL::text, NULL::uuid;
        RETURN;
    END IF;

    -- (f) Already consumed: by this consumer - a replay - or by another.
    SELECT c.id, c.consumer_kind, c.consumer_ref INTO v_held_id, v_held_kind, v_held_ref
      FROM credit.credit_decision_consumption c WHERE c.decision_id = decision;
    IF FOUND THEN
        IF v_held_kind = kind AND v_held_ref = ref THEN
            RETURN QUERY SELECT 'REPLAYED'::text, v_held_id, v_valid_until, v_held_kind, v_held_ref;
        ELSE
            RETURN QUERY SELECT 'ALREADY_CONSUMED'::text, NULL::uuid, v_valid_until, v_held_kind, v_held_ref;
        END IF;
        RETURN;
    END IF;

    -- (e) Lapsed: the negation of the reservation's predicate, at the instant read under the lock.
    IF NOT credit.decision_reserves(v_valid_until, v_now) THEN
        RETURN QUERY SELECT 'LAPSED'::text, NULL::uuid, v_valid_until, NULL::text, NULL::uuid;
        RETURN;
    END IF;

    -- The fact, stamped with the instant judged under the lock - so consumed_at is after the lock was held and before
    -- valid_until, for every consumption (V011's trigger no longer re-stamps it: see below).
    INSERT INTO credit.credit_decision_consumption (id, decision_id, consumed_at, consumer_kind, consumer_ref)
        VALUES (consumption, decision, v_now, kind, ref);
    RETURN QUERY SELECT 'CONSUMED'::text, consumption, v_valid_until, kind, ref;
END;
$$;

COMMENT ON FUNCTION credit.consume_decision(uuid, uuid, text, uuid) IS
    'The only path to credit_decision_consumption (P11-TSK-001, ADR-0091): under the party''s credit_profile row lock, an APPROVED decision still valid on the database clock read after the lock is consumed once, by one consumer; the same consumer replays. SECURITY DEFINER as credit''s owner, search_path pinned; EXECUTE to the application role only.';

REVOKE ALL ON FUNCTION credit.consume_decision(uuid, uuid, text, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION credit.consume_decision(uuid, uuid, text, uuid) TO finapp_app;

-- ------------------------------------------------------------------ no other insert, by any role

CREATE FUNCTION credit.credit_decision_consumption_only_through_the_port()
    RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    stack text;
BEGIN
    GET DIAGNOSTICS stack = PG_CONTEXT;
    -- credit.consume_decision is compiled under its own pinned search_path, which omits credit, so its frame always
    -- renders schema-qualified; a function of that name anywhere else renders otherwise.
    IF position('PL/pgSQL function credit.consume_decision(uuid,uuid,text,uuid)' IN stack) = 0 THEN
        RAISE EXCEPTION 'a decision is consumed only through credit.consume_decision - credit''s port, under the party''s profile lock (P11-TSK-001, INV-LND-06)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER credit_decision_consumption_only_through_the_port
    BEFORE INSERT ON credit.credit_decision_consumption
    FOR EACH ROW
    EXECUTE FUNCTION credit.credit_decision_consumption_only_through_the_port();

-- ------------------------------------------------------------------ the stamp is the judged instant

-- V011 stamped consumed_at with statement_timestamp() - the calling statement's start, which for a caller that waited on
-- the profile lock inside credit.consume_decision is a moment before the lock was even held, and before a decision the
-- lock's previous holder recorded. Only the function inserts now, so the stamp is its own: v_now, read under the lock.
-- The row stays append-only for every role: UPDATE, DELETE and TRUNCATE are still refused here.
CREATE OR REPLACE FUNCTION credit.credit_decision_consumption_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a consumption is append-only (P10-TSK-016)';
    END IF;
    RETURN NEW;
END;
$$;

-- ------------------------------------------------------------------ the grants

REVOKE INSERT ON credit.credit_decision_consumption FROM finapp_app;
