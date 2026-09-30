-- The payout return (P8-TSK-019, ADR-0073 section 1; INV-LIFE-04, INV-MER-02, INV-HIST-01).
--
-- A MERCHANT FACT, BORN ONCE, BESIDE A PAYOUT THAT STAYS COMPLETED. The beneficiary bank
-- returned a payout the provider executed: the payout's COMPLETED is terminal and still true
-- (instructed, accepted irrevocably), so the return is a new operation - its own posting,
-- merchant-payout-return:<payoutId>, DR PAYOUT_CLEARING / CR MERCHANT_PAYABLE - and this row
-- records it. RECORDED is its only state, so there is no status column and no machine (the
-- payments.clearing_record shape).
--
-- ONE RETURN PER PAYOUT, OF EXACTLY THE PAYOUT'S MONEY. payout_id is UNIQUE; the money is held
-- equal to the payout's by a COMPOSITE FOREIGN KEY onto merchant_payout's (id, amount_minor,
-- currency, scale) - a CHECK cannot read another row, and ADR-0057 section 7's destination
-- binding is the precedent, legal because both tables are merchant's. A return of any other
-- amount is not this fact: it goes to a person (ADR-0073 section 5).
--
-- ONLY A COMPLETED PAYOUT RETURNS. The service locks the payout and checks COMPLETED; the
-- trigger below is the rule for writers that never ran it. COMPLETED is terminal (V007), so the
-- check can never go stale.
--
-- NO CROSS-SCHEMA FOREIGN KEY: external_item_ref names the reconciliation item whose evidence
-- caused the application, and journal_entry_id the ledger entry - copies, by value (ADR-0064).

-- The composite key's target: trivially unique (id is the primary key), declared so the
-- return's money can be bound to the payout's.
ALTER TABLE merchant.merchant_payout
    ADD CONSTRAINT merchant_payout_money_is_unique UNIQUE (id, amount_minor, currency, scale);

CREATE TABLE merchant.payout_return (
    -- UUIDv7, minted by the application (ADR-0013).
    id                uuid        PRIMARY KEY,
    payout_id         uuid        NOT NULL UNIQUE,
    -- MoneyColumns: minor units, currency, scale (INV-MON-01/-02) - the payout's, by the key.
    amount_minor      bigint      NOT NULL,
    currency          char(3)     NOT NULL,
    scale             smallint    NOT NULL,
    -- The reconciliation item whose evidence won the payout row - by value, no FK.
    external_item_ref uuid        NOT NULL,
    -- The return's own posting - by value, no FK; one return per entry.
    journal_entry_id  uuid        NOT NULL UNIQUE,
    -- The posting date: the stored accepted_on of the item's settlement batch.
    returned_on       date        NOT NULL,
    -- The item's settlement date.
    value_date        date        NOT NULL,
    -- Application-supplied from one injected Clock, never DEFAULT now() (P0-TSK-015).
    recorded_at       timestamptz NOT NULL,

    CONSTRAINT payout_return_is_the_payouts_money
        FOREIGN KEY (payout_id, amount_minor, currency, scale)
        REFERENCES merchant.merchant_payout (id, amount_minor, currency, scale),
    CONSTRAINT payout_return_amount_is_positive CHECK (amount_minor > 0)
);

-- Every writer: only a COMPLETED payout returns.
CREATE FUNCTION merchant.payout_return_names_a_completed_payout()
    RETURNS trigger
LANGUAGE plpgsql AS
$$
BEGIN
    IF NOT EXISTS (SELECT 1
                     FROM merchant.merchant_payout payout
                    WHERE payout.id = NEW.payout_id
                      AND payout.status = 'COMPLETED') THEN
        RAISE EXCEPTION 'only a COMPLETED payout returns: a return is a new fact beside a payout the provider executed (P8-TSK-019, ADR-0073)'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER payout_return_names_a_completed_payout
    BEFORE INSERT ON merchant.payout_return
    FOR EACH ROW
    EXECUTE FUNCTION merchant.payout_return_names_a_completed_payout();

-- Every writer: the fact is append-only.
CREATE FUNCTION merchant.payout_return_is_append_only()
    RETURNS trigger
LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'a payout return is born once and never edited or deleted (INV-HIST-01, ADR-0073)';
END;
$$;

CREATE TRIGGER payout_return_is_append_only
    BEFORE UPDATE OR DELETE ON merchant.payout_return
    FOR EACH ROW
    EXECUTE FUNCTION merchant.payout_return_is_append_only();

COMMENT ON TABLE merchant.payout_return IS
    'A payout the beneficiary bank returned, applied from settlement evidence (ADR-0073): one per payout, of exactly its money (the composite key), its own posting DR PAYOUT_CLEARING / CR MERCHANT_PAYABLE named by journal_entry_id. The payout stays COMPLETED. Append-only for every writer.';
COMMENT ON COLUMN merchant.payout_return.returned_on IS
    'The posting date: the stored accepted_on of the evidence''s settlement batch - never the clock, so a retry on a later day converges on the posting key (ADR-0065 section 6).';

GRANT SELECT, INSERT ON merchant.payout_return TO finapp_app;
