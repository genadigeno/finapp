package com.finapp.kyc;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The reviewers' queues over kyc's own rows (`P17-TSK-029`, gaps G-60 and G-65; ADR-0035, ADR-0081): the open cases a reviewer
 * works, oldest first, and the counterparty screenings awaiting a person, oldest raised first. Read-only and lock-free; each a
 * keyset page - strictly after the last row served, in the read's own total order - so a row decided or raised between two
 * pages moves no other (`API_CONVENTIONS.md` section 7).
 *
 * <p>Separate from {@link KycCaseStore} and {@link CounterpartyScreeningStore} on purpose: those are the aggregates' write-side
 * stores, decorated and faked across the suites; these are a reviewer's lists, and nothing acts on what they return.
 */
public interface KycInquiries {

    /** Where a page of the review queue ended: the last case it served, by its order's key. */
    record QueuePosition(Instant openedAt, UUID caseId) {
        public QueuePosition {
            Objects.requireNonNull(openedAt, "openedAt must not be null");
            Objects.requireNonNull(caseId, "caseId must not be null");
        }
    }

    /**
     * One screening awaiting a person, as the backlog serves it: its identifier, its status and when it was raised for review -
     * never its subject, the payee's verdict, the provider's answer or why it was raised.
     */
    record AwaitingReview(CounterpartyScreeningId id, CounterpartyScreeningStatus status, Instant raisedAt) {
        public AwaitingReview {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(raisedAt, "raisedAt must not be null");
        }
    }

    /** Where a page of the backlog ended: the last screening it served, by its order's key. */
    record BacklogPosition(Instant raisedAt, UUID screeningId) {
        public BacklogPosition {
            Objects.requireNonNull(raisedAt, "raisedAt must not be null");
            Objects.requireNonNull(screeningId, "screeningId must not be null");
        }
    }

    /**
     * At most {@code limit} open cases whose status is one of {@code statuses}, oldest opening first, the case id breaking a tie,
     * strictly after {@code after}. Every status named must be open: a decided case is no queue's.
     *
     * @throws IllegalArgumentException {@code statuses} is empty or names a decided status
     */
    List<KycCase> openCases(Connection unitOfWork, Set<KycCaseStatus> statuses, Optional<QueuePosition> after, int limit);

    /**
     * At most {@code limit} screenings {@code IN_REVIEW}, oldest raised first - raised is when the automatic outcome sent it to a
     * person - the screening id breaking a tie, strictly after {@code after}.
     */
    List<AwaitingReview> screeningsAwaitingReview(Connection unitOfWork, Optional<BacklogPosition> after, int limit);
}
