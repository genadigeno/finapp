-- One session per payment intent, at the database (P6-DOC-001, ADR-0053 section 3).
--
-- WHAT WAS MISSING
--   ADR-0053 section 3 names a partial unique index on payment_intent_ref, and V002 never
--   created one. Its set-once trigger stops a session's intent reference from CHANGING; nothing
--   stopped two sessions from carrying the SAME one. The capture completes its session by
--   finding it through this column (JdbcCheckoutSessionStore.findByIntentForUpdate, inside
--   every capture's transaction) and takes the first row it meets - so two rows would let
--   landed money complete whichever the scan found first, and with no index at all that scan
--   read the whole table under the capture's locks. Found by the Phase 6 exit review.
--
-- PARTIAL
--   An OPEN session carries no intent yet. The partial form keeps those rows out of the index
--   altogether, which is also what makes it the index the capture's lookup uses.
--
-- IN THE MIGRATION'S TRANSACTION, NOT CONCURRENTLY
--   DATA_MIGRATIONS.md section 4.4: CONCURRENTLY needs a non-transactional migration that can
--   leave an invalid index behind. This table holds short-lived offers and the platform has no
--   production data yet, so the brief lock of a plain build is the smaller risk.
CREATE UNIQUE INDEX checkout_session_one_session_per_intent
    ON checkout.checkout_session (payment_intent_ref)
    WHERE payment_intent_ref IS NOT NULL;

-- V002's comment said a retry after a failed payment re-uses the intent. It cannot: the intent
-- fails with its attempt (ADR-0045 section 4), and the reference is set once. Applied history
-- is never edited, so the column's description is corrected here.
COMMENT ON COLUMN checkout.checkout_session.payment_intent_ref IS
    'SET ONCE by the trigger, and UNIQUE across sessions by checkout_session_one_session_per_intent: one payment intent per session, one session per intent (ADR-0053 section 3). A failed payment is not retried on it - the intent fails with its attempt (ADR-0045 section 4) and the session ends by expiry.';
