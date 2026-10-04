-- P9-TSK-006 (ADR-0075, ADR-0077; INV-HIST-02, INV-PAY-04): the FX provider's answers, verbatim.
--
-- Every request, response, inquiry result and callback exchanged with an FX provider, byte for
-- byte, as AES-256-GCM ciphertext under FINAPP_FX_EVIDENCE_KEY (held outside the database), with
-- the PLAINTEXT's SHA-256 recorded at capture and verified on read - the payments.provider_evidence
-- shape (payments V005), restated for fx, which may not see that schema.
--
-- NAMED fx_provider_evidence, not the plan's provider_evidence: DATA_CLASSIFICATION keys its rows on
-- table.column with no schema, and ColumnClassificationTest refuses two schemas defining one table
-- name - payments.provider_evidence already exists. The merchant.payout_evidence precedent: the
-- table carries its domain in its name.
--
-- KEYED BY OUR REFERENCE: the quote request's QR or the execution's T (INV-PAY-04), beside the
-- provider's code. The quote and cover rows that will name these references arrive with
-- P9-TSK-008 and -012; a later migration may add those subject columns, as payments V025 widened
-- its evidence.
--
-- The cipher's arithmetic is held HERE as well as in Java, so a writer that never passed through
-- JdbcFxProviderEvidenceStore cannot store plaintext dressed as ciphertext. Append-only for every
-- writer, migrator included.

CREATE TABLE fx.fx_provider_evidence (
    id                 UUID        NOT NULL,
    provider_code      TEXT        NOT NULL,
    client_reference   TEXT        NOT NULL,
    kind               TEXT        NOT NULL,
    content_ciphertext BYTEA       NOT NULL,
    content_nonce      BYTEA       NOT NULL,
    key_version        INTEGER     NOT NULL,
    checksum_sha256    BYTEA       NOT NULL,
    content_length     INTEGER     NOT NULL,
    recorded_at        TIMESTAMPTZ NOT NULL,
    CONSTRAINT fx_provider_evidence_pk PRIMARY KEY (id),
    CONSTRAINT fx_provider_evidence_provider_code_shape CHECK (provider_code ~ '^[a-z][a-z0-9-]{0,31}$'),
    CONSTRAINT fx_provider_evidence_reference_shape CHECK (client_reference ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    CONSTRAINT fx_provider_evidence_kind_is_known CHECK (kind IN ('REQUEST', 'RESPONSE', 'INQUIRY_RESULT', 'CALLBACK')),
    -- 96 bits, the size GCM is specified for.
    CONSTRAINT fx_provider_evidence_nonce_is_gcm_sized CHECK (octet_length(content_nonce) = 12),
    CONSTRAINT fx_provider_evidence_key_version_is_positive CHECK (key_version > 0),
    CONSTRAINT fx_provider_evidence_checksum_is_sha256 CHECK (octet_length(checksum_sha256) = 32),
    -- FxProviderEvidenceStore.MAX_PAYLOAD_BYTES; never empty - an empty body is absence, not evidence.
    CONSTRAINT fx_provider_evidence_content_length_is_bounded CHECK (content_length BETWEEN 1 AND 1048576),
    -- GCM: ciphertext = plaintext + the 16-byte tag.
    CONSTRAINT fx_provider_evidence_ciphertext_carries_the_tag CHECK (octet_length(content_ciphertext) = content_length + 16)
);

COMMENT ON TABLE fx.fx_provider_evidence IS
    'Verbatim FX provider payloads (P9-TSK-006, INV-HIST-02): every request, response, inquiry result and callback, AES-256-GCM under FINAPP_FX_EVIDENCE_KEY with the plaintext''s SHA-256 recorded at capture. Keyed by the platform''s own reference (QR or T, INV-PAY-04) and the provider code. Append-only for every writer.';

CREATE INDEX fx_provider_evidence_by_reference ON fx.fx_provider_evidence (provider_code, client_reference);

CREATE OR REPLACE FUNCTION fx.fx_provider_evidence_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'FX provider evidence is append-only for every writer: retained bytes are never edited and never deleted (INV-HIST-02)';
END;
$$;

CREATE TRIGGER fx_provider_evidence_is_append_only
    BEFORE UPDATE OR DELETE ON fx.fx_provider_evidence
    FOR EACH ROW
    EXECUTE FUNCTION fx.fx_provider_evidence_is_append_only();

GRANT SELECT, INSERT ON fx.fx_provider_evidence TO finapp_app;
