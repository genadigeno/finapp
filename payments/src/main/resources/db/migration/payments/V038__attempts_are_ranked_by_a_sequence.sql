-- An intent's attempt is named by its place in the order attempts were made, never by a stamp a stepped clock can reorder
-- (P15-TSK-037; ADR-0063 decision 2; THREAT_MODEL.md T13; DISTRIBUTED_EXECUTION.md section 3's clock-step rule). The number is V038:
-- V037 is the last payments migration (Flyway outOfOrder = false).
--
-- WHY THE STAMP WAS WRONG
--   JdbcPaymentAttemptStore.findForIntent (the confirmation's, the refund's, the void's and the surfaces') ranked an intent's
--   attempts by created_at DESC. The stamp is the application's clock (P5-TSK-001's rule: application-supplied, never DEFAULT
--   now()), and one attempt per intent ever exists today - the intent's one-way machine - but the schema admits more
--   (payment_attempt_one_live_per_intent), and the moment one exists a step between two inserts reverses "the" attempt the
--   refund and the void read.
--
-- WHAT THIS DOES
--   payments.attempt_order             a sequence: nextval is taken in call order whatever the clock does.
--   payment_attempt.attempt_order      DEFAULT nextval. Safe as a rank because the insert follows the serializing write: an
--                                      attempt is inserted after the intent's conditional transition
--                                      (REQUIRES_CONFIRMATION -> PROCESSING), one row count admitting one dispatcher, and a
--                                      book attempt the same way, so a later attempt of an intent draws a number after every
--                                      earlier attempt of that intent committed.
--
-- RELEASE N-1 AND ITS ROWS
--   The column is added with no default and the default set afterwards, so no table rewrite assigns numbers to existing rows (a
--   volatile DEFAULT inside ADD COLUMN would, in heap order, which is not attempt order): a row written before this migration
--   stays NULL and ranks below every row that has a number, by its stamp, as before (INV-HIST-01: history is not rewritten; the
--   platform holds no production rows). An insert from an instance still on the previous release omits the column and draws
--   the default, so it ranks exactly as a new instance's row does: nothing waits for the rollout to finish. No writer names the
--   column and no UPDATE grant covers it.
--
-- Migrator-owned; the application role draws from the sequence (USAGE). Nothing here writes money.

CREATE SEQUENCE payments.attempt_order AS bigint NO CYCLE;

COMMENT ON SEQUENCE payments.attempt_order IS
    'The order payment attempts were made in (P15-TSK-037) - drawn by the attempt_order default at insert, clock-independent.';

GRANT USAGE ON SEQUENCE payments.attempt_order TO finapp_app;

ALTER TABLE payments.payment_attempt ADD COLUMN attempt_order bigint;
ALTER TABLE payments.payment_attempt ALTER COLUMN attempt_order SET DEFAULT nextval('payments.attempt_order');

COMMENT ON COLUMN payments.payment_attempt.attempt_order IS
    'The attempt''s place in the order attempts of an intent were made (payments.attempt_order, drawn at insert after the intent''s row lock; P15-TSK-037). NULL for an attempt written before V038. The rank of "the attempt", never a clock.';
