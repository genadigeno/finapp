-- Verification checks and their retained evidence (P2-TSK-009, ADR-0038).
--
-- A CHECK IS EVIDENCE, NEVER THE DECISION (INV-KYC-01)
--   One question to one provider: the raw answer retained verbatim beside it (INV-HIST-02),
--   the outcome normalised into OUR vocabulary - including INDETERMINATE for a timeout or an
--   answer we do not recognise (INV-LIFE-03, arriving three phases before its catalogued
--   owner). The case moves only by the platform's own assessment of the whole
--   (ChecksAssessment); no code path maps a provider state onto a case status.
--
-- THE TERMINAL STATES ARE THE OUTCOMES
--   One machine, not a status beside an outcome column that could disagree with it.
--   INDETERMINATE is terminal for the check: its resolution is a NEW check, so a check never
--   flaps and the evidence of the failed attempt stays true.
--
-- EVERY GENERATED LIST BELOW HAS ONE DEFINITION
--   The type CHECK is CheckType.sqlValueList(), the status CHECK is CheckStatus.sqlValueList(),
--   and the one-in-flight index predicate is CheckStatus.sqlTerminalValueList();
--   VerificationCheckMigrationTest fails the build if this file and the enums disagree
--   (the P0-TSK-022 pattern).

CREATE TABLE kyc.verification_check (
    -- UUIDv7, minted by the application (ADR-0013).
    id                uuid        PRIMARY KEY,

    -- FK within this module's own schema, which ADR-0029 permits (the kyc_document precedent).
    case_id           uuid        NOT NULL REFERENCES kyc.kyc_case (id),

    check_type        text        NOT NULL,
    status            text        NOT NULL,

    -- Application-supplied from one injected Clock, never DEFAULT now().
    requested_at      timestamptz NOT NULL,
    status_changed_at timestamptz NOT NULL,

    CONSTRAINT verification_check_type_is_known
        CHECK (check_type IN ('IDENTITY', 'DOCUMENT', 'SANCTIONS', 'PEP', 'ADVERSE_MEDIA')),

    CONSTRAINT verification_check_status_is_known
        CHECK (status IN ('REQUESTED', 'DISPATCHED', 'CLEAR', 'HIT', 'INDETERMINATE')),

    CONSTRAINT verification_check_status_change_is_not_before_request
        CHECK (status_changed_at >= requested_at)
);

-- AT MOST ONE IN-FLIGHT CHECK PER TYPE PER CASE; answered ones unrestricted.
--
-- Partial on the NON-terminal states, so a terminal check frees the slot: the resolution of an
-- INDETERMINATE is a NEW check of the same type, and the successor must be insertable without
-- touching its predecessor (ADR-0038 - a check never flaps). The store's requestOrConverge
-- hands the loser of the insert race the winner's check rather than an error.
CREATE UNIQUE INDEX verification_check_one_in_flight_per_type
    ON kyc.verification_check (case_id, check_type)
    WHERE status NOT IN ('CLEAR', 'HIT', 'INDETERMINATE');

-- Serves "the checks of this case", including terminal ones, which the partial index cannot.
CREATE INDEX verification_check_by_case ON kyc.verification_check (case_id);

COMMENT ON TABLE kyc.verification_check IS
    'One question to one provider: REQUESTED -> DISPATCHED -> {CLEAR | HIT | INDETERMINATE}. '
    'The dispatch is made durable BEFORE the provider is called, so a crash mid-call leaves a '
    'visible DISPATCHED fact to reconcile, never an unknown (INV-LIFE-03). The raw answer is '
    'retained verbatim in kyc.verification_evidence (INV-HIST-02); the outcome vocabulary is '
    'ours (INV-KYC-01, ADR-0008).';

-- UPDATE because status legitimately moves (the kyc_case reasoning verbatim); the immutable
-- artefacts are the evidence rows and the audit record. No DELETE: a check is evidence, and an
-- investigator asking "what did we ask in March?" needs the row.
GRANT SELECT, INSERT, UPDATE ON kyc.verification_check TO finapp_app;


-- THE RAW ANSWER, VERBATIM - the kyc_document at-rest shape (ADR-0036 groups provider evidence
-- and document content under one treatment): AES-256-GCM under the same externalised key,
-- SHA-256 of the plaintext bytes received, append-only at the privilege level.
CREATE TABLE kyc.verification_evidence (
    id                 uuid        PRIMARY KEY,

    check_id           uuid        NOT NULL REFERENCES kyc.verification_check (id),

    content_ciphertext bytea       NOT NULL,
    content_nonce      bytea       NOT NULL,
    key_version        integer     NOT NULL,
    checksum_sha256    bytea       NOT NULL,
    content_length     integer     NOT NULL,

    received_at        timestamptz NOT NULL,

    CONSTRAINT verification_evidence_nonce_is_gcm_sized
        CHECK (octet_length(content_nonce) = 12),

    CONSTRAINT verification_evidence_key_version_is_positive
        CHECK (key_version > 0),

    CONSTRAINT verification_evidence_checksum_is_sha256
        CHECK (octet_length(checksum_sha256) = 32),

    CONSTRAINT verification_evidence_content_length_is_bounded
        CHECK (content_length BETWEEN 1 AND 524288),

    -- GCM's arithmetic: ciphertext = plaintext + 16-byte tag (the kyc_document constraint).
    CONSTRAINT verification_evidence_ciphertext_carries_the_tag
        CHECK (octet_length(content_ciphertext) = content_length + 16)
);

CREATE INDEX verification_evidence_by_check ON kyc.verification_evidence (check_id);

COMMENT ON TABLE kyc.verification_evidence IS
    'Raw provider answers, retained verbatim (INV-HIST-02): AES-256-GCM ciphertext under a key '
    'held outside the database, with the SHA-256 of the bytes received. Append-only at the '
    'privilege level; a decision (P2-TSK-013) references the evidence it rested on '
    '(INV-KYC-02).';

-- SELECT and INSERT and nothing else: evidence is never edited and never deleted by the
-- application (INV-HIST-02). Deletion is Phase 15's retention question.
GRANT SELECT, INSERT ON kyc.verification_evidence TO finapp_app;
