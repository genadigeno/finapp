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
 * <p><strong>What is built.</strong> The skeleton was {@code P3-TSK-011}'s; {@code CustomerAccount}
 * and its opening are {@code P3-TSK-012}'s, the surface {@code P3-TSK-013}'s and the close
 * {@code P3-TSK-014}'s. Since {@code P9-TSK-004} a wallet agreement holds one
 * {@code CUSTOMER_WALLET} ledger account <em>per currency</em>: {@link WalletAccounts} is the one
 * door that opens one (race-free, in the caller's transaction, announced once) and the one rule
 * every flow resolves a wallet by (the asked currency, else the first-opened), and
 * {@link AccountOpening#addCurrency} is the customer's act of adding a currency. <em>(This
 * paragraph read "Nothing is implemented yet" from the skeleton until {@code P9-TSK-004}'s gate.)</em>
 */
package com.finapp.accounts;
