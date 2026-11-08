-- Callbacks as hints for the payment and instant providers (P15-TSK-036; ADR-0083 decision 4; INV-PAY-01, INV-IDEM-04,
-- INV-LIFE-03). A callback now adopts nothing of its own: its evidence and dedupe commit, then an authenticated inquiry
-- over the provider credential answers, and only that answer reaches the appliers.
--
-- WHY A TABLE AT ALL: a failed inquiry about a capture, a refund, a void, a pay-in, a return or a withdrawal leaves that
-- row in a dispatched or unknown state, which its own resolution sweep finds. A failed inquiry about a CLEARING, a DISPUTE
-- or an EXECUTION on a concluded or unknown row leaves nothing to find, and the inbox has already recorded the delivery, so
-- the redelivery is absorbed. This row is what "the sweep decides" needs for those four kinds. It says what to ask and of
-- whom; what the callback CLAIMED rests in provider_evidence and decides nothing.
--
-- Operational state, never financial truth: nothing reads it to state a balance. Expand-only: a new table, and the previous
-- release neither reads nor writes it.

CREATE TABLE payments.callback_hint (
    id               uuid        PRIMARY KEY,
    kind             text        NOT NULL
        CONSTRAINT callback_hint_kind_is_known
            CHECK (kind IN ('CARD_CLEARING', 'CARD_DISPUTE', 'INSTANT_CONCLUDED_EXECUTION',
                            'INSTANT_UNATTRIBUTED_EXECUTION')),
    attempt_id       uuid
        CONSTRAINT callback_hint_attempt_exists REFERENCES payments.payment_attempt (id),
    -- Our operation reference, the PSP's dispute reference, our end-to-end reference, or the scheme's reference, each
    -- shape-checked by its type before it reaches here. The bound is ProviderReference.MAX_LENGTH, the longest of them.
    asked_about      text        NOT NULL
        CONSTRAINT callback_hint_asked_about_is_bounded CHECK (char_length(asked_about) BETWEEN 1 AND 128),
    received_at      timestamptz NOT NULL DEFAULT statement_timestamp(),
    -- The sweep's conditional permit, on the database's clock (X-TSK-013), moved further out each time it is asked.
    next_inquiry_at  timestamptz NOT NULL,
    -- How many times the sweep has asked: the backoff's exponent, so a stuck hint costs tens of inquiries, not thousands.
    asked_count      integer     NOT NULL DEFAULT 0
        CONSTRAINT callback_hint_asked_count_is_counted CHECK (asked_count >= 0),
    concluded_at     timestamptz,
    conclusion       text
        CONSTRAINT callback_hint_conclusion_is_known
            CHECK (conclusion IN ('ADOPTED', 'CONVERGED', 'CONTRADICTED', 'REFUTED', 'EXPIRED')),
    CONSTRAINT callback_hint_concluded_together CHECK ((concluded_at IS NULL) = (conclusion IS NULL)),
    -- Only an execution naming nothing of ours has no attempt.
    CONSTRAINT callback_hint_names_its_attempt
        CHECK (attempt_id IS NOT NULL OR kind = 'INSTANT_UNATTRIBUTED_EXECUTION')
);

-- The sweep's read: what is due, oldest first, over the unconcluded rows only.
CREATE INDEX callback_hint_due ON payments.callback_hint (next_inquiry_at) WHERE concluded_at IS NULL;

-- One open question per kind, reference and attempt: a fresh event id restating an open question adds no row and no
-- inquiry. The attempt is part of the key on purpose: a forged notice naming a genuine dispute on the wrong attempt must
-- not absorb the genuine notice for the right one. An unattributed execution has no attempt, so the key reads a fixed
-- nil value there (NULLs would never conflict).
CREATE UNIQUE INDEX callback_hint_one_open_question
    ON payments.callback_hint (kind, asked_about, COALESCE(attempt_id, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE concluded_at IS NULL;

COMMENT ON TABLE payments.callback_hint IS
    'A callback still to be asked about, for the kinds with no payment row a resolution sweep could find (P15-TSK-036). Operational state, never financial truth.';

GRANT SELECT, INSERT ON payments.callback_hint TO finapp_app;
GRANT UPDATE (next_inquiry_at, asked_count, concluded_at, conclusion) ON payments.callback_hint TO finapp_app;
