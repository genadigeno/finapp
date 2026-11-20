-- The lending operator lists, paged (P17-TSK-035, gaps G-82 and G-84; finapp-admin-panel B8.2, B8.4, ADR-C133;
-- API_CONVENTIONS.md section 7).
--
-- WHY
--   Four new operator collections walk lending's tables by keyset, every page resumed strictly beyond the last row the page
--   before served:
--     GET /v1/operator/loan-applications           - submitted newest first, (submitted_at, id) descending;
--     GET /v1/operator/loans                       - opened newest first, (opened_at, id) descending;
--     GET /v1/operator/loans/servicing-requests    - the waivers, amendments and repayment reversals awaiting a decision,
--                                                    proposed oldest first, (proposed_at, id) ascending in each table.
--   What existed served one party (loan_application_by_party, loan_by_party) or one loan (waiver_by_loan,
--   loan_amendment_by_loan, repayment_reversal_by_loan) - nothing served every party's in time order, so a page would read
--   and sort the whole table. A pending waiver or amendment is one with no decision row (V021's waiver_decision, V023's
--   loan_amendment_decision, each UNIQUE on its proposal): no partial index can say so, so those two indexes cover every
--   proposal; a pending reversal is one still PROPOSED, and its index holds only those.
--
-- WHAT IT ADDS, AND ONLY THAT
--   Five indexes. No column, row, constraint, trigger or grant. It moves no money. Expand-only: the previous release neither
--   reads nor needs them.
--
-- LOCKS
--   Built in this migration's transaction, not CONCURRENTLY (EveryMigrationRunsInATransactionTest; DATA_MIGRATIONS.md section
--   4.4): a SHARE lock on each table until this migration commits, so an application, a loan's birth or a servicing proposal
--   waits for it. Applications and loans are a customer's few a year, and proposals rarer still, so the tables are small; a
--   deployment with large ones builds them ahead, CONCURRENTLY and by the same names, which is what IF NOT EXISTS is for.

CREATE INDEX IF NOT EXISTS loan_application_by_submission
    ON lending.loan_application (submitted_at, id);

CREATE INDEX IF NOT EXISTS loan_by_opening
    ON lending.loan (opened_at, id);

CREATE INDEX IF NOT EXISTS waiver_by_proposal
    ON lending.waiver (proposed_at, id);

CREATE INDEX IF NOT EXISTS loan_amendment_by_proposal
    ON lending.loan_amendment (proposed_at, id);

CREATE INDEX IF NOT EXISTS repayment_reversal_pending
    ON lending.repayment_reversal (proposed_at, id) WHERE status = 'PROPOSED';
