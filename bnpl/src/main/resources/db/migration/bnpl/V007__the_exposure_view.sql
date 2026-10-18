-- The exposure read's one bnpl surface (P12-TSK-012; PHASE_12_PLAN.md section 12.10, ADR-0104; INV-BNPL-10, INV-CRD-09,
-- INV-BAL-05; lending V007's shape).
--
-- Credit's PlatformCreditExposure version 3 (app's BnplCreditExposure) reads, in ONE SQL statement inside credit's
-- deciding transaction under the party's profile lock (L0), lending's lending.party_principal_exposure, this view and the
-- ledger's journal lines - never account_balance. This view is per obligation and bnpl-only: the obligation, its party,
-- its consumed decision, product, status and currency - the read's columns and nothing else. No ledger table is named
-- here (the P11-TSK-013 refinement): each module migrates its own schema in no guaranteed order (ADR-0006/0011), so the
-- principal lines are joined by app's statement, by the obligation's id as the accounts' owner.
--
-- Every obligation is listed whatever its status: a CLOSED obligation's principal is zero by the closure rule, so it adds
-- nothing - and a defect leaving principal on a closed obligation is still exposure (lending's as-built choice; the
-- plan's "not CLOSED" is the same figure at rest).
--
-- A definer view owned by the migrator (security_invoker = false stated), SELECT to the application role alone, a join-free
-- projection the application role cannot write (no INSERT, UPDATE or DELETE granted); every name schema-qualified.

CREATE VIEW bnpl.party_principal_exposure WITH (security_invoker = false) AS
SELECT o.id          AS obligation_id,
       o.party_id,
       o.decision_id,
       o.product,
       o.status,
       o.currency
  FROM bnpl.bnpl_obligation o;

COMMENT ON VIEW bnpl.party_principal_exposure IS
    'The exposure read''s bnpl surface (P12-TSK-012, ADR-0104): per BNPL obligation, its party, consumed decision, product, status and currency - nothing else, no figure. A definer view owned by the migrator; SELECT to the application role only; read by app''s BnplCreditExposure (credit''s PlatformCreditExposure v3) in one statement beside lending''s view and the ledger''s journal lines, and by the exposure census. CONFIDENTIAL (party and decision references).';

GRANT SELECT ON bnpl.party_principal_exposure TO finapp_app;
