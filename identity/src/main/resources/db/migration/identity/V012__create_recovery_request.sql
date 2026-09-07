-- P1-TSK-023: account recovery, which exists to bypass the credential.
--
-- DELIVERY_PLAN.md §17 names recovery becoming the weakest link as a top Phase 1 risk, and
-- INV-IDN-06 says why: it is the classic account-takeover vector precisely because bypassing the
-- credential is its purpose. Built last, against a working MFA, session and audit model.
--
-- WHAT RECOVERY DOES, AND THE TWO THINGS IT DELIBERATELY DOES NOT.
--
-- It replaces the credential. It does NOT issue a session, and it does NOT remove a factor.
--
-- Both come straight from INV-IDN-06's second clause - "recovery never lowers the assurance required
-- to reach an account". A session handed out on completion IS that lowering: an attacker holding the
-- mailbox would skip the credential AND whatever stood behind it. Setting the credential and
-- stopping means the customer authenticates normally afterwards, so MFA still applies in full and an
-- attacker who compromised the mailbox still faces the second factor.
--
-- That also keeps MfaBypassPathsAreEnumeratedTest's statement true: nothing new creates a session.
-- That guard listed recovery as a recorded remainder for exactly this question.
CREATE TABLE identity.recovery_request
(
    id                  uuid        PRIMARY KEY,

    identity_id         uuid        NOT NULL REFERENCES identity.identity (id),

    -- Which channel proved control. Recorded rather than re-derived: if the identity later verifies
    -- a different address, an investigator still needs to know where THIS token was sent.
    channel_id          uuid        NOT NULL REFERENCES identity.contact_channel (id),

    -- Hashed, as PHASE_1_PLAN.md §5 requires. Whoever holds the plaintext can replace the
    -- credential, so this is a bearer credential and is treated as one.
    token_hash          text        NOT NULL,

    status              text        NOT NULL,

    -- THE CONCURRENT-RECOVERY-AND-LOGIN CONTROL, and it is a column rather than a procedure.
    --
    -- The dangerous form of that abuse case: an attacker initiates at T0, the customer notices and
    -- changes their password at T1, and the attacker completes at T2 and wins anyway. Completion is
    -- conditional on THIS credential still being the active one, so any change since initiation
    -- kills the request.
    --
    -- A predicate rather than "revoke recovery requests when the credential changes", because a
    -- predicate cannot be forgotten by a future credential-change caller - the same reasoning that
    -- puts ownership in the WHERE clause (ADR-0031).
    --
    -- Nullable: an identity with no credential at all can still recover, which is exactly the
    -- P1-TSK-026 situation where registration has not yet set one.
    credential_id       uuid,

    initiated_at        timestamptz NOT NULL,
    expires_at          timestamptz NOT NULL,

    -- Terminal timestamps. Set when the status becomes terminal, never before.
    completed_at        timestamptz,
    cancelled_at        timestamptz,

    CONSTRAINT recovery_request_status_is_known
        CHECK (status IN ('INITIATED', 'COMPLETED', 'CANCELLED')),

    CONSTRAINT recovery_request_expires_after_initiation
        CHECK (expires_at > initiated_at),

    -- A terminal status and its timestamp are one fact recorded in two columns.
    CONSTRAINT recovery_request_completion_is_complete
        CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL)),

    CONSTRAINT recovery_request_cancellation_is_complete
        CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL))
);

-- At most one LIVE request per identity.
--
-- Partial, so a completed or cancelled request frees the slot: somebody who recovers today and
-- forgets again next month must be able to. What it prevents is an attacker and a customer holding
-- two valid tokens at once - initiation cancels the predecessor, and this index is what makes that
-- true under ten concurrent initiations rather than merely usually.
CREATE UNIQUE INDEX recovery_request_one_live_per_identity
    ON identity.recovery_request (identity_id)
    WHERE status = 'INITIATED';

-- The lookup completion runs.
CREATE UNIQUE INDEX recovery_request_token_hash_is_unique
    ON identity.recovery_request (token_hash);

GRANT SELECT, INSERT, UPDATE ON identity.recovery_request TO finapp_app;

COMMENT ON TABLE identity.recovery_request IS
    'A single-use, expiring proof of channel control that permits replacing a credential. '
    'Never issues a session and never removes a factor: recovery must not lower the assurance '
    'required to reach an account (INV-IDN-06).';
