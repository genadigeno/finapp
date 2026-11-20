package com.finapp.kyc;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage for reviewers' requests for information (`P17-TSK-126`, ADR-0155). A port, on ADR-0033's reasoning: every write joins
 * the caller's unit of work, so the case's move, the audit record and the outbox row commit with it or not at all.
 *
 * <p><strong>Every close is a conditional on {@code OPEN}, judged on the database clock.</strong> The answer is admitted only
 * while the request is {@code OPEN} and its deadline has not passed ({@code due_at > statement_timestamp()}); the expiry takes
 * only the requests {@code OPEN} and past it, skipping rows another transaction holds. An answer and a sweep meet on the row's
 * lock and one of them wins; ten sweepers take ten sets of distinct rows. No instance's clock decides anything.
 *
 * @param <T> the transactional unit of work - a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface InformationRequestStore<T> {

    /** A request the expiry closed: what its case's return and its record need. */
    record Lapsed(InformationRequestId id, KycCaseId caseId, Instant dueAt, String correlationId) {
        public Lapsed {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(caseId, "caseId must not be null");
            Objects.requireNonNull(dueAt, "dueAt must not be null");
            Objects.requireNonNull(correlationId, "correlationId must not be null");
        }
    }

    /**
     * Inserts {@code request}, which is {@code OPEN}. Plain and loud: the caller has just won the case's conditional move to
     * {@code AWAITING_CUSTOMER} under the case row, so a second open request is a broken invariant, and V016's unique partial
     * index fails the transaction rather than converging.
     */
    void open(T unitOfWork, InformationRequest request);

    /** The case's open request, if any. */
    Optional<InformationRequest> findOpenFor(T unitOfWork, KycCaseId caseId);

    /** Every request of the case, oldest first: the case file's history. */
    List<InformationRequest> forCase(T unitOfWork, KycCaseId caseId);

    /**
     * Closes the case's open request as {@code ANSWERED} by {@code answeredBy}, if it is open and not yet due on the database
     * clock, and returns it closed; empty when there was nothing to answer - none open, or its deadline passed. Locks the row.
     */
    Optional<InformationRequest> answer(T unitOfWork, KycCaseId caseId, CustomerAnswer answer, UUID answeredBy);

    /**
     * Closes as {@code EXPIRED} up to {@code limit} requests {@code OPEN} whose deadline has passed on the database clock,
     * earliest due first, skipping rows another transaction holds ({@code FOR UPDATE SKIP LOCKED}), and returns them.
     */
    List<Lapsed> expirePage(T unitOfWork, int limit);
}
