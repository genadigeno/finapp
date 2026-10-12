-- Daily interest accrual and the servicing permit (P11-TSK-016; PHASE_11_PLAN.md sections 7.1 T-svc, 7.2, 7.5, 8, 12.3
-- and 12.8 rule 2, LENDING_LIFECYCLES.md section 4.11; ADR-0094; INV-LND-02, INV-LND-09, INV-LND-10, INV-IDEM-02,
-- INV-BAL-05). The number is V010: lending V009 is P11-TSK-015's (the external payout), built in parallel.
--
-- The servicing step (T-svc), one account at a time: under the loan's row lock (L3), the database clock read after it,
-- every elapsed date d of the account - the date has ended in the agreement's pinned servicing zone on the database
-- clock, never an instance clock - is accrued oldest first from the accrual start while principal outstanding at the end
-- of d is positive, the base LOAN_PRINCIPAL + LOAN_PRINCIPAL_DUE over journal lines with value_date <= d (never interest
-- or fees, never the projection). Each date writes, in the step's one transaction:
--
--     interest_accrual  - the date's row: the base, the period's exact cumulative interest, what the date posts (the
--                         change in the period's once-rounded cumulative figure - ACCRUAL_ENGINE_V1), the period it is
--                         rounded within, the agreement version and the accrual engine version it was computed under,
--                         and the journal entry of posting rule 2 by value - null for a zero day, which writes the row
--                         and no entry.
--     the ledger entry  - DR LOAN_INTEREST_ACCRUED / CR LOAN_INTEREST_INCOME under lending.accrual:<loan>:<date>,
--                         posting date = value date = the accrual date (through the ledger's PostingService).
--
-- And the permit: loan.next_servicing_at, which the leaderless servicing sweep claims on (ACTIVE and CLOSING accounts
-- whose permit has come, in one FOR UPDATE SKIP LOCKED statement), re-stamped by the claim to a retry cadence and by a
-- completed step to the start of the next business date in the pinned zone.
--
-- Held for EVERY writer: interest_accrual UNIQUE (loan_id, accrual_date) - one row per account and date (INV-LND-02) -
-- and UNIQUE (journal_entry_id); a row only for an ACTIVE or CLOSING account, for a date that has ENDED on the database
-- clock in its pinned zone (INV-LND-10: never early), on or after the account's accrual start (an instalment loan's
-- accrual_start row - none, nothing accrues; a line's opening business date), after every date already accrued (oldest
-- first), inside its period, under the account's own agreement version in its currency and scale and its pinned accrual
-- engine; a positive base; the row INSERT-only and never truncated. The loan's edge function replaced to admit one more
-- move - the permit alone, for an ACTIVE or CLOSING account.

-- ---------------------------------------------------------------------------------------------
-- The servicing permit.
-- ---------------------------------------------------------------------------------------------
-- Existing rows take the migration's instant (due at once); from here the edge function stamps every new loan's.
ALTER TABLE lending.loan ADD COLUMN next_servicing_at timestamptz NOT NULL DEFAULT statement_timestamp();
ALTER TABLE lending.loan ALTER COLUMN next_servicing_at DROP DEFAULT;

-- The servicing sweep's claim: the accounts in servicing by permit.
CREATE INDEX loan_servicing_due ON lending.loan (next_servicing_at, id) WHERE status IN ('ACTIVE', 'CLOSING');

COMMENT ON COLUMN lending.loan.next_servicing_at IS
    'The servicing sweep''s permit (P11-TSK-016, PHASE_11_PLAN.md section 7.5): an ACTIVE or CLOSING account is claimed once it has come on the database clock. Stamped at birth by the edge function; re-stamped by the claim to the retry cadence and by a completed servicing step to the start of the next business date in the pinned zone. A permit, never a fact: no figure and no decision rests on it.';

-- ---------------------------------------------------------------------------------------------
-- The loan's edge function, replaced: V008's edges, plus the permit-only move and the permit stamped at birth.
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
    -- Every other edge is a later task's: a line's closure request (P11-TSK-023), the closure (P11-TSK-026) - each
    -- replaces this function and admits its edge beside its own fact. Out of CANCELLED nothing, ever (INV-LIFE-04);
    -- ACTIVE -> CANCELLED and ACTIVE -> PENDING_DISBURSEMENT never (LENDING_LIFECYCLES.md section 4.5).
    RAISE EXCEPTION 'a loan does not move here; % -> % is not an edge until its task admits it (P11-TSK-014)', OLD.status, NEW.status;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The interest accrual: one row per account and date.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE lending.interest_accrual (
    id                       uuid            NOT NULL,
    loan_id                  uuid            NOT NULL,
    accrual_date             date            NOT NULL,
    period_start             date            NOT NULL,
    period_end               date            NOT NULL,
    currency                 char(3)         NOT NULL,
    scale                    smallint        NOT NULL,
    base_minor               bigint          NOT NULL,
    cumulative_exact         numeric(40, 20) NOT NULL,
    posted_minor             bigint          NOT NULL,
    agreement_id             uuid            NOT NULL,
    agreement_version        integer         NOT NULL,
    accrual_engine_version   smallint        NOT NULL,
    journal_entry_id         uuid,
    created_at               timestamptz     NOT NULL,
    correlation_id           text            NOT NULL,
    CONSTRAINT interest_accrual_pk PRIMARY KEY (id),
    -- One accrual per account and date (INV-LND-02, scenario 4) - the arbiter beneath L3 and the ledger key.
    CONSTRAINT interest_accrual_date_unique UNIQUE (loan_id, accrual_date),
    -- One accrual per journal entry: the ledger key gives one entry per (account, date), this one row per entry.
    CONSTRAINT interest_accrual_entry_unique UNIQUE (journal_entry_id),
    CONSTRAINT interest_accrual_loan_fk FOREIGN KEY (loan_id) REFERENCES lending.loan (id),
    CONSTRAINT interest_accrual_agreement_fk FOREIGN KEY (agreement_id) REFERENCES lending.loan_agreement (id),
    CONSTRAINT interest_accrual_currency_is_the_products CHECK (currency = 'EUR' AND scale BETWEEN 0 AND 9),
    -- The period it is rounded within (an instalment period or a statement cycle - AccrualPeriod) holds the date, and is
    -- at most two months long: the longest a monthly calendar makes is an instalment loan's first period - up to 28 days'
    -- minimum (the terms' bound) and then up to 31 to the intended day - and every later period, a post-maturity month and
    -- a line's cycle are a month at most. Never a span a cumulative figure could drift across (Act365Fixed: shorter than
    -- 73 days, the length at which a scale-20 daily sum could round differently).
    CONSTRAINT interest_accrual_period_holds_the_date CHECK (
        period_start <= accrual_date AND accrual_date < period_end AND period_end - period_start <= 62),
    -- A row exactly while principal is outstanding (a positive base); the cumulative figure and the posting not
    -- negative; an entry exactly when the date posts something - a zero day writes the row and no entry.
    CONSTRAINT interest_accrual_amounts_shape CHECK (base_minor > 0 AND cumulative_exact >= 0 AND posted_minor >= 0),
    CONSTRAINT interest_accrual_entry_when_posted CHECK ((posted_minor > 0) = (journal_entry_id IS NOT NULL)),
    CONSTRAINT interest_accrual_versions_numbered CHECK (agreement_version >= 1 AND accrual_engine_version >= 1),
    CONSTRAINT interest_accrual_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

COMMENT ON TABLE lending.interest_accrual IS
    'A day''s interest earned (P11-TSK-016; ADR-0094, PHASE_11_PLAN.md section 12.3): one row per account and elapsed date, oldest first from the accrual start while principal is outstanding - the base (LOAN_PRINCIPAL + LOAN_PRINCIPAL_DUE over journal lines with value date on or before the date), the period''s exact cumulative interest at scale 20, what the date posts (ACCRUAL_ENGINE_V1''s cumulative rounding), the period it is rounded within, the agreement version and accrual engine version it was computed under, and posting rule 2''s journal entry by value (DR LOAN_INTEREST_ACCRUED / CR LOAN_INTEREST_INCOME, key lending.accrual:<loan>:<date>) - null for a zero day. Explains LOAN_INTEREST_ACCRUED with the billings (the subledger proof, P11-TSK-029). INSERT-only for every role. RESTRICTED-FINANCIAL.';

CREATE OR REPLACE FUNCTION lending.interest_accrual_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    account lending.loan%ROWTYPE;
    zone text;
    agreement lending.loan_agreement%ROWTYPE;
    started date;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'an interest accrual is immutable from insert, for every role (P11-TSK-016, INV-LND-02)';
    END IF;
    -- An account in servicing, and its pinned servicing zone.
    SELECT l.* INTO account FROM lending.loan l WHERE l.id = NEW.loan_id;
    SELECT t.servicing_zone INTO zone
      FROM lending.loan l
      JOIN lending.loan_offer o ON o.id = l.offer_id
      JOIN lending.loan_terms t ON t.terms_version_id = o.terms_version_id
     WHERE l.id = NEW.loan_id;
    IF account.id IS NULL OR account.status NOT IN ('ACTIVE', 'CLOSING') OR zone IS NULL THEN
        RAISE EXCEPTION 'interest accrues only on an ACTIVE or CLOSING account (P11-TSK-016)';
    END IF;
    -- Never early (INV-LND-10): the date has ended in the pinned zone on the database clock.
    IF NEW.accrual_date >= lending.business_date(statement_timestamp(), zone) THEN
        RAISE EXCEPTION 'a date accrues only once it has ended on the database clock in its pinned zone (P11-TSK-016, INV-LND-10)';
    END IF;
    -- From the accrual start: an instalment loan's accrual_start row (none - nothing accrues: path X awaiting its payout),
    -- a line's opening business date.
    IF account.kind = 'INSTALMENT' THEN
        SELECT s.start_date INTO started FROM lending.accrual_start s WHERE s.loan_id = NEW.loan_id;
    ELSE
        started := lending.business_date(account.opened_at, zone);
    END IF;
    IF started IS NULL OR NEW.accrual_date < started THEN
        RAISE EXCEPTION 'interest accrues from the account''s accrual start, and not before it (P11-TSK-016, ADR-0094)';
    END IF;
    -- Oldest first: never a date at or before one already accrued.
    IF EXISTS (SELECT 1 FROM lending.interest_accrual a
                WHERE a.loan_id = NEW.loan_id AND a.accrual_date >= NEW.accrual_date) THEN
        RAISE EXCEPTION 'an account''s dates accrue oldest first, each once (P11-TSK-016, INV-LND-02)';
    END IF;
    -- Under the account's own agreement version, in its currency and scale, by its pinned accrual engine.
    SELECT a.* INTO agreement FROM lending.loan_agreement a
     WHERE a.id = NEW.agreement_id AND a.loan_id = NEW.loan_id AND a.version = NEW.agreement_version;
    IF agreement.id IS NULL OR NEW.currency <> agreement.currency OR NEW.scale <> agreement.scale
            OR NEW.accrual_engine_version <> agreement.accrual_engine_version THEN
        RAISE EXCEPTION 'an accrual names its account''s agreement version, its currency and scale and its pinned accrual engine (P11-TSK-016, INV-LND-09)';
    END IF;
    NEW.created_at := statement_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER interest_accrual_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON lending.interest_accrual
    FOR EACH ROW
    EXECUTE FUNCTION lending.interest_accrual_is_born_once();

CREATE TRIGGER interest_accrual_is_never_truncated
    BEFORE TRUNCATE ON lending.interest_accrual
    FOR EACH STATEMENT
    EXECUTE FUNCTION lending.loan_is_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The grants: the permit column on the loan; the accrual SELECT and INSERT only. Never DELETE.
-- ---------------------------------------------------------------------------------------------
GRANT UPDATE (next_servicing_at) ON lending.loan TO finapp_app;
GRANT SELECT, INSERT ON lending.interest_accrual TO finapp_app;
