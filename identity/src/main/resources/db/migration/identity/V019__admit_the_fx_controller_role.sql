-- P9-TSK-007: the role constraint learns FX_CONTROLLER.
--
-- The V013/V014/V015/V017/V018 ceremony, performed again for the same reason: an applied CHECK
-- is history that cannot be edited (ADR-0011), so a role added to RoleName is a NEW migration
-- replacing the constraint - and widening what the role table accepts is a change to who may do
-- anything privileged at all, so it should read like one. This role sets FX prices and
-- availability (FX_ADMINISTER, ADR-0075 section 7): the margin every conversion freezes and posts
-- is the platform's revenue policy, a distinct trust decision from operating the money or judging
-- it, which is why it is a seventh role rather than a widening of an existing one.
--
-- The list below is what RoleName.sqlValueList() generates; RoleAssignmentMigrationTest
-- reconciles the LATEST definition of this constraint against the enum, so the two cannot drift
-- in either direction.
--
-- Existing rows all read one of the first six names, all in the new list, so the constraint
-- revalidates without touching a row.
ALTER TABLE identity.role_assignment
    DROP CONSTRAINT role_assignment_role_is_known;

ALTER TABLE identity.role_assignment
    ADD CONSTRAINT role_assignment_role_is_known
        CHECK (role_name IN ('ADMINISTRATOR', 'KYC_REVIEWER', 'LEDGER_OPERATOR', 'MERCHANT_ADMINISTRATOR', 'RECONCILIATION_OPERATOR', 'RECONCILIATION_CONTROLLER', 'FX_CONTROLLER'));
