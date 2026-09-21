-- The merchant and its lifecycle history (P6-TSK-003, ADR-0050…0052).
--
-- EVERY GENERATED LIST BELOW HAS ONE DEFINITION: the status CHECKs come from
-- MerchantStatus.sqlValueList(), the transition trigger's edge conditions from
-- MerchantStatus.permittedTransitions(), the name bounds from Merchant.MAX_NAME_LENGTH.
-- MerchantMigrationTest fails the build if this file and the code disagree (the P0-TSK-022
-- pattern).
--
-- NO CROSS-SCHEMA FOREIGN KEYS, DELIBERATELY (ADR-0029): party_ref references party's tables
-- by value - the onboarding command resolves it from authoritative state through the KYB gate
-- port (INV-KYC-05 consumed), never from a request.
--
-- NO BALANCE COLUMN, EVER (INV-MER-02): what the platform owes this merchant is the
-- MERCHANT_PAYABLE ledger position created in the onboarding transaction (ledger V011), and it
-- exists nowhere else. A payable, balance or amount-owed column appearing in this schema is a
-- second balance authority - the defect this comment exists so a reviewer refuses.
--
-- THE ACTOR MODEL IS audit_record's from birth (the payments V006 lesson, applied in advance
-- rather than met again): actor_id TEXT + actor_type TEXT, length-bounded - a merchant
-- transition today is always an operator's act, and the vocabulary already speaks for the
-- platform's own acts when a later phase needs one.

CREATE TABLE merchant.merchant (
    -- UUIDv7, minted by the application (ADR-0013).
    id                  uuid        PRIMARY KEY,

    -- party.party.id by value; the KYB gate resolved it (INV-KYC-05, ADR-0029).
    party_ref           uuid        NOT NULL,

    legal_name          text        NOT NULL
        CONSTRAINT merchant_legal_name_bounded
            CHECK (length(legal_name) BETWEEN 1 AND 200),
    display_name        text        NOT NULL
        CONSTRAINT merchant_display_name_bounded
            CHECK (length(display_name) BETWEEN 1 AND 200),

    -- Explicit, always (INV-MON-02); the payable account's currency. CHAR(3), the
    -- MoneyColumns shape. Multi-currency settlement is Phase 9's (PHASE_6_PLAN.md section 17).
    settlement_currency char(3)     NOT NULL
        CONSTRAINT merchant_settlement_currency_shape
            CHECK (settlement_currency ~ '^[A-Z]{3}$'),

    status              text        NOT NULL
        CONSTRAINT merchant_status_is_known
            CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),

    -- Application-supplied from one injected Clock, never DEFAULT now() (DOMAIN_MODEL.md
    -- section Time).
    created_at          timestamptz NOT NULL,
    status_changed_at   timestamptz NOT NULL,

    CONSTRAINT merchant_status_change_is_not_before_creation
        CHECK (status_changed_at >= created_at)
);

-- No one-live-per-party index, DELIBERATELY (PHASE_6_PLAN.md section 4): two shops under one
-- organisation are two merchants; duplicate-command protection is the onboarding's
-- idempotency claim, and a double-submit under two keys is two legitimate acts an operator
-- closes if unintended.
CREATE INDEX merchant_by_party ON merchant.merchant (party_ref);

-- THE MACHINE'S EDGES BIND EVERY WRITER (INV-LIFE-02; the payment machines' idiom): the
-- aggregate refuses first in code, this trigger refuses raw SQL and the migrator alike, and
-- the frozen columns make identity an insert-time fact.
CREATE FUNCTION merchant.merchant_permits_only_machine_edges() RETURNS trigger
LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.party_ref IS DISTINCT FROM OLD.party_ref
            OR NEW.legal_name IS DISTINCT FROM OLD.legal_name
            OR NEW.display_name IS DISTINCT FROM OLD.display_name
            OR NEW.settlement_currency IS DISTINCT FROM OLD.settlement_currency
            OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'a merchant''s identity is frozen: only status and status_changed_at ever change (INV-HIST-01''s discipline)';
    END IF;
    IF NEW.status = OLD.status THEN
        RETURN NEW;
    END IF;
    IF NOT ((OLD.status = 'ACTIVE' AND NEW.status IN ('SUSPENDED', 'CLOSED'))
            OR (OLD.status = 'SUSPENDED' AND NEW.status IN ('ACTIVE'))) THEN
        RAISE EXCEPTION 'a merchant moves only along the machine''s edges: ACTIVE -> {SUSPENDED, CLOSED}, SUSPENDED -> {ACTIVE} (INV-LIFE-02/-04)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER merchant_permits_only_machine_edges
    BEFORE UPDATE ON merchant.merchant
    FOR EACH ROW
    EXECUTE FUNCTION merchant.merchant_permits_only_machine_edges();

CREATE TABLE merchant.merchant_event (
    id          bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- Same schema, so the FK is legal and right.
    merchant_id uuid        NOT NULL REFERENCES merchant.merchant (id),

    from_status text        NOT NULL
        CONSTRAINT merchant_event_from_status_is_known
            CHECK (from_status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    to_status   text        NOT NULL
        CONSTRAINT merchant_event_to_status_is_known
            CHECK (to_status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),

    actor_id    text        NOT NULL
        CONSTRAINT merchant_event_actor_id_bounded
            CHECK (length(actor_id) BETWEEN 1 AND 200),
    actor_type  text        NOT NULL
        CONSTRAINT merchant_event_actor_type_bounded
            CHECK (length(actor_type) BETWEEN 1 AND 50),

    -- Application-supplied, never DEFAULT now().
    occurred_at timestamptz NOT NULL
);

CREATE INDEX merchant_event_by_merchant ON merchant.merchant_event (merchant_id);

COMMENT ON TABLE merchant.merchant IS
    'The commercial counterparty: relationship, names, settlement currency, status - and never a balance (INV-MER-02: the payable is the MERCHANT_PAYABLE ledger position, ledger V011). Nothing but status changes after birth, and the UPDATE grant is those two columns.';
COMMENT ON COLUMN merchant.merchant.party_ref IS
    'party.party.id by value; no cross-schema FK by design (ADR-0029). The KYB gate (INV-KYC-05''s projection) resolved it at onboarding.';
COMMENT ON COLUMN merchant.merchant.status IS
    'ACTIVE -> {SUSPENDED, CLOSED}; SUSPENDED -> {ACTIVE}. CLOSED terminal (INV-LIFE-04). SUSPENDED gates NEW dispatches only - landed money still lands (CHECKOUT_MERCHANT_LIFECYCLES.md section 5).';
COMMENT ON TABLE merchant.merchant_event IS
    'Append-only lifecycle history: the path a merchant took, as evidence rather than memory. Server-assigned order; SELECT and INSERT only.';

-- THE GRANTS ARRIVE WITH THE TABLE (P6-TSK-001's floor). SELECT and INSERT for the row's
-- life; UPDATE narrowed to the two columns that ever legitimately change. No DELETE: a
-- merchant's end is a status, never an absence.
GRANT SELECT, INSERT ON merchant.merchant TO finapp_app;
GRANT UPDATE (status, status_changed_at) ON merchant.merchant TO finapp_app;

-- The history is append-only at the privilege (the audit_record model). No sequence grant:
-- an IDENTITY column's sequence is usable through the table's own INSERT privilege - the
-- payments histories' proven shape.
GRANT SELECT, INSERT ON merchant.merchant_event TO finapp_app;
