-- The ledger's operator read paths (P17-TSK-022; finapp-admin-panel B5.1-B5.3, ADR-C133).
--
-- WHY
--   Four reads, each a keyset walk that must be an index range rather than a scan and a sort:
--   - the account register, GET /v1/operator/ledger/accounts, pages the chart of accounts by (purpose, currency, id). The two
--     unique indexes of V002 lead with the owner for owned accounts, so without this one a page of customer wallets sorts every
--     wallet;
--   - the journal entry search by an economic event's reference, GET /v1/operator/ledger/entries?reference=, walks
--     (reference, posting_date, id). Without it a reference is found by reading every entry of the window. The window alone and
--     an account's entries ride V029's (posting_date, id) and V004's journal_line_by_account, which the plans showed sufficient;
--   - the two approvers' queues, GET /v1/ledger/adjustments?status=PROPOSED and GET /v1/ledger/projection-rebuilds?status=PROPOSED,
--     walk (proposed_at, id) over what awaits a second person. Partial, because the queue is only the PROPOSED rows - and, for the
--     adjustments, only the MANUAL ones, the only origin that door decides; the decided rows, which grow without end, are never in
--     them.
--   The plans are recorded by app's LedgerCollectionsDatabaseTest and in OPERATIONS_RUNBOOK.md section 11.48.
--
-- WHAT IT ADDS, AND ONLY THAT
--   Four indexes. No column, row, constraint, trigger or grant. It moves no money and changes no figure. Expand-only: the previous
--   release neither reads nor needs them.
--
-- LOCKS
--   Built in this migration's transaction, not CONCURRENTLY (identity V027's reasoning): each holds SHARE on its table until this
--   migration commits. journal_entry is the one that matters - every posting inserts there and waits for the build. A deployment
--   with a large journal builds journal_entry_by_reference (and, if large, ledger_account_register) ahead by the same names,
--   CONCURRENTLY and outside a transaction, which is what IF NOT EXISTS is for (OPERATIONS_RUNBOOK.md section 11.48). The two
--   queues' tables are written by people, a few rows a day.

CREATE INDEX IF NOT EXISTS ledger_account_register
    ON ledger.ledger_account (purpose, currency, id);

CREATE INDEX IF NOT EXISTS journal_entry_by_reference
    ON ledger.journal_entry (reference, posting_date, id);

CREATE INDEX IF NOT EXISTS adjustment_proposal_pending_manual
    ON ledger.adjustment_proposal (proposed_at, id)
    WHERE status = 'PROPOSED' AND origin = 'MANUAL';

CREATE INDEX IF NOT EXISTS projection_rebuild_pending
    ON ledger.projection_rebuild (proposed_at, id)
    WHERE status = 'PROPOSED';
