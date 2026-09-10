package com.finapp.kyc;

/**
 * Storage for review tasks (`P2-TSK-010`).
 *
 * <p>A port, on ADR-0033's recorded reasoning: the unit of work is the caller's, so a task
 * inserted beside a case transition commits or rolls back with it — which is what makes
 * "{@code IN_REVIEW} implies at least one task exists" an atomic fact rather than a hope.
 *
 * <p><strong>Reads arrive with their callers.</strong> `P2-TSK-012`'s reviewer surface brings the
 * per-case listing and the resolution — and its {@code IN_REVIEW → READY_FOR_DECISION} exit must
 * be conditional on "no {@code OPEN} task" <em>in the statement</em>, never a read-then-move,
 * because a task can join an already-in-review case (a late {@code HIT} completing after the
 * first routing) and two instances resolving the last two tasks would each read one still open.
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface ReviewTaskStore<T> {

    /**
     * Inserts {@code fresh}, or converges silently on the task its check already has.
     *
     * <p><strong>The total {@code UNIQUE (check_id)} index is the arbiter</strong>: one task per
     * check, ever — N instances routing one blocked case produce one row per raising check, and a
     * re-run's insert simply loses ({@code ON CONFLICT DO NOTHING}, the {@code RoleAssignment}
     * idiom rather than the savepoint one, because nothing else in the caller's transaction must
     * survive a lost race and the statement is single-row). The row count is the outcome.
     *
     * @return whether this call created the task
     */
    boolean openForCheck(T unitOfWork, ReviewTask fresh);

    /**
     * How many tasks await a person, fleet-wide — the {@code finapp.kyc.review.queue} gauge's
     * subject (`PHASE_2_PLAN.md` §10: the queue nobody watches is the queue that ages).
     *
     * <p>{@code OPEN} only, in the predicate: a resolved task is finished work, and counting it
     * would report a queue that never drains — wrong in the alarming direction, which is better
     * than reassuring but still wrong.
     */
    long countOpen(T unitOfWork);
}
