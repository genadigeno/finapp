package com.finapp.kyc;

import com.finapp.sharedkernel.id.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * The explicit work item a non-clean check becomes (`P2-TSK-010`, ADR-0038,
 * {@code INV-KYC-04}).
 *
 * <p>A screening hit is a probability, not a verdict, and both silent outcomes are wrong in
 * opposite directions: silently cleared is a sanctions breach, silently rejected is a person
 * refused service by string similarity. The review task is the shape that impossibility takes —
 * the case <em>becomes work for a person</em>, countable and (in `P2-TSK-012`) resolvable with a
 * reason.
 *
 * <p><strong>A task references the check that raised it, and one check gets one task, ever</strong>
 * — the total {@code UNIQUE (check_id)} in {@code V005}. The task is "resolve what <em>this
 * question</em> raised": resolution never frees the slot, because the resolution of that question
 * is permanent evidence, and changed circumstances are a <em>new check</em> which brings its own
 * task. That totality is also what makes creation idempotent under N instances — the
 * {@code ON CONFLICT} arbiter in {@code JdbcReviewTaskStore}.
 *
 * <p>Resolution — the transition, its reason, its reviewer — is `P2-TSK-012`'s; this aggregate
 * deliberately has no {@code resolve()} until the columns and the caller exist, because a method
 * nothing can call is dead code carrying confident javadoc (the {@code P1-TSK-013} finding).
 */
public final class ReviewTask {

    private final ReviewTaskId id;
    private final KycCaseId caseId;
    private final CheckId checkId;
    private final ReviewTaskStatus status;
    private final Instant openedAt;

    private ReviewTask(
            ReviewTaskId id,
            KycCaseId caseId,
            CheckId checkId,
            ReviewTaskStatus status,
            Instant openedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.caseId = Objects.requireNonNull(caseId, "caseId must not be null");
        this.checkId = Objects.requireNonNull(checkId, "checkId must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.openedAt = Objects.requireNonNull(openedAt, "openedAt must not be null");
    }

    /** Opens the task a raising check owes a person. {@code OPEN}, dated by the injected clock. */
    public static ReviewTask open(
            IdGenerator ids, Clock clock, KycCaseId caseId, CheckId checkId) {
        Objects.requireNonNull(ids, "ids must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        return new ReviewTask(
                ReviewTaskId.next(ids), caseId, checkId, ReviewTaskStatus.OPEN, Instant.now(clock));
    }

    /** Reconstitutes from storage. Applies no transition rules: the row was already valid. */
    public static ReviewTask rehydrate(
            ReviewTaskId id,
            KycCaseId caseId,
            CheckId checkId,
            ReviewTaskStatus status,
            Instant openedAt) {
        return new ReviewTask(id, caseId, checkId, status, openedAt);
    }

    public ReviewTaskId id() {
        return id;
    }

    public KycCaseId caseId() {
        return caseId;
    }

    public CheckId checkId() {
        return checkId;
    }

    public ReviewTaskStatus status() {
        return status;
    }

    public Instant openedAt() {
        return openedAt;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ReviewTask task && id.equals(task.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    /** Identifiers and a status — nothing about a person. */
    @Override
    public String toString() {
        return "ReviewTask[" + id + ", case=" + caseId + ", check=" + checkId + ", " + status + "]";
    }
}
