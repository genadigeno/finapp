-- Representment and dispute evidence (P7-TSK-014, ADR-0061 section 7).
--
-- FOUR THINGS, ONE CONCERN - ANSWERING A CHARGEBACK
--   1. The network's respond-by deadline on the dispute, recorded once with the chargeback.
--   2. payments.dispute_evidence: the documents a responder attaches - kyc V003's shape restated
--      (INV-DSP-03 is INV-KYC-06's regime for disputes): AES-256-GCM ciphertext under the
--      DISPUTE-evidence key held outside the database, the plaintext's SHA-256, append-only by
--      grant, content-addressed convergence.
--   3. payments.dispute_response and its trail: the platform's answer - a representment or an
--      acceptance - as a dispatch-before-call operation (INV-PAY-04): born DISPATCHED with our
--      reference, one LIVE answer per dispute for every writer, the send permit forward-only.
--   4. The PSP's answers to a response join the retained provider evidence as its FOURTH subject.
--
-- A RESPONSE MOVES NO STAGE AND NO MONEY
--   The dispute's stage stays the network's word (REPRESENTED, ACCEPTED, WON, LOST arrive by
--   notification, and P7-TSK-013 posts what each owes); no column of payments.dispute is
--   written by a response.
--
-- EVERY GENERATED LIST BELOW HAS ONE DEFINITION
--   DisputeResponseStatus / DisputeResponseKind / DisputeResponseFailure / DisputeEvidenceKind /
--   DisputeEvidenceContentType .sqlValueList(), DisputeResponseStatus.permittedTransitions() and
--   isLive(), DisputeEvidenceContent.MAX_BYTES and MAX_PER_DISPUTE; PaymentsMigrationTest fails
--   the build if this file and the code disagree (the P0-TSK-022 pattern).

-- ----------------------------------------------------------------- 1. the respond-by deadline

ALTER TABLE payments.dispute
    ADD COLUMN respond_by timestamptz,
    -- The deadline rides the chargeback: an inquiry has nothing to represent against.
    ADD CONSTRAINT dispute_respond_by_rides_the_chargeback
        CHECK (respond_by IS NULL OR chargeback_amount_minor IS NOT NULL);

-- The deadline alarm's read (finapp.payments.dispute.deadline.near): chargebacks awaiting an
-- answer, by deadline.
CREATE INDEX dispute_awaiting_answer
    ON payments.dispute (respond_by)
    WHERE stage = 'CHARGED_BACK';

-- The machine trigger, re-stated: V021's body VERBATIM (V020's and V021's definitions are
-- applied history - the migration handoff discipline, ADR-0011) plus the deadline's NULL ->
-- value rule, beside the fee's.
CREATE OR REPLACE FUNCTION payments.dispute_permits_only_machine_edges() RETURNS trigger
LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.provider IS DISTINCT FROM OLD.provider
            OR NEW.provider_dispute_reference IS DISTINCT FROM OLD.provider_dispute_reference
            OR NEW.attempt_id IS DISTINCT FROM OLD.attempt_id
            OR NEW.reason IS DISTINCT FROM OLD.reason
            OR NEW.opened_at IS DISTINCT FROM OLD.opened_at THEN
        RAISE EXCEPTION 'a dispute''s opening statement is frozen: provider, reference, attempt and reason never move (P7-TSK-012, INV-HIST-01''s discipline)';
    END IF;
    -- The chargeback's amount moves only NULL -> value (the attempt's payload discipline):
    -- the CHECK above lets it arrive only with the funds taken, and this refuses its
    -- revision or its removal once recorded.
    IF (OLD.chargeback_amount_minor IS NOT NULL
                AND NEW.chargeback_amount_minor IS DISTINCT FROM OLD.chargeback_amount_minor)
            OR (OLD.chargeback_currency IS NOT NULL
                AND NEW.chargeback_currency IS DISTINCT FROM OLD.chargeback_currency)
            OR (OLD.chargeback_scale IS NOT NULL
                AND NEW.chargeback_scale IS DISTINCT FROM OLD.chargeback_scale) THEN
        RAISE EXCEPTION 'a recorded chargeback amount never changes: it arrives with the funds taken and moves only from NULL to a value (P7-TSK-012, INV-HIST-02)';
    END IF;
    -- The attribution arrives with the chargeback (the CHECKs pair it with the amount) and
    -- afterwards moves only as a RE-ATTRIBUTION: its shares never shrink, and they grow only
    -- on a standing dispute whose stage the same statement does not move (P7-TSK-013).
    IF OLD.counterparty_share_amount_minor IS NOT NULL
            AND (NEW.counterparty_share_amount_minor IS DISTINCT FROM OLD.counterparty_share_amount_minor
                 OR NEW.parked_share_amount_minor IS DISTINCT FROM OLD.parked_share_amount_minor) THEN
        IF NEW.counterparty_share_amount_minor IS NULL
                OR NEW.parked_share_amount_minor IS NULL
                OR NEW.counterparty_share_amount_minor < OLD.counterparty_share_amount_minor
                OR NEW.parked_share_amount_minor < OLD.parked_share_amount_minor
                OR NEW.stage IS DISTINCT FROM OLD.stage
                OR OLD.stage NOT IN ('CHARGED_BACK', 'REPRESENTED', 'LOST', 'ACCEPTED') THEN
            RAISE EXCEPTION 'a chargeback''s attribution only grows, by re-attribution on a standing dispute whose stage does not move (P7-TSK-013, INV-DSP-01)';
        END IF;
    END IF;
    -- The PSP's dispute fee moves only NULL -> value.
    IF (OLD.dispute_fee_amount_minor IS NOT NULL
                AND NEW.dispute_fee_amount_minor IS DISTINCT FROM OLD.dispute_fee_amount_minor)
            OR (OLD.dispute_fee_currency IS NOT NULL
                AND NEW.dispute_fee_currency IS DISTINCT FROM OLD.dispute_fee_currency)
            OR (OLD.dispute_fee_scale IS NOT NULL
                AND NEW.dispute_fee_scale IS DISTINCT FROM OLD.dispute_fee_scale) THEN
        RAISE EXCEPTION 'a recorded dispute fee never changes: it moves only from NULL to a value (P7-TSK-013, INV-HIST-02)';
    END IF;
    -- The network's respond-by deadline moves only NULL -> value (P7-TSK-014): the first
    -- statement stands, and a later statement of another date rests as evidence.
    IF OLD.respond_by IS NOT NULL AND NEW.respond_by IS DISTINCT FROM OLD.respond_by THEN
        RAISE EXCEPTION 'a recorded respond-by deadline never changes: it moves only from NULL to a value (P7-TSK-014, INV-HIST-02)';
    END IF;
    IF NEW.stage = OLD.stage THEN
        RETURN NEW;
    END IF;
    IF NOT ((OLD.stage = 'INQUIRY' AND NEW.stage IN ('CHARGED_BACK', 'CLOSED'))
            OR (OLD.stage = 'CHARGED_BACK' AND NEW.stage IN ('REPRESENTED', 'LOST', 'ACCEPTED'))
            OR (OLD.stage = 'REPRESENTED' AND NEW.stage IN ('WON', 'LOST'))) THEN
        RAISE EXCEPTION 'a dispute moves only along the machine''s edges: INQUIRY -> {CHARGED_BACK, CLOSED}, CHARGED_BACK -> {REPRESENTED, LOST, ACCEPTED}, REPRESENTED -> {WON, LOST}; WON, LOST, ACCEPTED and CLOSED are terminal (INV-LIFE-02/-04)';
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON COLUMN payments.dispute.respond_by IS
    'The network''s representment deadline, stated with the chargeback and recorded once (P7-TSK-014, ADR-0061 section 7). The platform''s clock uses it only to refuse its own late dispatch and to raise finapp.payments.dispute.deadline.near - never to decide an outcome.';

GRANT UPDATE (respond_by) ON payments.dispute TO finapp_app;

-- ----------------------------------------------------------------- 2. the evidence

CREATE TABLE payments.dispute_evidence (
    -- UUIDv7, minted by the application (ADR-0013).
    id                 uuid        PRIMARY KEY,

    -- FK WITHIN this schema: an orphaned document is what the upload transaction must make
    -- impossible.
    dispute_id         uuid        NOT NULL REFERENCES payments.dispute (id),

    -- Generated from DisputeEvidenceKind.sqlValueList().
    kind               text        NOT NULL
        CONSTRAINT dispute_evidence_kind_is_known
            CHECK (kind IN ('RECEIPT', 'PROOF_OF_DELIVERY', 'CUSTOMER_COMMUNICATION', 'POLICY', 'OTHER')),

    -- Generated from DisputeEvidenceContentType.sqlValueList().
    content_type       text        NOT NULL
        CONSTRAINT dispute_evidence_content_type_is_known
            CHECK (content_type IN ('JPEG', 'PNG', 'PDF')),

    -- AES-256-GCM under FINAPP_PAYMENTS_DISPUTE_EVIDENCE_KEY, never the provider-evidence or document
    -- key: one key per concern (ADR-0036). The nonce per row because GCM requires it fresh, the
    -- version so a rotation can tell which key wrote which row (INV-HIST-04's rule on a key).
    content_ciphertext bytea       NOT NULL,
    content_nonce      bytea       NOT NULL,
    key_version        integer     NOT NULL,

    -- SHA-256 of the PLAINTEXT received, verified on every read - and the content address a
    -- retried upload converges on.
    checksum_sha256    bytea       NOT NULL,
    content_length     integer     NOT NULL,

    -- Who attached it: the merchant over its key, or an operator for a payment with no merchant.
    uploaded_by_id     text        NOT NULL
        CONSTRAINT dispute_evidence_uploaded_by_id_bounded
            CHECK (length(uploaded_by_id) BETWEEN 1 AND 200),
    uploaded_by_type   text        NOT NULL
        CONSTRAINT dispute_evidence_uploaded_by_type_bounded
            CHECK (length(uploaded_by_type) BETWEEN 1 AND 50),

    -- Application-supplied from one injected Clock, never DEFAULT now().
    uploaded_at        timestamptz NOT NULL,

    CONSTRAINT dispute_evidence_nonce_is_gcm_sized
        CHECK (octet_length(content_nonce) = 12),
    CONSTRAINT dispute_evidence_key_version_is_positive
        CHECK (key_version > 0),
    CONSTRAINT dispute_evidence_checksum_is_sha256
        CHECK (octet_length(checksum_sha256) = 32),
    -- DisputeEvidenceContent.MAX_BYTES: the boundary's bound, where a writer that never passed
    -- the boundary would otherwise ignore it.
    CONSTRAINT dispute_evidence_content_length_is_bounded
        CHECK (content_length BETWEEN 1 AND 524288),
    -- GCM's arithmetic: ciphertext = plaintext + the 16-byte tag. A plaintext passed off as a
    -- ciphertext of the declared length cannot satisfy it.
    CONSTRAINT dispute_evidence_ciphertext_carries_the_tag
        CHECK (octet_length(content_ciphertext) = content_length + 16),

    -- CONTENT-ADDRESSED CONVERGENCE (the kyc upload's idempotency): the same bytes on the same
    -- dispute are one document. The leading column also serves "the documents of this dispute".
    CONSTRAINT dispute_evidence_one_per_dispute_and_checksum
        UNIQUE (dispute_id, checksum_sha256)
);

COMMENT ON TABLE payments.dispute_evidence IS
    'Dispute evidence documents (P7-TSK-014, INV-DSP-03): AES-256-GCM ciphertext under the dispute-evidence key held outside the database, the plaintext''s SHA-256 recorded at capture and verified on every read. Append-only for the application role; every content read and every transmission to the PSP is audited.';

-- SELECT and INSERT and nothing else: evidence is never edited and never deleted by the
-- application (INV-HIST-02, INV-DSP-03). Deletion is Phase 15's retention question.
GRANT SELECT, INSERT ON payments.dispute_evidence TO finapp_app;

-- ----------------------------------------------------------------- 3. the response

CREATE TABLE payments.dispute_response (
    id                             uuid        PRIMARY KEY,

    dispute_id                     uuid        NOT NULL REFERENCES payments.dispute (id),

    -- Generated from DisputeResponseKind.sqlValueList().
    kind                           text        NOT NULL
        CONSTRAINT dispute_response_kind_is_known
            CHECK (kind IN ('REPRESENTMENT', 'ACCEPTANCE')),

    -- Generated from DisputeResponseStatus.sqlValueList().
    status                         text        NOT NULL
        CONSTRAINT dispute_response_status_is_known
            CHECK (status IN ('DISPATCHED', 'SUBMITTED', 'FAILED', 'UNKNOWN')),

    -- Generated from DisputeResponseFailure.sqlValueList(); FAILED's exact companion.
    failure_reason                 text
        CONSTRAINT dispute_response_failure_reason_is_known
            CHECK (failure_reason IN ('DECLINED', 'PROVIDER_UNAVAILABLE')),
    CONSTRAINT dispute_response_failure_reason_matches_status
        CHECK ((status = 'FAILED') = (failure_reason IS NOT NULL)),

    -- OUR reference (INV-PAY-04): minted at birth, presented on every send and re-send, the
    -- query's key; the provider-idempotency charset, unique platform-wide.
    provider_idempotency_reference text        NOT NULL
        CONSTRAINT dispute_response_reference_shape
            CHECK (provider_idempotency_reference ~ '^[A-Za-z0-9-]{1,64}$'),
    CONSTRAINT dispute_response_reference_is_unique
        UNIQUE (provider_idempotency_reference),

    -- The PSP's submission reference, exactly when SUBMITTED - the reconciliation key.
    provider_reference             text
        CONSTRAINT dispute_response_provider_reference_shape
            CHECK (provider_reference ~ '^[A-Za-z0-9_.:-]{1,128}$'),
    CONSTRAINT dispute_response_provider_reference_matches_status
        CHECK ((status = 'SUBMITTED') = (provider_reference IS NOT NULL)),

    -- The documents it transmits, in order - frozen at birth: a representment carries at
    -- least one (at most DisputeEvidenceContent.MAX_PER_DISPUTE), an acceptance none.
    evidence_ids                   uuid[]      NOT NULL,
    CONSTRAINT dispute_response_evidence_matches_kind
        CHECK ((kind = 'REPRESENTMENT') = (cardinality(evidence_ids) > 0)),
    CONSTRAINT dispute_response_evidence_is_bounded
        CHECK (cardinality(evidence_ids) <= 5 AND array_position(evidence_ids, NULL) IS NULL),

    -- Who answered: the merchant over its key, or an operator for a payment with no merchant,
    -- whose reason the row keeps (INV-AUD-03's bound, the audit record's own).
    requested_by_id                text        NOT NULL
        CONSTRAINT dispute_response_requested_by_id_bounded
            CHECK (length(requested_by_id) BETWEEN 1 AND 200),
    requested_by_type              text        NOT NULL
        CONSTRAINT dispute_response_requested_by_type_bounded
            CHECK (length(requested_by_type) BETWEEN 1 AND 50),
    reason                         text
        CONSTRAINT dispute_response_reason_bounded
            CHECK (reason IS NULL OR length(reason) BETWEEN 1 AND 1000),

    -- The claim's scope and client key: a takeover's convergence target, bound to ONE
    -- response for ever (the refund's V008 rule).
    dispatch_scope                 text        NOT NULL
        CONSTRAINT dispute_response_dispatch_scope_bounded
            CHECK (length(dispatch_scope) BETWEEN 1 AND 200),
    dispatch_key                   text        NOT NULL
        CONSTRAINT dispute_response_dispatch_key_bounded
            CHECK (length(dispatch_key) BETWEEN 1 AND 200),
    CONSTRAINT dispute_response_one_per_dispatch_key
        UNIQUE (dispatch_scope, dispatch_key),

    -- Application-supplied from the injected Clock, never DEFAULT now().
    created_at                     timestamptz NOT NULL,

    -- THE SEND PERMIT (ADR-0057 section 4): committed before every send of our reference,
    -- forward-only for every writer, judged on the locked row - the wire-noise arbiter among
    -- instances (a response moves no money).
    send_permit                    timestamptz NOT NULL,
    CONSTRAINT dispute_response_permit_follows_creation
        CHECK (send_permit >= created_at)
);

-- ONE LIVE ANSWER PER DISPUTE, for every writer (generated from DisputeResponseStatus.isLive():
-- every status but FAILED). A FAILED answer frees the dispute for another.
CREATE UNIQUE INDEX dispute_response_one_live_per_dispute
    ON payments.dispute_response (dispute_id)
    WHERE status <> 'FAILED';

-- The resolution sweep's candidates: only rows still awaiting the PSP's word.
CREATE INDEX dispute_response_sweepable
    ON payments.dispute_response (created_at, id)
    WHERE status IN ('DISPATCHED', 'UNKNOWN');

-- Every writer: a response is born DISPATCHED - its outcomes arrive through the machine.
CREATE FUNCTION payments.dispute_response_is_born_dispatched() RETURNS trigger
LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.status <> 'DISPATCHED' THEN
        RAISE EXCEPTION 'a dispute response is born DISPATCHED: its outcomes arrive through the machine (P7-TSK-014)'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER dispute_response_is_born_dispatched
    BEFORE INSERT ON payments.dispute_response
    FOR EACH ROW
    EXECUTE FUNCTION payments.dispute_response_is_born_dispatched();

-- The machine, the freeze and the permit, bound to every writer including the migrator (the
-- withdrawal's V016 function, this machine's columns; edges generated from
-- DisputeResponseStatus.permittedTransitions()).
CREATE FUNCTION payments.dispute_response_permits_only_machine_edges() RETURNS trigger
LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.dispute_id IS DISTINCT FROM OLD.dispute_id
            OR NEW.kind IS DISTINCT FROM OLD.kind
            OR NEW.provider_idempotency_reference IS DISTINCT FROM OLD.provider_idempotency_reference
            OR NEW.evidence_ids IS DISTINCT FROM OLD.evidence_ids
            OR NEW.requested_by_id IS DISTINCT FROM OLD.requested_by_id
            OR NEW.requested_by_type IS DISTINCT FROM OLD.requested_by_type
            OR NEW.reason IS DISTINCT FROM OLD.reason
            OR NEW.dispatch_scope IS DISTINCT FROM OLD.dispatch_scope
            OR NEW.dispatch_key IS DISTINCT FROM OLD.dispatch_key
            OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'a dispute response''s dispatch is frozen: dispute, kind, reference, evidence, requester and reason never move (P7-TSK-014)';
    END IF;
    IF (OLD.failure_reason IS NOT NULL AND NEW.failure_reason IS DISTINCT FROM OLD.failure_reason)
            OR (OLD.provider_reference IS NOT NULL
                AND NEW.provider_reference IS DISTINCT FROM OLD.provider_reference) THEN
        RAISE EXCEPTION 'a dispute response''s recorded outcome never moves (INV-HIST-01''s discipline)';
    END IF;
    IF NEW.send_permit < OLD.send_permit THEN
        RAISE EXCEPTION 'a dispute response''s send permit only moves forward (ADR-0057 section 4)';
    END IF;
    IF NEW.send_permit IS DISTINCT FROM OLD.send_permit
            AND OLD.status NOT IN ('DISPATCHED', 'UNKNOWN') THEN
        RAISE EXCEPTION 'a resolved dispute response is never sent again (ADR-0057 section 4)';
    END IF;
    IF NEW.status = OLD.status THEN
        RETURN NEW;
    END IF;
    IF NOT ((OLD.status = 'DISPATCHED' AND NEW.status IN ('SUBMITTED', 'FAILED', 'UNKNOWN'))
            OR (OLD.status = 'UNKNOWN' AND NEW.status IN ('SUBMITTED', 'FAILED'))) THEN
        RAISE EXCEPTION 'a dispute response moves only along the machine''s edges: DISPATCHED -> {SUBMITTED, FAILED, UNKNOWN}, UNKNOWN -> {SUBMITTED, FAILED}; SUBMITTED and FAILED are terminal (INV-LIFE-02/-04)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER dispute_response_permits_only_machine_edges
    BEFORE UPDATE ON payments.dispute_response
    FOR EACH ROW
    EXECUTE FUNCTION payments.dispute_response_permits_only_machine_edges();

-- The append-only trail; the sweep ages an UNKNOWN from its entering move.
CREATE TABLE payments.dispute_response_event (
    id           bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    response_id  uuid        NOT NULL REFERENCES payments.dispute_response (id),
    from_status  text        NOT NULL
        CONSTRAINT dispute_response_event_from_status_is_known
            CHECK (from_status IN ('DISPATCHED', 'SUBMITTED', 'FAILED', 'UNKNOWN')),
    to_status    text        NOT NULL
        CONSTRAINT dispute_response_event_to_status_is_known
            CHECK (to_status IN ('DISPATCHED', 'SUBMITTED', 'FAILED', 'UNKNOWN')),
    actor_id     text        NOT NULL
        CONSTRAINT dispute_response_event_actor_id_bounded
            CHECK (length(actor_id) BETWEEN 1 AND 200),
    actor_type   text        NOT NULL
        CONSTRAINT dispute_response_event_actor_type_bounded
            CHECK (length(actor_type) BETWEEN 1 AND 50),
    occurred_at  timestamptz NOT NULL
);

CREATE INDEX dispute_response_event_by_response
    ON payments.dispute_response_event (response_id);

COMMENT ON TABLE payments.dispute_response IS
    'The platform''s answer to a chargeback through the card PSP (P7-TSK-014, ADR-0061 section 7): a representment carrying the dispute''s evidence, or an acceptance - dispatched before the call with our reference (INV-PAY-04), one live answer per dispute. SUBMITTED means the PSP took it; the dispute''s stage stays the network''s word.';

GRANT SELECT, INSERT ON payments.dispute_response TO finapp_app;
GRANT UPDATE (status, failure_reason, provider_reference, send_permit)
    ON payments.dispute_response TO finapp_app;
GRANT SELECT, INSERT ON payments.dispute_response_event TO finapp_app;

-- ----------------------------------------------------------------- 4. provider evidence

-- The PSP's answers to a response join the retained evidence (INV-HIST-02) as the FOURTH
-- subject: the same cipher, the same classification, one evidence discipline. V016's
-- at-most-one-subject rule is recreated over the four - the current definition lives here
-- (ADR-0011). A representment's REQUEST is never retained here: it carries the evidence
-- itself, which rests once, under its own key, in payments.dispute_evidence.
ALTER TABLE payments.provider_evidence
    ADD COLUMN dispute_response_id uuid REFERENCES payments.dispute_response (id),
    DROP CONSTRAINT provider_evidence_has_at_most_one_subject,
    ADD CONSTRAINT provider_evidence_has_at_most_one_subject
        CHECK (num_nonnulls(attempt_id, refund_id, withdrawal_id, dispute_response_id) <= 1);

CREATE INDEX provider_evidence_by_dispute_response
    ON payments.provider_evidence (dispute_response_id)
    WHERE dispute_response_id IS NOT NULL;
