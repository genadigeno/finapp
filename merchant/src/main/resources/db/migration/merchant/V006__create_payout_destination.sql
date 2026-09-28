-- The payout destination and its history (P6-TSK-011, ADR-0056, INV-AUD-04).
--
-- A PROPOSAL FLOW, NOT A FIELD (CHECKOUT_MERCHANT_LIFECYCLES.md section 6): every change is its
-- own immutable row. An operator proposes; a DIFFERENT operator approves; the approval pins a
-- cooling-off deadline; once it has elapsed the platform's effectuation sweep makes the row
-- EFFECTIVE and supersedes the previous one in the same transaction. The row's id is the
-- destination's version: a payout that records it records exactly where it was sent.
--
-- FOUR-EYES' SECOND SUBJECT, NOT ITS FIRST. V001's header calls this "INV-AUD-04's first
-- implemented subject". It is not: P3-TSK-021 built four-eyes on manual adjustments (ledger
-- V010), and this table applies that shape again. V001 is a committed migration and cannot be
-- edited, so the correction lives here and in FINANCIAL_INVARIANTS.md.
--
-- EVERY GENERATED LIST BELOW HAS ONE DEFINITION: the status CHECKs from
-- PayoutDestinationStatus.sqlValueList(), the one-open index from openSqlValueList(), the
-- trigger's edges from permittedTransitions(), the reference and suffix shapes from
-- BankDetailShapes and PayoutDestination, the bounds from their constants.
-- PayoutDestinationMigrationTest fails the build if this file and the code disagree.
--
-- BANK DETAILS NEVER ENTER THE PLATFORM (ADR-0056 section 5): destination_reference is the
-- provider's opaque reference and display_suffix its four displayable characters. A value
-- shaped like an account number cannot be stored here by any writer - the CHECKs below refuse
-- digits-only and international-account shapes, as the domain types do.
--
-- NO CROSS-SCHEMA FOREIGN KEY, and NO AMOUNT OR BALANCE COLUMN (INV-MER-02): the only reference
-- is to merchant.merchant, in this schema.

CREATE TABLE merchant.payout_destination (
    -- UUIDv7, minted by the application (ADR-0013). Also the destination's version.
    id                    uuid        PRIMARY KEY,

    -- Same schema, so the FK is legal and right.
    merchant_id           uuid        NOT NULL REFERENCES merchant.merchant (id),

    destination_reference text        NOT NULL,
    display_suffix        text        NOT NULL,

    status                text        NOT NULL,

    -- The proposal, frozen at birth: what the approver reads is what takes effect.
    proposed_by           text        NOT NULL,
    -- Application-supplied from one injected Clock, never DEFAULT now() (P0-TSK-015).
    proposed_at           timestamptz NOT NULL,
    proposal_reason       text        NOT NULL,

    -- The approval, arriving whole with its edge; the deadline pinned at approval, so a later
    -- change to the configured cooling-off never alters an approved change.
    approved_by           text,
    approved_at           timestamptz,
    cooling_off_until     timestamptz,

    effective_at          timestamptz,
    superseded_at         timestamptz,

    -- Who rejected or withdrew it, and when.
    ended_by              text,
    ended_at              timestamptz,

    CONSTRAINT payout_destination_status_is_known
        CHECK (status IN ('PROPOSED', 'APPROVED', 'EFFECTIVE', 'SUPERSEDED', 'REJECTED', 'WITHDRAWN')),

    -- INV-AUD-04's Enforce clause, verbatim: approver <> initiator, at DB-CONSTRAINT rank, for
    -- every state that passed through an approval. A plain CHECK: the table is new, so no
    -- history predates it.
    CONSTRAINT payout_destination_approver_is_not_proposer
        CHECK (approved_by IS NULL OR approved_by <> proposed_by),

    -- An approval is its approver, instant and deadline together - unsplittable.
    CONSTRAINT payout_destination_approval_is_whole
        CHECK ((approved_by IS NULL) = (approved_at IS NULL)
               AND (approved_at IS NULL) = (cooling_off_until IS NULL)),
    CONSTRAINT payout_destination_approved_states_carry_the_approval
        CHECK (status NOT IN ('APPROVED', 'EFFECTIVE', 'SUPERSEDED') OR approved_by IS NOT NULL),
    CONSTRAINT payout_destination_unapproved_states_carry_none
        CHECK (status NOT IN ('PROPOSED', 'REJECTED') OR approved_by IS NULL),
    CONSTRAINT payout_destination_effective_instant_matches_status
        CHECK ((status IN ('EFFECTIVE', 'SUPERSEDED')) = (effective_at IS NOT NULL)),
    CONSTRAINT payout_destination_superseded_instant_matches_status
        CHECK ((status = 'SUPERSEDED') = (superseded_at IS NOT NULL)),
    CONSTRAINT payout_destination_ending_matches_status
        CHECK ((status IN ('REJECTED', 'WITHDRAWN')) = (ended_by IS NOT NULL)
               AND (ended_by IS NULL) = (ended_at IS NULL)),

    -- Time moves forward. The cooling-off is enforced HERE for every writer: a destination
    -- cannot take effect before its deadline, whatever the code that wrote it believed.
    CONSTRAINT payout_destination_approval_follows_proposal
        CHECK (approved_at IS NULL OR approved_at >= proposed_at),
    CONSTRAINT payout_destination_cooling_off_follows_approval
        CHECK (cooling_off_until IS NULL OR cooling_off_until > approved_at),
    CONSTRAINT payout_destination_effect_follows_cooling_off
        CHECK (effective_at IS NULL OR effective_at >= cooling_off_until),
    CONSTRAINT payout_destination_supersession_follows_effect
        CHECK (superseded_at IS NULL OR superseded_at >= effective_at),
    CONSTRAINT payout_destination_ending_follows_proposal
        CHECK (ended_at IS NULL OR ended_at >= proposed_at),

    -- The reference is a token, never a bank detail (BankDetailShapes).
    CONSTRAINT payout_destination_reference_is_a_token
        CHECK (destination_reference ~ '^[A-Za-z0-9_-]{1,128}$'),
    CONSTRAINT payout_destination_reference_is_not_digits
        CHECK (destination_reference !~ '^[0-9-]+$'),
    CONSTRAINT payout_destination_reference_is_not_an_account
        CHECK (destination_reference !~ '^[A-Za-z]{2}[0-9]{2}[A-Za-z0-9]{11,30}$'),
    CONSTRAINT payout_destination_suffix_shape
        CHECK (display_suffix ~ '^[0-9A-Z]{4}$'),

    CONSTRAINT payout_destination_proposed_by_bounded
        CHECK (length(proposed_by) BETWEEN 1 AND 200),
    CONSTRAINT payout_destination_approved_by_bounded
        CHECK (approved_by IS NULL OR length(approved_by) BETWEEN 1 AND 200),
    CONSTRAINT payout_destination_ended_by_bounded
        CHECK (ended_by IS NULL OR length(ended_by) BETWEEN 1 AND 200),
    CONSTRAINT payout_destination_reason_bounded
        CHECK (length(proposal_reason) BETWEEN 1 AND 1000)
);

-- ONE OPEN CHANGE PER MERCHANT: a cooling-off never arbitrates between two pending
-- destinations, and two racing proposals are one refused insert (the store's savepoint).
CREATE UNIQUE INDEX payout_destination_one_open_per_merchant
    ON merchant.payout_destination (merchant_id)
    WHERE status IN ('PROPOSED', 'APPROVED');

-- ONE EFFECTIVE DESTINATION PER MERCHANT (CHECKOUT_MERCHANT_LIFECYCLES.md section 6): checked
-- per statement, which is why the effectuation supersedes the old row before it effects the new.
CREATE UNIQUE INDEX payout_destination_one_effective_per_merchant
    ON merchant.payout_destination (merchant_id)
    WHERE status = 'EFFECTIVE';

-- The effectuation sweep's candidates, oldest deadline first.
CREATE INDEX payout_destination_due
    ON merchant.payout_destination (cooling_off_until, id)
    WHERE status = 'APPROVED';

-- THE PAYLOAD IS FROZEN AND THE MACHINE'S EDGES BIND EVERY WRITER (INV-LIFE-02; the P3-TSK-021
-- TOCTOU closure): what the approver approved is what takes effect, and a recorded decision
-- never moves again.
CREATE FUNCTION merchant.payout_destination_permits_only_machine_edges() RETURNS trigger
LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.merchant_id IS DISTINCT FROM OLD.merchant_id
            OR NEW.destination_reference IS DISTINCT FROM OLD.destination_reference
            OR NEW.display_suffix IS DISTINCT FROM OLD.display_suffix
            OR NEW.proposed_by IS DISTINCT FROM OLD.proposed_by
            OR NEW.proposed_at IS DISTINCT FROM OLD.proposed_at
            OR NEW.proposal_reason IS DISTINCT FROM OLD.proposal_reason THEN
        RAISE EXCEPTION 'a payout destination''s proposal is frozen: the approver approves what they read (P6-TSK-011, INV-AUD-04)';
    END IF;
    IF (OLD.approved_by IS NOT NULL
                AND (NEW.approved_by IS DISTINCT FROM OLD.approved_by
                     OR NEW.approved_at IS DISTINCT FROM OLD.approved_at
                     OR NEW.cooling_off_until IS DISTINCT FROM OLD.cooling_off_until))
            OR (OLD.effective_at IS NOT NULL AND NEW.effective_at IS DISTINCT FROM OLD.effective_at)
            OR (OLD.superseded_at IS NOT NULL AND NEW.superseded_at IS DISTINCT FROM OLD.superseded_at)
            OR (OLD.ended_by IS NOT NULL
                AND (NEW.ended_by IS DISTINCT FROM OLD.ended_by
                     OR NEW.ended_at IS DISTINCT FROM OLD.ended_at)) THEN
        RAISE EXCEPTION 'a payout destination''s recorded decisions never move (INV-HIST-01''s discipline)';
    END IF;
    IF NEW.status = OLD.status THEN
        RETURN NEW;
    END IF;
    IF NOT ((OLD.status = 'PROPOSED' AND NEW.status IN ('APPROVED', 'REJECTED', 'WITHDRAWN'))
            OR (OLD.status = 'APPROVED' AND NEW.status IN ('EFFECTIVE', 'WITHDRAWN'))
            OR (OLD.status = 'EFFECTIVE' AND NEW.status IN ('SUPERSEDED'))) THEN
        RAISE EXCEPTION 'a payout destination moves only along the machine''s edges: PROPOSED -> {APPROVED, REJECTED, WITHDRAWN}, APPROVED -> {EFFECTIVE, WITHDRAWN}, EFFECTIVE -> {SUPERSEDED} (INV-LIFE-02/-04)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER payout_destination_permits_only_machine_edges
    BEFORE UPDATE ON merchant.payout_destination
    FOR EACH ROW
    EXECUTE FUNCTION merchant.payout_destination_permits_only_machine_edges();

CREATE TABLE merchant.payout_destination_event (
    id                    bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    payout_destination_id uuid        NOT NULL REFERENCES merchant.payout_destination (id),

    from_status           text        NOT NULL
        CONSTRAINT payout_destination_event_from_status_is_known
            CHECK (from_status IN ('PROPOSED', 'APPROVED', 'EFFECTIVE', 'SUPERSEDED', 'REJECTED', 'WITHDRAWN')),
    to_status             text        NOT NULL
        CONSTRAINT payout_destination_event_to_status_is_known
            CHECK (to_status IN ('PROPOSED', 'APPROVED', 'EFFECTIVE', 'SUPERSEDED', 'REJECTED', 'WITHDRAWN')),

    -- The audit_record actor model: an operator's act, or the platform's effectuation.
    actor_id              text        NOT NULL
        CONSTRAINT payout_destination_event_actor_id_bounded
            CHECK (length(actor_id) BETWEEN 1 AND 200),
    actor_type            text        NOT NULL
        CONSTRAINT payout_destination_event_actor_type_bounded
            CHECK (length(actor_type) BETWEEN 1 AND 50),

    -- Application-supplied, never DEFAULT now().
    occurred_at           timestamptz NOT NULL
);

CREATE INDEX payout_destination_event_by_destination
    ON merchant.payout_destination_event (payout_destination_id);

COMMENT ON TABLE merchant.payout_destination IS
    'Where a merchant''s payouts go, as a proposal flow: PROPOSED -> APPROVED (a second, distinct operator; INV-AUD-04) -> EFFECTIVE (the platform, once the pinned cooling-off elapsed) -> SUPERSEDED; REJECTED and WITHDRAWN terminal. One open change and one EFFECTIVE destination per merchant. The provider''s opaque reference and a four-character suffix only - never bank details (ADR-0056).';
COMMENT ON COLUMN merchant.payout_destination.destination_reference IS
    'The provider''s opaque reference for the account (RESTRICTED-PII, the payment_method.token_reference reasoning): never logged, never in an event payload, never in an API response. Refused if shaped like an account number.';
COMMENT ON COLUMN merchant.payout_destination.cooling_off_until IS
    'Pinned at approval: approved_at plus the configured cooling-off. The row cannot become EFFECTIVE before it (payout_destination_effect_follows_cooling_off).';
COMMENT ON TABLE merchant.payout_destination_event IS
    'Append-only lifecycle history of payout destinations: every move, by whom and when. Server-assigned order; SELECT and INSERT only.';

-- THE GRANTS ARRIVE WITH THE TABLE (P6-TSK-001's floor). SELECT and INSERT for the row's life;
-- UPDATE narrowed to the lifecycle columns, and the trigger freezes each once recorded. No
-- DELETE: a destination's end is a status, never an absence.
GRANT SELECT, INSERT ON merchant.payout_destination TO finapp_app;
GRANT UPDATE (status, approved_by, approved_at, cooling_off_until, effective_at, superseded_at, ended_by, ended_at) ON merchant.payout_destination TO finapp_app;

-- The history is append-only at the privilege (the audit_record model).
GRANT SELECT, INSERT ON merchant.payout_destination_event TO finapp_app;
