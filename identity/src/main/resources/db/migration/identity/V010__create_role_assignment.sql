-- P1-TSK-020: which identities hold which roles.
--
-- WHAT IS DATA HERE AND WHAT IS NOT.
--
-- This table holds ASSIGNMENTS. The role-to-permission MAPPING is code (`RoleName`), and that split
-- is ADR-0031's central decision rather than an implementation detail: *who* holds a role changes at
-- runtime, *what a role means* does not. Putting the mapping in a table would make "why was this
-- denied?" a question you answer by evaluating rows instead of by reading a method - which is
-- exactly what the ADR rejected a policy engine to avoid.
--
-- WHY ASSIGNMENTS ARE READ PER REQUEST AND NEVER STAMPED ON A SESSION.
--
-- A role carried on a session survives its own revocation until that session expires, so "remove
-- their access now" becomes a promise the architecture cannot keep. That is INV-IDN-03's reasoning
-- applied to authorization, and the same defect ADR-0030 rejected self-contained tokens for.
CREATE TABLE identity.role_assignment
(
    id           uuid        PRIMARY KEY,

    identity_id  uuid        NOT NULL REFERENCES identity.identity (id),
    role_name    text        NOT NULL,

    -- WHO granted it, and WHEN. Not decoration: the permission that grants permissions is held by
    -- somebody, and an escalation is only visible afterwards if the grant names its author. The
    -- audit trail carries the same fact; this column is what makes the CURRENT state explicable
    -- without reading the trail.
    assigned_by  uuid        NOT NULL REFERENCES identity.identity (id),
    assigned_at  timestamptz NOT NULL,

    -- Revoked, never deleted. The application role holds no DELETE on this table: a role somebody
    -- held for a month is a fact about the past, and an investigator asking "who could do this in
    -- March?" needs the row rather than its absence (INV-HIST-01's reasoning).
    revoked_by   uuid        REFERENCES identity.identity (id),
    revoked_at   timestamptz,

    -- Generated from RoleName; RoleAssignmentMigrationTest fails the build if they drift. A value
    -- the domain produces and the database refuses fails at the last write, after all the work, for
    -- a reason no error message explains (the P1-TSK-013 finding).
    CONSTRAINT role_assignment_role_is_known
        CHECK (role_name IN ('ADMINISTRATOR')),

    -- Revocation is one fact recorded in two columns, so they may not disagree.
    CONSTRAINT role_assignment_revocation_is_complete
        CHECK ((revoked_at IS NULL) = (revoked_by IS NULL)),

    CONSTRAINT role_assignment_revoked_after_assigned
        CHECK (revoked_at IS NULL OR revoked_at >= assigned_at)
);

-- At most one LIVE assignment of a role per identity.
--
-- Partial, so a revoked assignment frees the slot and the role can be granted again - which is the
-- ordinary thing that happens when somebody changes team and comes back. The identity.credential
-- shape rather than the login-identifier shape, and the difference is deliberate (P1-TSK-005).
CREATE UNIQUE INDEX role_assignment_one_live_per_identity
    ON identity.role_assignment (identity_id, role_name)
    WHERE revoked_at IS NULL;

-- The query the permission check runs on every request that declares a permission: an identity's
-- live roles. Partial for the same reason the session index is - revoked rows leave it, so it stays
-- the size of the live set rather than of the history.
CREATE INDEX role_assignment_live_by_identity
    ON identity.role_assignment (identity_id)
    WHERE revoked_at IS NULL;

GRANT SELECT, INSERT, UPDATE ON identity.role_assignment TO finapp_app;

COMMENT ON TABLE identity.role_assignment IS
    'Which identities hold which roles. Assignments are data; the role-to-permission mapping is '
    'code (ADR-0031). Read per request, never cached on a session, so revocation is immediate.';
