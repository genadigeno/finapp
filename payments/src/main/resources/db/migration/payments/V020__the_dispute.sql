-- The dispute (P7-TSK-012, ADR-0061 sections 1, 2 and 6): a contested card payment's own
-- lifecycle, one row per provider dispute reference however often the network notifies it,
-- driven by the card PSP's signed notifications alone. No money moves here - the stages
-- exist; their postings are P7-TSK-013's, keyed by this row's id and its stage.
--
-- THE NETWORK'S OPENING STATEMENT IS FROZEN FOR EVERY WRITER: the provider, its dispute
-- reference, the contested attempt and the reason category are what the dispute IS, and a
-- later notification that contradicts the attempt moves nothing. The stage moves one machine
-- edge at a time, each edge with its trail row - and THE CHARGEBACK'S AMOUNT ARRIVES WITH THE
-- CHARGEBACK (the captured amount's discipline, NULL -> value): an inquiry states only the
-- transaction it asks about, and a chargeback may take less, so the figure P7-TSK-013 posts
-- is recorded exactly when the network takes the funds and never moves after.
--
-- RECORDED AGAINST THE ATTEMPT THE NETWORK NAMES, WHATEVER ITS STATE (the external fact
-- first, ADR-0061 section 4): a chargeback is money the network has already taken, so the
-- schema refuses no attempt state here - attribution is the combined bound's (P7-TSK-013).
--
-- EVERY GENERATED LIST BELOW HAS ONE DEFINITION: stages from DisputeStage.sqlValueList(), the
-- birth list from entrySqlValueList(), edges from permittedTransitions(), reasons from
-- DisputeReason.sqlValueList(); PaymentsMigrationTest fails the build on drift.

CREATE TABLE payments.dispute (
    -- UUIDv7, minted by the application (ADR-0013).
    id                          uuid        PRIMARY KEY,

    -- The PSP's stable adapter name: the scope its references are unique in.
    provider                    text        NOT NULL
        CONSTRAINT dispute_provider_shape
            CHECK (provider ~ '^[a-z][a-z0-9-]{0,63}$'),

    -- The PSP's identifier for the dispute, stable across its stages (ProviderReference's
    -- own charset and bound) - reconciliation's key for the dispute and its stage entries.
    provider_dispute_reference  text        NOT NULL
        CONSTRAINT dispute_provider_reference_shape
            CHECK (provider_dispute_reference ~ '^[A-Za-z0-9_.:-]{1,128}$'),

    -- The attempt the network contests - the row the combined bound is judged on.
    attempt_id                  uuid        NOT NULL REFERENCES payments.payment_attempt (id),

    -- Generated from DisputeReason.sqlValueList(): OUR category, never the network's code.
    reason                      text        NOT NULL
        CONSTRAINT dispute_reason_is_known
            CHECK (reason IN ('FRAUD', 'AUTHORIZATION', 'PROCESSING_ERROR', 'CONSUMER_DISPUTE', 'UNCATEGORISED')),

    -- Generated from DisputeStage.sqlValueList().
    stage                       text        NOT NULL
        CONSTRAINT dispute_stage_is_known
            CHECK (stage IN ('INQUIRY', 'CHARGED_BACK', 'REPRESENTED', 'WON', 'LOST', 'ACCEPTED', 'CLOSED')),

    -- What the network took (P7-TSK-013 posts it; a win reverses it): the NULLABLE money
    -- fragment, present EXACTLY when the funds have been taken - every stage but the two
    -- before any chargeback, generated from DisputeStage.notChargedBackSqlValueList().
    chargeback_amount_minor BIGINT, chargeback_currency CHAR(3), chargeback_scale SMALLINT, CHECK (chargeback_currency ~ '^[A-Z]{3}$'), CHECK (chargeback_scale BETWEEN 0 AND 9), CHECK ((chargeback_amount_minor IS NULL) = (chargeback_currency IS NULL) AND (chargeback_amount_minor IS NULL) = (chargeback_scale IS NULL)),
    CONSTRAINT dispute_chargeback_matches_stage
        CHECK ((stage IN ('INQUIRY', 'CLOSED')) = (chargeback_amount_minor IS NULL)),
    CONSTRAINT dispute_chargeback_is_positive
        CHECK (chargeback_amount_minor IS NULL OR chargeback_amount_minor > 0),

    -- Application-supplied from the injected Clock, never DEFAULT now() (P0-TSK-015).
    opened_at                   timestamptz NOT NULL,

    -- ONE ROW PER NETWORK DISPUTE (ADR-0061 section 1, INV-IDEM-04's second rank): the
    -- arbiter that makes ten concurrent deliveries of one opening - each under a fresh event
    -- id, past the inbox - insert exactly one row. A second-cycle chargeback arrives with a
    -- new reference and is a new dispute, never a reopened one (INV-LIFE-04).
    CONSTRAINT dispute_one_per_provider_reference UNIQUE (provider, provider_dispute_reference)
);

-- A payment's disputes: the operator's read, and the combined bound's (P7-TSK-013).
CREATE INDEX dispute_by_attempt ON payments.dispute (attempt_id);

-- Every writer: a dispute is born at an entry stage - the network opens with an inquiry or a
-- chargeback, and every later stage arrives through the machine.
CREATE FUNCTION payments.dispute_is_born_at_an_entry_stage() RETURNS trigger
LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.stage NOT IN ('INQUIRY', 'CHARGED_BACK') THEN
        RAISE EXCEPTION 'a dispute is born at an entry stage - INQUIRY or CHARGED_BACK - and reaches every other through the machine (P7-TSK-012, INV-LIFE-02)'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER dispute_is_born_at_an_entry_stage
    BEFORE INSERT ON payments.dispute
    FOR EACH ROW
    EXECUTE FUNCTION payments.dispute_is_born_at_an_entry_stage();

-- The machine and the freeze, bound to every writer including the migrator (the withdrawal's
-- function shape; edges generated from DisputeStage.permittedTransitions()). The freeze is
-- judged FIRST and NULL-safely (IS DISTINCT FROM - the P7-TSK-007 class, never NULL-blind),
-- so a status-preserving edit of a frozen column is refused by its own clause.
CREATE FUNCTION payments.dispute_permits_only_machine_edges() RETURNS trigger
LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.provider IS DISTINCT FROM OLD.provider
            OR NEW.provider_dispute_reference IS DISTINCT FROM OLD.provider_dispute_reference
            OR NEW.attempt_id IS DISTINCT FROM OLD.attempt_id
            OR NEW.reason IS DISTINCT FROM OLD.reason
            OR NEW.opened_at IS DISTINCT FROM OLD.opened_at THEN
        RAISE EXCEPTION 'a dispute''s opening statement is frozen: provider, reference, attempt and reason never move (P7-TSK-012, INV-HIST-01''s discipline)';
    END IF;
    -- The chargeback's amount moves only NULL -> value (the attempt's payload discipline):
    -- the CHECK above lets it arrive only with the funds taken, and this refuses its
    -- revision or its removal once recorded.
    IF (OLD.chargeback_amount_minor IS NOT NULL
                AND NEW.chargeback_amount_minor IS DISTINCT FROM OLD.chargeback_amount_minor)
            OR (OLD.chargeback_currency IS NOT NULL
                AND NEW.chargeback_currency IS DISTINCT FROM OLD.chargeback_currency)
            OR (OLD.chargeback_scale IS NOT NULL
                AND NEW.chargeback_scale IS DISTINCT FROM OLD.chargeback_scale) THEN
        RAISE EXCEPTION 'a recorded chargeback amount never changes: it arrives with the funds taken and moves only from NULL to a value (P7-TSK-012, INV-HIST-02)';
    END IF;
    IF NEW.stage = OLD.stage THEN
        RETURN NEW;
    END IF;
    IF NOT ((OLD.stage = 'INQUIRY' AND NEW.stage IN ('CHARGED_BACK', 'CLOSED'))
            OR (OLD.stage = 'CHARGED_BACK' AND NEW.stage IN ('REPRESENTED', 'LOST', 'ACCEPTED'))
            OR (OLD.stage = 'REPRESENTED' AND NEW.stage IN ('WON', 'LOST'))) THEN
        RAISE EXCEPTION 'a dispute moves only along the machine''s edges: INQUIRY -> {CHARGED_BACK, CLOSED}, CHARGED_BACK -> {REPRESENTED, LOST, ACCEPTED}, REPRESENTED -> {WON, LOST}; WON, LOST, ACCEPTED and CLOSED are terminal (INV-LIFE-02/-04)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER dispute_permits_only_machine_edges
    BEFORE UPDATE ON payments.dispute
    FOR EACH ROW
    EXECUTE FUNCTION payments.dispute_permits_only_machine_edges();

-- The append-only trail (the withdrawal_event ceremony): every stage the dispute moved
-- through after its birth, by whom and when. The birth itself is the row, its audit record
-- and its DisputeOpened event.
CREATE TABLE payments.dispute_event (
    id          bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    dispute_id  uuid        NOT NULL REFERENCES payments.dispute (id),
    from_stage  text        NOT NULL
        CONSTRAINT dispute_event_from_stage_is_known
            CHECK (from_stage IN ('INQUIRY', 'CHARGED_BACK', 'REPRESENTED', 'WON', 'LOST', 'ACCEPTED', 'CLOSED')),
    to_stage    text        NOT NULL
        CONSTRAINT dispute_event_to_stage_is_known
            CHECK (to_stage IN ('INQUIRY', 'CHARGED_BACK', 'REPRESENTED', 'WON', 'LOST', 'ACCEPTED', 'CLOSED')),
    actor_id    text        NOT NULL
        CONSTRAINT dispute_event_actor_id_bounded
            CHECK (length(actor_id) BETWEEN 1 AND 200),
    actor_type  text        NOT NULL
        CONSTRAINT dispute_event_actor_type_bounded
            CHECK (length(actor_type) BETWEEN 1 AND 50),
    occurred_at timestamptz NOT NULL
);

CREATE INDEX dispute_event_by_dispute ON payments.dispute_event (dispute_id);

-- ----------------------------------------------------------------- comments and grants

COMMENT ON TABLE payments.dispute IS
    'One dispute on a card payment (P7-TSK-012, ADR-0061): the network''s opening statement frozen, the stage moving only along the machine for every writer, one row per (provider, provider_dispute_reference). No posting lives here: the stages'' money is P7-TSK-013''s, keyed by the dispute and its stage.';
COMMENT ON COLUMN payments.dispute.provider_dispute_reference IS
    'The PSP''s identifier for the dispute (the acquirer_reference class): stable across the dispute''s stages, reconciliation''s join to the stage entries (ADR-0061, Phase 8).';
COMMENT ON COLUMN payments.dispute.chargeback_amount_minor IS
    'What the network took, in the chargeback''s currency and scale: present exactly when the funds have been taken (CHARGED_BACK and after), set with that edge, never revised - the figure P7-TSK-013 posts and a win reverses.';
COMMENT ON COLUMN payments.dispute.stage IS
    'INQUIRY -> {CHARGED_BACK, CLOSED}; CHARGED_BACK -> {REPRESENTED, LOST, ACCEPTED}; REPRESENTED -> {WON, LOST}. Born at INQUIRY or CHARGED_BACK; a notified stage ahead applies every stage between, in order (ADR-0061 section 2).';
COMMENT ON TABLE payments.dispute_event IS
    'Append-only dispute trail: every stage move after birth, as evidence rather than memory. Server-assigned order; SELECT and INSERT only.';

GRANT SELECT, INSERT ON payments.dispute TO finapp_app;
GRANT UPDATE (stage, chargeback_amount_minor, chargeback_currency, chargeback_scale)
    ON payments.dispute TO finapp_app;
GRANT SELECT, INSERT ON payments.dispute_event TO finapp_app;
