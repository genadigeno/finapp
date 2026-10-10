-- An answer that could not be recorded is an attempt of its own kind (the Phase 10 -> 11 transition; ADR-0085 section 11).
--
-- Since this transition, when the recording transaction (ADR-0085's Tx2) fails - an answer naming another provider than
-- the request's (INV-CRD-07), a value a CHECK refuses, a retrieval instant past the database's range, or a connection
-- lost mid-transaction - the request is moved REQUESTED -> UNAVAILABLE in a transaction of its own, the bytes kept as
-- evidence, so it is retried until its deadline and then reaches the policy's fallback, instead of staying REQUESTED and
-- being re-asked every cadence until its decision request expired.
--
-- The attempt row says what happened: UNRECORDED - an answer arrived and was not recorded as data. None of V004's
-- outcomes says that: MALFORMED is a body the adapter could not read, and the cause here may be ours (a lost connection),
-- not the provider's. Only the CHECK's list grows; the column, the grants and the machine are unchanged.

ALTER TABLE credit.data_request_attempt DROP CONSTRAINT data_request_attempt_outcome_is_known;

ALTER TABLE credit.data_request_attempt ADD CONSTRAINT data_request_attempt_outcome_is_known CHECK (outcome IN (
    'RECEIVED', 'PARTIAL', 'TIMEOUT', 'MALFORMED', 'UNKNOWN_STATUS', 'PROVIDER_ERROR', 'CONSENT_WITHDRAWN', 'UNRECORDED'));
