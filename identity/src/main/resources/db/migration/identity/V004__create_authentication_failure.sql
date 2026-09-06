-- P1-TSK-011: consecutive authentication failures per identity, and the lock they produce.
--
-- INV-CON-03 - "limits enforced non-atomically are limits that do not exist". Catalogued at
-- Phase 13; this is its first enforcement, three phases early, because ADR-0032 makes verification
-- deliberately expensive and names lockout as part of the same design rather than an extra.
--
-- ONE ROW PER IDENTITY, NOT ONE ROW PER ATTEMPT. An append-only attempt log is this platform's
-- usual idiom and is wrong here: counting rows in a window is a read-then-count, so ten concurrent
-- attempts at the threshold all read nine and all proceed - a burst straight through the limit,
-- which is exactly the shape INV-CON-03 calls a limit that does not exist. A single row updated by
-- one atomic statement has no such window. The cost is a row lock held for that statement alone -
-- microseconds, not the 46 ms of a derivation - and at volume the only party it serialises is the
-- attacker.
--
-- Forward-only (ADR-0011).

CREATE TABLE identity.authentication_failure (

    -- The primary key IS the identity: at most one counter per identity, enforced by the database
    -- rather than by a writer remembering. FK within this schema, which ADR-0029 permits - what it
    -- forbids is an FK across module schemas.
    identity_id        uuid        PRIMARY KEY REFERENCES identity.identity (id),

    -- Consecutive failures in the current window.
    failures           integer     NOT NULL,

    -- When this run of failures began. A window that has elapsed resets the count, so an occasional
    -- typo months apart never accumulates into a lock.
    window_started_at  timestamptz NOT NULL,

    -- Non-null while locked. Set when the threshold is crossed.
    --
    -- TIME-BOUNDED AND SELF-HEALING, deliberately, and this is a security decision rather than a
    -- convenience. Lockout is itself an attack: anyone who knows a login identifier can lock its
    -- owner out by failing often enough. A lock requiring an operator to clear it converts that
    -- cheap attack into a support-desk denial of service, and gives an insider a standing reason to
    -- touch other people's accounts. A lock that expires on its own has neither property.
    locked_until       timestamptz,

    updated_at         timestamptz NOT NULL,

    -- A row exists because something failed. Zero would mean "counted nothing", which is what the
    -- absence of a row already says.
    CONSTRAINT authentication_failure_count_is_positive
        CHECK (failures > 0),

    -- A lock is always in the future OF THE WINDOW IT CAME FROM. This catches the write that sets a
    -- lock already expired, which would look like a control and be none.
    CONSTRAINT authentication_failure_lock_follows_the_window
        CHECK (locked_until IS NULL OR locked_until > window_started_at)
);

COMMENT ON TABLE identity.authentication_failure IS
    'Consecutive failed authentications per identity, and the time-bounded lock they produce '
    '(P1-TSK-011). Counted only for identities that exist: a login identifier nobody registered has '
    'nothing to key on, and keying on the attempted string would build a caller-controlled, '
    'attacker-fillable table of things people typed.';

COMMENT ON COLUMN identity.authentication_failure.locked_until IS
    'While non-null and in the future, authentication is refused even when the password is correct. '
    'The refusal is byte-identical to every other failure and costs the same work (INV-IDN-07): a '
    'locked account answering faster than an unknown one is an account-existence oracle, which is '
    'the control defeating the invariant it was added to protect.';

-- Finding the locks that are still live, for the operational question "is a campaign running?".
-- Partial, because a row that is not locked is not interesting to that question and the table is
-- mostly such rows.
CREATE INDEX authentication_failure_locked
    ON identity.authentication_failure (locked_until)
    WHERE locked_until IS NOT NULL;

-- The application role writes and reads this table, and updates it in place. That is not a
-- weakening of INV-HIST-01: a counter is operational state, not financial history. What must not be
-- editable is the AUDIT RECORD of the lock, and that lives in platform.audit_record where the role
-- holds INSERT and SELECT only (P0-TSK-022).
GRANT SELECT, INSERT, UPDATE, DELETE ON identity.authentication_failure TO finapp_app;
