-- Idempotency records: the database-level arbiter of duplicate money-moving commands.
--
-- INVARIANTS THIS MIGRATION ENFORCES
--   INV-IDEM-01  Same business command, same key, one financial effect.
--                Enforced here by the unique constraint on (scope, idempotency_key). ADR-0004
--                rejected a cache and an HTTP filter for the same reason: under genuine
--                concurrency the database is the only component that can arbitrate, and a
--                cache miss or a restart admits a duplicate debit.
--   INV-IDEM-03  Key reuse with a different request is rejected.
--                Enforced in the domain (P0-TSK-016) by comparing request_fingerprint. The
--                schema's job is to make that comparison possible and self-describing.
--   INV-LIFE-02  Invalid state transitions are rejected where representable. The state
--                machine's shape is checked here, not only in application code.
--
-- MUTABILITY, AND WHY THIS TABLE IS NOT APPEND-ONLY
--   INV-LED-03, INV-HIST-01 and INV-HIST-03 require the application role to hold no UPDATE or
--   DELETE on the tables they cover, and DATA_MIGRATIONS.md §6 requires that split to land
--   before any such table exists (P0-TSK-022).
--
--   This table is deliberately not one of them. An idempotency record is a concurrency-control
--   artefact, not a financial record: it is claimed before the command runs and must then be
--   updated with the outcome, and it must be deleted when it expires. Making it immutable
--   would make the mechanism unimplementable. It records *that* a command ran; the financial
--   record of *what it did* lives in the ledger and is immutable there.
--
--   Required grants, applied by P0-TSK-022 when the roles exist, and stated here so the
--   table's intended privileges arrive with it (DATA_MIGRATIONS.md §6):
--       migrator     — owner; DDL only
--       application  — SELECT, INSERT, UPDATE, DELETE
--   No grant is issued now because no application role exists yet; local development runs as
--   the cluster superuser. Recorded in CURRENT_STATE.md §Partially Satisfied Definition of Done.

CREATE TABLE platform.idempotency_record
(
    -- The natural key IS the primary key. There is no surrogate id: every lookup is by
    -- (scope, idempotency_key), nothing references this table, and a surrogate would add a
    -- second index that only ever costs writes. An idempotency record is also not a domain
    -- aggregate, so ADR-0013's typed UUIDv7 identifiers do not apply to it.
    scope                 TEXT        NOT NULL,
    idempotency_key       TEXT        NOT NULL,

    -- Digest of the semantically significant request fields. Storing the algorithm alongside
    -- it is INV-HIST-04's rule applied here: a fingerprint is what decides whether two
    -- requests are "the same", so the thing that produced it is pinned on the record. Without
    -- it, changing the algorithm would silently compare digests from two different functions —
    -- which never match, so every retry after the change would be treated as a new request and
    -- produce a second financial effect.
    request_fingerprint   BYTEA       NOT NULL,
    fingerprint_algorithm TEXT        NOT NULL,

    state                 TEXT        NOT NULL,

    -- The stored outcome, replayed verbatim on retry.
    --
    -- BYTEA rather than JSONB on purpose. A retry must receive the bytes the first caller
    -- received; re-serialising a parsed structure can differ in field order, numeric
    -- formatting or encoding, and "almost the same response" is not what INV-IDEM-01 promises.
    -- It also avoids deciding the API's representation here, which is P0-EPIC-08's decision.
    -- NULL is legitimate: a command can complete with no body.
    response_body         BYTEA,
    response_media_type   TEXT,

    -- Which flow claimed this key. Diagnostic rather than functional, and the reason a
    -- duplicate submission can be traced back to the request that made it (P0-TSK-014).
    correlation_id        TEXT        NOT NULL,

    created_at            TIMESTAMPTZ NOT NULL,
    completed_at          TIMESTAMPTZ,

    -- Retention bound. See the expiry policy in DATA_MIGRATIONS.md §8.
    expires_at            TIMESTAMPTZ NOT NULL,

    CONSTRAINT idempotency_record_pk
        PRIMARY KEY (scope, idempotency_key),

    -- Bounded, because both are supplied by callers and an unbounded key is an unbounded
    -- index entry. 200 accommodates a scope of "command-type:principal" and any realistic
    -- client-generated key.
    CONSTRAINT idempotency_record_scope_bounded
        CHECK (length(scope) BETWEEN 1 AND 200),
    CONSTRAINT idempotency_record_key_bounded
        CHECK (length(idempotency_key) BETWEEN 1 AND 200),
    CONSTRAINT idempotency_record_correlation_bounded
        CHECK (length(correlation_id) BETWEEN 1 AND 128),

    -- A 256- to 512-bit digest. A truncated fingerprint weakens INV-IDEM-03 by making
    -- materially different requests collide, so the length is constrained rather than trusted.
    CONSTRAINT idempotency_record_fingerprint_sized
        CHECK (octet_length(request_fingerprint) BETWEEN 32 AND 64),
    CONSTRAINT idempotency_record_algorithm_bounded
        CHECK (length(fingerprint_algorithm) BETWEEN 1 AND 32),

    -- Generated from IdempotencyState by the build; see IdempotencyStateTest (hermetic) and
    -- IdempotencyRecordSchemaTest (against a real database), either of which fails if this
    -- list and the enum ever disagree.
    CONSTRAINT idempotency_record_state_known
        CHECK (state IN ('IN_PROGRESS', 'COMPLETED', 'FAILED')),

    -- The state machine, as far as a row can express it. An IN_PROGRESS claim that already
    -- carried a completion time or a response would be a contradiction: it would let a reader
    -- treat an unfinished command as finished, which is the one reading that produces a second
    -- financial effect.
    CONSTRAINT idempotency_record_in_progress_has_no_outcome
        CHECK (state <> 'IN_PROGRESS'
               OR (completed_at IS NULL AND response_body IS NULL AND response_media_type IS NULL)),
    CONSTRAINT idempotency_record_terminal_is_timestamped
        CHECK (state = 'IN_PROGRESS' OR completed_at IS NOT NULL),
    CONSTRAINT idempotency_record_completed_after_created
        CHECK (completed_at IS NULL OR completed_at >= created_at),

    -- A body without a media type cannot be replayed correctly; a media type without a body
    -- describes nothing.
    CONSTRAINT idempotency_record_body_and_type_together
        CHECK ((response_body IS NULL) = (response_media_type IS NULL)),

    -- An already-expired record would be swept away before it could ever answer a retry.
    CONSTRAINT idempotency_record_expires_after_created
        CHECK (expires_at > created_at)
);

COMMENT ON TABLE platform.idempotency_record IS
    'Claims for money-moving commands, keyed on (scope, idempotency_key). The unique key is '
    'what makes INV-IDEM-01 a guarantee under concurrency rather than a hope. Mutable and '
    'expiring by design: this records that a command ran, not what it did.';

COMMENT ON COLUMN platform.idempotency_record.scope IS
    'Command type plus owning principal. Prevents one client''s key colliding with another''s, '
    'and one command''s with a different command''s.';

COMMENT ON COLUMN platform.idempotency_record.state IS
    'IN_PROGRESS means the outcome is unknown - never assume failure and re-execute.';

-- NO INDEX ON expires_at, DELIBERATELY.
--
-- The retention sweep will need one, and its right shape depends on the predicate that sweep
-- actually uses - a plain btree, or a partial index restricted to terminal states, are
-- different answers with different write costs. Guessing now would add an index maintained on
-- every insert for a query nobody has written. It belongs to the task that writes the sweep.
