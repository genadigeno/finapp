-- Credit data collection (P10-TSK-006; ADR-0085, INV-CRD-03, INV-CRD-07, INV-CRD-10, INV-LIFE-03, INV-HIST-02).
--
-- A data request asks one source kind for a party's credit data, under OUR reference - the provider's
-- idempotency key - and is retried while unavailable until a deadline stamped at its birth. Its answer is
-- recorded once: the normalised attributes in credit_record and credit_record_attribute, born once per data
-- request and never changed; the bytes the provider sent in credit_evidence, AES-256-GCM under credit's own
-- key with the evidence id as associated data, unreadable by the application role except through the
-- reasoned definer function at the end of this file.
--
-- THE MACHINE (the lifecycle document's data request):
--   REQUESTED   -> REQUESTED (a permit renewal or a claim) | RECEIVED | UNAVAILABLE | CONSENT_WITHDRAWN
--   UNAVAILABLE -> REQUESTED (a retry claim, only while statement_timestamp() < deadline_at)
--                | UNAVAILABLE (re-stamped, reported) | CONSENT_WITHDRAWN (G11)
--   RECEIVED and CONSENT_WITHDRAWN are terminal.
--
-- EVERY WINDOW IS THE DATABASE'S: requested_at, deadline_at and the first permit are stamped by the trigger
-- from statement_timestamp(); the retry cadence and the collection window arrive with the insert (the source
-- kind's configuration) and are frozen; every later permit is statement_timestamp() + retry_cadence, binding
-- nothing (SendPermitsAreTheDatabasesTest). No instance's clock decides when a request is retried or given up.

CREATE TABLE credit.data_request (
    id                    uuid        PRIMARY KEY,
    -- The decision request it serves; the foreign key arrives with that table (P10-TSK-014, G10).
    decision_request_id   uuid        NOT NULL,
    party_id              uuid        NOT NULL,
    product               text        NOT NULL,
    source_kind           text        NOT NULL,
    provider_code         text        NOT NULL,
    request_reference     text        NOT NULL,
    status                text        NOT NULL,
    attempts              integer     NOT NULL,
    retry_cadence         interval    NOT NULL,
    collection_window     interval    NOT NULL,
    next_attempt_at       timestamptz NOT NULL,
    requested_at          timestamptz NOT NULL,
    deadline_at           timestamptz NOT NULL,
    unavailable_reported  boolean     NOT NULL,

    -- The reference is the request's identity at the provider - unique, minted before any call. A decision
    -- request may hold several data requests for one source kind: a record found stale at the freeze opens a
    -- NEW one under a new reference (the lifecycle document, READY -> COLLECTING); the decision request's own
    -- row lock (lock-order element (2)) is what keeps two openers from racing.
    CONSTRAINT data_request_reference_unique UNIQUE (request_reference),
    CONSTRAINT data_request_product_is_known CHECK (product IN ('PERSONAL_LOAN', 'CREDIT_LINE')),
    CONSTRAINT data_request_source_kind_is_known CHECK (source_kind IN ('BUREAU', 'FINANCIAL_DATA')),
    CONSTRAINT data_request_provider_code_shape CHECK (provider_code ~ '^[a-z][a-z0-9-]{0,31}$'),
    CONSTRAINT data_request_reference_shape CHECK (request_reference ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    CONSTRAINT data_request_status_is_known
        CHECK (status IN ('REQUESTED', 'RECEIVED', 'UNAVAILABLE', 'CONSENT_WITHDRAWN')),
    CONSTRAINT data_request_attempts_are_counted CHECK (attempts >= 0),
    CONSTRAINT data_request_cadence_is_positive CHECK (retry_cadence > interval '0'),
    CONSTRAINT data_request_window_is_positive CHECK (collection_window > interval '0'),
    CONSTRAINT data_request_deadline_after_birth CHECK (deadline_at > requested_at),
    -- Only an unavailable request past its deadline is ever reported.
    CONSTRAINT data_request_reported_only_when_unavailable
        CHECK (NOT unavailable_reported OR status IN ('UNAVAILABLE', 'CONSENT_WITHDRAWN'))
);

CREATE INDEX data_request_by_decision ON credit.data_request (decision_request_id, source_kind);
CREATE INDEX data_request_due ON credit.data_request (next_attempt_at, id)
    WHERE status IN ('REQUESTED', 'UNAVAILABLE');
CREATE INDEX data_request_unreported ON credit.data_request (deadline_at, id)
    WHERE status = 'UNAVAILABLE' AND NOT unavailable_reported;

COMMENT ON TABLE credit.data_request IS
    'One source kind asked for a party''s credit data under the platform''s own reference (P10-TSK-006, ADR-0085): REQUESTED -> RECEIVED | UNAVAILABLE | CONSENT_WITHDRAWN; UNAVAILABLE -> REQUESTED only before the deadline. Every window on the database clock; identity, reference, cadence, window and deadline frozen.';

CREATE OR REPLACE FUNCTION credit.data_request_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'REQUESTED' OR NEW.attempts <> 0 OR NEW.unavailable_reported THEN
            RAISE EXCEPTION 'a data request is born REQUESTED, unasked and unreported (P10-TSK-006)';
        END IF;
        NEW.requested_at := statement_timestamp();
        NEW.deadline_at := NEW.requested_at + NEW.collection_window;
        NEW.next_attempt_at := NEW.requested_at + NEW.retry_cadence;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a data request is never deleted (INV-HIST-01)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.decision_request_id IS DISTINCT FROM OLD.decision_request_id
            OR NEW.party_id IS DISTINCT FROM OLD.party_id
            OR NEW.product IS DISTINCT FROM OLD.product
            OR NEW.source_kind IS DISTINCT FROM OLD.source_kind
            OR NEW.provider_code IS DISTINCT FROM OLD.provider_code
            OR NEW.request_reference IS DISTINCT FROM OLD.request_reference
            OR NEW.retry_cadence IS DISTINCT FROM OLD.retry_cadence
            OR NEW.collection_window IS DISTINCT FROM OLD.collection_window
            OR NEW.requested_at IS DISTINCT FROM OLD.requested_at
            OR NEW.deadline_at IS DISTINCT FROM OLD.deadline_at THEN
        RAISE EXCEPTION 'a data request''s identity, reference, cadence, window and deadline are frozen';
    END IF;
    IF NEW.attempts < OLD.attempts THEN
        RAISE EXCEPTION 'a data request''s attempts only grow';
    END IF;
    IF OLD.unavailable_reported AND NOT NEW.unavailable_reported THEN
        RAISE EXCEPTION 'a reported data request stays reported';
    END IF;
    IF NOT ((OLD.status = 'REQUESTED' AND NEW.status IN ('REQUESTED', 'RECEIVED', 'UNAVAILABLE', 'CONSENT_WITHDRAWN'))
            OR (OLD.status = 'UNAVAILABLE' AND NEW.status IN ('UNAVAILABLE', 'CONSENT_WITHDRAWN'))
            OR (OLD.status = 'UNAVAILABLE' AND NEW.status = 'REQUESTED' AND statement_timestamp() < OLD.deadline_at)) THEN
        RAISE EXCEPTION 'a data request cannot move from % to %', OLD.status, NEW.status;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER data_request_permits_only_machine_edges
    BEFORE INSERT OR UPDATE OR DELETE ON credit.data_request
    FOR EACH ROW
    EXECUTE FUNCTION credit.data_request_permits_only_machine_edges();

-- ---------------------------------------------------------------------------------------------
-- Every attempt, append-only: what the provider answered, or that consent was found withdrawn. The
-- primary key arbitrates two writers of one attempt number.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE credit.data_request_attempt (
    data_request_id  uuid        NOT NULL REFERENCES credit.data_request (id),
    attempt          integer     NOT NULL,
    outcome          text        NOT NULL,
    answered_at      timestamptz NOT NULL,
    PRIMARY KEY (data_request_id, attempt),
    CONSTRAINT data_request_attempt_is_positive CHECK (attempt > 0),
    CONSTRAINT data_request_attempt_outcome_is_known CHECK (outcome IN (
        'RECEIVED', 'PARTIAL', 'TIMEOUT', 'MALFORMED', 'UNKNOWN_STATUS', 'PROVIDER_ERROR', 'CONSENT_WITHDRAWN'))
);

COMMENT ON TABLE credit.data_request_attempt IS
    'Every attempt of a credit data request (P10-TSK-006): the provider''s answer kind, or consent found withdrawn. Append-only; answered_at the database''s.';

-- ---------------------------------------------------------------------------------------------
-- The record: born once per data request (UNIQUE (data_request_id)), never changed. Its attributes are
-- rows with typed columns - never a blob a CHECK cannot read - each carrying the provider and normaliser
-- version through its record (INV-CRD-07).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE credit.credit_record (
    id                  uuid        PRIMARY KEY,
    data_request_id     uuid        NOT NULL REFERENCES credit.data_request (id),
    party_id            uuid        NOT NULL,
    source_kind         text        NOT NULL,
    provider_code       text        NOT NULL,
    normaliser_version  integer     NOT NULL,
    complete            boolean     NOT NULL,
    retrieved_at        timestamptz NOT NULL,
    recorded_at         timestamptz NOT NULL,
    CONSTRAINT credit_record_once_per_request UNIQUE (data_request_id),
    CONSTRAINT credit_record_source_kind_is_known CHECK (source_kind IN ('BUREAU', 'FINANCIAL_DATA')),
    CONSTRAINT credit_record_provider_code_shape CHECK (provider_code ~ '^[a-z][a-z0-9-]{0,31}$'),
    CONSTRAINT credit_record_normaliser_version_is_positive CHECK (normaliser_version > 0)
);

COMMENT ON TABLE credit.credit_record IS
    'A data request''s answer, born once (P10-TSK-006, INV-CRD-07): the provider, its normaliser version and its retrieved-at; the attributes in credit_record_attribute. Never updated or deleted by any role; replay reads these rows and never re-normalises (INV-CRD-01).';

CREATE TABLE credit.credit_record_attribute (
    record_id      uuid    NOT NULL REFERENCES credit.credit_record (id),
    code           text    NOT NULL,
    value_type     text    NOT NULL,
    integer_value  bigint,
    money_minor    bigint,
    money_currency text,
    money_scale    integer,
    boolean_value  boolean,
    code_value     text,
    absent         boolean NOT NULL,
    PRIMARY KEY (record_id, code),
    CONSTRAINT credit_record_attribute_code_shape CHECK (code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT credit_record_attribute_type_is_known CHECK (value_type IN ('INTEGER', 'MONEY', 'BOOLEAN', 'CODE')),
    -- Exactly the typed column of its type holds a value - or none, when absent.
    CONSTRAINT credit_record_attribute_one_value CHECK (
        (absent AND integer_value IS NULL AND money_minor IS NULL AND money_currency IS NULL AND money_scale IS NULL
            AND boolean_value IS NULL AND code_value IS NULL)
        OR (NOT absent AND value_type = 'INTEGER' AND integer_value IS NOT NULL AND money_minor IS NULL
            AND boolean_value IS NULL AND code_value IS NULL)
        OR (NOT absent AND value_type = 'MONEY' AND money_minor IS NOT NULL AND money_currency IS NOT NULL
            AND money_scale IS NOT NULL AND integer_value IS NULL AND boolean_value IS NULL AND code_value IS NULL)
        OR (NOT absent AND value_type = 'BOOLEAN' AND boolean_value IS NOT NULL AND integer_value IS NULL
            AND money_minor IS NULL AND code_value IS NULL)
        OR (NOT absent AND value_type = 'CODE' AND code_value IS NOT NULL AND integer_value IS NULL
            AND money_minor IS NULL AND boolean_value IS NULL)),
    CONSTRAINT credit_record_attribute_currency_shape CHECK (money_currency IS NULL OR money_currency ~ '^[A-Z]{3}$'),
    CONSTRAINT credit_record_attribute_scale_is_bounded CHECK (money_scale IS NULL OR money_scale BETWEEN 0 AND 9)
);

COMMENT ON TABLE credit.credit_record_attribute IS
    'One normalised attribute of a credit record (P10-TSK-006): a code, its type and exactly one typed value - or absent, a value the policy reasons about. Never updated or deleted by any role.';

CREATE OR REPLACE FUNCTION credit.credit_record_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a credit record is never updated or deleted (P10-TSK-006, INV-CRD-07)';
    END IF;
    IF TG_TABLE_NAME = 'credit_record' THEN
        NEW.recorded_at := statement_timestamp();
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER credit_record_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON credit.credit_record
    FOR EACH ROW
    EXECUTE FUNCTION credit.credit_record_is_born_once();

CREATE TRIGGER credit_record_attribute_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON credit.credit_record_attribute
    FOR EACH ROW
    EXECUTE FUNCTION credit.credit_record_is_born_once();

-- ---------------------------------------------------------------------------------------------
-- The evidence: the bytes each attempt delivered, verbatim, AES-256-GCM under credit's own key
-- (finapp.credit.evidence.key, ADR-0066's envelope) with the evidence id's sixteen bytes as associated
-- data, so a ciphertext moved onto another row refuses to decrypt. A duplicate answer is kept and flagged;
-- an answer arriving after consent was withdrawn keeps only the fact that it arrived - no payload.
-- retain_until is the database's: recorded_at plus the product's evidence retention (the purge is a debt
-- owned by Phase 15).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE credit.credit_evidence (
    id                 uuid        PRIMARY KEY,
    data_request_id    uuid        NOT NULL REFERENCES credit.data_request (id),
    attempt            integer     NOT NULL,
    duplicate          boolean     NOT NULL,
    consent_withdrawn  boolean     NOT NULL,
    content_ciphertext bytea,
    content_nonce      bytea,
    key_version        integer,
    checksum_sha256    bytea,
    content_length     integer,
    retention_months   integer     NOT NULL,
    retain_until       timestamptz NOT NULL,
    recorded_at        timestamptz NOT NULL,
    CONSTRAINT credit_evidence_attempt_is_positive CHECK (attempt > 0),
    -- A withdrawn answer keeps nothing of its content; every other row keeps all of it.
    CONSTRAINT credit_evidence_payload_whole CHECK (
        (consent_withdrawn AND content_ciphertext IS NULL AND content_nonce IS NULL AND key_version IS NULL
            AND checksum_sha256 IS NULL AND content_length IS NULL)
        OR (NOT consent_withdrawn AND content_ciphertext IS NOT NULL AND content_nonce IS NOT NULL
            AND key_version IS NOT NULL AND checksum_sha256 IS NOT NULL AND content_length IS NOT NULL)),
    CONSTRAINT credit_evidence_nonce_is_gcm_sized CHECK (content_nonce IS NULL OR octet_length(content_nonce) = 12),
    CONSTRAINT credit_evidence_key_version_is_positive CHECK (key_version IS NULL OR key_version > 0),
    CONSTRAINT credit_evidence_checksum_is_sha256 CHECK (checksum_sha256 IS NULL OR octet_length(checksum_sha256) = 32),
    CONSTRAINT credit_evidence_length_is_bounded CHECK (content_length IS NULL OR content_length BETWEEN 1 AND 65536),
    CONSTRAINT credit_evidence_ciphertext_carries_the_tag
        CHECK (content_ciphertext IS NULL OR octet_length(content_ciphertext) = content_length + 16),
    CONSTRAINT credit_evidence_retention_is_positive CHECK (retention_months > 0)
);

CREATE INDEX credit_evidence_by_request ON credit.credit_evidence (data_request_id, attempt);

COMMENT ON TABLE credit.credit_evidence IS
    'The bytes each credit data attempt delivered (P10-TSK-006, INV-HIST-02): AES-256-GCM under credit''s own key, the evidence id as associated data; duplicate and consent-withdrawn arrivals flagged (the latter with no payload). INSERT only to the application, never SELECT - read through credit.read_evidence with a reason. Never updated or deleted by any role.';

CREATE OR REPLACE FUNCTION credit.credit_evidence_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'credit evidence is never updated or deleted (P10-TSK-006, INV-HIST-02)';
    END IF;
    NEW.recorded_at := statement_timestamp();
    NEW.retain_until := NEW.recorded_at + make_interval(months => NEW.retention_months);
    RETURN NEW;
END;
$$;

CREATE TRIGGER credit_evidence_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON credit.credit_evidence
    FOR EACH ROW
    EXECUTE FUNCTION credit.credit_evidence_is_born_once();

-- TRUNCATE fires no row trigger; the statement trigger refuses it on every born-once table.
CREATE OR REPLACE FUNCTION credit.collection_is_never_truncated()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'credit data collection history is never truncated (P10-TSK-006)';
END;
$$;

CREATE TRIGGER data_request_is_never_truncated BEFORE TRUNCATE ON credit.data_request
    FOR EACH STATEMENT EXECUTE FUNCTION credit.collection_is_never_truncated();
CREATE TRIGGER data_request_attempt_is_never_truncated BEFORE TRUNCATE ON credit.data_request_attempt
    FOR EACH STATEMENT EXECUTE FUNCTION credit.collection_is_never_truncated();
CREATE TRIGGER credit_record_is_never_truncated BEFORE TRUNCATE ON credit.credit_record
    FOR EACH STATEMENT EXECUTE FUNCTION credit.collection_is_never_truncated();
CREATE TRIGGER credit_record_attribute_is_never_truncated BEFORE TRUNCATE ON credit.credit_record_attribute
    FOR EACH STATEMENT EXECUTE FUNCTION credit.collection_is_never_truncated();
CREATE TRIGGER credit_evidence_is_never_truncated BEFORE TRUNCATE ON credit.credit_evidence
    FOR EACH STATEMENT EXECUTE FUNCTION credit.collection_is_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The evidence read: the ONE way the application reaches a ciphertext, and only with a reason - the door
-- that calls it (P10-TSK-017) audits the read and decrypts outside the database, where the key is.
-- SECURITY DEFINER runs as the migrator, which owns the table; the search_path is pinned so no caller's
-- schema can shadow a name the function resolves.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION credit.read_evidence(evidence_id uuid, reason text)
    RETURNS TABLE (content_ciphertext bytea, content_nonce bytea, key_version integer, checksum_sha256 bytea,
                   content_length integer, consent_withdrawn boolean)
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF reason IS NULL OR btrim(reason) = '' THEN
        RAISE EXCEPTION 'reading credit evidence requires a reason (P10-TSK-006)';
    END IF;
    RETURN QUERY
        SELECT e.content_ciphertext, e.content_nonce, e.key_version, e.checksum_sha256, e.content_length,
               e.consent_withdrawn
        FROM credit.credit_evidence e
        WHERE e.id = evidence_id;
END;
$$;

REVOKE ALL ON FUNCTION credit.read_evidence(uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION credit.read_evidence(uuid, text) TO finapp_app;

-- ---------------------------------------------------------------------------------------------
-- THE GRANTS. The data request moves along its machine through the columns that move; everything else
-- is born and read - and the evidence is born and NEVER read by the application role.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON credit.data_request TO finapp_app;
GRANT UPDATE (status, attempts, next_attempt_at, unavailable_reported) ON credit.data_request TO finapp_app;
GRANT SELECT, INSERT ON credit.data_request_attempt TO finapp_app;
GRANT SELECT, INSERT ON credit.credit_record TO finapp_app;
GRANT SELECT, INSERT ON credit.credit_record_attribute TO finapp_app;
GRANT INSERT ON credit.credit_evidence TO finapp_app;
