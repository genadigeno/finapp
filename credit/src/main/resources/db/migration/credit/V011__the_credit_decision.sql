-- The credit decision (P10-TSK-016; PHASE_10_PLAN.md section 12.7, ADR-0087; INV-CRD-01, INV-CRD-02, INV-CRD-04,
-- INV-CRD-06, INV-CRD-09, INV-HIST-04).
--
-- What was decided on a decision request, exactly once and never changed: the outcome (APPROVED | DECLINED - the
-- decision's word, apart from the evaluation's), an approval's amount and term in minor units of the product's
-- currency, the ordered reason codes, the snapshot it was made from and its SHA-256, the pinned policy, model and engine
-- versions, who decided (the platform or a person), when - and until when an approval reserves credit exposure
-- (valid_until = decided_at + the product's decision validity, on the database clock).
--
-- BORN ONCE PER REQUEST (UNIQUE (decision_request_id)), and NEVER UPDATED OR DELETED BY ANY ROLE: the application holds
-- neither privilege, and the trigger refuses the owner too. A DECLINED decision, and an APPROVED one below its request
-- (capped), commit only with their reasons - a deferred constraint trigger judges the transaction at commit.
--
-- credit_decision_consumption is created empty for Phase 11 (G1): a consumed approval reserves nothing, the loan it
-- became being the exposure.

CREATE TABLE credit.credit_decision (
    id                    uuid        NOT NULL,
    decision_request_id   uuid        NOT NULL,
    party_id              uuid        NOT NULL,
    profile_id            uuid        NOT NULL,
    product               text        NOT NULL,
    snapshot_id           uuid        NOT NULL,
    snapshot_sha256       bytea       NOT NULL,
    outcome               text        NOT NULL,
    currency              char(3)     NOT NULL,
    requested_minor       bigint      NOT NULL,
    approved_minor        bigint,
    term_months           integer,
    decision_validity     interval    NOT NULL,
    decided_at            timestamptz NOT NULL,
    valid_until           timestamptz NOT NULL,
    decided_by            text        NOT NULL,
    decided_by_type       text        NOT NULL,
    policy_version_id     uuid        NOT NULL,
    model_version_id      uuid        NOT NULL,
    engine_version        integer     NOT NULL,
    CONSTRAINT credit_decision_pk PRIMARY KEY (id),
    CONSTRAINT credit_decision_once_per_request UNIQUE (decision_request_id),
    CONSTRAINT credit_decision_request_fk FOREIGN KEY (decision_request_id, party_id)
        REFERENCES credit.decision_request (id, party_id),
    CONSTRAINT credit_decision_profile_fk FOREIGN KEY (profile_id, party_id) REFERENCES credit.credit_profile (id, party_id),
    CONSTRAINT credit_decision_snapshot_fk FOREIGN KEY (snapshot_id) REFERENCES credit.decision_snapshot (id),
    CONSTRAINT credit_decision_policy_fk FOREIGN KEY (policy_version_id) REFERENCES credit.credit_policy_version (id),
    CONSTRAINT credit_decision_model_fk FOREIGN KEY (model_version_id) REFERENCES credit.scorecard_model_version (id),
    CONSTRAINT credit_decision_product_is_known CHECK (product IN ('PERSONAL_LOAN', 'CREDIT_LINE')),
    CONSTRAINT credit_decision_outcome_is_known CHECK (outcome IN ('APPROVED', 'DECLINED')),
    CONSTRAINT credit_decision_currency_shape CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT credit_decision_hash_shape CHECK (octet_length(snapshot_sha256) = 32),
    CONSTRAINT credit_decision_requested_positive CHECK (requested_minor > 0),
    -- An approval, and only an approval, approves an amount - positive, at most the request - and an instalment
    -- product's approval its term.
    CONSTRAINT credit_decision_approval_coherent CHECK (
        (outcome = 'APPROVED' AND approved_minor IS NOT NULL AND approved_minor > 0 AND approved_minor <= requested_minor
            AND ((product = 'PERSONAL_LOAN') = (term_months IS NOT NULL)))
        OR (outcome = 'DECLINED' AND approved_minor IS NULL AND term_months IS NULL)),
    CONSTRAINT credit_decision_decided_by_type_is_known CHECK (decided_by_type IN ('SYSTEM', 'EMPLOYEE')),
    CONSTRAINT credit_decision_decided_by_bounded CHECK (char_length(decided_by) BETWEEN 1 AND 200),
    CONSTRAINT credit_decision_engine_positive CHECK (engine_version >= 1)
);

-- The reservation read under the profile lock: a party's approvals still valid and not consumed.
CREATE INDEX credit_decision_by_party ON credit.credit_decision (party_id, valid_until) WHERE outcome = 'APPROVED';

COMMENT ON TABLE credit.credit_decision IS
    'A decision request''s decision (P10-TSK-016): the outcome, an approval''s amount and term, the snapshot it was made from and its hash, the pinned versions, who decided and when, and until when an approval reserves exposure (the database''s clock). Born once per request; never updated, deleted or truncated by any role. RESTRICTED-FINANCIAL.';

CREATE OR REPLACE FUNCTION credit.credit_decision_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a credit decision is never updated, deleted or truncated - by any role (P10-TSK-016, INV-CRD-02)';
    END IF;
    NEW.decided_at := statement_timestamp();
    NEW.valid_until := NEW.decided_at + NEW.decision_validity;
    RETURN NEW;
END;
$$;

CREATE TRIGGER credit_decision_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON credit.credit_decision
    FOR EACH ROW
    EXECUTE FUNCTION credit.credit_decision_is_born_once();

CREATE TRIGGER credit_decision_is_never_truncated
    BEFORE TRUNCATE ON credit.credit_decision
    FOR EACH STATEMENT
    EXECUTE FUNCTION credit.credit_decision_is_born_once();

-- ------------------------------------------------------------------ the reasons

CREATE TABLE credit.credit_decision_reason (
    decision_id  uuid    NOT NULL,
    ordinal      integer NOT NULL,
    reason_code  text    NOT NULL,
    CONSTRAINT credit_decision_reason_pk PRIMARY KEY (decision_id, ordinal),
    CONSTRAINT credit_decision_reason_code_once UNIQUE (decision_id, reason_code),
    CONSTRAINT credit_decision_reason_decision_fk FOREIGN KEY (decision_id) REFERENCES credit.credit_decision (id),
    CONSTRAINT credit_decision_reason_catalogued FOREIGN KEY (reason_code) REFERENCES credit.reason_code (code),
    CONSTRAINT credit_decision_reason_ordinal_positive CHECK (ordinal >= 1)
);

COMMENT ON TABLE credit.credit_decision_reason IS
    'A credit decision''s reason codes in order (P10-TSK-016, INV-CRD-02): catalogued, each once. Born with the decision in the deciding transaction; never updated, deleted or truncated.';

-- The reasons join their decision in the decision's own transaction, or not at all (V006's xmin test).
CREATE OR REPLACE FUNCTION credit.credit_decision_reason_is_born_with_its_decision()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a credit decision''s reasons are never updated, deleted or truncated (P10-TSK-016)';
    END IF;
    IF NOT EXISTS (
            SELECT 1 FROM credit.credit_decision d
             WHERE d.id = NEW.decision_id
               AND d.xmin::text::bigint = pg_current_xact_id()::text::bigint % 4294967296) THEN
        RAISE EXCEPTION 'a reason is born with its decision, in the deciding transaction (P10-TSK-016)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER credit_decision_reason_is_born_with_its_decision
    BEFORE INSERT OR UPDATE OR DELETE ON credit.credit_decision_reason
    FOR EACH ROW
    EXECUTE FUNCTION credit.credit_decision_reason_is_born_with_its_decision();

CREATE TRIGGER credit_decision_reason_is_never_truncated
    BEFORE TRUNCATE ON credit.credit_decision_reason
    FOR EACH STATEMENT
    EXECUTE FUNCTION credit.credit_decision_is_born_once();

-- INV-CRD-02 at commit: a decline, or an approval below its request, never commits without its reasons.
CREATE OR REPLACE FUNCTION credit.credit_decision_is_explained()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF (NEW.outcome = 'DECLINED' OR NEW.approved_minor < NEW.requested_minor)
            AND NOT EXISTS (SELECT 1 FROM credit.credit_decision_reason r WHERE r.decision_id = NEW.id) THEN
        RAISE EXCEPTION 'a declined or capped credit decision commits only with its reasons (P10-TSK-016, INV-CRD-02)';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER credit_decision_is_explained
    AFTER INSERT ON credit.credit_decision
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION credit.credit_decision_is_explained();

-- ------------------------------------------------------------------ consumption, empty until Phase 11 (G1)

CREATE TABLE credit.credit_decision_consumption (
    id           uuid        NOT NULL,
    decision_id  uuid        NOT NULL,
    consumed_at  timestamptz NOT NULL,
    CONSTRAINT credit_decision_consumption_pk PRIMARY KEY (id),
    CONSTRAINT credit_decision_consumption_once UNIQUE (decision_id),
    CONSTRAINT credit_decision_consumption_decision_fk FOREIGN KEY (decision_id) REFERENCES credit.credit_decision (id)
);

COMMENT ON TABLE credit.credit_decision_consumption IS
    'An approval consumed by the loan it became (P10-TSK-016 creates it empty; Phase 11 writes it): once per decision, append-only; a consumed approval reserves no exposure.';

CREATE OR REPLACE FUNCTION credit.credit_decision_consumption_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a consumption is append-only (P10-TSK-016)';
    END IF;
    NEW.consumed_at := statement_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER credit_decision_consumption_is_append_only
    BEFORE INSERT OR UPDATE OR DELETE ON credit.credit_decision_consumption
    FOR EACH ROW
    EXECUTE FUNCTION credit.credit_decision_consumption_is_append_only();

CREATE TRIGGER credit_decision_consumption_is_never_truncated
    BEFORE TRUNCATE ON credit.credit_decision_consumption
    FOR EACH STATEMENT
    EXECUTE FUNCTION credit.credit_decision_consumption_is_append_only();

-- ------------------------------------------------------------------ DECIDED only beside its decision

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
    IF OLD.status = 'EVALUATED' AND NEW.status = 'DECIDED' AND OLD.expires_at <= statement_timestamp() THEN
        RAISE EXCEPTION 'an expired decision request is not decided by the system (P10-TSK-014)';
    END IF;
    IF NEW.status = 'EVALUATED' AND NOT EXISTS (
            SELECT 1 FROM credit.policy_evaluation e WHERE e.decision_request_id = NEW.id) THEN
        RAISE EXCEPTION 'a decision request is EVALUATED only beside its policy evaluation (P10-TSK-014)';
    END IF;
    -- No decision, no DECIDED (P10-TSK-016). IN_REVIEW's precondition, its underwriting case, joins with that table
    -- (P10-TSK-018).
    IF NEW.status = 'DECIDED' AND NOT EXISTS (
            SELECT 1 FROM credit.credit_decision d WHERE d.decision_request_id = NEW.id) THEN
        RAISE EXCEPTION 'a decision request is DECIDED only beside its credit decision (P10-TSK-016)';
    END IF;
    RETURN NEW;
END;
$$;

GRANT SELECT, INSERT ON credit.credit_decision TO finapp_app;
GRANT SELECT, INSERT ON credit.credit_decision_reason TO finapp_app;
GRANT SELECT, INSERT ON credit.credit_decision_consumption TO finapp_app;
