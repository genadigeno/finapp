-- P2-TSK-004: the role constraint learns KYC_REVIEWER.
--
-- V010's CHECK was generated from RoleName when the enum held one value, and an applied
-- migration is history that cannot be edited (ADR-0011): the database and the repository must
-- keep agreeing about what V010 said. So a role added to the enum is a NEW migration replacing
-- the constraint - which is exactly the ceremony the design wants, because widening what the
-- role table accepts is a change to who may do anything privileged at all, and it should read
-- like one.
--
-- The list below is what RoleName.sqlValueList() generates; RoleAssignmentMigrationTest
-- reconciles the LATEST definition of this constraint against the enum, so the two cannot
-- drift in either direction - and it separately pins V010's original literal, because history
-- keeping its shape is its own claim.
--
-- Existing rows all read 'ADMINISTRATOR', which the new list contains, so the constraint
-- revalidates without touching a row.
ALTER TABLE identity.role_assignment
    DROP CONSTRAINT role_assignment_role_is_known;

ALTER TABLE identity.role_assignment
    ADD CONSTRAINT role_assignment_role_is_known
        CHECK (role_name IN ('ADMINISTRATOR', 'KYC_REVIEWER'));
