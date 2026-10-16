-- P12-TSK-002: the role constraint learns BNPL_OFFICER and BNPL_SERVICING_AGENT.
--
-- The V013/V014/V015/V017/V018/V019/V020/V021 ceremony, performed again for the same reason: an applied CHECK is
-- history that cannot be edited (ADR-0011), so a role added to RoleName is a NEW migration replacing the constraint -
-- and widening what the role table accepts is a change to who may do anything privileged at all, so it should read like
-- one. Two roles, because Phase 12 has two distinct trust decisions (ADR-0101, ADR-0102, ADR-0106; PHASE_12_PLAN.md
-- section 11): the BNPL officer sets the BNPL products' terms under four eyes and investigates what they produced
-- (BNPL_ADMINISTER, BNPL_INVESTIGATE); the BNPL servicing agent reads customers' obligations, proposes corrections,
-- approves another agent's - never one's own - and resolves the disputes a merchant answered (BNPL_SERVICE,
-- BNPL_SERVICE_APPROVE, BNPL_DISPUTE_RESOLVE). No role creates a purchase or finances a merchant. RoleNameTest holds the
-- grants exact and pairwise disjoint.
--
-- The list below is what RoleName.sqlValueList() generates; RoleAssignmentMigrationTest reconciles the LATEST
-- definition of this constraint against the enum, so the two cannot drift in either direction.
--
-- Existing rows all read one of the first twelve names, all in the new list, so the constraint revalidates without
-- touching a row.
ALTER TABLE identity.role_assignment
    DROP CONSTRAINT role_assignment_role_is_known;

ALTER TABLE identity.role_assignment
    ADD CONSTRAINT role_assignment_role_is_known
        CHECK (role_name IN ('ADMINISTRATOR', 'KYC_REVIEWER', 'LEDGER_OPERATOR', 'MERCHANT_ADMINISTRATOR', 'RECONCILIATION_OPERATOR', 'RECONCILIATION_CONTROLLER', 'FX_CONTROLLER', 'CREDIT_POLICY_OFFICER', 'UNDERWRITER', 'LENDING_OFFICER', 'LENDING_TREASURY_OFFICER', 'LOAN_SERVICING_AGENT', 'BNPL_OFFICER', 'BNPL_SERVICING_AGENT'));
