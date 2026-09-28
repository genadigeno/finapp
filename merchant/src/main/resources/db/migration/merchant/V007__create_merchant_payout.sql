-- The merchant payout (P6-TSK-012, ADR-0051, ADR-0057): the first money the platform sends to
-- an external party on its own initiative, under Phase 5's disciplines pointed outward.
--
-- WHAT A ROW IS
--   One payout: amount, the destination VERSION it was bound to (a payout_destination id - each
--   change is its own immutable row, ADR-0056 section 9), the ledger hold reserving it on the
--   merchant's payable (by value; the ledger is another schema), and OUR minted reference -
--   all committed together by the dispatch transaction, which judged the bound inside the
--   payable account's lock (INV-MER-05). The provider's word moves only status, the failure
--   reason and the provider's reference. It is NOT a balance: in flight it is a hold, accepted
--   it is the merchant-payout:<payoutId> posting (INV-MER-02 - no payable column exists).
--
-- FOUR STATES, NOT FIVE
--   DISPATCHED -> {COMPLETED, FAILED, UNKNOWN}, UNKNOWN -> {COMPLETED, FAILED}. The plan's
--   REQUESTED would be a state no committed row can hold (the dispatch transaction judges,
--   holds and dispatches atomically), which ADR-0044 refuses (ADR-0057 section 1).
--
-- THE SEND PERMIT (last_dispatched_at)
--   Every send of our reference is preceded by a committed permit: the dispatch itself, or a
--   takeover's conditional renewal while the payout is still resolvable. The resolution sweep
--   concludes NEVER_RECEIVED only when the latest permit is older than its dispatched bound,
--   re-judged under the row lock - so "never received" can never be followed by a send
--   (ADR-0057 section 4). The one column that moves without a state change, forward only.
--
-- THE DESTINATION, AT EVERY RANK
--   The service reads the effective destination FOR SHARE in the dispatch transaction; the
--   composite FK below makes a payout to ANOTHER merchant's destination unstorable; the insert
--   trigger makes a payout to a destination that is not EFFECTIVE unstorable - for every writer.

-- The destination's (id, merchant_id) pair made referenceable, for the composite FK. The id is
-- already unique, so the constraint adds no restriction - only the reference target.
ALTER TABLE merchant.payout_destination
    ADD CONSTRAINT payout_destination_id_merchant_is_unique UNIQUE (id, merchant_id);

CREATE TABLE merchant.merchant_payout (
    -- UUIDv7, minted by the application (ADR-0013). The posting key's suffix.
    id                             uuid        PRIMARY KEY,
    merchant_id                    uuid        NOT NULL REFERENCES merchant.merchant (id),
    -- MoneyColumns: minor units, currency, scale (INV-MON-01/-02).
    amount_minor                   bigint      NOT NULL,
    currency                       char(3)     NOT NULL,
    scale                          smallint    NOT NULL,
    -- The destination version the payout was bound to at dispatch.
    destination_id                 uuid        NOT NULL,
    -- The ledger hold reserving the amount on the payable - by value, no cross-schema FK
    -- (the payments.refund.hold_reference precedent).
    hold_reference                 uuid        NOT NULL,
    -- OUR reference at the provider (INV-PAY-04): stored before anything is sent.
    provider_idempotency_reference text        NOT NULL UNIQUE,
    -- THE PROVIDER's reference, exactly when COMPLETED.
    provider_reference             text        UNIQUE,
    status                         text        NOT NULL,
    failure_reason                 text,
    -- The client's idempotency key, for the takeover's convergence (P5-TSK-016's contract).
    dispatch_key                   text        NOT NULL,
    -- Who asked: the merchant's API key (MERCHANT) or an operator on its behalf, with a reason.
    requested_by                   text        NOT NULL,
    requested_by_type              text        NOT NULL,
    reason                         text,
    -- Application-supplied from one injected Clock, never DEFAULT now() (P0-TSK-015).
    created_at                     timestamptz NOT NULL,
    last_dispatched_at             timestamptz NOT NULL,
    CONSTRAINT merchant_payout_destination_is_the_merchants
        FOREIGN KEY (destination_id, merchant_id)
        REFERENCES merchant.payout_destination (id, merchant_id),
    CONSTRAINT merchant_payout_currency_shape
        CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT merchant_payout_scale_bounded
        CHECK (scale BETWEEN 0 AND 9),
    CONSTRAINT merchant_payout_amount_is_positive
        CHECK (amount_minor > 0),
    CONSTRAINT merchant_payout_status_is_known
        CHECK (status IN ('DISPATCHED', 'COMPLETED', 'FAILED', 'UNKNOWN')),
    CONSTRAINT merchant_payout_failure_reason_is_known
        CHECK (failure_reason IS NULL OR failure_reason IN ('DECLINED', 'PROVIDER_UNAVAILABLE', 'NEVER_RECEIVED')),
    CONSTRAINT merchant_payout_failure_reason_exactly_when_failed
        CHECK ((status = 'FAILED') = (failure_reason IS NOT NULL)),
    CONSTRAINT merchant_payout_provider_reference_exactly_when_completed
        CHECK ((status = 'COMPLETED') = (provider_reference IS NOT NULL)),
    CONSTRAINT merchant_payout_reference_shape
        CHECK (provider_idempotency_reference ~ '^[A-Za-z0-9-]{1,64}$'),
    CONSTRAINT merchant_payout_provider_reference_shape
        CHECK (provider_reference IS NULL OR provider_reference ~ '^[A-Za-z0-9_.:-]{1,128}$'),
    CONSTRAINT merchant_payout_dispatch_key_bounded
        CHECK (length(dispatch_key) BETWEEN 1 AND 200),
    CONSTRAINT merchant_payout_requested_by_bounded
        CHECK (length(requested_by) BETWEEN 1 AND 200),
    CONSTRAINT merchant_payout_requested_by_type_bounded
        CHECK (length(requested_by_type) BETWEEN 1 AND 50),
    CONSTRAINT merchant_payout_reason_bounded
        CHECK (reason IS NULL OR length(reason) BETWEEN 1 AND 1000),
    -- The merchant's own request carries no reason; an operator's always does (ADR-0057 s.6).
    CONSTRAINT merchant_payout_reason_follows_the_requester
        CHECK ((requested_by_type = 'MERCHANT') = (reason IS NULL)),
    CONSTRAINT merchant_payout_permit_follows_birth
        CHECK (last_dispatched_at >= created_at)
);

-- One payout per client key per merchant: the takeover's convergence target. The idempotency
-- claim arbitrates duplicates first; this is the row-level backstop.
CREATE UNIQUE INDEX merchant_payout_one_per_dispatch_key
    ON merchant.merchant_payout (merchant_id, dispatch_key);

-- The resolution sweep's candidates: only rows still awaiting the rail's word.
CREATE INDEX merchant_payout_sweepable
    ON merchant.merchant_payout (created_at, id)
    WHERE status IN ('DISPATCHED', 'UNKNOWN');

-- Every writer: a payout is born DISPATCHED, to its merchant's EFFECTIVE destination. The
-- service's FOR SHARE read is the ordering; this is the rule, for writers that never ran it.
CREATE FUNCTION merchant.merchant_payout_is_born_dispatched_to_the_effective_destination()
    RETURNS trigger
LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.status <> 'DISPATCHED' THEN
        RAISE EXCEPTION 'a payout is born DISPATCHED: its outcomes arrive through the machine (P6-TSK-012)'
            USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (SELECT 1
                     FROM merchant.payout_destination destination
                    WHERE destination.id = NEW.destination_id
                      AND destination.merchant_id = NEW.merchant_id
                      AND destination.status = 'EFFECTIVE') THEN
        RAISE EXCEPTION 'a payout goes only to its merchant''s EFFECTIVE destination (P6-TSK-012, ADR-0056 section 9)'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER merchant_payout_is_born_dispatched_to_the_effective_destination
    BEFORE INSERT ON merchant.merchant_payout
    FOR EACH ROW
    EXECUTE FUNCTION merchant.merchant_payout_is_born_dispatched_to_the_effective_destination();

CREATE FUNCTION merchant.merchant_payout_permits_only_machine_edges() RETURNS trigger
LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.merchant_id IS DISTINCT FROM OLD.merchant_id
            OR NEW.amount_minor IS DISTINCT FROM OLD.amount_minor
            OR NEW.currency IS DISTINCT FROM OLD.currency
            OR NEW.scale IS DISTINCT FROM OLD.scale
            OR NEW.destination_id IS DISTINCT FROM OLD.destination_id
            OR NEW.hold_reference IS DISTINCT FROM OLD.hold_reference
            OR NEW.provider_idempotency_reference IS DISTINCT FROM OLD.provider_idempotency_reference
            OR NEW.dispatch_key IS DISTINCT FROM OLD.dispatch_key
            OR NEW.requested_by IS DISTINCT FROM OLD.requested_by
            OR NEW.requested_by_type IS DISTINCT FROM OLD.requested_by_type
            OR NEW.reason IS DISTINCT FROM OLD.reason
            OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'a payout''s dispatch is frozen: amount, destination, hold and reference never move (P6-TSK-012)';
    END IF;
    IF (OLD.failure_reason IS NOT NULL AND NEW.failure_reason IS DISTINCT FROM OLD.failure_reason)
            OR (OLD.provider_reference IS NOT NULL
                AND NEW.provider_reference IS DISTINCT FROM OLD.provider_reference) THEN
        RAISE EXCEPTION 'a payout''s recorded outcome never moves (INV-HIST-01''s discipline)';
    END IF;
    IF NEW.last_dispatched_at < OLD.last_dispatched_at THEN
        RAISE EXCEPTION 'a payout''s send permit only moves forward (ADR-0057 section 4)';
    END IF;
    IF NEW.last_dispatched_at IS DISTINCT FROM OLD.last_dispatched_at
            AND OLD.status NOT IN ('DISPATCHED', 'UNKNOWN') THEN
        RAISE EXCEPTION 'a resolved payout is never sent again (ADR-0057 section 4)';
    END IF;
    IF NEW.status = OLD.status THEN
        RETURN NEW;
    END IF;
    IF NOT ((OLD.status = 'DISPATCHED' AND NEW.status IN ('COMPLETED', 'FAILED', 'UNKNOWN'))
            OR (OLD.status = 'UNKNOWN' AND NEW.status IN ('COMPLETED', 'FAILED'))) THEN
        RAISE EXCEPTION 'a payout moves only along the machine''s edges: DISPATCHED -> {COMPLETED, FAILED, UNKNOWN}, UNKNOWN -> {COMPLETED, FAILED} (INV-LIFE-02/-04)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER merchant_payout_permits_only_machine_edges
    BEFORE UPDATE ON merchant.merchant_payout
    FOR EACH ROW
    EXECUTE FUNCTION merchant.merchant_payout_permits_only_machine_edges();

CREATE TABLE merchant.merchant_payout_event (
    id          bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payout_id   uuid        NOT NULL REFERENCES merchant.merchant_payout (id),
    from_status text        NOT NULL
        CONSTRAINT merchant_payout_event_from_status_is_known
            CHECK (from_status IN ('DISPATCHED', 'COMPLETED', 'FAILED', 'UNKNOWN')),
    to_status   text        NOT NULL
        CONSTRAINT merchant_payout_event_to_status_is_known
            CHECK (to_status IN ('DISPATCHED', 'COMPLETED', 'FAILED', 'UNKNOWN')),
    -- The audit_record actor model: the platform applies every outcome.
    actor_id    text        NOT NULL
        CONSTRAINT merchant_payout_event_actor_id_bounded
            CHECK (length(actor_id) BETWEEN 1 AND 200),
    actor_type  text        NOT NULL
        CONSTRAINT merchant_payout_event_actor_type_bounded
            CHECK (length(actor_type) BETWEEN 1 AND 50),
    -- Application-supplied, never DEFAULT now(). The sweep ages an UNKNOWN from this.
    occurred_at timestamptz NOT NULL
);

CREATE INDEX merchant_payout_event_by_payout
    ON merchant.merchant_payout_event (payout_id);

-- The provider's answers, verbatim and encrypted (INV-HIST-02; the payments.provider_evidence
-- shape, restated because merchant has no payments edge). Untrusted bytes whose content the
-- platform does not control, so never plaintext at rest, under a key of its own.
CREATE TABLE merchant.payout_evidence (
    id                 uuid        PRIMARY KEY,
    payout_id          uuid        NOT NULL REFERENCES merchant.merchant_payout (id),
    kind               text        NOT NULL
        CONSTRAINT payout_evidence_kind_is_known
            CHECK (kind IN ('RESPONSE', 'QUERY_RESULT')),
    content_ciphertext bytea       NOT NULL,
    content_nonce      bytea       NOT NULL,
    key_version        integer     NOT NULL,
    -- SHA-256 of the PLAINTEXT bytes as they arrived.
    checksum_sha256    bytea       NOT NULL,
    content_length     integer     NOT NULL,
    recorded_at        timestamptz NOT NULL,
    CONSTRAINT payout_evidence_nonce_is_gcm_sized
        CHECK (octet_length(content_nonce) = 12),
    CONSTRAINT payout_evidence_key_version_is_positive
        CHECK (key_version > 0),
    CONSTRAINT payout_evidence_checksum_is_sha256
        CHECK (octet_length(checksum_sha256) = 32),
    CONSTRAINT payout_evidence_content_length_is_bounded
        CHECK (content_length BETWEEN 1 AND 1048576),
    CONSTRAINT payout_evidence_ciphertext_carries_the_tag
        CHECK (octet_length(content_ciphertext) = content_length + 16)
);

CREATE INDEX payout_evidence_by_payout
    ON merchant.payout_evidence (payout_id);

CREATE FUNCTION merchant.payout_evidence_is_append_only() RETURNS trigger
LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'payout evidence is append-only for every writer: retained bytes are never edited and never deleted (INV-HIST-02)';
END;
$$;

CREATE TRIGGER payout_evidence_is_append_only
    BEFORE UPDATE OR DELETE ON merchant.payout_evidence
    FOR EACH ROW
    EXECUTE FUNCTION merchant.payout_evidence_is_append_only();

COMMENT ON TABLE merchant.merchant_payout IS
    'One payout of a merchant''s payable to its effective destination: DISPATCHED (judged in the payable''s lock, held, our reference committed before the wire) -> COMPLETED (hold released, DEBIT payable / CREDIT PAYOUT_CLEARING posted) | FAILED (released, nothing posted) | UNKNOWN (the hold stands until a query resolves it). Never a balance (INV-MER-02).';
COMMENT ON COLUMN merchant.merchant_payout.provider_idempotency_reference IS
    'Our minted reference at the payout provider (INV-PAY-04): committed before anything is sent, presented on every send and query, so no retry, takeover or sweep can make the provider pay twice.';
COMMENT ON COLUMN merchant.merchant_payout.last_dispatched_at IS
    'The latest send permit: committed before every send of our reference. The sweep concludes NEVER_RECEIVED only when it is older than the dispatched bound (ADR-0057 section 4). Forward only, and only while DISPATCHED or UNKNOWN.';
COMMENT ON TABLE merchant.merchant_payout_event IS
    'Append-only lifecycle history of merchant payouts: every move, by whom and when. Server-assigned order; SELECT and INSERT only.';
COMMENT ON TABLE merchant.payout_evidence IS
    'Verbatim payout provider answers (INV-HIST-02): AES-256-GCM ciphertext under a key held outside the database, the plaintext''s SHA-256 recorded at capture. Append-only for every writer.';

GRANT SELECT, INSERT ON merchant.merchant_payout TO finapp_app;
GRANT UPDATE (status, failure_reason, provider_reference, last_dispatched_at) ON merchant.merchant_payout TO finapp_app;
GRANT SELECT, INSERT ON merchant.merchant_payout_event TO finapp_app;
GRANT SELECT, INSERT ON merchant.payout_evidence TO finapp_app;
