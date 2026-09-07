-- P1-TSK-018: refuse a replayed one-time password.
--
-- WHY A NEW MECHANISM RATHER THAN THE ONE ENROLMENT USES.
--
-- P1-TSK-017 made confirmation replay-safe by consuming the PENDING row: a second attempt found
-- nothing to confirm. A CHALLENGE has no such state - the factor is already ACTIVE and stays that
-- way - so without this a code is replayable for as long as it is valid, which the +/-1 step window
-- makes roughly ninety seconds. "One-time password" would be false.
--
-- WHAT IS RECORDED, AND WHY THE STEP RATHER THAN THE CODE.
--
-- The time step the last accepted code belonged to. Any presented code whose step is <= this one is
-- refused, which is what RFC 6238 5.2 actually requires: not merely "the same code twice" but "no
-- code at or before the last accepted step". Storing consumed CODES instead would grow without
-- bound and need a sweep, for a strictly weaker property.
--
-- Nullable, because an enrolment that has never been used has no last step - and a sentinel like 0
-- would be a real step in January 1970 that some clock skew could in principle produce.
ALTER TABLE identity.mfa_enrolment
    ADD COLUMN last_used_step bigint;

-- A step is the epoch second divided by a positive period, so it cannot be negative. A negative
-- value here would mean somebody wrote a sentinel, and a sentinel in this column would silently
-- widen the window it exists to close.
ALTER TABLE identity.mfa_enrolment
    ADD CONSTRAINT mfa_enrolment_last_used_step_is_a_real_step
        CHECK (last_used_step IS NULL OR last_used_step > 0);

COMMENT ON COLUMN identity.mfa_enrolment.last_used_step IS
    'The TOTP time step of the last accepted code. Any code at or before it is refused '
    '(RFC 6238 5.2). Advanced by a conditional UPDATE whose row count is the outcome, so two '
    'instances presenting one code produce one success.';
