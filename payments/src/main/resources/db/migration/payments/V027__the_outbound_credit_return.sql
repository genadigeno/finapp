-- =============================================================================================
-- P9-TSK-023 - the outbound credit's return, born once (PHASE_9_PLAN.md sections 12.4(i) and 12.9.3, the
-- lifecycle document section 4; INV-XB-04, INV-REC-09, INV-IDEM-04).
--
-- A cross-border credit's return is one fact whichever channel brings it: the corridor provider's inquiry
-- answer (or its hinted inquiry), the corridor report's PAYOUT_RETURNED line read by the return worker, or a
-- person's four-eyes transfer of a parked return. UNIQUE (outbound_credit_id) is the arbiter - with the posting
-- key beside it - so ten channels make one return. An APPLIER return is exactly the instructed credit coming
-- back - held here for every writer - and only a person's RESOLUTION may record a return that differs.
--
-- The provider's return reference is recorded when the inquiry carries it; the report's return line names the
-- payout by its own references, never the return's, so no execution claim is keyed by it (a deviation from the
-- plan's CROSSBORDER_RETURN claim subject, recorded): this unique is the arbiter on both channels.
-- =============================================================================================

CREATE TABLE payments.outbound_credit_return (
    id                  uuid        PRIMARY KEY,
    outbound_credit_id  uuid        NOT NULL REFERENCES payments.outbound_credit (id),
    amount_minor        bigint      NOT NULL,
    amount_currency     text        NOT NULL,
    amount_scale        smallint    NOT NULL,
    return_reference    text,
    applied_by          text        NOT NULL,
    resolution_id       uuid,
    journal_entry_id    uuid,
    returned_at         timestamptz NOT NULL,
    created_at          timestamptz NOT NULL,
    CONSTRAINT outbound_credit_return_once UNIQUE (outbound_credit_id),
    CONSTRAINT outbound_credit_return_entry_unique UNIQUE (journal_entry_id),
    CONSTRAINT outbound_credit_return_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT outbound_credit_return_currency_shape CHECK (amount_currency ~ '^[A-Z]{3}$'),
    CONSTRAINT outbound_credit_return_reference_shape CHECK (return_reference ~ '^[A-Za-z0-9_.:-]{1,128}$'),
    CONSTRAINT outbound_credit_return_applied_by_is_known CHECK (applied_by IN ('APPLIER', 'RESOLUTION')),
    CONSTRAINT outbound_credit_return_resolution_iff_resolved
        CHECK ((applied_by = 'RESOLUTION') = (resolution_id IS NOT NULL)),
    CONSTRAINT outbound_credit_return_applier_posts CHECK (applied_by = 'RESOLUTION' OR journal_entry_id IS NOT NULL)
);

CREATE OR REPLACE FUNCTION payments.outbound_credit_return_is_born_exact()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    c payments.outbound_credit%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'an outbound credit''s return is append-only (P9-TSK-023)';
    END IF;
    SELECT * INTO c FROM payments.outbound_credit WHERE id = NEW.outbound_credit_id;
    IF NOT FOUND OR c.status <> 'COMPLETED' THEN
        RAISE EXCEPTION 'only a completed outbound credit returns (P9-TSK-023, INV-XB-04)';
    END IF;
    IF NEW.applied_by = 'APPLIER' AND (NEW.amount_minor <> c.amount_minor OR NEW.amount_currency <> c.amount_currency
            OR NEW.amount_scale <> c.amount_scale) THEN
        RAISE EXCEPTION 'an applied return is exactly the instructed credit coming back; any other is a person''s (P9-TSK-023, INV-XB-04)';
    END IF;
    NEW.created_at := statement_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER outbound_credit_return_is_born_exact
    BEFORE INSERT OR UPDATE OR DELETE ON payments.outbound_credit_return
    FOR EACH ROW
    EXECUTE FUNCTION payments.outbound_credit_return_is_born_exact();

GRANT SELECT, INSERT ON payments.outbound_credit_return TO finapp_app;

COMMENT ON TABLE payments.outbound_credit_return IS
    'A cross-border outbound credit''s return, born once (P9-TSK-023, INV-XB-04): UNIQUE (outbound_credit_id) the arbiter of every channel; an APPLIER return equals the instructed credit, held by trigger for every writer; a RESOLUTION return is a person''s four-eyes transfer of a parked return, its fee refund the entry it names.';
