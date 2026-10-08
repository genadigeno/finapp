-- The decision request (P10-TSK-014; CREDIT_DECISIONING_LIFECYCLES.md section 3.1, ADR-0087; INV-CRD-03, INV-CRD-06,
-- INV-CRD-12, INV-LIFE-01, INV-LIFE-02, INV-HIST-01).
--
-- A customer's application for one credit product: the requested amount (minor units of the product's one currency),
-- the term (an instalment product's alone), the declared monthly income and expenditure, and its lifecycle -
-- SUBMITTED -> COLLECTING -> READY -> EVALUATED -> DECIDED | IN_REVIEW -> DECIDED, READY -> COLLECTING the one backward
-- edge, and the closures CANCELLED (the applicant, before an evaluation), EXPIRED (no decision within the validity) and
-- ABANDONED (the platform: standing lost, or consent withdrawn - always with its reason).
--
-- ONE OPEN PER PARTY AND PRODUCT: a partial UNIQUE over the open states - two submissions racing for one product leave
-- one request, the other refused naming it (INV-CRD-06's precondition).
--
-- EVERY WINDOW IS THE DATABASE'S: submitted_at, expires_at (submission + the product's request validity, which arrives
-- with the insert and is frozen) and the first progress permit are stamped by the trigger from statement_timestamp().
-- The expiry and the system decision use complementary conditionals on that one clock.
--
-- EVERY WRITER MEETS THE MACHINE: the trigger admits exactly the lifecycle document's edges; the terms are frozen from
-- birth; the pinned policy, model and engine versions are written once, at SUBMITTED -> COLLECTING (P10-TSK-015); a
-- terminal request never changes again; nothing is deleted or truncated. Every edge is recorded in the append-only
-- decision_request_event, in the edge's own transaction.

-- The profile's party, so a request's profile is provably its own party's (the composite reference below).
ALTER TABLE credit.credit_profile ADD CONSTRAINT credit_profile_id_and_party UNIQUE (id, party_id);

CREATE TABLE credit.decision_request (
    id                          uuid        NOT NULL,
    party_id                    uuid        NOT NULL,
    profile_id                  uuid        NOT NULL,
    product                     text        NOT NULL,
    currency                    char(3)     NOT NULL,
    requested_minor             bigint      NOT NULL,
    term_months                 integer,
    declared_income_minor       bigint,
    declared_expenditure_minor  bigint,
    status                      text        NOT NULL,
    closure_reason              text,
    request_validity            interval    NOT NULL,
    submitted_at                timestamptz NOT NULL,
    expires_at                  timestamptz NOT NULL,
    next_step_at                timestamptz NOT NULL,
    pinned_policy_version_id    uuid,
    pinned_model_version_id     uuid,
    pinned_engine_version       integer,
    correlation_id              text        NOT NULL,
    CONSTRAINT decision_request_pk PRIMARY KEY (id),
    CONSTRAINT decision_request_id_and_party UNIQUE (id, party_id),
    CONSTRAINT decision_request_profile_fk FOREIGN KEY (profile_id, party_id)
        REFERENCES credit.credit_profile (id, party_id),
    CONSTRAINT decision_request_policy_fk FOREIGN KEY (pinned_policy_version_id)
        REFERENCES credit.credit_policy_version (id),
    CONSTRAINT decision_request_model_fk FOREIGN KEY (pinned_model_version_id)
        REFERENCES credit.scorecard_model_version (id),
    CONSTRAINT decision_request_product_is_known CHECK (product IN ('PERSONAL_LOAN', 'CREDIT_LINE')),
    CONSTRAINT decision_request_currency_shape CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT decision_request_amount_positive CHECK (requested_minor > 0),
    -- An instalment product names its term; a revolving line has none.
    CONSTRAINT decision_request_term_per_product CHECK (
        (product = 'CREDIT_LINE' AND term_months IS NULL)
        OR (product = 'PERSONAL_LOAN' AND term_months IS NOT NULL AND term_months >= 1)),
    CONSTRAINT decision_request_declared_not_negative CHECK (
        (declared_income_minor IS NULL OR declared_income_minor >= 0)
        AND (declared_expenditure_minor IS NULL OR declared_expenditure_minor >= 0)),
    CONSTRAINT decision_request_status_is_known CHECK (status IN (
        'SUBMITTED', 'COLLECTING', 'READY', 'EVALUATED', 'IN_REVIEW', 'DECIDED', 'CANCELLED', 'EXPIRED', 'ABANDONED')),
    -- A platform closure always says why, and nothing else carries a reason.
    CONSTRAINT decision_request_closure_reason_paired CHECK (
        (status = 'ABANDONED') = (closure_reason IS NOT NULL)
        AND (closure_reason IS NULL OR closure_reason IN ('STANDING_LOST', 'CONSENT_WITHDRAWN'))),
    CONSTRAINT decision_request_validity_positive CHECK (request_validity > interval '0'),
    -- The pins are all set or none, and every state past collection's start carries them.
    CONSTRAINT decision_request_pins_together CHECK (
        num_nonnulls(pinned_policy_version_id, pinned_model_version_id, pinned_engine_version) IN (0, 3)),
    CONSTRAINT decision_request_pinned_once_collecting CHECK (
        status NOT IN ('COLLECTING', 'READY', 'EVALUATED', 'IN_REVIEW', 'DECIDED') OR pinned_policy_version_id IS NOT NULL),
    CONSTRAINT decision_request_engine_positive CHECK (pinned_engine_version IS NULL OR pinned_engine_version >= 1),
    CONSTRAINT decision_request_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 128)
);

CREATE UNIQUE INDEX decision_request_one_open_per_product ON credit.decision_request (party_id, product)
    WHERE status IN ('SUBMITTED', 'COLLECTING', 'READY', 'EVALUATED', 'IN_REVIEW');
CREATE INDEX decision_request_due ON credit.decision_request (next_step_at, id)
    WHERE status IN ('SUBMITTED', 'COLLECTING', 'READY', 'EVALUATED', 'IN_REVIEW');
CREATE INDEX decision_request_by_party ON credit.decision_request (party_id, submitted_at);

COMMENT ON TABLE credit.decision_request IS
    'A customer''s credit decision request (P10-TSK-014): one product, its requested amount in minor units of the product''s currency, its term, the declared figures, and its lifecycle (CREDIT_DECISIONING_LIFECYCLES.md 3.1). One open per party and product; every window on the database clock; the terms frozen; the pins written once; every edge by the machine trigger, recorded in decision_request_event. Never deleted or truncated. RESTRICTED-FINANCIAL.';

CREATE OR REPLACE FUNCTION credit.decision_request_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    open_states CONSTANT text[] := ARRAY['SUBMITTED', 'COLLECTING', 'READY', 'EVALUATED', 'IN_REVIEW'];
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'SUBMITTED' OR NEW.closure_reason IS NOT NULL
                OR num_nonnulls(NEW.pinned_policy_version_id, NEW.pinned_model_version_id, NEW.pinned_engine_version) <> 0 THEN
            RAISE EXCEPTION 'a decision request is born SUBMITTED, open and unpinned (P10-TSK-014)';
        END IF;
        NEW.submitted_at := statement_timestamp();
        NEW.expires_at := NEW.submitted_at + NEW.request_validity;
        NEW.next_step_at := NEW.submitted_at;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a decision request is never deleted (INV-HIST-01)';
    END IF;
    IF NOT (OLD.status = ANY (open_states)) THEN
        RAISE EXCEPTION 'a % decision request is terminal: it never changes again (INV-LIFE-02)', OLD.status;
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.party_id IS DISTINCT FROM OLD.party_id
            OR NEW.profile_id IS DISTINCT FROM OLD.profile_id
            OR NEW.product IS DISTINCT FROM OLD.product
            OR NEW.currency IS DISTINCT FROM OLD.currency
            OR NEW.requested_minor IS DISTINCT FROM OLD.requested_minor
            OR NEW.term_months IS DISTINCT FROM OLD.term_months
            OR NEW.declared_income_minor IS DISTINCT FROM OLD.declared_income_minor
            OR NEW.declared_expenditure_minor IS DISTINCT FROM OLD.declared_expenditure_minor
            OR NEW.request_validity IS DISTINCT FROM OLD.request_validity
            OR NEW.submitted_at IS DISTINCT FROM OLD.submitted_at
            OR NEW.expires_at IS DISTINCT FROM OLD.expires_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a decision request''s terms, validity and correlation are frozen from submission (P10-TSK-014)';
    END IF;
    -- The pins: written once, at SUBMITTED -> COLLECTING, and never again.
    IF OLD.pinned_policy_version_id IS NOT NULL THEN
        IF NEW.pinned_policy_version_id IS DISTINCT FROM OLD.pinned_policy_version_id
                OR NEW.pinned_model_version_id IS DISTINCT FROM OLD.pinned_model_version_id
                OR NEW.pinned_engine_version IS DISTINCT FROM OLD.pinned_engine_version THEN
            RAISE EXCEPTION 'a decision request''s pinned versions are written once (INV-HIST-04)';
        END IF;
    ELSIF NEW.pinned_policy_version_id IS NOT NULL
            AND NOT (OLD.status = 'SUBMITTED' AND NEW.status = 'COLLECTING') THEN
        RAISE EXCEPTION 'a decision request is pinned at SUBMITTED -> COLLECTING, and only there (P10-TSK-014)';
    END IF;
    IF NEW.status = OLD.status THEN
        RETURN NEW; -- a permit re-stamped, nothing else
    END IF;
    IF NOT ((OLD.status = 'SUBMITTED' AND NEW.status IN ('COLLECTING', 'CANCELLED', 'EXPIRED', 'ABANDONED'))
            OR (OLD.status = 'COLLECTING' AND NEW.status IN ('READY', 'CANCELLED', 'EXPIRED', 'ABANDONED'))
            OR (OLD.status = 'READY' AND NEW.status IN ('COLLECTING', 'EVALUATED', 'CANCELLED', 'EXPIRED', 'ABANDONED'))
            OR (OLD.status = 'EVALUATED' AND NEW.status IN ('DECIDED', 'IN_REVIEW', 'EXPIRED', 'ABANDONED'))
            OR (OLD.status = 'IN_REVIEW' AND NEW.status IN ('DECIDED', 'EXPIRED', 'ABANDONED'))) THEN
        RAISE EXCEPTION 'a decision request cannot move from % to % (INV-LIFE-01)', OLD.status, NEW.status;
    END IF;
    -- The complementary clock conditionals: an expiry only once expired, a system decision only before.
    IF NEW.status = 'EXPIRED' AND OLD.expires_at > statement_timestamp() THEN
        RAISE EXCEPTION 'a decision request expires only at or after its expiry (P10-TSK-014)';
    END IF;
    IF OLD.status = 'EVALUATED' AND NEW.status = 'DECIDED' AND OLD.expires_at <= statement_timestamp() THEN
        RAISE EXCEPTION 'an expired decision request is not decided by the system (P10-TSK-014)';
    END IF;
    -- No snapshot, no evaluation: EVALUATED only beside the request's policy evaluation. The preconditions of
    -- IN_REVIEW (its underwriting case) and DECIDED (its credit decision) join with those tables (P10-TSK-016, -018).
    IF NEW.status = 'EVALUATED' AND NOT EXISTS (
            SELECT 1 FROM credit.policy_evaluation e WHERE e.decision_request_id = NEW.id) THEN
        RAISE EXCEPTION 'a decision request is EVALUATED only beside its policy evaluation (P10-TSK-014)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER decision_request_permits_only_machine_edges
    BEFORE INSERT OR UPDATE OR DELETE ON credit.decision_request
    FOR EACH ROW
    EXECUTE FUNCTION credit.decision_request_permits_only_machine_edges();

CREATE OR REPLACE FUNCTION credit.decision_request_is_never_truncated()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'a decision request and its history are never truncated (INV-HIST-01)';
END;
$$;

CREATE TRIGGER decision_request_is_never_truncated
    BEFORE TRUNCATE ON credit.decision_request
    FOR EACH STATEMENT
    EXECUTE FUNCTION credit.decision_request_is_never_truncated();

-- ------------------------------------------------------------------ the history

CREATE TABLE credit.decision_request_event (
    id                   uuid        NOT NULL,
    decision_request_id  uuid        NOT NULL,
    from_status          text,
    to_status            text        NOT NULL,
    actor_id             text        NOT NULL,
    actor_type           text        NOT NULL,
    reason               text,
    occurred_at          timestamptz NOT NULL,
    CONSTRAINT decision_request_event_pk PRIMARY KEY (id),
    CONSTRAINT decision_request_event_request_fk FOREIGN KEY (decision_request_id) REFERENCES credit.decision_request (id),
    CONSTRAINT decision_request_event_statuses_known CHECK (
        (from_status IS NULL OR from_status IN ('SUBMITTED', 'COLLECTING', 'READY', 'EVALUATED', 'IN_REVIEW'))
        AND to_status IN ('SUBMITTED', 'COLLECTING', 'READY', 'EVALUATED', 'IN_REVIEW', 'DECIDED', 'CANCELLED',
                          'EXPIRED', 'ABANDONED')),
    -- The birth has no predecessor; every other row names one.
    CONSTRAINT decision_request_event_birth_shape CHECK ((from_status IS NULL) = (to_status = 'SUBMITTED')),
    CONSTRAINT decision_request_event_actor_bounded CHECK (
        char_length(actor_id) BETWEEN 1 AND 200 AND char_length(actor_type) BETWEEN 1 AND 32),
    CONSTRAINT decision_request_event_reason_bounded CHECK (reason IS NULL OR char_length(reason) BETWEEN 1 AND 1000)
);

CREATE INDEX decision_request_event_by_request ON credit.decision_request_event (decision_request_id, occurred_at);

COMMENT ON TABLE credit.decision_request_event IS
    'Every edge of a decision request (P10-TSK-014, INV-LIFE-02): from, to, actor id and type, reason where one applies, occurred_at stamped by the database. Append-only; never updated, deleted or truncated.';

CREATE OR REPLACE FUNCTION credit.decision_request_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a decision request''s history is append-only (P10-TSK-014, INV-LIFE-02)';
    END IF;
    NEW.occurred_at := statement_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER decision_request_event_is_append_only
    BEFORE INSERT OR UPDATE OR DELETE ON credit.decision_request_event
    FOR EACH ROW
    EXECUTE FUNCTION credit.decision_request_event_is_append_only();

CREATE TRIGGER decision_request_event_is_never_truncated
    BEFORE TRUNCATE ON credit.decision_request_event
    FOR EACH STATEMENT
    EXECUTE FUNCTION credit.decision_request_is_never_truncated();

-- ------------------------------------------------------------------ the references that waited for this table (G10)

-- A data request serves a decision request - of the same party.
ALTER TABLE credit.data_request
    ADD CONSTRAINT data_request_decision_request_fk FOREIGN KEY (decision_request_id, party_id)
        REFERENCES credit.decision_request (id, party_id);

ALTER TABLE credit.decision_snapshot
    ADD CONSTRAINT decision_snapshot_decision_request_fk FOREIGN KEY (decision_request_id)
        REFERENCES credit.decision_request (id);

GRANT SELECT, INSERT, UPDATE ON credit.decision_request TO finapp_app;
GRANT SELECT, INSERT ON credit.decision_request_event TO finapp_app;
