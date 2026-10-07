-- The decision input snapshot (P10-TSK-008; ADR-0087 section 2, INV-CRD-07, INV-CRD-08, INV-CRD-06).
--
-- Everything a decision may read, frozen once per evaluation: the canonical form (format 1, CanonicalSnapshot) -
-- the request, the pinned versions and every attribute with its provenance, sorted by code - and the SHA-256 of
-- those bytes. A replay reads exactly this text.
--
-- BORN ONCE PER (decision request, sequence): sequence 1 at the freeze, a successor only when the deciding
-- transaction finds the reserved exposure changed (P10-TSK-016). Ten freezers on one request leave one snapshot
-- (INSERT ... ON CONFLICT DO NOTHING, then read). Never updated, deleted or truncated by any role.
--
-- THE HASH IS THE DATABASE'S TOO: a CHECK recomputes sha256 over the canonical text's UTF-8 bytes, so a writer that
-- stored a body and a hash that disagree - or edited one without the other - is refused, whoever it is.
--
-- The pinned policy and model versions are references whose foreign keys arrive with their tables (P10-TSK-011,
-- -012); the decision request's arrives with P10-TSK-014 (G10). frozen_at is stamped by the database.

CREATE TABLE credit.decision_snapshot (
    id                   uuid        PRIMARY KEY,
    decision_request_id  uuid        NOT NULL,
    sequence             integer     NOT NULL,
    snapshot_format      integer     NOT NULL,
    canonical            text        NOT NULL,
    content_sha256       bytea       NOT NULL,
    policy_version_id    uuid        NOT NULL,
    model_version_id     uuid        NOT NULL,
    engine_version       integer     NOT NULL,
    frozen_at            timestamptz NOT NULL,
    CONSTRAINT decision_snapshot_once_per_sequence UNIQUE (decision_request_id, sequence),
    CONSTRAINT decision_snapshot_sequence_is_positive CHECK (sequence >= 1),
    CONSTRAINT decision_snapshot_format_is_known CHECK (snapshot_format = 1),
    CONSTRAINT decision_snapshot_engine_is_positive CHECK (engine_version >= 1),
    CONSTRAINT decision_snapshot_hash_is_the_contents
        CHECK (content_sha256 = sha256(convert_to(canonical, 'UTF8'))),
    CONSTRAINT decision_snapshot_canonical_is_bounded CHECK (octet_length(canonical) BETWEEN 2 AND 262144)
);

COMMENT ON TABLE credit.decision_snapshot IS
    'A frozen decision input (P10-TSK-008, INV-CRD-07): the canonical format-1 text and its SHA-256 (recomputed by a CHECK), the pinned policy, model and engine versions; born once per (decision request, sequence), never updated, deleted or truncated by any role. RESTRICTED-FINANCIAL.';

CREATE OR REPLACE FUNCTION credit.decision_snapshot_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a decision snapshot is never updated, deleted or truncated (P10-TSK-008, INV-CRD-07)';
    END IF;
    NEW.frozen_at := statement_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER decision_snapshot_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON credit.decision_snapshot
    FOR EACH ROW
    EXECUTE FUNCTION credit.decision_snapshot_is_born_once();

CREATE TRIGGER decision_snapshot_is_never_truncated
    BEFORE TRUNCATE ON credit.decision_snapshot
    FOR EACH STATEMENT
    EXECUTE FUNCTION credit.decision_snapshot_is_born_once();

GRANT SELECT, INSERT ON credit.decision_snapshot TO finapp_app;
