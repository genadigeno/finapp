-- P8-TSK-003: the role constraint learns RECONCILIATION_OPERATOR.
--
-- Numbered V016 on its branch; renumbered V017 when the branch merged, identity V016 being
-- X-TSK-007's session migration, already on the main line.
--
-- The V013/V014/V015 ceremony, performed again for the same reason: an applied CHECK is
-- history that cannot be edited (ADR-0011), so a role added to RoleName is a NEW migration
-- replacing the constraint - and widening what the role table accepts is a change to who may
-- do anything privileged at all, so it should read like one. This role runs settlement and
-- reconciliation (SETTLEMENT_INGEST, RECONCILIATION_INVESTIGATE) - judging whether the outside
-- world's account of the money matches ours is a distinct trust decision from operating the
-- money, whose desk's work this population checks (ADR-0066, owner decision O1), which is why
-- it is a fifth role rather than a widening of LEDGER_OPERATOR.
--
-- The list below is what RoleName.sqlValueList() generates; RoleAssignmentMigrationTest
-- reconciles the LATEST definition of this constraint against the enum, so the two cannot
-- drift in either direction.
--
-- Existing rows all read one of the first four names, all in the new list, so the
-- constraint revalidates without touching a row.
ALTER TABLE identity.role_assignment
    DROP CONSTRAINT role_assignment_role_is_known;

ALTER TABLE identity.role_assignment
    ADD CONSTRAINT role_assignment_role_is_known
        CHECK (role_name IN ('ADMINISTRATOR', 'KYC_REVIEWER', 'LEDGER_OPERATOR', 'MERCHANT_ADMINISTRATOR', 'RECONCILIATION_OPERATOR'));
