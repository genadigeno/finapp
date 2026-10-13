package com.finapp.lending;

import java.sql.Connection;
import java.time.LocalDate;

/**
 * Whether a credit line's draws are suspended (`P11-TSK-021`; A25, ADR-0100 point 3, ADR-0098,
 * {@code LENDING_LIFECYCLES.md} section 5) - a <strong>derived condition</strong>, never a stored flag: suspended while any
 * amount of the line is past due, or while the line is defaulted. Read by the draw under the line's row lock (L3), on the
 * business date of the database clock read after it, so a condition committed by the servicing step (which takes the same
 * lock) is always seen.
 *
 * <p><strong>Where the conditions stand.</strong> "Past due" is a statement's minimum payment unpaid at the end of its due
 * date: since `P11-TSK-022` a line's statements exist, and the seam a derivation reads is the billed-item model -
 * {@link InstalmentBillingStore#dueItems} under L3, any item whose due date is before the business date with something
 * still due. "Defaulted" is the condition step's (`P11-TSK-024`). The backlog gives the suspension's derivation - both
 * conditions, proven biting through the real path ({@code DelinquencyDatabaseTest#aPastDueLineRefusesDraws}) - to
 * `P11-TSK-024`, so {@link #BEFORE_ANY_CONDITION} stays the wired derivation until then: <strong>between `-022` and
 * `-024` a line whose minimum payment is past due can still draw</strong> (A25 not yet enforced) - recorded for the owner
 * by `P11-TSK-022`'s gate. The check, its place under L3 and the refusal ({@code 409 lending.DrawsSuspended}, nothing
 * written) are `P11-TSK-021`'s ({@code PHASE_11_PLAN.md} section 5.5).
 */
@FunctionalInterface
public interface DrawSuspension {

    /**
     * Never suspended - the wired derivation until the condition step (`-024`) replaces it with the condition read (a
     * past-due item or the default flag). Since statements (`-022`) a minimum can be past due; this does not yet see it.
     */
    DrawSuspension BEFORE_ANY_CONDITION = (unitOfWork, line, businessDate) -> false;

    /** Whether {@code line}'s draws are suspended on {@code businessDate}; called under the line's row lock (L3). */
    boolean suspended(Connection unitOfWork, LoanId line, LocalDate businessDate);
}
