-- X-TSK-007: a session's bounds are stamped and judged by the database's clock.
--
-- THE DEFECT THIS CORRECTS
--   ADR-0014: where time is decided across instances, "the database supplies both sides of the
--   comparison". A session is the plainest case: one instance writes its bounds and any other asks
--   whether it is live. Both sides were instance clocks. The issuing instance stamped
--
--       idle_expires_at     = its now + idle timeout
--       absolute_expires_at = its now + absolute lifetime
--
--   and every lookup compared them with an instant the ASKING instance read from its own clock.
--   With the writer s_w and the judge s_j away from true time, a session lived
--
--       policy + s_w - s_j
--
--   A fast issuer's sessions outlived the absolute lifetime - the bound ADR-0030 keeps against a
--   stolen token - by its skew. A slow issuer's were dead on arrival once its skew passed the idle
--   timeout. A fast judge ended live sessions early, and a slow one honoured expired sessions late.
--   Judging on the database's clock alone would still leave s_w, and stamping alone would leave s_j,
--   so the store now does both. That is code, not schema. This migration is what the code needs
--   from the schema.
--
-- WHY THE SCHEMA HAS TO CHANGE
--   issued_at stays business time: the issuing instance's own reading, from its injected Clock
--   (P0-TSK-013), and the same reading the audit record of the login or rotation carries. Moving
--   it to the database's clock would split the row from its audit trail by the issuer's skew, so
--   it stays. revoked_at stays business time for the same reason.
--
--   But V005's session_bounds_follow_issue compares issued_at with the bounds, which are now the
--   database's. That is a comparison of two clocks inside a constraint - the defect itself, moved
--   into the schema. It would refuse every login on an instance more than the idle timeout fast,
--   and a step-up near the end of a session's life on an instance faster than the time remaining.
--
--   live_from is the database's own instant for the row: its now() when the row was written. The
--   bounds are measured from it, so "a session is never expired when it is written" is kept, on one
--   clock. The session lifetime metric is measured from it too, as now() - live_from, where it used
--   to subtract the issuing instance's clock from the revoking one's.
--
-- WHY THIS DOES NOT CONTRADICT P0-TSK-013
--   That rule governs business time, and business time is untouched: issued_at and revoked_at
--   remain application-supplied from one injected Clock, and declare no DEFAULT. live_from is not a
--   business fact. It is coordination time, and coordination time is the database's, as V004
--   decided for the idempotency lease.
--
-- A ROLLING DEPLOY
--   Old and new instances run together (ADR-0014). An old instance's INSERT names its ten columns
--   and not live_from, so the DEFAULT gives it the database's now(), which is exactly the value the
--   new code writes explicitly. The column is invisible to old SELECTs, which name their columns.
--   Old instances keep judging with their own clocks until they are replaced. That is inherent to
--   a rolling deploy, and it ends when the last old instance stops.
--
-- EXISTING ROWS
--   Their bounds were stamped from issued_at on the issuing instance's clock, so issued_at IS the
--   origin they were measured from, and backfilling live_from from it keeps every existing row
--   valid under the new constraint - V005's check held for each of them. Those bounds carry their
--   issuer's skew until they expire, within one absolute lifetime. From this migration on they are
--   judged by the database's clock like every other.
--
--   The ADD COLUMN takes an ACCESS EXCLUSIVE lock for the rest of this transaction, so no insert
--   can land between the backfill and SET NOT NULL, and session lookups wait for the migration.
--   At this table's size that is a pause. A table in the millions would want the backfill batched
--   ahead of the constraint instead.
--
-- INVARIANTS
--   INV-IDN-03  The row stays the one authority every instance reads, now judged on one clock:
--               expiry, like revocation, is the same fact on every instance.
--   ADR-0030    Two bounds, and the absolute one cannot be stretched - not by use, not by
--               rotation, and now not by an instance's clock.
--
-- Forward-only (ADR-0011).

ALTER TABLE identity.session
    ADD COLUMN live_from timestamptz;

UPDATE identity.session
SET live_from = issued_at;

ALTER TABLE identity.session
    ALTER COLUMN live_from SET DEFAULT now(),
    ALTER COLUMN live_from SET NOT NULL;

ALTER TABLE identity.session
    DROP CONSTRAINT session_bounds_follow_issue,
    -- A session that is already expired when it is written is not a session - V005's rule, on the
    -- one clock both sides are now read from.
    ADD CONSTRAINT session_bounds_follow_liveness
        CHECK (idle_expires_at > live_from AND absolute_expires_at > live_from);

COMMENT ON COLUMN identity.session.live_from IS
    'The database''s now() when this row was written: the instant its bounds are measured from and '
    'its lifetime is measured to. Coordination time (X-TSK-007, ADR-0014) - never an instance''s '
    'clock. issued_at is the same moment by the issuing instance''s clock, and the two differ by '
    'that instance''s skew.';

COMMENT ON COLUMN identity.session.issued_at IS
    'Business time: when the issuing instance says the session began, from its injected Clock - the '
    'reading its audit record carries. Decides nothing, and is compared with no bound (X-TSK-007).';

COMMENT ON COLUMN identity.session.revoked_at IS
    'Business time: when the revoking instance says the session ended, the reading its audit record '
    'carries. Under skew it can precede issued_at, which another instance wrote; nothing compares '
    'them.';

COMMENT ON COLUMN identity.session.idle_expires_at IS
    'Coordination time: stamped as the database''s now() plus the idle timeout, extended by the '
    'same rule on use (never backwards, never past absolute_expires_at), and judged against the '
    'database''s now() (X-TSK-007).';

COMMENT ON COLUMN identity.session.absolute_expires_at IS
    'Coordination time: stamped once, as the database''s now() plus the absolute lifetime, and '
    'carried verbatim by a rotation. Judged against the database''s now() (X-TSK-007).';
