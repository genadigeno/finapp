-- The underwriting case (P10-TSK-018; CREDIT_DECISIONING_LIFECYCLES.md section 3.3, ADR-0089; INV-CRD-11, INV-CRD-09,
-- INV-CRD-06, INV-CRD-02, INV-AUD-04, INV-LIFE-01, INV-LIFE-02).
--
-- A REFER evaluation is a question for a person. The deciding transaction opens ONE case per referral (UNIQUE
-- (decision_request_id)) and moves the request IN_REVIEW; a person takes it, decides it with reasons, and - above the
-- product's four-eyes threshold, copied onto the case at birth - a DIFFERENT person approves it before the decision is
-- recorded. The machine: OPEN -> ASSIGNED -> DECIDED, ASSIGNED -> AWAITING_SECOND -> DECIDED, ASSIGNED -> OPEN (released),
-- AWAITING_SECOND -> ASSIGNED (the second approver refuses, reasoned - the first decision cleared here, kept in the
-- history), and the terminal CLOSED with its request: from OPEN when the request expires or is abandoned, from a taken
-- case only when the request is ABANDONED (by its person's own deciding transaction - the domain's rank).
--
-- WHAT A PERSON MAY NOT DO, AT THE DATABASE: second-approve their own decision (the four-eyes CHECK); decide without a
-- reason code (the first-decision CHECK); approve more than the referral's ceiling (approvable_minor - the request capped
-- by every CAP_AMOUNT rule the basis evaluation triggered, stamped at birth); approve above the threshold without a
-- second person (the DECIDED shape CHECK); hold a case whose basis evaluation triggered a HARD_DECLINE (the birth trigger).
--
-- THE REQUEST AND ITS CASE MOVE TOGETHER: a request is IN_REVIEW only beside its OPEN case; IN_REVIEW -> EXPIRED only while
-- the case is OPEN (a taken case is decided by its person whatever the validity); a case is assigned only under an
-- IN_REVIEW request, closes only once its request has closed, and is DECIDED only beside the request's decision; and a
-- request that leaves IN_REVIEW commits only with its case terminal (a deferred constraint trigger).

CREATE TABLE credit.underwriting_case (
    id                         uuid        NOT NULL,
    decision_request_id        uuid        NOT NULL,
    party_id                   uuid        NOT NULL,
    product                    text        NOT NULL,
    currency                   char(3)     NOT NULL,
    basis_evaluation_id        uuid        NOT NULL,
    requested_minor            bigint      NOT NULL,
    approvable_minor           bigint      NOT NULL,
    four_eyes_threshold_minor  bigint      NOT NULL,
    status                     text        NOT NULL,
    assignee                   text,
    first_outcome              text,
    first_approved_minor       bigint,
    first_reason_codes         text[],
    first_reason               text,
    first_decided_by           text,
    first_decided_at           timestamptz,
    second_decided_by          text,
    second_decided_at          timestamptz,
    closure_reason             text,
    opened_at                  timestamptz NOT NULL,
    CONSTRAINT underwriting_case_pk PRIMARY KEY (id),
    CONSTRAINT underwriting_case_once_per_request UNIQUE (decision_request_id),
    CONSTRAINT underwriting_case_request_fk FOREIGN KEY (decision_request_id, party_id)
        REFERENCES credit.decision_request (id, party_id),
    CONSTRAINT underwriting_case_basis_fk FOREIGN KEY (basis_evaluation_id) REFERENCES credit.policy_evaluation (id),
    CONSTRAINT underwriting_case_product_is_known CHECK (product IN ('PERSONAL_LOAN', 'CREDIT_LINE')),
    CONSTRAINT underwriting_case_currency_shape CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT underwriting_case_amounts_coherent CHECK (
        requested_minor > 0 AND approvable_minor > 0 AND approvable_minor <= requested_minor
        AND four_eyes_threshold_minor > 0),
    CONSTRAINT underwriting_case_status_is_known CHECK (
        status IN ('OPEN', 'ASSIGNED', 'AWAITING_SECOND', 'DECIDED', 'CLOSED')),
    CONSTRAINT underwriting_case_actors_bounded CHECK (
        (assignee IS NULL OR char_length(assignee) BETWEEN 1 AND 200)
        AND (first_decided_by IS NULL OR char_length(first_decided_by) BETWEEN 1 AND 200)
        AND (second_decided_by IS NULL OR char_length(second_decided_by) BETWEEN 1 AND 200)),
    -- A first decision is whole or absent: an outcome, its reason codes (at least one), its reason, who and when.
    CONSTRAINT underwriting_case_first_decision_whole CHECK (
        num_nonnulls(first_outcome, first_reason_codes, first_reason, first_decided_by, first_decided_at) IN (0, 5)
        AND (first_outcome IS NULL OR first_outcome IN ('APPROVED', 'DECLINED'))
        AND (first_reason_codes IS NULL
            OR (cardinality(first_reason_codes) BETWEEN 1 AND 32 AND array_position(first_reason_codes, NULL) IS NULL))
        AND (first_reason IS NULL OR (btrim(first_reason) <> '' AND char_length(first_reason) <= 1000))),
    -- An approval, and only an approval, approves an amount - positive, at most the referral's ceiling (G7).
    CONSTRAINT underwriting_case_first_amount_bounded CHECK (
        (first_outcome = 'APPROVED' AND first_approved_minor BETWEEN 1 AND approvable_minor)
        OR (first_outcome IS DISTINCT FROM 'APPROVED' AND first_approved_minor IS NULL)),
    CONSTRAINT underwriting_case_second_whole CHECK (num_nonnulls(second_decided_by, second_decided_at) IN (0, 2)),
    -- INV-CRD-11, INV-AUD-04: the second approver is never the first decider.
    CONSTRAINT underwriting_case_four_eyes CHECK (
        second_decided_by IS NULL OR (first_decided_by IS NOT NULL AND second_decided_by <> first_decided_by)),
    CONSTRAINT underwriting_case_closure_reason_known CHECK (
        closure_reason IS NULL OR closure_reason IN ('EXPIRED', 'STANDING_LOST', 'CONSENT_WITHDRAWN')),
    -- Each state's shape.
    CONSTRAINT underwriting_case_status_shape CHECK (
        (status = 'OPEN' AND assignee IS NULL AND first_outcome IS NULL AND second_decided_by IS NULL
            AND closure_reason IS NULL)
        OR (status = 'ASSIGNED' AND assignee IS NOT NULL AND first_outcome IS NULL AND second_decided_by IS NULL
            AND closure_reason IS NULL)
        OR (status = 'AWAITING_SECOND' AND assignee IS NOT NULL AND first_outcome = 'APPROVED'
            AND first_approved_minor > four_eyes_threshold_minor AND first_decided_by = assignee
            AND second_decided_by IS NULL AND closure_reason IS NULL)
        OR (status = 'DECIDED' AND assignee IS NOT NULL AND first_outcome IS NOT NULL AND first_decided_by = assignee
            AND closure_reason IS NULL
            -- four eyes above the threshold: a second person exactly when the approval is above it
            AND ((second_decided_by IS NOT NULL)
                = (first_outcome = 'APPROVED' AND first_approved_minor > four_eyes_threshold_minor)))
        OR (status = 'CLOSED' AND closure_reason IS NOT NULL AND second_decided_by IS NULL))
);

-- The queue, oldest first, and the review-age gauge's oldest OPEN case.
CREATE INDEX underwriting_case_queue ON credit.underwriting_case (status, opened_at, id);

COMMENT ON TABLE credit.underwriting_case IS
    'A referral''s manual review (P10-TSK-018, ADR-0089): born OPEN once per referred request by the deciding transaction, with its basis evaluation, the referral''s ceiling and the product''s four-eyes threshold; assigned, released, decided with reasons, second-approved above the threshold by a different person, or closed with its request. Every edge by the machine trigger, recorded in underwriting_case_event. Never deleted or truncated. RESTRICTED-FINANCIAL.';

CREATE OR REPLACE FUNCTION credit.underwriting_case_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    request_status  text;
    request_reason  text;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'an underwriting case is never deleted (P10-TSK-018, INV-HIST-01)';
    END IF;
    IF NEW.first_reason_codes IS NOT NULL THEN
        IF cardinality(NEW.first_reason_codes) <> (SELECT count(DISTINCT c) FROM unnest(NEW.first_reason_codes) AS c) THEN
            RAISE EXCEPTION 'a case''s decision names each reason code once (P10-TSK-018)';
        END IF;
        IF EXISTS (SELECT 1 FROM unnest(NEW.first_reason_codes) AS c
                    WHERE NOT EXISTS (SELECT 1 FROM credit.reason_code r WHERE r.code = c)) THEN
            RAISE EXCEPTION 'a case''s reason codes are the catalogue''s (P10-TSK-018, INV-CRD-11)';
        END IF;
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'OPEN' THEN
            RAISE EXCEPTION 'an underwriting case is born OPEN (P10-TSK-018)';
        END IF;
        IF NOT EXISTS (
                SELECT 1 FROM credit.decision_request r
                 WHERE r.id = NEW.decision_request_id AND r.status = 'EVALUATED' AND r.party_id = NEW.party_id
                   AND r.product = NEW.product AND r.currency = NEW.currency
                   AND r.requested_minor = NEW.requested_minor) THEN
            RAISE EXCEPTION 'a case is opened for an EVALUATED request, with its terms (P10-TSK-018)';
        END IF;
        IF NOT EXISTS (
                SELECT 1 FROM credit.policy_evaluation e
                 WHERE e.id = NEW.basis_evaluation_id AND e.decision_request_id = NEW.decision_request_id
                   AND e.outcome = 'REFER') THEN
            RAISE EXCEPTION 'a case''s basis is its request''s REFER evaluation (P10-TSK-018)';
        END IF;
        IF EXISTS (
                SELECT 1 FROM credit.policy_evaluation_rule r
                 WHERE r.evaluation_id = NEW.basis_evaluation_id AND r.effect = 'HARD_DECLINE' AND r.triggered) THEN
            RAISE EXCEPTION 'a hard decline is never referred, so no case holds one (P10-TSK-018, ADR-0089 point 2)';
        END IF;
        NEW.opened_at := statement_timestamp();
        RETURN NEW;
    END IF;
    IF OLD.status IN ('DECIDED', 'CLOSED') THEN
        RAISE EXCEPTION 'a % underwriting case is terminal: it never changes again (INV-LIFE-02)', OLD.status;
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.decision_request_id IS DISTINCT FROM OLD.decision_request_id
            OR NEW.party_id IS DISTINCT FROM OLD.party_id
            OR NEW.product IS DISTINCT FROM OLD.product
            OR NEW.currency IS DISTINCT FROM OLD.currency
            OR NEW.basis_evaluation_id IS DISTINCT FROM OLD.basis_evaluation_id
            OR NEW.requested_minor IS DISTINCT FROM OLD.requested_minor
            OR NEW.approvable_minor IS DISTINCT FROM OLD.approvable_minor
            OR NEW.four_eyes_threshold_minor IS DISTINCT FROM OLD.four_eyes_threshold_minor
            OR NEW.opened_at IS DISTINCT FROM OLD.opened_at THEN
        RAISE EXCEPTION 'a case''s request, basis, ceiling and threshold are frozen from its birth (P10-TSK-018)';
    END IF;
    IF NEW.status = OLD.status THEN
        RAISE EXCEPTION 'an underwriting case changes only by an edge of its machine (P10-TSK-018)';
    END IF;
    IF NOT ((OLD.status = 'OPEN' AND NEW.status IN ('ASSIGNED', 'CLOSED'))
            OR (OLD.status = 'ASSIGNED' AND NEW.status IN ('OPEN', 'AWAITING_SECOND', 'DECIDED', 'CLOSED'))
            OR (OLD.status = 'AWAITING_SECOND' AND NEW.status IN ('ASSIGNED', 'DECIDED', 'CLOSED'))) THEN
        RAISE EXCEPTION 'an underwriting case cannot move from % to % (INV-LIFE-01)', OLD.status, NEW.status;
    END IF;
    SELECT r.status, r.closure_reason INTO request_status, request_reason
      FROM credit.decision_request r WHERE r.id = NEW.decision_request_id;
    IF NEW.status = 'ASSIGNED' AND OLD.status = 'OPEN' AND request_status <> 'IN_REVIEW' THEN
        RAISE EXCEPTION 'a case is assigned only while its request is IN_REVIEW (P10-TSK-018)';
    END IF;
    IF NEW.status IN ('ASSIGNED', 'AWAITING_SECOND', 'DECIDED') AND OLD.status <> 'OPEN'
            AND NEW.assignee IS DISTINCT FROM OLD.assignee THEN
        RAISE EXCEPTION 'a taken case stays with its underwriter (P10-TSK-018)';
    END IF;
    -- The first decision: written at ASSIGNED -> AWAITING_SECOND | DECIDED by the assignee, carried unchanged to the
    -- second approval or a closure, and cleared only by a refused second approval.
    IF OLD.status = 'ASSIGNED' AND NEW.status IN ('AWAITING_SECOND', 'DECIDED') THEN
        IF NEW.first_decided_by IS DISTINCT FROM OLD.assignee THEN
            RAISE EXCEPTION 'a case is decided by its assignee (P10-TSK-018)';
        END IF;
        NEW.first_decided_at := statement_timestamp();
    ELSIF NOT (OLD.status = 'AWAITING_SECOND' AND NEW.status = 'ASSIGNED')
            AND (NEW.first_outcome IS DISTINCT FROM OLD.first_outcome
                OR NEW.first_approved_minor IS DISTINCT FROM OLD.first_approved_minor
                OR NEW.first_reason_codes IS DISTINCT FROM OLD.first_reason_codes
                OR NEW.first_reason IS DISTINCT FROM OLD.first_reason
                OR NEW.first_decided_by IS DISTINCT FROM OLD.first_decided_by
                OR NEW.first_decided_at IS DISTINCT FROM OLD.first_decided_at) THEN
        RAISE EXCEPTION 'a case''s first decision is written once, at its decision (P10-TSK-018)';
    END IF;
    IF OLD.status = 'AWAITING_SECOND' AND NEW.status = 'DECIDED' THEN
        NEW.second_decided_at := statement_timestamp();
    END IF;
    IF NEW.status = 'DECIDED' AND NOT (request_status = 'DECIDED' AND EXISTS (
            SELECT 1 FROM credit.credit_decision d WHERE d.decision_request_id = NEW.decision_request_id)) THEN
        RAISE EXCEPTION 'a case is DECIDED only beside its request''s decision (P10-TSK-018)';
    END IF;
    IF NEW.status = 'CLOSED' THEN
        IF OLD.status = 'OPEN' AND request_status NOT IN ('EXPIRED', 'ABANDONED') THEN
            RAISE EXCEPTION 'an open case closes only with its expired or abandoned request (P10-TSK-018, G8)';
        END IF;
        IF OLD.status <> 'OPEN' AND request_status <> 'ABANDONED' THEN
            RAISE EXCEPTION 'a taken case closes only with its abandoned request (P10-TSK-018, G8)';
        END IF;
        IF NEW.closure_reason IS DISTINCT FROM (CASE request_status WHEN 'EXPIRED' THEN 'EXPIRED' ELSE request_reason END) THEN
            RAISE EXCEPTION 'a closed case carries its request''s reason (P10-TSK-018, G8)';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER underwriting_case_permits_only_machine_edges
    BEFORE INSERT OR UPDATE OR DELETE ON credit.underwriting_case
    FOR EACH ROW
    EXECUTE FUNCTION credit.underwriting_case_permits_only_machine_edges();

CREATE OR REPLACE FUNCTION credit.underwriting_case_is_never_truncated()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'an underwriting case and its history are never truncated (P10-TSK-018, INV-HIST-01)';
END;
$$;

CREATE TRIGGER underwriting_case_is_never_truncated
    BEFORE TRUNCATE ON credit.underwriting_case
    FOR EACH STATEMENT
    EXECUTE FUNCTION credit.underwriting_case_is_never_truncated();

-- ------------------------------------------------------------------ the history

CREATE TABLE credit.underwriting_case_event (
    id                uuid        NOT NULL,
    case_id           uuid        NOT NULL,
    from_status       text,
    to_status         text        NOT NULL,
    actor_id          text        NOT NULL,
    actor_type        text        NOT NULL,
    outcome           text,
    approved_minor    bigint,
    reason_codes      text[],
    reason            text,
    first_decided_by  text,
    occurred_at       timestamptz NOT NULL,
    CONSTRAINT underwriting_case_event_pk PRIMARY KEY (id),
    CONSTRAINT underwriting_case_event_case_fk FOREIGN KEY (case_id) REFERENCES credit.underwriting_case (id),
    CONSTRAINT underwriting_case_event_statuses_known CHECK (
        (from_status IS NULL OR from_status IN ('OPEN', 'ASSIGNED', 'AWAITING_SECOND'))
        AND to_status IN ('OPEN', 'ASSIGNED', 'AWAITING_SECOND', 'DECIDED', 'CLOSED')),
    -- The birth has no predecessor and is OPEN; a release back to OPEN names its predecessor.
    CONSTRAINT underwriting_case_event_birth_shape CHECK (from_status IS NOT NULL OR to_status = 'OPEN'),
    CONSTRAINT underwriting_case_event_actor_bounded CHECK (
        char_length(actor_id) BETWEEN 1 AND 200 AND char_length(actor_type) BETWEEN 1 AND 32),
    CONSTRAINT underwriting_case_event_reason_bounded CHECK (
        reason IS NULL OR (btrim(reason) <> '' AND char_length(reason) <= 1000)),
    CONSTRAINT underwriting_case_event_outcome_known CHECK (outcome IS NULL OR outcome IN ('APPROVED', 'DECLINED')),
    -- A person's decision is reasoned: an outcome, at least one code and a reason (INV-CRD-11).
    CONSTRAINT underwriting_case_event_decision_reasoned CHECK (
        NOT (from_status = 'ASSIGNED' AND to_status IN ('AWAITING_SECOND', 'DECIDED'))
        OR (outcome IS NOT NULL AND reason_codes IS NOT NULL AND cardinality(reason_codes) >= 1 AND reason IS NOT NULL)),
    -- The second person's acts: never the first decider's, and a refusal is reasoned (INV-AUD-04).
    CONSTRAINT underwriting_case_event_second_person CHECK (
        NOT (from_status = 'AWAITING_SECOND' AND to_status IN ('ASSIGNED', 'DECIDED'))
        OR (first_decided_by IS NOT NULL AND actor_id <> first_decided_by
            AND (to_status <> 'ASSIGNED' OR reason IS NOT NULL)))
);

CREATE INDEX underwriting_case_event_by_case ON credit.underwriting_case_event (case_id, occurred_at);

COMMENT ON TABLE credit.underwriting_case_event IS
    'Every edge of an underwriting case (P10-TSK-018): from, to, the actor, a decision''s outcome, amount, reason codes and reason, the first decider a second person''s act answered, occurred_at stamped by the database. A refused second approval''s first decision is kept here. Append-only; never updated, deleted or truncated.';

CREATE OR REPLACE FUNCTION credit.underwriting_case_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'an underwriting case''s history is append-only (P10-TSK-018, INV-LIFE-02)';
    END IF;
    NEW.occurred_at := statement_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER underwriting_case_event_is_append_only
    BEFORE INSERT OR UPDATE OR DELETE ON credit.underwriting_case_event
    FOR EACH ROW
    EXECUTE FUNCTION credit.underwriting_case_event_is_append_only();

CREATE TRIGGER underwriting_case_event_is_never_truncated
    BEFORE TRUNCATE ON credit.underwriting_case_event
    FOR EACH STATEMENT
    EXECUTE FUNCTION credit.underwriting_case_is_never_truncated();

-- ------------------------------------------------------------------ the request's machine meets its case

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
        RETURN NEW;
    END IF;
    IF NOT ((OLD.status = 'SUBMITTED' AND NEW.status IN ('COLLECTING', 'CANCELLED', 'EXPIRED', 'ABANDONED'))
            OR (OLD.status = 'COLLECTING' AND NEW.status IN ('READY', 'CANCELLED', 'EXPIRED', 'ABANDONED'))
            OR (OLD.status = 'READY' AND NEW.status IN ('COLLECTING', 'EVALUATED', 'CANCELLED', 'EXPIRED', 'ABANDONED'))
            OR (OLD.status = 'EVALUATED' AND NEW.status IN ('DECIDED', 'IN_REVIEW', 'EXPIRED', 'ABANDONED'))
            OR (OLD.status = 'IN_REVIEW' AND NEW.status IN ('DECIDED', 'EXPIRED', 'ABANDONED'))) THEN
        RAISE EXCEPTION 'a decision request cannot move from % to % (INV-LIFE-01)', OLD.status, NEW.status;
    END IF;
    IF NEW.status = 'EXPIRED' AND OLD.expires_at > statement_timestamp() THEN
        RAISE EXCEPTION 'a decision request expires only at or after its expiry (P10-TSK-014)';
    END IF;
    -- The system's complementary conditionals: neither a decision nor a referral once expired.
    IF OLD.status = 'EVALUATED' AND NEW.status IN ('DECIDED', 'IN_REVIEW') AND OLD.expires_at <= statement_timestamp() THEN
        RAISE EXCEPTION 'an expired decision request is not decided or referred by the system (P10-TSK-014, -018)';
    END IF;
    IF NEW.status = 'EVALUATED' AND NOT EXISTS (
            SELECT 1 FROM credit.policy_evaluation e WHERE e.decision_request_id = NEW.id) THEN
        RAISE EXCEPTION 'a decision request is EVALUATED only beside its policy evaluation (P10-TSK-014)';
    END IF;
    IF NEW.status = 'DECIDED' AND NOT EXISTS (
            SELECT 1 FROM credit.credit_decision d WHERE d.decision_request_id = NEW.id) THEN
        RAISE EXCEPTION 'a decision request is DECIDED only beside its credit decision (P10-TSK-016)';
    END IF;
    -- No case, no review (P10-TSK-018).
    IF NEW.status = 'IN_REVIEW' AND NOT EXISTS (
            SELECT 1 FROM credit.underwriting_case c WHERE c.decision_request_id = NEW.id AND c.status = 'OPEN') THEN
        RAISE EXCEPTION 'a decision request is IN_REVIEW only beside its open underwriting case (P10-TSK-018)';
    END IF;
    -- A taken case is decided by its person whatever the validity: only an OPEN case expires with its request.
    IF OLD.status = 'IN_REVIEW' AND NEW.status = 'EXPIRED' AND NOT EXISTS (
            SELECT 1 FROM credit.underwriting_case c WHERE c.decision_request_id = NEW.id AND c.status = 'OPEN') THEN
        RAISE EXCEPTION 'a request whose case is taken does not expire (P10-TSK-018, G8)';
    END IF;
    RETURN NEW;
END;
$$;

-- A request leaves IN_REVIEW only with its case terminal, judged at commit: DECIDED beside a DECIDED case, a closure
-- beside a CLOSED one.
CREATE OR REPLACE FUNCTION credit.decision_request_leaves_review_with_its_case()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    request_status text;
BEGIN
    SELECT r.status INTO request_status FROM credit.decision_request r WHERE r.id = NEW.id;
    IF NOT EXISTS (
            SELECT 1 FROM credit.underwriting_case c
             WHERE c.decision_request_id = NEW.id
               AND c.status = CASE request_status WHEN 'DECIDED' THEN 'DECIDED' ELSE 'CLOSED' END) THEN
        RAISE EXCEPTION 'a request leaves IN_REVIEW only with its case decided or closed beside it (P10-TSK-018)';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER decision_request_leaves_review_with_its_case
    AFTER UPDATE ON credit.decision_request
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (OLD.status = 'IN_REVIEW' AND NEW.status <> 'IN_REVIEW')
    EXECUTE FUNCTION credit.decision_request_leaves_review_with_its_case();

GRANT SELECT, INSERT, UPDATE ON credit.underwriting_case TO finapp_app;
GRANT SELECT, INSERT ON credit.underwriting_case_event TO finapp_app;
