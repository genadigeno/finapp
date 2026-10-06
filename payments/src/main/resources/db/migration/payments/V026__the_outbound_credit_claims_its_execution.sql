-- =============================================================================================
-- P9-TSK-020 - one scheme execution, one money fact, for the cross-border outbound credit
-- (PHASE_9_PLAN.md sections 12.4(g) and 12.8; INV-PAY-01, INV-SET-02).
--
-- The outbound credit's completion claims its corridor execution - (rail, the provider's reference) -
-- exactly as a withdrawal claims its scheme execution (V023): the claim's primary key is one of the three
-- arbiters that make ten completion appliers post one entry and one fee line. The subject vocabulary
-- gains OUTBOUND_CREDIT; the table stays append-only for every writer.
-- =============================================================================================

ALTER TABLE payments.scheme_execution_claim
    DROP CONSTRAINT scheme_execution_claim_subject_is_known,
    ADD CONSTRAINT scheme_execution_claim_subject_is_known
        CHECK (subject_kind IN ('PAY_IN', 'WITHDRAWAL', 'RETURN', 'UNMATCHED', 'OUTBOUND_CREDIT'));

COMMENT ON COLUMN payments.scheme_execution_claim.subject_kind IS
    'What the execution is explained by: a pay-in, a withdrawal, a return payment, a suspense parking (V023) or a cross-border outbound credit (V026, P9-TSK-020).';
