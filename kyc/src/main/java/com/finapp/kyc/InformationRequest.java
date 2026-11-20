package com.finapp.kyc;

import com.finapp.sharedkernel.id.IdGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A reviewer's request to a case's customer for more information (`P17-TSK-126`, ADR-0155 decision 2): the entity that holds a
 * case {@code AWAITING_CUSTOMER}.
 *
 * <p><strong>A case is {@code AWAITING_CUSTOMER} exactly while it has one {@code OPEN} request</strong>: the request is written in
 * the transaction that moves the case there, and closed in the one that moves it back, and kyc {@code V016}'s unique partial
 * index admits one open request per case. The request's identity and text never change; it closes once, answered or expired,
 * by its machine ({@link InformationRequestStatus}) here and by V016's trigger in the database.
 *
 * <p><strong>The deadline is {@link #ANSWER_WINDOW} after the request</strong>, judged on the database clock - the one clock
 * every instance shares - by the answer's admission and by the expiry sweep alike, so whether an answer counts never depends on
 * when a sweeper ran. A policy value, changed in review like the policy version.
 */
public final class InformationRequest {

    /** How long the customer has to answer: fourteen days from the request (ADR-0155 decision 7). */
    public static final Duration ANSWER_WINDOW = Duration.ofDays(14);

    private final InformationRequestId id;
    private final KycCaseId caseId;
    private final InformationRequestReason reason;
    private final CustomerRequestText text;
    private final UUID requestedBy;
    private final Instant requestedAt;
    private final Instant dueAt;
    private final String correlationId;
    private final InformationRequestStatus status;
    private final Optional<Instant> closedAt;
    private final Optional<CustomerAnswer> answer;
    private final Optional<UUID> answeredBy;

    private InformationRequest(
            InformationRequestId id,
            KycCaseId caseId,
            InformationRequestReason reason,
            CustomerRequestText text,
            UUID requestedBy,
            Instant requestedAt,
            Instant dueAt,
            String correlationId,
            InformationRequestStatus status,
            Optional<Instant> closedAt,
            Optional<CustomerAnswer> answer,
            Optional<UUID> answeredBy) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.caseId = Objects.requireNonNull(caseId, "caseId must not be null");
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
        this.text = Objects.requireNonNull(text, "text must not be null");
        this.requestedBy = Objects.requireNonNull(requestedBy, "requestedBy must not be null");
        this.requestedAt = Objects.requireNonNull(requestedAt, "requestedAt must not be null");
        this.dueAt = Objects.requireNonNull(dueAt, "dueAt must not be null");
        this.correlationId = Objects.requireNonNull(correlationId, "correlationId must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.closedAt = Objects.requireNonNull(closedAt, "closedAt must not be null");
        this.answer = Objects.requireNonNull(answer, "answer must not be null");
        this.answeredBy = Objects.requireNonNull(answeredBy, "answeredBy must not be null");
        if (!dueAt.isAfter(requestedAt)) {
            throw new IllegalArgumentException("a request's deadline follows its asking");
        }
        if ((status == InformationRequestStatus.OPEN) == closedAt.isPresent()) {
            throw new IllegalArgumentException("a request is closed exactly when it is no longer OPEN");
        }
        if ((status == InformationRequestStatus.ANSWERED) != answer.isPresent()
                || answer.isPresent() != answeredBy.isPresent()) {
            throw new IllegalArgumentException("an answer and its author stand exactly on an ANSWERED request");
        }
    }

    /**
     * A new {@code OPEN} request on {@code caseId}, asked now by {@code requestedBy} (the reviewer's identity), due
     * {@link #ANSWER_WINDOW} later. {@code correlationId} is the asking flow's, so the expiry's record joins it.
     */
    public static InformationRequest open(
            IdGenerator ids,
            Clock clock,
            KycCaseId caseId,
            InformationRequestReason reason,
            CustomerRequestText text,
            UUID requestedBy,
            String correlationId) {
        Objects.requireNonNull(ids, "ids must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        Instant now = Instant.now(clock);
        return new InformationRequest(InformationRequestId.next(ids), caseId, reason, text, requestedBy, now,
                now.plus(ANSWER_WINDOW), correlationId, InformationRequestStatus.OPEN, Optional.empty(), Optional.empty(),
                Optional.empty());
    }

    /** Reconstitutes from storage. Applies no transition rules: the row was already valid. */
    public static InformationRequest rehydrate(
            InformationRequestId id,
            KycCaseId caseId,
            InformationRequestReason reason,
            CustomerRequestText text,
            UUID requestedBy,
            Instant requestedAt,
            Instant dueAt,
            String correlationId,
            InformationRequestStatus status,
            Optional<Instant> closedAt,
            Optional<CustomerAnswer> answer,
            Optional<UUID> answeredBy) {
        return new InformationRequest(id, caseId, reason, text, requestedBy, requestedAt, dueAt, correlationId, status,
                closedAt, answer, answeredBy);
    }

    /** {@code OPEN → ANSWERED}, at {@code at}, by the customer {@code answeredBy}. */
    public InformationRequest answer(CustomerAnswer customerAnswer, UUID answeredByCustomer, Instant at) {
        Objects.requireNonNull(customerAnswer, "customerAnswer must not be null");
        Objects.requireNonNull(answeredByCustomer, "answeredByCustomer must not be null");
        return close(InformationRequestStatus.ANSWERED, at, Optional.of(customerAnswer), Optional.of(answeredByCustomer));
    }

    /** {@code OPEN → EXPIRED}, at {@code at}: the deadline passed unanswered. */
    public InformationRequest expire(Instant at) {
        return close(InformationRequestStatus.EXPIRED, at, Optional.empty(), Optional.empty());
    }

    /** Whether the deadline has passed at {@code now}: the database's clock in production, judged in the statement. */
    public boolean lapsedAt(Instant now) {
        return !dueAt.isAfter(now);
    }

    private InformationRequest close(
            InformationRequestStatus target, Instant at, Optional<CustomerAnswer> customerAnswer, Optional<UUID> author) {
        Objects.requireNonNull(at, "at must not be null");
        if (!status.canTransitionTo(target)) {
            throw new IllegalInformationRequestTransitionException(id, status, target);
        }
        return new InformationRequest(id, caseId, reason, text, requestedBy, requestedAt, dueAt, correlationId, target,
                Optional.of(at.isBefore(requestedAt) ? requestedAt : at), customerAnswer, author);
    }

    public InformationRequestId id() {
        return id;
    }

    public KycCaseId caseId() {
        return caseId;
    }

    /** Internal: never shown to the customer. */
    public InformationRequestReason reason() {
        return reason;
    }

    /** The screened text the customer reads. */
    public CustomerRequestText text() {
        return text;
    }

    /** The reviewer's identity. */
    public UUID requestedBy() {
        return requestedBy;
    }

    public Instant requestedAt() {
        return requestedAt;
    }

    /** The deadline: {@link #ANSWER_WINDOW} after {@link #requestedAt()}. */
    public Instant dueAt() {
        return dueAt;
    }

    /** The asking flow's correlation, carried to the expiry's record. */
    public String correlationId() {
        return correlationId;
    }

    public InformationRequestStatus status() {
        return status;
    }

    public boolean isOpen() {
        return status == InformationRequestStatus.OPEN;
    }

    public Optional<Instant> closedAt() {
        return closedAt;
    }

    /** The customer's answer, on an {@code ANSWERED} request only. RESTRICTED-PII. */
    public Optional<CustomerAnswer> answer() {
        return answer;
    }

    /** The customer's identity, on an {@code ANSWERED} request only. */
    public Optional<UUID> answeredBy() {
        return answeredBy;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof InformationRequest request && id.equals(request.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    /** Identifiers and a status: never the text or the answer. */
    @Override
    public String toString() {
        return "InformationRequest[" + id + ", case=" + caseId + ", " + reason + ", " + status + "]";
    }
}
