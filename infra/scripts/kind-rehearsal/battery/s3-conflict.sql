-- TEST-ONLY (P17-TST-001). Scenario 3's reading, after the console's spec: two operators conflicting on one case, the second refused
-- 409. Both analysts read the case at one revision and asked to take it: one took it over, and the other's take, at a revision no
-- longer the case's, was refused (risk.CaseRevisionStale) - the journal holds the answers, this the one effect.
-- Variables: since; case; analysta; analystb.
SELECT CASE WHEN count(*) = 1 THEN 'PASS' ELSE 'FAIL' END, 'one take of the case since the start',
       count(*) || ' risk.CaseTaken records of the case since the start'
  FROM platform.audit_record
 WHERE operation = 'risk.CaseTaken' AND target_id = :'case' AND occurred_at >= :'since'::timestamptz;
SELECT CASE WHEN count(*) = 1 THEN 'PASS' ELSE 'FAIL' END, 'taken by one of the two analysts, and the case is that one''s',
       count(*) || ' cases taken by the analyst whose take was recorded'
  FROM risk.risk_case c
  JOIN platform.audit_record a ON a.target_id = c.id::text AND a.operation = 'risk.CaseTaken' AND a.occurred_at >= :'since'::timestamptz
 WHERE c.id = :'case'::uuid AND a.actor_id IN (:'analysta', :'analystb') AND c.taken_by = a.actor_id;
