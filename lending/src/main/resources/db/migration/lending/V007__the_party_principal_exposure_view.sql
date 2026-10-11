-- The exposure read's published surface (P11-TSK-013; PHASE_11_PLAN.md section 12.10, ADR-0091 points 5 to 7;
-- INV-CRD-09, INV-LND-06, INV-BAL-05).
--
-- Credit's exposure judgement must see every unit of principal the platform has committed, lent or promised on an open
-- line. app's LendingCreditExposure (credit's PlatformCreditExposure version 2) answers it in ONE SQL statement over this
-- view and the ledger's journal lines - never over a balance column, and never over lending's tables directly:
--
--     party_principal_exposure - one row per loan account of either kind: the loan, its party, the credit decision it
--                                consumed (by value), its kind, status and currency, and its LATEST agreement version's
--                                principal (a loan) or limit (a line) with the scale it was written at. Nothing else: no
--                                rate, term, canonical terms, evidence or stamp - the exposure read's columns only.
--
-- What the read does with the rows (app, not here, because the outstanding arms are ledger journal lines and a lending
-- view naming ledger tables would make lending's migration depend on ledger's - each module migrates its own schema in
-- its own Flyway history, in no guaranteed order, ADR-0006 and ADR-0011):
--
--     a loan PENDING_DISBURSEMENT counts this agreement amount (committed); every loan not CANCELLED counts
--     DR - CR of its LOAN_PRINCIPAL + LOAN_PRINCIPAL_DUE journal lines (outstanding); an ACTIVE line counts
--     GREATEST(this limit, its drawn principal); a CLOSING (or CLOSED) line its drawn principal. Principal only (A3).
--
-- Why a view, and why a definer view: the read is credit's, composed in app, and it must not grow into a second reader of
-- lending's tables - the view is the one surface it is granted, and it is the shape lending answers for. It runs with its
-- owner's rights (security_invoker = false, stated rather than left to the default), and its owner is the migrator,
-- which owns the tables it reads; the application role is granted SELECT on the view and nothing else here. It is not
-- updatable (a join), and no INSERT, UPDATE or DELETE is granted on it to anyone. Every object it names is
-- schema-qualified, so no caller's search_path changes what it reads.
--
-- A loan whose agreement were missing would read a NULL amount here rather than vanish from the sum - the reader refuses
-- it loudly (the deferred birth check of V006 makes it impossible at commit; the outer join makes a defect fail closed,
-- never a quiet zero).

CREATE VIEW lending.party_principal_exposure WITH (security_invoker = false) AS
SELECT l.id          AS loan_id,
       l.party_id,
       l.decision_id,
       l.kind,
       l.status,
       l.currency,
       a.scale        AS agreement_scale,
       a.amount_minor AS agreement_amount_minor
  FROM lending.loan l
  LEFT JOIN LATERAL (SELECT x.scale, x.amount_minor
                       FROM lending.loan_agreement x
                      WHERE x.loan_id = l.id
                      ORDER BY x.version DESC
                      LIMIT 1) a ON true;

COMMENT ON VIEW lending.party_principal_exposure IS
    'The exposure read''s one surface (P11-TSK-013, ADR-0091): per loan account, its party, consumed decision, kind, status, currency and its latest agreement version''s principal or limit with its scale - nothing else. A definer view owned by the migrator; SELECT to the application role only; read by app''s LendingCreditExposure (credit''s PlatformCreditExposure v2) in one statement beside the ledger''s journal lines, and by the exposure census. RESTRICTED-FINANCIAL (the agreement amount).';

GRANT SELECT ON lending.party_principal_exposure TO finapp_app;
