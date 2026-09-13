/**
 * The ledger: the authoritative financial record, and the sole writer of postings.
 *
 * <p><strong>What belongs here.</strong> The chart of accounts, ledger accounts, journal entries
 * and their lines, the balance projection and holds ({@code MODULE_ARCHITECTURE.md} §4). Every
 * balance, statement, report and reconciliation on the platform is derived from what this module
 * records, and nothing else is financial truth ({@code CLAUDE.md} rule 12, {@code INV-EVT-02}).
 *
 * <p><strong>The ledger is commanded; it does not react.</strong> Other modules request a posting
 * through its command API and the ledger decides whether and how it is written
 * ({@code INV-LED-04}). That is why this module depends on no business module: it has nothing to
 * import from the modules that command it, and a compile-time edge toward one is the first step
 * toward a second set of accounting rules living somewhere else.
 *
 * <p><strong>A Ledger Account is not a customer's account</strong> (ADR-0042). A Customer Account
 * is a product agreement with a lifecycle and <em>no balance</em>; a Ledger Account is an
 * accounting position with a type, a normal balance and one currency. The first lives in
 * {@code accounts}, the second here.
 *
 * <p><strong>History is never edited</strong> ({@code INV-LED-03}, {@code INV-HIST-01}), and that
 * is enforced at {@code DB-PRIVILEGE}: the objects in the {@code ledger} schema are owned by the
 * migrator, and the application role receives, table by table, only the grants each table's own
 * migration gives it. A mistake is corrected by a new entry - a reversal or an adjustment - never
 * by changing an old one.
 *
 * <p><strong>Nothing is implemented yet.</strong> This module is the skeleton created by
 * {@code P3-TSK-001}: the boundary, the schema and the auditable-action registry exist so that the
 * first ledger table lands inside an enforced boundary and under the right owner.
 * {@code LedgerAccount} is {@code P3-TSK-002}.
 */
package com.finapp.ledger;
