-- The intents in flight, by the account they credit (the Phase 6 -> 7 transition).
--
-- Account closing now refuses while any payment not yet final will credit one of the product's
-- ledger accounts (the accounts PendingCredits port, asked under the account's FOR UPDATE): a
-- customer could close the wallet an open top-up credited and then confirm, and the provider
-- captured the card while the ledger refused the capture's posting - the customer charged,
-- nothing booked, no route to refund. This index serves that question and nothing else: the
-- predicate is the intent machine's non-terminal set, generated from
-- PaymentIntentStatus.sqlTerminalValueList() (PaymentsMigrationTest pins it), so "in flight" has
-- one definition and the index stays small - it holds only the payments still on their way.
--
-- wallet_account_id keeps its name here, though for a merchant-bound payment it holds the
-- merchant's payable: the rename is a recorded debt, due with the next migration that recreates
-- the intent's every-writer trigger, which this one does not.

CREATE INDEX payment_intent_in_flight_by_credit_account
    ON payments.payment_intent (wallet_account_id)
    WHERE status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED');
