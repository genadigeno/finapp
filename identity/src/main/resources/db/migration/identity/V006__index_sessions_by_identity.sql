-- P1-TSK-014: the index bulk revocation needs.
--
-- V005 deliberately omitted this. PHASE_1_PLAN.md §6 requires every index to serve a stated query
-- and says an index without one is removed - and when the session table was created, listing a
-- person's sessions and revoking them in bulk were both queries that did not exist yet.
--
-- They exist now. "Revoke every session of this identity" is this task, and without an index it is
-- a sequential scan of every session on the platform, taking a row lock on each - which is the
-- shape that turns "log out everywhere" from a fast, safe operation into one nobody dares run.
--
-- PARTIAL, on the live sessions. Revocation only ever touches ACTIVE rows, and the table's long-term
-- bulk is revoked and expired ones that no query in this milestone reads. The partial index
-- therefore stays small as the table grows, which is the same reasoning V005 applied to the lock
-- index on authentication_failure.
--
-- Forward-only (ADR-0011).

CREATE INDEX session_active_by_identity
    ON identity.session (identity_id)
    WHERE status = 'ACTIVE';

COMMENT ON INDEX identity.session_active_by_identity IS
    'Serves bulk revocation and session listing (P1-TSK-014, P1-TSK-016). Partial, because both '
    'only ever ask about live sessions and the table''s bulk is eventually dead ones.';
