-- Every data request ends (the Phase 10 -> 11 transition; ADR-0085 section 11, INV-CRD-10).
--
-- V004's overdue report claimed only UNAVAILABLE requests past their deadline, and the retry claim took a REQUESTED
-- request with no deadline bound at all: an answer that could never be recorded (its record transaction refused, every
-- time) left the request REQUESTED, re-asked every cadence until the decision request expired - never UNAVAILABLE, never
-- the policy's fallback, no CreditDataUnavailable. Since this transition the retry claim takes a REQUESTED request only
-- before its deadline, and the report claims a REQUESTED request past it as well, moving it to UNAVAILABLE as it sets
-- unavailable_reported (JdbcCreditDataRequestStore.claimOverdue; both edges were already V004's machine).
--
-- This index serves that claim - status IN ('REQUESTED', 'UNAVAILABLE') AND NOT unavailable_reported, oldest deadline
-- first - and replaces V004's data_request_unreported, which covered UNAVAILABLE alone. Nothing else changes: no
-- column, no grant, no edge.

CREATE INDEX data_request_overdue ON credit.data_request (deadline_at, id)
    WHERE status IN ('REQUESTED', 'UNAVAILABLE') AND NOT unavailable_reported;

DROP INDEX credit.data_request_unreported;
