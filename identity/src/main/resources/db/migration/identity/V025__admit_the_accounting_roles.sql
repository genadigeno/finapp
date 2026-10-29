-- P14-TSK-001: the role constraint learns ACCOUNTANT, FINANCIAL_CONTROLLER and REGULATORY_REPORTER.
--
-- The V013...V023 ceremony, performed again for the same reason: an applied CHECK is history that cannot be edited
-- (ADR-0011), so a role added to RoleName is a NEW migration replacing the constraint - and widening what the role table
-- accepts is a change to who may do anything privileged at all, so it should read like one. (V024 was the Phase 13
-- completion gate's suspension cause; this is the next free number.) Three roles, because Phase 14 has three distinct
-- trust decisions (ADR-0113...ADR-0118; PHASE_14_PLAN.md section 12; owner decision C12): the accountant reads the GL and
-- proposes - a mapping version, a period's close, unlock or reopen, a prior-period adjustment - and generates reports
-- (ACCOUNTING_READ, ACCOUNTING_MAPPING_PROPOSE, ACCOUNTING_CLOSE_PROPOSE, ACCOUNTING_ADJUST_PROPOSE,
-- ACCOUNTING_REPORT_GENERATE); the financial controller approves those acts and a regulatory submission
-- (ACCOUNTING_MAPPING_APPROVE, ACCOUNTING_CLOSE_APPROVE, ACCOUNTING_ADJUST_APPROVE, REGULATORY_REPORT_APPROVE); the
-- regulatory reporter prepares and submits (REGULATORY_REPORT_PREPARE, REGULATORY_REPORT_SUBMIT). Eleven permissions,
-- three disjoint desks, no role holding the propose and the approve of one act. No role moves money.
--
-- The plan first gave ACCOUNTING_READ to all three; RoleNameTest holds every pair of grants disjoint (INV-AUD-03), so the
-- read is the accountant's and the controller and the reporter read what they act on under their own permissions (the
-- P13-TSK-001 precedent, which made the risk desks a partition). The grants are disjoint; their HOLDERS need not be -
-- the arbiter below is one live assignment of EACH role per identity - so approver <> proposer stays a CHECK on every
-- four-eyes row, each written by the task that builds the act (INV-AUD-04).
--
-- The list below is what RoleName.sqlValueList() generates; RoleAssignmentMigrationTest reconciles the LATEST
-- definition of this constraint against the enum, so the two cannot drift in either direction. Permissions are not
-- persisted: what a role means is code (ADR-0031).
--
-- Existing rows all read one of the first seventeen names, all in the new list, so the constraint revalidates without
-- touching a row.
ALTER TABLE identity.role_assignment
    DROP CONSTRAINT role_assignment_role_is_known;

ALTER TABLE identity.role_assignment
    ADD CONSTRAINT role_assignment_role_is_known
        CHECK (role_name IN ('ADMINISTRATOR', 'KYC_REVIEWER', 'LEDGER_OPERATOR', 'MERCHANT_ADMINISTRATOR', 'RECONCILIATION_OPERATOR', 'RECONCILIATION_CONTROLLER', 'FX_CONTROLLER', 'CREDIT_POLICY_OFFICER', 'UNDERWRITER', 'LENDING_OFFICER', 'LENDING_TREASURY_OFFICER', 'LOAN_SERVICING_AGENT', 'BNPL_OFFICER', 'BNPL_SERVICING_AGENT', 'RISK_ANALYST', 'RISK_OFFICER', 'AML_OFFICER', 'ACCOUNTANT', 'FINANCIAL_CONTROLLER', 'REGULATORY_REPORTER'));
