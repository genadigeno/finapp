package com.finapp.reconciliation;

import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Lock-free reads for the suspense proof, the ownership verdict and the gauges
 * (`P8-TSK-010`, ADR-0070 §§5 and 7) — report and never repair, the {@code TrialBalance}
 * shape. Every remainder is computed through {@code Money} per row and folded by the
 * caller, never a SQL {@code SUM}.
 */
public interface SuspenseReadings {

    /** One open item's unreleased remainder — {@code CREDIT} and {@code DEBIT} never net. */
    record OpenSuspenseRemainder(SuspenseSide side, Money remainder) {}

    /** Every item still holding value, its remainder per row. */
    List<OpenSuspenseRemainder> openRemainders(Connection unitOfWork);

    /** Items with a remainder — {@code finapp.reconciliation.suspense.open}. */
    long openCount(Connection unitOfWork);

    /** The oldest open item's {@code opened_on} — {@code suspense.age}'s anchor. */
    Optional<LocalDate> oldestOpenedOn(Connection unitOfWork);

    /**
     * Items with a remainder whose owning break no longer answers — missing, or
     * {@code RESOLVED} over value it still owns. Must read 0
     * ({@code finapp.reconciliation.suspense.unowned}); {@code break_id NOT NULL} makes the
     * first half structural, and this read is the defect detector for the second.
     */
    long unownedCount(Connection unitOfWork);

    /** The {@code origin_ref}s the register already owns for {@code origin} — the
     * Phase 7 unadopted-parkings term subtracts against this set (ADR-0070 §7). */
    Set<String> ownedOriginRefs(Connection unitOfWork, SuspenseOrigin origin);

    /**
     * Every entry a park posted or a suspense item owns — the completeness verifier's
     * known-entry classes this task adds (ADR-0067 §9, ADR-0070 §7).
     */
    List<UUID> knownEntries(Connection unitOfWork);
}
