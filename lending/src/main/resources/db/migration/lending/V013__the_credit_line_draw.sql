-- The credit line draw (P11-TSK-021; PHASE_11_PLAN.md sections 7.1 T-drw, 7.2, 7.4, 8, 12.6, 12.8 rule 1 and 12.9,
-- LENDING_LIFECYCLES.md section 4.11; ADR-0100 points 3 and 4, ADR-0096; INV-LND-14, INV-LND-13, INV-LND-01, INV-IDEM-01,
-- INV-BAL-05). The number is V013 - the wave 3 board's reservation: V011 is left free for the servicing-zone fix and V012
-- is P11-TSK-017's (instalment billing), built in parallel.
--
-- The borrower draws against their line to their wallet in ONE transaction (T-drw), under the door's idempotency claim
-- (lending.draw:CUSTOMER:<id>): the line's row lock (L3), the available limit - the agreement's limit less drawn principal
-- (LOAN_PRINCIPAL + LOAN_PRINCIPAL_DUE), derived from journal lines under L3, never a stored number - the currency's
-- LENDING_CAPITAL row lock (L6) and headroom >= the amount (A28), then posting rule 1, DR LOAN_PRINCIPAL / CR
-- CUSTOMER_WALLET, through the ledger's PostingService under lending.draw:<drawId>, and beside it this table's row:
--
--     credit_line_draw - the draw, born whole once its entry exists: the amount in the agreement's currency and scale, the
--                        agreement version whose limit it was judged against, the journal entry by value (no
--                        cross-schema key, ADR-0029), the value date (the business date of the database clock in the
--                        pinned servicing zone, A10) and the customer's own key - UNIQUE (loan_id, draw_key), the
--                        arbiter behind the door's claim: a key retried after its claim has lapsed converges on its draw.
--
-- No column of the loan moves - a draw changes no state (the line stays ACTIVE), so the loan's edge function is NOT
-- replaced here. No available-limit column exists anywhere (ADR-0100 point 4): every draw derives it under L3.
--
-- Held for EVERY writer: UNIQUE (loan_id, draw_key) and UNIQUE (journal_entry_id); a draw only on an ACTIVE credit line
-- (never an instalment loan, never a CLOSING or CLOSED line), under its line's governing agreement version (its highest),
-- in that agreement's currency and scale, for a positive amount at most the limit; its value date the business date of
-- this transaction in the pinned zone; the row INSERT-only and never truncated.

CREATE TABLE lending.credit_line_draw (
    id                  uuid        NOT NULL,
    loan_id             uuid        NOT NULL,
    draw_key            text        NOT NULL,
    currency            char(3)     NOT NULL,
    scale               smallint    NOT NULL,
    amount_minor        bigint      NOT NULL,
    agreement_id        uuid        NOT NULL,
    agreement_version   integer     NOT NULL,
    journal_entry_id    uuid        NOT NULL,
    value_date          date        NOT NULL,
    created_at          timestamptz NOT NULL,
    correlation_id      text        NOT NULL,
    CONSTRAINT credit_line_draw_pk PRIMARY KEY (id),
    -- One draw per line and customer key (INV-IDEM-01): the database arbiter behind the door's claim.
    CONSTRAINT credit_line_draw_key_unique UNIQUE (loan_id, draw_key),
    -- One draw per journal entry: the ledger key lending.draw:<drawId> gives one entry per draw, this one draw per entry.
    CONSTRAINT credit_line_draw_entry_unique UNIQUE (journal_entry_id),
    CONSTRAINT credit_line_draw_loan_fk FOREIGN KEY (loan_id) REFERENCES lending.loan (id),
    CONSTRAINT credit_line_draw_agreement_fk FOREIGN KEY (agreement_id) REFERENCES lending.loan_agreement (id),
    CONSTRAINT credit_line_draw_key_bounded CHECK (char_length(draw_key) BETWEEN 1 AND 200),
    CONSTRAINT credit_line_draw_currency_is_the_products CHECK (currency = 'EUR' AND scale BETWEEN 0 AND 9),
    CONSTRAINT credit_line_draw_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT credit_line_draw_version_numbered CHECK (agreement_version >= 1),
    CONSTRAINT credit_line_draw_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

COMMENT ON TABLE lending.credit_line_draw IS
    'A credit line draw (P11-TSK-021; ADR-0100 point 3, PHASE_11_PLAN.md section 12.6): the borrower''s draw to their wallet, born once its posting exists - the amount in the agreement''s currency and scale, the agreement version whose limit it was judged against (the available limit, limit less LOAN_PRINCIPAL + LOAN_PRINCIPAL_DUE, derived from journal lines under the line''s row lock, never stored - INV-LND-14), posting rule 1''s journal entry by value (DR LOAN_PRINCIPAL / CR CUSTOMER_WALLET, key lending.draw:<id>), its value date and the customer''s key (UNIQUE per line). Explains LOAN_PRINCIPAL''s debits with the billings and paydowns (the subledger proof, P11-TSK-029). INSERT-only for every role. RESTRICTED-FINANCIAL.';

CREATE OR REPLACE FUNCTION lending.credit_line_draw_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    account lending.loan%ROWTYPE;
    zone text;
    agreement lending.loan_agreement%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a credit line draw is immutable from insert, for every role (P11-TSK-021, INV-LND-14)';
    END IF;
    -- An ACTIVE credit line, and its pinned servicing zone.
    SELECT l.* INTO account FROM lending.loan l WHERE l.id = NEW.loan_id;
    SELECT t.servicing_zone INTO zone
      FROM lending.loan l
      JOIN lending.loan_offer o ON o.id = l.offer_id
      JOIN lending.loan_terms t ON t.terms_version_id = o.terms_version_id
     WHERE l.id = NEW.loan_id;
    IF account.id IS NULL OR account.kind <> 'REVOLVING' OR account.status <> 'ACTIVE' OR zone IS NULL THEN
        RAISE EXCEPTION 'a draw is made only on an ACTIVE credit line (P11-TSK-021, INV-LND-14)';
    END IF;
    -- Under the line's governing agreement version - its highest - in its currency and scale, never above its limit.
    SELECT a.* INTO agreement FROM lending.loan_agreement a
     WHERE a.id = NEW.agreement_id AND a.loan_id = NEW.loan_id AND a.version = NEW.agreement_version;
    IF agreement.id IS NULL
            OR NEW.agreement_version <> (SELECT max(x.version) FROM lending.loan_agreement x WHERE x.loan_id = NEW.loan_id)
            OR NEW.currency <> agreement.currency OR NEW.scale <> agreement.scale
            OR NEW.amount_minor > agreement.amount_minor THEN
        RAISE EXCEPTION 'a draw names its line''s governing agreement version, in its currency and scale, and never exceeds its limit (P11-TSK-021, INV-LND-14)';
    END IF;
    -- The value date is this transaction's business date in the pinned servicing zone (A10, INV-LND-10): read by the step
    -- after its locks, so between the transaction's start and this statement.
    IF NEW.value_date < lending.business_date(transaction_timestamp(), zone)
            OR NEW.value_date > lending.business_date(statement_timestamp(), zone) THEN
        RAISE EXCEPTION 'a draw''s value date is the business date of the database clock in its pinned zone (P11-TSK-021, A10)';
    END IF;
    NEW.created_at := statement_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER credit_line_draw_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON lending.credit_line_draw
    FOR EACH ROW
    EXECUTE FUNCTION lending.credit_line_draw_is_born_once();

CREATE TRIGGER credit_line_draw_is_never_truncated
    BEFORE TRUNCATE ON lending.credit_line_draw
    FOR EACH STATEMENT
    EXECUTE FUNCTION lending.loan_is_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The grants: SELECT and INSERT only. Never UPDATE, never DELETE.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON lending.credit_line_draw TO finapp_app;
