-- Which check of a type and which case of a customer is "the latest" is decided by the rows, never by a stamp a stepped clock can
-- reorder (P15-TSK-037; ADR-0063 decision 2; THREAT_MODEL.md T13; DISTRIBUTED_EXECUTION.md section 3's clock-step rule). The number
-- is V012: V011 is the last kyc migration (Flyway outOfOrder = false).
--
-- WHY THE STAMPS WERE WRONG
--   kyc.verification_check's requested_at is stamped by a clock - an instance's for an onboarding check (JdbcCheckStore.insert
--   binds the domain's instant, V004), the database's for a rescreen (requestRescreen) - and JdbcCheckStore.newestOfType and
--   CheckStore.latestDecidedScreenings ranked the checks by requested_at DESC: a skew or a backward step between two asks lets an earlier
--   check read newer. The newest decides whether a successor is asked (a terminal INDETERMINATE under the budget) and which answer
--   risk's T-eval reads (a HIT masked by an earlier CLEAR reads as clear). kyc.kyc_case had no stamp in its rank but a UUIDv7
--   id minted from an instance's clock (ADR-0013): time-ordered only while no instance's clock moved.
--
-- WHAT THIS DOES - one order per table, drawn at the insert, ranked by the rows
--   kyc.check_order, kyc.case_order    a sequence each: nextval is taken in call order whatever a clock does.
--   verification_check.check_order     DEFAULT nextval, evaluated when the insert starts. Safe as a rank because a successor's insert
--                                      starts after its predecessor's: the run inserts a successor only once it has read the
--                                      predecessor terminal, and the one-in-flight index refuses any overlap, so a successor that
--                                      lands draws a number after it. A rescreen (keyed per list version, not per one-in-flight) asks
--                                      an independent question: two concurrent ones are concurrent asks, and either rank
--                                      is an order of asking - a clock cannot reverse it.
--   kyc_case.case_order                DEFAULT nextval, under kyc_case_one_open_per_customer: a customer's next case's insert starts
--                                      after the last one is decided, the index refusing any overlap, so a case that lands draws a
--                                      number after it.
--
-- RELEASE N-1 AND ITS ROWS
--   The column is added with no default and the default set afterwards, so no table rewrite assigns numbers to existing rows
--   (a volatile DEFAULT inside ADD COLUMN would, in heap order, which is not ask order): a row written before this migration
--   stays NULL and ranks below every row that has a number, by its stamps, as before (INV-HIST-01: history is not rewritten; the
--   platform holds no production rows). An insert from an instance still on the previous release omits the column and draws the
--   default, so it ranks exactly as a new instance's row does: nothing waits for the rollout to finish. Nothing updates the
--   column: no writer names it, and verification_check's UPDATE grant is on its status columns only.
--
-- Migrator-owned; the application role draws from the sequences (USAGE). Nothing here writes money. The indexes are built in the
-- migration's transaction, not CONCURRENTLY (DATA_MIGRATIONS.md section 4.4, the lending V033 precedent).

CREATE SEQUENCE kyc.check_order AS bigint NO CYCLE;

COMMENT ON SEQUENCE kyc.check_order IS
    'The order a verification check was asked in (P15-TSK-037) - drawn by the check_order default at insert, clock-independent.';

GRANT USAGE ON SEQUENCE kyc.check_order TO finapp_app;

ALTER TABLE kyc.verification_check ADD COLUMN check_order bigint;
ALTER TABLE kyc.verification_check ALTER COLUMN check_order SET DEFAULT nextval('kyc.check_order');

COMMENT ON COLUMN kyc.verification_check.check_order IS
    'The check''s place in its asking order (kyc.check_order, drawn at insert; P15-TSK-037). NULL for a check written before V012. The rank of "newest" and "latest decided", never a clock.';

CREATE SEQUENCE kyc.case_order AS bigint NO CYCLE;

COMMENT ON SEQUENCE kyc.case_order IS
    'The order a KYC case was opened in (P15-TSK-037) - drawn by the case_order default at insert, clock-independent.';

GRANT USAGE ON SEQUENCE kyc.case_order TO finapp_app;

ALTER TABLE kyc.kyc_case ADD COLUMN case_order bigint;
ALTER TABLE kyc.kyc_case ALTER COLUMN case_order SET DEFAULT nextval('kyc.case_order');

COMMENT ON COLUMN kyc.kyc_case.case_order IS
    'The case''s place in its customer''s order of cases (kyc.case_order, drawn at insert; P15-TSK-037). NULL for a case written before V012. The rank of "latest", never a clock or a UUIDv7.';

-- Risk's screening-hit read's index, the same predicate, ordered by the new rank: replaced, not doubled. Served by the
-- lateral's LIMIT 1 as before.
CREATE INDEX verification_check_decided_latest_by_order
    ON kyc.verification_check (case_id, check_type, check_order DESC NULLS LAST, requested_at DESC, status_changed_at DESC, id DESC)
    WHERE status IN ('CLEAR', 'HIT');

DROP INDEX kyc.verification_check_decided_latest;
