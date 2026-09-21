-- P6-TSK-003: the role constraint learns MERCHANT_ADMINISTRATOR.
--
-- The V013/V014 ceremony, performed again for the same reason: V010's applied CHECK is
-- history that cannot be edited (ADR-0011), so a role added to RoleName is a NEW migration
-- replacing the constraint - and widening what the role table accepts is a change to who may
-- do anything privileged at all, so it should read like one. This role administers commercial
-- counterparties (MERCHANT_ONBOARD, MERCHANT_ADMINISTER) - a distinct trust decision from
-- managing identities, reviewing cases and operating the money, which is why it is a fourth
-- role rather than a widening of any existing one.
--
-- The list below is what RoleName.sqlValueList() generates; RoleAssignmentMigrationTest
-- reconciles the LATEST definition of this constraint against the enum, so the two cannot
-- drift in either direction.
--
-- Existing rows all read one of the first three names, all in the new list, so the
-- constraint revalidates without touching a row.
ALTER TABLE identity.role_assignment
    DROP CONSTRAINT role_assignment_role_is_known;

ALTER TABLE identity.role_assignment
    ADD CONSTRAINT role_assignment_role_is_known
        CHECK (role_name IN ('ADMINISTRATOR', 'KYC_REVIEWER', 'LEDGER_OPERATOR', 'MERCHANT_ADMINISTRATOR'));
