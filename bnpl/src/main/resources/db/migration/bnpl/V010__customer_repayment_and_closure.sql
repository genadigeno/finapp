-- The customer's repayment from the wallet and the obligation's closure (P12-TSK-018; PHASE_12_PLAN.md sections 5.5,
-- 7.1 T-rep and T-svc, 7.2, 12.11 F3 and F6's refund arm; BNPL_LIFECYCLES.md sections 4.7 and 4.13; owner decision B6;
-- INV-BNPL-01, INV-LND-04 and INV-LND-08 by analogy, INV-IDEM-01, INV-CON-01, INV-LIFE-04; lending V014's shape).
--
-- The customer repays any amount from their wallet (T-rep, BnplRepayments): under the obligation's row lock (B4) the due
-- state is derived from the rows and the ledger, ALLOCATION_ENGINE_V1 allocates the amount - billed items oldest first,
-- FEES -> INTEREST -> PRINCIPAL within each, the remainder held as credit (HOLD_AS_CREDIT: no repayment pays principal not
-- yet due; early money is applied at the next billing, F3a) - and F3 posts DR CUSTOMER_WALLET / CR <due accounts> /
-- CR BNPL_CREDIT_BALANCE under bnpl.repayment:<repayment>. The transaction that brings the last receivable to zero - this
-- repayment, or the servicing step after an application (T-svc, P12-TSK-015's closure slot) - closes the obligation: the
-- credit balance refunded to the wallet (DR BNPL_CREDIT_BALANCE / CR CUSTOMER_WALLET, bnpl.closure-refund:<obligation>)
-- and ACTIVE -> CLOSED (REPAID), in one commit (BnplClosures). In this migration:
--
--     bnpl_repayment              - EXTENDED (P12-TSK-013 created it for pay-in-4's instalment 1, kind AT_PURCHASE): the
--                                   kinds CUSTOMER (here), AUTO_COLLECTION (P12-TSK-019) and PAYOFF (P12-TSK-022) known to
--                                   the CHECK, the two later ones refused by the trigger until their tasks replace it;
--                                   B4 re-taken by the trigger for every writer; the agreement version in force; the
--                                   floor's two ranks as V006 wrote them. No status column: REVERSED is derived from
--                                   P12-TSK-024's approved reversal. INSERT-only, born only once its money moved.
--     bnpl_repayment_allocation   - EXTENDED: no not-due-principal target (HOLD_AS_CREDIT); a due item only of a billed
--                                   instalment; one line per instalment and component, one credit-balance line per
--                                   repayment; and at commit no billed component paid beyond what it billed - every
--                                   payer's, through V008's bnpl.billing_component_paid, which already counts the
--                                   repayments' due-item lines beside the applications' (so it is not replaced here).
--     bnpl_obligation_closure     - the closure: UNIQUE (obligation_id); the reason (REPAID here; REFUNDED and
--                                   REFUNDED_PARTIALLY_REPAID P12-TSK-021's, PAID_OFF_EARLY P12-TSK-022's - each replaces
--                                   the trigger); the credit balance refunded and its entry by value (exactly when
--                                   positive); the closing repayment, if any (none when the servicing step closed it).
--                                   The rows' side of "every receivable zero" held by its trigger - every instalment of
--                                   the latest schedule billed and every billed component paid in full; the ledger's side
--                                   is the closing transaction's own under B4 (no bnpl trigger reads a ledger table, the
--                                   P12-TSK-013 refinement). A deferred check holds it to its obligation CLOSED for the
--                                   same reason at commit.
--
-- The obligation's edge function, replaced: V008's, plus ACTIVE -> CLOSED only beside its closure row for the same reason,
-- the terminal instant stamped by the database after the lock. Out of CLOSED nothing, ever (INV-LIFE-04): no permit move,
-- no second closure. Every instant the database's, clock_timestamp() after the act's locks. bnpl V009 is P12-TSK-017's.

-- ---------------------------------------------------------------------------------------------
-- The repayment, extended: its kinds, B4 for every writer, the agreement version in force.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE bnpl.bnpl_repayment DROP CONSTRAINT bnpl_repayment_kind_is_known;
ALTER TABLE bnpl.bnpl_repayment ADD CONSTRAINT bnpl_repayment_kind_is_known
    CHECK (kind IN ('AT_PURCHASE', 'CUSTOMER', 'AUTO_COLLECTION', 'PAYOFF'));

CREATE INDEX bnpl_repayment_by_obligation ON bnpl.bnpl_repayment (obligation_id, received_at);

COMMENT ON TABLE bnpl.bnpl_repayment IS
    'A BNPL repayment (P12-TSK-013 for pay-in-4''s instalment 1 collected from the customer''s wallet at purchase, kind AT_PURCHASE; P12-TSK-018 the customer''s own from the wallet, kind CUSTOMER, keyed by their idempotency key; P12-TSK-019 AUTO_COLLECTION and P12-TSK-022 PAYOFF, refused until their tasks): the amount the entry F3 (bnpl.repayment:<repayment>, its id here; DR CUSTOMER_WALLET / CR <due accounts> / CR BNPL_CREDIT_BALANCE) debited the wallet, allocated by the pinned engine, value-dated never before the obligation''s floor. Born once per (obligation, kind, key) and only once its money moved; INSERT-only; REVERSED derived from P12-TSK-024''s approved reversal (RESTRICTED-FINANCIAL).';

CREATE OR REPLACE FUNCTION bnpl.bnpl_repayment_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    obligation bnpl.bnpl_obligation%ROWTYPE;
    value_floor date;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL repayment is immutable from insert, for every role (P12-TSK-013)';
    END IF;
    -- (B4) The obligation, for every writer: the repayment, its floor and its allocation are judged under the lock every
    -- servicing act on it holds (T-pur, T-rep and T-svc hold it already; re-taking it is free).
    SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id FOR UPDATE;
    IF NOT FOUND OR obligation.status <> 'ACTIVE' OR obligation.currency <> NEW.currency
            OR obligation.agreement_id <> NEW.agreement_id
            OR NEW.agreement_version <> (SELECT max(x.version) FROM bnpl.bnpl_agreement x
                                          WHERE x.obligation_id = NEW.obligation_id)
            OR NOT EXISTS (SELECT 1 FROM bnpl.bnpl_agreement a WHERE a.agreement_id = NEW.agreement_id
                              AND a.version = NEW.agreement_version AND a.allocation_engine = NEW.allocation_engine) THEN
        RAISE EXCEPTION 'a BNPL repayment repays its ACTIVE obligation, in its currency, under its agreement version in force and its allocation engine (P12-TSK-018, INV-LND-08)';
    END IF;
    -- AT_PURCHASE: pay-in-4's instalment 1, inside the purchase, keyed by the purchase, dated the purchase's date.
    IF NEW.kind = 'AT_PURCHASE' AND (obligation.product <> 'BNPL_PAY_IN_4'
            OR NEW.request_key <> obligation.purchase_id::text OR NEW.value_date <> obligation.purchase_date) THEN
        RAISE EXCEPTION 'an AT_PURCHASE repayment is a pay-in-4 purchase''s own, keyed by it and dated its business date (P12-TSK-013, A5)';
    END IF;
    -- The automatic collection (P12-TSK-019) and the payoff (P12-TSK-022) replace this function beside their own facts.
    IF NEW.kind IN ('AUTO_COLLECTION', 'PAYOFF') THEN
        RAISE EXCEPTION 'a % repayment is born only beside its own fact, which its task admits (P12-TSK-019, P12-TSK-022)', NEW.kind;
    END IF;
    -- The Phase 11 gate's R1 from the first table: never a date the record has closed, never after the later of the
    -- latest date the clock showed and the floor.
    value_floor := bnpl.principal_value_floor(NEW.obligation_id);
    IF NEW.value_date < value_floor
            OR NEW.value_date > GREATEST(bnpl.business_date_latest(obligation.servicing_zone), value_floor) THEN
        RAISE EXCEPTION 'a BNPL repayment is value-dated on or after its obligation''s floor and never after today (P12-TSK-013, the Phase 11 gate''s R1)';
    END IF;
    NEW.received_at := clock_timestamp();
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The allocation, extended: HOLD_AS_CREDIT, a billed item only, one line per component, conserving at commit.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE bnpl.bnpl_repayment_allocation DROP CONSTRAINT bnpl_repayment_allocation_target;
-- AllocationLine.Target and AllocationComponent: a due item names its instalment and component; the credit balance names
-- neither. No NOT_DUE_PRINCIPAL: both products hold a remainder as credit (BnplTerms: HOLD_AS_CREDIT), applied at the
-- next billing (F3a) and refunded at closure - early money never pays principal not yet due.
ALTER TABLE bnpl.bnpl_repayment_allocation ADD CONSTRAINT bnpl_repayment_allocation_target CHECK (
    (target = 'DUE_ITEM' AND instalment_id IS NOT NULL AND component IN ('FEES', 'INTEREST', 'PRINCIPAL'))
    OR (target = 'CREDIT_BALANCE' AND instalment_id IS NULL AND component IS NULL));

-- ALLOCATION_ENGINE_V1: two lines of one repayment never pay one component of one item, and what is held is one line.
CREATE UNIQUE INDEX bnpl_repayment_allocation_component_unique
    ON bnpl.bnpl_repayment_allocation (repayment_id, instalment_id, component) WHERE target = 'DUE_ITEM';
CREATE UNIQUE INDEX bnpl_repayment_allocation_held_unique
    ON bnpl.bnpl_repayment_allocation (repayment_id) WHERE target = 'CREDIT_BALANCE';
CREATE INDEX bnpl_repayment_allocation_by_instalment
    ON bnpl.bnpl_repayment_allocation (instalment_id, component) WHERE target = 'DUE_ITEM';

COMMENT ON TABLE bnpl.bnpl_repayment_allocation IS
    'A BNPL repayment''s allocation (P12-TSK-013; extended by P12-TSK-018): one line per billed instalment component paid (DUE_ITEM) or the remainder held as credit (CREDIT_BALANCE, one line), in order - never principal not yet due (HOLD_AS_CREDIT). The lines sum to the repayment and no billed component is ever paid beyond what it billed (every payer''s, bnpl.billing_component_paid), held at commit. INSERT-only (RESTRICTED-FINANCIAL).';

CREATE OR REPLACE FUNCTION bnpl.bnpl_repayment_allocation_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL repayment allocation is immutable from insert, for every role (P12-TSK-013)';
    END IF;
    -- In the repayment's currency and scale, on one of its own obligation's instalments.
    IF NOT EXISTS (SELECT 1 FROM bnpl.bnpl_repayment r WHERE r.id = NEW.repayment_id
                      AND r.currency = NEW.currency AND r.scale = NEW.scale
                      AND (NEW.instalment_id IS NULL OR EXISTS (
                          SELECT 1 FROM bnpl.bnpl_instalment i
                           WHERE i.id = NEW.instalment_id AND i.obligation_id = r.obligation_id))) THEN
        RAISE EXCEPTION 'a BNPL repayment allocation is its repayment''s, in its currency, on its obligation''s instalment (P12-TSK-013)';
    END IF;
    -- A due item is a billed one: what has not been billed is not due, and early money is held (P12-TSK-018).
    IF NEW.target = 'DUE_ITEM'
            AND NOT EXISTS (SELECT 1 FROM bnpl.bnpl_billing b WHERE b.instalment_id = NEW.instalment_id) THEN
        RAISE EXCEPTION 'a BNPL repayment pays only a billed instalment; early money is held as credit (P12-TSK-018, HOLD_AS_CREDIT)';
    END IF;
    RETURN NEW;
END;
$$;

-- At commit, for every writer: no billed component a repayment paid is paid beyond what it billed - with every other
-- payer's (the applications, the other repayments) - so no due account of the obligation goes negative from the rows'
-- side (INV-LND-04 by analogy). The lines' sum is V006's bnpl_repayment_is_allocated_whole.
CREATE OR REPLACE FUNCTION bnpl.bnpl_repayment_allocation_conserves()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM bnpl.bnpl_repayment_allocation l
                 JOIN bnpl.bnpl_billing b ON b.instalment_id = l.instalment_id
                WHERE l.repayment_id = NEW.repayment_id AND l.target = 'DUE_ITEM'
                  AND bnpl.billing_component_paid(b.id, l.component)
                      > bnpl.billing_component_billed(b.id, l.component)) THEN
        RAISE EXCEPTION 'no billed component is ever paid beyond what it billed (P12-TSK-018, INV-LND-04)';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER bnpl_repayment_allocation_conserves
    AFTER INSERT ON bnpl.bnpl_repayment_allocation
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_repayment_allocation_conserves();

-- ---------------------------------------------------------------------------------------------
-- The closure: born in the transaction that brings the last receivable to zero, with the credit balance it refunded.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE bnpl.bnpl_obligation_closure (
    id                        uuid        NOT NULL,
    obligation_id             uuid        NOT NULL,
    reason                    text        NOT NULL,
    currency                  char(3)     NOT NULL,
    scale                     smallint    NOT NULL,
    refund_minor              bigint      NOT NULL,
    refund_journal_entry_id   uuid,
    repayment_id              uuid,
    created_at                timestamptz NOT NULL,
    correlation_id            text        NOT NULL,
    CONSTRAINT bnpl_obligation_closure_pk PRIMARY KEY (id),
    -- An obligation closes once (INV-LIFE-04): beneath B4, the conditional edge and the refund's ledger key
    -- bnpl.closure-refund:<obligation>.
    CONSTRAINT bnpl_obligation_closure_obligation_unique UNIQUE (obligation_id),
    CONSTRAINT bnpl_obligation_closure_refund_entry_unique UNIQUE (refund_journal_entry_id),
    CONSTRAINT bnpl_obligation_closure_obligation_fk FOREIGN KEY (obligation_id) REFERENCES bnpl.bnpl_obligation (id),
    CONSTRAINT bnpl_obligation_closure_repayment_fk FOREIGN KEY (repayment_id) REFERENCES bnpl.bnpl_repayment (id),
    -- BnplClosureReason.
    CONSTRAINT bnpl_obligation_closure_reason_is_known CHECK (
        reason IN ('REPAID', 'PAID_OFF_EARLY', 'REFUNDED', 'REFUNDED_PARTIALLY_REPAID')),
    CONSTRAINT bnpl_obligation_closure_currency_is_the_products CHECK (currency = 'EUR' AND scale BETWEEN 0 AND 9),
    -- A refund's entry exactly when a credit balance was refunded (nothing owed back after closure, INV-LND-08).
    CONSTRAINT bnpl_obligation_closure_refund_shape CHECK (
        refund_minor >= 0 AND (refund_minor > 0) = (refund_journal_entry_id IS NOT NULL)),
    CONSTRAINT bnpl_obligation_closure_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

COMMENT ON TABLE bnpl.bnpl_obligation_closure IS
    'A BNPL obligation''s closure (P12-TSK-018; INV-LND-08 by analogy, BNPL_LIFECYCLES.md section 4.7): born in the transaction that brings its last receivable to zero - a customer repayment (T-rep), or the servicing step after a credit-balance application (T-svc) - with the reason (REPAID; REFUNDED and REFUNDED_PARTIALLY_REPAID P12-TSK-021, PAID_OFF_EARLY P12-TSK-022), the credit balance refunded to the wallet and the refund''s journal entry by value (DR BNPL_CREDIT_BALANCE / CR CUSTOMER_WALLET, key bnpl.closure-refund:<obligation>), and the repayment whose transaction closed it (none for a closure by the servicing step). Explains BNPL_CREDIT_BALANCE''s last debit (the subledger proof, P12-TSK-025). One per obligation; INSERT-only for every role (RESTRICTED-FINANCIAL).';

CREATE OR REPLACE FUNCTION bnpl.bnpl_obligation_closure_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    obligation bnpl.bnpl_obligation%ROWTYPE;
    schedule bnpl.bnpl_schedule%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL closure is immutable from insert, for every role (P12-TSK-018, INV-LIFE-04)';
    END IF;
    -- (B4) The obligation, for every writer; an ACTIVE obligation closing REPAID - a refund's and a payoff's closures are
    -- P12-TSK-021's and -022's, each replacing this function beside its own fact.
    SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id FOR UPDATE;
    IF NOT FOUND OR obligation.status <> 'ACTIVE' OR NEW.reason <> 'REPAID' THEN
        RAISE EXCEPTION 'an ACTIVE BNPL obligation closes REPAID here, and nothing else does (P12-TSK-018, INV-LND-08)';
    END IF;
    SELECT * INTO schedule FROM bnpl.bnpl_schedule s
     WHERE s.obligation_id = NEW.obligation_id
       AND s.schedule_version = (SELECT max(x.schedule_version) FROM bnpl.bnpl_schedule x
                                  WHERE x.obligation_id = NEW.obligation_id);
    IF schedule.id IS NULL OR NEW.currency <> obligation.currency OR NEW.scale <> schedule.scale THEN
        RAISE EXCEPTION 'a BNPL closure refunds in its obligation''s currency and scale (P12-TSK-018)';
    END IF;
    IF NEW.repayment_id IS NOT NULL
            AND NOT EXISTS (SELECT 1 FROM bnpl.bnpl_repayment r
                             WHERE r.id = NEW.repayment_id AND r.obligation_id = NEW.obligation_id) THEN
        RAISE EXCEPTION 'a BNPL closure names a repayment of its own obligation (P12-TSK-018)';
    END IF;
    -- The rows' side of "every receivable zero": every instalment of the latest schedule billed, and every billed
    -- component paid in full by every payer. The ledger's side is the closing transaction's own, under B4.
    IF EXISTS (SELECT 1 FROM bnpl.bnpl_instalment i
                WHERE i.schedule_id = schedule.id
                  AND NOT EXISTS (SELECT 1 FROM bnpl.bnpl_billing b WHERE b.instalment_id = i.id)) THEN
        RAISE EXCEPTION 'a BNPL obligation closes only once every instalment of its schedule is billed (P12-TSK-018, INV-LND-08)';
    END IF;
    IF EXISTS (SELECT 1 FROM bnpl.bnpl_billing b
                 CROSS JOIN (VALUES ('FEES'), ('INTEREST'), ('PRINCIPAL')) AS c (component)
                WHERE b.obligation_id = NEW.obligation_id
                  AND bnpl.billing_component_paid(b.id, c.component)
                      <> bnpl.billing_component_billed(b.id, c.component)) THEN
        RAISE EXCEPTION 'a BNPL obligation closes only once every billed item is paid in full (P12-TSK-018, INV-LND-08)';
    END IF;
    NEW.created_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER bnpl_obligation_closure_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON bnpl.bnpl_obligation_closure
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_obligation_closure_is_born_once();

CREATE TRIGGER bnpl_obligation_closure_is_never_truncated
    BEFORE TRUNCATE ON bnpl.bnpl_obligation_closure
    FOR EACH STATEMENT
    EXECUTE FUNCTION bnpl.acceptance_rows_are_never_truncated();

-- At commit, for every writer: a closure's obligation is CLOSED, for the same reason, in the same transaction.
CREATE OR REPLACE FUNCTION bnpl.bnpl_obligation_closure_closes_its_obligation()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM bnpl.bnpl_obligation o
                    WHERE o.id = NEW.obligation_id AND o.status = 'CLOSED' AND o.closure_reason = NEW.reason) THEN
        RAISE EXCEPTION 'a BNPL closure committed without its obligation CLOSED for the same reason (P12-TSK-018, INV-LND-08)';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER bnpl_obligation_closure_closes_its_obligation
    AFTER INSERT ON bnpl.bnpl_obligation_closure
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_obligation_closure_closes_its_obligation();

-- ---------------------------------------------------------------------------------------------
-- The obligation's edge function, replaced: V008's, plus ACTIVE -> CLOSED beside its closure.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION bnpl.bnpl_obligation_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    purchase bnpl.purchase%ROWTYPE;
    offer bnpl.bnpl_offer%ROWTYPE;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'ACTIVE' OR NEW.closed_at IS NOT NULL OR NEW.closure_reason IS NOT NULL THEN
            RAISE EXCEPTION 'a BNPL obligation is born ACTIVE (P12-TSK-013)';
        END IF;
        -- Born beside its AUTHORISED purchase, for the purchase's party, product, plan and currency and the offer's
        -- decision; its purchase date and zone the purchase's own.
        SELECT * INTO purchase FROM bnpl.purchase p WHERE p.id = NEW.purchase_id;
        IF NOT FOUND OR purchase.status <> 'AUTHORISED' OR purchase.party_id <> NEW.party_id
                OR purchase.product <> NEW.product OR purchase.plan <> NEW.plan OR purchase.currency <> NEW.currency THEN
            RAISE EXCEPTION 'a BNPL obligation is born beside its AUTHORISED purchase, for its party, product, plan and currency (P12-TSK-013)';
        END IF;
        SELECT * INTO offer FROM bnpl.bnpl_offer o WHERE o.id = purchase.offer_id;
        IF offer.decision_id <> NEW.decision_id THEN
            RAISE EXCEPTION 'a BNPL obligation names the decision its offer was made on (P12-TSK-013)';
        END IF;
        NEW.servicing_zone := purchase.servicing_zone;
        NEW.purchase_date := purchase.business_date;
        NEW.opened_at := clock_timestamp();
        -- The servicing sweep's first look is due at once (P12-TSK-015).
        NEW.next_servicing_at := NEW.opened_at;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a BNPL obligation is never deleted (P12-TSK-013, INV-HIST-01)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.purchase_id IS DISTINCT FROM OLD.purchase_id
            OR NEW.agreement_id IS DISTINCT FROM OLD.agreement_id OR NEW.party_id IS DISTINCT FROM OLD.party_id
            OR NEW.decision_id IS DISTINCT FROM OLD.decision_id OR NEW.product IS DISTINCT FROM OLD.product
            OR NEW.plan IS DISTINCT FROM OLD.plan OR NEW.currency IS DISTINCT FROM OLD.currency
            OR NEW.servicing_zone IS DISTINCT FROM OLD.servicing_zone
            OR NEW.purchase_date IS DISTINCT FROM OLD.purchase_date OR NEW.opened_at IS DISTINCT FROM OLD.opened_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a BNPL obligation''s identity, product and origin are frozen from birth (P12-TSK-015)';
    END IF;
    -- The servicing permit alone (P12-TSK-015, BNPL_LIFECYCLES.md section 4.7): an ACTIVE obligation moves its permit and
    -- nothing else - the sweep's claim and a completed servicing step. Never a CLOSED obligation's, never beside another
    -- column.
    IF NEW.status = OLD.status AND OLD.status = 'ACTIVE'
            AND NEW.closed_at IS NOT DISTINCT FROM OLD.closed_at
            AND NEW.closure_reason IS NOT DISTINCT FROM OLD.closure_reason THEN
        RETURN NEW;
    END IF;
    -- The closure (P12-TSK-018): ACTIVE -> CLOSED only beside its bnpl_obligation_closure row born in this transaction for
    -- the same reason (which holds the rows' side of "every receivable zero"), the permit unmoved; the terminal instant
    -- is the database's, after the lock. A refund's (P12-TSK-021) and a payoff's (P12-TSK-022) reasons are admitted by
    -- their closure rows' own triggers.
    IF OLD.status = 'ACTIVE' AND NEW.status = 'CLOSED' THEN
        IF NEW.closure_reason IS NULL OR NEW.next_servicing_at IS DISTINCT FROM OLD.next_servicing_at
                OR NOT EXISTS (SELECT 1 FROM bnpl.bnpl_obligation_closure c
                                WHERE c.obligation_id = OLD.id AND c.reason = NEW.closure_reason) THEN
            RAISE EXCEPTION 'a BNPL obligation is CLOSED only beside its closure, for the same reason (P12-TSK-018, INV-LND-08)';
        END IF;
        NEW.closed_at := clock_timestamp();
        RETURN NEW;
    END IF;
    -- Out of CLOSED nothing, ever (INV-LIFE-04); and no other edge exists.
    RAISE EXCEPTION 'a BNPL obligation moves only its servicing permit, or ACTIVE -> CLOSED beside its closure; % -> % is not an edge (P12-TSK-018, INV-LIFE-04)', OLD.status, NEW.status;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The grants: the closure SELECT and INSERT only; the obligation's status and closure reason beside its permit (the
-- terminal instant is the trigger's - stamped, never granted). Never UPDATE of a fact, never DELETE.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON bnpl.bnpl_obligation_closure TO finapp_app;
GRANT UPDATE (status, closure_reason) ON bnpl.bnpl_obligation TO finapp_app;
