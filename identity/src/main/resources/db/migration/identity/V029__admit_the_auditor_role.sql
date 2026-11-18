-- finapp-contract: first-conforming-release=ANY; waives=DROP,CHECK; reason=the replaced check admits every role name the old one did and AUDITOR besides, so no row a release has written and no write a release makes is refused by it
--
-- The auditor role (P17-TSK-017; finapp-admin-panel ADR-C134 decision 2): the read-only role that holds AUDIT_READ, the audit
-- trail's read, and nothing else.
--
-- WHAT IT CHANGES
--   identity.role_assignment's check of the role names it admits, replaced by one admitting AUDITOR as well - the shape of every
--   role's admission since V013. The new list is a superset of the old one: every row stands, and every grant N - 1 makes is still
--   admitted, which is the marker's reason. Nothing else: no row, no column, no grant.
--
-- NOT VALID, AND WHY NOTHING IS LOST
--   The new check is enforced on every insert and update from this migration on, and judges no existing row: every one passed the
--   old check, whose list this one contains, so none can fail it - and a check judged over old rows is the lint's
--   TIGHTENED_OVER_OLD_ROWS (DATA_MIGRATIONS.md section 4.1). A later release may VALIDATE it under SHARE UPDATE EXCLUSIVE.
--
-- LOCKS
--   A brief ACCESS EXCLUSIVE on identity.role_assignment for the catalogue change, no scan. In the migration's transaction.

ALTER TABLE identity.role_assignment
    DROP CONSTRAINT role_assignment_role_is_known;

ALTER TABLE identity.role_assignment
    ADD CONSTRAINT role_assignment_role_is_known
        CHECK (role_name IN ('ADMINISTRATOR', 'KYC_REVIEWER', 'LEDGER_OPERATOR', 'MERCHANT_ADMINISTRATOR', 'RECONCILIATION_OPERATOR', 'RECONCILIATION_CONTROLLER', 'FX_CONTROLLER', 'CREDIT_POLICY_OFFICER', 'UNDERWRITER', 'LENDING_OFFICER', 'LENDING_TREASURY_OFFICER', 'LOAN_SERVICING_AGENT', 'BNPL_OFFICER', 'BNPL_SERVICING_AGENT', 'RISK_ANALYST', 'RISK_OFFICER', 'AML_OFFICER', 'ACCOUNTANT', 'FINANCIAL_CONTROLLER', 'REGULATORY_REPORTER', 'EVENT_OPERATOR', 'AUDITOR')) NOT VALID;
