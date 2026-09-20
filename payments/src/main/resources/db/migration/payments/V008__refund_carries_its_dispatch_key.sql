-- The refund carries its dispatch key (P5-TSK-016).
--
-- The refund is the platform's first TWO-TRANSACTION keyed command: the idempotency claim and
-- the dispatch (row, hold, audit) commit together in Tx1 with the claim still IN_PROGRESS,
-- and the claim COMPLETES in Tx2 with the judged response - so a replay renders the response
-- of record byte-for-byte, exactly as platform.V003's freeze rule already promises
-- ("the stored response is what a replay renders", INV-LIFE-04). What that shape needs is an
-- arbiter for the crash between the transactions: the lease expires, the retry takes the
-- claim over and re-runs the dispatch - and the re-run must CONVERGE on the committed work
-- (find this row, place no second hold, re-drive the wire with the reference already stored -
-- INV-PAY-04's whole point) rather than dispatch a second refund. The claim row cannot carry
-- the linkage (its response is writable only at completion, and frozen after); the refund row
-- can, and the fact is the refund's own: WHICH dispatch does this key own.
--
-- Nullable, deliberately: rows born before this migration carry no key (applied history,
-- ADR-0011 - P5-TSK-015's rows exist in development databases), and a legacy NULL simply
-- never matches a convergence lookup. The partial unique index is the backstop beneath the
-- claim's own arbitration: two takeovers racing the same key cannot both insert.

ALTER TABLE payments.refund
    ADD COLUMN dispatch_key text;

COMMENT ON COLUMN payments.refund.dispatch_key IS
    'The idempotency claim (scope payment.refund) whose Tx1 created this row. The takeover '
    're-run''s convergence lookup (P5-TSK-016); NULL only on rows born before V008.';

CREATE UNIQUE INDEX refund_one_row_per_dispatch_key
    ON payments.refund (dispatch_key)
    WHERE dispatch_key IS NOT NULL;
