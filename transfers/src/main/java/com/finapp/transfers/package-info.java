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
 * <p><strong>Nothing is implemented yet.</strong> This module is the skeleton created by
 * {@code P4-TSK-001}: the boundary and the migrator-owned schema exist so that the first transfer
 * table lands inside an enforced boundary and under the right owner - the column-narrowed grants
 * and the every-writer transition trigger {@code PHASE_4_PLAN.md} §8 plans are only available that
 * way. {@code Transfer} is {@code P4-TSK-003}; the schema tables are {@code P4-TSK-004}; and there
 * is deliberately <strong>no {@code TransfersAuditAction} enum</strong> - the audit actions arrive
 * with the aggregates and commands whose designs fix their meaning ({@code P2-TSK-005}'s
 * precedent, exercised for {@code accounts} by {@code P3-TSK-011}), not with this skeleton.
 */
package com.finapp.transfers;
