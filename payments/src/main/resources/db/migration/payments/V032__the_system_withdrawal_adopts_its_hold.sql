-- The system withdrawal on a recorded instruction, adopting the hold its instructor placed (P11-TSK-015; ADR-0097
-- point 5, PHASE_11_PLAN.md sections 7.3 (d) and 12.4). Lending's external payout (path X) is the first instructor: the
-- disbursement credits the borrower's wallet and places a ledger hold of the net principal under the wallet's lock in
-- lending's own transaction; payments' withdrawal machine then carries the money out - its routing, its dispatch, its
-- UNKNOWN held, its inquiry-only outcomes, its expectation - exactly as for a person's withdrawal, but ADOPTING that hold
-- rather than placing a second one. The person's door, merchant and crossborder are unchanged.
--
-- What this migration adds, each for every writer:
--
--     instruction_reference - the instructor's recorded instruction, '<namespace>:<uuid>' (lending:<loanId>), present
--                             exactly on a system withdrawal; frozen; one withdrawal per instruction. A system row's
--                             dispatch_key IS its instruction reference, so the person's per-customer dispatch key is
--                             unique among PERSON rows only: a person choosing 'lending:<loanId>' as an Idempotency-Key
--                             can neither pre-empt nor be converged onto the platform's withdrawal.
--     one withdrawal per hold - the hold adopted, never duplicated: no two withdrawals ever name the same ledger hold
--                             (every person's withdrawal placed its own, so the existing rows already satisfy it).
--
-- The machine function is replaced only to freeze the new column beside the others (V016's body otherwise verbatim).

ALTER TABLE payments.withdrawal
    ADD COLUMN instruction_reference text
        CONSTRAINT withdrawal_instruction_reference_shape
            CHECK (instruction_reference ~ '^[a-z][a-z-]{0,31}:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    ADD CONSTRAINT withdrawal_instruction_is_the_dispatch_key
        CHECK (instruction_reference IS NULL OR dispatch_key = instruction_reference);

DROP INDEX payments.withdrawal_one_per_dispatch_key;

-- The person's convergence target, among the person's rows (V016's index, now partial).
CREATE UNIQUE INDEX withdrawal_one_per_dispatch_key
    ON payments.withdrawal (customer_id, dispatch_key)
    WHERE instruction_reference IS NULL;

-- One withdrawal per recorded instruction: the system entry's convergence target.
CREATE UNIQUE INDEX withdrawal_one_per_instruction
    ON payments.withdrawal (instruction_reference)
    WHERE instruction_reference IS NOT NULL;

-- One withdrawal per ledger hold: an adopted hold is never adopted twice, nor placed beside (ADR-0097 point 5).
CREATE UNIQUE INDEX withdrawal_one_per_hold
    ON payments.withdrawal (hold_reference);

CREATE OR REPLACE FUNCTION payments.withdrawal_permits_only_machine_edges() RETURNS trigger
LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.party_id IS DISTINCT FROM OLD.party_id
            OR NEW.customer_id IS DISTINCT FROM OLD.customer_id
            OR NEW.wallet_account_id IS DISTINCT FROM OLD.wallet_account_id
            OR NEW.payment_method_id IS DISTINCT FROM OLD.payment_method_id
            OR NEW.destination_reference IS DISTINCT FROM OLD.destination_reference
            OR NEW.amount_minor IS DISTINCT FROM OLD.amount_minor
            OR NEW.currency IS DISTINCT FROM OLD.currency
            OR NEW.scale IS DISTINCT FROM OLD.scale
            OR NEW.end_to_end_reference IS DISTINCT FROM OLD.end_to_end_reference
            OR NEW.rail IS DISTINCT FROM OLD.rail
            OR NEW.dispatch_key IS DISTINCT FROM OLD.dispatch_key
            OR NEW.hold_reference IS DISTINCT FROM OLD.hold_reference
            OR NEW.instruction_reference IS DISTINCT FROM OLD.instruction_reference
            OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'a withdrawal''s dispatch is frozen: wallet, instrument, destination, amount, reference, instruction and hold never move (P7-TSK-008, P11-TSK-015)';
    END IF;
    IF (OLD.failure_reason IS NOT NULL AND NEW.failure_reason IS DISTINCT FROM OLD.failure_reason)
            OR (OLD.scheme_reference IS NOT NULL
                AND NEW.scheme_reference IS DISTINCT FROM OLD.scheme_reference)
            OR (OLD.settlement_cycle IS NOT NULL
                AND NEW.settlement_cycle IS DISTINCT FROM OLD.settlement_cycle) THEN
        RAISE EXCEPTION 'a withdrawal''s recorded outcome never moves (INV-HIST-01''s discipline)';
    END IF;
    IF NEW.last_dispatched_at < OLD.last_dispatched_at THEN
        RAISE EXCEPTION 'a withdrawal''s send permit only moves forward (ADR-0057 section 4)';
    END IF;
    IF NEW.last_dispatched_at IS DISTINCT FROM OLD.last_dispatched_at
            AND OLD.status NOT IN ('DISPATCHED', 'UNKNOWN') THEN
        RAISE EXCEPTION 'a resolved withdrawal is never sent again (ADR-0057 section 4)';
    END IF;
    IF NEW.status = OLD.status THEN
        RETURN NEW;
    END IF;
    IF NOT ((OLD.status = 'DISPATCHED' AND NEW.status IN ('COMPLETED', 'FAILED', 'UNKNOWN'))
            OR (OLD.status = 'UNKNOWN' AND NEW.status IN ('COMPLETED', 'FAILED'))) THEN
        RAISE EXCEPTION 'a withdrawal moves only along the machine''s edges: DISPATCHED -> {COMPLETED, FAILED, UNKNOWN}, UNKNOWN -> {COMPLETED, FAILED} (INV-LIFE-02/-04; INV-REV-03: nothing leaves COMPLETED)';
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON COLUMN payments.withdrawal.instruction_reference IS
    'The instructor''s recorded instruction (P11-TSK-015, ADR-0097 point 5): ''<namespace>:<uuid>'' (lending:<loanId>), present exactly on a system withdrawal, which adopted the hold its instructor placed. Equal to the dispatch key; one withdrawal per instruction; frozen.';
