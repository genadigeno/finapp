-- Loan write-off under four eyes (P14-TSK-021; owner decision C6 (A), ratified 2026-11-04; ADR-0096 rule 14, ADR-0104
-- Follow-up; INV-LND-01, INV-REV-04, INV-AUD-04, INV-BAL-03, INV-LIFE-04). The number is V032, reserved for this task.
--
-- A write-off moves a defaulted account's whole receivable to LOAN_WRITE_OFF_EXPENSE. It is an act on OUR books and says
-- nothing about what the customer owes - not paid, not forgiven, not closed. So it is a status of its own, WRITTEN_OFF,
-- with closed_at left NULL and no closure reason (loan_terminal_coherent needs no rebuild: it ties closed_at to CLOSED and
-- CANCELLED only). Nothing leaves it (INV-LIFE-04): the way back is an owner item, stated in LENDING_LIFECYCLES.md.
--
--     loan_write_off           the proposal: born once, INSERT-only - the five receivables as read under L3, the revision
--                              (the count of journal lines on the loan's six accounts when they were read: it only rises,
--                              so a repayment and its reversal cannot round-trip), the DEFAULT condition change it stands on
--                              (a loan cured and defaulted again names a later one), the proposer and their reason.
--     loan_write_off_decision  the decision: born once, INSERT-only, so the row IS the history. APPROVED names the entry
--                              and the date it is valued; REJECTED names neither. Four eyes over the proposer pinned by a
--                              composite foreign key, a CHECK for every writer.
--
-- Lending's triggers never read the ledger (P11-TSK-014's rule). So the triggers hold what the rows can: an ACTIVE or
-- CLOSING account, the latest DEFAULT change still the one named and still defaulted, one live proposal, what the billed
-- items say is due equal to the due figures named, a value date the record leaves open. That the figures are the ledger's
-- is the domain's judgement under L3, and the subledger proof's and the replay's for every writer.
--
-- Every existing writer's status stays admitted: each CHECK below is its predecessor plus WRITTEN_OFF, and no existing row
-- holds it, so each revalidates without touching a row. Every other trigger of the schema judging an account refuses
-- anything but ACTIVE or CLOSING (or ACTIVE), so a written-off loan fails closed at each of them; the servicing permit's
-- arm refuses it too, and the sweep's claim and every lag read filter on ACTIVE and CLOSING.
-- Migrator-owned; schema-qualified throughout and invoker's rights, like V026's, so no search_path is set.

ALTER TABLE lending.loan DROP CONSTRAINT loan_status_is_known;
ALTER TABLE lending.loan ADD CONSTRAINT loan_status_is_known CHECK (
    status IN ('PENDING_DISBURSEMENT', 'ACTIVE', 'CLOSING', 'CLOSED', 'CANCELLED', 'WRITTEN_OFF'));

ALTER TABLE lending.loan DROP CONSTRAINT loan_status_is_the_kinds;
ALTER TABLE lending.loan ADD CONSTRAINT loan_status_is_the_kinds CHECK (
    (kind = 'INSTALMENT' AND status IN ('PENDING_DISBURSEMENT', 'ACTIVE', 'CLOSED', 'CANCELLED', 'WRITTEN_OFF'))
    OR (kind = 'REVOLVING' AND status IN ('ACTIVE', 'CLOSING', 'CLOSED', 'WRITTEN_OFF')));

ALTER TABLE lending.loan_event DROP CONSTRAINT loan_event_edge;
ALTER TABLE lending.loan_event ADD CONSTRAINT loan_event_edge CHECK (
    (from_status IS NULL AND to_status IN ('PENDING_DISBURSEMENT', 'ACTIVE'))
    OR (from_status = 'PENDING_DISBURSEMENT' AND to_status IN ('ACTIVE', 'CANCELLED'))
    OR (from_status = 'ACTIVE' AND to_status IN ('CLOSING', 'CLOSED', 'WRITTEN_OFF'))
    OR (from_status = 'CLOSING' AND to_status IN ('CLOSED', 'WRITTEN_OFF')));

-- ---------------------------------------------------------------------------------------------
-- The proposal.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE lending.loan_write_off (
    id                      uuid        NOT NULL,
    loan_id                 uuid        NOT NULL,
    currency                char(3)     NOT NULL,
    scale                   smallint    NOT NULL,
    principal_minor         bigint      NOT NULL,
    principal_due_minor     bigint      NOT NULL,
    interest_accrued_minor  bigint      NOT NULL,
    interest_due_minor      bigint      NOT NULL,
    fees_due_minor          bigint      NOT NULL,
    revision                bigint      NOT NULL,
    default_event_id        uuid        NOT NULL,
    agreement_version       integer     NOT NULL,
    proposed_by             text        NOT NULL,
    proposed_by_type        text        NOT NULL,
    proposed_at             timestamptz NOT NULL,
    proposal_reason         text        NOT NULL,
    correlation_id          text        NOT NULL,
    CONSTRAINT loan_write_off_pk PRIMARY KEY (id),
    -- The decision's composite key pins its copies of the loan and the proposer to this row's.
    CONSTRAINT loan_write_off_pinned UNIQUE (id, loan_id, proposed_by),
    CONSTRAINT loan_write_off_loan_fk FOREIGN KEY (loan_id) REFERENCES lending.loan (id),
    CONSTRAINT loan_write_off_default_fk FOREIGN KEY (default_event_id) REFERENCES lending.loan_condition_event (id),
    CONSTRAINT loan_write_off_currency_is_the_products CHECK (currency = 'EUR' AND scale BETWEEN 0 AND 9),
    -- Each receivable on its normal side; never partial, never nothing.
    CONSTRAINT loan_write_off_figures_not_negative CHECK (
        principal_minor >= 0 AND principal_due_minor >= 0 AND interest_accrued_minor >= 0 AND interest_due_minor >= 0
        AND fees_due_minor >= 0),
    CONSTRAINT loan_write_off_takes_something CHECK (
        principal_minor::numeric + principal_due_minor + interest_accrued_minor + interest_due_minor + fees_due_minor > 0),
    CONSTRAINT loan_write_off_revision_counted CHECK (revision > 0),
    CONSTRAINT loan_write_off_version_numbered CHECK (agreement_version >= 1),
    CONSTRAINT loan_write_off_actor_type_known CHECK (
        proposed_by_type IN ('SYSTEM', 'CUSTOMER', 'EMPLOYEE', 'SERVICE', 'MERCHANT')),
    CONSTRAINT loan_write_off_actor_bounded CHECK (char_length(proposed_by) BETWEEN 1 AND 200),
    CONSTRAINT loan_write_off_proposal_reasoned CHECK (char_length(proposal_reason) BETWEEN 1 AND 1000),
    CONSTRAINT loan_write_off_proposal_reason_no_instrument_shape CHECK (NOT lending.holds_instrument_shape(proposal_reason)),
    CONSTRAINT loan_write_off_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

CREATE INDEX loan_write_off_by_loan ON lending.loan_write_off (loan_id, proposed_at);
CREATE INDEX loan_write_off_oldest ON lending.loan_write_off (proposed_at, id);

COMMENT ON TABLE lending.loan_write_off IS
    'A loan write-off''s proposal (P14-TSK-021; owner decision C6, ADR-0096 rule 14): the five receivables as read under L3, the revision (journal lines on the loan''s six accounts then), the DEFAULT condition change it stands on, the governing agreement version, the proposer (a holder of LOAN_WRITE_OFF_PROPOSE) and the reason. Born once, INSERT-only for every role; its decision is loan_write_off_decision (none: PROPOSED). One live per loan. A write-off is an act on the books, never forgiveness: what the customer owes is untouched. Amounts RESTRICTED-FINANCIAL; the reason a person''s prose, screened for instrument shapes.';

-- ---------------------------------------------------------------------------------------------
-- The decision.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE lending.loan_write_off_decision (
    id                 uuid        NOT NULL,
    write_off_id       uuid        NOT NULL,
    loan_id            uuid        NOT NULL,
    proposed_by        text        NOT NULL,
    outcome            text        NOT NULL,
    decided_by         text        NOT NULL,
    decided_by_type    text        NOT NULL,
    decided_at         timestamptz NOT NULL,
    decision_reason    text        NOT NULL,
    journal_entry_id   uuid,
    value_date         date,
    recorded_order     bigint,
    correlation_id     text        NOT NULL,
    CONSTRAINT loan_write_off_decision_pk PRIMARY KEY (id),
    CONSTRAINT loan_write_off_decision_write_off_fk FOREIGN KEY (write_off_id, loan_id, proposed_by)
        REFERENCES lending.loan_write_off (id, loan_id, proposed_by),
    -- Decided once: ten approvers, one decision - the arbiter beneath L3.
    CONSTRAINT loan_write_off_decision_once UNIQUE (write_off_id),
    CONSTRAINT loan_write_off_decision_entry_unique UNIQUE (journal_entry_id),
    CONSTRAINT loan_write_off_decision_outcome_is_known CHECK (outcome IN ('APPROVED', 'REJECTED')),
    -- FOUR EYES, for every writer (INV-AUD-04): whoever approved a write-off did not propose it. A rejection may be the
    -- proposer's. The row is the history: it is never updated.
    CONSTRAINT loan_write_off_decision_four_eyes CHECK (outcome <> 'APPROVED' OR decided_by <> proposed_by),
    -- The approval and only the approval posts: it names its entry and the date it is valued.
    CONSTRAINT loan_write_off_decision_approval_posts CHECK (
        (outcome = 'APPROVED') = (journal_entry_id IS NOT NULL) AND (outcome = 'APPROVED') = (value_date IS NOT NULL)),
    CONSTRAINT loan_write_off_decision_actor_type_known CHECK (
        decided_by_type IN ('SYSTEM', 'CUSTOMER', 'EMPLOYEE', 'SERVICE', 'MERCHANT')),
    CONSTRAINT loan_write_off_decision_actor_bounded CHECK (
        char_length(decided_by) BETWEEN 1 AND 200 AND char_length(proposed_by) BETWEEN 1 AND 200),
    CONSTRAINT loan_write_off_decision_reasoned CHECK (char_length(decision_reason) BETWEEN 1 AND 1000),
    CONSTRAINT loan_write_off_decision_reason_no_instrument_shape CHECK (NOT lending.holds_instrument_shape(decision_reason)),
    CONSTRAINT loan_write_off_decision_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

-- A loan is written off once, ever - beneath L3 and beneath the ledger key lending.write-off:<loan>.
CREATE UNIQUE INDEX loan_write_off_one_approved ON lending.loan_write_off_decision (loan_id) WHERE outcome = 'APPROVED';

COMMENT ON TABLE lending.loan_write_off_decision IS
    'A loan write-off''s decision (P14-TSK-021; INV-AUD-04, INV-REV-04): APPROVED by a holder of LOAN_WRITE_OFF_APPROVE who did not propose it (four-eyes CHECK over the proposer copied and pinned by the composite foreign key), naming the entry it posted (rule 14) and its value date - or REJECTED, the door out of a proposal that can no longer post, on any account status. UNIQUE (write_off_id): decided once. INSERT-only for every role, so this row is the history.';

CREATE OR REPLACE FUNCTION lending.loan_write_off_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    account lending.loan%ROWTYPE;
    agreement lending.loan_agreement%ROWTYPE;
    default_change lending.loan_condition_event%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a write-off proposal is a born-once fact (P14-TSK-021): its decision is loan_write_off_decision';
    END IF;
    -- (L3) The account, for every writer.
    SELECT l.* INTO account FROM lending.loan l WHERE l.id = NEW.loan_id FOR UPDATE;
    IF account.id IS NULL OR account.status NOT IN ('ACTIVE', 'CLOSING') THEN
        RAISE EXCEPTION 'a write-off is proposed only on an ACTIVE or CLOSING account (P14-TSK-021)';
    END IF;
    SELECT a.* INTO agreement FROM lending.loan_agreement a
     WHERE a.loan_id = NEW.loan_id
       AND a.version = (SELECT max(x.version) FROM lending.loan_agreement x WHERE x.loan_id = NEW.loan_id);
    IF NEW.agreement_version <> agreement.version OR NEW.currency <> agreement.currency OR NEW.scale <> agreement.scale THEN
        RAISE EXCEPTION 'a write-off names its account''s governing agreement version, in its currency and scale (P14-TSK-021)';
    END IF;
    -- The default it stands on is the account's latest DEFAULT change, and that change set the flag.
    SELECT e.* INTO default_change FROM lending.loan_condition_event e
     WHERE e.loan_id = NEW.loan_id AND e.kind = 'DEFAULT'
     ORDER BY e.business_date DESC LIMIT 1;
    IF default_change.id IS DISTINCT FROM NEW.default_event_id OR default_change.defaulted IS NOT TRUE THEN
        RAISE EXCEPTION 'a write-off stands on its account''s latest DEFAULT change, which set the flag (P14-TSK-021)';
    END IF;
    -- One proposal awaiting a decision per account: a second is lending.ProposalPending.
    IF EXISTS (SELECT 1 FROM lending.loan_write_off w
                WHERE w.loan_id = NEW.loan_id
                  AND NOT EXISTS (SELECT 1 FROM lending.loan_write_off_decision d WHERE d.write_off_id = w.id)) THEN
        RAISE EXCEPTION 'a write-off of this account already awaits a decision (P14-TSK-021)';
    END IF;
    NEW.proposed_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER loan_write_off_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON lending.loan_write_off
    FOR EACH ROW
    EXECUTE FUNCTION lending.loan_write_off_is_born_once();

CREATE TRIGGER loan_write_off_is_never_truncated
    BEFORE TRUNCATE ON lending.loan_write_off
    FOR EACH STATEMENT
    EXECUTE FUNCTION lending.loan_is_never_truncated();

CREATE OR REPLACE FUNCTION lending.loan_write_off_decision_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    proposal lending.loan_write_off%ROWTYPE;
    account lending.loan%ROWTYPE;
    default_change lending.loan_condition_event%ROWTYPE;
    zone text;
    value_floor date;
    due_principal numeric;
    due_interest numeric;
    due_fees numeric;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a write-off decision is immutable from insert, for every role (P14-TSK-021, INV-HIST-01)';
    END IF;
    SELECT w.* INTO proposal FROM lending.loan_write_off w WHERE w.id = NEW.write_off_id;
    IF proposal.id IS NULL THEN
        RAISE EXCEPTION 'a write-off decision decides a write-off (P14-TSK-021)';
    END IF;
    -- (L3) The account, for every writer: everything below is judged under the lock every act on the account holds.
    SELECT l.* INTO account FROM lending.loan l WHERE l.id = proposal.loan_id FOR UPDATE;
    IF NEW.outcome = 'APPROVED' THEN
        IF account.status NOT IN ('ACTIVE', 'CLOSING') THEN
            RAISE EXCEPTION 'a write-off is approved only on an ACTIVE or CLOSING account (P14-TSK-021, INV-LIFE-04)';
        END IF;
        -- A condition that can leave and come back is compared by its revision: the same DEFAULT change, still set.
        SELECT e.* INTO default_change FROM lending.loan_condition_event e
         WHERE e.loan_id = proposal.loan_id AND e.kind = 'DEFAULT'
         ORDER BY e.business_date DESC LIMIT 1;
        IF default_change.id IS DISTINCT FROM proposal.default_event_id OR default_change.defaulted IS NOT TRUE THEN
            RAISE EXCEPTION 'a write-off is approved only while the DEFAULT change it stands on is the latest and still set (P14-TSK-021)';
        END IF;
        -- The agreement in force is the one it was proposed under: a restructuring accepted without re-scheduling arrears moves no
        -- journal line, so the revision cannot say so.
        IF proposal.agreement_version <> (SELECT max(a.version) FROM lending.loan_agreement a WHERE a.loan_id = proposal.loan_id) THEN
            RAISE EXCEPTION 'a write-off is approved only under the agreement version it was proposed on (P14-TSK-021, INV-LND-01)';
        END IF;
        -- What the billed items say is due is what the proposal named (the rows' side; the ledger's is the domain's under L3).
        SELECT coalesce(sum(lending.billing_component_billed(i.id, 'PRINCIPAL') - lending.billing_component_paid(i.id, 'PRINCIPAL')), 0),
               coalesce(sum(lending.billing_component_billed(i.id, 'INTEREST') - lending.billing_component_paid(i.id, 'INTEREST')), 0),
               coalesce(sum(lending.billing_component_billed(i.id, 'FEES') - lending.billing_component_paid(i.id, 'FEES')), 0)
          INTO due_principal, due_interest, due_fees
          FROM (SELECT b.id FROM lending.instalment_billing b WHERE b.loan_id = proposal.loan_id
                UNION ALL
                SELECT s.id FROM lending.credit_line_statement s WHERE s.loan_id = proposal.loan_id) i;
        IF due_principal <> proposal.principal_due_minor OR due_interest <> proposal.interest_due_minor
                OR due_fees <> proposal.fees_due_minor THEN
            RAISE EXCEPTION 'a write-off takes exactly what the billed items have due (P14-TSK-021, INV-LND-01)';
        END IF;
        -- The value date: never one the record has closed (the Phase 11 gate's R1), never after the clock.
        SELECT t.servicing_zone INTO zone
          FROM lending.loan_offer o
          JOIN lending.loan_terms t ON t.terms_version_id = o.terms_version_id
         WHERE o.id = account.offer_id;
        value_floor := lending.principal_value_floor(proposal.loan_id);
        IF NEW.value_date < value_floor OR NEW.value_date > lending.business_date_latest(zone) THEN
            RAISE EXCEPTION 'a write-off is valued on a date the record leaves open, never after today (P14-TSK-021, ADR-0063 decision 2)';
        END IF;
    END IF;
    NEW.recorded_order := nextval('lending.record_order');
    NEW.decided_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER loan_write_off_decision_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON lending.loan_write_off_decision
    FOR EACH ROW
    EXECUTE FUNCTION lending.loan_write_off_decision_is_born_once();

CREATE TRIGGER loan_write_off_decision_is_never_truncated
    BEFORE TRUNCATE ON lending.loan_write_off_decision
    FOR EACH STATEMENT
    EXECUTE FUNCTION lending.loan_is_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The loan's edge function, replaced: V026's, verbatim but for the one new edge.
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
        IF NOT FOUND OR offer.status <> 'OFFERED'
                OR offer.party_id <> NEW.party_id OR offer.product <> NEW.product
                OR offer.decision_id <> NEW.decision_id THEN
            RAISE EXCEPTION 'a loan is born for its own party''s OFFERED, unexpired offer of the same product, on its decision (P11-TSK-012)';
        END IF;
        SELECT t.currency INTO offered_currency FROM lending.loan_offer_terms t WHERE t.offer_id = NEW.offer_id;
        IF offered_currency IS DISTINCT FROM NEW.currency THEN
            RAISE EXCEPTION 'a loan is held in its offer''s currency (P11-TSK-012)';
        END IF;
        -- The expiry, judged last - after the offer's identity and its currency, so a breach of either is never
        -- answered as a lapse - and in its own SQLSTATE (P11-TSK-034): the acceptance judged the offer unexpired after
        -- L2 and then waited (credit's consumption, L6), so the database clock may cross expires_at before this
        -- statement - the customer's 409, not a breach.
        IF offer.expires_at <= statement_timestamp() THEN
            RAISE EXCEPTION 'a loan is never born for an expired offer - it expires (P11-TSK-012, P11-TSK-034)'
                USING ERRCODE = 'LNEXP';
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
    -- The closure (P11-TSK-018): an instalment loan ACTIVE -> CLOSED (REPAID) - or (PAID_OFF_EARLY) by its payoff
    -- (P11-TSK-026) - only beside its loan_closure row born in this transaction for the same reason (which holds the rows'
    -- side of "every receivable zero" and, for a payoff, the execution beside it); the terminal instant is the trigger's.
    IF OLD.status = 'ACTIVE' AND NEW.status = 'CLOSED' AND OLD.kind = 'INSTALMENT' THEN
        IF NEW.closure_reason IS NULL OR NEW.closure_reason NOT IN ('REPAID', 'PAID_OFF_EARLY')
                OR NEW.cancellation_reason IS NOT NULL
                OR NOT EXISTS (SELECT 1 FROM lending.loan_closure c
                                WHERE c.loan_id = OLD.id AND c.reason = NEW.closure_reason) THEN
            RAISE EXCEPTION 'an instalment loan is CLOSED (REPAID or PAID_OFF_EARLY) only beside its closure (P11-TSK-018, P11-TSK-026, INV-LND-08)';
        END IF;
        NEW.closed_at := statement_timestamp();
        RETURN NEW;
    END IF;
    -- A credit line's closure request (P11-TSK-023): ACTIVE -> CLOSING, only beside its request born for that outcome - the
    -- line then takes no draw (V013's draw trigger admits only ACTIVE) and is still serviced, open, never terminal.
    IF OLD.status = 'ACTIVE' AND NEW.status = 'CLOSING' AND OLD.kind = 'REVOLVING' THEN
        IF NEW.closed_at IS NOT NULL OR NEW.closure_reason IS NOT NULL OR NEW.cancellation_reason IS NOT NULL
                OR NOT EXISTS (SELECT 1 FROM lending.line_closure_request q
                                WHERE q.loan_id = OLD.id AND q.outcome = 'CLOSING') THEN
            RAISE EXCEPTION 'a credit line is CLOSING only beside the customer''s closure request (P11-TSK-023, INV-LIFE-04)';
        END IF;
        RETURN NEW;
    END IF;
    -- A credit line's closure (P11-TSK-023): ACTIVE -> CLOSED at the request when nothing is outstanding (its request born
    -- for that outcome), CLOSING -> CLOSED in the transaction that zeroes its last receivable - each CLOSED_BY_CUSTOMER and
    -- only beside its loan_closure row (the closure trigger holds the rows' side and the request beside it).
    IF OLD.status IN ('ACTIVE', 'CLOSING') AND NEW.status = 'CLOSED' AND OLD.kind = 'REVOLVING' THEN
        IF NEW.closure_reason IS DISTINCT FROM 'CLOSED_BY_CUSTOMER' OR NEW.cancellation_reason IS NOT NULL
                OR NOT EXISTS (SELECT 1 FROM lending.loan_closure c
                                WHERE c.loan_id = OLD.id AND c.reason = NEW.closure_reason)
                OR (OLD.status = 'ACTIVE'
                    AND NOT EXISTS (SELECT 1 FROM lending.line_closure_request q
                                     WHERE q.loan_id = OLD.id AND q.outcome = 'CLOSED')) THEN
            RAISE EXCEPTION 'a credit line is CLOSED (CLOSED_BY_CUSTOMER) only beside its closure and the customer''s request (P11-TSK-023, INV-LND-08)';
        END IF;
        NEW.closed_at := statement_timestamp();
        RETURN NEW;
    END IF;
    -- The write-off (P14-TSK-021): ACTIVE or CLOSING -> WRITTEN_OFF, only beside its APPROVED decision born in this transaction;
    -- never closed - closed_at, the closure and cancellation reasons stay NULL, the permit unmoved: nothing was paid or
    -- forgiven, and nothing services the account again. Nothing leaves WRITTEN_OFF (below).
    IF OLD.status IN ('ACTIVE', 'CLOSING') AND NEW.status = 'WRITTEN_OFF' THEN
        IF NEW.closed_at IS NOT NULL OR NEW.closure_reason IS NOT NULL OR NEW.cancellation_reason IS NOT NULL
                OR NEW.next_servicing_at IS DISTINCT FROM OLD.next_servicing_at
                OR NOT EXISTS (SELECT 1 FROM lending.loan_write_off_decision d
                                WHERE d.loan_id = OLD.id AND d.outcome = 'APPROVED') THEN
            RAISE EXCEPTION 'a loan is WRITTEN_OFF only beside its approved write-off, and is never closed by it (P14-TSK-021, INV-LIFE-04)';
        END IF;
        RETURN NEW;
    END IF;
    -- Every other edge is never: out of CLOSED, CANCELLED and WRITTEN_OFF nothing, ever (INV-LIFE-04); CLOSING -> ACTIVE never (a closing
    -- line is never reopened - a new application); ACTIVE -> CANCELLED and ACTIVE -> PENDING_DISBURSEMENT never
    -- (LENDING_LIFECYCLES.md section 4.5).
    RAISE EXCEPTION 'a loan does not move here; % -> % is not an edge (P11-TSK-026, INV-LIFE-04)', OLD.status, NEW.status;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The grants: SELECT and INSERT only - never UPDATE, never DELETE.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON lending.loan_write_off TO finapp_app;
GRANT SELECT, INSERT ON lending.loan_write_off_decision TO finapp_app;
