-- BNPL write-off under four eyes (P14-TSK-021; owner decision C6 (A), ratified 2026-11-04; ADR-0103 F11, ADR-0104 Follow-up;
-- INV-BNPL-01, INV-REV-04, INV-AUD-04, INV-BAL-03, INV-LIFE-04). The number is V020, reserved for this task.
--
-- A write-off moves a defaulted obligation's whole receivable to BNPL_WRITE_OFF_EXPENSE. It is an act on OUR books and says
-- nothing about what the customer owes - not paid, not forgiven, not closed. So it is a status of its own, WRITTEN_OFF, with
-- closed_at and closure_reason left NULL. Nothing leaves it (INV-LIFE-04): the way back is an owner item, stated in
-- BNPL_LIFECYCLES.md. The merchant is not touched: its payable has no line here.
--
--     bnpl_write_off           the proposal: born once, INSERT-only - the five receivables as read under B4, the revision (the
--                              count of journal lines on the obligation's six accounts when they were read: it only rises, so a
--                              repayment and its reversal cannot round-trip), the DEFAULT change it stands on, the proposer and
--                              their reason.
--     bnpl_write_off_decision  the decision: born once, INSERT-only, so the row IS the history. APPROVED names the entry and the
--                              date it is valued; REJECTED names neither. Four eyes over the proposer pinned by a composite
--                              foreign key, a CHECK for every writer.
--
-- bnpl's triggers never read the ledger. So the triggers hold what the rows can: an ACTIVE obligation, the latest DEFAULT change
-- still the one named and still defaulted, no dispute open, one live proposal, what the billed items say is due equal to the due
-- figures named, a value date the record leaves open. That the figures are the ledger's is the domain's judgement under B4, and
-- the subledger proof's and the replay's for every writer.
--
-- A merchant's refund and a customer's dispute are refused on a WRITTEN_OFF obligation, for every writer, by two triggers of their
-- own: F5 credits the receivables, which the books no longer hold, and what a refund would cancel then is a recovery, a later rule.
-- bnpl_refund_is_born_once and the dispute's machine admit a CLOSED obligation by design, so they would not refuse it themselves.
-- Every other trigger of the schema judging an obligation admits only ACTIVE, so a written-off one fails closed at each of them.
--
-- Every existing writer's status stays admitted: each CHECK below is its predecessor plus WRITTEN_OFF, and no existing row holds it.
-- Migrator-owned; schema-qualified throughout and invoker's rights, like V010's, so no search_path is set.

ALTER TABLE bnpl.bnpl_obligation DROP CONSTRAINT bnpl_obligation_status_is_known;
ALTER TABLE bnpl.bnpl_obligation ADD CONSTRAINT bnpl_obligation_status_is_known CHECK (status IN ('ACTIVE', 'CLOSED', 'WRITTEN_OFF'));

ALTER TABLE bnpl.bnpl_obligation DROP CONSTRAINT bnpl_obligation_closure_coherent;
ALTER TABLE bnpl.bnpl_obligation ADD CONSTRAINT bnpl_obligation_closure_coherent CHECK (
    (status IN ('ACTIVE', 'WRITTEN_OFF') AND closed_at IS NULL AND closure_reason IS NULL)
    OR (status = 'CLOSED' AND closed_at IS NOT NULL
        AND closure_reason IN ('REPAID', 'PAID_OFF_EARLY', 'REFUNDED', 'REFUNDED_PARTIALLY_REPAID')));

ALTER TABLE bnpl.bnpl_obligation_event DROP CONSTRAINT bnpl_obligation_event_edge;
ALTER TABLE bnpl.bnpl_obligation_event ADD CONSTRAINT bnpl_obligation_event_edge CHECK (
    (from_status IS NULL AND to_status = 'ACTIVE')
    OR (from_status = 'ACTIVE' AND to_status IN ('CLOSED', 'WRITTEN_OFF')));

-- ---------------------------------------------------------------------------------------------
-- The proposal.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE bnpl.bnpl_write_off (
    id                      uuid        NOT NULL,
    obligation_id           uuid        NOT NULL,
    agreement_id            uuid        NOT NULL,
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
    CONSTRAINT bnpl_write_off_pk PRIMARY KEY (id),
    -- The decision's composite key pins its copies of the obligation and the proposer to this row's.
    CONSTRAINT bnpl_write_off_pinned UNIQUE (id, obligation_id, proposed_by),
    CONSTRAINT bnpl_write_off_obligation_fk FOREIGN KEY (obligation_id) REFERENCES bnpl.bnpl_obligation (id),
    CONSTRAINT bnpl_write_off_default_fk FOREIGN KEY (default_event_id) REFERENCES bnpl.bnpl_condition_event (id),
    CONSTRAINT bnpl_write_off_currency_is_the_products CHECK (currency = 'EUR' AND scale BETWEEN 0 AND 9),
    -- Each receivable on its normal side; never partial, never nothing.
    CONSTRAINT bnpl_write_off_figures_not_negative CHECK (
        principal_minor >= 0 AND principal_due_minor >= 0 AND interest_accrued_minor >= 0 AND interest_due_minor >= 0
        AND fees_due_minor >= 0),
    CONSTRAINT bnpl_write_off_takes_something CHECK (
        principal_minor::numeric + principal_due_minor + interest_accrued_minor + interest_due_minor + fees_due_minor > 0),
    CONSTRAINT bnpl_write_off_revision_counted CHECK (revision > 0),
    CONSTRAINT bnpl_write_off_version_numbered CHECK (agreement_version >= 1),
    CONSTRAINT bnpl_write_off_actor_type_known CHECK (
        proposed_by_type IN ('SYSTEM', 'CUSTOMER', 'EMPLOYEE', 'SERVICE', 'MERCHANT')),
    CONSTRAINT bnpl_write_off_actor_bounded CHECK (char_length(proposed_by) BETWEEN 1 AND 200),
    CONSTRAINT bnpl_write_off_proposal_reasoned CHECK (char_length(proposal_reason) BETWEEN 1 AND 1000),
    CONSTRAINT bnpl_write_off_proposal_reason_no_instrument_shape CHECK (NOT bnpl.holds_instrument_shape(proposal_reason)),
    CONSTRAINT bnpl_write_off_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

CREATE INDEX bnpl_write_off_by_obligation ON bnpl.bnpl_write_off (obligation_id, proposed_at);
CREATE INDEX bnpl_write_off_oldest ON bnpl.bnpl_write_off (proposed_at, id);

COMMENT ON TABLE bnpl.bnpl_write_off IS
    'A BNPL write-off''s proposal (P14-TSK-021; owner decision C6, ADR-0103 F11): the five receivables as read under B4, the revision (journal lines on the obligation''s six accounts then), the DEFAULT condition change it stands on, the agreement version in force, the proposer (a holder of BNPL_WRITE_OFF_PROPOSE) and the reason. Born once, INSERT-only for every role; its decision is bnpl_write_off_decision (none: PROPOSED). One live per obligation. A write-off is an act on the books, never forgiveness: what the customer owes is untouched. Amounts RESTRICTED-FINANCIAL; the reason a person''s prose, screened for instrument shapes.';

-- ---------------------------------------------------------------------------------------------
-- The decision.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE bnpl.bnpl_write_off_decision (
    id                 uuid        NOT NULL,
    write_off_id       uuid        NOT NULL,
    obligation_id      uuid        NOT NULL,
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
    CONSTRAINT bnpl_write_off_decision_pk PRIMARY KEY (id),
    CONSTRAINT bnpl_write_off_decision_write_off_fk FOREIGN KEY (write_off_id, obligation_id, proposed_by)
        REFERENCES bnpl.bnpl_write_off (id, obligation_id, proposed_by),
    -- Decided once: ten approvers, one decision - the arbiter beneath B4.
    CONSTRAINT bnpl_write_off_decision_once UNIQUE (write_off_id),
    CONSTRAINT bnpl_write_off_decision_entry_unique UNIQUE (journal_entry_id),
    CONSTRAINT bnpl_write_off_decision_outcome_is_known CHECK (outcome IN ('APPROVED', 'REJECTED')),
    -- FOUR EYES, for every writer (INV-AUD-04): whoever approved a write-off did not propose it. A rejection may be the
    -- proposer's. The row is the history: it is never updated.
    CONSTRAINT bnpl_write_off_decision_four_eyes CHECK (outcome <> 'APPROVED' OR decided_by <> proposed_by),
    -- The approval and only the approval posts: it names its entry and the date it is valued.
    CONSTRAINT bnpl_write_off_decision_approval_posts CHECK (
        (outcome = 'APPROVED') = (journal_entry_id IS NOT NULL) AND (outcome = 'APPROVED') = (value_date IS NOT NULL)),
    CONSTRAINT bnpl_write_off_decision_actor_type_known CHECK (
        decided_by_type IN ('SYSTEM', 'CUSTOMER', 'EMPLOYEE', 'SERVICE', 'MERCHANT')),
    CONSTRAINT bnpl_write_off_decision_actor_bounded CHECK (
        char_length(decided_by) BETWEEN 1 AND 200 AND char_length(proposed_by) BETWEEN 1 AND 200),
    CONSTRAINT bnpl_write_off_decision_reasoned CHECK (char_length(decision_reason) BETWEEN 1 AND 1000),
    CONSTRAINT bnpl_write_off_decision_reason_no_instrument_shape CHECK (NOT bnpl.holds_instrument_shape(decision_reason)),
    CONSTRAINT bnpl_write_off_decision_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

-- An obligation is written off once, ever - beneath B4 and beneath the ledger key bnpl.write-off:<obligation>.
CREATE UNIQUE INDEX bnpl_write_off_one_approved ON bnpl.bnpl_write_off_decision (obligation_id) WHERE outcome = 'APPROVED';

COMMENT ON TABLE bnpl.bnpl_write_off_decision IS
    'A BNPL write-off''s decision (P14-TSK-021; INV-AUD-04, INV-REV-04): APPROVED by a holder of BNPL_WRITE_OFF_APPROVE who did not propose it (four-eyes CHECK over the proposer copied and pinned by the composite foreign key), naming the entry it posted (F11) and its value date - or REJECTED, the door out of a proposal that can no longer post, on any status. UNIQUE (write_off_id): decided once. INSERT-only for every role, so this row is the history.';

CREATE OR REPLACE FUNCTION bnpl.bnpl_write_off_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    account bnpl.bnpl_obligation%ROWTYPE;
    agreement bnpl.bnpl_agreement%ROWTYPE;
    default_change bnpl.bnpl_condition_event%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a write-off proposal is a born-once fact (P14-TSK-021): its decision is bnpl_write_off_decision';
    END IF;
    -- (B4) The obligation, for every writer.
    SELECT o.* INTO account FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id FOR UPDATE;
    IF account.id IS NULL OR account.status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'a write-off is proposed only on an ACTIVE obligation (P14-TSK-021)';
    END IF;
    SELECT a.* INTO agreement FROM bnpl.bnpl_agreement a
     WHERE a.agreement_id = account.agreement_id
     ORDER BY a.version DESC LIMIT 1;
    IF NEW.agreement_id <> account.agreement_id OR NEW.agreement_version <> agreement.version
            OR NEW.currency <> account.currency THEN
        RAISE EXCEPTION 'a write-off names its obligation''s agreement version in force, in its currency (P14-TSK-021)';
    END IF;
    -- The default it stands on is the obligation's latest DEFAULT change, and that change set the flag.
    SELECT e.* INTO default_change FROM bnpl.bnpl_condition_event e
     WHERE e.obligation_id = NEW.obligation_id AND e.kind = 'DEFAULT'
     ORDER BY e.business_date DESC LIMIT 1;
    IF default_change.id IS DISTINCT FROM NEW.default_event_id OR default_change.defaulted IS NOT TRUE THEN
        RAISE EXCEPTION 'a write-off stands on its obligation''s latest DEFAULT change, which set the flag (P14-TSK-021)';
    END IF;
    -- A dispute's outcome may be a refund, which needs the receivable: it is resolved first.
    IF EXISTS (SELECT 1 FROM bnpl.bnpl_dispute d
                WHERE d.obligation_id = NEW.obligation_id AND d.status IN ('OPEN', 'MERCHANT_RESPONDED')) THEN
        RAISE EXCEPTION 'a write-off is proposed only with no dispute open (P14-TSK-021, ADR-0106)';
    END IF;
    -- One proposal awaiting a decision per obligation: a second is bnpl.ProposalPending.
    IF EXISTS (SELECT 1 FROM bnpl.bnpl_write_off w
                WHERE w.obligation_id = NEW.obligation_id
                  AND NOT EXISTS (SELECT 1 FROM bnpl.bnpl_write_off_decision d WHERE d.write_off_id = w.id)) THEN
        RAISE EXCEPTION 'a write-off of this obligation already awaits a decision (P14-TSK-021)';
    END IF;
    NEW.proposed_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER bnpl_write_off_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON bnpl.bnpl_write_off
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_write_off_is_born_once();

CREATE TRIGGER bnpl_write_off_is_never_truncated
    BEFORE TRUNCATE ON bnpl.bnpl_write_off
    FOR EACH STATEMENT
    EXECUTE FUNCTION bnpl.acceptance_rows_are_never_truncated();

CREATE OR REPLACE FUNCTION bnpl.bnpl_write_off_decision_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    proposal bnpl.bnpl_write_off%ROWTYPE;
    account bnpl.bnpl_obligation%ROWTYPE;
    default_change bnpl.bnpl_condition_event%ROWTYPE;
    value_floor date;
    due_principal numeric;
    due_interest numeric;
    due_fees numeric;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a write-off decision is immutable from insert, for every role (P14-TSK-021, INV-HIST-01)';
    END IF;
    SELECT w.* INTO proposal FROM bnpl.bnpl_write_off w WHERE w.id = NEW.write_off_id;
    IF proposal.id IS NULL THEN
        RAISE EXCEPTION 'a write-off decision decides a write-off (P14-TSK-021)';
    END IF;
    -- (B4) The obligation, for every writer: everything below is judged under the lock every act on it holds.
    SELECT o.* INTO account FROM bnpl.bnpl_obligation o WHERE o.id = proposal.obligation_id FOR UPDATE;
    IF NEW.outcome = 'APPROVED' THEN
        IF account.status <> 'ACTIVE' THEN
            RAISE EXCEPTION 'a write-off is approved only on an ACTIVE obligation (P14-TSK-021, INV-LIFE-04)';
        END IF;
        -- A condition that can leave and come back is compared by its revision: the same DEFAULT change, still set.
        SELECT e.* INTO default_change FROM bnpl.bnpl_condition_event e
         WHERE e.obligation_id = proposal.obligation_id AND e.kind = 'DEFAULT'
         ORDER BY e.business_date DESC LIMIT 1;
        IF default_change.id IS DISTINCT FROM proposal.default_event_id OR default_change.defaulted IS NOT TRUE THEN
            RAISE EXCEPTION 'a write-off is approved only while the DEFAULT change it stands on is the latest and still set (P14-TSK-021)';
        END IF;
        IF EXISTS (SELECT 1 FROM bnpl.bnpl_dispute d
                    WHERE d.obligation_id = proposal.obligation_id AND d.status IN ('OPEN', 'MERCHANT_RESPONDED')) THEN
            RAISE EXCEPTION 'a write-off is approved only with no dispute open (P14-TSK-021, ADR-0106)';
        END IF;
        -- What the billed items say is due is what the proposal named (the rows' side; the ledger's is the domain's under B4).
        SELECT coalesce(sum(bnpl.billing_component_billed(b.id, 'PRINCIPAL') - bnpl.billing_component_paid(b.id, 'PRINCIPAL')), 0),
               coalesce(sum(bnpl.billing_component_billed(b.id, 'INTEREST') - bnpl.billing_component_paid(b.id, 'INTEREST')), 0),
               coalesce(sum(bnpl.billing_component_billed(b.id, 'FEES') - bnpl.billing_component_paid(b.id, 'FEES')), 0)
          INTO due_principal, due_interest, due_fees
          FROM bnpl.bnpl_billing b WHERE b.obligation_id = proposal.obligation_id;
        IF due_principal <> proposal.principal_due_minor OR due_interest <> proposal.interest_due_minor
                OR due_fees <> proposal.fees_due_minor THEN
            RAISE EXCEPTION 'a write-off takes exactly what the billed items have due (P14-TSK-021, INV-BNPL-01)';
        END IF;
        -- The value date: never one the record has closed (R1's rule), never after the clock.
        value_floor := bnpl.principal_value_floor(proposal.obligation_id);
        IF NEW.value_date < value_floor OR NEW.value_date > bnpl.business_date_latest(account.servicing_zone) THEN
            RAISE EXCEPTION 'a write-off is valued on a date the record leaves open, never after today (P14-TSK-021, ADR-0063 decision 2)';
        END IF;
    END IF;
    NEW.recorded_order := nextval('bnpl.record_order');
    NEW.decided_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER bnpl_write_off_decision_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON bnpl.bnpl_write_off_decision
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_write_off_decision_is_born_once();

CREATE TRIGGER bnpl_write_off_decision_is_never_truncated
    BEFORE TRUNCATE ON bnpl.bnpl_write_off_decision
    FOR EACH STATEMENT
    EXECUTE FUNCTION bnpl.acceptance_rows_are_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- A refund and a dispute on a written-off obligation, refused for every writer. Reads the row the caller's B4 holds; a dispute's
-- and a refund's own triggers run beside these, and these only add the refusal.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION bnpl.nothing_is_refunded_or_disputed_on_a_written_off_obligation()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    status_of text;
BEGIN
    SELECT o.status INTO status_of FROM bnpl.bnpl_obligation o WHERE o.purchase_id = NEW.purchase_id FOR SHARE;
    IF status_of = 'WRITTEN_OFF' THEN
        RAISE EXCEPTION 'a written-off obligation takes no refund and no dispute: recovery is a later rule (P14-TSK-021, F5, F9)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER bnpl_refund_not_on_a_written_off_obligation
    BEFORE INSERT ON bnpl.bnpl_refund
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.nothing_is_refunded_or_disputed_on_a_written_off_obligation();

CREATE TRIGGER bnpl_dispute_not_on_a_written_off_obligation
    BEFORE INSERT ON bnpl.bnpl_dispute
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.nothing_is_refunded_or_disputed_on_a_written_off_obligation();

-- ---------------------------------------------------------------------------------------------
-- The obligation's edge function, replaced: V010's, verbatim but for the one new edge.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION bnpl.bnpl_obligation_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    purchase bnpl.purchase%ROWTYPE;
    offer bnpl.bnpl_offer%ROWTYPE;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'ACTIVE' OR NEW.closed_at IS NOT NULL OR NEW.closure_reason IS NOT NULL THEN
            RAISE EXCEPTION 'a BNPL obligation is born ACTIVE (P12-TSK-013)';
        END IF;
        -- Born beside its AUTHORISED purchase, for the purchase's party, product, plan and currency and the offer's
        -- decision; its purchase date and zone the purchase's own.
        SELECT * INTO purchase FROM bnpl.purchase p WHERE p.id = NEW.purchase_id;
        IF NOT FOUND OR purchase.status <> 'AUTHORISED' OR purchase.party_id <> NEW.party_id
                OR purchase.product <> NEW.product OR purchase.plan <> NEW.plan OR purchase.currency <> NEW.currency THEN
            RAISE EXCEPTION 'a BNPL obligation is born beside its AUTHORISED purchase, for its party, product, plan and currency (P12-TSK-013)';
        END IF;
        SELECT * INTO offer FROM bnpl.bnpl_offer o WHERE o.id = purchase.offer_id;
        IF offer.decision_id <> NEW.decision_id THEN
            RAISE EXCEPTION 'a BNPL obligation names the decision its offer was made on (P12-TSK-013)';
        END IF;
        NEW.servicing_zone := purchase.servicing_zone;
        NEW.purchase_date := purchase.business_date;
        NEW.opened_at := clock_timestamp();
        -- The servicing sweep's first look is due at once (P12-TSK-015).
        NEW.next_servicing_at := NEW.opened_at;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a BNPL obligation is never deleted (P12-TSK-013, INV-HIST-01)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.purchase_id IS DISTINCT FROM OLD.purchase_id
            OR NEW.agreement_id IS DISTINCT FROM OLD.agreement_id OR NEW.party_id IS DISTINCT FROM OLD.party_id
            OR NEW.decision_id IS DISTINCT FROM OLD.decision_id OR NEW.product IS DISTINCT FROM OLD.product
            OR NEW.plan IS DISTINCT FROM OLD.plan OR NEW.currency IS DISTINCT FROM OLD.currency
            OR NEW.servicing_zone IS DISTINCT FROM OLD.servicing_zone
            OR NEW.purchase_date IS DISTINCT FROM OLD.purchase_date OR NEW.opened_at IS DISTINCT FROM OLD.opened_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a BNPL obligation''s identity, product and origin are frozen from birth (P12-TSK-015)';
    END IF;
    -- The servicing permit alone (P12-TSK-015, BNPL_LIFECYCLES.md section 4.7): an ACTIVE obligation moves its permit and
    -- nothing else - the sweep's claim and a completed servicing step. Never a CLOSED obligation's, never beside another
    -- column.
    IF NEW.status = OLD.status AND OLD.status = 'ACTIVE'
            AND NEW.closed_at IS NOT DISTINCT FROM OLD.closed_at
            AND NEW.closure_reason IS NOT DISTINCT FROM OLD.closure_reason THEN
        RETURN NEW;
    END IF;
    -- The closure (P12-TSK-018): ACTIVE -> CLOSED only beside its bnpl_obligation_closure row born in this transaction for
    -- the same reason (which holds the rows' side of "every receivable zero"), the permit unmoved; the terminal instant
    -- is the database's, after the lock. A refund's (P12-TSK-021) and a payoff's (P12-TSK-022) reasons are admitted by
    -- their closure rows' own triggers.
    IF OLD.status = 'ACTIVE' AND NEW.status = 'CLOSED' THEN
        IF NEW.closure_reason IS NULL OR NEW.next_servicing_at IS DISTINCT FROM OLD.next_servicing_at
                OR NOT EXISTS (SELECT 1 FROM bnpl.bnpl_obligation_closure c
                                WHERE c.obligation_id = OLD.id AND c.reason = NEW.closure_reason) THEN
            RAISE EXCEPTION 'a BNPL obligation is CLOSED only beside its closure, for the same reason (P12-TSK-018, INV-LND-08)';
        END IF;
        NEW.closed_at := clock_timestamp();
        RETURN NEW;
    END IF;
    -- The write-off (P14-TSK-021): ACTIVE -> WRITTEN_OFF, only beside its APPROVED decision born in this transaction; never closed -
    -- closed_at and the closure reason stay NULL, the permit unmoved: nothing was paid or forgiven, and nothing services it again.
    IF OLD.status = 'ACTIVE' AND NEW.status = 'WRITTEN_OFF' THEN
        IF NEW.closed_at IS NOT NULL OR NEW.closure_reason IS NOT NULL
                OR NEW.next_servicing_at IS DISTINCT FROM OLD.next_servicing_at
                OR NOT EXISTS (SELECT 1 FROM bnpl.bnpl_write_off_decision d
                                WHERE d.obligation_id = OLD.id AND d.outcome = 'APPROVED') THEN
            RAISE EXCEPTION 'a BNPL obligation is WRITTEN_OFF only beside its approved write-off, and is never closed by it (P14-TSK-021, INV-LIFE-04)';
        END IF;
        RETURN NEW;
    END IF;
    -- Out of CLOSED and WRITTEN_OFF nothing, ever (INV-LIFE-04); and no other edge exists.
    RAISE EXCEPTION 'a BNPL obligation moves only its servicing permit, or ACTIVE -> CLOSED beside its closure; % -> % is not an edge (P12-TSK-018, INV-LIFE-04)', OLD.status, NEW.status;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The grants: SELECT and INSERT only - never UPDATE, never DELETE. The obligation's status is V010's grant.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON bnpl.bnpl_write_off TO finapp_app;
GRANT SELECT, INSERT ON bnpl.bnpl_write_off_decision TO finapp_app;
