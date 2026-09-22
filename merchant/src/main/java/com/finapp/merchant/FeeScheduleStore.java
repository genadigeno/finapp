package com.finapp.merchant;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for fee schedules, their versions and merchant assignment (`P6-TSK-004`).
 * A port on ADR-0033's recorded reasoning; the unit of work is the caller's, because a version
 * is minted, inserted and audited in one transaction.
 *
 * <p><strong>There is no update and no delete on this port, anywhere.</strong> Not an
 * omission: a version is immutable ({@code INV-MER-03}) and a schedule has nothing that
 * changes. The only mutable thing here is which schedule a merchant points at, and that has
 * its own method with its own append-only history. A port with no way to express a repricing
 * is the cheapest enforcement available — the trigger and the withheld grant are the other
 * two ranks.
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface FeeScheduleStore<T> {

    // ----------------------------------------------------------------- schedules

    void insertSchedule(T unitOfWork, FeeSchedule schedule);

    /** The schedule, or empty — unknown and malformed are the surface's one 404. */
    Optional<FeeSchedule> findSchedule(T unitOfWork, FeeScheduleId id);

    /** Every schedule, newest first. An operator listing; there is no tenant here. */
    List<FeeSchedule> listSchedules(T unitOfWork);

    // ----------------------------------------------------------------- versions

    /**
     * Inserts the version if its number is still free, answering {@code false} if another
     * writer took it first — <strong>the version-minting arbiter, and there is deliberately no
     * lock behind it</strong>.
     *
     * <p>A schedule row cannot be locked, because a schedule row can never be updated: `V004`
     * withholds the {@code UPDATE} privilege, and PostgreSQL requires it to take a row lock at
     * all. That is not an obstacle to work around — it is the same fact stated twice. The
     * arbiter is therefore the unique index on {@code (fee_schedule_id, version)}, which is
     * also the <em>queue</em>: a racer inserting a number that an uncommitted transaction
     * already holds waits on the index, and is answered {@code false} when that transaction
     * commits.
     *
     * <p>The caller re-reads {@link #nextVersionNumber} and tries again, so ten instances
     * produce ten distinct numbers with no gap. The refusal is taken on a savepoint, so a lost
     * race costs one statement rather than the whole transaction — the audit record written
     * beside the version survives it.
     *
     * @return {@code true} if the row landed; {@code false} if the number was taken
     */
    boolean insertVersionIfNumberIsFree(T unitOfWork, FeeScheduleVersion version);

    /**
     * The next version number for this schedule — {@link FeeScheduleVersion#FIRST_VERSION}
     * when it has none. An optimistic read: what makes it safe is
     * {@link #insertVersionIfNumberIsFree}'s refusal, not this statement.
     */
    int nextVersionNumber(T unitOfWork, FeeScheduleId scheduleId);

    /**
     * One version by its own id — <strong>the recomputation read</strong> ({@code INV-MER-03}):
     * given what an assessment pinned, this returns exactly what priced it, forever, because
     * the row cannot change.
     */
    Optional<FeeScheduleVersion> findVersion(T unitOfWork, FeeScheduleVersionId id);

    /** Every version of a schedule, newest-effective first ({@code EFFECTIVE_ORDER}). */
    List<FeeScheduleVersion> listVersions(T unitOfWork, FeeScheduleId scheduleId);

    /**
     * The version effective at {@code instant}: the greatest {@code effective_from <= instant},
     * ties broken by the greater version number.
     *
     * <p>No lock. The rows are immutable, so there is nothing to serialize against; a version
     * committed concurrently with {@code effective_from = instant} may or may not be seen, and
     * <strong>both answers are correct</strong> — "now" is not a single instant across ten
     * instances. Pinning whichever answer was given is what makes the choice permanent and
     * explainable.
     */
    Optional<FeeScheduleVersion> findEffectiveVersion(
            T unitOfWork, FeeScheduleId scheduleId, Instant instant);

    // ----------------------------------------------------------------- assignment

    /** Which schedule this merchant is priced by, or empty if none has been assigned. */
    Optional<FeeScheduleId> findAssignment(T unitOfWork, MerchantId merchantId);

    /**
     * Points {@code merchantId} at {@code scheduleId}, inserting the pointer or moving it, and
     * appends the history row recording the move with the operator's reason.
     *
     * <p>Called under the merchant row's lock, which is what lets one statement serve both the
     * first assignment and a later move without the primary key having to refuse nine racers.
     *
     * @param previous the schedule the merchant was on, or empty for a first assignment
     */
    void assign(
            T unitOfWork,
            MerchantId merchantId,
            Optional<FeeScheduleId> previous,
            FeeScheduleId scheduleId,
            String reason,
            Instant at);

    /**
     * The version pricing this merchant at {@code instant} — the resolution
     * {@code P6-TSK-005} pins at intent creation, and the reason this port exists.
     *
     * <p>Empty when the merchant has no assignment. That is an honest answer rather than a
     * fabricated default schedule: what an unpriced merchant's session does about it is the
     * session's decision, not this port's.
     */
    Optional<FeeScheduleVersion> findEffectiveVersionFor(
            T unitOfWork, MerchantId merchantId, Instant instant);
}
