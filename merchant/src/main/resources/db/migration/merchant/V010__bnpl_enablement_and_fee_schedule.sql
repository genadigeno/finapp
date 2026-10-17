-- Merchant BNPL enablement, the BNPL fee schedule per (merchant, product), and the fee pin per BNPL purchase
-- (P12-TSK-006; OQ-8 - owner accepted default under B8: a four-eyes enablement and a per-merchant schedule; ADR-0101,
-- ADR-0103 decision 7, ADR-0105 decision 3, PHASE_12_PLAN.md section 12.5; INV-AUD-04, INV-MER-03, INV-MER-04,
-- INV-BNPL-04).
--
-- EVERY GENERATED LIST BELOW HAS ONE DEFINITION: the enablement's status CHECK, its one-live index and its trigger edges
-- from BnplEnablementStatus; the product CHECKs from MerchantBnplProduct; the reason bound from
-- BnplEnablement.MAX_REASON_LENGTH; the gross's three columns from MoneyColumns.columnsFor("gross").
-- BnplEnablementMigrationTest fails the build if this file and the code disagree.
--
-- THE ENABLEMENT IS ITS OWN HISTORY. Each decision - the approval or the rejection, then a withdrawal - arrives whole on
-- the row and is frozen there by the edge trigger, so the row says who proposed, who decided and who withdrew, when and
-- why; the audit trail records every act beside it. Every instant is stamped by the DATABASE in the trigger,
-- clock_timestamp() after the lock the act waited on (the Phase 11 gate's A2-F3 lesson, lending V030, bnpl V002), and
-- never earlier than the decision before it, whatever the clock did (credit V014's clamp):
--
--     PROPOSED --a second, distinct operator--> ACTIVE --any operator, risk-reducing--> WITHDRAWN
--         \--any operator, the proposer included--> REJECTED
--
-- STILL NO BALANCE IN THIS SCHEMA (INV-MER-02): the pin's gross is what was FINANCED, not what is owed; the fee and the
-- net are the pinned version applied to it, never stored. What the platform owes the merchant is its MERCHANT_PAYABLE
-- ledger position.
--
-- NO CROSS-SCHEMA FOREIGN KEY (ADR-0029): bnpl_fee_pin.purchase_id references bnpl.purchase by value (P12-TSK-013's
-- table); every other reference is same-schema.

-- ---------------------------------------------------------------------------------------------------------------------
-- The enablement: one live (proposed or active) per merchant; four eyes for every writer.
-- ---------------------------------------------------------------------------------------------------------------------
CREATE TABLE merchant.merchant_bnpl_enablement (
    -- UUIDv7, minted by the application (ADR-0013).
    id                uuid        PRIMARY KEY,
    merchant_id       uuid        NOT NULL REFERENCES merchant.merchant (id),
    status            text        NOT NULL
        CONSTRAINT merchant_bnpl_enablement_status_is_known
            CHECK (status IN ('PROPOSED', 'ACTIVE', 'REJECTED', 'WITHDRAWN')),

    -- The proposal, frozen at birth: what the approver approves is what was proposed.
    proposed_by       text        NOT NULL,
    proposed_at       timestamptz NOT NULL,
    proposal_reason   text        NOT NULL,

    -- The approval or the rejection, arriving whole.
    decided_by        text,
    decided_at        timestamptz,
    decision_reason   text,

    -- The withdrawal of an ACTIVE enablement, arriving whole.
    withdrawn_by      text,
    withdrawn_at      timestamptz,
    withdrawal_reason text,

    -- INV-AUD-04's Enforce clause at DB-CONSTRAINT rank, for every state that passed through an approval: whoever
    -- approved an enablement did not propose it. A plain CHECK: the table is new, so no history predates it.
    CONSTRAINT merchant_bnpl_enablement_approver_is_not_proposer
        CHECK (status NOT IN ('ACTIVE', 'WITHDRAWN') OR decided_by <> proposed_by),

    -- A decision is its person, instant and reason together - unsplittable; only a PROPOSED row is undecided.
    CONSTRAINT merchant_bnpl_enablement_decision_is_whole
        CHECK ((status = 'PROPOSED') = (decided_by IS NULL)
               AND (decided_by IS NULL) = (decided_at IS NULL)
               AND (decided_by IS NULL) = (decision_reason IS NULL)),
    -- A withdrawal likewise; only a WITHDRAWN row carries one.
    CONSTRAINT merchant_bnpl_enablement_withdrawal_is_whole
        CHECK ((status = 'WITHDRAWN') = (withdrawn_by IS NOT NULL)
               AND (withdrawn_by IS NULL) = (withdrawn_at IS NULL)
               AND (withdrawn_by IS NULL) = (withdrawal_reason IS NULL)),

    -- Time moves forward along the machine.
    CONSTRAINT merchant_bnpl_enablement_decision_follows_proposal
        CHECK (decided_at IS NULL OR decided_at >= proposed_at),
    CONSTRAINT merchant_bnpl_enablement_withdrawal_follows_decision
        CHECK (withdrawn_at IS NULL OR withdrawn_at >= decided_at),

    CONSTRAINT merchant_bnpl_enablement_proposed_by_bounded
        CHECK (length(proposed_by) BETWEEN 1 AND 200),
    CONSTRAINT merchant_bnpl_enablement_decided_by_bounded
        CHECK (decided_by IS NULL OR length(decided_by) BETWEEN 1 AND 200),
    CONSTRAINT merchant_bnpl_enablement_withdrawn_by_bounded
        CHECK (withdrawn_by IS NULL OR length(withdrawn_by) BETWEEN 1 AND 200),
    CONSTRAINT merchant_bnpl_enablement_proposal_reason_bounded
        CHECK (length(proposal_reason) BETWEEN 1 AND 1000),
    CONSTRAINT merchant_bnpl_enablement_decision_reason_bounded
        CHECK (decision_reason IS NULL OR length(decision_reason) BETWEEN 1 AND 1000),
    CONSTRAINT merchant_bnpl_enablement_withdrawal_reason_bounded
        CHECK (withdrawal_reason IS NULL OR length(withdrawal_reason) BETWEEN 1 AND 1000)
);

-- ONE LIVE ENABLEMENT PER MERCHANT: ten proposers, one proposal (the store's savepoint answers the other nine); an
-- approval keeps the row inside the index, so a proposal beside an ACTIVE enablement is refused too.
CREATE UNIQUE INDEX merchant_bnpl_enablement_one_live_per_merchant
    ON merchant.merchant_bnpl_enablement (merchant_id)
    WHERE status IN ('PROPOSED', 'ACTIVE');

-- THE MACHINE BINDS EVERY WRITER: born PROPOSED and undecided, identity frozen, only the machine's edges, each
-- decision frozen once written, every instant the database's after the lock.
CREATE FUNCTION merchant.merchant_bnpl_enablement_permits_only_machine_edges() RETURNS trigger
LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PROPOSED' OR NEW.decided_by IS NOT NULL OR NEW.decided_at IS NOT NULL
                OR NEW.decision_reason IS NOT NULL OR NEW.withdrawn_by IS NOT NULL OR NEW.withdrawn_at IS NOT NULL
                OR NEW.withdrawal_reason IS NOT NULL THEN
            RAISE EXCEPTION 'a merchant BNPL enablement is born PROPOSED and undecided: none is enabled by insert (P12-TSK-006)';
        END IF;
        NEW.proposed_at := clock_timestamp();
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a merchant BNPL enablement is never deleted: its end is a status (P12-TSK-006)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.merchant_id IS DISTINCT FROM OLD.merchant_id
            OR NEW.proposed_by IS DISTINCT FROM OLD.proposed_by
            OR NEW.proposed_at IS DISTINCT FROM OLD.proposed_at
            OR NEW.proposal_reason IS DISTINCT FROM OLD.proposal_reason THEN
        RAISE EXCEPTION 'a merchant BNPL enablement''s proposal is frozen: the approver approves what was proposed (P12-TSK-006, INV-AUD-04)';
    END IF;
    IF NOT ((OLD.status = 'PROPOSED' AND NEW.status IN ('ACTIVE', 'REJECTED'))
            OR (OLD.status = 'ACTIVE' AND NEW.status IN ('WITHDRAWN'))) THEN
        RAISE EXCEPTION 'a merchant BNPL enablement moves PROPOSED -> {ACTIVE, REJECTED}, ACTIVE -> {WITHDRAWN}; % -> % is not an edge (P12-TSK-006)', OLD.status, NEW.status;
    END IF;
    IF OLD.status = 'PROPOSED' THEN
        IF NEW.withdrawn_by IS NOT NULL OR NEW.withdrawn_at IS NOT NULL OR NEW.withdrawal_reason IS NOT NULL THEN
            RAISE EXCEPTION 'a proposal is decided before anything is withdrawn (P12-TSK-006)';
        END IF;
        NEW.decided_at := GREATEST(clock_timestamp(), OLD.proposed_at);
    ELSE
        IF NEW.decided_by IS DISTINCT FROM OLD.decided_by
                OR NEW.decided_at IS DISTINCT FROM OLD.decided_at
                OR NEW.decision_reason IS DISTINCT FROM OLD.decision_reason THEN
            RAISE EXCEPTION 'a merchant BNPL enablement''s decision is frozen; a withdrawal records only itself (P12-TSK-006)';
        END IF;
        NEW.withdrawn_at := GREATEST(clock_timestamp(), OLD.decided_at);
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER merchant_bnpl_enablement_permits_only_machine_edges
    BEFORE INSERT OR UPDATE OR DELETE ON merchant.merchant_bnpl_enablement
    FOR EACH ROW
    EXECUTE FUNCTION merchant.merchant_bnpl_enablement_permits_only_machine_edges();

-- One function for every V010 table's "never truncated" - TRUNCATE fires no row trigger.
CREATE FUNCTION merchant.bnpl_records_are_never_truncated() RETURNS trigger
LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'merchant BNPL records are never truncated (P12-TSK-006). Table %', TG_TABLE_NAME;
END;
$$;

CREATE TRIGGER merchant_bnpl_enablement_is_never_truncated
    BEFORE TRUNCATE ON merchant.merchant_bnpl_enablement
    FOR EACH STATEMENT
    EXECUTE FUNCTION merchant.bnpl_records_are_never_truncated();

-- ---------------------------------------------------------------------------------------------------------------------
-- The BNPL fee schedule per (merchant, product): a pointer at an existing fee_schedule whose versions return their fee.
-- ---------------------------------------------------------------------------------------------------------------------
CREATE TABLE merchant.merchant_bnpl_fee_schedule (
    merchant_id     uuid        NOT NULL REFERENCES merchant.merchant (id),
    product         text        NOT NULL
        CONSTRAINT merchant_bnpl_fee_schedule_product_is_known
            CHECK (product IN ('BNPL_PAY_IN_4', 'BNPL_PAY_IN_N')),
    fee_schedule_id uuid        NOT NULL REFERENCES merchant.fee_schedule (id),
    -- Application-supplied from one injected Clock (V004's merchant_fee_schedule).
    assigned_at     timestamptz NOT NULL,
    assigned_by     text        NOT NULL
        CONSTRAINT merchant_bnpl_fee_schedule_assigned_by_bounded
            CHECK (length(assigned_by) BETWEEN 1 AND 200),
    -- One schedule per (merchant, product), total, by the key itself.
    CONSTRAINT merchant_bnpl_fee_schedule_one_per_product PRIMARY KEY (merchant_id, product)
);

CREATE INDEX merchant_bnpl_fee_schedule_by_schedule ON merchant.merchant_bnpl_fee_schedule (fee_schedule_id);

-- A BNPL fee is pro-rated back on refund (ADR-0105 decision 3): a schedule with any version that retains its fee is
-- refused for every writer. A RETAINED version added LATER cannot be refused here (a schedule row cannot be locked,
-- V004); it is refused where it would price - BnplPricing's standing and pin, and the pin's trigger below.
CREATE FUNCTION merchant.merchant_bnpl_fee_schedule_returns_its_fee() RETURNS trigger
LANGUAGE plpgsql AS
$$
BEGIN
    IF EXISTS (SELECT 1 FROM merchant.fee_schedule_version v
                WHERE v.fee_schedule_id = NEW.fee_schedule_id AND v.refund_fee_policy <> 'RETURNED') THEN
        RAISE EXCEPTION 'a BNPL fee schedule returns its fee on refund: schedule % has a version that retains it (P12-TSK-006, ADR-0105)', NEW.fee_schedule_id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER merchant_bnpl_fee_schedule_returns_its_fee
    BEFORE INSERT OR UPDATE ON merchant.merchant_bnpl_fee_schedule
    FOR EACH ROW
    EXECUTE FUNCTION merchant.merchant_bnpl_fee_schedule_returns_its_fee();

CREATE TABLE merchant.merchant_bnpl_fee_schedule_event (
    id                   bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    merchant_id          uuid        NOT NULL REFERENCES merchant.merchant (id),
    product              text        NOT NULL
        CONSTRAINT merchant_bnpl_fee_schedule_event_product_is_known
            CHECK (product IN ('BNPL_PAY_IN_4', 'BNPL_PAY_IN_N')),
    -- NULL for a first assignment.
    from_fee_schedule_id uuid        REFERENCES merchant.fee_schedule (id),
    to_fee_schedule_id   uuid        NOT NULL REFERENCES merchant.fee_schedule (id),
    -- A recorded move must move: a converging assignment writes nothing.
    CONSTRAINT merchant_bnpl_fee_schedule_event_moves
        CHECK (from_fee_schedule_id IS NULL OR from_fee_schedule_id <> to_fee_schedule_id),
    reason               text        NOT NULL
        CONSTRAINT merchant_bnpl_fee_schedule_event_reason_bounded
            CHECK (length(reason) BETWEEN 1 AND 1000),
    actor_id             text        NOT NULL
        CONSTRAINT merchant_bnpl_fee_schedule_event_actor_id_bounded
            CHECK (length(actor_id) BETWEEN 1 AND 200),
    actor_type           text        NOT NULL
        CONSTRAINT merchant_bnpl_fee_schedule_event_actor_type_bounded
            CHECK (length(actor_type) BETWEEN 1 AND 50),
    occurred_at          timestamptz NOT NULL
);

CREATE INDEX merchant_bnpl_fee_schedule_event_by_merchant ON merchant.merchant_bnpl_fee_schedule_event (merchant_id);

CREATE TRIGGER merchant_bnpl_fee_schedule_event_is_append_only
    BEFORE UPDATE OR DELETE ON merchant.merchant_bnpl_fee_schedule_event
    FOR EACH ROW
    EXECUTE FUNCTION merchant.fee_definitions_are_immutable();

-- ---------------------------------------------------------------------------------------------------------------------
-- The fee pin: one per BNPL purchase, born in the purchase's transaction, never changed.
-- ---------------------------------------------------------------------------------------------------------------------
CREATE TABLE merchant.bnpl_fee_pin (
    -- bnpl.purchase.id by value; no cross-schema FK by design (ADR-0029).
    purchase_id             uuid        NOT NULL,
    merchant_id             uuid        NOT NULL REFERENCES merchant.merchant (id),
    product                 text        NOT NULL
        CONSTRAINT bnpl_fee_pin_product_is_known
            CHECK (product IN ('BNPL_PAY_IN_4', 'BNPL_PAY_IN_N')),
    fee_schedule_version_id uuid        NOT NULL REFERENCES merchant.fee_schedule_version (id),

    -- What was financed. MoneyColumns' three-column shape (ADR-0003).
    gross_amount_minor BIGINT NOT NULL, gross_currency CHAR(3) NOT NULL, gross_scale SMALLINT NOT NULL, CHECK (gross_currency ~ '^[A-Z]{3}$'), CHECK (gross_scale BETWEEN 0 AND 9),
    CONSTRAINT bnpl_fee_pin_gross_is_positive
        CHECK (gross_amount_minor > 0),

    -- When the version was resolved: the application clock, the fee schedules' own time basis (V004).
    pinned_at               timestamptz NOT NULL,
    pinned_by               text        NOT NULL
        CONSTRAINT bnpl_fee_pin_pinned_by_bounded
            CHECK (length(pinned_by) BETWEEN 1 AND 200),

    -- ONE PIN PER PURCHASE: ten pinners, one pin; the second is refused by the key, never given a second version.
    CONSTRAINT bnpl_fee_pin_one_per_purchase PRIMARY KEY (purchase_id)
);

CREATE INDEX bnpl_fee_pin_by_merchant ON merchant.bnpl_fee_pin (merchant_id);

-- THE PIN NAMES A VERSION THAT COULD HAVE PRICED IT, for every writer: the merchant's BNPL schedule for the product, a
-- version that returns its fee, in the gross's currency, in force by the pin's instant - and the merchant BNPL-enabled.
CREATE FUNCTION merchant.bnpl_fee_pin_names_a_returning_version_in_force() RETURNS trigger
LANGUAGE plpgsql AS
$$
DECLARE
    pinned merchant.fee_schedule_version%ROWTYPE;
BEGIN
    SELECT * INTO pinned FROM merchant.fee_schedule_version WHERE id = NEW.fee_schedule_version_id;
    IF pinned.refund_fee_policy <> 'RETURNED' THEN
        RAISE EXCEPTION 'a BNPL fee pin names a version that returns its fee on refund (P12-TSK-006, ADR-0105)';
    END IF;
    IF pinned.fixed_currency <> NEW.gross_currency THEN
        RAISE EXCEPTION 'a BNPL fee pin''s gross is in its version''s currency (P12-TSK-006, INV-MON-04)';
    END IF;
    IF pinned.effective_from > NEW.pinned_at THEN
        RAISE EXCEPTION 'a BNPL fee pin names a version in force when it was pinned (P12-TSK-006, INV-MER-03)';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM merchant.merchant_bnpl_fee_schedule a
                    WHERE a.merchant_id = NEW.merchant_id AND a.product = NEW.product
                      AND a.fee_schedule_id = pinned.fee_schedule_id) THEN
        RAISE EXCEPTION 'a BNPL fee pin names a version of the merchant''s BNPL schedule for the product (P12-TSK-006)';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM merchant.merchant_bnpl_enablement e
                    WHERE e.merchant_id = NEW.merchant_id AND e.status = 'ACTIVE') THEN
        RAISE EXCEPTION 'a BNPL fee pin is born only for a BNPL-enabled merchant (P12-TSK-006, INV-AUD-04)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER bnpl_fee_pin_names_a_returning_version_in_force
    BEFORE INSERT ON merchant.bnpl_fee_pin
    FOR EACH ROW
    EXECUTE FUNCTION merchant.bnpl_fee_pin_names_a_returning_version_in_force();

-- IMMUTABILITY, BOUND TO EVERY WRITER INCLUDING THE MIGRATOR - V004's function, reused (V005's precedent).
CREATE TRIGGER bnpl_fee_pin_is_immutable
    BEFORE UPDATE OR DELETE ON merchant.bnpl_fee_pin
    FOR EACH ROW
    EXECUTE FUNCTION merchant.fee_definitions_are_immutable();

CREATE TRIGGER bnpl_fee_pin_is_never_truncated
    BEFORE TRUNCATE ON merchant.bnpl_fee_pin
    FOR EACH STATEMENT
    EXECUTE FUNCTION merchant.bnpl_records_are_never_truncated();

COMMENT ON TABLE merchant.merchant_bnpl_enablement IS
    'Whether a merchant may offer BNPL (P12-TSK-006, OQ-8): PROPOSED -> ACTIVE (a second, distinct operator; INV-AUD-04) -> WITHDRAWN (any one operator), or PROPOSED -> REJECTED. One live (proposed or active) per merchant. Each decision whole and frozen on the row; every instant the database''s, after the lock. Reasons RESTRICTED-PII, never served.';
COMMENT ON TABLE merchant.merchant_bnpl_fee_schedule IS
    'Which fee schedule prices a merchant''s BNPL product - a pointer per (merchant, product), moved under the merchant row''s lock; the schedule''s versions return their fee on refund (ADR-0105).';
COMMENT ON TABLE merchant.merchant_bnpl_fee_schedule_event IS
    'Append-only history of BNPL fee schedule assignments with the operator''s required reason. SELECT and INSERT only.';
COMMENT ON TABLE merchant.bnpl_fee_pin IS
    'Which fee schedule version prices one BNPL purchase (INV-MER-03, INV-HIST-04): born in the purchase''s transaction, one per purchase by the key, never changed. The fee and the net are the version applied to the gross - never stored.';

-- THE GRANTS ARRIVE WITH THE TABLES. The enablement: the decision and withdrawal columns only (the trigger stamps the
-- instants, so the application role names none); no DELETE.
GRANT SELECT, INSERT ON merchant.merchant_bnpl_enablement TO finapp_app;
GRANT UPDATE (status, decided_by, decision_reason, withdrawn_by, withdrawal_reason)
    ON merchant.merchant_bnpl_enablement TO finapp_app;

-- The pointer moves; nothing else about it does (V004's merchant_fee_schedule).
GRANT SELECT, INSERT ON merchant.merchant_bnpl_fee_schedule TO finapp_app;
GRANT UPDATE (fee_schedule_id, assigned_at, assigned_by) ON merchant.merchant_bnpl_fee_schedule TO finapp_app;
GRANT SELECT, INSERT ON merchant.merchant_bnpl_fee_schedule_event TO finapp_app;

-- The pin: SELECT and INSERT only - the first rank of its immutability.
GRANT SELECT, INSERT ON merchant.bnpl_fee_pin TO finapp_app;
