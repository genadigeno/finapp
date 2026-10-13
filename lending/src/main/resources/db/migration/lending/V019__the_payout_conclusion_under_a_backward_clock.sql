-- The payout's conclusion instant on a database clock behind the payout's birth (P11-TSK-033, a Phase 11 remediation;
-- lending V009 / P11-TSK-015; ADR-0097 point 7, ADR-0063 decision 2, X-TSK-017's credit V014; A30; INV-LND-10,
-- INV-LND-07, INV-HIST-01). The number is V019 - reserved beside P11-TSK-023's V018.
--
-- V009 held payments' conclusion instant "within the payout's life" as
--
--     concluded_at := LEAST(GREATEST(instant, created_at), statement_timestamp())
--
-- - the floor (the payout's birth) applied FIRST and the ceiling (the recording statement's clock) LAST. The database
-- clock is not monotonic: an NTP or hypervisor correction steps it back (measured on the local Docker VM about every
-- 30 s, by 1.6-1.7 s; ADR-0063, X-TSK-017). A conclusion recorded while the clock stands behind the payout's birth has
-- statement_timestamp() < created_at, and the outer LEAST put concluded_at BEFORE created_at: a payout concluded before
-- it existed, and an accrual start dated by that instant - possibly the business date before the payout's
-- (LoanPayoutStepsDatabaseTest met it once, intermittently). NOT_DISPATCHED stamped statement_timestamp() unclamped, the
-- same defect on the same column. Interest starts at the conclusion's business date (A30), so this is a dating defect on
-- money's clock, not cosmetic.
--
-- The precedence, decided (ADR-0097 Follow-up): the ORDER OF FACTS WINS over the clock's reading - ADR-0063 decision 2,
-- "a stamp for a later fact is the later of that reading and the latest stamp the aggregate already carries" - in the
-- form credit V014 took for the database's own stamps (GREATEST(the clock, the predecessor's stamp)):
--
--     PAID_OUT | FAILED:  concluded_at := GREATEST(LEAST(instant, statement_timestamp()), created_at)
--     NOT_DISPATCHED:     concluded_at := GREATEST(statement_timestamp(), created_at)
--
-- The ceiling binds payments' instant only (another process's clock never puts a conclusion in the database's future);
-- it never binds the payout's own birth, which the database already recorded. On a clock not behind the birth
-- (statement_timestamp() >= created_at) both forms are the same clamp, so nothing changes; on a clock behind it the
-- conclusion is the birth - later than the clock's reading by at most the step, exactly ADR-0063's stated consequence.
--
-- The accrual start's "never after this statement's business date" rank is the clamp's consumer and takes the same
-- reference: never after the business date of GREATEST(statement_timestamp(), the payout's created_at). Left at the bare
-- clock, a conclusion recorded within a backward step across midnight in the pinned zone would date its accrual start the
-- birth's business date and be refused (P0001) - the conclusion rolled back and re-observed until the clock caught up:
-- credit V014's lesson, a legitimate transition lost to an opaque storage failure for as long as the clock stood behind.
-- The rank keeps its force: created_at is the trigger's statement_timestamp() at birth and frozen for every writer, so no
-- writer can carry an accrual start past the latest instant the database itself has recorded for the payout.
--
-- Both functions are otherwise V009's, verbatim (migrator-owned, as V009's; like V009's, schema-qualified throughout and
-- invoker's rights, so no search_path is set). The column comment is restated to match.

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
        -- P11-TSK-033: never before the payout's birth, whatever the clock (ADR-0063 decision 2).
        NEW.concluded_at := GREATEST(statement_timestamp(), OLD.created_at);
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
        -- P11-TSK-033: "now" bounds payments' instant only; the payout's birth bounds it last, so on a database clock
        -- stepped back behind the birth the conclusion is the birth (ADR-0063 decision 2, credit V014's form).
        IF NEW.concluded_at IS NULL OR NEW.not_dispatched_reason IS NOT NULL THEN
            RAISE EXCEPTION 'a concluded payout carries payments'' conclusion instant (P11-TSK-015)';
        END IF;
        -- P11-TSK-033: the birth's floor last - never before the payout, whatever the clock (ADR-0063 decision 2).
        NEW.concluded_at := GREATEST(LEAST(NEW.concluded_at, statement_timestamp()), OLD.created_at);
        NEW.decided_at := statement_timestamp();
        NEW.next_attempt_at := OLD.next_attempt_at;
        NEW.last_observed_at := statement_timestamp();
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'a payout moves PENDING -> DISPATCHING -> DISPATCHED -> PAID_OUT | FAILED, or DISPATCHING -> NOT_DISPATCHED; % -> % is not an edge (P11-TSK-015)', OLD.status, NEW.status;
END;
$$;

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
        -- P11-TSK-033: "this statement's" read as GREATEST(the clock, the payout's birth) - the conclusion's own reference.
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
                OR NEW.start_date > lending.business_date(GREATEST(statement_timestamp(), payout.created_at), zone) THEN
            RAISE EXCEPTION 'a payout''s accrual start is the business date of its conclusion, beside the loan ACTIVE and the payout terminal (P11-TSK-015, A30)';
        END IF;
    END IF;
    NEW.created_at := statement_timestamp();
    RETURN NEW;
END;
$$;

COMMENT ON COLUMN lending.loan_payout.concluded_at IS
    'Payments'' conclusion instant (its withdrawal history''s terminal move) for PAID_OUT and FAILED - held by the trigger at or after created_at and, against another process''s clock skew, at or before the recording statement''s clock unless that clock stands behind the payout''s birth (the birth wins, P11-TSK-033) - or the database clock of the step that recorded NOT_DISPATCHED, never before created_at. The accrual start is its business date in the pinned zone.';
