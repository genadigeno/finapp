-- P3-TSK-007: the role constraint learns LEDGER_OPERATOR.
--
-- The V013 ceremony, performed again for the same reason: V010's applied CHECK is history that
-- cannot be edited (ADR-0011), so a role added to RoleName is a NEW migration replacing the
-- constraint - and widening what the role table accepts is a change to who may do anything
-- privileged at all, so it should read like one.
--
-- The list below is what RoleName.sqlValueList() generates; RoleAssignmentMigrationTest
-- reconciles the LATEST definition of this constraint against the enum, so the two cannot
-- drift in either direction - and it separately pins V010's original literal, because history
-- keeping its shape is its own claim.
--
-- Existing rows all read 'ADMINISTRATOR' or 'KYC_REVIEWER', both in the new list, so the
-- constraint revalidates without touching a row.
ALTER TABLE identity.role_assignment
    DROP CONSTRAINT role_assignment_role_is_known;

ALTER TABLE identity.role_assignment
    ADD CONSTRAINT role_assignment_role_is_known
        CHECK (role_name IN ('ADMINISTRATOR', 'KYC_REVIEWER', 'LEDGER_OPERATOR'));
