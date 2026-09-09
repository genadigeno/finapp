-- Captured documents: encrypted, checksummed, append-only evidence (P2-TSK-008, ADR-0036).
--
-- APPEND-ONLY AT THE PRIVILEGE LEVEL
--   Evidence is never edited (INV-HIST-02) and document content is the most sensitive PII the
--   platform holds before card data (INV-KYC-06). The application role gets SELECT and INSERT
--   and nothing else - the audit-table mechanism from P0-TSK-022. A document is replaced by
--   uploading another document, never by updating one; deletion is Phase 15's recorded
--   retention/erasure tension (ADR-0036), not a grant.
--
-- THE CONTENT IS CIPHERTEXT, NEVER PLAINTEXT
--   AES-256-GCM under a key held outside the database (FINAPP_DOC_KEY), so a database leak
--   alone yields no documents. The nonce and key version are stored per row: the nonce because
--   GCM requires it fresh per encryption, the version so a rotation can tell which key wrote
--   which row (INV-HIST-04's rule applied to a key).
--
-- EVERY GENERATED LIST BELOW HAS ONE DEFINITION
--   The type CHECKs are DocumentType.sqlValueList() and DocumentContentType.sqlValueList(), and
--   the size bound is DocumentBytes.MAX_BYTES; KycDocumentMigrationTest fails the build if this
--   file and the code disagree (the P0-TSK-022 pattern).

CREATE TABLE kyc.kyc_document (
    -- UUIDv7, minted by the application (ADR-0013).
    id                 uuid        PRIMARY KEY,

    -- FK WITHIN this schema, which is permitted and correct (the party.customer precedent):
    -- kyc_case is this module's own table, and an orphaned evidence row is exactly what the
    -- upload transaction must make impossible.
    case_id            uuid        NOT NULL REFERENCES kyc.kyc_case (id),

    document_type      text        NOT NULL,
    content_type       text        NOT NULL,

    content_ciphertext bytea       NOT NULL,
    content_nonce      bytea       NOT NULL,
    key_version        integer     NOT NULL,

    -- SHA-256 of the PLAINTEXT bytes received at capture (INV-HIST-02: a checksum where the
    -- source is a file), verified on every read. Also the identity a retry converges on.
    checksum_sha256    bytea       NOT NULL,
    content_length     integer     NOT NULL,

    -- Application-supplied from one injected Clock, never DEFAULT now() (DOMAIN_MODEL.md
    -- section Time).
    uploaded_at        timestamptz NOT NULL,

    CONSTRAINT kyc_document_type_is_known
        CHECK (document_type IN ('PASSPORT', 'ID_CARD_FRONT', 'ID_CARD_BACK', 'DRIVING_LICENCE', 'PROOF_OF_ADDRESS')),

    CONSTRAINT kyc_document_content_type_is_known
        CHECK (content_type IN ('JPEG', 'PNG', 'PDF')),

    -- 96 bits, the size GCM is specified for.
    CONSTRAINT kyc_document_nonce_is_gcm_sized
        CHECK (octet_length(content_nonce) = 12),

    CONSTRAINT kyc_document_key_version_is_positive
        CHECK (key_version > 0),

    CONSTRAINT kyc_document_checksum_is_sha256
        CHECK (octet_length(checksum_sha256) = 32),

    -- DocumentBytes.MAX_BYTES: the boundary's bound, enforced where a writer that never passed
    -- through the boundary would otherwise ignore it.
    CONSTRAINT kyc_document_content_length_is_bounded
        CHECK (content_length BETWEEN 1 AND 524288),

    -- GCM's arithmetic: ciphertext = plaintext + 16-byte tag. A plaintext passed off as a
    -- ciphertext of the declared length cannot satisfy it - the INV-IDN-08 "nonce and
    -- ciphertext sized for AES-GCM" idea, made exact.
    CONSTRAINT kyc_document_ciphertext_carries_the_tag
        CHECK (octet_length(content_ciphertext) = content_length + 16)
);

-- CONTENT-ADDRESSED CONVERGENCE: the idempotency mechanism (no Idempotency-Key header - this
-- command moves no money, and content addressing is stronger). A retry after a lost response, a
-- double-tap and a deliberate re-upload of the same file all land on one row; the store's
-- appendOrConverge hands the loser the existing document rather than an error. The leading
-- column also serves "the documents of this case", so no second index.
CREATE UNIQUE INDEX kyc_document_one_per_case_and_checksum
    ON kyc.kyc_document (case_id, checksum_sha256);

COMMENT ON TABLE kyc.kyc_document IS
    'Captured document evidence: AES-256-GCM ciphertext under a key held outside the database, '
    'with the SHA-256 of the bytes received recorded at capture and verified on every read. '
    'Append-only at the privilege level (INV-HIST-02); every content read is audited '
    '(INV-KYC-06). Reached only through the DocumentStore port, which is the object-storage '
    'seam (ADR-0036).';

-- SELECT and INSERT and nothing else: evidence is never edited and never deleted by the
-- application (INV-HIST-02, INV-KYC-06). Deletion is Phase 15's retention question.
GRANT SELECT, INSERT ON kyc.kyc_document TO finapp_app;
