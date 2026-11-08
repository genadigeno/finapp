-- A BNPL terms version ends after every offer made under it, its successor starts exactly there, and an offer is never
-- dated before its version began - whatever the database clock did (P15-TSK-033, PI-014 item 1; INV-HIST-04; ADR-0063
-- decision 2; lending V033's twin, DISTRIBUTED_EXECUTION.md section 3's clock-step rule). The number is V021: V020 is the
-- last bnpl migration (Flyway outOfOrder = false). P14-DOC-001's draft of the same number lived on that task's branch only
-- and was withdrawn there before its merge: master's tree never held it. A throwaway database migrated on that branch holds
-- a different V021 and is rebuilt, as every test container is.
--
-- WHAT WAS WRONG
--   V002 stamps a retirement's effective_to GREATEST(clock_timestamp(), transaction_timestamp(), its start + 1 us), written
--   after the activation's lock on the version row, and its comment concluded "so after every offer made under it". That
--   holds only while the database clock never steps back: an offer's offered_at is its own insert's clock_timestamp() (V004,
--   V006), and a backward step between that offer's commit and the retirement ends the version BEFORE an offer pinned to it,
--   so the offer reads as made outside its own version's period and "the version in force at the offering instant"
--   (BnplTermsStore.activeAt) names the successor. Found in lending by Phase 14's database pass; bnpl's stamp is the same
--   (PI-014 (1)).
--
-- WHAT THIS DOES - lending V033's three changes, each in the statement that writes its row
--   1. The retirement ends the version never before one microsecond after the latest offered_at pinned to it, read after
--      the activation's FOR UPDATE (behind every pin's FOR SHARE) by this volatile function's own snapshot (READ COMMITTED).
--      GREATEST ignores the NULL of a version no offer pinned; bnpl_offer_by_terms_version makes the read an index lookup.
--   2. The activation starts the successor EXACTLY at its predecessor's end, chosen by row order: the product's
--      highest-numbered RETIRED version below the new one - the one BnplTermsAdministration.approve retires first in this
--      transaction (one ACTIVE and one PROPOSED per product, numbers max + 1 under the administration lock). Never the
--      greater of the start and every end of the product, which any later end elsewhere in the product moved off its
--      predecessor (V002's deferred bnpl_terms_retires_only_beside_its_successor then refuses the commit). The product's first
--      activation starts at the transaction's start, as before.
--   3. The offering instant is never before the pinned version's effective_from (pinned FOR SHARE, so it cannot move under
--      the insert). On a clock that did not step nothing changes; on one that did, the instant is later than the clock by at
--      most the step, and an offer whose decision or checkout session lapses inside that window is refused "born expired" -
--      fail closed. Its closed_at already reads GREATEST(clock_timestamp(), OLD.offered_at) (V004), so no closure stands
--      before its offering.
--
-- WHAT IS LEFT AS IT WAS
--   No backfill: a RETIRED version's effective_to is frozen history (the edge trigger refuses any change once RETIRED), and
--   moving it would move its successor's start (INV-HIST-01). Any offer a past step left outside its version stays visible
--   as such (POSTPONED_ISSUES.md PI-014 gives the census query); the platform holds no production data.
--
-- Replaced, each its latest predecessor's body with exactly the changes above: bnpl.bnpl_terms_version_permits_only_machine_edges
-- (V002), bnpl.bnpl_offer_permits_only_machine_edges (V006). New: bnpl_offer_by_terms_version, built in the migration's
-- transaction, not CONCURRENTLY (lending V033's and ledger V029's reasoning). Migrator-owned; schema-qualified throughout and
-- invoker's rights, so no search_path is set. Nothing here writes money, adds a column or grants anything.

CREATE INDEX bnpl_offer_by_terms_version ON bnpl.bnpl_offer (terms_version_id, offered_at);

-- ---------------------------------------------------------------------------------------------
-- bnpl.bnpl_terms_version_permits_only_machine_edges, replaced: V002's body and changes 1 and 2 alone.
-- ---------------------------------------------------------------------------------------------
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
        -- P15-TSK-033 (PI-014): exactly where its predecessor ends - the product's highest-numbered RETIRED version, retired
        -- first in this transaction - chosen by row order, never by a stamp; the product's first version at the start.
        NEW.effective_from := CASE WHEN NEW.status = 'ACTIVE' THEN COALESCE(
                (SELECT v.effective_to FROM bnpl.bnpl_terms_version v
                  WHERE v.product = NEW.product AND v.status = 'RETIRED' AND v.version < NEW.version
                  ORDER BY v.version DESC LIMIT 1),
                transaction_timestamp()) END;
        NEW.effective_to := NULL;
    ELSE
        IF NEW.decided_by IS DISTINCT FROM OLD.decided_by OR NEW.decided_by_type IS DISTINCT FROM OLD.decided_by_type
                OR NEW.decided_at IS DISTINCT FROM OLD.decided_at
                OR NEW.decision_reason IS DISTINCT FROM OLD.decision_reason
                OR NEW.effective_from IS DISTINCT FROM OLD.effective_from THEN
            RAISE EXCEPTION 'an activation''s decision is frozen; a retirement records only its end (P12-TSK-004)';
        END IF;
        -- Written after the activation's lock on this row (FOR UPDATE, behind every pin's FOR SHARE); never before the
        -- transaction's start; never at or before its own start, whatever the clock says. P15-TSK-033 (PI-014): and never
        -- before one microsecond after the latest offer pinned to it - the clock can step back between that offer's stamp
        -- and this one.
        NEW.effective_to := GREATEST(clock_timestamp(), transaction_timestamp(), OLD.effective_from + interval '1 microsecond',
                (SELECT max(o.offered_at) FROM bnpl.bnpl_offer o WHERE o.terms_version_id = OLD.id) + interval '1 microsecond');
    END IF;
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- bnpl.bnpl_offer_permits_only_machine_edges, replaced: V006's body and change 3 alone.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION bnpl.bnpl_offer_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    application bnpl.bnpl_application%ROWTYPE;
    validity_minutes integer;
    version_start timestamptz;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'OFFERED' OR NEW.closed_at IS NOT NULL THEN
            RAISE EXCEPTION 'a BNPL offer is born OFFERED and open (P12-TSK-011)';
        END IF;
        -- Born under its application's row lock, from AWAITING_DECISION, for the same party, product, plan and session.
        SELECT * INTO application FROM bnpl.bnpl_application a WHERE a.id = NEW.application_id;
        IF NOT FOUND OR application.status <> 'AWAITING_DECISION'
                OR application.party_id <> NEW.party_id OR application.product <> NEW.product
                OR application.plan <> NEW.plan OR application.checkout_session_id <> NEW.checkout_session_id THEN
            RAISE EXCEPTION 'a BNPL offer is born for its own party''s application AWAITING_DECISION, of the same product, plan and checkout session (P12-TSK-011)';
        END IF;
        -- On the product's terms version ACTIVE now - the one the step pinned FOR SHARE (INV-HIST-04).
        SELECT t.offer_validity_minutes, v.effective_from INTO validity_minutes, version_start
          FROM bnpl.bnpl_terms_version v JOIN bnpl.bnpl_terms t ON t.terms_version_id = v.id
         WHERE v.id = NEW.terms_version_id AND v.product = NEW.product AND v.status = 'ACTIVE';
        IF NOT FOUND THEN
            RAISE EXCEPTION 'a BNPL offer pins its product''s ACTIVE terms version (P12-TSK-011, INV-HIST-04)';
        END IF;
        -- The offering instant is the database's, after the step's locks - never the step's read, never an instance's.
        -- P15-TSK-033 (PI-014): and never before the pinned version began, which a step can put ahead of this clock.
        NEW.offered_at := GREATEST(clock_timestamp(), version_start);
        -- The expiry on the database clock (A17): the earliest of the decision's validity, the checkout session's
        -- deadline and the pinned offer validity - stamped here, never written by the step.
        NEW.expires_at := LEAST(NEW.decision_valid_until, NEW.session_expires_at,
                                NEW.offered_at + make_interval(mins => validity_minutes));
        IF NEW.expires_at <= NEW.offered_at THEN
            RAISE EXCEPTION 'a BNPL offer is never born expired: its decision or its checkout session has lapsed (P12-TSK-011)';
        END IF;
        -- The expiry sweep's permit: due at the expiry.
        NEW.next_step_at := NEW.expires_at;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a BNPL offer is never deleted (P12-TSK-011, INV-HIST-01)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.application_id IS DISTINCT FROM OLD.application_id
            OR NEW.party_id IS DISTINCT FROM OLD.party_id OR NEW.product IS DISTINCT FROM OLD.product
            OR NEW.plan IS DISTINCT FROM OLD.plan
            OR NEW.decision_id IS DISTINCT FROM OLD.decision_id
            OR NEW.terms_version_id IS DISTINCT FROM OLD.terms_version_id
            OR NEW.checkout_session_id IS DISTINCT FROM OLD.checkout_session_id
            OR NEW.offered_at IS DISTINCT FROM OLD.offered_at
            OR NEW.decision_valid_until IS DISTINCT FROM OLD.decision_valid_until
            OR NEW.session_expires_at IS DISTINCT FROM OLD.session_expires_at
            OR NEW.expires_at IS DISTINCT FROM OLD.expires_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a BNPL offer''s identity, pin and expiry are frozen from birth (P12-TSK-011)';
    END IF;
    -- A permit-only move: only an open offer has a next step, the move moves it, and nothing but the permit moves.
    IF NEW.status = OLD.status THEN
        IF OLD.status <> 'OFFERED' OR NEW.closed_at IS DISTINCT FROM OLD.closed_at
                OR NEW.next_step_at IS NOT DISTINCT FROM OLD.next_step_at THEN
            RAISE EXCEPTION 'only an OFFERED offer has a permit to move, and only its permit; % is not (P12-TSK-011)', OLD.status;
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status <> 'OFFERED' THEN
        RAISE EXCEPTION 'a % BNPL offer is terminal (P12-TSK-011)', OLD.status;
    END IF;
    -- The clock-sided edges, bracketing the domain's conditional (its clock_timestamp() read after the row lock,
    -- between this statement's start and this trigger's clock): exactly one of them at the boundary instant.
    IF NEW.status = 'DECLINED' THEN
        IF OLD.expires_at <= statement_timestamp() THEN
            RAISE EXCEPTION 'an expired BNPL offer cannot be declined: it expires (P12-TSK-011)';
        END IF;
    ELSIF NEW.status = 'EXPIRED' THEN
        IF OLD.expires_at > clock_timestamp() THEN
            RAISE EXCEPTION 'a BNPL offer expires only once expires_at <= the database clock (P12-TSK-011)';
        END IF;
    ELSIF NEW.status = 'ACCEPTED' THEN
        -- P12-TSK-013: the acceptance's side of the clock, the decline's bracket - and only beside its FINANCED purchase,
        -- born in the same transaction with its financing, obligation and agreement (INV-BNPL-02).
        IF OLD.expires_at <= statement_timestamp() THEN
            RAISE EXCEPTION 'an expired BNPL offer cannot be accepted: it expires (P12-TSK-013)';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM bnpl.purchase p WHERE p.offer_id = NEW.id AND p.status = 'FINANCED') THEN
            RAISE EXCEPTION 'a BNPL offer is ACCEPTED only beside its FINANCED purchase (P12-TSK-013, INV-BNPL-02)';
        END IF;
    ELSE
        RAISE EXCEPTION 'a BNPL offer moves OFFERED -> ACCEPTED | DECLINED | EXPIRED; % -> % is not an edge (P12-TSK-013)', OLD.status, NEW.status;
    END IF;
    -- The database stamps the closure after the lock, never before the offering on a backward clock.
    NEW.closed_at := GREATEST(clock_timestamp(), OLD.offered_at);
    RETURN NEW;
END;
$$;
