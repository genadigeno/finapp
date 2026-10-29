-- The Phase 13 completion gate (2026-10-29; owner item 25, P13-TSK-017 MINOR 5, ratified "fix before production use";
-- the gate's check that risk is never an alternative owner of identity state; INV-RSK-02, ADR-0112 decision 2).
--
-- THE GAP
--   Risk's REINSTATE_IDENTITY action calls identity's reinstate over every identity of the party, and identity recorded
--   no cause of a suspension: an administrator's suspension (a compromised login, an employee's exit) was lifted by a
--   risk case's action as readily as risk's own. Risk had become a second owner of a decision identity made.
--
-- THE RULE (identity's own; the code is IdentityAdministration#suspend/#reinstate and JdbcIdentityStore#moveStatus)
--   Identity records WHO suspended - the cause - beside the status, in the same conditional write:
--     ADMINISTRATOR - identity's administration door (IdentityAdministrationController, IDENTITY_SUSPEND);
--     RISK          - risk's SUSPEND_IDENTITY action, through identity's own command.
--   An administrator's reinstatement lifts any suspension (identity's own authority). Risk's lifts ONLY a RISK one: the
--   conditional UPDATE names the cause (... AND suspension_cause = 'RISK'), so an administrator's suspension is left
--   standing and risk's action is REFUSED. A suspension recorded before this migration has no cause (NULL) and is
--   lifted by an administrator only - fail closed: no row is back-filled with a cause nobody recorded.
--
-- No grant changes: UPDATE on identity.identity is V002's, for the status transitions - the cause is part of one.

ALTER TABLE identity.identity ADD COLUMN suspension_cause text;

ALTER TABLE identity.identity
    ADD CONSTRAINT identity_suspension_cause_is_known
        CHECK (suspension_cause IS NULL OR suspension_cause IN ('ADMINISTRATOR', 'RISK')),
    ADD CONSTRAINT identity_suspension_cause_only_while_suspended
        CHECK (suspension_cause IS NULL OR status = 'SUSPENDED');

COMMENT ON COLUMN identity.identity.suspension_cause IS
    'Who suspended the identity: ADMINISTRATOR or RISK; NULL when not suspended, or suspended before V024 (lifted by an administrator only). Risk''s reinstatement lifts a RISK suspension only (the Phase 13 gate, item 25).';
