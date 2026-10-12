-- The disbursement to an external bank account (path X) and its payout (P11-TSK-015; PHASE_11_PLAN.md sections 5.4,
-- 7.1 T-dis / T-pyo / T-pyr, 7.3 (d), 12.4, LENDING_LIFECYCLES.md sections 4.6, 4.7 and 4.11; ADR-0097 points 3-9;
-- INV-LND-07, INV-RAIL-02, INV-RAIL-03, INV-PAY-01, INV-PAY-04, INV-LIFE-03, INV-LND-10).
--
-- Path X is born financially exactly as path W (lending V008): ONE transaction, the receivable debited in the entry that
-- credits the borrower's wallet. Beside it, under the wallet's row lock (L7, taken before the posting), a ledger hold of
-- the net principal P - f (HoldService) - the funds cannot be spent between the disbursement and the payout - and:
--
--     loan_payout   - born PENDING beside the loan ACTIVE and its disbursement POSTED; no accrual start and no schedule
--                     yet (the awaiting-payout condition, A30). Its machine, at three ranks (PayoutStatus, this trigger,
--                     the steps' conditionals):
--
--                         PENDING -> DISPATCHING -> DISPATCHED -> PAID_OUT | FAILED
--                                    DISPATCHING -> NOT_DISPATCHED
--
--                     DISPATCHING is the claim (a database-stamped permit, committed BEFORE payments is called); DISPATCHED
--                     names payments' withdrawal by value (no cross-schema key, ADR-0029); PAID_OUT / FAILED are read from
--                     payments' terminal withdrawal and carry payments' conclusion instant; NOT_DISPATCHED is payments
--                     refusing or unable to route the withdrawal (met while dispatching), the hold released by lending.
--                     RETURNED - planned - is not a state: payments' withdrawal has no return record (the instant rail is
--                     final on acceptance, INV-REV-03; recalls and return windows are deferred by ADR-0062), so the
--                     state would have no producer (P11-TSK-015's recorded refinement).
--     accrual_start - path X: origin PAYOUT_CONCLUDED, no entry, the start date the business date of the payout's
--                     conclusion instant in the pinned servicing zone (A30) - born with schedule version 1 in the
--                     transaction that records the payout's terminal outcome.
--
-- The payout carries no money column: its amount is the agreement's principal less the pinned origination fee (the hold
-- the disbursement placed), re-derived by the step and judged against the hold by payments' adoption under the wallet lock
-- - so the schema stays without mutable money (LendingSchemaHasNoMutableMoneyTest).
--
-- Held for EVERY writer: the disbursement's edge function without V008's path X refusal; the disbursement sweep's claim
-- no longer skips path X (its index re-made); loan_payout's edge function and its deferred check; the accrual start's
-- function admitting PAYOUT_CONCLUDED; the loan's deferred check requiring, on path X, the payout beside the ACTIVE edge.
-- The loan's own edge function is untouched: V008 already admits PENDING_DISBURSEMENT -> ACTIVE on either path, and no
-- payout step updates the loan (they lock it, L3).

-- ---------------------------------------------------------------------------------------------
-- The disbursement sweep claims path X too.
-- ---------------------------------------------------------------------------------------------
DROP INDEX lending.loan_disbursement_due;
CREATE INDEX loan_disbursement_due ON lending.loan_disbursement (next_attempt_at, id) WHERE status = 'PENDING';

-- The disbursement's edge function, replaced: V008's body without the path X refusal.
CREATE OR REPLACE FUNCTION lending.loan_disbursement_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    deadline_hours integer;
    preference text;
    loan_status text;
    zone text;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PENDING' THEN
            RAISE EXCEPTION 'a disbursement is born PENDING (P11-TSK-012)';
        END IF;
        -- Beside its instalment loan awaiting disbursement; the deadline is its agreement's pinned terms version's.
        SELECT t.disbursement_deadline_hours, a.payout_preference INTO deadline_hours, preference
          FROM lending.loan l
          JOIN lending.loan_offer o ON o.id = l.offer_id
          JOIN lending.loan_application a ON a.id = o.application_id
          JOIN lending.loan_terms t ON t.terms_version_id = o.terms_version_id
         WHERE l.id = NEW.loan_id AND l.kind = 'INSTALMENT' AND l.status = 'PENDING_DISBURSEMENT';
        IF NOT FOUND OR deadline_hours IS NULL THEN
            RAISE EXCEPTION 'a disbursement is born beside its instalment loan PENDING_DISBURSEMENT (P11-TSK-012)';
        END IF;
        -- Path X exactly when the application preferred an external account (A25; ADR-0097).
        IF (preference = 'EXTERNAL_ACCOUNT') <> (NEW.payout_method_id IS NOT NULL) THEN
            RAISE EXCEPTION 'a disbursement names a payout method exactly when its application preferred an external account (P11-TSK-012)';
        END IF;
        NEW.created_at := statement_timestamp();
        NEW.deadline_at := statement_timestamp() + make_interval(hours => deadline_hours);
        -- Due for the disbursement sweep at once (P11-TSK-014).
        NEW.next_attempt_at := statement_timestamp();
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a disbursement is never deleted (P11-TSK-012, INV-HIST-01)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.loan_id IS DISTINCT FROM OLD.loan_id
            OR NEW.payout_method_id IS DISTINCT FROM OLD.payout_method_id
            OR NEW.created_at IS DISTINCT FROM OLD.created_at OR NEW.deadline_at IS DISTINCT FROM OLD.deadline_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a disbursement''s identity, path, birth and deadline are frozen (P11-TSK-014)';
    END IF;
    IF OLD.status <> 'PENDING' THEN
        RAISE EXCEPTION 'a % disbursement is terminal (P11-TSK-014, INV-LIFE-04)', OLD.status;
    END IF;
    -- A permit-only move: the sweep's claim re-stamps the permit and nothing else.
    IF NEW.status = 'PENDING' THEN
        IF NEW.next_attempt_at IS NOT DISTINCT FROM OLD.next_attempt_at OR NEW.journal_entry_id IS NOT NULL
                OR NEW.value_date IS NOT NULL OR NEW.failure_reason IS NOT NULL OR NEW.decided_at IS NOT NULL THEN
            RAISE EXCEPTION 'a PENDING disbursement moves its permit and nothing else (P11-TSK-014)';
        END IF;
        RETURN NEW;
    END IF;
    -- PENDING -> POSTED | FAILED, while its loan still awaits it: the loan moves beside it, in this transaction, and the
    -- deferred check holds the pair at commit (on path X with its payout - loan_disbursed_beside_its_facts).
    SELECT l.status, t.servicing_zone INTO loan_status, zone
      FROM lending.loan l
      JOIN lending.loan_offer o ON o.id = l.offer_id
      JOIN lending.loan_terms t ON t.terms_version_id = o.terms_version_id
     WHERE l.id = OLD.loan_id;
    IF loan_status IS DISTINCT FROM 'PENDING_DISBURSEMENT' THEN
        RAISE EXCEPTION 'a disbursement is decided only while its loan is PENDING_DISBURSEMENT (P11-TSK-014, INV-LND-07)';
    END IF;
    NEW.next_attempt_at := OLD.next_attempt_at;
    NEW.decided_at := statement_timestamp();
    IF NEW.status = 'POSTED' THEN
        IF NEW.journal_entry_id IS NULL OR NEW.value_date IS NULL OR NEW.failure_reason IS NOT NULL THEN
            RAISE EXCEPTION 'a POSTED disbursement names its journal entry and value date, and no failure (P11-TSK-014)';
        END IF;
        -- The value date is this transaction's business date in the pinned servicing zone (A10, INV-LND-10): read by the
        -- step after its locks, so between the transaction's start and this statement.
        IF NEW.value_date < lending.business_date(transaction_timestamp(), zone)
                OR NEW.value_date > lending.business_date(statement_timestamp(), zone) THEN
            RAISE EXCEPTION 'a disbursement''s value date is the business date of the database clock in its pinned zone (P11-TSK-014, A10)';
        END IF;
    ELSIF NEW.status = 'FAILED' THEN
        IF NEW.journal_entry_id IS NOT NULL OR NEW.value_date IS NOT NULL OR NEW.failure_reason IS NULL THEN
            RAISE EXCEPTION 'a FAILED disbursement names its reason and posted nothing (P11-TSK-014, rule 11)';
        END IF;
        -- A wallet that cannot be posted is retried until the deadline: the failure is judged on the database clock.
        IF NEW.failure_reason = 'DISBURSEMENT_FAILED' AND OLD.deadline_at > statement_timestamp() THEN
            RAISE EXCEPTION 'a disbursement fails only once its deadline has passed on the database clock (P11-TSK-014)';
        END IF;
    ELSE
        RAISE EXCEPTION 'a disbursement moves PENDING -> POSTED | FAILED; % -> % is not an edge (P11-TSK-014)', OLD.status, NEW.status;
    END IF;
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The payout.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE lending.loan_payout (
    id                     uuid        NOT NULL,
    loan_id                uuid        NOT NULL,
    -- The borrower's verified payout method, the disbursement's - an opaque payments reference (INV-RAIL-03).
    payment_method_id      uuid        NOT NULL,
    -- The wallet the hold stands on and the hold itself, by value (ADR-0029: no cross-schema key).
    wallet_account_id      uuid        NOT NULL,
    hold_id                uuid        NOT NULL,
    status                 text        NOT NULL,
    -- Payments' withdrawal, by value, once dispatched.
    withdrawal_id          uuid,
    not_dispatched_reason  text,
    next_attempt_at        timestamptz NOT NULL,
    last_observed_at       timestamptz,
    created_at             timestamptz NOT NULL,
    dispatched_at          timestamptz,
    concluded_at           timestamptz,
    decided_at             timestamptz,
    correlation_id         text        NOT NULL,
    CONSTRAINT loan_payout_pk PRIMARY KEY (id),
    -- One payout per loan (INV-LND-07: one disbursement, one payout of it).
    CONSTRAINT loan_payout_loan_unique UNIQUE (loan_id),
    CONSTRAINT loan_payout_loan_fk FOREIGN KEY (loan_id) REFERENCES lending.loan (id),
    -- One payout per hold and per withdrawal: the hold adopted once, the withdrawal one payout's.
    CONSTRAINT loan_payout_hold_unique UNIQUE (hold_id),
    CONSTRAINT loan_payout_withdrawal_unique UNIQUE (withdrawal_id),
    -- PayoutStatus.sqlValueList().
    CONSTRAINT loan_payout_status_is_known CHECK (
        status IN ('PENDING', 'DISPATCHING', 'DISPATCHED', 'PAID_OUT', 'FAILED', 'NOT_DISPATCHED')),
    -- NotDispatchedReason.sqlValueList(), NOT_DISPATCHED's exact companion.
    CONSTRAINT loan_payout_not_dispatched_reason_is_known CHECK (
        not_dispatched_reason IS NULL
        OR not_dispatched_reason IN ('NO_ELIGIBLE_RAIL', 'DESTINATION_UNAVAILABLE', 'CUSTOMER_UNAVAILABLE')),
    CONSTRAINT loan_payout_not_dispatched_coherent CHECK (
        (status = 'NOT_DISPATCHED') = (not_dispatched_reason IS NOT NULL)),
    -- Payments' withdrawal named exactly once it was accepted for dispatch.
    CONSTRAINT loan_payout_withdrawal_coherent CHECK (
        (status IN ('DISPATCHED', 'PAID_OUT', 'FAILED')) = (withdrawal_id IS NOT NULL)
        AND (status IN ('DISPATCHED', 'PAID_OUT', 'FAILED')) = (dispatched_at IS NOT NULL)),
    -- Concluded and decided exactly when terminal.
    CONSTRAINT loan_payout_concluded_coherent CHECK (
        (status IN ('PAID_OUT', 'FAILED', 'NOT_DISPATCHED')) = (concluded_at IS NOT NULL)
        AND (status IN ('PAID_OUT', 'FAILED', 'NOT_DISPATCHED')) = (decided_at IS NOT NULL)),
    CONSTRAINT loan_payout_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

-- The payout sweep's claim: every non-terminal payout by permit.
CREATE INDEX loan_payout_due ON lending.loan_payout (next_attempt_at, id)
    WHERE status IN ('PENDING', 'DISPATCHING', 'DISPATCHED');
-- The oldest payout not yet concluded - the borrower's funds held - for finapp.lending.payout.dispatched.age.
CREATE INDEX loan_payout_unconcluded ON lending.loan_payout (created_at)
    WHERE status IN ('PENDING', 'DISPATCHING', 'DISPATCHED');

COMMENT ON TABLE lending.loan_payout IS
    'Path X''s payout of a disbursed loan to the borrower''s verified external account (P11-TSK-015; LENDING_LIFECYCLES.md section 4.7, ADR-0097 points 3-9): born PENDING in the disbursement''s transaction beside the hold of the net principal on the borrower''s wallet; PENDING -> DISPATCHING (the sweep''s claim, committed before payments is called) -> DISPATCHED (payments'' system withdrawal under payments.withdrawal:lending/<loan>, adopting the hold, named by value) -> PAID_OUT | FAILED (payments'' terminal outcome read plainly, its conclusion instant); DISPATCHING -> NOT_DISPATCHED (payments refused or could not route: the hold released). Interest starts at the terminal outcome (A30). No money column, no account identifier (INV-RAIL-03). One per loan, per hold, per withdrawal.';
COMMENT ON COLUMN lending.loan_payout.concluded_at IS
    'Payments'' conclusion instant (its withdrawal history''s terminal move) for PAID_OUT and FAILED - held by the trigger within [created_at, the recording statement''s clock] against clock skew - or the database clock of the step that recorded NOT_DISPATCHED. The accrual start is its business date in the pinned zone.';

CREATE OR REPLACE FUNCTION lending.loan_payout_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    disbursement lending.loan_disbursement%ROWTYPE;
BEGIN
    IF TG_OP = 'INSERT' THEN
        -- Born PENDING in the disbursement's transaction: its instalment loan ACTIVE beside its path X disbursement POSTED,
        -- for that disbursement's payout method.
        SELECT d.* INTO disbursement
          FROM lending.loan_disbursement d JOIN lending.loan l ON l.id = d.loan_id
         WHERE d.loan_id = NEW.loan_id AND l.kind = 'INSTALMENT' AND l.status = 'ACTIVE';
        IF NOT FOUND OR disbursement.status <> 'POSTED' OR disbursement.payout_method_id IS NULL
                OR disbursement.payout_method_id <> NEW.payment_method_id THEN
            RAISE EXCEPTION 'a payout is born beside its loan ACTIVE and its path X disbursement POSTED, for its payout method (P11-TSK-015)';
        END IF;
        IF NEW.status <> 'PENDING' OR NEW.withdrawal_id IS NOT NULL OR NEW.not_dispatched_reason IS NOT NULL
                OR NEW.last_observed_at IS NOT NULL OR NEW.dispatched_at IS NOT NULL OR NEW.concluded_at IS NOT NULL
                OR NEW.decided_at IS NOT NULL THEN
            RAISE EXCEPTION 'a payout is born PENDING, undispatched (P11-TSK-015)';
        END IF;
        NEW.created_at := statement_timestamp();
        -- Due for the payout sweep at once.
        NEW.next_attempt_at := statement_timestamp();
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a payout is never deleted (P11-TSK-015, INV-HIST-01)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.loan_id IS DISTINCT FROM OLD.loan_id
            OR NEW.payment_method_id IS DISTINCT FROM OLD.payment_method_id
            OR NEW.wallet_account_id IS DISTINCT FROM OLD.wallet_account_id OR NEW.hold_id IS DISTINCT FROM OLD.hold_id
            OR NEW.created_at IS DISTINCT FROM OLD.created_at OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id
            OR (OLD.withdrawal_id IS NOT NULL AND NEW.withdrawal_id IS DISTINCT FROM OLD.withdrawal_id)
            OR (OLD.dispatched_at IS NOT NULL AND NEW.dispatched_at IS DISTINCT FROM OLD.dispatched_at) THEN
        RAISE EXCEPTION 'a payout''s identity, method, wallet, hold, birth and withdrawal are frozen (P11-TSK-015)';
    END IF;
    IF OLD.status IN ('PAID_OUT', 'FAILED', 'NOT_DISPATCHED') THEN
        RAISE EXCEPTION 'a % payout is terminal (P11-TSK-015, INV-LIFE-04)', OLD.status;
    END IF;
    IF OLD.status = 'PENDING' AND NEW.status = 'DISPATCHING' THEN
        -- The claim: a permit stamped forward from the database clock, nothing else.
        IF NEW.next_attempt_at < statement_timestamp() OR NEW.withdrawal_id IS NOT NULL
                OR NEW.not_dispatched_reason IS NOT NULL OR NEW.dispatched_at IS NOT NULL
                OR NEW.concluded_at IS NOT NULL OR NEW.decided_at IS NOT NULL
                OR NEW.last_observed_at IS DISTINCT FROM OLD.last_observed_at THEN
            RAISE EXCEPTION 'a payout''s claim stamps its permit forward and nothing else (P11-TSK-015)';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status = 'DISPATCHING' AND NEW.status = 'DISPATCHING' THEN
        -- A re-drive's claim: the permit moved forward, nothing else.
        IF NEW.next_attempt_at IS NOT DISTINCT FROM OLD.next_attempt_at OR NEW.next_attempt_at < statement_timestamp()
                OR NEW.withdrawal_id IS NOT NULL OR NEW.not_dispatched_reason IS NOT NULL
                OR NEW.dispatched_at IS NOT NULL OR NEW.concluded_at IS NOT NULL OR NEW.decided_at IS NOT NULL
                OR NEW.last_observed_at IS DISTINCT FROM OLD.last_observed_at THEN
            RAISE EXCEPTION 'a DISPATCHING payout moves its permit and nothing else (P11-TSK-015)';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status = 'DISPATCHING' AND NEW.status = 'DISPATCHED' THEN
        -- Payments accepted the withdrawal: named by value, observed at once.
        IF NEW.withdrawal_id IS NULL OR NEW.not_dispatched_reason IS NOT NULL OR NEW.concluded_at IS NOT NULL
                OR NEW.decided_at IS NOT NULL THEN
            RAISE EXCEPTION 'a DISPATCHED payout names payments'' withdrawal and nothing concluded (P11-TSK-015)';
        END IF;
        NEW.dispatched_at := statement_timestamp();
        NEW.next_attempt_at := statement_timestamp();
        NEW.last_observed_at := OLD.last_observed_at;
        RETURN NEW;
    END IF;
    IF OLD.status = 'DISPATCHING' AND NEW.status = 'NOT_DISPATCHED' THEN
        -- Payments refused or could not route the withdrawal: the hold released beside this edge, interest from now.
        IF NEW.not_dispatched_reason IS NULL OR NEW.withdrawal_id IS NOT NULL OR NEW.dispatched_at IS NOT NULL THEN
            RAISE EXCEPTION 'a NOT_DISPATCHED payout names its reason and no withdrawal (P11-TSK-015)';
        END IF;
        NEW.next_attempt_at := OLD.next_attempt_at;
        NEW.last_observed_at := OLD.last_observed_at;
        NEW.concluded_at := statement_timestamp();
        NEW.decided_at := statement_timestamp();
        RETURN NEW;
    END IF;
    IF OLD.status = 'DISPATCHED' AND NEW.status = 'DISPATCHED' THEN
        -- An observation of payments' undecided withdrawal (DISPATCHED or UNKNOWN there): the permit and the observation
        -- stamp move, nothing else - never a re-dispatch (INV-RAIL-02).
        IF NEW.not_dispatched_reason IS NOT NULL OR NEW.concluded_at IS NOT NULL OR NEW.decided_at IS NOT NULL
                OR (NEW.next_attempt_at IS DISTINCT FROM OLD.next_attempt_at
                    AND NEW.next_attempt_at < statement_timestamp()) THEN
            RAISE EXCEPTION 'a DISPATCHED payout moves its permit and its observation and nothing else (P11-TSK-015)';
        END IF;
        IF NEW.last_observed_at IS DISTINCT FROM OLD.last_observed_at THEN
            NEW.last_observed_at := statement_timestamp();
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status = 'DISPATCHED' AND NEW.status IN ('PAID_OUT', 'FAILED') THEN
        -- Payments' terminal outcome, read: its conclusion instant held within the payout's life on the database clock
        -- (another process's clock may be seconds off - the accrual start must never precede the payout nor follow now).
        IF NEW.concluded_at IS NULL OR NEW.not_dispatched_reason IS NOT NULL THEN
            RAISE EXCEPTION 'a concluded payout carries payments'' conclusion instant (P11-TSK-015)';
        END IF;
        NEW.concluded_at := LEAST(GREATEST(NEW.concluded_at, OLD.created_at), statement_timestamp());
        NEW.decided_at := statement_timestamp();
        NEW.next_attempt_at := OLD.next_attempt_at;
        NEW.last_observed_at := statement_timestamp();
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'a payout moves PENDING -> DISPATCHING -> DISPATCHED -> PAID_OUT | FAILED, or DISPATCHING -> NOT_DISPATCHED; % -> % is not an edge (P11-TSK-015)', OLD.status, NEW.status;
END;
$$;

CREATE TRIGGER loan_payout_permits_only_machine_edges
    BEFORE INSERT OR UPDATE OR DELETE ON lending.loan_payout
    FOR EACH ROW
    EXECUTE FUNCTION lending.loan_payout_permits_only_machine_edges();

CREATE TRIGGER loan_payout_is_never_truncated
    BEFORE TRUNCATE ON lending.loan_payout
    FOR EACH STATEMENT
    EXECUTE FUNCTION lending.loan_is_never_truncated();

-- A payout committed terminal has its accrual start and schedule version 1 (A30): the outcome and the schedule commit
-- together, for every writer.
CREATE OR REPLACE FUNCTION lending.loan_payout_concluded_beside_its_schedule()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM lending.accrual_start s
                    WHERE s.loan_id = NEW.loan_id AND s.origin = 'PAYOUT_CONCLUDED')
            OR NOT EXISTS (SELECT 1 FROM lending.repayment_schedule r
                            WHERE r.loan_id = NEW.loan_id AND r.schedule_version = 1) THEN
        RAISE EXCEPTION 'a payout committed % without its accrual start and schedule version 1 (P11-TSK-015, A30)', NEW.status;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER loan_payout_concluded_beside_its_schedule
    AFTER UPDATE ON lending.loan_payout
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (NEW.status IN ('PAID_OUT', 'FAILED', 'NOT_DISPATCHED') AND NEW.status IS DISTINCT FROM OLD.status)
    EXECUTE FUNCTION lending.loan_payout_concluded_beside_its_schedule();

-- ---------------------------------------------------------------------------------------------
-- The accrual start, replaced: path X's PAYOUT_CONCLUDED admitted.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION lending.accrual_start_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    disbursement lending.loan_disbursement%ROWTYPE;
    payout lending.loan_payout%ROWTYPE;
    zone text;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'an accrual start is immutable from insert, for every role (P11-TSK-014)';
    END IF;
    IF NEW.origin = 'DISBURSEMENT' THEN
        -- Path W: beside its instalment loan ACTIVE and its wallet disbursement POSTED, on that entry's value date.
        SELECT d.* INTO disbursement
          FROM lending.loan_disbursement d JOIN lending.loan l ON l.id = d.loan_id
         WHERE d.loan_id = NEW.loan_id AND l.kind = 'INSTALMENT' AND l.status = 'ACTIVE';
        IF NOT FOUND OR disbursement.status <> 'POSTED' OR disbursement.payout_method_id IS NOT NULL
                OR NEW.start_date <> disbursement.value_date
                OR NEW.journal_entry_id IS DISTINCT FROM disbursement.journal_entry_id THEN
            RAISE EXCEPTION 'a wallet disbursement''s accrual start is its entry''s value date, beside the loan ACTIVE (P11-TSK-014, ADR-0097)';
        END IF;
    ELSE
        -- Path X (A30): beside its instalment loan ACTIVE, its path X disbursement POSTED and its payout terminal, on the
        -- business date of the payout's conclusion in the pinned zone - never before the disbursement's value date, never
        -- after this statement's business date.
        SELECT d.* INTO disbursement
          FROM lending.loan_disbursement d JOIN lending.loan l ON l.id = d.loan_id
         WHERE d.loan_id = NEW.loan_id AND l.kind = 'INSTALMENT' AND l.status = 'ACTIVE';
        SELECT p.* INTO payout FROM lending.loan_payout p WHERE p.loan_id = NEW.loan_id;
        SELECT t.servicing_zone INTO zone
          FROM lending.loan l
          JOIN lending.loan_offer o ON o.id = l.offer_id
          JOIN lending.loan_terms t ON t.terms_version_id = o.terms_version_id
         WHERE l.id = NEW.loan_id;
        IF disbursement.id IS NULL OR disbursement.status <> 'POSTED' OR disbursement.payout_method_id IS NULL
                OR payout.id IS NULL OR payout.status NOT IN ('PAID_OUT', 'FAILED', 'NOT_DISPATCHED')
                OR NEW.journal_entry_id IS NOT NULL
                OR NEW.start_date <> lending.business_date(payout.concluded_at, zone)
                OR NEW.start_date < disbursement.value_date
                OR NEW.start_date > lending.business_date(statement_timestamp(), zone) THEN
            RAISE EXCEPTION 'a payout''s accrual start is the business date of its conclusion, beside the loan ACTIVE and the payout terminal (P11-TSK-015, A30)';
        END IF;
    END IF;
    NEW.created_at := statement_timestamp();
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The loan's deferred check, replaced: on path X the payout is born beside the ACTIVE edge.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION lending.loan_disbursed_beside_its_facts()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    disbursement lending.loan_disbursement%ROWTYPE;
BEGIN
    SELECT * INTO disbursement FROM lending.loan_disbursement d WHERE d.loan_id = NEW.id;
    IF NOT FOUND OR disbursement.status <> 'POSTED' THEN
        RAISE EXCEPTION 'a loan committed ACTIVE without its disbursement POSTED (P11-TSK-014, INV-LND-07)';
    END IF;
    IF disbursement.payout_method_id IS NULL THEN
        -- Path W: the accrual start and schedule version 1 in the same transaction (ADR-0097 point 2).
        IF NOT EXISTS (SELECT 1 FROM lending.accrual_start s WHERE s.loan_id = NEW.id)
                OR NOT EXISTS (SELECT 1 FROM lending.repayment_schedule r
                                WHERE r.loan_id = NEW.id AND r.schedule_version = 1) THEN
            RAISE EXCEPTION 'a wallet-disbursed loan committed ACTIVE without its accrual start and schedule version 1 (P11-TSK-014, ADR-0097)';
        END IF;
    ELSIF NOT EXISTS (SELECT 1 FROM lending.loan_payout p WHERE p.loan_id = NEW.id) THEN
        -- Path X: its payout born in the same transaction, the hold beside it (ADR-0097 point 3); interest waits for it.
        RAISE EXCEPTION 'an externally paid-out loan committed ACTIVE without its payout (P11-TSK-015, ADR-0097)';
    END IF;
    RETURN NULL;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The grants: the machine's status, permit, observation and outcome columns; the instants the trigger's. Never DELETE.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON lending.loan_payout TO finapp_app;
GRANT UPDATE (status, withdrawal_id, not_dispatched_reason, next_attempt_at, last_observed_at, concluded_at)
    ON lending.loan_payout TO finapp_app;
