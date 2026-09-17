/**
 * The customer account product: the agreement, its lifecycle, its owner - never its money.
 *
 * <p><strong>What belongs here.</strong> Customer Account and Wallet - the products a customer
 * holds - with their status machines and ownership ({@code MODULE_ARCHITECTURE.md} §4). Wallet
 * shares the module provisionally (§3 M1, ADR-0042): a wallet is a stored-value account, and the
 * recorded split trigger is a wallet acquiring a lifecycle that is not the account's.
 *
 * <p><strong>A Customer Account carries no balance</strong> (ADR-0042). It <em>references</em> the
 * ledger account(s) recording its position, and a balance query on the product is a query against
 * those. A balance field on the product row is {@code balance = balance + amount} waiting to
 * happen, which {@code INV-BAL-01} forbids - the product has nothing to increment.
 *
 * <p><strong>This module depends on {@code ledger}; {@code ledger} never depends on it.</strong>
 * The product asks the ledger for balances and requests postings through its command API - it
 * writes none itself ({@code INV-LED-04}). The direction is structural: with
 * {@code accounts -> ledger} in the build graph, the reverse edge is a Gradle dependency cycle,
 * and {@code AccountsModuleIsolationTest} pins the positive half so the asymmetry is asserted in
 * both directions rather than implied by one.
 *
 * <p><strong>Nothing is implemented yet.</strong> This module is the skeleton created by
 * {@code P3-TSK-011}: the boundary and the migrator-owned schema exist so that the first product
 * table lands inside an enforced boundary and under the right owner - the column-narrowed grant
 * {@code PHASE_3_PLAN.md} §8 plans for {@code customer_account} is only available that way.
 * {@code CustomerAccount} is {@code P3-TSK-012}; its audit actions arrive with the designs that
 * fix their meaning ({@code P2-TSK-005}'s precedent), not with this skeleton.
 */
package com.finapp.accounts;
