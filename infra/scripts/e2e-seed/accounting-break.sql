-- TEST-ONLY (P17-TSK-112). An OPEN accounting break in :'month', as a close run's source-completeness check raises one, with its
-- case file's RAISED row: the console's accountant proposes its acknowledgement and its controller approves - or rejects - it
-- (P17-TSK-111). Only a close run's check on a locked month raises a break, and a clean stack's checks find nothing, so this is the
-- one way to put one on a test stack. Planted in the current month, which no seeded close touches: an OPEN break blocks its
-- month's close. Applied by infra/scripts/e2e-seed.sh with psql's variables - :'month' (the month's first day) and :'subject' (a
-- fresh subject, also the act's correlation) - each quoted as a literal by psql itself after the shell checked its shape, and
-- proven unchanged by app's E2eSeedFinanceDatabaseTest: two persons acknowledge what it wrote.
--
-- It writes accounting's own rows as the raise would, minus its audit record and its event: accounting V006's checks, its
-- machine-edge trigger (born OPEN, nothing acted) and V018's case-file checks hold every value.
WITH raised AS (
    INSERT INTO accounting.accounting_break (id, period_start, check_code, break_type, subject_ref, evidence, judged_by, status,
        raised_at, raised_by, raised_by_type, correlation_id)
    VALUES (uuidv7(), :'month'::date, 'SOURCE_COMPLETENESS', 'SOURCE_DISCREPANCY', :'subject',
        'proof=E2E_SEED, a discrepancy planted for the console', 'source-proofs:v1', 'OPEN', clock_timestamp(), 'system',
        'SYSTEM', :'subject')
    RETURNING id, correlation_id)
INSERT INTO accounting.break_event (id, break_id, act, from_status, to_status, actor, actor_type, occurred_at, correlation_id)
SELECT uuidv7(), id, 'RAISED', NULL, 'OPEN', 'system', 'SYSTEM', clock_timestamp(), correlation_id FROM raised
RETURNING break_id;
