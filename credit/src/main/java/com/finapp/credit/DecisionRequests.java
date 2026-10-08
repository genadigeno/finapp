package com.finapp.credit;

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
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * A customer's credit decision requests (`P10-TSK-014`; CREDIT_DECISIONING_LIFECYCLES.md section 3.1, ADR-0087;
 * {@code INV-CRD-03}, {@code INV-CRD-06}, {@code INV-CRD-12}): the submission, the cancellation and the owner's read,
 * each on the caller's unit of work - the door's idempotency claim wraps them.
 *
 * <p><strong>The submission, in one transaction and in this order</strong>: the party's standing (a verified customer,
 * read authoritatively); the product offered (an {@code ACTIVE} policy); the amount and term within the product's
 * bounds; a current consent basis for every source kind that active policy reads - refused before any provider is
 * asked, nothing written; the profile ensured; the request born {@code SUBMITTED} under the partial unique, with its
 * history row and {@code credit.CreditDecisionRequested}. Ten submissions with ten keys for one party and product leave
 * one open request; every other is {@link DecisionRequestOpen}, naming it.
 *
 * <p>No row lock is taken by the submission: the standing and the consent gate are plain authoritative reads under
 * {@code READ COMMITTED} in the acting transaction, and the partial unique is the one arbiter. The cancellation takes
 * the request {@code FOR UPDATE} (lock order element (2)) and moves it conditionally.
 */
@RequiredArgsConstructor
public final class DecisionRequests {

    static final String REQUESTED_EVENT = "credit.CreditDecisionRequested";
    static final String CLOSED_EVENT = "credit.CreditDecisionRequestClosed";
    static final String AGGREGATE_TYPE = "decision_request";
    static final int EVENT_VERSION = 1;

    @NonNull private final DecisionRequestStore requests;
    @NonNull private final CreditProfiles<Connection> profiles;
    @NonNull private final CreditPolicyStore policies;
    @NonNull private final CreditPartyStanding<Connection> standing;
    @NonNull private final CreditConsentGate<Connection> consents;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** The terms as the customer submitted them, before any judgement. */
    public record Terms(
            CreditProduct product,
            Money requested,
            Optional<Integer> termMonths,
            Optional<Money> declaredMonthlyIncome,
            Optional<Money> declaredMonthlyExpenditure) {

        public Terms {
            Objects.requireNonNull(product, "product");
            Objects.requireNonNull(requested, "requested");
            Objects.requireNonNull(termMonths, "termMonths");
            Objects.requireNonNull(declaredMonthlyIncome, "declaredMonthlyIncome");
            Objects.requireNonNull(declaredMonthlyExpenditure, "declaredMonthlyExpenditure");
        }

        @Override
        public String toString() {
            return "Terms[" + product + ", redacted]";
        }
    }

    /** Submits {@code terms} for {@code party}; the born request, read back. */
    public DecisionRequest submit(Connection unitOfWork, UUID party, Terms terms, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(party, "party");
        Objects.requireNonNull(terms, "terms");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        if (!standing.inGoodStanding(unitOfWork, party)) {
            throw new ApplicantNotEligible();
        }
        CreditPolicyVersionId active = policies.active(unitOfWork, terms.product())
                .orElseThrow(() -> new ProductNotOffered(terms.product()));
        DecisionRequest.Application application = DecisionRequest.Application.submitted(terms.product(), terms.requested(),
                terms.termMonths(), terms.declaredMonthlyIncome(), terms.declaredMonthlyExpenditure());
        CreditPolicy policy = policies.policy(unitOfWork, active)
                .orElseThrow(() -> new IllegalStateException("the active policy version is unreadable"))
                .policy();
        for (CreditSourceKind kind : policy.sourceKinds()) {
            if (!consents.permits(unitOfWork, party, kind)) {
                throw new ConsentAbsent(kind);
            }
        }
        CreditProfile profile = profiles.ensure(unitOfWork, party);
        DecisionRequestId id = DecisionRequestId.next(ids);
        if (!requests.insert(unitOfWork, id, party, profile.id(), application, terms.product().requestValidity(),
                correlation.value(), actor)) {
            throw new DecisionRequestOpen(requests.openFor(unitOfWork, party, terms.product())
                    .orElseThrow(() -> new IllegalStateException(
                            "the open request that refused this one closed before it could be named - retry")));
        }
        DecisionRequest born = requests.ownedBy(unitOfWork, id, party)
                .orElseThrow(() -> new IllegalStateException("a decision request is readable once written"));
        EventPayload payload = EventPayload.of()
                .with("decisionRequestId", id.value().toString())
                .with("partyId", party.toString())
                .with("product", terms.product().name())
                // Minor units beside the currency, an instant as epoch milliseconds - the payload's own vocabulary.
                .with("requestedMinor", Long.toString(application.requested().minorUnits()))
                .with("currency", application.requested().currency().code())
                .with("expiresAtEpochMilli", Long.toString(born.expiresAt().toEpochMilli()));
        if (application.termMonths().isPresent()) {
            payload = payload.with("termMonths", application.termMonths().get().toString());
        }
        publish(unitOfWork, REQUESTED_EVENT, id, payload, correlation);
        return born;
    }

    /** Cancels {@code party}'s request {@code id}, before it is evaluated; the request as it now stands. */
    public DecisionRequest cancel(
            Connection unitOfWork, DecisionRequestId id, UUID party, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(party, "party");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        DecisionRequest request = requests.lockOwnedBy(unitOfWork, id, party).orElseThrow(DecisionRequestNotFound::new);
        if (!DecisionRequestStatus.CANCELLABLE.contains(request.status())
                || !requests.transition(unitOfWork, id, DecisionRequestStatus.CANCELLABLE, DecisionRequestStatus.CANCELLED,
                        Optional.empty(), actor, Optional.empty())) {
            throw new RequestNotCancellable(request.status());
        }
        publish(unitOfWork, CLOSED_EVENT, id, EventPayload.of()
                .with("decisionRequestId", id.value().toString())
                .with("status", DecisionRequestStatus.CANCELLED.name()), correlation);
        audit.append(unitOfWork, new AuditRecord(
                AuditId.next(ids),
                actor,
                clock.instant(),
                CreditAuditAction.DECISION_REQUEST_CANCELLED,
                AGGREGATE_TYPE,
                id.value().toString(),
                Optional.empty(),
                AuditOutcome.SUCCEEDED,
                correlation,
                Optional.of("decision request " + id.value() + " cancelled from " + request.status())));
        return requests.ownedBy(unitOfWork, id, party)
                .orElseThrow(() -> new IllegalStateException("a decision request is readable once written"));
    }

    /** {@code party}'s request {@code id}; another party's is the same empty answer as an absent one. */
    public Optional<DecisionRequest> read(Connection unitOfWork, DecisionRequestId id, UUID party) {
        return requests.ownedBy(unitOfWork, id, party);
    }

    private void publish(
            Connection unitOfWork, String type, DecisionRequestId id, EventPayload payload, CorrelationId correlation) {
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        type,
                        EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        id,
                        AGGREGATE_TYPE,
                        clock.instant(),
                        CreditDataCollection.PRODUCER,
                        correlation,
                        CausationId.of(correlation.value())),
                payload.toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    // ------------------------------------------------------------------ the refusals, each writing nothing

    /** The caller is not a verified customer in good standing ({@code credit.ApplicantNotEligible}). */
    public static final class ApplicantNotEligible extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ApplicantNotEligible() {
            super("the applicant is not a verified customer in good standing");
        }
    }

    /** No {@code ACTIVE} policy offers the product ({@code credit.ProductNotOffered}). */
    public static final class ProductNotOffered extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ProductNotOffered(CreditProduct product) {
            super(product + " is not offered: no policy version is active");
        }
    }

    /** No current consent basis for a source kind the active policy reads - nothing is asked of any provider. */
    public static final class ConsentAbsent extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final CreditSourceKind kind;

        ConsentAbsent(CreditSourceKind kind) {
            super("no current consent basis for " + kind);
            this.kind = kind;
        }

        public CreditSourceKind kind() {
            return kind;
        }
    }

    /** The party already holds an open request for the product ({@code credit.DecisionRequestOpen}), named. */
    public static final class DecisionRequestOpen extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final transient DecisionRequestId open;

        DecisionRequestOpen(DecisionRequestId open) {
            super("decision request " + open.value() + " is open for this product");
            this.open = open;
        }

        public DecisionRequestId open() {
            return open;
        }
    }

    /** No request of the caller's has this id ({@code credit.NotFound}) - another party's included. */
    public static final class DecisionRequestNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        DecisionRequestNotFound() {
            super("no such decision request");
        }
    }

    /** The request is past cancellation ({@code credit.RequestNotCancellable}). */
    public static final class RequestNotCancellable extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        RequestNotCancellable(DecisionRequestStatus status) {
            super("a " + status + " decision request can no longer be cancelled");
        }
    }
}
