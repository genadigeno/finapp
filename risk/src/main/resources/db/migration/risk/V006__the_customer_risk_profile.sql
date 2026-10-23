-- The customer risk profile (P13-TSK-007; PHASE_13_PLAN.md sections 3.1, 4, 7.1 T-mon, 7.2 R8, 8, 12.5;
-- RISK_LIFECYCLES.md section 4.9; ADR-0107, ADR-0109; INV-RSK-10, INV-RSK-07, INV-HIST-04).
--
-- A HEAD ROW AND APPEND-ONLY VERSIONS
--   customer_risk_profile is one row per party: the current version and rating, and the recompute's permit. It is
--   born UNASSESSED (version 0, no rating) by a signal's hint or by a recompute, and its version and rating move only
--   together, one step at a time, beside a version row carrying the same rating - the trigger below refuses any other
--   move, so the head always says what its newest version says. customer_risk_profile_version is INSERT-only: one row
--   per rating CHANGE (an unchanged rating writes nothing), numbered without a gap (the in-sequence trigger), never
--   updated, deleted or truncated by any writer. PRIMARY KEY (party_id, version) is the UNIQUE (party_id, version) the
--   plan names: ten sweepers recomputing one party under the head's lock (R8) write one version.
--
-- WHAT A VERSION RECORDS
--   A RECOMPUTE version carries the evaluation that set it: the MONITORING policy version pinned (a composite foreign
--   key to risk_policy_version (id, operation), so the database proves it is a monitoring version), the engine, the
--   party's signal score, the ordered reason codes and the sealed inputs in CanonicalEvaluation's format 1 with their
--   SHA-256 (recomputed by a CHECK) - so the rating is replayable from the row (INV-HIST-04). A DISPOSITION version
--   (P13-TSK-016's door) carries the approved disposition instead; its foreign key arrives with risk V009.
--
-- PROHIBITED IS A PERSON'S ACT, TWICE OVER (INV-RSK-07, INV-RSK-10)
--   A PROHIBITED version names its disposition (the CHECK) and only a disposition writes one; and a RECOMPUTE version
--   never follows a PROHIBITED one (the in-sequence trigger) - the sweep neither raises a rating to PROHIBITED nor
--   lowers one from it. The engine cannot produce it either (RatingBand, Verdict refuse it).
--
-- NEVER MONEY
--   No amount, no balance: the profile is derived and versioned, read by credit's seam (seam version 2, the version
--   recorded in credit's snapshot) and by every evaluation (PROFILE_RATING), never an authority over money.

CREATE TABLE risk.customer_risk_profile (
    -- The party, by value (ADR-0029: no cross-schema foreign key).
    party_id            uuid        NOT NULL,

    -- The newest version's number; 0 while the party is enrolled but not yet assessed.
    current_version     integer     NOT NULL,

    -- The newest version's rating; NULL exactly while current_version is 0.
    rating              text,

    -- The permit: the sweep claims the head when this instant has come. Pulled to now by a signal's hint; re-stamped on
    -- the database clock after every recompute (the cadence).
    next_recompute_at   timestamptz NOT NULL,

    -- The database clock at the last recompute; NULL until the first.
    recomputed_at       timestamptz,

    -- The database clock at the head's birth, stamped by the trigger.
    created_at          timestamptz NOT NULL,

    CONSTRAINT customer_risk_profile_pk PRIMARY KEY (party_id),

    CONSTRAINT customer_risk_profile_current_version_counts CHECK (current_version >= 0),

    CONSTRAINT customer_risk_profile_rating_is_known
        CHECK (rating IS NULL OR rating IN ('LOW', 'MEDIUM', 'HIGH', 'PROHIBITED')),

    CONSTRAINT customer_risk_profile_rated_iff_versioned CHECK ((current_version = 0) = (rating IS NULL))
);

-- The sweep's claim: the heads whose permit has come, oldest first.
CREATE INDEX customer_risk_profile_due ON risk.customer_risk_profile (next_recompute_at);

COMMENT ON TABLE risk.customer_risk_profile IS
    'The customer risk profile''s head per party (P13-TSK-007; INV-RSK-10): the current version and rating - always '
    'its newest version''s, the trigger refusing any other move - and the recompute''s permit. Locked FOR UPDATE at the '
    'Phase 13 lock order''s R8 by every recompute; read without a lock by credit''s seam and every evaluation. Never '
    'deleted.';

-- ---------------------------------------------------------------------------------------------------------------------

CREATE TABLE risk.customer_risk_profile_version (
    party_id            uuid        NOT NULL,
    version             integer     NOT NULL,
    rating              text        NOT NULL,

    -- What set it: RECOMPUTE (the MONITORING policy's rating bands, through the engine) or DISPOSITION (a four-eyes
    -- case disposition, P13-TSK-016).
    cause               text        NOT NULL,

    -- RECOMPUTE: the party's signal score - the fired weights summed, clamped to [0, 1000]. Risk's number, never
    -- answered to credit (INV-CRD-04: credit receives the rating's code).
    signal_score        integer,

    -- The fired rules' reason codes in evaluation order, comma-joined; '' when none fired.
    reason_codes        text        NOT NULL,

    -- RECOMPUTE: the MONITORING policy version pinned (R1), by id and number, and the engine that evaluated it.
    policy_version_id   uuid,
    policy_operation    text,
    policy_version      integer,
    engine              text,

    -- RECOMPUTE: the sealed inputs, CanonicalEvaluation format 1, and the SHA-256 of their UTF-8 bytes (lowercase hex).
    inputs_canonical    text,
    inputs_sha256       text,

    -- DISPOSITION: the approved disposition that set the rating (P13-TSK-016; its foreign key with risk V009).
    disposition_id      uuid,

    -- The flow that wrote the version - a disposition's, or the recompute's own.
    correlation_id      text        NOT NULL,

    -- The database clock at the insert, stamped by the trigger.
    computed_at         timestamptz NOT NULL,

    -- The UNIQUE (party_id, version) of PHASE_13_PLAN.md section 4: one version per number, under any concurrency.
    CONSTRAINT customer_risk_profile_version_pk PRIMARY KEY (party_id, version),

    CONSTRAINT customer_risk_profile_version_head_fk
        FOREIGN KEY (party_id) REFERENCES risk.customer_risk_profile (party_id),

    -- The pinned version is a MONITORING one - the database says so, not only the code.
    CONSTRAINT customer_risk_profile_version_policy_fk
        FOREIGN KEY (policy_version_id, policy_operation) REFERENCES risk.risk_policy_version (id, operation),

    CONSTRAINT customer_risk_profile_version_counts CHECK (version >= 1),

    CONSTRAINT customer_risk_profile_version_rating_is_known
        CHECK (rating IN ('LOW', 'MEDIUM', 'HIGH', 'PROHIBITED')),

    CONSTRAINT customer_risk_profile_version_cause_is_known CHECK (cause IN ('RECOMPUTE', 'DISPOSITION')),

    -- A recompute carries its whole evaluation and no disposition.
    CONSTRAINT customer_risk_profile_version_recompute_is_whole CHECK (cause <> 'RECOMPUTE' OR (
        signal_score IS NOT NULL AND policy_version_id IS NOT NULL AND policy_operation IS NOT NULL
        AND policy_version IS NOT NULL AND engine IS NOT NULL AND inputs_canonical IS NOT NULL
        AND inputs_sha256 IS NOT NULL AND disposition_id IS NULL)),

    -- A disposition carries its disposition and no evaluation.
    CONSTRAINT customer_risk_profile_version_disposition_is_whole CHECK (cause <> 'DISPOSITION' OR (
        disposition_id IS NOT NULL AND signal_score IS NULL AND policy_version_id IS NULL AND policy_operation IS NULL
        AND policy_version IS NULL AND engine IS NULL AND inputs_canonical IS NULL AND inputs_sha256 IS NULL)),

    -- INV-RSK-07, INV-RSK-10: PROHIBITED names the approved disposition that set it - never a band's answer.
    CONSTRAINT customer_risk_profile_version_prohibited_only_by_a_disposition
        CHECK (rating <> 'PROHIBITED' OR (cause = 'DISPOSITION' AND disposition_id IS NOT NULL)),

    CONSTRAINT customer_risk_profile_version_score_bounded
        CHECK (signal_score IS NULL OR signal_score BETWEEN 0 AND 1000),

    CONSTRAINT customer_risk_profile_version_engine_is_known CHECK (engine IS NULL OR engine IN ('RISK_ENGINE_V1')),

    CONSTRAINT customer_risk_profile_version_operation_is_monitoring
        CHECK (policy_operation IS NULL OR policy_operation = 'MONITORING'),

    CONSTRAINT customer_risk_profile_version_policy_numbered CHECK (policy_version IS NULL OR policy_version >= 1),

    CONSTRAINT customer_risk_profile_version_reasons_shaped CHECK (
        reason_codes ~ '^([A-Z][A-Z0-9_]{0,63}(,[A-Z][A-Z0-9_]{0,63})*)?$' AND length(reason_codes) <= 4096),

    CONSTRAINT customer_risk_profile_version_inputs_bounded
        CHECK (inputs_canonical IS NULL OR octet_length(inputs_canonical) BETWEEN 2 AND 262144),

    -- The digest is the database's too: whatever a writer says, it is the inputs' own.
    CONSTRAINT customer_risk_profile_version_inputs_digest_holds CHECK (inputs_canonical IS NULL
        OR inputs_sha256 = encode(sha256(convert_to(inputs_canonical, 'UTF8')), 'hex')),

    CONSTRAINT customer_risk_profile_version_correlation_bounded CHECK (length(correlation_id) BETWEEN 1 AND 128)
);

COMMENT ON TABLE risk.customer_risk_profile_version IS
    'One customer risk rating per change, per party (P13-TSK-007; INV-RSK-10): born once in sequence, INSERT-only for '
    'every writer, PRIMARY KEY (party_id, version). A RECOMPUTE version carries the MONITORING policy version, the '
    'engine, the signal score, the reasons and the sealed inputs that set it; a DISPOSITION version the approved '
    'disposition (P13-TSK-016). PROHIBITED only by a disposition, and never followed by a recompute''s version.';

-- ---------------------------------------------------------------------------------------------------------------------
-- The triggers: the database's clock on every stamp; the head moving only with its versions; the versions in sequence,
-- PROHIBITED sticky against the sweep, and never edited; nothing ever removed.
-- ---------------------------------------------------------------------------------------------------------------------

CREATE FUNCTION risk.customer_risk_profile_moves_with_its_versions() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        -- A head is born unassessed; its first rating arrives as version 1, beside its row.
        IF NEW.current_version <> 0 OR NEW.rating IS NOT NULL THEN
            RAISE EXCEPTION 'a customer risk profile is born unassessed: version 0, no rating (INV-RSK-10)'
                USING ERRCODE = 'check_violation';
        END IF;
        NEW.created_at := clock_timestamp();
        RETURN NEW;
    END IF;
    IF NEW.party_id IS DISTINCT FROM OLD.party_id THEN
        RAISE EXCEPTION 'a customer risk profile is never moved to another party' USING ERRCODE = 'check_violation';
    END IF;
    NEW.created_at := OLD.created_at;
    IF NEW.current_version IS DISTINCT FROM OLD.current_version OR NEW.rating IS DISTINCT FROM OLD.rating THEN
        -- The head says what its newest version says: one step, beside a version row of the same rating.
        IF NEW.current_version <> OLD.current_version + 1 OR NOT EXISTS (
                SELECT 1 FROM risk.customer_risk_profile_version v
                 WHERE v.party_id = NEW.party_id AND v.version = NEW.current_version AND v.rating = NEW.rating) THEN
            RAISE EXCEPTION 'a customer risk profile moves one version at a time, beside that version (INV-RSK-10)'
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER customer_risk_profile_moves_with_its_versions
    BEFORE INSERT OR UPDATE ON risk.customer_risk_profile
    FOR EACH ROW
    EXECUTE FUNCTION risk.customer_risk_profile_moves_with_its_versions();

CREATE FUNCTION risk.customer_risk_profile_is_never_removed() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    -- For EVERY writer, the migrator included: a profile and its history are the explanation of every decision that
    -- read them (INV-RSK-10, INV-HIST-04).
    RAISE EXCEPTION 'a customer risk profile and its versions are never deleted or truncated (P13-TSK-007, INV-RSK-10)';
END;
$$;

CREATE TRIGGER customer_risk_profile_is_never_deleted
    BEFORE DELETE ON risk.customer_risk_profile
    FOR EACH ROW
    EXECUTE FUNCTION risk.customer_risk_profile_is_never_removed();

CREATE TRIGGER customer_risk_profile_is_never_truncated
    BEFORE TRUNCATE ON risk.customer_risk_profile
    FOR EACH STATEMENT
    EXECUTE FUNCTION risk.customer_risk_profile_is_never_removed();

CREATE FUNCTION risk.customer_risk_profile_version_is_born_in_sequence() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    newest integer;
    newest_rating text;
BEGIN
    SELECT v.version, v.rating INTO newest, newest_rating
      FROM risk.customer_risk_profile_version v
     WHERE v.party_id = NEW.party_id
     ORDER BY v.version DESC
     LIMIT 1;
    IF NEW.version <> COALESCE(newest, 0) + 1 THEN
        RAISE EXCEPTION 'a customer risk profile version is numbered without a gap (INV-RSK-10)'
            USING ERRCODE = 'check_violation';
    END IF;
    -- Only a disposition lifts PROHIBITED: the sweep never re-rates a prohibited party (INV-RSK-07).
    IF newest_rating = 'PROHIBITED' AND NEW.cause <> 'DISPOSITION' THEN
        RAISE EXCEPTION 'a recompute never follows a PROHIBITED version: only a disposition lifts it (INV-RSK-07)'
            USING ERRCODE = 'check_violation';
    END IF;
    NEW.computed_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER customer_risk_profile_version_is_born_in_sequence
    BEFORE INSERT ON risk.customer_risk_profile_version
    FOR EACH ROW
    EXECUTE FUNCTION risk.customer_risk_profile_version_is_born_in_sequence();

CREATE FUNCTION risk.customer_risk_profile_version_is_append_only() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    -- For EVERY writer: a rating once given is history; a correction is a new version (INV-RSK-10).
    RAISE EXCEPTION 'a customer risk profile version is never updated or deleted: a change is a new version (INV-RSK-10)';
END;
$$;

CREATE TRIGGER customer_risk_profile_version_is_append_only
    BEFORE UPDATE OR DELETE ON risk.customer_risk_profile_version
    FOR EACH ROW
    EXECUTE FUNCTION risk.customer_risk_profile_version_is_append_only();

CREATE TRIGGER customer_risk_profile_version_is_never_truncated
    BEFORE TRUNCATE ON risk.customer_risk_profile_version
    FOR EACH STATEMENT
    EXECUTE FUNCTION risk.customer_risk_profile_is_never_removed();

-- ---------------------------------------------------------------------------------------------------------------------
-- The grants: the head born and moved through its four mutable columns; the versions SELECT and INSERT; no DELETE.
-- ---------------------------------------------------------------------------------------------------------------------

GRANT SELECT, INSERT ON risk.customer_risk_profile TO finapp_app;
GRANT UPDATE (current_version, rating, next_recompute_at, recomputed_at) ON risk.customer_risk_profile TO finapp_app;
GRANT SELECT, INSERT ON risk.customer_risk_profile_version TO finapp_app;
