-- The Phase 7 -> 8 transition: ONE SCHEME EXECUTION, ONE MONEY FACT, PER RAIL.
--
-- The gate found the instant rail's scheme reference guarded by two per-table UNIQUEs and two
-- unlocked reads: a pay-in's credit and a suspense parking of the same execution could BOTH
-- commit (sequentially when the parking came second - nothing asked - and concurrently either
-- way), and a withdrawal's own confirmation echoed back to the pay-in door parked as INBOUND
-- money for value that went OUT. Every producer that records a scheme execution on a rail now
-- claims its (rail, scheme_reference) here, inside its own transaction, before any money
-- moves: the pay-in's execution, the withdrawal's completion, the return's completion and the
-- suspense parking. The PRIMARY KEY is the arbiter - race-free under INSERT ... ON CONFLICT DO
-- NOTHING, a concurrent claimant blocks on the in-progress row and then reads the winner - and
-- the loser's domain rule is its producer's (a parking converges or yields, a pay-in moves
-- nothing, a completion that happened anyway is recorded loud, never refused).
--
-- The parking gains what Phase 8 must resolve it with: the reference the statement named, its
-- settlement cycle, WHY it parked, the attempt it named when it named one, and a fifth
-- provider_evidence subject so its raw statement is found by stored identifier, not by
-- decrypting every unattributed row.

-- ------------------------------------------------------------------- the claim

CREATE TABLE payments.scheme_execution_claim (
    rail              text        NOT NULL
        CONSTRAINT scheme_execution_claim_rail_shape
            CHECK (rail ~ '^[a-z][a-z0-9-]{0,31}$'),
    scheme_reference  text        NOT NULL
        CONSTRAINT scheme_execution_claim_reference_shape
            CHECK (scheme_reference ~ '^[A-Za-z0-9_.:-]{1,128}$'),
    -- What the execution is explained by: the credited pay-in attempt, the completed
    -- withdrawal, the completed return (a refund row), or the suspense parking.
    subject_kind      text        NOT NULL
        CONSTRAINT scheme_execution_claim_subject_is_known
            CHECK (subject_kind IN ('PAY_IN', 'WITHDRAWAL', 'RETURN', 'UNMATCHED')),
    subject_id        uuid        NOT NULL,
    claimed_at        timestamptz NOT NULL,
    CONSTRAINT scheme_execution_claim_one_per_execution PRIMARY KEY (rail, scheme_reference)
);

CREATE FUNCTION payments.scheme_execution_claim_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'a scheme execution''s claim is the record of what explains it: claimed once, edited by nobody (the Phase 7 -> 8 transition, INV-REC-05, INV-HIST-02)';
END;
$$;

CREATE TRIGGER scheme_execution_claim_is_append_only
    BEFORE UPDATE OR DELETE ON payments.scheme_execution_claim
    FOR EACH ROW EXECUTE FUNCTION payments.scheme_execution_claim_is_append_only();

-- Backfill: every execution already recorded claims its reference, in the order the
-- producers' own UNIQUEs already guaranteed within each table. Where two tables already hold
-- one reference (the defect this migration closes), the credit or completion claims first
-- and the parking is left without a claim - the break stands visible in both tables for
-- Phase 8's resolution, exactly as it stood before.
INSERT INTO payments.scheme_execution_claim
    (rail, scheme_reference, subject_kind, subject_id, claimed_at)
SELECT rail, scheme_reference, 'PAY_IN', id, created_at
  FROM payments.payment_attempt
 WHERE scheme_reference IS NOT NULL
ON CONFLICT DO NOTHING;

INSERT INTO payments.scheme_execution_claim
    (rail, scheme_reference, subject_kind, subject_id, claimed_at)
SELECT rail, scheme_reference, 'WITHDRAWAL', id, created_at
  FROM payments.withdrawal
 WHERE scheme_reference IS NOT NULL
ON CONFLICT DO NOTHING;

INSERT INTO payments.scheme_execution_claim
    (rail, scheme_reference, subject_kind, subject_id, claimed_at)
SELECT a.rail, r.provider_reference, 'RETURN', r.id, r.created_at
  FROM payments.refund r
  JOIN payments.payment_attempt a ON a.id = r.attempt_id
 WHERE a.interaction_model = 'PUSH' AND r.provider_reference IS NOT NULL
ON CONFLICT DO NOTHING;

INSERT INTO payments.scheme_execution_claim
    (rail, scheme_reference, subject_kind, subject_id, claimed_at)
SELECT rail, scheme_reference, 'UNMATCHED', id, received_at
  FROM payments.unmatched_confirmation
ON CONFLICT DO NOTHING;

GRANT SELECT, INSERT ON payments.scheme_execution_claim TO finapp_app;

-- ------------------------------------------------------------ the parking's own facts

ALTER TABLE payments.unmatched_confirmation
    -- The end-to-end reference the statement named, when it had OUR minted shape - a
    -- mistyped reference, a concluded attempt's, or nothing at all (NULL).
    ADD COLUMN named_reference text
        CONSTRAINT unmatched_confirmation_named_reference_shape
            CHECK (named_reference ~ '^[A-Za-z0-9-]{1,35}$'),
    -- The scheme's settlement cycle the value rides in - Phase 8's matching key beside the
    -- scheme reference.
    ADD COLUMN settlement_cycle text
        CONSTRAINT unmatched_confirmation_settlement_cycle_bounded
            CHECK (length(settlement_cycle) BETWEEN 1 AND 64),
    -- WHY it parked: the statement named nothing we made (UNATTRIBUTED); it named an attempt
    -- already concluded - failed, or executed under ANOTHER scheme reference
    -- (ATTEMPT_CONCLUDED); or it named a waiting attempt but executed an amount other than
    -- the initiation's ask (AMOUNT_MISMATCH). Every row before this migration is the first.
    ADD COLUMN cause text NOT NULL DEFAULT 'UNATTRIBUTED'
        CONSTRAINT unmatched_confirmation_cause_is_known
            CHECK (cause IN ('UNATTRIBUTED', 'ATTEMPT_CONCLUDED', 'AMOUNT_MISMATCH')),
    -- The attempt the statement named, exactly when it named one.
    ADD COLUMN attempt_id uuid REFERENCES payments.payment_attempt (id),
    ADD CONSTRAINT unmatched_confirmation_attempt_exactly_when_attributed
        CHECK ((cause = 'UNATTRIBUTED') = (attempt_id IS NULL));

-- The default existed only to backfill the rows parked before the cause was recorded.
ALTER TABLE payments.unmatched_confirmation ALTER COLUMN cause DROP DEFAULT;

CREATE INDEX unmatched_confirmation_by_attempt
    ON payments.unmatched_confirmation (attempt_id)
    WHERE attempt_id IS NOT NULL;

-- ----------------------------------------------------------------- provider evidence

-- The parking joins the retained evidence as the FIFTH subject (V022's definition recreated
-- over five - the current definition lives here, ADR-0011): the delivery that parked value,
-- and every later delivery of the same execution, rests addressed to its parking.
ALTER TABLE payments.provider_evidence
    ADD COLUMN unmatched_confirmation_id uuid
        REFERENCES payments.unmatched_confirmation (id),
    DROP CONSTRAINT provider_evidence_has_at_most_one_subject,
    ADD CONSTRAINT provider_evidence_has_at_most_one_subject
        CHECK (num_nonnulls(attempt_id, refund_id, withdrawal_id, dispute_response_id,
                            unmatched_confirmation_id) <= 1);

CREATE INDEX provider_evidence_by_unmatched_confirmation
    ON payments.provider_evidence (unmatched_confirmation_id)
    WHERE unmatched_confirmation_id IS NOT NULL;

COMMENT ON TABLE payments.scheme_execution_claim IS
    'One scheme execution, one money fact, per rail: every producer claims (rail, scheme_reference) before money moves (the Phase 7 -> 8 transition).';
