-- Merchant refunds, latest instalments first (P12-TSK-021; owner decisions B3 and B7, ADR-0105; PHASE_12_PLAN.md
-- sections 5.3-5.7, 7.1 T-ref, 7.2, 12.6 and 12.11 F5/F5a; BNPL_LIFECYCLES.md sections 4.5-4.9; INV-BNPL-05, INV-BNPL-06,
-- INV-BNPL-07, INV-BNPL-09, INV-MER-07 as amended, INV-HIST-04, INV-IDEM-01, INV-LIFE-04).
--
-- bnpl V012 is this task's - renumbered from the plan's V013 by the Phase 12 wave D coordinator, because P12-TSK-021
-- started before P12-TSK-020 and Flyway runs with out-of-order off (P12-TSK-020 takes V013; V011 is P12-TSK-019's).
--
-- A merchant (over its key, the BNPL refund scope) or an operator (the door beside it) refunds R of a BNPL purchase. In ONE
-- transaction (BnplRefunds, T-ref): the purchase FOR UPDATE (B5) - the refund bound's arbiter, R <= G - refunded; the
-- obligation FOR UPDATE (B4); the due state derived under it; REFUND_ALLOCATION_ENGINE_V1 splits R latest instalment first
-- into unbilled principal cancelled (CR BNPL_PRINCIPAL), billed-unpaid principal cancelled (CR BNPL_PRINCIPAL_DUE) and the
-- excess E owed to the customer (CR CUSTOMER_WALLET, or BNPL_CREDIT_BALANCE when the wallet cannot be credited); the fee
-- returned r_f by merchant's cumulative arithmetic; schedule and agreement v n+1 when an unbilled instalment is cancelled
-- or shortened; the payable and the wallet as one pair (P); L8; one entry F5 + F5a (DR MERCHANT_PAYABLE R, DR
-- BNPL_MERCHANT_FEE_INCOME r_f / CR the receivables, CR the excess, CR MERCHANT_PAYABLE r_f; the releases beside them)
-- under bnpl.refund:<refund>; the rows below; the purchase FULLY_REFUNDED when the refunds total the gross; the closure
-- when every receivable is zero. In this migration:
--
--     bnpl_refund             - the refund, born APPLIED (a row exists only once its effects posted): the purchase, its
--                               obligation and merchant by value, the door and who asked under which key (UNIQUE
--                               (merchant_id, requested_by, refund_key) - the arbiter behind a lapsed claim), R, what was
--                               refunded before it, the fee returned and the payable debit (R = r_f + debit), the principal
--                               cancelled unbilled and billed and the excess (R = cancelled + E, INV-BNPL-06), where E went,
--                               the interest released, the allocation engine, the agreement and schedule versions in force
--                               after it, the reason (RESTRICTED-PII, screened), the entry by value. Its trigger re-takes
--                               B5 then B4 for every writer and holds the bound: R <= G - Sigma refunds (INV-BNPL-07) and
--                               Sigma payable debits <= the financed net (the B7 bound, INV-MER-07 as amended). INSERT-only.
--     bnpl_refund_adjustment  - one row per instalment the refund touched: the unbilled or billed-unpaid principal it
--                               cancelled, the billed interest it released, whether it cancelled the instalment's whole
--                               unpaid principal. A deferred check: the rows sum to the refund's cancellations and release
--                               (so Sigma adjustments + E = R at commit), and no billed component is paid beyond what it
--                               billed.
--
-- Replaced, each the body verbatim but for what is said:
--
--     bnpl.billing_component_paid           - + the refunds' cancellations of billed principal and releases of billed
--                                             interest (V008's one definition; "paid" here means settled, by any payer or
--                                             by a refund's cancellation), so a cancelled billed item reads settled.
--     bnpl.bnpl_billing_is_born_once        - V008's, but "every earlier instalment of the version billed" judged by its
--                                             due date (a billing is UNIQUE (obligation_id, value_date) and dated its
--                                             instalment's due date): schedule v n+1 carries the billed instalments as new
--                                             rows beside the billings that name v n's.
--     bnpl.bnpl_schedule_is_born_once,      - version n+1 admitted beside its refund, from the purchase date (the
--     bnpl.bnpl_schedule_conserves,           P12-TSK-017 V009 rule kept), every billed instalment kept as its billing,
--     bnpl.bnpl_instalment_is_born_once       and the principal still to bill falling by exactly the refund's unbilled
--                                             cancellation; a billed copy may carry zero principal (a pay-in-N billing of
--                                             interest alone); an empty version when nothing is left to bill.
--     bnpl.bnpl_agreement_is_born_once      - version n+1 a copy of version n's terms beside its refund and the schedule
--                                             version it governs (INV-HIST-04).
--     bnpl.purchase_permits_only_machine_edges - FINANCED -> FULLY_REFUNDED when the refunds total the gross.
--     bnpl.bnpl_obligation_closure_is_born_once - REFUNDED (the purchase FULLY_REFUNDED) and REFUNDED_PARTIALLY_REPAID
--                                             (beside a refund of the purchase, not yet fully refunded) admitted beside
--                                             REPAID; every instalment billed judged by due date. PAID_OFF_EARLY stays
--                                             P12-TSK-022's.
--
-- No bnpl trigger reads a ledger table (the P12-TSK-013 refinement): the entry's lines and their agreement with the rows
-- are the one transaction's and the three-way proof's (P12-TSK-025). No cross-schema foreign key (ADR-0029): the merchant
-- and the entry are named by value. Every instant the database's, clock_timestamp() after the act's locks.

-- ---------------------------------------------------------------------------------------------
-- The refund: born APPLIED, INSERT-only.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE bnpl.bnpl_refund (
    id                              uuid        NOT NULL,
    purchase_id                     uuid        NOT NULL,
    obligation_id                   uuid        NOT NULL,
    merchant_id                     uuid        NOT NULL,
    door                            text        NOT NULL,
    requested_by                    text        NOT NULL,
    refund_key                      text        NOT NULL,
    currency                        char(3)     NOT NULL,
    scale                           smallint    NOT NULL,
    amount_minor                    bigint      NOT NULL,
    refunded_before_minor           bigint      NOT NULL,
    fee_returned_minor              bigint      NOT NULL,
    payable_debit_minor             bigint      NOT NULL,
    cancelled_unbilled_minor        bigint      NOT NULL,
    cancelled_billed_minor          bigint      NOT NULL,
    excess_minor                    bigint      NOT NULL,
    excess_to                       text        NOT NULL,
    interest_accrued_released_minor bigint      NOT NULL,
    interest_due_released_minor     bigint      NOT NULL,
    allocation_engine               text        NOT NULL,
    agreement_id                    uuid        NOT NULL,
    agreement_version               integer     NOT NULL,
    schedule_version                integer     NOT NULL,
    reason                          text        NOT NULL,
    journal_entry_id                uuid        NOT NULL,
    value_date                      date        NOT NULL,
    created_at                      timestamptz NOT NULL,
    correlation_id                  text        NOT NULL,
    CONSTRAINT bnpl_refund_pk PRIMARY KEY (id),
    -- One refund per requester's key at a merchant (INV-IDEM-01): beneath the door's claim, the arbiter behind a lapsed
    -- one - the merchant's own key (requested_by MERCHANT:<merchant>) or an operator's (EMPLOYEE:<id>).
    CONSTRAINT bnpl_refund_request_unique UNIQUE (merchant_id, requested_by, refund_key),
    -- One entry per refund: the ledger key bnpl.refund:<refund> beneath it.
    CONSTRAINT bnpl_refund_journal_entry_unique UNIQUE (journal_entry_id),
    CONSTRAINT bnpl_refund_purchase_fk FOREIGN KEY (purchase_id) REFERENCES bnpl.purchase (id),
    CONSTRAINT bnpl_refund_obligation_fk FOREIGN KEY (obligation_id) REFERENCES bnpl.bnpl_obligation (id),
    CONSTRAINT bnpl_refund_agreement_fk FOREIGN KEY (agreement_id, agreement_version)
        REFERENCES bnpl.bnpl_agreement (agreement_id, version),
    CONSTRAINT bnpl_refund_door_is_known CHECK (door IN ('MERCHANT', 'OPERATOR')),
    CONSTRAINT bnpl_refund_requester_shape CHECK (
        (door = 'MERCHANT' AND requested_by = 'MERCHANT:' || merchant_id::text)
        OR (door = 'OPERATOR' AND requested_by ~ '^EMPLOYEE:.{1,200}$')),
    CONSTRAINT bnpl_refund_key_bounded CHECK (char_length(refund_key) BETWEEN 1 AND 200),
    CONSTRAINT bnpl_refund_currency_is_the_products CHECK (currency = 'EUR' AND scale BETWEEN 0 AND 9),
    -- INV-BNPL-06 on the row: R is the fee returned plus the payable's debit, and the principal cancelled plus the excess.
    CONSTRAINT bnpl_refund_conserves CHECK (
        amount_minor > 0 AND refunded_before_minor >= 0 AND fee_returned_minor >= 0 AND payable_debit_minor >= 0
        AND cancelled_unbilled_minor >= 0 AND cancelled_billed_minor >= 0 AND excess_minor >= 0
        AND interest_accrued_released_minor >= 0 AND interest_due_released_minor >= 0
        AND amount_minor = fee_returned_minor + payable_debit_minor
        AND amount_minor = cancelled_unbilled_minor + cancelled_billed_minor + excess_minor),
    -- Where the excess went: the wallet (A12), the credit balance while the wallet cannot be credited (OQ-13), or nowhere.
    CONSTRAINT bnpl_refund_excess_to_is_known CHECK (
        (excess_minor = 0 AND excess_to = 'NONE')
        OR (excess_minor > 0 AND excess_to IN ('WALLET', 'CREDIT_BALANCE'))),
    CONSTRAINT bnpl_refund_allocation_engine CHECK (allocation_engine = 'REFUND_ALLOCATION_ENGINE_V1'),
    CONSTRAINT bnpl_refund_versions CHECK (agreement_version >= 1 AND schedule_version >= 1),
    -- The reason a person wrote: required, bounded, never an instrument's shape (RESTRICTED-PII, INV-AUD-02).
    CONSTRAINT bnpl_refund_reason_bounded CHECK (char_length(reason) BETWEEN 1 AND 1000),
    CONSTRAINT bnpl_refund_reason_no_instrument_shape CHECK (NOT bnpl.holds_instrument_shape(reason)),
    CONSTRAINT bnpl_refund_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

COMMENT ON TABLE bnpl.bnpl_refund IS
    'A BNPL refund (P12-TSK-021; owner decisions B3 and B7, ADR-0105; BNPL_LIFECYCLES.md section 4.9), born APPLIED - a row exists only once its effects posted: R of the purchase refunded by the merchant over its key (door MERCHANT) or by an operator (door OPERATOR) under their idempotency key (UNIQUE (merchant_id, requested_by, refund_key)); split latest instalment first by REFUND_ALLOCATION_ENGINE_V1 into unbilled principal cancelled (CR BNPL_PRINCIPAL), billed-unpaid principal cancelled (CR BNPL_PRINCIPAL_DUE) and the excess owed to the customer (CR CUSTOMER_WALLET, or BNPL_CREDIT_BALANCE while the wallet cannot be credited), R = cancelled + excess (INV-BNPL-06); the merchant fee returned by the cumulative method (cum(before + R) - cum(before)) and the payable debited R - r_f, Sigma over the purchase''s refunds <= G (INV-BNPL-07) and Sigma payable debits <= the financed net (B7, INV-MER-07 as amended); the PN interest released (A11); the agreement and schedule versions in force after it; the reason (RESTRICTED-PII); the entry F5/F5a (bnpl.refund:<refund>) by value. INSERT-only for every role (RESTRICTED-FINANCIAL).';

CREATE INDEX bnpl_refund_by_purchase ON bnpl.bnpl_refund (purchase_id, created_at);
CREATE INDEX bnpl_refund_by_merchant ON bnpl.bnpl_refund (merchant_id, created_at);

CREATE OR REPLACE FUNCTION bnpl.bnpl_refund_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    purchase bnpl.purchase%ROWTYPE;
    obligation bnpl.bnpl_obligation%ROWTYPE;
    financing bnpl.merchant_financing%ROWTYPE;
    refunded bigint;
    debited bigint;
    returned bigint;
    value_floor date;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL refund is immutable from insert, for every role (P12-TSK-021, INV-HIST-01)';
    END IF;
    -- (B5) then (B4), for every writer: the bound and the cancellations are judged under the locks every refund and
    -- every dispute holds (T-ref holds both already; re-taking them is free).
    SELECT * INTO purchase FROM bnpl.purchase p WHERE p.id = NEW.purchase_id FOR UPDATE;
    SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.purchase_id = NEW.purchase_id FOR UPDATE;
    SELECT * INTO financing FROM bnpl.merchant_financing f WHERE f.purchase_id = NEW.purchase_id;
    IF purchase.id IS NULL OR purchase.status <> 'FINANCED' OR purchase.merchant_id <> NEW.merchant_id
            OR purchase.currency <> NEW.currency OR financing.scale <> NEW.scale
            OR obligation.id IS DISTINCT FROM NEW.obligation_id THEN
        RAISE EXCEPTION 'a BNPL refund refunds a FINANCED purchase of its merchant, in its currency and scale, against its own obligation (P12-TSK-021, INV-BNPL-07)';
    END IF;
    -- INV-BNPL-07's second rank: what was refunded before, exactly, and never more than the gross in all.
    SELECT coalesce(sum(r.amount_minor), 0), coalesce(sum(r.payable_debit_minor), 0), coalesce(sum(r.fee_returned_minor), 0)
      INTO refunded, debited, returned
      FROM bnpl.bnpl_refund r WHERE r.purchase_id = NEW.purchase_id;
    IF NEW.refunded_before_minor <> refunded OR refunded + NEW.amount_minor > financing.gross_minor THEN
        RAISE EXCEPTION 'a BNPL purchase''s refunds never total more than its gross (P12-TSK-021, INV-BNPL-07)';
    END IF;
    -- B7's bound (INV-MER-07 as amended): the purchase's refunds debit the payable by at most what its financing credited
    -- it, and return at most its fee.
    IF debited + NEW.payable_debit_minor > financing.net_minor OR returned + NEW.fee_returned_minor > financing.fee_minor THEN
        RAISE EXCEPTION 'a BNPL purchase''s refunds take back at most its financed net and return at most its fee (P12-TSK-021, B7, INV-MER-07)';
    END IF;
    -- Under the agreement version in force (the obligation's highest - a v n+1 born before this row in the transaction).
    IF NEW.agreement_id <> obligation.agreement_id
            OR NEW.agreement_version <> (SELECT max(x.version) FROM bnpl.bnpl_agreement x
                                          WHERE x.obligation_id = obligation.id)
            OR NEW.schedule_version <> (SELECT max(x.schedule_version) FROM bnpl.bnpl_schedule x
                                         WHERE x.obligation_id = obligation.id) THEN
        RAISE EXCEPTION 'a BNPL refund names its obligation''s agreement and schedule versions in force (P12-TSK-021, INV-HIST-04)';
    END IF;
    IF obligation.status = 'ACTIVE' THEN
        -- The Phase 11 gate's R1: a principal fact is never value-dated before the floor, nor after the later of today
        -- and it.
        value_floor := bnpl.principal_value_floor(obligation.id);
        IF NEW.value_date < value_floor
                OR NEW.value_date > GREATEST(bnpl.business_date_latest(obligation.servicing_zone), value_floor) THEN
            RAISE EXCEPTION 'a BNPL refund is value-dated on or after its obligation''s floor and never after today (P12-TSK-021, the Phase 11 gate''s R1)';
        END IF;
    ELSIF NEW.cancelled_unbilled_minor <> 0 OR NEW.cancelled_billed_minor <> 0
            OR NEW.interest_accrued_released_minor <> 0 OR NEW.interest_due_released_minor <> 0
            OR NEW.excess_to <> 'WALLET'
            OR NEW.value_date > bnpl.business_date_latest(obligation.servicing_zone) THEN
        -- A refund after closure (OQ-15): nothing left to cancel or release, the whole of R to the wallet - a CLOSED
        -- obligation holds no credit balance (INV-BNPL-09).
        RAISE EXCEPTION 'a refund of a CLOSED obligation cancels nothing and pays the customer''s wallet only (P12-TSK-021, OQ-15, INV-BNPL-09)';
    END IF;
    NEW.created_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER bnpl_refund_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON bnpl.bnpl_refund
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_refund_is_born_once();

CREATE TRIGGER bnpl_refund_is_never_truncated
    BEFORE TRUNCATE ON bnpl.bnpl_refund
    FOR EACH STATEMENT
    EXECUTE FUNCTION bnpl.acceptance_rows_are_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The adjustment: one row per instalment the refund touched, INSERT-only.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE bnpl.bnpl_refund_adjustment (
    id                        uuid     NOT NULL,
    refund_id                 uuid     NOT NULL,
    obligation_id             uuid     NOT NULL,
    sequence                  smallint NOT NULL,
    instalment_id             uuid     NOT NULL,
    kind                      text     NOT NULL,
    currency                  char(3)  NOT NULL,
    scale                     smallint NOT NULL,
    principal_cancelled_minor bigint   NOT NULL,
    interest_released_minor   bigint   NOT NULL,
    whole                     boolean  NOT NULL,
    CONSTRAINT bnpl_refund_adjustment_pk PRIMARY KEY (id),
    -- Each instalment once per refund (the allocator walks each once, latest first).
    CONSTRAINT bnpl_refund_adjustment_sequence_unique UNIQUE (refund_id, sequence),
    CONSTRAINT bnpl_refund_adjustment_refund_fk FOREIGN KEY (refund_id) REFERENCES bnpl.bnpl_refund (id),
    CONSTRAINT bnpl_refund_adjustment_instalment_fk FOREIGN KEY (instalment_id, obligation_id)
        REFERENCES bnpl.bnpl_instalment (id, obligation_id),
    -- RefundAllocation.Cancellation.Kind: unbilled principal (CR BNPL_PRINCIPAL) or billed-unpaid (CR BNPL_PRINCIPAL_DUE).
    CONSTRAINT bnpl_refund_adjustment_kind_is_known CHECK (kind IN ('UNBILLED', 'BILLED_UNPAID')),
    CONSTRAINT bnpl_refund_adjustment_currency_is_the_products CHECK (currency = 'EUR' AND scale BETWEEN 0 AND 9),
    -- Something cancelled or released; an unbilled instalment has no billed interest to release.
    CONSTRAINT bnpl_refund_adjustment_amounts CHECK (
        sequence >= 1 AND principal_cancelled_minor >= 0 AND interest_released_minor >= 0
        AND principal_cancelled_minor + interest_released_minor > 0
        AND (kind = 'BILLED_UNPAID' OR interest_released_minor = 0))
);

COMMENT ON TABLE bnpl.bnpl_refund_adjustment IS
    'A BNPL refund''s adjustment of one instalment (P12-TSK-021; ADR-0105 decision 2): the unbilled (UNBILLED, the latest schedule''s instalment) or billed-unpaid (BILLED_UNPAID, the billing''s instalment, curing its days past due) principal the refund cancelled - latest instalment first - the billed pay-in-N interest it released (a cancellation of every remaining unit of principal, A11), and whether it cancelled the instalment''s whole unpaid principal. The rows sum to their refund''s cancellations and release at commit (Sigma adjustments + excess = R, INV-BNPL-06); no billed component is ever paid beyond what it billed. INSERT-only (RESTRICTED-FINANCIAL).';

CREATE INDEX bnpl_refund_adjustment_by_instalment ON bnpl.bnpl_refund_adjustment (instalment_id) WHERE kind = 'BILLED_UNPAID';
CREATE INDEX bnpl_refund_adjustment_by_obligation ON bnpl.bnpl_refund_adjustment (obligation_id, sequence);

CREATE OR REPLACE FUNCTION bnpl.bnpl_refund_adjustment_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    instalment bnpl.bnpl_instalment%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL refund adjustment is immutable from insert, for every role (P12-TSK-021)';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM bnpl.bnpl_refund r WHERE r.id = NEW.refund_id AND r.obligation_id = NEW.obligation_id
                      AND r.currency = NEW.currency AND r.scale = NEW.scale) THEN
        RAISE EXCEPTION 'a BNPL refund adjustment is its refund''s, on its obligation, in its currency and scale (P12-TSK-021)';
    END IF;
    SELECT * INTO instalment FROM bnpl.bnpl_instalment i WHERE i.id = NEW.instalment_id;
    IF instalment.sequence <> NEW.sequence THEN
        RAISE EXCEPTION 'a BNPL refund adjustment names its instalment''s own sequence (P12-TSK-021)';
    END IF;
    -- Billed-unpaid names the billing's own instalment; unbilled an instalment with no billing at its due date.
    IF NEW.kind = 'BILLED_UNPAID'
            AND NOT EXISTS (SELECT 1 FROM bnpl.bnpl_billing b WHERE b.instalment_id = NEW.instalment_id) THEN
        RAISE EXCEPTION 'a billed-unpaid cancellation names a billed instalment (P12-TSK-021)';
    END IF;
    IF NEW.kind = 'UNBILLED' AND EXISTS (SELECT 1 FROM bnpl.bnpl_billing b
                                          WHERE b.obligation_id = NEW.obligation_id
                                            AND b.value_date = instalment.due_date) THEN
        RAISE EXCEPTION 'an unbilled cancellation names an instalment not yet billed (P12-TSK-021)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER bnpl_refund_adjustment_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON bnpl.bnpl_refund_adjustment
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_refund_adjustment_is_born_once();

CREATE TRIGGER bnpl_refund_adjustment_is_never_truncated
    BEFORE TRUNCATE ON bnpl.bnpl_refund_adjustment
    FOR EACH STATEMENT
    EXECUTE FUNCTION bnpl.acceptance_rows_are_never_truncated();

-- INV-BNPL-06 at commit, for every writer: the adjustments sum to their refund's cancellations by kind and to its billed
-- interest released - so Sigma adjustments + excess = R (the row's CHECK) - and no billed component is settled beyond what
-- it billed. A refund that cancelled unbilled principal commits beside the schedule version it made; and the purchase is
-- FULLY_REFUNDED exactly when its refunds total the gross.
CREATE OR REPLACE FUNCTION bnpl.bnpl_refund_conserves()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    refund bnpl.bnpl_refund%ROWTYPE;
    unbilled bigint;
    billed bigint;
    released bigint;
BEGIN
    IF TG_TABLE_NAME = 'bnpl_refund' THEN
        SELECT * INTO refund FROM bnpl.bnpl_refund r WHERE r.id = NEW.id;
    ELSE
        SELECT * INTO refund FROM bnpl.bnpl_refund r WHERE r.id = NEW.refund_id;
    END IF;
    SELECT coalesce(sum(a.principal_cancelled_minor) FILTER (WHERE a.kind = 'UNBILLED'), 0),
           coalesce(sum(a.principal_cancelled_minor) FILTER (WHERE a.kind = 'BILLED_UNPAID'), 0),
           coalesce(sum(a.interest_released_minor), 0)
      INTO unbilled, billed, released
      FROM bnpl.bnpl_refund_adjustment a WHERE a.refund_id = refund.id;
    IF unbilled <> refund.cancelled_unbilled_minor OR billed <> refund.cancelled_billed_minor
            OR released <> refund.interest_due_released_minor THEN
        RAISE EXCEPTION 'a BNPL refund''s adjustments sum to its cancellations and release: Sigma adjustments + excess = R (P12-TSK-021, INV-BNPL-06)';
    END IF;
    IF EXISTS (SELECT 1 FROM bnpl.bnpl_refund_adjustment a
                 JOIN bnpl.bnpl_billing b ON b.instalment_id = a.instalment_id
                 CROSS JOIN (VALUES ('INTEREST'), ('PRINCIPAL')) AS c (component)
                WHERE a.refund_id = refund.id AND a.kind = 'BILLED_UNPAID'
                  AND bnpl.billing_component_paid(b.id, c.component)
                      > bnpl.billing_component_billed(b.id, c.component)) THEN
        RAISE EXCEPTION 'no billed component is ever settled beyond what it billed (P12-TSK-021, INV-LND-04)';
    END IF;
    IF refund.cancelled_unbilled_minor > 0 AND NOT EXISTS (
            SELECT 1 FROM bnpl.bnpl_schedule s WHERE s.refund_id = refund.id
                                                 AND s.schedule_version = refund.schedule_version) THEN
        RAISE EXCEPTION 'a BNPL refund that cancels unbilled principal commits beside the schedule version it made (P12-TSK-021, INV-HIST-04)';
    END IF;
    IF (SELECT p.status = 'FULLY_REFUNDED' FROM bnpl.purchase p WHERE p.id = refund.purchase_id)
            IS DISTINCT FROM ((SELECT sum(r.amount_minor) FROM bnpl.bnpl_refund r WHERE r.purchase_id = refund.purchase_id)
                              = (SELECT f.gross_minor FROM bnpl.merchant_financing f
                                  WHERE f.purchase_id = refund.purchase_id)) THEN
        RAISE EXCEPTION 'a BNPL purchase is FULLY_REFUNDED exactly when its refunds total its gross (P12-TSK-021, INV-BNPL-07)';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER bnpl_refund_conserves
    AFTER INSERT ON bnpl.bnpl_refund
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_refund_conserves();

CREATE CONSTRAINT TRIGGER bnpl_refund_adjustment_conserves
    AFTER INSERT ON bnpl.bnpl_refund_adjustment
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_refund_conserves();

-- ---------------------------------------------------------------------------------------------
-- What a billing's component has had settled: every payer's, and every refund's cancellation (V008's one definition).
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION bnpl.billing_component_paid(billing uuid, paid_component text)
    RETURNS bigint
    LANGUAGE sql
    STABLE
AS $$
    SELECT ((SELECT coalesce(sum(a.amount_minor), 0) FROM bnpl.bnpl_credit_balance_allocation a
              WHERE a.billing_id = billing AND a.component = paid_component)
          + (SELECT coalesce(sum(r.amount_minor), 0) FROM bnpl.bnpl_repayment_allocation r
               JOIN bnpl.bnpl_billing b ON b.instalment_id = r.instalment_id
              WHERE b.id = billing AND r.target = 'DUE_ITEM' AND r.component = paid_component)
          + (SELECT coalesce(sum(CASE paid_component WHEN 'PRINCIPAL' THEN x.principal_cancelled_minor
                                                     WHEN 'INTEREST' THEN x.interest_released_minor
                                                     ELSE 0 END), 0)
               FROM bnpl.bnpl_refund_adjustment x
               JOIN bnpl.bnpl_billing b ON b.instalment_id = x.instalment_id
              WHERE b.id = billing AND x.kind = 'BILLED_UNPAID'))::bigint
$$;

COMMENT ON FUNCTION bnpl.billing_component_paid(uuid, text) IS
    'What a billing''s component (FEES, INTEREST, PRINCIPAL) has had settled - by every payer (credit-balance applications, P12-TSK-015; repayments, P12-TSK-018) and by every refund''s cancellation of its principal or release of its interest (P12-TSK-021). The one definition later payers replace (-020, -024); what is still due is billed less this.';

-- ---------------------------------------------------------------------------------------------
-- The billing, replaced: V008's body verbatim but for the oldest-first rank, judged by due date.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION bnpl.bnpl_billing_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    instalment bnpl.bnpl_instalment%ROWTYPE;
    obligation bnpl.bnpl_obligation%ROWTYPE;
    schedule bnpl.bnpl_schedule%ROWTYPE;
    agreement bnpl.bnpl_agreement%ROWTYPE;
    period_from date;
    accrued bigint;
    accrued_through date;
    remaining bigint;
    expected bigint;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL billing is immutable from insert, for every role (P12-TSK-013)';
    END IF;
    -- (B4) The obligation, for every writer: the order of billings and the principal billed are judged under the lock
    -- every servicing act on it holds (the step and the acceptance hold it already; re-taking it is free).
    SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id FOR UPDATE;
    IF NOT FOUND OR obligation.status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'a BNPL billing bills an ACTIVE obligation (P12-TSK-015)';
    END IF;
    SELECT * INTO instalment FROM bnpl.bnpl_instalment i
     WHERE i.id = NEW.instalment_id AND i.obligation_id = NEW.obligation_id;
    SELECT * INTO schedule FROM bnpl.bnpl_schedule s WHERE s.id = instalment.schedule_id;
    -- Of the obligation's LATEST schedule version, on that version's engine, in its currency and scale.
    IF instalment.id IS NULL OR schedule.id IS NULL OR NEW.schedule_id IS DISTINCT FROM schedule.id
            OR NEW.schedule_version IS DISTINCT FROM schedule.schedule_version
            OR schedule.schedule_version <> (SELECT max(x.schedule_version) FROM bnpl.bnpl_schedule x
                                              WHERE x.obligation_id = NEW.obligation_id)
            OR NEW.schedule_engine IS DISTINCT FROM schedule.engine
            OR NEW.currency <> instalment.currency OR NEW.scale <> instalment.scale THEN
        RAISE EXCEPTION 'a BNPL billing bills an instalment of its obligation''s latest schedule version, on its engine, in its currency (P12-TSK-015, INV-BNPL-05)';
    END IF;
    -- Under the agreement version in force - the obligation's highest - and its pinned accrual engine.
    SELECT * INTO agreement FROM bnpl.bnpl_agreement a
     WHERE a.agreement_id = NEW.agreement_id AND a.version = NEW.agreement_version AND a.obligation_id = NEW.obligation_id;
    IF agreement.agreement_id IS NULL
            OR NEW.agreement_version <> (SELECT max(x.version) FROM bnpl.bnpl_agreement x
                                          WHERE x.obligation_id = NEW.obligation_id)
            OR NEW.accrual_engine IS DISTINCT FROM agreement.accrual_engine
            OR NEW.correlation_id IS NULL THEN
        RAISE EXCEPTION 'a BNPL billing names its obligation''s agreement version in force and its pinned accrual engine (P12-TSK-015, INV-LND-09)';
    END IF;
    -- Dated its due date, which has come on the database clock in the pinned zone - never early.
    IF NEW.value_date IS DISTINCT FROM instalment.due_date
            OR instalment.due_date > bnpl.business_date_latest(obligation.servicing_zone) THEN
        RAISE EXCEPTION 'a BNPL billing is dated its instalment''s due date, once that date has come on the database clock (P12-TSK-015)';
    END IF;
    -- Oldest first: every earlier instalment of the version billed - judged by its due date (P12-TSK-021: a schedule
    -- version n+1 carries its billed instalments as new rows beside the billings that name version n's) - and never
    -- before the latest billing's date.
    IF EXISTS (SELECT 1 FROM bnpl.bnpl_instalment i
                WHERE i.schedule_id = schedule.id AND i.sequence < instalment.sequence
                  AND NOT EXISTS (SELECT 1 FROM bnpl.bnpl_billing b
                                   WHERE b.obligation_id = NEW.obligation_id AND b.value_date = i.due_date))
            OR EXISTS (SELECT 1 FROM bnpl.bnpl_billing b
                        WHERE b.obligation_id = NEW.obligation_id AND b.value_date >= NEW.value_date) THEN
        RAISE EXCEPTION 'an obligation''s instalments are billed oldest first, one per due date (P12-TSK-015)';
    END IF;
    -- The period it closes: from the previous instalment's due date, the first from the schedule's start.
    period_from := COALESCE((SELECT p.due_date FROM bnpl.bnpl_instalment p
                              WHERE p.schedule_id = schedule.id AND p.sequence = instalment.sequence - 1),
                            schedule.start_date);
    IF NEW.period_start IS DISTINCT FROM period_from THEN
        RAISE EXCEPTION 'a BNPL billing closes its instalment''s own period (P12-TSK-015)';
    END IF;
    -- The interest: pay-in-4 none (A4); pay-in-N exactly the period's posted accruals - interest actually accrued, never the
    -- projection - and only once every date of the period has accrued (the accrual gate, for every writer).
    IF schedule.engine = 'BNPL_SCHEDULE_ENGINE_V1' THEN
        accrued := 0;
    ELSE
        SELECT coalesce(sum(a.posted_minor), 0) INTO accrued FROM bnpl.bnpl_interest_accrual a
         WHERE a.obligation_id = NEW.obligation_id AND a.accrual_date >= period_from
           AND a.accrual_date < instalment.due_date;
        SELECT max(a.accrual_date) INTO accrued_through FROM bnpl.bnpl_interest_accrual a
         WHERE a.obligation_id = NEW.obligation_id;
        IF accrued_through IS NULL OR accrued_through < instalment.due_date - 1 THEN
            RAISE EXCEPTION 'a pay-in-N billing waits for every accrual of its period (P12-TSK-015, INV-LND-02)';
        END IF;
    END IF;
    IF NEW.interest_minor <> accrued THEN
        RAISE EXCEPTION 'a BNPL billing''s interest is exactly its period''s accrued interest (P12-TSK-015, INV-LND-02)';
    END IF;
    -- The principal (INV-BNPL-05): never beyond the version's principal not yet billed; the last instalment all of it, every
    -- other least(remaining, max(0, amount - interest)) - for pay-in-4 exactly its scheduled principal.
    remaining := schedule.principal_minor - (SELECT coalesce(sum(b.principal_minor), 0) FROM bnpl.bnpl_billing b
                                               WHERE b.obligation_id = NEW.obligation_id);
    IF instalment.sequence = schedule.instalment_count THEN
        expected := remaining;
    ELSE
        expected := LEAST(remaining, GREATEST(0, instalment.amount_minor - NEW.interest_minor));
    END IF;
    IF remaining < 0 OR NEW.principal_minor IS DISTINCT FROM expected THEN
        RAISE EXCEPTION 'a BNPL billing bills least(remaining, amount - interest) of principal, the last instalment the remainder (P12-TSK-015, INV-BNPL-05)';
    END IF;
    -- The business date it was made on and its instant - the database's, after the lock.
    NEW.business_date := GREATEST(bnpl.business_date_latest(obligation.servicing_zone), NEW.value_date);
    NEW.billed_at := clock_timestamp();
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The schedule, extended: version n+1 beside its refund.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE bnpl.bnpl_schedule ADD COLUMN refund_id uuid;
ALTER TABLE bnpl.bnpl_schedule ADD CONSTRAINT bnpl_schedule_refund_fk
    FOREIGN KEY (refund_id) REFERENCES bnpl.bnpl_refund (id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE bnpl.bnpl_schedule DROP CONSTRAINT bnpl_schedule_version_one;
-- Version 1 the acceptance's; a version n+1 a refund's, one per refund.
ALTER TABLE bnpl.bnpl_schedule ADD CONSTRAINT bnpl_schedule_version_origin CHECK (
    schedule_version >= 1 AND (schedule_version = 1) = (refund_id IS NULL));
ALTER TABLE bnpl.bnpl_schedule ADD CONSTRAINT bnpl_schedule_refund_unique UNIQUE (refund_id);
ALTER TABLE bnpl.bnpl_schedule DROP CONSTRAINT bnpl_schedule_principal_positive;
-- Version 1 schedules the gross over 2 to 12 instalments; a refund's version what is left - nothing at all, when the
-- refund left nothing to bill and nothing billed (its rows are then none).
ALTER TABLE bnpl.bnpl_schedule ADD CONSTRAINT bnpl_schedule_principal_positive CHECK (
    (schedule_version = 1 AND principal_minor > 0 AND instalment_count BETWEEN 2 AND 12)
    OR (schedule_version > 1 AND principal_minor >= 0 AND instalment_count BETWEEN 0 AND 12));

-- A billed instalment carried into a refund's version is its billing's, which may bill no principal (pay-in-N interest
-- reaching the instalment): the positive principal of every other instalment is the trigger's rank.
ALTER TABLE bnpl.bnpl_instalment DROP CONSTRAINT bnpl_instalment_amounts;
ALTER TABLE bnpl.bnpl_instalment ADD CONSTRAINT bnpl_instalment_amounts CHECK (
    sequence >= 1 AND principal_minor >= 0 AND interest_minor >= 0 AND amount_minor = principal_minor + interest_minor);

COMMENT ON TABLE bnpl.bnpl_schedule IS
    'A BNPL Schedule version (P12-TSK-013, INV-BNPL-05): version 1 projected by the pinned engine (pay-in-4 BNPL_SCHEDULE_ENGINE_V1, pay-in-N SCHEDULE_ENGINE_V1) from the purchase''s business date over exactly the financed gross; version n+1 a refund''s (P12-TSK-021, refund_id): every billed instalment kept as its billing billed it, the unbilled tail what the refund left - pay-in-4 shortened from the end, pay-in-N re-amortised at the same instalment by ScheduleEngineV1.recalculateAtInstalment (the least term) - so the principal still to bill falls by exactly the refund''s unbilled cancellation; every version from the purchase''s business date. Its instalments'' principal sums to its principal exactly, held at commit. INSERT-only (RESTRICTED-FINANCIAL).';

CREATE OR REPLACE FUNCTION bnpl.bnpl_schedule_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    obligation bnpl.bnpl_obligation%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL schedule is immutable from insert, for every role (P12-TSK-013, INV-BNPL-05)';
    END IF;
    SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id;
    -- Every version starts on the purchase's own business date (the accrual span's start, V009), in the obligation's
    -- currency, on its agreement's engine.
    IF NOT FOUND OR NEW.start_date <> obligation.purchase_date OR NEW.currency <> obligation.currency
            OR NOT EXISTS (SELECT 1 FROM bnpl.bnpl_agreement a WHERE a.obligation_id = NEW.obligation_id
                              AND a.version = 1 AND a.schedule_engine = NEW.engine) THEN
        RAISE EXCEPTION 'a BNPL schedule version starts on its obligation''s purchase date, in its currency, on its agreement''s engine (P12-TSK-013)';
    END IF;
    -- A version n+1 (P12-TSK-021): the next, of an ACTIVE obligation, beside its refund (held at commit by the deferred
    -- foreign key and bnpl.bnpl_refund_conserves).
    IF NEW.schedule_version > 1 AND (obligation.status <> 'ACTIVE' OR NEW.refund_id IS NULL
            OR NEW.schedule_version <> (SELECT max(x.schedule_version) + 1 FROM bnpl.bnpl_schedule x
                                         WHERE x.obligation_id = NEW.obligation_id)) THEN
        RAISE EXCEPTION 'a BNPL schedule version n+1 is the next version of an ACTIVE obligation, beside its refund (P12-TSK-021, INV-HIST-04)';
    END IF;
    NEW.created_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION bnpl.bnpl_instalment_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    schedule bnpl.bnpl_schedule%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL instalment is immutable from insert, for every role (P12-TSK-013, INV-BNPL-05)';
    END IF;
    SELECT * INTO schedule FROM bnpl.bnpl_schedule s WHERE s.id = NEW.schedule_id;
    IF NEW.currency <> schedule.currency OR NEW.scale <> schedule.scale OR NEW.sequence > schedule.instalment_count
            OR NEW.due_date < schedule.start_date THEN
        RAISE EXCEPTION 'a BNPL instalment is its schedule''s, in its currency and scale, within its count and on or after its start (P12-TSK-013)';
    END IF;
    -- Pay-in-4 (A4, A5): the first on the purchase date, every 14 days after, no interest.
    IF schedule.engine = 'BNPL_SCHEDULE_ENGINE_V1'
            AND (NEW.interest_minor <> 0 OR NEW.due_date <> schedule.start_date + 14 * (NEW.sequence - 1)) THEN
        RAISE EXCEPTION 'a pay-in-4 instalment is interest-free and falls due 14 days after its predecessor, the first on the purchase date (P12-TSK-013, A5)';
    END IF;
    -- A positive principal (P12-TSK-021: but for a billed instalment a refund's version carries as its billing billed it).
    IF NEW.principal_minor <= 0 AND (schedule.schedule_version = 1
            OR NOT EXISTS (SELECT 1 FROM bnpl.bnpl_billing b
                            WHERE b.obligation_id = NEW.obligation_id AND b.value_date = NEW.due_date)) THEN
        RAISE EXCEPTION 'a BNPL instalment schedules a positive principal, but for a billed one carried into a refund''s version (P12-TSK-021, INV-BNPL-05)';
    END IF;
    RETURN NEW;
END;
$$;

-- INV-BNPL-05 at commit: the instalments are numbered 1..n, n the schedule's count (none for an empty version n+1), and
-- their principal sums to the schedule's principal exactly - which for version 1 is the financed gross; for a version n+1
-- (P12-TSK-021) every billing of the obligation is one of its instalments, as billed, and its principal is version n's less
-- exactly its refund's unbilled cancellation, so the principal still to bill falls by exactly that.
CREATE OR REPLACE FUNCTION bnpl.bnpl_schedule_conserves()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    schedule bnpl.bnpl_schedule%ROWTYPE;
    previous bnpl.bnpl_schedule%ROWTYPE;
    counted integer;
    highest integer;
    scheduled numeric;
BEGIN
    SELECT * INTO schedule FROM bnpl.bnpl_schedule s WHERE s.id = NEW.id;
    SELECT count(*), coalesce(max(i.sequence), 0), coalesce(sum(i.principal_minor), 0) INTO counted, highest, scheduled
      FROM bnpl.bnpl_instalment i WHERE i.schedule_id = schedule.id;
    IF counted <> schedule.instalment_count OR highest <> counted OR scheduled <> schedule.principal_minor THEN
        RAISE EXCEPTION 'a BNPL schedule conserves: its instalments are numbered 1 to n and their principal sums to its principal exactly (P12-TSK-013, INV-BNPL-05)';
    END IF;
    IF schedule.schedule_version = 1 AND NOT EXISTS (
            SELECT 1 FROM bnpl.bnpl_obligation o JOIN bnpl.merchant_financing f ON f.purchase_id = o.purchase_id
             WHERE o.id = schedule.obligation_id AND f.gross_minor = schedule.principal_minor
               AND f.scale = schedule.scale) THEN
        RAISE EXCEPTION 'a BNPL schedule version 1 schedules exactly the financed gross (P12-TSK-013, INV-BNPL-04, INV-BNPL-05)';
    END IF;
    IF schedule.schedule_version > 1 THEN
        SELECT * INTO previous FROM bnpl.bnpl_schedule s
         WHERE s.obligation_id = schedule.obligation_id AND s.schedule_version = schedule.schedule_version - 1;
        IF previous.id IS NULL OR schedule.principal_minor IS DISTINCT FROM previous.principal_minor
                - (SELECT r.cancelled_unbilled_minor FROM bnpl.bnpl_refund r WHERE r.id = schedule.refund_id) THEN
            RAISE EXCEPTION 'a refund''s schedule version schedules its predecessor''s principal less exactly the refund''s unbilled cancellation (P12-TSK-021, INV-BNPL-05, INV-BNPL-06)';
        END IF;
        IF EXISTS (SELECT 1 FROM bnpl.bnpl_billing b WHERE b.obligation_id = schedule.obligation_id
                      AND NOT EXISTS (SELECT 1 FROM bnpl.bnpl_instalment i
                                       WHERE i.schedule_id = schedule.id AND i.due_date = b.value_date
                                         AND i.principal_minor = b.principal_minor
                                         AND i.interest_minor = b.interest_minor)) THEN
            RAISE EXCEPTION 'a refund''s schedule version keeps every billed instalment as it was billed (P12-TSK-021, INV-HIST-04)';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The agreement, extended: version n+1 beside its refund.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE bnpl.bnpl_agreement ADD COLUMN refund_id uuid;
ALTER TABLE bnpl.bnpl_agreement ADD COLUMN schedule_version integer NOT NULL DEFAULT 1;
ALTER TABLE bnpl.bnpl_agreement ADD CONSTRAINT bnpl_agreement_refund_fk
    FOREIGN KEY (refund_id) REFERENCES bnpl.bnpl_refund (id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE bnpl.bnpl_agreement ADD CONSTRAINT bnpl_agreement_refund_unique UNIQUE (refund_id);
ALTER TABLE bnpl.bnpl_agreement DROP CONSTRAINT bnpl_agreement_origin;
-- Version 1 is the offer's, accepted; a later version a refund's - its origin the same offer, by value.
ALTER TABLE bnpl.bnpl_agreement ADD CONSTRAINT bnpl_agreement_origin CHECK (
    offer_id IS NOT NULL AND (version = 1) = (refund_id IS NULL)
    AND ((version = 1 AND schedule_version = 1) OR (version > 1 AND schedule_version > 1)));

COMMENT ON TABLE bnpl.bnpl_agreement IS
    'A BNPL financing agreement''s versions (P12-TSK-013, BNPL_LIFECYCLES.md section 4.6, INV-HIST-04): version 1 the accepted offer''s canonical terms copied byte for byte (text, the hash held to the bytes by CHECK), the document''s hash, the pinned terms version, the schedule, accrual, allocation and refund allocation engines and the merchant fee schedule version; version n+1 a refund''s (P12-TSK-021, refund_id) - version n''s terms, engines and pins copied exactly, governing the schedule version the refund made. INSERT-only and immutable for every role; status lives on the obligation.';

CREATE OR REPLACE FUNCTION bnpl.bnpl_agreement_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    obligation bnpl.bnpl_obligation%ROWTYPE;
    offer bnpl.bnpl_offer%ROWTYPE;
    terms bnpl.bnpl_offer_terms%ROWTYPE;
    pinned bnpl.bnpl_terms%ROWTYPE;
    previous bnpl.bnpl_agreement%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL agreement is immutable from insert, for every role (P12-TSK-013, INV-HIST-04)';
    END IF;
    SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id;
    IF NOT FOUND OR obligation.agreement_id <> NEW.agreement_id OR obligation.decision_id <> NEW.decision_id
            OR obligation.product <> NEW.product OR obligation.plan <> NEW.plan THEN
        RAISE EXCEPTION 'a BNPL agreement is its own obligation''s, for its decision, product and plan (P12-TSK-013)';
    END IF;
    IF NEW.version > 1 THEN
        -- A refund's version n+1 (P12-TSK-021): the next, of an ACTIVE obligation, an exact copy of version n's terms,
        -- engines, pins and template - the change is the schedule version it governs, the next one (INV-HIST-04).
        SELECT * INTO previous FROM bnpl.bnpl_agreement a
         WHERE a.obligation_id = NEW.obligation_id AND a.version = NEW.version - 1;
        IF previous.agreement_id IS NULL OR obligation.status <> 'ACTIVE'
                OR NEW.version <> (SELECT max(x.version) + 1 FROM bnpl.bnpl_agreement x
                                    WHERE x.obligation_id = NEW.obligation_id)
                OR NEW.schedule_version <> previous.schedule_version + 1
                OR NEW.offer_id <> previous.offer_id OR NEW.terms_version_id <> previous.terms_version_id
                OR NEW.schedule_engine <> previous.schedule_engine
                OR NEW.accrual_engine IS DISTINCT FROM previous.accrual_engine
                OR NEW.allocation_engine <> previous.allocation_engine
                OR NEW.refund_allocation_engine <> previous.refund_allocation_engine
                OR NEW.fee_schedule_version_id <> previous.fee_schedule_version_id
                OR NEW.agreement_template_id <> previous.agreement_template_id
                OR NEW.agreement_template_version <> previous.agreement_template_version
                OR NEW.canonical_format <> previous.canonical_format OR NEW.canonical_terms <> previous.canonical_terms
                OR NEW.terms_sha256 <> previous.terms_sha256 OR NEW.document_sha256 <> previous.document_sha256 THEN
            RAISE EXCEPTION 'a BNPL agreement version n+1 is the next version of an ACTIVE obligation, a copy of version n governing the next schedule version (P12-TSK-021, INV-HIST-04)';
        END IF;
        NEW.created_at := clock_timestamp();
        NEW.effective_from := NEW.created_at;
        RETURN NEW;
    END IF;
    -- Version 1 is an EXACT copy of the OFFERED offer's terms the obligation's purchase accepts (INV-HIST-04).
    SELECT * INTO offer FROM bnpl.bnpl_offer o WHERE o.id = NEW.offer_id;
    SELECT * INTO terms FROM bnpl.bnpl_offer_terms t WHERE t.offer_id = NEW.offer_id;
    IF offer.status IS DISTINCT FROM 'OFFERED'
            OR NOT EXISTS (SELECT 1 FROM bnpl.purchase p WHERE p.id = obligation.purchase_id AND p.offer_id = NEW.offer_id)
            OR offer.decision_id <> NEW.decision_id OR offer.terms_version_id <> NEW.terms_version_id
            OR terms.canonical_format <> NEW.canonical_format OR terms.canonical_terms <> NEW.canonical_terms
            OR terms.terms_sha256 <> NEW.terms_sha256 OR terms.document_sha256 <> NEW.document_sha256
            OR terms.schedule_engine <> NEW.schedule_engine THEN
        RAISE EXCEPTION 'a BNPL agreement''s version 1 is an exact copy of its purchase''s OFFERED offer''s terms (P12-TSK-013, INV-HIST-04)';
    END IF;
    -- The engines and the template the pinned terms version names.
    SELECT * INTO pinned FROM bnpl.bnpl_terms t WHERE t.terms_version_id = NEW.terms_version_id;
    IF pinned.allocation_engine <> NEW.allocation_engine
            OR pinned.refund_allocation_engine <> NEW.refund_allocation_engine
            OR pinned.accrual_engine IS DISTINCT FROM NEW.accrual_engine
            OR pinned.agreement_template_id <> NEW.agreement_template_id
            OR pinned.agreement_template_version <> NEW.agreement_template_version THEN
        RAISE EXCEPTION 'a BNPL agreement pins the engines and the template of its terms version (P12-TSK-013, INV-HIST-04)';
    END IF;
    NEW.created_at := clock_timestamp();
    NEW.effective_from := NEW.created_at;
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The purchase's edge function, replaced: V006's, plus FINANCED -> FULLY_REFUNDED.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION bnpl.purchase_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    offer bnpl.bnpl_offer%ROWTYPE;
    application bnpl.bnpl_application%ROWTYPE;
    zone text;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'AUTHORISED' OR NEW.financed_at IS NOT NULL OR NEW.order_id IS NOT NULL THEN
            RAISE EXCEPTION 'a BNPL purchase is born AUTHORISED (P12-TSK-013)';
        END IF;
        -- Born under its offer's row lock (B2), while it is OFFERED, for the offer's own party, product, plan and session,
        -- at the application's merchant. The expiry is judged at the offer's ACCEPTED edge, the last write of the act.
        SELECT * INTO offer FROM bnpl.bnpl_offer o WHERE o.id = NEW.offer_id;
        IF NOT FOUND OR offer.status <> 'OFFERED' OR offer.party_id <> NEW.party_id OR offer.product <> NEW.product
                OR offer.plan <> NEW.plan OR offer.checkout_session_id <> NEW.checkout_session_id THEN
            RAISE EXCEPTION 'a BNPL purchase is born for its own party''s OFFERED offer, of the same product, plan and checkout session (P12-TSK-013)';
        END IF;
        SELECT * INTO application FROM bnpl.bnpl_application a WHERE a.id = offer.application_id;
        IF application.merchant_id <> NEW.merchant_id THEN
            RAISE EXCEPTION 'a BNPL purchase pays the merchant its checkout session pays (P12-TSK-013)';
        END IF;
        SELECT t.servicing_zone INTO zone FROM bnpl.bnpl_terms t WHERE t.terms_version_id = offer.terms_version_id;
        -- The instant and the business date are the database's, after the act's locks - never an instance's clock.
        NEW.servicing_zone := zone;
        NEW.authorised_at := clock_timestamp();
        NEW.business_date := bnpl.business_date(NEW.authorised_at, zone);
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a BNPL purchase is never deleted (P12-TSK-013, INV-HIST-01)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.offer_id IS DISTINCT FROM OLD.offer_id
            OR NEW.checkout_session_id IS DISTINCT FROM OLD.checkout_session_id
            OR NEW.party_id IS DISTINCT FROM OLD.party_id OR NEW.merchant_id IS DISTINCT FROM OLD.merchant_id
            OR NEW.product IS DISTINCT FROM OLD.product OR NEW.plan IS DISTINCT FROM OLD.plan
            OR NEW.currency IS DISTINCT FROM OLD.currency OR NEW.servicing_zone IS DISTINCT FROM OLD.servicing_zone
            OR NEW.business_date IS DISTINCT FROM OLD.business_date
            OR NEW.authorised_at IS DISTINCT FROM OLD.authorised_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a BNPL purchase''s identity is frozen from birth (P12-TSK-013)';
    END IF;
    -- FINANCED -> FULLY_REFUNDED (P12-TSK-021): once, when the purchase's refunds total its gross - the refund that does
    -- it born before this edge in its transaction (held again at commit by bnpl.bnpl_refund_conserves); the financed
    -- instant and the order frozen. Terminal: nothing out of FULLY_REFUNDED, ever.
    IF OLD.status = 'FINANCED' AND NEW.status = 'FULLY_REFUNDED' THEN
        IF NEW.order_id IS DISTINCT FROM OLD.order_id OR NEW.financed_at IS DISTINCT FROM OLD.financed_at
                OR (SELECT coalesce(sum(r.amount_minor), 0) FROM bnpl.bnpl_refund r WHERE r.purchase_id = OLD.id)
                   <> (SELECT f.gross_minor FROM bnpl.merchant_financing f WHERE f.purchase_id = OLD.id) THEN
            RAISE EXCEPTION 'a BNPL purchase is FULLY_REFUNDED only when its refunds total its gross (P12-TSK-021, INV-BNPL-07)';
        END IF;
        RETURN NEW;
    END IF;
    -- AUTHORISED -> FINANCED, once, beside its merchant financing and with checkout's order. Never FINANCED -> AUTHORISED.
    IF NOT (OLD.status = 'AUTHORISED' AND NEW.status = 'FINANCED') THEN
        RAISE EXCEPTION 'a BNPL purchase moves AUTHORISED -> FINANCED -> FULLY_REFUNDED; % -> % is not an edge (P12-TSK-021)', OLD.status, NEW.status;
    END IF;
    IF NEW.order_id IS NULL THEN
        RAISE EXCEPTION 'a BNPL purchase is FINANCED with checkout''s order (P12-TSK-013)';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM bnpl.merchant_financing f WHERE f.purchase_id = NEW.id) THEN
        RAISE EXCEPTION 'a BNPL purchase is FINANCED only beside its merchant financing (P12-TSK-013, INV-BNPL-02)';
    END IF;
    NEW.financed_at := GREATEST(clock_timestamp(), OLD.authorised_at);
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The closure, replaced: V010's, plus the refund's two reasons and every instalment billed judged by due date.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE bnpl.bnpl_obligation_closure ADD COLUMN refund_id uuid;
ALTER TABLE bnpl.bnpl_obligation_closure ADD CONSTRAINT bnpl_obligation_closure_refund_fk
    FOREIGN KEY (refund_id) REFERENCES bnpl.bnpl_refund (id);
-- One closing act: a repayment, a refund, or the servicing step (neither).
ALTER TABLE bnpl.bnpl_obligation_closure ADD CONSTRAINT bnpl_obligation_closure_one_act CHECK (
    repayment_id IS NULL OR refund_id IS NULL);

CREATE OR REPLACE FUNCTION bnpl.bnpl_obligation_closure_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    obligation bnpl.bnpl_obligation%ROWTYPE;
    schedule bnpl.bnpl_schedule%ROWTYPE;
    purchase_status text;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL closure is immutable from insert, for every role (P12-TSK-018, INV-LIFE-04)';
    END IF;
    -- (B4) The obligation, for every writer; an ACTIVE obligation closing REPAID, or - P12-TSK-021 - REFUNDED when its
    -- purchase is FULLY_REFUNDED, or REFUNDED_PARTIALLY_REPAID beside a refund of a purchase not fully refunded. A
    -- payoff's closure is P12-TSK-022's, which replaces this function beside its own fact.
    SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id FOR UPDATE;
    SELECT p.status INTO purchase_status FROM bnpl.purchase p WHERE p.id = obligation.purchase_id;
    IF obligation.id IS NULL OR obligation.status <> 'ACTIVE'
            OR NOT ((NEW.reason = 'REPAID' AND purchase_status <> 'FULLY_REFUNDED')
                    OR (NEW.reason = 'REFUNDED' AND purchase_status = 'FULLY_REFUNDED')
                    OR (NEW.reason = 'REFUNDED_PARTIALLY_REPAID' AND purchase_status = 'FINANCED'
                        AND NEW.refund_id IS NOT NULL)) THEN
        RAISE EXCEPTION 'an ACTIVE BNPL obligation closes REPAID, REFUNDED (its purchase fully refunded) or REFUNDED_PARTIALLY_REPAID (beside a partial refund), and nothing else does (P12-TSK-021, INV-LND-08)';
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
    IF NEW.refund_id IS NOT NULL
            AND NOT EXISTS (SELECT 1 FROM bnpl.bnpl_refund r
                             WHERE r.id = NEW.refund_id AND r.obligation_id = NEW.obligation_id) THEN
        RAISE EXCEPTION 'a BNPL closure names a refund of its own obligation (P12-TSK-021)';
    END IF;
    -- The rows' side of "every receivable zero": every instalment of the latest schedule billed - by its due date
    -- (P12-TSK-021) - and every billed component settled in full by every payer and every refund. The ledger's side is
    -- the closing transaction's own, under B4.
    IF EXISTS (SELECT 1 FROM bnpl.bnpl_instalment i
                WHERE i.schedule_id = schedule.id
                  AND NOT EXISTS (SELECT 1 FROM bnpl.bnpl_billing b
                                   WHERE b.obligation_id = NEW.obligation_id AND b.value_date = i.due_date)) THEN
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

-- ---------------------------------------------------------------------------------------------
-- The grants: the refund and its adjustments SELECT and INSERT only. Never UPDATE, never DELETE.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON bnpl.bnpl_refund TO finapp_app;
GRANT SELECT, INSERT ON bnpl.bnpl_refund_adjustment TO finapp_app;
