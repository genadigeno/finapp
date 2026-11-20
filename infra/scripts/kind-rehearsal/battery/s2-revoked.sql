-- TEST-ONLY (P17-TST-001). Scenario 2's reading, after the console's spec: a permission revoked mid-session, the next act refused.
-- The administrator revoked EVENT_OPERATOR from the revoked person, signed in and stepped up on the dead letters; the person's next
-- act there was refused for want of EVENT_OPERATE, after the revocation, and nothing of the event plumbing was done by them after it.
-- The other role is still held, so the answer was 403, not the end of the session (finapp-admin-panel's m1.spec holds the last role's
-- 401). Nothing is seeded: the dead letters' queues are the act's subject, and a planted dead letter left behind would fail the census.
-- Variables: since; revoked; admin.
SELECT CASE WHEN count(*) = 1 THEN 'PASS' ELSE 'FAIL' END, 'EVENT_OPERATOR revoked by the administrator since the start',
       count(*) || ' revocations of EVENT_OPERATOR from the person by the administrator since the start'
  FROM identity.role_assignment
 WHERE identity_id = :'revoked'::uuid AND role_name = 'EVENT_OPERATOR' AND revoked_by = :'admin'::uuid
   AND revoked_at >= :'since'::timestamptz;
SELECT CASE WHEN count(*) >= 1 THEN 'PASS' ELSE 'FAIL' END, 'the next act refused for EVENT_OPERATE after the revocation',
       count(*) || ' identity.AuthorizationDenied records naming EVENT_OPERATE after the revocation'
  FROM platform.audit_record
 WHERE operation = 'identity.AuthorizationDenied' AND target_id = :'revoked' AND change_summary = 'permission=EVENT_OPERATE'
   AND occurred_at >= (SELECT max(revoked_at) FROM identity.role_assignment
                        WHERE identity_id = :'revoked'::uuid AND role_name = 'EVENT_OPERATOR');
SELECT CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'FAIL' END, 'nothing of the event plumbing done by the person after the revocation',
       count(*) || ' outbox, inbox and dead-letter records of the person that SUCCEEDED after the revocation'
  FROM platform.audit_record
 WHERE actor_id = :'revoked' AND outcome = 'SUCCEEDED'
   AND (operation LIKE 'outbox.%' OR operation LIKE 'inbox.%' OR operation LIKE 'deadletter.%')
   AND occurred_at >= (SELECT max(revoked_at) FROM identity.role_assignment
                        WHERE identity_id = :'revoked'::uuid AND role_name = 'EVENT_OPERATOR');
SELECT CASE WHEN count(*) = 1 THEN 'PASS' ELSE 'FAIL' END, 'the other role is still held',
       count(*) || ' live holdings of OPERATIONS_VIEWER'
  FROM identity.role_assignment
 WHERE identity_id = :'revoked'::uuid AND role_name = 'OPERATIONS_VIEWER' AND revoked_at IS NULL;
