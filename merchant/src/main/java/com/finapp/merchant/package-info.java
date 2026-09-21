/**
 * The merchant as a commercial counterparty — its identity, its pricing, its payouts, and never
 * its balance.
 *
 * <p><strong>What belongs here.</strong> The Merchant and its machine ({@code P6-TSK-003}), the
 * MerchantApiKey — the platform's fourth authentication vocabulary, hashed and never recoverable
 * ({@code INV-IDN-01}, ADR-0052) — the versioned FeeSchedule ({@code P6-TSK-004}: immutable once
 * effective, change creates a new version effective forward, {@code INV-MER-03}), the
 * PayoutDestination with the platform's first four-eyes flow ({@code P6-TSK-011},
 * {@code INV-AUD-04}: proposer and approver distinct actors, cooling-off before effect), and the
 * MerchantPayout with its hold-then-dispatch lifecycle ({@code P6-TSK-012}, ADR-0051).
 *
 * <p><strong>What is structurally absent, and why the module exists in this shape.</strong> A
 * balance. What the platform owes a merchant is the merchant's payable <em>ledger position</em>
 * — captured minus fees minus refunds minus payouts — and it exists nowhere else
 * ({@code INV-MER-02}): a stored payable would be a second balance authority, and drift in a
 * liability to a counterparty is a dispute the books cannot win. Hence this module's one
 * permitted sibling edge, {@code merchant -> ledger}: the payable is read and posted to through
 * the ledger's APIs, commanded and never written ({@code INV-LED-04}) — and with that edge in
 * the build graph, {@code ledger -> merchant} is a Gradle cycle that cannot be added at all.
 *
 * <p>{@code checkout} and {@code payments} are refused in both directions: a session references
 * its merchant by identifier through a port {@code app} implements, and the fee assessment
 * rides the capture through the ADR-0050 §6 composition seam — the module that prices the
 * platform's service never sees provider machinery. {@code MerchantModuleIsolationTest} is the
 * only control on the {@code checkout} refusal; no cycle backs it.
 *
 * <p><strong>What exists so far.</strong> The boundary and the migrator-owned schema
 * ({@code P6-TSK-001}).
 */
package com.finapp.merchant;
