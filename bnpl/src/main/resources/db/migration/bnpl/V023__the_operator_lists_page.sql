-- The BNPL operator lists, paged (P17-TSK-035, gaps G-83 and G-84; finapp-admin-panel B8.3, B8.4, ADR-C133;
-- API_CONVENTIONS.md section 7).
--
-- WHY
--   Three new operator collections walk bnpl's tables by keyset, every page resumed strictly beyond the last row the page
--   before served:
--     GET /v1/operator/bnpl-applications                    - submitted newest first, (submitted_at, id) descending;
--     GET /v1/operator/bnpl-agreements                      - the obligations opened newest first, (opened_at, id)
--                                                             descending; one merchant's ride V006's purchase_by_merchant;
--     GET /v1/operator/bnpl-agreements/servicing-requests   - the waivers and repayment reversals awaiting a decision,
--                                                             proposed oldest first, (proposed_at, id) ascending in each table.
--   (GET /v1/operator/bnpl-purchases/{id}/refunds rides V012's bnpl_refund_by_purchase.) What existed served one party
--   (bnpl_application_by_party, bnpl_obligation_by_party) or one obligation (bnpl_waiver_by_obligation,
--   bnpl_repayment_reversal_by_obligation) - nothing served every party's in time order, so a page would read and sort the
--   whole table. A pending waiver is one with no decision row (V016's bnpl_waiver_decision, UNIQUE on its waiver): no partial
--   index can say so, so its index covers every waiver; a pending reversal is one still PROPOSED, and its index holds only
--   those.
--
-- WHAT IT ADDS, AND ONLY THAT
--   Four indexes. No column, row, constraint, trigger or grant. It moves no money. Expand-only: the previous release neither
--   reads nor needs them.
--
-- LOCKS
--   Built in this migration's transaction, not CONCURRENTLY (EveryMigrationRunsInATransactionTest; DATA_MIGRATIONS.md section
--   4.4): a SHARE lock on each table until this migration commits, so an application, a purchase's obligation or a servicing
--   proposal waits for it. On a deployment whose BNPL book is large they are built ahead, CONCURRENTLY and by the same names,
--   which is what IF NOT EXISTS is for.

CREATE INDEX IF NOT EXISTS bnpl_application_by_submission
    ON bnpl.bnpl_application (submitted_at, id);

CREATE INDEX IF NOT EXISTS bnpl_obligation_by_opening
    ON bnpl.bnpl_obligation (opened_at, id);

CREATE INDEX IF NOT EXISTS bnpl_waiver_by_proposal
    ON bnpl.bnpl_waiver (proposed_at, id);

CREATE INDEX IF NOT EXISTS bnpl_repayment_reversal_pending
    ON bnpl.bnpl_repayment_reversal (proposed_at, id) WHERE status = 'PROPOSED';
