-- P10-TSK-003: the role constraint learns CREDIT_POLICY_OFFICER and UNDERWRITER.
--
-- The V013/V014/V015/V017/V018/V019 ceremony, performed again for the same reason: an applied CHECK
-- is history that cannot be edited (ADR-0011), so a role added to RoleName is a NEW migration
-- replacing the constraint - and widening what the role table accepts is a change to who may do
-- anything privileged at all, so it should read like one. Two roles, because Phase 10 has two
-- distinct trust decisions (ADR-0084): the credit policy officer writes the lending policy and the
-- scorecard under four eyes and investigates what they decided (CREDIT_POLICY_ADMINISTER,
-- CREDIT_INVESTIGATE); the underwriter decides the cases the policy refers (CREDIT_UNDERWRITE). The
-- policy's author is not the queue's decider - RoleNameTest holds the grants exact and disjoint.
--
-- The list below is what RoleName.sqlValueList() generates; RoleAssignmentMigrationTest
-- reconciles the LATEST definition of this constraint against the enum, so the two cannot drift
-- in either direction.
--
-- Existing rows all read one of the first seven names, all in the new list, so the constraint
-- revalidates without touching a row.
ALTER TABLE identity.role_assignment
    DROP CONSTRAINT role_assignment_role_is_known;

ALTER TABLE identity.role_assignment
    ADD CONSTRAINT role_assignment_role_is_known
        CHECK (role_name IN ('ADMINISTRATOR', 'KYC_REVIEWER', 'LEDGER_OPERATOR', 'MERCHANT_ADMINISTRATOR', 'RECONCILIATION_OPERATOR', 'RECONCILIATION_CONTROLLER', 'FX_CONTROLLER', 'CREDIT_POLICY_OFFICER', 'UNDERWRITER'));
