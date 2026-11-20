-- TEST-ONLY (P17-TST-001). Scenario 1's reading, after the console's spec: an unauthorised privileged operation refused and audited
-- as a denial. The viewer, who holds OPERATIONS_VIEWER alone, forced the ledger adjustment's approval: refused for want of
-- LEDGER_ADJUST, recorded, and nothing of the ledger's done by the viewer.
-- Variables (psql's quoted form, each checked by the harness first): since, the scenario's start on the database's clock; viewer.
SELECT CASE WHEN count(*) >= 1 THEN 'PASS' ELSE 'FAIL' END, 'the refusal is in the audit trail as a denial for LEDGER_ADJUST',
       count(*) || ' identity.AuthorizationDenied records of the viewer naming LEDGER_ADJUST since the start'
  FROM platform.audit_record
 WHERE operation = 'identity.AuthorizationDenied' AND target_id = :'viewer' AND change_summary = 'permission=LEDGER_ADJUST'
   AND occurred_at >= :'since'::timestamptz;
SELECT CASE WHEN coalesce(string_agg(role_name, ',' ORDER BY role_name), '') = 'OPERATIONS_VIEWER' THEN 'PASS' ELSE 'FAIL' END,
       'the viewer holds OPERATIONS_VIEWER alone', 'live roles: ' || coalesce(string_agg(role_name, ',' ORDER BY role_name), 'none')
  FROM identity.role_assignment
 WHERE identity_id = :'viewer'::uuid AND revoked_at IS NULL;
SELECT CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'FAIL' END, 'nothing of the ledger succeeded for the viewer',
       count(*) || ' ledger records of the viewer that SUCCEEDED since the start'
  FROM platform.audit_record
 WHERE actor_id = :'viewer' AND operation LIKE 'ledger.%' AND outcome = 'SUCCEEDED' AND occurred_at >= :'since'::timestamptz;
