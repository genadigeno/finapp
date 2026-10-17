-- A checkout session completed by a BNPL purchase (P12-TSK-007, ADR-0101, PHASE_12_PLAN.md section 5.2a,
-- BNPL_LIFECYCLES.md section 4.4; INV-MER-06, INV-BNPL-03, INV-LIFE-02/-04).
--
-- ONE EDGE, NO STATE. The session stays OPEN while a BNPL application and offer run - the card door must stay usable
-- if BNPL is declined - and a BNPL purchase completes it OPEN -> COMPLETED "by financing", intent-less, in the
-- purchase's own transaction (P12-TSK-013). A financed session is as paid as a captured one: one order, whatever paid
-- for it, born with the financing journal entry as its captured_entry_ref.
--
-- EVERY GENERATED LIST BELOW HAS ONE DEFINITION: the trigger's edge conditions come from
-- CheckoutSessionStatus.permittedTransitions() (OPEN now reaches COMPLETED), and the financing edge is
-- CheckoutSessionStatus.isFinancingEdge(). CheckoutSessionMigrationTest reads THIS file's function as the trigger's
-- current definition and fails the build if it and the code disagree.
--
-- THE EVIDENCE, AT EVERY RANK. The aggregate refuses first (CheckoutSession.completeByFinancing), the store's
-- conditional write re-judges, and this migration binds every writer, the migrator included:
--   * financed_by_ref is SET ONCE, and only on the financing edge;
--   * OPEN -> COMPLETED requires it, never beside a payment intent, and only before the deadline on the database clock;
--   * a financed session is COMPLETED and nothing else, so there is no COMPLETED_LATE by financing (no money lands
--     late - the financing exists only in the commit that completes the session) and no PAYMENT_PENDING with one;
--   * one session per purchase (a partial unique index), as V003 holds one session per intent;
--   * a session committed COMPLETED by financing commits only beside its order (a deferred check, lending's shape).
-- The other half of "beside its evidence" - that the purchase and its financing exist - is bnpl's (P12-TSK-013): no
-- cross-schema read here (ADR-0029), and the purchase carries the session it financed as this column carries it.

ALTER TABLE checkout.checkout_session
    -- bnpl.purchase.id by value (ADR-0029): the BNPL purchase that financed the sale. NULL for every session a payment
    -- completes, or none does.
    ADD COLUMN financed_by_ref uuid;

-- NEVER BOTH, NEVER LATE, NEVER PENDING. A financed session is COMPLETED and carries no payment intent; it is
-- terminal, so nothing can move it again.
ALTER TABLE checkout.checkout_session
    ADD CONSTRAINT checkout_session_financed_is_completed_without_intent
        CHECK (financed_by_ref IS NULL OR (status = 'COMPLETED' AND payment_intent_ref IS NULL));

-- ONE SESSION PER PURCHASE (INV-BNPL-03, checkout's half), V003's shape for intents. Partial, so the sessions no
-- purchase financed are absent from it.
CREATE UNIQUE INDEX checkout_session_one_session_per_financing
    ON checkout.checkout_session (financed_by_ref)
    WHERE financed_by_ref IS NOT NULL;

-- The machine's edges, replaced whole (the function is V002's; applied history is never edited). Everything V002
-- held is held unchanged - the frozen offer, the intent set once, the edge list - and three clauses join it.
CREATE OR REPLACE FUNCTION checkout.checkout_session_permits_only_machine_edges() RETURNS trigger
LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.merchant_ref IS DISTINCT FROM OLD.merchant_ref
            OR NEW.amount_minor IS DISTINCT FROM OLD.amount_minor
            OR NEW.amount_currency IS DISTINCT FROM OLD.amount_currency
            OR NEW.amount_scale IS DISTINCT FROM OLD.amount_scale
            OR NEW.line_summary IS DISTINCT FROM OLD.line_summary
            OR NEW.fee_schedule_version_ref IS DISTINCT FROM OLD.fee_schedule_version_ref
            OR NEW.token_hash IS DISTINCT FROM OLD.token_hash
            OR NEW.algorithm IS DISTINCT FROM OLD.algorithm
            OR NEW.expires_at IS DISTINCT FROM OLD.expires_at
            OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'a checkout session''s offer is frozen: only status, status_changed_at, the one payment intent reference and the one financing reference ever change (INV-HIST-01)';
    END IF;
    IF OLD.payment_intent_ref IS NOT NULL
            AND NEW.payment_intent_ref IS DISTINCT FROM OLD.payment_intent_ref THEN
        RAISE EXCEPTION 'a checkout session opens ONE payment intent and keeps it: the reference is set once (ADR-0053 section 3)';
    END IF;
    -- P12-TSK-007: the financing reference is set once, and only by the financing edge.
    IF OLD.financed_by_ref IS NOT NULL
            AND NEW.financed_by_ref IS DISTINCT FROM OLD.financed_by_ref THEN
        RAISE EXCEPTION 'a checkout session is financed by ONE purchase and keeps it: the financing reference is set once (P12-TSK-007, INV-BNPL-03)';
    END IF;
    IF OLD.financed_by_ref IS NULL AND NEW.financed_by_ref IS NOT NULL
            AND NOT (OLD.status = 'OPEN' AND NEW.status = 'COMPLETED') THEN
        RAISE EXCEPTION 'a financing reference is set only by the financing edge OPEN -> COMPLETED (P12-TSK-007)';
    END IF;
    IF NEW.status = OLD.status THEN
        RETURN NEW;
    END IF;
    IF NOT ((OLD.status = 'OPEN' AND NEW.status IN ('PAYMENT_PENDING', 'COMPLETED', 'EXPIRED', 'ABANDONED'))
            OR (OLD.status = 'PAYMENT_PENDING' AND NEW.status IN ('COMPLETED', 'EXPIRED'))
            OR (OLD.status = 'EXPIRED' AND NEW.status IN ('COMPLETED_LATE'))) THEN
        RAISE EXCEPTION 'a checkout session moves only along the machine''s edges: OPEN -> {PAYMENT_PENDING, COMPLETED by financing, EXPIRED, ABANDONED}, PAYMENT_PENDING -> {COMPLETED, EXPIRED}, EXPIRED -> {COMPLETED_LATE} (ADR-0053, INV-LIFE-02/-04)';
    END IF;
    -- P12-TSK-007: THE FINANCING EDGE BESIDE ITS EVIDENCE. Intent-less, with its financing reference, and only before
    -- the deadline on the database clock - statement_timestamp() of this UPDATE, which runs under the row lock the
    -- caller already holds, the same instant the store's conditional write judges.
    IF OLD.status = 'OPEN' AND NEW.status = 'COMPLETED' THEN
        IF NEW.financed_by_ref IS NULL OR NEW.payment_intent_ref IS NOT NULL THEN
            RAISE EXCEPTION 'the machine''s OPEN -> COMPLETED edge is by financing only: it carries its financing reference and no payment intent (P12-TSK-007, INV-BNPL-03)';
        END IF;
        IF OLD.expires_at <= statement_timestamp() THEN
            RAISE EXCEPTION 'the machine''s financing edge closes at the deadline: an expired checkout session is not financed (P12-TSK-007, INV-LIFE-04)';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

-- A SESSION COMPLETED BY FINANCING COMMITS ONLY BESIDE ITS ORDER - deferred, so the order inserted after the edge in
-- the same transaction satisfies it (lending V008's shape). A raw edge with a financing reference and no order is
-- refused at commit for every writer: "completed by financing" cannot exist without the commercial fact it produces.
CREATE FUNCTION checkout.checkout_session_financed_beside_its_order() RETURNS trigger
LANGUAGE plpgsql AS
$$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM checkout.checkout_order o WHERE o.session_ref = NEW.id) THEN
        RAISE EXCEPTION 'a checkout session committed COMPLETED by financing without its order (P12-TSK-007, INV-MER-06)';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER checkout_session_financed_beside_its_order
    AFTER UPDATE ON checkout.checkout_session
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (OLD.status = 'OPEN' AND NEW.status = 'COMPLETED')
    EXECUTE FUNCTION checkout.checkout_session_financed_beside_its_order();

COMMENT ON COLUMN checkout.checkout_session.financed_by_ref IS
    'bnpl.purchase.id by value (ADR-0029): SET ONCE, only on the financing edge OPEN -> COMPLETED, never beside a payment intent, UNIQUE across sessions (P12-TSK-007, INV-BNPL-03). NULL for every session a payment completes.';
COMMENT ON COLUMN checkout.checkout_order.captured_entry_ref IS
    'The ledger journal entry that paid for this order, by value (ADR-0029): the capture''s entry (ADR-0050 section 3''s four lines), or - for a session completed by financing - the BNPL financing entry F1 (P12-TSK-007). The traceable chain''s last link.';

-- The grant arrives with the column: the fourth column that ever legitimately changes, beside V002's three.
GRANT UPDATE (financed_by_ref) ON checkout.checkout_session TO finapp_app;
