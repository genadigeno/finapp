-- P8-TSK-007: the role constraint learns RECONCILIATION_CONTROLLER.
--
-- Numbered V017 on its branch; renumbered V018 when the branch merged, identity V016 being
-- X-TSK-007's session migration, already on the main line.
--
-- The V013/V014/V015/V017 ceremony, performed again for the same reason: an applied CHECK is
-- history that cannot be edited (ADR-0011), so a role added to RoleName is a NEW migration
-- replacing the constraint - and widening what the role table accepts is a change to who may
-- do anything privileged at all, so it should read like one. This role controls the
-- reconciliation register (RECONCILIATION_ADMINISTER - today the opening-position backfill,
-- ADR-0067 section 8): adopting history into the register decides what every proof and break
-- is judged against, a distinct trust decision from feeding evidence in or investigating it
-- (owner decision O1's second, disjoint role), which is why it is a sixth role rather than a
-- widening of RECONCILIATION_OPERATOR.
--
-- The list below is what RoleName.sqlValueList() generates; RoleAssignmentMigrationTest
-- reconciles the LATEST definition of this constraint against the enum, so the two cannot
-- drift in either direction.
--
-- Existing rows all read one of the first five names, all in the new list, so the
-- constraint revalidates without touching a row.
ALTER TABLE identity.role_assignment
    DROP CONSTRAINT role_assignment_role_is_known;

ALTER TABLE identity.role_assignment
    ADD CONSTRAINT role_assignment_role_is_known
        CHECK (role_name IN ('ADMINISTRATOR', 'KYC_REVIEWER', 'LEDGER_OPERATOR', 'MERCHANT_ADMINISTRATOR', 'RECONCILIATION_OPERATOR', 'RECONCILIATION_CONTROLLER'));
