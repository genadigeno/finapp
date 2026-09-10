package com.finapp.kyc;

/**
 * Storage for review tasks (`P2-TSK-010`; resolution and the reads `P2-TSK-012`).
 *
 * <p>A port, on ADR-0033's recorded reasoning: the unit of work is the caller's, so a task
 * inserted beside a case transition commits or rolls back with it — which is what makes
 * "{@code IN_REVIEW} implies at least one task exists" an atomic fact rather than a hope.
 *
 * <p>The {@code IN_REVIEW → READY_FOR_DECISION} exit this store's javadoc demanded lives in
 * {@link KycCaseStore#moveStatusWhenNoOpenTasks}: conditional on "no {@code OPEN} task"
 * <em>in the statement</em>, never a read-then-move, because a task can join an
 * already-in-review case (a late {@code HIT} completing after the first routing) and two
 * instances resolving the last two tasks would each read one still open.
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

    /**
     * Resolves one task, conditionally — the platform's row-count-is-the-outcome protocol
     * (`P2-TSK-012`, {@code INV-KYC-04}).
     *
     * <p>Three predicates in one statement, each load-bearing: {@code id = ?} names the task,
     * {@code case_id = ?} refuses a task reached through the wrong case's URL (the
     * belongs-to-case rule as a predicate rather than a procedure, so no future caller can
     * forget it), and {@code status = 'OPEN'} is the concurrency arbiter — N reviewers racing
     * one task produce one resolution, and the losers are told they lost. Deliberately
     * <strong>unconditional on the case's own status</strong>, the mirror of creation: a late
     * {@code HIT}'s task on a case that already exited review must still be resolvable.
     *
     * @return whether this call resolved the task
     */
    boolean resolve(
            T unitOfWork,
            ReviewTaskId taskId,
            KycCaseId caseId,
            java.util.UUID resolvedBy,
            String reason,
            java.time.Instant at);

    /**
     * One task by its composite address, resolution included — how a losing {@link #resolve} is
     * disambiguated (absent → not found; present and {@code RESOLVED} → the race's loser) and
     * the reviewer listing's row shape.
     */
    java.util.Optional<ReviewTask> findByIdForCase(
            T unitOfWork, ReviewTaskId taskId, KycCaseId caseId);

    /** The case's tasks, oldest first — the reviewer surface's listing (`P2-TSK-012`). */
    java.util.List<ReviewTask> forCase(T unitOfWork, KycCaseId caseId);
}
