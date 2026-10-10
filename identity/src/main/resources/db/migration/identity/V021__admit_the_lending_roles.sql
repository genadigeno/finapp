-- P11-TSK-002: the role constraint learns LENDING_OFFICER, LENDING_TREASURY_OFFICER and LOAN_SERVICING_AGENT.
--
-- The V013/V014/V015/V017/V018/V019/V020 ceremony, performed again for the same reason: an applied
-- CHECK is history that cannot be edited (ADR-0011), so a role added to RoleName is a NEW migration
-- replacing the constraint - and widening what the role table accepts is a change to who may do
-- anything privileged at all, so it should read like one. Three roles, because Phase 11 has three
-- distinct trust decisions (ADR-0090, ADR-0096, ADR-0099): the lending officer sets the loan
-- products' terms under four eyes and investigates what they produced (LENDING_ADMINISTER,
-- LENDING_INVESTIGATE); the treasury officer commits the platform's capital to lend under four eyes
-- (LENDING_CAPITAL_ADMINISTER); the servicing agent reads borrowers' accounts, proposes corrections
-- and approves another agent's - never one's own (LOAN_SERVICE, LOAN_SERVICE_APPROVE). RoleNameTest
-- holds the grants exact and pairwise disjoint.
--
-- The list below is what RoleName.sqlValueList() generates; RoleAssignmentMigrationTest
-- reconciles the LATEST definition of this constraint against the enum, so the two cannot drift
-- in either direction.
--
-- Existing rows all read one of the first nine names, all in the new list, so the constraint
-- revalidates without touching a row.
ALTER TABLE identity.role_assignment
    DROP CONSTRAINT role_assignment_role_is_known;

ALTER TABLE identity.role_assignment
    ADD CONSTRAINT role_assignment_role_is_known
        CHECK (role_name IN ('ADMINISTRATOR', 'KYC_REVIEWER', 'LEDGER_OPERATOR', 'MERCHANT_ADMINISTRATOR', 'RECONCILIATION_OPERATOR', 'RECONCILIATION_CONTROLLER', 'FX_CONTROLLER', 'CREDIT_POLICY_OFFICER', 'UNDERWRITER', 'LENDING_OFFICER', 'LENDING_TREASURY_OFFICER', 'LOAN_SERVICING_AGENT'));
