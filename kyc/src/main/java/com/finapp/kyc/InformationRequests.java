package com.finapp.kyc;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The three acts on a case awaiting its customer (`P17-TSK-126`, ADR-0155), each inside its caller's transaction, each a named
 * edge of the case machine ({@link KycCaseTransition}) taken by the case's conditional move and written beside its request row
 * and its audit record - so a case is {@code AWAITING_CUSTOMER} exactly while it has one {@code OPEN} request.
 *
 * <ul>
 *   <li>{@link #request}: {@code INFORMATION_REQUESTED} - the reviewer's; the case's conditional move is the arbiter between
 *       racing reviewers, then the request, {@code kyc.InformationRequested} and {@code kyc.CustomerInformationRequested} on the
 *       outbox;</li>
 *   <li>{@link #answer}: {@code CUSTOMER_ANSWERED} - the customer's; the request's conditional close (open, not yet due on the
 *       database clock) is the arbiter, then the case's return and {@code kyc.InformationRequestAnswered};</li>
 *   <li>{@link #expirePage}: {@code REQUEST_EXPIRED} - the platform's; the store's {@code SKIP LOCKED} page on the database clock,
 *       then each case's return and {@code kyc.InformationRequestExpired}.</li>
 * </ul>
 *
 * <p>After a return the caller re-asks the case's readiness in a transaction of its own (ADR-0155 decision 8): a reviewer may have
 * resolved the last review task while the customer held the case.
 */
@RequiredArgsConstructor
public final class InformationRequests {

    /** The integration event the deferred notifier consumes (ADR-0155 decision 5): identifiers and the deadline, never words. */
    public static final String REQUESTED_EVENT = "kyc.CustomerInformationRequested";

    static final String PRODUCER = "kyc";
    static final int EVENT_VERSION = 1;

    /** The audit target and the event's aggregate: the case, whose state the request changes. */
    static final String TARGET_TYPE = "KycCase";

    /** What a reviewer's request came to. */
    public enum Requested {
        /** The case awaits its customer; the request, its record and its event are written. */
        REQUESTED,
        /** No such case. */
        NOT_FOUND,
        /** The case is not in review: checks still running, ready for a decision, or decided. */
        NOT_IN_REVIEW,
        /** The case already awaits its customer: one request at a time. */
        ALREADY_AWAITING,
        /** A KYB case: its customer has no answer door yet (ADR-0155 decision 9). */
        NOT_OFFERED
    }

    @NonNull private final KycCaseStore<Connection> cases;
    @NonNull private final InformationRequestStore<Connection> requests;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /**
     * The reviewer {@code reviewer} (identity {@code reviewerIdentity}) asks the customer of {@code caseId}, in
     * {@code unitOfWork}, under the flow {@code correlation}.
     */
    public Requested request(
            Connection unitOfWork,
            KycCaseId caseId,
            InformationRequestReason reason,
            CustomerRequestText text,
            Actor reviewer,
            UUID reviewerIdentity,
            CorrelationId correlation) {
        Objects.requireNonNull(caseId, "caseId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(text, "text must not be null");
        Objects.requireNonNull(reviewer, "reviewer must not be null");
        Objects.requireNonNull(reviewerIdentity, "reviewerIdentity must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        Optional<KycCase> found = cases.findById(unitOfWork, caseId);
        if (found.isEmpty()) {
            return Requested.NOT_FOUND;
        }
        KycCase kycCase = found.get();
        if (kycCase.kind() == KycCaseKind.KYB) {
            return Requested.NOT_OFFERED;
        }
        // The aggregate judges the edge (INV-LIFE-02); the conditional move makes one instance's application of it stick. Of N
        // reviewers asking at once, one moves the case and N-1 find it awaiting.
        Instant now = Instant.now(clock);
        KycCaseTransition edge = KycCaseTransition.INFORMATION_REQUESTED;
        if (kycCase.status() != edge.from()
                || !cases.moveStatus(unitOfWork, caseId, edge.from(), edge.to(), now)) {
            KycCaseStatus standing = kycCase.status() != edge.from()
                    ? kycCase.status()
                    : cases.findById(unitOfWork, caseId).map(KycCase::status).orElse(kycCase.status());
            return standing == KycCaseStatus.AWAITING_CUSTOMER ? Requested.ALREADY_AWAITING : Requested.NOT_IN_REVIEW;
        }
        kycCase.awaitCustomer(clock);
        InformationRequest request =
                InformationRequest.open(ids, clock, caseId, reason, text, reviewerIdentity, correlation.value());
        requests.open(unitOfWork, request);
        audit.append(unitOfWork, new AuditRecord(
                AuditId.next(ids),
                reviewer,
                now,
                KycAuditAction.INFORMATION_REQUESTED,
                TARGET_TYPE,
                caseId.value().toString(),
                Optional.of(reason.name()),
                AuditOutcome.SUCCEEDED,
                correlation,
                Optional.of("customer=" + kycCase.customerId() + ", request=" + request.id().value()
                        + ", dueAt=" + request.dueAt())));
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        REQUESTED_EVENT,
                        EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        caseId,
                        TARGET_TYPE,
                        now,
                        PRODUCER,
                        correlation,
                        // A flow root: nothing caused the request but the reviewer's own (the PartyRegistration idiom).
                        CausationId.of(correlation.value())),
                EventPayload.of()
                        .with("caseId", caseId.value().toString())
                        .with("customerId", kycCase.customerId().toString())
                        .with("requestId", request.id().value().toString())
                        .with("dueAtEpochMilli", Long.toString(request.dueAt().toEpochMilli()))
                        .toBytes(),
                EventPayload.MEDIA_TYPE);
        return Requested.REQUESTED;
    }

    /**
     * The customer {@code customer} (identity {@code customerIdentity}) answers the open request of {@code openCase}, their own
     * open case, in {@code unitOfWork}. Empty when there is nothing to answer - none open, or its deadline passed on the database
     * clock; the answered request otherwise, its case back {@code IN_REVIEW}.
     *
     * @throws IllegalStateException the request closed but its case was not awaiting the customer: the invariant the shared
     *     transaction keeps was broken, and nothing is committed
     */
    public Optional<InformationRequest> answer(
            Connection unitOfWork, KycCase openCase, CustomerAnswer answer, Actor customer, UUID customerIdentity,
            CorrelationId correlation) {
        Objects.requireNonNull(openCase, "openCase must not be null");
        Objects.requireNonNull(answer, "answer must not be null");
        Objects.requireNonNull(customer, "customer must not be null");
        Objects.requireNonNull(customerIdentity, "customerIdentity must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        Optional<InformationRequest> answered = requests.answer(unitOfWork, openCase.id(), answer, customerIdentity);
        if (answered.isEmpty()) {
            return Optional.empty();
        }
        Instant now = Instant.now(clock);
        KycCaseTransition edge = KycCaseTransition.CUSTOMER_ANSWERED;
        if (!cases.moveStatus(unitOfWork, openCase.id(), edge.from(), edge.to(), now)) {
            throw new IllegalStateException("an open information request stood on a case not awaiting its customer: "
                    + openCase.id());
        }
        audit.append(unitOfWork, new AuditRecord(
                AuditId.next(ids),
                customer,
                now,
                KycAuditAction.INFORMATION_REQUEST_ANSWERED,
                TARGET_TYPE,
                openCase.id().value().toString(),
                Optional.empty(),
                AuditOutcome.SUCCEEDED,
                correlation,
                Optional.of("customer=" + openCase.customerId() + ", request=" + answered.get().id().value())));
        return answered;
    }

    /**
     * Expires up to {@code limit} lapsed requests in {@code unitOfWork}, returning each case to review, under the platform's
     * actor {@code platform}. A case found not awaiting its customer is left where it stands and its record says so
     * ({@code caseReturned=false}): the lapse is the fact, and a sweep that refused it would stop every later expiry behind it.
     *
     * @return how many requests the page expired, and the cases it returned to review
     */
    public ExpiredPage expirePage(Connection unitOfWork, int limit, Actor platform) {
        Objects.requireNonNull(platform, "platform must not be null");
        List<KycCaseId> returned = new ArrayList<>();
        List<InformationRequestStore.Lapsed> page = requests.expirePage(unitOfWork, limit);
        for (InformationRequestStore.Lapsed lapsed : page) {
            Instant now = Instant.now(clock);
            KycCaseTransition edge = KycCaseTransition.REQUEST_EXPIRED;
            boolean moved = cases.moveStatus(unitOfWork, lapsed.caseId(), edge.from(), edge.to(), now);
            if (moved) {
                returned.add(lapsed.caseId());
            }
            audit.append(unitOfWork, new AuditRecord(
                    AuditId.next(ids),
                    platform,
                    now,
                    KycAuditAction.INFORMATION_REQUEST_EXPIRED,
                    TARGET_TYPE,
                    lapsed.caseId().value().toString(),
                    Optional.empty(),
                    AuditOutcome.SUCCEEDED,
                    CorrelationId.of(lapsed.correlationId()),
                    Optional.of("request=" + lapsed.id().value() + ", dueAt=" + lapsed.dueAt()
                            + ", caseReturned=" + moved)));
        }
        return new ExpiredPage(page.size(), List.copyOf(returned));
    }

    /** One expiry page: how many requests it closed, and which cases it returned to review. */
    public record ExpiredPage(int expired, List<KycCaseId> returned) {
        public ExpiredPage {
            Objects.requireNonNull(returned, "returned must not be null");
        }
    }
}
