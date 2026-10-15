-- The offer's expiry refused in its own SQLSTATE (P11-TSK-034, a Phase 11 remediation; lending V006 / P11-TSK-012,
-- V022 / P11-TSK-026; PHASE_11_PLAN.md section 7.1 T-acc, LENDING_LIFECYCLES.md section 4.4; INV-LND-10, INV-LND-06;
-- found by P11-TST-001's storm).
--
-- The acceptance (LoanAcceptances) judges the offer unexpired on the database clock right after the offer's row lock
-- (L2), then waits - credit's consumption under L0, and for a loan the currency's capital row (L6) - and only then births
-- the loan. The loan's edge trigger re-judges the expiry at the INSERT, as the strict backstop it is: an offer expired at
-- the instant the loan is born is refused. But it refused in the generic P0001 that every invariant breach of the
-- trigger shares, so when the clock crossed expires_at during the waits the customer's lapse surfaced as a storage
-- failure - 500 api.InternalError - where the same lapse judged a moment earlier is 409 lending.OfferNotAcceptable. No
-- money was at risk (the transaction rolled back); the error contract was broken.
--
-- The decision (owner, "map the refusal"): the expiry refusals get their own SQLSTATE, LNEXP - a custom code outside
-- every class PostgreSQL defines, after fx V005's FXCAP - and the acceptance maps exactly that code to
-- OfferNotAcceptable (JdbcLoanStore.insert, JdbcLoanOfferStore.accept/decline). Every other refusal of these triggers
-- stays P0001 and a 500: a loan born for another party's offer, a moved offer, another product or decision is an
-- invariant breach, never a client error - and the identity checks run first, so an offer both foreign and expired is
-- refused as the breach.
--
-- Two functions are replaced, otherwise verbatim (migrator-owned; like V006's and V022's, schema-qualified and invoker's
-- rights, so no search_path is set): the loan's edge function, V022's latest, its INSERT branch splitting the expiry
-- from the offer's identity; and the offer's edge function, V006's latest, its customer-side expiry refusal (ACCEPTED
-- and DECLINED past the instant) carrying LNEXP. The rules themselves - what is refused, for every writer - are
-- unchanged; only the code a refusal carries is. Flyway never edits an applied migration: this replaces, it does not
-- amend.

-- ---------------------------------------------------------------------------------------------
-- The loan's edge function, replaced: V022's, the expiry at birth in its own SQLSTATE.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION lending.loan_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    offer lending.loan_offer%ROWTYPE;
    offered_currency char(3);
    disbursement lending.loan_disbursement%ROWTYPE;
BEGIN
    IF TG_OP = 'INSERT' THEN
        -- Born by kind (LENDING_LIFECYCLES.md section 4.5): a loan awaits its disbursement, a line is born serviceable.
        IF NEW.closed_at IS NOT NULL OR NEW.closure_reason IS NOT NULL OR NEW.cancellation_reason IS NOT NULL
                OR NOT ((NEW.kind = 'INSTALMENT' AND NEW.status = 'PENDING_DISBURSEMENT')
                        OR (NEW.kind = 'REVOLVING' AND NEW.status = 'ACTIVE')) THEN
            RAISE EXCEPTION 'a loan is born PENDING_DISBURSEMENT, a credit line ACTIVE, and open (P11-TSK-012)';
        END IF;
        -- Born under its offer's row lock (L2), for its own party's OFFERED, unexpired offer of the same product, on its
        -- decision, in its currency - the offer moves ACCEPTED in the same transaction.
        SELECT * INTO offer FROM lending.loan_offer o WHERE o.id = NEW.offer_id;
        IF NOT FOUND OR offer.status <> 'OFFERED'
                OR offer.party_id <> NEW.party_id OR offer.product <> NEW.product
                OR offer.decision_id <> NEW.decision_id THEN
            RAISE EXCEPTION 'a loan is born for its own party''s OFFERED, unexpired offer of the same product, on its decision (P11-TSK-012)';
        END IF;
        SELECT t.currency INTO offered_currency FROM lending.loan_offer_terms t WHERE t.offer_id = NEW.offer_id;
        IF offered_currency IS DISTINCT FROM NEW.currency THEN
            RAISE EXCEPTION 'a loan is held in its offer''s currency (P11-TSK-012)';
        END IF;
        -- The expiry, judged last - after the offer's identity and its currency, so a breach of either is never
        -- answered as a lapse - and in its own SQLSTATE (P11-TSK-034): the acceptance judged the offer unexpired after
        -- L2 and then waited (credit's consumption, L6), so the database clock may cross expires_at before this
        -- statement - the customer's 409, not a breach.
        IF offer.expires_at <= statement_timestamp() THEN
            RAISE EXCEPTION 'a loan is never born for an expired offer - it expires (P11-TSK-012, P11-TSK-034)'
                USING ERRCODE = 'LNEXP';
        END IF;
        NEW.opened_at := statement_timestamp();
        -- Due for servicing at once - an account is claimed only while ACTIVE or CLOSING (P11-TSK-016).
        NEW.next_servicing_at := statement_timestamp();
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a loan is never deleted (P11-TSK-012, INV-HIST-01)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.party_id IS DISTINCT FROM OLD.party_id
            OR NEW.product IS DISTINCT FROM OLD.product OR NEW.kind IS DISTINCT FROM OLD.kind
            OR NEW.currency IS DISTINCT FROM OLD.currency OR NEW.offer_id IS DISTINCT FROM OLD.offer_id
            OR NEW.decision_id IS DISTINCT FROM OLD.decision_id OR NEW.opened_at IS DISTINCT FROM OLD.opened_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a loan''s identity, kind, currency and origin are frozen from birth (P11-TSK-012)';
    END IF;
    -- The servicing permit alone (P11-TSK-016): an account in servicing moves its permit and nothing else - the sweep's
    -- claim and a completed servicing step (which may re-stamp the instant it already holds: ten steps racing on one
    -- account each stamp the same next business date). Never a terminal account's, never beside another column.
    IF NEW.status = OLD.status THEN
        IF OLD.status IN ('ACTIVE', 'CLOSING')
                AND NEW.closed_at IS NOT DISTINCT FROM OLD.closed_at
                AND NEW.closure_reason IS NOT DISTINCT FROM OLD.closure_reason
                AND NEW.cancellation_reason IS NOT DISTINCT FROM OLD.cancellation_reason THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'a % loan moves only its servicing permit, alone (P11-TSK-016)', OLD.status;
    END IF;
    -- The disbursement's two edges (P11-TSK-014): each beside the disbursement's matching state, in its transaction.
    IF OLD.status = 'PENDING_DISBURSEMENT' AND NEW.status IN ('ACTIVE', 'CANCELLED') THEN
        SELECT * INTO disbursement FROM lending.loan_disbursement d WHERE d.loan_id = OLD.id;
        IF NEW.status = 'ACTIVE' THEN
            IF NOT FOUND OR disbursement.status <> 'POSTED' OR NEW.closed_at IS NOT NULL
                    OR NEW.closure_reason IS NOT NULL OR NEW.cancellation_reason IS NOT NULL THEN
                RAISE EXCEPTION 'a loan is ACTIVE only beside its disbursement POSTED (P11-TSK-014, INV-LND-07)';
            END IF;
        ELSE
            IF NOT FOUND OR disbursement.status <> 'FAILED' OR NEW.closure_reason IS NOT NULL
                    OR NEW.cancellation_reason IS DISTINCT FROM disbursement.failure_reason THEN
                RAISE EXCEPTION 'a loan is CANCELLED only beside its disbursement FAILED, for the same reason (P11-TSK-014)';
            END IF;
            NEW.closed_at := statement_timestamp();
        END IF;
        RETURN NEW;
    END IF;
    -- The closure (P11-TSK-018): an instalment loan ACTIVE -> CLOSED (REPAID) - or (PAID_OFF_EARLY) by its payoff
    -- (P11-TSK-026) - only beside its loan_closure row born in this transaction for the same reason (which holds the rows'
    -- side of "every receivable zero" and, for a payoff, the execution beside it); the terminal instant is the trigger's.
    IF OLD.status = 'ACTIVE' AND NEW.status = 'CLOSED' AND OLD.kind = 'INSTALMENT' THEN
        IF NEW.closure_reason IS NULL OR NEW.closure_reason NOT IN ('REPAID', 'PAID_OFF_EARLY')
                OR NEW.cancellation_reason IS NOT NULL
                OR NOT EXISTS (SELECT 1 FROM lending.loan_closure c
                                WHERE c.loan_id = OLD.id AND c.reason = NEW.closure_reason) THEN
            RAISE EXCEPTION 'an instalment loan is CLOSED (REPAID or PAID_OFF_EARLY) only beside its closure (P11-TSK-018, P11-TSK-026, INV-LND-08)';
        END IF;
        NEW.closed_at := statement_timestamp();
        RETURN NEW;
    END IF;
    -- A credit line's closure request (P11-TSK-023): ACTIVE -> CLOSING, only beside its request born for that outcome - the
    -- line then takes no draw (V013's draw trigger admits only ACTIVE) and is still serviced, open, never terminal.
    IF OLD.status = 'ACTIVE' AND NEW.status = 'CLOSING' AND OLD.kind = 'REVOLVING' THEN
        IF NEW.closed_at IS NOT NULL OR NEW.closure_reason IS NOT NULL OR NEW.cancellation_reason IS NOT NULL
                OR NOT EXISTS (SELECT 1 FROM lending.line_closure_request q
                                WHERE q.loan_id = OLD.id AND q.outcome = 'CLOSING') THEN
            RAISE EXCEPTION 'a credit line is CLOSING only beside the customer''s closure request (P11-TSK-023, INV-LIFE-04)';
        END IF;
        RETURN NEW;
    END IF;
    -- A credit line's closure (P11-TSK-023): ACTIVE -> CLOSED at the request when nothing is outstanding (its request born
    -- for that outcome), CLOSING -> CLOSED in the transaction that zeroes its last receivable - each CLOSED_BY_CUSTOMER and
    -- only beside its loan_closure row (the closure trigger holds the rows' side and the request beside it).
    IF OLD.status IN ('ACTIVE', 'CLOSING') AND NEW.status = 'CLOSED' AND OLD.kind = 'REVOLVING' THEN
        IF NEW.closure_reason IS DISTINCT FROM 'CLOSED_BY_CUSTOMER' OR NEW.cancellation_reason IS NOT NULL
                OR NOT EXISTS (SELECT 1 FROM lending.loan_closure c
                                WHERE c.loan_id = OLD.id AND c.reason = NEW.closure_reason)
                OR (OLD.status = 'ACTIVE'
                    AND NOT EXISTS (SELECT 1 FROM lending.line_closure_request q
                                     WHERE q.loan_id = OLD.id AND q.outcome = 'CLOSED')) THEN
            RAISE EXCEPTION 'a credit line is CLOSED (CLOSED_BY_CUSTOMER) only beside its closure and the customer''s request (P11-TSK-023, INV-LND-08)';
        END IF;
        NEW.closed_at := statement_timestamp();
        RETURN NEW;
    END IF;
    -- Every other edge is never: out of CLOSED and CANCELLED nothing, ever (INV-LIFE-04); CLOSING -> ACTIVE never (a closing
    -- line is never reopened - a new application); ACTIVE -> CANCELLED and ACTIVE -> PENDING_DISBURSEMENT never
    -- (LENDING_LIFECYCLES.md section 4.5).
    RAISE EXCEPTION 'a loan does not move here; % -> % is not an edge (P11-TSK-026, INV-LIFE-04)', OLD.status, NEW.status;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The offer's edge function, replaced: V006's, the customer's edges past the instant refused in their own SQLSTATE.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION lending.loan_offer_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    application lending.loan_application%ROWTYPE;
    validity_days integer;
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
        SELECT t.offer_validity_days INTO validity_days
          FROM lending.loan_terms_version v JOIN lending.loan_terms t ON t.terms_version_id = v.id
         WHERE v.id = NEW.terms_version_id AND v.product = NEW.product AND v.status = 'ACTIVE';
        IF NOT FOUND THEN
            RAISE EXCEPTION 'a loan offer pins its product''s ACTIVE terms version (P11-TSK-011, INV-HIST-04)';
        END IF;
        -- The offering instant is a database instant of this transaction - read by the step after its locks - so the
        -- illustrative schedule's first day and the expiry are one clock's.
        IF NEW.offered_at < transaction_timestamp() OR NEW.offered_at > statement_timestamp() THEN
            RAISE EXCEPTION 'a loan offer''s offering instant is the database clock''s, within its transaction (P11-TSK-011)';
        END IF;
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
