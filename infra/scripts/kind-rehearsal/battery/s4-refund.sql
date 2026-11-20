-- TEST-ONLY (P17-TST-001). Scenario 4's reading, after the console's spec: a timeout after the backend executed. The refunder proposed
-- the refund of the whole payment (above the four-eyes threshold), the controller approved it, the proxy dropped the approval's answer
-- after the backend committed, and the console retried under the same key: the journal holds the replayed answer, this the one refund.
-- Variables: payment; refunder; controller; amount_minor (the whole payment, at EUR's scale).
SELECT CASE WHEN count(*) = 1 THEN 'PASS' ELSE 'FAIL' END, 'exactly one refund of the payment',
       count(*) || ' refunds of the payment''s attempts'
  FROM payments.refund r
  JOIN payments.payment_attempt a ON a.id = r.attempt_id
 WHERE a.intent_id = :'payment'::uuid;
SELECT CASE WHEN count(*) = 1 THEN 'PASS' ELSE 'FAIL' END, 'the whole amount, in EUR, COMPLETED',
       count(*) || ' COMPLETED refunds of ' || :'amount_minor' || ' EUR minor units'
  FROM payments.refund r
  JOIN payments.payment_attempt a ON a.id = r.attempt_id
 WHERE a.intent_id = :'payment'::uuid AND r.amount_minor = :'amount_minor'::bigint AND r.currency = 'EUR' AND r.scale = 2
   AND r.status = 'COMPLETED';
SELECT CASE WHEN count(*) = 1 AND count(*) FILTER (WHERE p.status = 'APPROVED' AND p.proposed_by = :'refunder'
                 AND p.decided_by = :'controller' AND p.refund_id = r.id) = 1 THEN 'PASS' ELSE 'FAIL' END,
       'one proposal of it: the refunder''s, approved by the controller, naming the refund',
       count(*) || ' proposals of the payment, ' || count(*) FILTER (WHERE p.status = 'APPROVED' AND p.proposed_by = :'refunder'
           AND p.decided_by = :'controller' AND p.refund_id = r.id) || ' of them approved so'
  FROM payments.refund_proposal p
  LEFT JOIN payments.refund r ON r.id = p.refund_id
 WHERE p.intent_id = :'payment'::uuid;
