package com.finapp.lending;

import java.sql.Connection;
import java.time.LocalDate;

/**
 * Whether a credit line's draws are suspended (`P11-TSK-021`; A25, ADR-0100 point 3, ADR-0098 point 5,
 * {@code LENDING_LIFECYCLES.md} section 5) - a <strong>derived condition</strong>, never a stored flag: suspended while any
 * amount of the line is past due, or while the line is defaulted. Read by the draw under the line's row lock (L3), on the
 * business date of the database clock read after it, so whatever a repayment, a collection or the servicing step committed
 * under the same lock is always seen.
 *
 * <p>The wired derivation is {@link LoanConditions} (`P11-TSK-024`): {@link DelinquencyState#derive} over the line's billed
 * items - a statement's minimum payment unpaid at the end of its due date is past due - and its recorded default flag
 * ({@code DelinquencyDatabaseTest#aPastDueLineRefusesDraws}, {@code #aDefaultedLineRefusesDraws}). `P11-TSK-021`'s
 * placeholder ({@code BEFORE_ANY_CONDITION}, never suspended) is gone. The check, its place under L3 and the refusal
 * ({@code 409 lending.DrawsSuspended}, nothing written) are `P11-TSK-021`'s ({@code PHASE_11_PLAN.md} section 5.5); this
 * interface stays the seam a test double stands in at.
 */
@FunctionalInterface
public interface DrawSuspension {

    /**
     * Whether {@code line}'s draws are suspended on {@code businessDate} under its governing agreement's {@code pinned}
     * terms (the bounds and the default threshold); called under the line's row lock (L3).
     */
    boolean suspended(Connection unitOfWork, LoanId line, LoanTerms pinned, LocalDate businessDate);
}
