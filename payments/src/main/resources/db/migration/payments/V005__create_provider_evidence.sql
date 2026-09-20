-- Verbatim provider evidence: encrypted, checksummed, append-only for every writer
-- (P5-TSK-008, INV-HIST-02, ADR-0049).
--
-- EVERYTHING THE PROVIDER SENT AND EVERYTHING WE SENT IT, byte for byte: requests, responses,
-- query results and webhooks - malformed and 5xx included, because the unparseable answer is
-- precisely what an investigation of the provider wants (ProviderAnswer's recorded rule).
-- Whatever the total mapping said, the bytes land here; provider vocabulary appears in
-- exactly one place - this table and the adapter that parsed it (INV-PAY-03).
--
-- THE CONTENT IS CIPHERTEXT, NEVER PLAINTEXT (the kyc_document V003 shape, mandated for this
-- table by PHASE_5_PLAN.md section 8): provider payloads may quote masked instrument data, so
-- AES-256-GCM under a key held outside the database, nonce and key version per row. The
-- cipher component and its KeySpec arrive with the first writer (P5-TSK-009, beside its
-- consumer - the ProviderApiKey precedent); what this migration commits is the SHAPE: NOT
-- NULL nonce, key version and tag arithmetic make an unencrypted write a lie a writer has to
-- tell explicitly, and the checksum is of the PLAINTEXT bytes received, verified on read.
--
-- APPEND-ONLY FOR EVERY WRITER, THE MIGRATOR INCLUDED. The application role gets SELECT and
-- INSERT and nothing else (the audit_record model), and the raise-always trigger below binds
-- raw SQL and the migrator too - evidence that can be edited is not evidence (INV-HIST-02).
-- Deletion is Phase 15's recorded retention/erasure tension, not a grant.
--
-- AT MOST ONE SUBJECT, AND POSSIBLY NONE: evidence about an attempt operation names the
-- attempt, evidence about a refund operation names the refund - and a webhook the platform
-- cannot attribute to either is STILL RETAINED, both references NULL, because "we could not
-- attribute it" is itself the fact an investigation starts from (INV-HIST-02's whole point).
--
-- EVERY GENERATED VALUE HAS ONE DEFINITION: the size bound is PspWireClient.
-- MAX_EVIDENCE_BYTES - the retention bound P5-TSK-003 stated, arriving at exactly the
-- reconciliation it named - and PaymentsMigrationTest fails the build on drift. The kind list
-- is PAYMENT_LIFECYCLES.md section 6's own enumeration; its domain enum arrives with the
-- first writer.

CREATE TABLE payments.provider_evidence (
    -- UUIDv7, minted by the application (ADR-0013).
    id                 uuid        PRIMARY KEY,

    -- At most one subject; possibly none (the unattributable webhook). Same-schema FKs.
    attempt_id         uuid        REFERENCES payments.payment_attempt (id),
    refund_id          uuid        REFERENCES payments.refund (id),
    CONSTRAINT provider_evidence_has_at_most_one_subject
        CHECK (attempt_id IS NULL OR refund_id IS NULL),

    -- What kind of bytes these are (PAYMENT_LIFECYCLES.md section 6's enumeration).
    kind               text        NOT NULL
        CONSTRAINT provider_evidence_kind_is_known
            CHECK (kind IN ('REQUEST', 'RESPONSE', 'QUERY_RESULT', 'WEBHOOK')),

    content_ciphertext bytea       NOT NULL,
    content_nonce      bytea       NOT NULL,
    key_version        integer     NOT NULL,

    -- SHA-256 of the PLAINTEXT bytes as they arrived (INV-HIST-02), verified on read.
    checksum_sha256    bytea       NOT NULL,
    content_length     integer     NOT NULL,

    -- Application-supplied from the injected Clock, never DEFAULT now().
    recorded_at        timestamptz NOT NULL,

    -- 96 bits, the size GCM is specified for (the kyc_document rule).
    CONSTRAINT provider_evidence_nonce_is_gcm_sized
        CHECK (octet_length(content_nonce) = 12),

    CONSTRAINT provider_evidence_key_version_is_positive
        CHECK (key_version > 0),

    CONSTRAINT provider_evidence_checksum_is_sha256
        CHECK (octet_length(checksum_sha256) = 32),

    -- PspWireClient.MAX_EVIDENCE_BYTES: the wire's own retention bound, enforced where a
    -- writer that never passed through the client would otherwise ignore it. Non-empty,
    -- because the client never yields empty evidence (an empty body is still a received
    -- answer with headers - the stored bytes are the raw body, and a truly empty one is
    -- absence, not evidence).
    CONSTRAINT provider_evidence_content_length_is_bounded
        CHECK (content_length BETWEEN 1 AND 1048576),

    -- GCM's arithmetic: ciphertext = plaintext + 16-byte tag (the kyc_document rule). A
    -- plaintext passed off as ciphertext of the declared length cannot satisfy it.
    CONSTRAINT provider_evidence_ciphertext_carries_the_tag
        CHECK (octet_length(content_ciphertext) = content_length + 16)
);

-- The investigation's reads, partial because most rows name exactly one subject.
CREATE INDEX provider_evidence_by_attempt
    ON payments.provider_evidence (attempt_id)
    WHERE attempt_id IS NOT NULL;
CREATE INDEX provider_evidence_by_refund
    ON payments.provider_evidence (refund_id)
    WHERE refund_id IS NOT NULL;

-- Append-only for EVERY writer, the migrator included (the ledger journal's regime): the
-- grants below already deny the application role, and this binds raw SQL and the migrator
-- too. Evidence that can be edited is not evidence.
CREATE OR REPLACE FUNCTION payments.provider_evidence_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'provider evidence is append-only for every writer: retained bytes are never edited and never deleted (INV-HIST-02)';
END;
$$;

CREATE TRIGGER provider_evidence_is_append_only
    BEFORE UPDATE OR DELETE ON payments.provider_evidence
    FOR EACH ROW
    EXECUTE FUNCTION payments.provider_evidence_is_append_only();

COMMENT ON TABLE payments.provider_evidence IS
    'Verbatim provider payloads (INV-HIST-02): every request, response, query result and webhook, byte for byte, AES-256-GCM ciphertext under a key held outside the database with the plaintext''s SHA-256 recorded at capture. Append-only for every writer; at most one subject, possibly none (an unattributable webhook is still retained).';
COMMENT ON COLUMN payments.provider_evidence.content_ciphertext IS
    'AES-256-GCM ciphertext of the raw payload bytes; provider payloads may quote masked instrument data, so never plaintext at rest (PHASE_5_PLAN.md section 8). The cipher arrives with the first writer (P5-TSK-009).';

-- SELECT and INSERT and nothing else (the audit_record model); the trigger above binds the
-- writers the grants cannot.
GRANT SELECT, INSERT ON payments.provider_evidence TO finapp_app;
