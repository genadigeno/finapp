-- A terms version ends after every offer made under it, its successor starts exactly there, and an offer is never dated
-- before its version began - whatever the database clock did (P15-TSK-033, PI-014 item 1; INV-LND-05, INV-HIST-04; ADR-0063
-- decision 2, the X-TSK-017 / credit V014 lesson; DISTRIBUTED_EXECUTION.md section 3's clock-step rule). The number is V033:
-- V032 is the last lending migration (Flyway outOfOrder = false). P14-DOC-001's draft of the same number lived on that task's
-- branch only and was withdrawn there before its merge: master's tree never held it. A throwaway database migrated on that
-- branch holds a different V033 and is rebuilt, as every test container is.
--
-- WHAT WAS WRONG
--   V030 stamps a retirement's effective_to GREATEST(clock_timestamp(), transaction_timestamp(), its start + 1 us), written
--   after the activation's lock on the version row (FOR UPDATE, behind every origination's FOR SHARE pin), and its comment
--   concluded "so after every offer made under it". That holds only while the database clock never steps back. An offer's
--   offered_at is its own insert's stamp (V028); a backward step between that offer's commit and the retirement ends the
--   version BEFORE an offer pinned to it, so the offer reads as made outside its own version's effective period and "the
--   version in force at the offering instant" (LoanTermsStore.activeAt) names the successor. Phase 14's database pass caught
--   it red once (LoanTermsDatabaseTest#anOfferMadeDuringAnActivationStandsInsideItsVersion) on a container whose clock
--   stepped back 0.57 s and 2.15 s about every 25 s; the owner postponed it as PI-014 (1).
--
--   The draft fix took the end past the latest offer but kept V030's successor start, GREATEST(transaction_timestamp(),
--   max(effective_to) over the PRODUCT): any other version of the product ending later - an end pushed by a future-dated
--   offer another suite committed - moved the successor off its predecessor, and V003's deferred
--   loan_terms_retires_only_beside_its_successor refused the commit (LoanTermsDatabaseTest#aBackwardClockIsClamped).
--
-- WHAT THIS DOES - three stamps follow the facts they must follow, each in the statement that writes its row
--   1. The retirement (ACTIVE -> RETIRED) ends the version never before one microsecond after the latest offered_at pinned
--      to it, besides V030's three terms. Read after the lock: every origination pinning the version holds it FOR SHARE, so
--      the activation's FOR UPDATE waited until each committed or rolled back, and under READ COMMITTED - the application's
--      isolation - this volatile function's query takes a snapshot after the lock and sees every one. GREATEST ignores the
--      NULL of a version no offer pinned. loan_offer_by_terms_version below makes the read an index lookup.
--   2. The activation (PROPOSED -> ACTIVE) starts the successor EXACTLY at its predecessor's end, chosen by row order, never
--      by a stamp: the product's highest-numbered RETIRED version below the new one. LoanTermsAdministration.approve retires
--      the product's ACTIVE version first in this transaction, and it is that one: one ACTIVE per product
--      (loan_terms_one_active), one PROPOSED (loan_terms_one_proposed), numbers max + 1 under the product's administration
--      lock (A0), and every other RETIRED version was retired beside a successor activated after it. Its end is never
--      before this transaction's start (its GREATEST holds transaction_timestamp()), so the start is never earlier than V030's.
--      The product's first activation has no predecessor and starts at the transaction's start, as before.
--   3. The offering instant (V028's GREATEST(statement_timestamp(), transaction_timestamp())) is never before the pinned
--      version's effective_from. Without it, (1) and (2) could start a successor up to a step ahead of the clock and an offer
--      made under it just after, its clock stepped back, would stand before its version began - the defect moved, not fixed.
--      The version is pinned FOR SHARE by the origination, so its start cannot move under the insert. On a clock that did not
--      step the version began before the offer and nothing changes; on one that did, the offering instant is later than the
--      clock by at most the step, and an offer whose decision lapses inside that window is refused "born expired" - fail
--      closed, as V028's floor at the transaction's start already is.
--
-- WHAT IS LEFT AS IT WAS
--   No backfill. A RETIRED version's effective_to is frozen history - the edge trigger refuses any change once RETIRED - and
--   moving it would move its successor's start, another frozen fact (INV-HIST-01). Any offer a past step left outside its
--   version stays visible as such (POSTPONED_ISSUES.md PI-014 gives the census query); the platform holds no production data.
--   An offer's closed_at stays statement_timestamp() (V028's reading of the birth and terminal stamps: no rank orders them).
--
-- Replaced, each its latest predecessor's body with exactly the changes above: lending.loan_terms_version_permits_only_machine_edges
-- (V030), lending.loan_offer_permits_only_machine_edges (V028). New: loan_offer_by_terms_version, built in the migration's
-- transaction, not CONCURRENTLY (DATA_MIGRATIONS.md section 4.4, ledger V029's precedent: a plain build's brief SHARE lock is
-- the smaller risk on a table with no production rows; a large live table would take a non-transactional migration).
-- Migrator-owned; like their predecessors schema-qualified throughout and invoker's rights, so no search_path is set. Nothing
-- here writes money, adds a column or grants anything.

CREATE INDEX loan_offer_by_terms_version ON lending.loan_offer (terms_version_id, offered_at);

-- ---------------------------------------------------------------------------------------------
-- lending.loan_terms_version_permits_only_machine_edges, replaced: V030's body and changes 1 and 2 alone.
-- ---------------------------------------------------------------------------------------------
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
        -- P15-TSK-033 (PI-014): exactly where its predecessor ends - the product's highest-numbered RETIRED version, retired
        -- first in this transaction - chosen by row order, never by a stamp; the product's first version at the start.
        NEW.effective_from := CASE WHEN NEW.status = 'ACTIVE' THEN COALESCE(
                (SELECT v.effective_to FROM lending.loan_terms_version v
                  WHERE v.product = NEW.product AND v.status = 'RETIRED' AND v.version < NEW.version
                  ORDER BY v.version DESC LIMIT 1),
                transaction_timestamp()) END;
        NEW.effective_to := NULL;
    ELSE
        IF NEW.decided_by IS DISTINCT FROM OLD.decided_by OR NEW.decided_by_type IS DISTINCT FROM OLD.decided_by_type
                OR NEW.decided_at IS DISTINCT FROM OLD.decided_at
                OR NEW.decision_reason IS DISTINCT FROM OLD.decision_reason
                OR NEW.effective_from IS DISTINCT FROM OLD.effective_from THEN
            RAISE EXCEPTION 'an activation''s decision is frozen; a retirement records only its end (P11-TSK-005)';
        END IF;
        -- Never at or before its own start, whatever the clock says (credit V014); written after the activation's lock on
        -- this row (FOR UPDATE, behind every origination's FOR SHARE pin), never the transaction's start, before the wait
        -- (the Phase 11 gate, A2-F3). P15-TSK-033 (PI-014): and never before one microsecond after the latest offer pinned to
        -- it - the clock can step back between that offer's stamp and this one.
        NEW.effective_to := GREATEST(clock_timestamp(), transaction_timestamp(), OLD.effective_from + interval '1 microsecond',
                (SELECT max(o.offered_at) FROM lending.loan_offer o WHERE o.terms_version_id = OLD.id) + interval '1 microsecond');
    END IF;
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- lending.loan_offer_permits_only_machine_edges, replaced: V028's body and change 3 alone.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION lending.loan_offer_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    application lending.loan_application%ROWTYPE;
    validity_days integer;
    version_start timestamptz;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'OFFERED' OR NEW.closed_at IS NOT NULL THEN
            RAISE EXCEPTION 'a loan offer is born OFFERED and open (P11-TSK-011)';
        END IF;
        -- Born under its application's row lock, from AWAITING_DECISION, for the same party and product.
        SELECT * INTO application FROM lending.loan_application a WHERE a.id = NEW.application_id;
        IF NOT FOUND OR application.status <> 'AWAITING_DECISION'
                OR application.party_id <> NEW.party_id OR application.product <> NEW.product THEN
            RAISE EXCEPTION 'a loan offer is born for its own party''s application AWAITING_DECISION, of the same product (P11-TSK-011)';
        END IF;
        -- On the product's terms version ACTIVE now - the one the step pinned FOR SHARE (INV-HIST-04).
        SELECT t.offer_validity_days, v.effective_from INTO validity_days, version_start
          FROM lending.loan_terms_version v JOIN lending.loan_terms t ON t.terms_version_id = v.id
         WHERE v.id = NEW.terms_version_id AND v.product = NEW.product AND v.status = 'ACTIVE';
        IF NOT FOUND THEN
            RAISE EXCEPTION 'a loan offer pins its product''s ACTIVE terms version (P11-TSK-011, INV-HIST-04)';
        END IF;
        -- The offering instant is a database instant of this transaction, after the step's locks, so the illustrative
        -- schedule's first day and the expiry are one clock's. P11-TSK-036: stamped HERE, by this statement's clock - never
        -- a value an earlier statement read, which a database clock stepped back between the two put after this clock (or
        -- before the transaction's start) and V005/V006 refused - and never before the transaction began (ADR-0063
        -- decision 2, the order of facts over the clock's reading). P15-TSK-033 (PI-014): and never before the pinned
        -- version began, which a step can put ahead of this clock. The step takes it back from RETURNING.
        NEW.offered_at := GREATEST(statement_timestamp(), transaction_timestamp(), version_start);
        -- The expiry on the database clock: the earlier of the decision's validity and the pinned version's offer
        -- validity (PHASE_11_PLAN.md section 5.2) - stamped here, never written by the step.
        NEW.expires_at := LEAST(NEW.decision_valid_until, NEW.offered_at + make_interval(days => validity_days));
        IF NEW.expires_at <= NEW.offered_at THEN
            RAISE EXCEPTION 'a loan offer is never born expired: the decision has lapsed (P11-TSK-011)';
        END IF;
        -- The expiry sweep's permit: due at the expiry.
        NEW.next_step_at := NEW.expires_at;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a loan offer is never deleted (P11-TSK-011, INV-HIST-01)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.application_id IS DISTINCT FROM OLD.application_id
            OR NEW.party_id IS DISTINCT FROM OLD.party_id OR NEW.product IS DISTINCT FROM OLD.product
            OR NEW.decision_id IS DISTINCT FROM OLD.decision_id
            OR NEW.terms_version_id IS DISTINCT FROM OLD.terms_version_id
            OR NEW.offered_at IS DISTINCT FROM OLD.offered_at
            OR NEW.decision_valid_until IS DISTINCT FROM OLD.decision_valid_until
            OR NEW.expires_at IS DISTINCT FROM OLD.expires_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a loan offer''s identity, pin and expiry are frozen from birth (P11-TSK-011, INV-LND-05)';
    END IF;
    -- A permit-only move: only an open offer has a next step, the move moves it, and nothing but the permit moves.
    IF NEW.status = OLD.status THEN
        IF OLD.status <> 'OFFERED' OR NEW.closed_at IS DISTINCT FROM OLD.closed_at
                OR NEW.next_step_at IS NOT DISTINCT FROM OLD.next_step_at THEN
            RAISE EXCEPTION 'only an OFFERED offer has a permit to move, and only its permit; % is not (P11-TSK-011)', OLD.status;
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status <> 'OFFERED' THEN
        RAISE EXCEPTION 'a % loan offer is terminal (P11-TSK-011)', OLD.status;
    END IF;
    -- The three clock-sided edges: the customer's two while expires_at > the database clock, the expiry once <=  -
    -- exactly one of them at the boundary instant. The customer's side refused past the instant in its own SQLSTATE
    -- (P11-TSK-034), as the loan's birth is.
    IF NEW.status IN ('DECLINED', 'ACCEPTED') THEN
        IF OLD.expires_at <= statement_timestamp() THEN
            RAISE EXCEPTION 'an expired loan offer cannot be % - it expires (P11-TSK-011, P11-TSK-012)', lower(NEW.status)
                USING ERRCODE = 'LNEXP';
        END IF;
        -- ACCEPTED only beside its loan (P11-TSK-012): the loan is born first, in the same transaction, and the deferred
        -- check holds its agreement and evidence beside it at commit.
        IF NEW.status = 'ACCEPTED' AND NOT EXISTS (SELECT 1 FROM lending.loan l WHERE l.offer_id = NEW.id) THEN
            RAISE EXCEPTION 'a loan offer is ACCEPTED only beside its loan (P11-TSK-012)';
        END IF;
    ELSIF NEW.status = 'EXPIRED' THEN
        IF OLD.expires_at > statement_timestamp() THEN
            RAISE EXCEPTION 'a loan offer expires only once expires_at <= the database clock (P11-TSK-011)';
        END IF;
    ELSE
        RAISE EXCEPTION 'a loan offer moves OFFERED -> ACCEPTED | DECLINED | EXPIRED; % -> % is not an edge (P11-TSK-011)', OLD.status, NEW.status;
    END IF;
    NEW.closed_at := statement_timestamp();
    RETURN NEW;
END;
$$;
