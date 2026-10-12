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
 * <p><strong>The two conditions it derives from have no producer yet.</strong> "Past due" needs a line's statement - its
 * minimum payment and due date (`P11-TSK-022`) - and "defaulted" the condition step (`P11-TSK-024`); before them nothing
 * can be past due and no line can be defaulted, so {@link #BEFORE_ANY_CONDITION} is the condition derived over what exists:
 * never suspended. The check, its place under L3 and the refusal ({@code 409 lending.DrawsSuspended}, nothing written) are
 * built here; `P11-TSK-024` replaces the derivation with the condition read and proves it bites
 * ({@code PHASE_11_PLAN.md} section 5.5, the backlog's `-021` scope).
 */
@FunctionalInterface
public interface DrawSuspension {

    /**
     * Over the conditions that exist before statements (`-022`) and the condition step (`-024`): nothing is past due and
     * nothing defaulted, so no line is suspended.
     */
    DrawSuspension BEFORE_ANY_CONDITION = (unitOfWork, line, businessDate) -> false;

    /** Whether {@code line}'s draws are suspended on {@code businessDate}; called under the line's row lock (L3). */
    boolean suspended(Connection unitOfWork, LoanId line, LocalDate businessDate);
}
