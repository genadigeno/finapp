-- Customer repayment from the wallet and the closure it can bring (P11-TSK-018; PHASE_11_PLAN.md sections 7.1 T-rep, 7.2,
-- 7.4, 12.5 and 12.8 rule 5 and rule 7's refund, LENDING_LIFECYCLES.md sections 4.5 and 4.8; ADR-0095 points 3 and 4,
-- ADR-0090; INV-LND-04, INV-LND-08, INV-LND-01, INV-IDEM-01, INV-CON-01, INV-BAL-04, INV-BAL-05, INV-LIFE-04). The number is
-- V014: V011 is P11-TSK-031's (canonical offer terms format 2), V012 P11-TSK-017's (billing), V013 P11-TSK-021's (draws).
--
-- The borrower repays any amount from their wallet in ONE transaction (T-rep), under the door's idempotency claim
-- (lending.repayment:CUSTOMER:<id>): the loan's row lock (L3), the due state derived under it from the billed items (billed
-- less paid) and checked against the due accounts' ledger balances, ALLOCATION_ENGINE_V1 by the agreement's pinned order,
-- the wallet's ledger row lock (L7) and its available balance judged under it, then posting rule 5 - DR CUSTOMER_WALLET A /
-- CR <due accounts> / CR LOAN_CREDIT_BALANCE rest, aggregated per account - under lending.repayment:<repaymentId>, and
-- beside it:
--
--     repayment             - the money received, born only once it moved: its kind (CUSTOMER here; AUTO_COLLECTION is
--                             P11-TSK-019's, PAYOFF P11-TSK-026's), the requester's key (UNIQUE per loan and kind - the
--                             arbiter behind a claim that has lapsed), the amount in the agreement's currency and scale, the
--                             agreement and allocation engine versions it was allocated under, posting rule 5's journal
--                             entry by value and the value date (the business date of the database clock in the pinned
--                             zone). INSERT-only: its REVERSED status is derived from an approved reversal (P11-TSK-020).
--     repayment_allocation  - its per-item split: ordinal, target (a billed item's component, principal not yet due - a
--                             line's paydown, P11-TSK-023 - or the credit balance), the billed item and component when it
--                             pays one, the amount. At commit the rows sum to the repayment exactly, and no billed component
--                             is ever paid beyond what it billed (lending.billing_component_paid, replaced here to count
--                             every payer - the credit-balance application's rows and these).
--     loan_closure          - the closure, when a transaction brings every receivable to zero: the reason (REPAID here;
--                             PAID_OFF_EARLY is P11-TSK-026's, CLOSED_BY_CUSTOMER P11-TSK-023's), the credit balance
--                             refunded to the wallet and the refund's journal entry (DR LOAN_CREDIT_BALANCE / CR
--                             CUSTOMER_WALLET under lending.closure-refund:<loan>), and the repayment whose transaction
--                             closed it - none when the servicing step did (a credit-balance application at a billing).
--
-- The loan's edge function is replaced to admit ACTIVE -> CLOSED (REPAID) for an instalment loan, only beside its
-- loan_closure row; a deferred check holds every closure to its loan CLOSED at commit. The ledger side - every receivable
-- zero, the credit balance refunded - is the closing transaction's own, under L3 (lending's triggers never read the ledger,
-- P11-TSK-014's rule); the rows' side is held here for every writer: every instalment of the latest schedule billed and
-- every billed component paid in full.
--
-- Held for EVERY writer: repayment UNIQUE (loan_id, kind, request_key) and UNIQUE (journal_entry_id); a repayment only by an
-- ACTIVE instalment loan (a line's is P11-TSK-023's, which replaces this function), under its governing agreement version
-- in its currency and scale and its pinned allocation engine, its value date the business date of its transaction in the
-- pinned zone; repayment_allocation coherent (a billed item and component exactly when it pays one), paying billed items of
-- its own loan in its currency and scale, never principal not yet due on an instalment loan (HOLD_AS_CREDIT, ADR-0095); the
-- rows summing to the repayment and no billed component over-paid, at commit; loan_closure UNIQUE (loan_id) and UNIQUE
-- (refund_journal_entry_id), born only for an ACTIVE instalment loan whose schedule is billed and paid in full, closing its
-- loan at commit. Every new table INSERT-only and never truncated.

-- ---------------------------------------------------------------------------------------------
-- The repayment.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE lending.repayment (
    id                          uuid        NOT NULL,
    loan_id                     uuid        NOT NULL,
    kind                        text        NOT NULL,
    request_key                 text        NOT NULL,
    currency                    char(3)     NOT NULL,
    scale                       smallint    NOT NULL,
    amount_minor                bigint      NOT NULL,
    agreement_id                uuid        NOT NULL,
    agreement_version           integer     NOT NULL,
    allocation_engine_version   smallint    NOT NULL,
    journal_entry_id            uuid        NOT NULL,
    value_date                  date        NOT NULL,
    created_at                  timestamptz NOT NULL,
    correlation_id              text        NOT NULL,
    CONSTRAINT repayment_pk PRIMARY KEY (id),
    -- One repayment per loan, kind and requester's key (INV-IDEM-01): the database arbiter behind the door's claim.
    CONSTRAINT repayment_key_unique UNIQUE (loan_id, kind, request_key),
    -- One repayment per journal entry: the ledger key lending.repayment:<id> gives one entry per repayment.
    CONSTRAINT repayment_entry_unique UNIQUE (journal_entry_id),
    CONSTRAINT repayment_loan_fk FOREIGN KEY (loan_id) REFERENCES lending.loan (id),
    CONSTRAINT repayment_agreement_fk FOREIGN KEY (agreement_id) REFERENCES lending.loan_agreement (id),
    CONSTRAINT repayment_kind_is_known CHECK (kind IN ('CUSTOMER', 'AUTO_COLLECTION', 'PAYOFF')),
    CONSTRAINT repayment_key_bounded CHECK (char_length(request_key) BETWEEN 1 AND 200),
    CONSTRAINT repayment_currency_is_the_products CHECK (currency = 'EUR' AND scale BETWEEN 0 AND 9),
    CONSTRAINT repayment_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT repayment_versions_numbered CHECK (agreement_version >= 1 AND allocation_engine_version >= 1),
    CONSTRAINT repayment_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

CREATE INDEX repayment_by_loan ON lending.repayment (loan_id, created_at);

COMMENT ON TABLE lending.repayment IS
    'Money received for a loan (P11-TSK-018; ADR-0095 point 3, PHASE_11_PLAN.md section 12.5, posting rule 5): born only once it moved - the kind (CUSTOMER; AUTO_COLLECTION P11-TSK-019, PAYOFF P11-TSK-026), the requester''s key (UNIQUE per loan and kind), the amount in the agreement''s currency and scale, the agreement and allocation engine versions it was allocated under, posting rule 5''s journal entry by value (DR CUSTOMER_WALLET / CR <due accounts> / CR LOAN_CREDIT_BALANCE rest, key lending.repayment:<id>) and its value date. Its repayment_allocation rows sum to it at commit. REVERSED is derived from an approved reversal (P11-TSK-020), never written here. Explains the due accounts'' and the credit balance''s credits with the applications (the subledger proof, P11-TSK-029). INSERT-only for every role. RESTRICTED-FINANCIAL.';

CREATE OR REPLACE FUNCTION lending.repayment_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    account lending.loan%ROWTYPE;
    zone text;
    agreement lending.loan_agreement%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a repayment is immutable from insert, for every role (P11-TSK-018, INV-LND-04)';
    END IF;
    -- An ACTIVE instalment loan, and its pinned servicing zone. A CLOSED or CANCELLED account takes no money (INV-LND-08); a
    -- line's repayment is P11-TSK-023's, which replaces this function.
    SELECT l.* INTO account FROM lending.loan l WHERE l.id = NEW.loan_id;
    SELECT t.servicing_zone INTO zone
      FROM lending.loan l
      JOIN lending.loan_offer o ON o.id = l.offer_id
      JOIN lending.loan_terms t ON t.terms_version_id = o.terms_version_id
     WHERE l.id = NEW.loan_id;
    IF account.id IS NULL OR account.kind <> 'INSTALMENT' OR account.status <> 'ACTIVE' OR zone IS NULL THEN
        RAISE EXCEPTION 'a repayment is received only by an ACTIVE instalment loan (P11-TSK-018, INV-LND-08)';
    END IF;
    -- Under the loan's governing agreement version - its highest - in its currency and scale, by its pinned allocation
    -- engine (INV-LND-09).
    SELECT a.* INTO agreement FROM lending.loan_agreement a
     WHERE a.id = NEW.agreement_id AND a.loan_id = NEW.loan_id AND a.version = NEW.agreement_version;
    IF agreement.id IS NULL
            OR NEW.agreement_version <> (SELECT max(x.version) FROM lending.loan_agreement x WHERE x.loan_id = NEW.loan_id)
            OR NEW.currency <> agreement.currency OR NEW.scale <> agreement.scale
            OR NEW.allocation_engine_version <> agreement.allocation_engine_version THEN
        RAISE EXCEPTION 'a repayment names its loan''s governing agreement version, its currency and scale and its pinned allocation engine (P11-TSK-018, INV-LND-09)';
    END IF;
    -- The value date is this transaction's business date in the pinned servicing zone (ADR-0094 point 8, INV-LND-10): read
    -- by the repayment after its locks, so between the transaction's start and this statement.
    IF NEW.value_date < lending.business_date(transaction_timestamp(), zone)
            OR NEW.value_date > lending.business_date(statement_timestamp(), zone) THEN
        RAISE EXCEPTION 'a repayment''s value date is the business date of the database clock in its pinned zone (P11-TSK-018, INV-LND-10)';
    END IF;
    NEW.created_at := statement_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER repayment_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON lending.repayment
    FOR EACH ROW
    EXECUTE FUNCTION lending.repayment_is_born_once();

CREATE TRIGGER repayment_is_never_truncated
    BEFORE TRUNCATE ON lending.repayment
    FOR EACH STATEMENT
    EXECUTE FUNCTION lending.loan_is_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The repayment's allocation rows.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE lending.repayment_allocation (
    id               uuid     NOT NULL,
    repayment_id     uuid     NOT NULL,
    ordinal          smallint NOT NULL,
    target           text     NOT NULL,
    billing_id       uuid,
    component        text,
    currency         char(3)  NOT NULL,
    scale            smallint NOT NULL,
    amount_minor     bigint   NOT NULL,
    CONSTRAINT repayment_allocation_pk PRIMARY KEY (id),
    CONSTRAINT repayment_allocation_ordinal_unique UNIQUE (repayment_id, ordinal),
    CONSTRAINT repayment_allocation_repayment_fk FOREIGN KEY (repayment_id) REFERENCES lending.repayment (id),
    CONSTRAINT repayment_allocation_billing_fk FOREIGN KEY (billing_id) REFERENCES lending.instalment_billing (id),
    CONSTRAINT repayment_allocation_target_is_known CHECK (target IN ('DUE_ITEM', 'NOT_DUE_PRINCIPAL', 'CREDIT_BALANCE')),
    -- A billed item and its component exactly when the line pays one (AllocationLine).
    CONSTRAINT repayment_allocation_target_coherent CHECK (
        ((target = 'DUE_ITEM') = (billing_id IS NOT NULL)) AND ((target = 'DUE_ITEM') = (component IS NOT NULL))),
    CONSTRAINT repayment_allocation_component_is_known CHECK (
        component IS NULL OR component IN ('FEES', 'INTEREST', 'PRINCIPAL')),
    CONSTRAINT repayment_allocation_currency_is_the_products CHECK (currency = 'EUR' AND scale BETWEEN 0 AND 9),
    CONSTRAINT repayment_allocation_shape CHECK (ordinal >= 1 AND amount_minor > 0)
);

-- One line per item and component, and one per overflow target (AllocationEngineV1: two lines never pay one component).
CREATE UNIQUE INDEX repayment_allocation_component_unique
    ON lending.repayment_allocation (repayment_id, billing_id, component) WHERE target = 'DUE_ITEM';
CREATE UNIQUE INDEX repayment_allocation_overflow_unique
    ON lending.repayment_allocation (repayment_id, target) WHERE target <> 'DUE_ITEM';
CREATE INDEX repayment_allocation_by_billing
    ON lending.repayment_allocation (billing_id, component) WHERE target = 'DUE_ITEM';

COMMENT ON TABLE lending.repayment_allocation IS
    'One line of a repayment''s allocation (P11-TSK-018; ADR-0095 points 1 and 3): what it paid of one billed item''s component (FEES, INTEREST or PRINCIPAL), of principal not yet due (a line''s paydown, P11-TSK-023) or held as the credit balance - the per-item split posting rule 5''s entry aggregates per account. INSERT-only for every role; with its repayment''s other rows it sums to the repayment, and no billed component is ever paid beyond what it billed, at commit (INV-LND-04). A reversal negates rows, never deletes them (P11-TSK-020). RESTRICTED-FINANCIAL.';

CREATE OR REPLACE FUNCTION lending.repayment_allocation_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    repayment lending.repayment%ROWTYPE;
    loan_kind text;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a repayment allocation is immutable from insert, for every role (P11-TSK-018, INV-LND-04)';
    END IF;
    SELECT r.* INTO repayment FROM lending.repayment r WHERE r.id = NEW.repayment_id;
    IF repayment.id IS NULL OR NEW.currency <> repayment.currency OR NEW.scale <> repayment.scale THEN
        RAISE EXCEPTION 'a repayment allocation is a line of its repayment, in its currency and scale (P11-TSK-018)';
    END IF;
    -- A billed item of the repayment's own loan.
    IF NEW.target = 'DUE_ITEM' AND NOT EXISTS (SELECT 1 FROM lending.instalment_billing b
                                                WHERE b.id = NEW.billing_id AND b.loan_id = repayment.loan_id) THEN
        RAISE EXCEPTION 'a repayment allocation pays a billed item of its repayment''s own loan (P11-TSK-018)';
    END IF;
    -- An instalment loan holds the remainder as credit (HOLD_AS_CREDIT, ADR-0095 point 2): an overpayment never reduces its
    -- principal - a prepayment is its own command (P11-TSK-028).
    SELECT l.kind INTO loan_kind FROM lending.loan l WHERE l.id = repayment.loan_id;
    IF NEW.target = 'NOT_DUE_PRINCIPAL' AND loan_kind = 'INSTALMENT' THEN
        RAISE EXCEPTION 'an instalment loan''s repayment never pays principal not yet due (HOLD_AS_CREDIT, P11-TSK-018, ADR-0095)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER repayment_allocation_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON lending.repayment_allocation
    FOR EACH ROW
    EXECUTE FUNCTION lending.repayment_allocation_is_born_once();

CREATE TRIGGER repayment_allocation_is_never_truncated
    BEFORE TRUNCATE ON lending.repayment_allocation
    FOR EACH STATEMENT
    EXECUTE FUNCTION lending.loan_is_never_truncated();

-- What a billed item's component has had paid - every allocation that pays a billed item: the credit-balance
-- application's rows (P11-TSK-017) and the repayments' (P11-TSK-018). V012's never-over-paid rule, the rule below, the
-- closure's and the schedule read's paid status all read this one sum, so each covers every payer. P11-TSK-020's negating
-- rows join it when they land.
CREATE OR REPLACE FUNCTION lending.billing_component_paid(billing uuid, paid_component text)
    RETURNS bigint
    LANGUAGE sql
    STABLE
AS $$
    SELECT ((SELECT coalesce(sum(a.amount_minor), 0) FROM lending.credit_balance_allocation a
              WHERE a.billing_id = billing AND a.component = paid_component)
          + (SELECT coalesce(sum(r.amount_minor), 0) FROM lending.repayment_allocation r
              WHERE r.target = 'DUE_ITEM' AND r.billing_id = billing AND r.component = paid_component))::bigint
$$;

-- At commit, for every writer (INV-LND-04): a repayment's rows sum to it exactly, and no billed component it paid is paid
-- beyond what it billed - so no due account of the loan ever goes negative from the rows' side.
CREATE OR REPLACE FUNCTION lending.repayment_conserves_at_commit()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    repayment uuid;
    allocated bigint;
BEGIN
    -- NEW is a different row type per table: each branch names only its own table's field.
    IF TG_TABLE_NAME = 'repayment' THEN
        repayment := NEW.id;
    ELSE
        repayment := NEW.repayment_id;
    END IF;
    SELECT coalesce(sum(a.amount_minor), 0) INTO allocated FROM lending.repayment_allocation a
     WHERE a.repayment_id = repayment;
    IF allocated <> (SELECT r.amount_minor FROM lending.repayment r WHERE r.id = repayment) THEN
        RAISE EXCEPTION 'a repayment''s allocations sum to it exactly (P11-TSK-018, INV-LND-04)';
    END IF;
    IF EXISTS (SELECT 1 FROM lending.repayment_allocation a
                WHERE a.repayment_id = repayment AND a.target = 'DUE_ITEM'
                  AND lending.billing_component_paid(a.billing_id, a.component)
                      > lending.billing_component_billed(a.billing_id, a.component)) THEN
        RAISE EXCEPTION 'no billed component is ever paid beyond what it billed (P11-TSK-018, INV-LND-04)';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER repayment_conserves
    AFTER INSERT ON lending.repayment
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION lending.repayment_conserves_at_commit();

CREATE CONSTRAINT TRIGGER repayment_allocation_conserves
    AFTER INSERT ON lending.repayment_allocation
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION lending.repayment_conserves_at_commit();

-- ---------------------------------------------------------------------------------------------
-- The closure.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE lending.loan_closure (
    id                        uuid        NOT NULL,
    loan_id                   uuid        NOT NULL,
    reason                    text        NOT NULL,
    currency                  char(3)     NOT NULL,
    scale                     smallint    NOT NULL,
    refund_minor              bigint      NOT NULL,
    refund_journal_entry_id   uuid,
    repayment_id              uuid,
    created_at                timestamptz NOT NULL,
    correlation_id            text        NOT NULL,
    CONSTRAINT loan_closure_pk PRIMARY KEY (id),
    -- A loan closes once (INV-LIFE-04): beneath L3, the conditional edge and the refund's ledger key.
    CONSTRAINT loan_closure_loan_unique UNIQUE (loan_id),
    CONSTRAINT loan_closure_refund_entry_unique UNIQUE (refund_journal_entry_id),
    CONSTRAINT loan_closure_loan_fk FOREIGN KEY (loan_id) REFERENCES lending.loan (id),
    CONSTRAINT loan_closure_repayment_fk FOREIGN KEY (repayment_id) REFERENCES lending.repayment (id),
    CONSTRAINT loan_closure_reason_is_known CHECK (reason IN ('REPAID', 'PAID_OFF_EARLY', 'CLOSED_BY_CUSTOMER')),
    CONSTRAINT loan_closure_currency_is_the_products CHECK (currency = 'EUR' AND scale BETWEEN 0 AND 9),
    -- A refund's entry exactly when a credit balance was refunded (nothing owed back after closure, INV-LND-08).
    CONSTRAINT loan_closure_refund_shape CHECK (
        refund_minor >= 0 AND (refund_minor > 0) = (refund_journal_entry_id IS NOT NULL)),
    CONSTRAINT loan_closure_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

COMMENT ON TABLE lending.loan_closure IS
    'A loan''s closure (P11-TSK-018; INV-LND-08, LENDING_LIFECYCLES.md section 4.5): born in the transaction that brings its last receivable to zero - a repayment, or the servicing step after a credit-balance application - with the reason (REPAID; PAID_OFF_EARLY P11-TSK-026, CLOSED_BY_CUSTOMER P11-TSK-023), the credit balance refunded to the wallet and the refund''s journal entry by value (DR LOAN_CREDIT_BALANCE / CR CUSTOMER_WALLET, key lending.closure-refund:<loan>), and the repayment whose transaction closed it (none for a closure by the servicing step). Explains LOAN_CREDIT_BALANCE''s last debit (the subledger proof, P11-TSK-029). One per loan; INSERT-only for every role. RESTRICTED-FINANCIAL.';

CREATE OR REPLACE FUNCTION lending.loan_closure_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    account lending.loan%ROWTYPE;
    agreement lending.loan_agreement%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a closure is immutable from insert, for every role (P11-TSK-018, INV-LIFE-04)';
    END IF;
    -- An ACTIVE instalment loan, closing REPAID: a payoff's closure is P11-TSK-026's and a line's P11-TSK-023's - each
    -- replaces this function beside its own fact.
    SELECT l.* INTO account FROM lending.loan l WHERE l.id = NEW.loan_id;
    IF account.id IS NULL OR account.kind <> 'INSTALMENT' OR account.status <> 'ACTIVE' OR NEW.reason <> 'REPAID' THEN
        RAISE EXCEPTION 'an ACTIVE instalment loan closes REPAID here, and nothing else does (P11-TSK-018, INV-LND-08)';
    END IF;
    SELECT a.* INTO agreement FROM lending.loan_agreement a
     WHERE a.loan_id = NEW.loan_id
       AND a.version = (SELECT max(x.version) FROM lending.loan_agreement x WHERE x.loan_id = NEW.loan_id);
    IF NEW.currency <> agreement.currency OR NEW.scale <> agreement.scale THEN
        RAISE EXCEPTION 'a closure refunds in its loan''s currency and scale (P11-TSK-018)';
    END IF;
    IF NEW.repayment_id IS NOT NULL
            AND NOT EXISTS (SELECT 1 FROM lending.repayment r WHERE r.id = NEW.repayment_id AND r.loan_id = NEW.loan_id) THEN
        RAISE EXCEPTION 'a closure names a repayment of its own loan (P11-TSK-018)';
    END IF;
    -- The rows' side of "every receivable zero": every instalment of the latest schedule billed, and every billed component
    -- paid in full. The ledger's side is the closing transaction's own, under L3 (lending's triggers never read the ledger).
    IF NOT EXISTS (SELECT 1 FROM lending.repayment_schedule s WHERE s.loan_id = NEW.loan_id)
            OR EXISTS (SELECT 1 FROM lending.loan_instalment i
                        WHERE i.schedule_id = (SELECT s.id FROM lending.repayment_schedule s WHERE s.loan_id = NEW.loan_id
                                                ORDER BY s.schedule_version DESC LIMIT 1)
                          AND NOT EXISTS (SELECT 1 FROM lending.instalment_billing b WHERE b.instalment_id = i.id)) THEN
        RAISE EXCEPTION 'a loan closes only once every instalment of its schedule is billed (P11-TSK-018, INV-LND-08)';
    END IF;
    IF EXISTS (SELECT 1 FROM lending.instalment_billing b
                 CROSS JOIN (VALUES ('FEES'), ('INTEREST'), ('PRINCIPAL')) AS c (component)
                WHERE b.loan_id = NEW.loan_id
                  AND lending.billing_component_paid(b.id, c.component)
                      <> lending.billing_component_billed(b.id, c.component)) THEN
        RAISE EXCEPTION 'a loan closes only once every billed item is paid in full (P11-TSK-018, INV-LND-08)';
    END IF;
    NEW.created_at := statement_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER loan_closure_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON lending.loan_closure
    FOR EACH ROW
    EXECUTE FUNCTION lending.loan_closure_is_born_once();

CREATE TRIGGER loan_closure_is_never_truncated
    BEFORE TRUNCATE ON lending.loan_closure
    FOR EACH STATEMENT
    EXECUTE FUNCTION lending.loan_is_never_truncated();

-- At commit, for every writer: a closure's loan is CLOSED, for the same reason, in the same transaction.
CREATE OR REPLACE FUNCTION lending.loan_closure_closes_its_loan()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM lending.loan l
                    WHERE l.id = NEW.loan_id AND l.status = 'CLOSED' AND l.closure_reason = NEW.reason) THEN
        RAISE EXCEPTION 'a closure committed without its loan CLOSED for the same reason (P11-TSK-018, INV-LND-08)';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER loan_closure_closes_its_loan
    AFTER INSERT ON lending.loan_closure
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION lending.loan_closure_closes_its_loan();

-- ---------------------------------------------------------------------------------------------
-- The loan's edge function, replaced: V010's edges, plus ACTIVE -> CLOSED (REPAID) beside the closure.
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
        IF NOT FOUND OR offer.status <> 'OFFERED' OR offer.expires_at <= statement_timestamp()
                OR offer.party_id <> NEW.party_id OR offer.product <> NEW.product
                OR offer.decision_id <> NEW.decision_id THEN
            RAISE EXCEPTION 'a loan is born for its own party''s OFFERED, unexpired offer of the same product, on its decision (P11-TSK-012)';
        END IF;
        SELECT t.currency INTO offered_currency FROM lending.loan_offer_terms t WHERE t.offer_id = NEW.offer_id;
        IF offered_currency IS DISTINCT FROM NEW.currency THEN
            RAISE EXCEPTION 'a loan is held in its offer''s currency (P11-TSK-012)';
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
    -- The closure (P11-TSK-018): an instalment loan ACTIVE -> CLOSED (REPAID), only beside its loan_closure row born in
    -- this transaction for the same reason (which holds the rows' side of "every receivable zero"); the terminal instant is
    -- the trigger's. A payoff's PAID_OFF_EARLY (P11-TSK-026) and a line's closure (P11-TSK-023) replace this function.
    IF OLD.status = 'ACTIVE' AND NEW.status = 'CLOSED' AND OLD.kind = 'INSTALMENT' THEN
        IF NEW.closure_reason IS DISTINCT FROM 'REPAID' OR NEW.cancellation_reason IS NOT NULL
                OR NOT EXISTS (SELECT 1 FROM lending.loan_closure c
                                WHERE c.loan_id = OLD.id AND c.reason = NEW.closure_reason) THEN
            RAISE EXCEPTION 'an instalment loan is CLOSED (REPAID) only beside its closure (P11-TSK-018, INV-LND-08)';
        END IF;
        NEW.closed_at := statement_timestamp();
        RETURN NEW;
    END IF;
    -- Every other edge is a later task's: a line's closure request and closure (P11-TSK-023), a payoff (P11-TSK-026) - each
    -- replaces this function and admits its edge beside its own fact. Out of CLOSED and CANCELLED nothing, ever
    -- (INV-LIFE-04); ACTIVE -> CANCELLED and ACTIVE -> PENDING_DISBURSEMENT never (LENDING_LIFECYCLES.md section 4.5).
    RAISE EXCEPTION 'a loan does not move here; % -> % is not an edge until its task admits it (P11-TSK-018)', OLD.status, NEW.status;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The grants: the facts SELECT and INSERT only; the loan's closure reason beside its status (the terminal instant is the
-- trigger's - stamped, never granted). Never UPDATE of a fact, never DELETE.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON lending.repayment TO finapp_app;
GRANT SELECT, INSERT ON lending.repayment_allocation TO finapp_app;
GRANT SELECT, INSERT ON lending.loan_closure TO finapp_app;
GRANT UPDATE (closure_reason) ON lending.loan TO finapp_app;
