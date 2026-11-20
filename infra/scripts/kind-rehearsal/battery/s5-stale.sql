-- TEST-ONLY (P17-TST-001). Scenario 5's reading, after the console's spec: a stale record refused and re-confirmed. One reconciler
-- proposed the fee break's resolution, the other approved it echoing an operand the proposal does not name - refused 409
-- reconciliation.ResolutionStale - and then re-confirmed it as proposed: one approval.
-- Variables: since; break; reconcilera; reconcilerb.
SELECT CASE WHEN count(*) = 1 THEN 'PASS' ELSE 'FAIL' END, 'the break''s resolution approved once, four eyes between the two',
       count(*) || ' resolutions of the break approved since the start, proposed by one reconciler and decided by the other'
  FROM reconciliation.resolution
 WHERE break_id = :'break'::uuid AND status = 'APPROVED' AND decided_at >= :'since'::timestamptz
   AND proposed_by IN (:'reconcilera', :'reconcilerb') AND decided_by IN (:'reconcilera', :'reconcilerb') AND proposed_by <> decided_by;
SELECT CASE WHEN count(*) = 1 THEN 'PASS' ELSE 'FAIL' END, 'one approval in the audit trail',
       count(*) || ' reconciliation.ResolutionApproved records of the break''s resolutions since the start'
  FROM platform.audit_record
 WHERE operation = 'reconciliation.ResolutionApproved' AND occurred_at >= :'since'::timestamptz
   AND target_id IN (SELECT id::text FROM reconciliation.resolution WHERE break_id = :'break'::uuid);
SELECT CASE WHEN count(*) >= 1 THEN 'PASS' ELSE 'FAIL' END, 'the stale approval refused before the one that counted',
       count(*) || ' door.ActRefused records of reconciliation.ResolutionStale by a reconciler before the approval'
  FROM platform.audit_record
 WHERE operation = 'door.ActRefused' AND change_summary LIKE 'code=reconciliation.ResolutionStale,%'
   AND actor_id IN (:'reconcilera', :'reconcilerb') AND occurred_at >= :'since'::timestamptz
   AND occurred_at < (SELECT min(decided_at) FROM reconciliation.resolution WHERE break_id = :'break'::uuid AND status = 'APPROVED');
