-- P15-TSK-028: the role constraint learns EVENT_OPERATOR.
--
-- The V013...V025 ceremony, for the same reason: an applied CHECK is history that cannot be edited (ADR-0011), so a role added
-- to RoleName is a NEW migration replacing the constraint. One role, because this is a distinct trust decision: whoever retries,
-- discards or replays the platform's events operates its plumbing, and moves no money, reads no book and decides no customer.
-- It holds one permission, EVENT_OPERATE. The four eyes on a discard are two holders of this one role, and the second person is
-- a CHECK on platform.dead_letter_discard (INV-AUD-04).
--
-- The list below is what RoleName.sqlValueList() generates; RoleAssignmentMigrationTest reconciles the LATEST definition of this
-- constraint against the enum. Every existing row reads one of the first twenty names, so the constraint revalidates without
-- touching a row. Widening the accepted set cannot refuse a write release N - 1 makes.
ALTER TABLE identity.role_assignment
    DROP CONSTRAINT role_assignment_role_is_known;

ALTER TABLE identity.role_assignment
    ADD CONSTRAINT role_assignment_role_is_known
        CHECK (role_name IN ('ADMINISTRATOR', 'KYC_REVIEWER', 'LEDGER_OPERATOR', 'MERCHANT_ADMINISTRATOR', 'RECONCILIATION_OPERATOR', 'RECONCILIATION_CONTROLLER', 'FX_CONTROLLER', 'CREDIT_POLICY_OFFICER', 'UNDERWRITER', 'LENDING_OFFICER', 'LENDING_TREASURY_OFFICER', 'LOAN_SERVICING_AGENT', 'BNPL_OFFICER', 'BNPL_SERVICING_AGENT', 'RISK_ANALYST', 'RISK_OFFICER', 'AML_OFFICER', 'ACCOUNTANT', 'FINANCIAL_CONTROLLER', 'REGULATORY_REPORTER', 'EVENT_OPERATOR'));
