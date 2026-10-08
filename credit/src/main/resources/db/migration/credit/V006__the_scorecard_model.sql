-- The scorecard model and its versioning (P10-TSK-011; ADR-0086 section 3, PHASE_10_PLAN.md section 12.5;
-- INV-CRD-05, INV-HIST-04, INV-AUD-04).
--
-- A MODEL VERSION is a points table as rows: a base, and per scored attribute an ABSENT band and ordered value bands -
-- [lower, upper) over an integer attribute, or a code set over a code or boolean one - each with integer points.
-- score = base + the points of the band each scored attribute falls in. It changes only forward and only under four
-- eyes, on the corridor policy's shape (crossborder V002), per model family:
--
--     PROPOSED --a different approver--> ACTIVE --only beside its successor--> RETIRED
--         \--anyone (a withdrawal or a correction)--> REJECTED
--
-- Held for EVERY writer: the edge trigger, identity and content frozen; the BANDS born with their version in its
-- proposing transaction and immutable from insert - never updated or deleted, and refused for a version not created
-- in the inserting transaction, in every status (a correction is a rejection and a new proposal); one PROPOSED and
-- one ACTIVE version per family (partial uniques); activator <> proposer by CHECK, with NO SEED EXEMPTION; a
-- retirement committed only beside its successor (a deferred constraint trigger). The effective period is stamped by
-- the database - effective_from at activation, effective_to at retirement, both transaction_timestamp(), so a
-- predecessor's end IS its successor's start - and which version was active at any past instant is answered from
-- the rows. Proposals of one family serialise on advisory namespace 10 (DISTRIBUTED_EXECUTION.md section 3); the
-- partial unique is the backstop.
--
-- RETAIL_SCORECARD v1 is seeded HERE AS A PROPOSAL (proposed_by 'migration:V006'): no model is migration-activated -
-- two persons holding CREDIT_POLICY_ADMINISTER activate it, and the four-eyes CHECK binds it like any other.

CREATE TABLE credit.scorecard_model_version (
    id                  uuid        NOT NULL,
    family              text        NOT NULL,
    version             integer     NOT NULL,
    status              text        NOT NULL,
    base_points         integer     NOT NULL,
    proposed_by         text        NOT NULL,
    proposed_at         timestamptz NOT NULL,
    proposal_reason     text        NOT NULL,
    decided_by          text,
    decided_at          timestamptz,
    decision_reason     text,
    effective_from      timestamptz,
    effective_to        timestamptz,
    CONSTRAINT scorecard_model_version_pk PRIMARY KEY (id),
    CONSTRAINT scorecard_model_version_number_unique UNIQUE (family, version),
    CONSTRAINT scorecard_model_version_number_positive CHECK (version >= 1),
    CONSTRAINT scorecard_model_family_is_known CHECK (family IN ('RETAIL_SCORECARD')),
    CONSTRAINT scorecard_model_status_is_known CHECK (status IN ('PROPOSED', 'ACTIVE', 'RETIRED', 'REJECTED')),
    CONSTRAINT scorecard_model_base_bounded CHECK (base_points BETWEEN -100000 AND 100000),
    CONSTRAINT scorecard_model_proposal_reasoned CHECK (char_length(proposal_reason) BETWEEN 1 AND 1000),
    -- A decision exists exactly when the version has left PROPOSED.
    CONSTRAINT scorecard_model_decision_coherent CHECK (
        (status = 'PROPOSED') = (decided_by IS NULL)
        AND (decided_by IS NULL) = (decided_at IS NULL)
        AND (decided_by IS NULL) = (decision_reason IS NULL)),
    CONSTRAINT scorecard_model_decision_reasoned CHECK (decision_reason IS NULL OR char_length(decision_reason) BETWEEN 1 AND 1000),
    -- FOUR EYES, with no seed exemption: an ACTIVE or RETIRED version was activated by someone other than its
    -- proposer (a REJECTED one may be the proposer's withdrawal).
    CONSTRAINT scorecard_model_four_eyes CHECK (status NOT IN ('ACTIVE', 'RETIRED') OR decided_by <> proposed_by),
    -- The effective period: from at activation, to at retirement; never for a version that was never active.
    CONSTRAINT scorecard_model_effective_coherent CHECK (
        (status IN ('ACTIVE', 'RETIRED')) = (effective_from IS NOT NULL)
        AND (status = 'RETIRED') = (effective_to IS NOT NULL)
        AND (effective_to IS NULL OR effective_to >= effective_from))
);

COMMENT ON TABLE credit.scorecard_model_version IS
    'A scorecard model version (P10-TSK-011, ADR-0086 section 3): versioned per family, four-eyes, seeded only as a proposal. One ACTIVE and one PROPOSED per family; activator <> proposer by CHECK; identity and bands frozen from PROPOSED; the effective period stamped by the database; a retirement only beside its successor. Every decision pins the version that scored it (INV-HIST-04).';

CREATE UNIQUE INDEX scorecard_model_one_active ON credit.scorecard_model_version (family) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX scorecard_model_one_proposed ON credit.scorecard_model_version (family) WHERE status = 'PROPOSED';
CREATE INDEX scorecard_model_effective ON credit.scorecard_model_version (family, effective_from)
    WHERE effective_from IS NOT NULL;

CREATE OR REPLACE FUNCTION credit.scorecard_model_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PROPOSED' OR NEW.decided_by IS NOT NULL OR NEW.effective_from IS NOT NULL
                OR NEW.effective_to IS NOT NULL THEN
            RAISE EXCEPTION 'a scorecard model version is born PROPOSED and undecided: no model is activated by insert (P10-TSK-011)';
        END IF;
        NEW.proposed_at := transaction_timestamp();
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a scorecard model version is never deleted (P10-TSK-011)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.family IS DISTINCT FROM OLD.family
            OR NEW.version IS DISTINCT FROM OLD.version
            OR NEW.base_points IS DISTINCT FROM OLD.base_points
            OR NEW.proposed_by IS DISTINCT FROM OLD.proposed_by
            OR NEW.proposed_at IS DISTINCT FROM OLD.proposed_at
            OR NEW.proposal_reason IS DISTINCT FROM OLD.proposal_reason THEN
        RAISE EXCEPTION 'a scorecard model version''s identity and content are frozen (P10-TSK-011, INV-CRD-05)';
    END IF;
    IF NOT ((OLD.status = 'PROPOSED' AND NEW.status IN ('ACTIVE', 'REJECTED'))
            OR (OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED')) THEN
        RAISE EXCEPTION 'a scorecard model version moves PROPOSED -> ACTIVE | REJECTED, ACTIVE -> RETIRED; % -> % is not an edge (P10-TSK-011)', OLD.status, NEW.status;
    END IF;
    IF OLD.status = 'PROPOSED' THEN
        NEW.decided_at := transaction_timestamp();
        NEW.effective_from := CASE WHEN NEW.status = 'ACTIVE' THEN transaction_timestamp() END;
        NEW.effective_to := NULL;
    ELSE
        IF NEW.decided_by IS DISTINCT FROM OLD.decided_by OR NEW.decided_at IS DISTINCT FROM OLD.decided_at
                OR NEW.decision_reason IS DISTINCT FROM OLD.decision_reason
                OR NEW.effective_from IS DISTINCT FROM OLD.effective_from THEN
            RAISE EXCEPTION 'an activation''s decision is frozen; a retirement records only its end (P10-TSK-011)';
        END IF;
        NEW.effective_to := transaction_timestamp();
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER scorecard_model_permits_only_machine_edges
    BEFORE INSERT OR UPDATE OR DELETE ON credit.scorecard_model_version
    FOR EACH ROW
    EXECUTE FUNCTION credit.scorecard_model_permits_only_machine_edges();

CREATE OR REPLACE FUNCTION credit.scorecard_model_is_never_truncated()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'the scorecard model''s history is never truncated (P10-TSK-011, INV-HIST-04)';
END;
$$;

CREATE TRIGGER scorecard_model_version_is_never_truncated
    BEFORE TRUNCATE ON credit.scorecard_model_version
    FOR EACH STATEMENT
    EXECUTE FUNCTION credit.scorecard_model_is_never_truncated();

-- A retirement commits only beside its successor: at COMMIT, a later version of the family must be ACTIVE.
CREATE OR REPLACE FUNCTION credit.scorecard_model_retires_only_beside_its_successor()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'RETIRED' AND NOT EXISTS (
            SELECT 1 FROM credit.scorecard_model_version successor
             WHERE successor.family = NEW.family AND successor.status = 'ACTIVE' AND successor.version > NEW.version
               AND successor.effective_from = NEW.effective_to) THEN
        RAISE EXCEPTION 'scorecard model version % retired with no later ACTIVE successor starting where it ends (P10-TSK-011)', NEW.version;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER scorecard_model_retires_only_beside_its_successor
    AFTER UPDATE ON credit.scorecard_model_version
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION credit.scorecard_model_retires_only_beside_its_successor();

-- ------------------------------------------------------------------ the bands

CREATE TABLE credit.scorecard_band (
    model_version_id  uuid    NOT NULL,
    attribute_code    text    NOT NULL,
    ordinal           integer NOT NULL,
    kind              text    NOT NULL,
    lower_bound       bigint,
    upper_bound       bigint,
    codes             text[],
    points            integer NOT NULL,
    CONSTRAINT scorecard_band_pk PRIMARY KEY (model_version_id, attribute_code, ordinal),
    CONSTRAINT scorecard_band_version_fk FOREIGN KEY (model_version_id) REFERENCES credit.scorecard_model_version (id),
    CONSTRAINT scorecard_band_code_shape CHECK (attribute_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT scorecard_band_kind_is_known CHECK (kind IN ('ABSENT', 'RANGE', 'CODES')),
    CONSTRAINT scorecard_band_points_bounded CHECK (points BETWEEN -100000 AND 100000),
    -- The ABSENT band is ordinal 0 and carries nothing else; value bands are 1.. in order.
    CONSTRAINT scorecard_band_shape CHECK (
        (kind = 'ABSENT' AND ordinal = 0 AND lower_bound IS NULL AND upper_bound IS NULL AND codes IS NULL)
        OR (kind = 'RANGE' AND ordinal >= 1 AND codes IS NULL
            AND (lower_bound IS NULL OR upper_bound IS NULL OR lower_bound < upper_bound))
        OR (kind = 'CODES' AND ordinal >= 1 AND lower_bound IS NULL AND upper_bound IS NULL
            AND cardinality(codes) BETWEEN 1 AND 64 AND array_position(codes, NULL) IS NULL))
);

COMMENT ON TABLE credit.scorecard_band IS
    'One band of a scorecard model version (P10-TSK-011): an attribute''s ABSENT band (ordinal 0) or an ordered [lower, upper) range or code set, with integer points. Born with its version in the proposing transaction; never updated or deleted, and refused for any version not created in the inserting transaction (INV-CRD-05).';

-- The bands join their version in the version's own transaction, or not at all. xmin is the inserting transaction's
-- 32-bit id; pg_current_xact_id() is the epoch-extended one, so the comparison is modulo 2^32 (reconciliation V012's
-- precedent). The version is written at the top level of its transaction, so its xmin IS the transaction's id; a
-- writer that wrapped it in a savepoint is refused - fail-closed. UPDATE and DELETE are refused in every status.
CREATE OR REPLACE FUNCTION credit.scorecard_band_is_born_with_its_version()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a scorecard band is immutable from insert: a correction is a rejection and a new proposal (P10-TSK-011, INV-CRD-05)';
    END IF;
    IF NOT EXISTS (
            SELECT 1 FROM credit.scorecard_model_version v
             WHERE v.id = NEW.model_version_id
               AND v.status = 'PROPOSED'
               AND v.xmin::text::bigint = pg_current_xact_id()::text::bigint % 4294967296) THEN
        RAISE EXCEPTION 'a scorecard band is born with its version, in the proposing transaction (P10-TSK-011, INV-CRD-05): an approver approves exactly what was proposed';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER scorecard_band_is_born_with_its_version
    BEFORE INSERT OR UPDATE OR DELETE ON credit.scorecard_band
    FOR EACH ROW
    EXECUTE FUNCTION credit.scorecard_band_is_born_with_its_version();

CREATE TRIGGER scorecard_band_is_never_truncated
    BEFORE TRUNCATE ON credit.scorecard_band
    FOR EACH STATEMENT
    EXECUTE FUNCTION credit.scorecard_model_is_never_truncated();

-- ------------------------------------------------------------------ the history

CREATE TABLE credit.scorecard_model_event (
    id                uuid        NOT NULL,
    model_version_id  uuid        NOT NULL,
    from_status       text,
    to_status         text        NOT NULL,
    actor_id          text        NOT NULL,
    reason            text        NOT NULL,
    occurred_at       timestamptz NOT NULL,
    CONSTRAINT scorecard_model_event_pk PRIMARY KEY (id),
    CONSTRAINT scorecard_model_event_version_fk FOREIGN KEY (model_version_id) REFERENCES credit.scorecard_model_version (id),
    CONSTRAINT scorecard_model_event_statuses_known CHECK (
        (from_status IS NULL OR from_status IN ('PROPOSED', 'ACTIVE'))
        AND to_status IN ('PROPOSED', 'ACTIVE', 'RETIRED', 'REJECTED')),
    CONSTRAINT scorecard_model_event_reason_bounded CHECK (char_length(reason) BETWEEN 1 AND 1000)
);

CREATE INDEX scorecard_model_event_by_version ON credit.scorecard_model_event (model_version_id, occurred_at);

CREATE OR REPLACE FUNCTION credit.scorecard_model_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a scorecard model event is append-only (P10-TSK-011)';
    END IF;
    NEW.occurred_at := transaction_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER scorecard_model_event_is_append_only
    BEFORE INSERT OR UPDATE OR DELETE ON credit.scorecard_model_event
    FOR EACH ROW
    EXECUTE FUNCTION credit.scorecard_model_event_is_append_only();

CREATE TRIGGER scorecard_model_event_is_never_truncated
    BEFORE TRUNCATE ON credit.scorecard_model_event
    FOR EACH STATEMENT
    EXECUTE FUNCTION credit.scorecard_model_is_never_truncated();

-- ------------------------------------------------------------------ the pinned model's reference (V005's promise)

ALTER TABLE credit.decision_snapshot
    ADD CONSTRAINT decision_snapshot_model_version_fk
    FOREIGN KEY (model_version_id) REFERENCES credit.scorecard_model_version (id);

-- ------------------------------------------------------------------ RETAIL_SCORECARD v1, as a proposal

INSERT INTO credit.scorecard_model_version (id, family, version, status, base_points, proposed_by, proposed_at, proposal_reason)
VALUES ('0190a1b2-5c0e-7000-8000-00000000c001', 'RETAIL_SCORECARD', 1, 'PROPOSED', 500, 'migration:V006',
        transaction_timestamp(), 'RETAIL_SCORECARD version 1 (PHASE_10_PLAN.md section 12.5): seeded as a proposal, activated only by two persons');

INSERT INTO credit.scorecard_band (model_version_id, attribute_code, ordinal, kind, lower_bound, upper_bound, codes, points) VALUES
    ('0190a1b2-5c0e-7000-8000-00000000c001', 'BUREAU_EXTERNAL_SCORE',    0, 'ABSENT', NULL, NULL, NULL,  -40),
    ('0190a1b2-5c0e-7000-8000-00000000c001', 'BUREAU_EXTERNAL_SCORE',    1, 'RANGE',  NULL, 550,  NULL,  -60),
    ('0190a1b2-5c0e-7000-8000-00000000c001', 'BUREAU_EXTERNAL_SCORE',    2, 'RANGE',  550,  650,  NULL,    0),
    ('0190a1b2-5c0e-7000-8000-00000000c001', 'BUREAU_EXTERNAL_SCORE',    3, 'RANGE',  650,  750,  NULL,   40),
    ('0190a1b2-5c0e-7000-8000-00000000c001', 'BUREAU_EXTERNAL_SCORE',    4, 'RANGE',  750,  NULL, NULL,   80),
    ('0190a1b2-5c0e-7000-8000-00000000c001', 'BUREAU_DELINQUENCIES_24M', 0, 'ABSENT', NULL, NULL, NULL,  -20),
    ('0190a1b2-5c0e-7000-8000-00000000c001', 'BUREAU_DELINQUENCIES_24M', 1, 'RANGE',  NULL, 1,    NULL,   30),
    ('0190a1b2-5c0e-7000-8000-00000000c001', 'BUREAU_DELINQUENCIES_24M', 2, 'RANGE',  1,    3,    NULL,  -30),
    ('0190a1b2-5c0e-7000-8000-00000000c001', 'BUREAU_DELINQUENCIES_24M', 3, 'RANGE',  3,    NULL, NULL,  -90),
    ('0190a1b2-5c0e-7000-8000-00000000c001', 'BUREAU_DEFAULTS_72M',      0, 'ABSENT', NULL, NULL, NULL,  -20),
    ('0190a1b2-5c0e-7000-8000-00000000c001', 'BUREAU_DEFAULTS_72M',      1, 'RANGE',  NULL, 1,    NULL,   20),
    ('0190a1b2-5c0e-7000-8000-00000000c001', 'BUREAU_DEFAULTS_72M',      2, 'RANGE',  1,    NULL, NULL, -120),
    ('0190a1b2-5c0e-7000-8000-00000000c001', 'BUREAU_ACTIVE_ACCOUNTS',   0, 'ABSENT', NULL, NULL, NULL,    0),
    ('0190a1b2-5c0e-7000-8000-00000000c001', 'BUREAU_ACTIVE_ACCOUNTS',   1, 'RANGE',  NULL, 1,    NULL,  -10),
    ('0190a1b2-5c0e-7000-8000-00000000c001', 'BUREAU_ACTIVE_ACCOUNTS',   2, 'RANGE',  1,    6,    NULL,   10),
    ('0190a1b2-5c0e-7000-8000-00000000c001', 'BUREAU_ACTIVE_ACCOUNTS',   3, 'RANGE',  6,    NULL, NULL,  -20),
    ('0190a1b2-5c0e-7000-8000-00000000c001', 'BUREAU_INSOLVENCY_FLAG',   0, 'ABSENT', NULL, NULL, NULL,  -30),
    ('0190a1b2-5c0e-7000-8000-00000000c001', 'BUREAU_INSOLVENCY_FLAG',   1, 'CODES',  NULL, NULL, ARRAY['false'],  10),
    ('0190a1b2-5c0e-7000-8000-00000000c001', 'BUREAU_INSOLVENCY_FLAG',   2, 'CODES',  NULL, NULL, ARRAY['true'], -200);

INSERT INTO credit.scorecard_model_event (id, model_version_id, from_status, to_status, actor_id, reason, occurred_at)
VALUES ('0190a1b2-5c0e-7000-8000-00000000c0e1', '0190a1b2-5c0e-7000-8000-00000000c001', NULL, 'PROPOSED',
        'migration:V006', 'seeded as a proposal (P10-TSK-011)', transaction_timestamp());

-- ------------------------------------------------------------------ the grants

GRANT SELECT, INSERT ON credit.scorecard_model_version TO finapp_app;
GRANT UPDATE (status, decided_by, decided_at, decision_reason, effective_from, effective_to)
    ON credit.scorecard_model_version TO finapp_app;
GRANT SELECT, INSERT ON credit.scorecard_band TO finapp_app;
GRANT SELECT, INSERT ON credit.scorecard_model_event TO finapp_app;
