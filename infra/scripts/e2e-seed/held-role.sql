-- TEST-ONLY (P17-TSK-108). A live holding of a role, as one granted before a rule the grant door now enforces: a role that needs
-- a second factor, held by an identity with none (the factorless review's row, P15-TSK-039), or the second half of a pair the
-- owner's segregation-of-duties table forbids (the SoD review's row, P17-TSK-016). The door refuses both today - 409
-- identity.FactorRequiredForRole and 409 identity.RoleConflict - and the reviews exist for the holdings that came before it, so
-- this is the one way to put one on a test stack. Applied by infra/scripts/e2e-seed.sh with psql's variables - :'identity',
-- :'role', :'granter' (the run's administrator), each quoted as a literal by psql itself - and proven unchanged by app's
-- E2eSeedReviewsDatabaseTest: the reviews name what it wrote, and the revocation door takes it away.
--
-- It writes identity's own row as the grant would, minus the grant's checks and its audit record: the reviews read exactly
-- this. A role it does not know, an identity that does not exist, or a role already held live is refused by the table's own
-- constraints (role_assignment_role_is_known, the foreign keys, role_assignment_one_live_per_identity).
INSERT INTO identity.role_assignment (id, identity_id, role_name, assigned_by, assigned_at)
VALUES (gen_random_uuid(), :'identity', :'role', :'granter', now())
RETURNING id;
