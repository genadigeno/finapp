-- A risk policy version ends after every assessment made under it, its successor starts exactly there, and an assessment is never
-- dated before its version began - whatever the database clock did (P15-TSK-037; INV-RSK-05, INV-HIST-04; ADR-0063 decision 2,
-- the X-TSK-017 / credit V014 lesson, lending V033's rule; DISTRIBUTED_EXECUTION.md section 3's clock-step rule). The number
-- is V020: V019 is the last risk migration (Flyway outOfOrder = false).
--
-- WHAT WAS WRONG
--   V002 stamps a retirement's effective_to GREATEST(clock_timestamp(), transaction_timestamp(), its start + 1 us) and its comment
--   concluded "so after every assessment made under it". That holds only while the database clock never steps back: an assessment's
--   assessed_at is its own insert's stamp (V007), and a backward step between that assessment's commit and the retirement ends the
--   version BEFORE an assessment pinned to it, so the assessment reads as made outside its own version's effective period. Latent:
--   nothing reads a risk policy version by an instant, so no verdict has turned on it - but a stored fact stands wrong, and
--   PI-014 (lending V033, bnpl V021) showed the shape. V002's successor start, GREATEST(transaction_timestamp(), max(effective_to)
--   over the operation), is the same term the withdrawn lending draft kept: any version of the operation ending later moves the
--   successor off its predecessor and the deferred risk_policy_version_retires_only_beside_its_successor refuses the commit.
--
-- WHAT THIS DOES - three stamps follow the facts they must follow, each in the statement that writes its row (lending V033's three)
--   1. The retirement (ACTIVE -> RETIRED) ends the version never before one microsecond after the latest assessed_at pinned to
--      it, besides V002's three terms. Read after the lock: every assessment pinning the version holds it FOR SHARE (T-eval, A0),
--      so the activation's FOR UPDATE waited until each committed or rolled back, and under READ COMMITTED this volatile function's
--      query takes a snapshot after the lock and sees every one. GREATEST ignores the NULL of a version no assessment pinned.
--      risk_assessment_by_policy_version below makes the read an index lookup.
--   2. The activation (PROPOSED -> ACTIVE) starts the successor EXACTLY at its predecessor's end, chosen by row order, never by a
--      stamp: the operation's highest-numbered RETIRED version below the new one. RiskPolicyAdministration.activate retires the
--      operation's ACTIVE version first in this transaction, and it is that one: one ACTIVE per operation (risk_policy_one_active),
--      activation monotonic in version number under the operation's advisory lock (A0), and every other RETIRED version was
--      retired beside a successor activated after it. Its end is never before this transaction's start (its GREATEST holds
--      transaction_timestamp()), so the start is never earlier than V002's. The operation's first activation has no predecessor and
--      starts at the transaction's start, as before.
--   3. An assessment's instant (V007's clock_timestamp()) is never before the pinned version's effective_from. Without it, (1) and (2)
--      could start a successor up to a step ahead of the clock and an assessment made under it just after, its clock stepped
--      back, would stand before its version began - the defect moved, not fixed. The version is pinned FOR SHARE by the assessment's
--      evaluation (or none, an unavailable evaluation pinning nothing), so its start cannot move under the insert; on a clock that
--      did not step the version began before the assessment and nothing changes; on one that did, the assessed instant is later
--      than the clock by at most the step (nothing judges a verdict against it but the row's own ordering).
--
-- WHAT IS LEFT AS IT WAS
--   No backfill. A RETIRED version's effective_to is frozen history - the edge trigger refuses any change once RETIRED - and moving
--   it would move its successor's start, another frozen fact (INV-HIST-01). The platform holds no production risk data. No index
--   rebuild beyond the one named below; the policy's clock calls stay unqualified exactly as their predecessors' (P15-TSK-040
--   qualifies every schema's clock calls at once).
--
-- Replaced, each its latest predecessor's body with exactly the changes above: risk.risk_policy_version_permits_only_machine_edges
-- (V002) and risk.risk_assessment_is_born_stamped (V007). New: risk_assessment_by_policy_version, built in the migration's
-- transaction, not CONCURRENTLY (DATA_MIGRATIONS.md section 4.4, the lending V033 precedent: a plain build's brief SHARE lock is the
-- smaller risk on a table with no production rows; a large live table would take a non-transactional migration).
-- Migrator-owned; like their predecessors schema-qualified throughout and invoker's rights, so no search_path is set. Nothing here
-- writes money, adds a column or grants anything.

CREATE INDEX risk_assessment_by_policy_version ON risk.risk_assessment (policy_version_id, assessed_at);

-- ---------------------------------------------------------------------------------------------
-- risk.risk_policy_version_permits_only_machine_edges, replaced: V002's body and changes 1 and 2 alone.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION risk.risk_policy_version_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PROPOSED' OR NEW.decided_by IS NOT NULL OR NEW.decided_by_type IS NOT NULL
                OR NEW.decided_at IS NOT NULL OR NEW.decision_reason IS NOT NULL
                OR NEW.effective_from IS NOT NULL OR NEW.effective_to IS NOT NULL THEN
            RAISE EXCEPTION 'a risk policy version is born PROPOSED and undecided: no version is activated by insert (P13-TSK-002)';
        END IF;
        NEW.proposed_at := clock_timestamp();
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a risk policy version is never deleted (P13-TSK-002, INV-HIST-04)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.operation IS DISTINCT FROM OLD.operation
            OR NEW.version IS DISTINCT FROM OLD.version
            OR NEW.engine IS DISTINCT FROM OLD.engine
            OR NEW.proposed_by IS DISTINCT FROM OLD.proposed_by
            OR NEW.proposed_by_type IS DISTINCT FROM OLD.proposed_by_type
            OR NEW.proposed_at IS DISTINCT FROM OLD.proposed_at
            OR NEW.proposal_reason IS DISTINCT FROM OLD.proposal_reason
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a risk policy version''s identity is frozen (P13-TSK-002, INV-HIST-04, INV-RSK-05)';
    END IF;
    IF NOT ((OLD.status = 'PROPOSED' AND NEW.status IN ('ACTIVE', 'REJECTED'))
            OR (OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED')) THEN
        RAISE EXCEPTION 'a risk policy version moves PROPOSED -> ACTIVE | REJECTED, ACTIVE -> RETIRED; % -> % is not an edge (P13-TSK-002)', OLD.status, NEW.status;
    END IF;
    IF OLD.status = 'PROPOSED' THEN
        NEW.decided_at := clock_timestamp();
        -- P15-TSK-037: exactly where its predecessor ends - the operation's highest-numbered RETIRED version, retired first in
        -- this transaction - chosen by row order, never by a stamp; the operation's first version at the start.
        NEW.effective_from := CASE WHEN NEW.status = 'ACTIVE' THEN COALESCE(
                (SELECT v.effective_to FROM risk.risk_policy_version v
                  WHERE v.operation = NEW.operation AND v.status = 'RETIRED' AND v.version < NEW.version
                  ORDER BY v.version DESC LIMIT 1),
                transaction_timestamp()) END;
        NEW.effective_to := NULL;
    ELSE
        IF NEW.decided_by IS DISTINCT FROM OLD.decided_by OR NEW.decided_by_type IS DISTINCT FROM OLD.decided_by_type
                OR NEW.decided_at IS DISTINCT FROM OLD.decided_at
                OR NEW.decision_reason IS DISTINCT FROM OLD.decision_reason
                OR NEW.effective_from IS DISTINCT FROM OLD.effective_from THEN
            RAISE EXCEPTION 'an activation''s decision is frozen; a retirement records only its end (P13-TSK-002)';
        END IF;
        -- Written after the activation's lock on this row (FOR UPDATE, behind every pin's FOR SHARE); never before the
        -- transaction's start, so the successor's effective_from (exactly this end) is never earlier than V002's; never at or
        -- before its own start, whatever the clock says. P15-TSK-037: and never before one microsecond after the latest
        -- assessment pinned to it - the clock can step back between that assessment's stamp and this one.
        NEW.effective_to := GREATEST(clock_timestamp(), transaction_timestamp(), OLD.effective_from + interval '1 microsecond',
                (SELECT max(a.assessed_at) FROM risk.risk_assessment a WHERE a.policy_version_id = OLD.id) + interval '1 microsecond');
    END IF;
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- risk.risk_assessment_is_born_stamped, replaced: V007's body and change 3 alone.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION risk.risk_assessment_is_born_stamped() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    version_start timestamptz;
BEGIN
    -- P15-TSK-037: never before the pinned version began, which a step can put ahead of this clock (see the header); an
    -- unavailable evaluation pins no version, so no floor is read. The evaluation holds the version FOR SHARE.
    SELECT v.effective_from INTO version_start
      FROM risk.risk_policy_version v WHERE v.id = NEW.policy_version_id;
    NEW.assessed_at := GREATEST(clock_timestamp(), version_start);
    RETURN NEW;
END;
$$;
