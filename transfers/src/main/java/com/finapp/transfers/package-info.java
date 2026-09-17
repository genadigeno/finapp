/**
 * Movement of funds between two internal accounts: the transfer, its lifecycle, its history -
 * never a posting.
 *
 * <p><strong>What belongs here.</strong> Transfer and Beneficiary - the movement and the people it
 * is addressed to - with their status machines and ownership ({@code MODULE_ARCHITECTURE.md} §4,
 * bounded context 8). A transfer's outcome is decided by this platform alone: both legs are
 * internal, and the state transition and its posting commit in <em>one</em> local transaction
 * (ADR-0043) - an outcome a third party decides is a <em>payment</em>, Phase 5's lifecycle, and
 * that boundary must not be inherited by analogy.
 *
 * <p><strong>This module commands postings and writes none</strong> ({@code INV-LED-04}). The
 * money movement IS a ledger posting - one {@code POSTING} entry, debit the source wallet, credit
 * the destination wallet - requested through the ledger's command API in the caller's transaction.
 * The direction is structural: with {@code transfers -> ledger} in the build graph, the reverse
 * edge is a Gradle dependency cycle, and {@code TransfersModuleIsolationTest} pins the positive
 * half so the asymmetry is asserted in both directions rather than implied by one.
 *
 * <p><strong>Deliberately no edge to {@code accounts}.</strong> The transfer resolves the caller's
 * products through a port {@code app} implements (the {@code AccountHolderVerification} shape,
 * {@code P4-TSK-005}): the module that owns the product and the module that moves the money must
 * not become one dependency ball, and a compile-time edge would be its first step.
 *
 * <p><strong>What exists so far.</strong> The boundary and the migrator-owned schema
 * ({@code P4-TSK-001}), the {@link com.finapp.transfers.Transfer} aggregate with its ADR-0044
 * machine ({@code P4-TSK-003}), the schema that inherits it ({@code P4-TSK-004}), the
 * execution command with its stores, ports and seams ({@code P4-TSK-005}) — one transfer judged
 * and committed with its money in one local transaction — and the
 * {@link com.finapp.transfers.Beneficiary} with its one-live-per-(party, destination) schema
 * and port-validated creation ({@code P4-TSK-006}) — carried over HTTP with the conditional
 * step-up, both beneficiary audit actions and the module's first error code
 * ({@code P4-TSK-007}). The transfer surface is {@code P4-TSK-008}'s; the reversal
 * {@code P4-TSK-009}'s (with its own audit action — the deliberately-few licence continues);
 * the seam hardening {@code P4-TSK-010}'s.
 */
package com.finapp.transfers;
