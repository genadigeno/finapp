-- P4-TSK-010: the failure-reason constraint learns the seam refusals.
--
-- The V013/V014 ceremony, performed on a genuine column constraint: V002's applied CHECK is
-- history that cannot be edited (ADR-0011), so reasons added to FailureReason are a NEW
-- migration replacing the constraint - and widening what a committed FAILED may say is a
-- change to the refusal vocabulary customers and investigators read, so it should read like
-- one.
--
-- LIMIT_REFUSED and RISK_REFUSED are the two seams' reserved outcomes (TransferLimitCheck /
-- TransferRiskDecision, ROADMAP.md section Refinement 2): the execution commits a seam's
-- REFUSE verdict as its seam's own reason, so Phase 13's real implementations change no
-- contract - not the ports, not this column, not the HTTP shape. Nothing produces them in
-- production until Phase 13; the execution's mapping arms are exercised today by a refusing
-- test decorator, because a value with no exercised producer is a branch somebody eventually
-- writes code for (ADR-0044's doctrine).
--
-- The list below is what FailureReason.sqlValueList() generates; TransferMigrationTest
-- reconciles the LATEST definition of this constraint against the enum, so the two cannot
-- drift in either direction - and it separately pins V002's original five-value literal,
-- because history keeping its shape is its own claim.
--
-- Existing rows all read one of the original five, so the constraint revalidates without
-- touching a row. The pair rule (SELF_TRANSFER <=> equal accounts) and the transition trigger
-- are untouched: both new reasons demand an unequal pair, which the <=> already gives, and
-- the aggregate's constructor enforces it for every non-SELF_TRANSFER reason.
ALTER TABLE transfers.transfer
    DROP CONSTRAINT transfer_failure_reason_is_known;

ALTER TABLE transfers.transfer
    ADD CONSTRAINT transfer_failure_reason_is_known
        CHECK (failure_reason IN ('INSUFFICIENT_FUNDS', 'SOURCE_NOT_POSTABLE', 'DESTINATION_NOT_POSTABLE', 'CURRENCY_MISMATCH', 'SELF_TRANSFER', 'LIMIT_REFUSED', 'RISK_REFUSED'));
