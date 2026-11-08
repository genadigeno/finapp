-- A payment intent readable by the day of its confirmation (P15-TSK-025; the debt row "The assessment census dates a payment
-- intent by its creation"; C15).
--
-- WHAT READS IT
--   The risk proofs' assessment census. A payment intent is minted at its creation and judged at its confirmation, any number
--   of days later, so a census that enters a day through the intent's own UUIDv7 names a late-confirmed intent's missing seal
--   only on the day it was created - and the meters read today and yesterday, so after two days nothing does. The confirmation
--   is payment_intent_event's edge out of REQUIRES_CONFIRMATION into PROCESSING, or into FAILED when risk refused it, and
--   nothing served "the confirmations of a day": occurred_at had no index and the event's id is an identity, not a UUIDv7.
--
-- ONLY THE TWO EDGES THAT MEAN A JUDGEMENT
--   Not REQUIRES_CONFIRMATION -> CANCELLED: a customer's own withdrawal of an intent that was never judged. A partial index
--   holds exactly the rows the census asks for, and costs the intent's other transitions nothing. Its predicate is restated by
--   PlatformJudgedCommands, whose test pins both.
--
-- IN THE MIGRATION'S TRANSACTION, NOT CONCURRENTLY
--   DATA_MIGRATIONS.md section 4.4 and the ledger V029 precedent: CONCURRENTLY needs a non-transactional migration that can
--   leave an invalid index behind, and the platform holds no production intents yet. Over a large live table it would be built
--   CONCURRENTLY in a non-transactional migration instead.
--
-- EXPAND-ONLY
--   No column, no constraint, no trigger, no grant, and an index is not a privilege: an instance of the previous release reads
--   and writes this table unchanged.
CREATE INDEX payment_intent_event_confirmed_at
    ON payments.payment_intent_event (occurred_at)
    WHERE from_status = 'REQUIRES_CONFIRMATION' AND to_status IN ('PROCESSING', 'FAILED');
