-- P13-TSK-001: the role constraint learns RISK_ANALYST, RISK_OFFICER and AML_OFFICER.
--
-- The V013...V022 ceremony, performed again for the same reason: an applied CHECK is history that cannot be edited
-- (ADR-0011), so a role added to RoleName is a NEW migration replacing the constraint - and widening what the role table
-- accepts is a change to who may do anything privileged at all, so it should read like one. Three roles, because Phase
-- 13 has three distinct trust decisions (ADR-0107, ADR-0108...ADR-0111; PHASE_13_PLAN.md section 11): the risk analyst
-- investigates and approves another analyst's disposition, action or suppression (RISK_INVESTIGATE, RISK_CASE_APPROVE);
-- the risk officer administers policy versions and overrides decisions (RISK_POLICY_ADMINISTER, RISK_OVERRIDE); the
-- AML officer approves and files regulatory reports (AML_REPORT_APPROVE, AML_REPORT_FILE). Three disjoint desks, one
-- live role per identity: the investigation, the policy and the filing are held by three populations, and four eyes is
-- two holders of one permission, never one's own act. No role moves money. RoleNameTest holds the grants exact and
-- pairwise disjoint.
--
-- The list below is what RoleName.sqlValueList() generates; RoleAssignmentMigrationTest reconciles the LATEST
-- definition of this constraint against the enum, so the two cannot drift in either direction.
--
-- Existing rows all read one of the first fourteen names, all in the new list, so the constraint revalidates without
-- touching a row.
ALTER TABLE identity.role_assignment
    DROP CONSTRAINT role_assignment_role_is_known;

ALTER TABLE identity.role_assignment
    ADD CONSTRAINT role_assignment_role_is_known
        CHECK (role_name IN ('ADMINISTRATOR', 'KYC_REVIEWER', 'LEDGER_OPERATOR', 'MERCHANT_ADMINISTRATOR', 'RECONCILIATION_OPERATOR', 'RECONCILIATION_CONTROLLER', 'FX_CONTROLLER', 'CREDIT_POLICY_OFFICER', 'UNDERWRITER', 'LENDING_OFFICER', 'LENDING_TREASURY_OFFICER', 'LOAN_SERVICING_AGENT', 'BNPL_OFFICER', 'BNPL_SERVICING_AGENT', 'RISK_ANALYST', 'RISK_OFFICER', 'AML_OFFICER'));
