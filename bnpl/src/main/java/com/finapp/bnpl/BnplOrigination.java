package com.finapp.bnpl;

import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The origination step - T-off (`P12-TSK-011`; {@code PHASE_12_PLAN.md} sections 5.1, 5.2, 7.1 T-off, 7.5 and 12.1,
 * {@code BNPL_LIFECYCLES.md} sections 4.2 and 4.3; {@code INV-BNPL-03}, {@code INV-LIFE-01}, {@code INV-LIFE-02},
 * {@code INV-CRD-04}, {@code INV-EVT-04}; lending's {@code LoanOrigination}): every application {@code AWAITING_DECISION}
 * leaves that state exactly once, on credit's authoritative answer and the session's own state, and every offer past
 * its expiry expires. A system act - no person originates ({@code PHASE_12_PLAN.md} section 11).
 *
 * <p><strong>One application, one transaction, in this order</strong> ({@link #originate}): (B1) the application
 * {@code FOR UPDATE}, acted on only while {@code AWAITING_DECISION}; credit's request read through
 * {@link BnplCreditDecisions#decisionFor} - still open: the session read ({@link BnplCheckoutSessions#byRef}, its deadline
 * on the database clock) and, if no longer {@code OPEN} or past its deadline, credit's request cancelled while it is still
 * cancellable and the application {@code CLOSED_UNDECIDED (SESSION_CLOSED)} - an approval arriving later lapses unconsumed;
 * otherwise nothing (the claim's permit lapses and a later sweep asks again); closed without a decision:
 * {@code CLOSED_UNDECIDED (CREDIT_REQUEST_*)}; declined: {@code DECLINED}; approved: the session still open and unexpired,
 * the approval EXACTLY the session's gross (below it: {@code CLOSED_UNDECIDED (APPROVED_BELOW_PURCHASE)} - the price is
 * fixed), the product's {@code ACTIVE} terms version pinned {@code FOR SHARE} (none to pin: the step waits, nothing
 * written - the Phase 12 transition gate's A3-I1) and offering the plan (else {@code PRODUCT_NOT_OFFERED} - the version
 * recorded on the application is informational), the database clock read after
 * the pin (a lapsed approval: {@code DECISION_LAPSED}), the illustrative schedule from the pinned engine, and the offer
 * born with its canonical terms and their hash, the application {@code OFFERED} beside it. The offering instant and the
 * expiry are the insert's own database stamps - {@code LEAST(the decision's validity, the session's expiry, offered_at +
 * the pinned offer validity)} (A17) - never an instance's clock.
 *
 * <p><strong>Ten sweepers, one offer</strong>: the claim's {@code SKIP LOCKED} gives disjoint pages; a step whose permit
 * lapsed under a slow sweeper waits on B1 and finds the application moved - {@link Outcome.Kind#NOTHING}, nothing written;
 * {@code UNIQUE (application_id)} and {@code UNIQUE (decision_id)} beneath. <strong>An event is a hint</strong>
 * ({@link #hintDecision}, {@link #hintRequest}, {@link #hintSession}): it makes the application due now and nothing else -
 * delivered twice, late, forged or never, the read decides, within one sweep interval.
 *
 * <p><strong>The merchant learns one bit</strong> - {@code bnpl.BnplEligibilityAssessed} with the session and
 * {@code ELIGIBLE} (an offer) or {@code NOT_ELIGIBLE} (a decline, or an approval below the purchase) - never a reason, a
 * score or an amount (OQ-12).
 */
@RequiredArgsConstructor
public final class BnplOrigination {

    public static final String OFFERED_EVENT = "bnpl.BnplOffered";
    public static final String OFFER_CLOSED_EVENT = "bnpl.BnplOfferClosed";
    public static final String ELIGIBILITY_EVENT = "bnpl.BnplEligibilityAssessed";

    /** The offer's history's and events' aggregate type. */
    public static final String OFFER_AGGREGATE_TYPE = "bnpl_offer";

    public static final String APPROVED_REASON = "DECISION_APPROVED";
    public static final String CREDIT_DECLINED_REASON = "CREDIT_DECLINED";
    public static final String EXPIRED_REASON = "OFFER_EXPIRED";

    @NonNull private final BnplApplicationStore applications;
    @NonNull private final BnplOfferStore offers;
    @NonNull private final BnplTermsAdministration terms;
    @NonNull private final BnplCreditDecisions credit;
    @NonNull private final BnplCheckoutSessions sessions;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** The merchant's one signal. */
    public enum Eligibility {
        ELIGIBLE,
        NOT_ELIGIBLE
    }

    /** What one origination step did - for the meters; never a figure. */
    public record Outcome(
            Kind kind,
            Optional<BnplProduct> product,
            Optional<BnplApplicationClosureReason> reason,
            Optional<Eligibility> eligibility) {

        public Outcome {
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(product, "product must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(eligibility, "eligibility must not be null");
        }

        static Outcome nothing() {
            return new Outcome(Kind.NOTHING, Optional.empty(), Optional.empty(), Optional.empty());
        }

        static Outcome waiting(BnplProduct product) {
            return new Outcome(Kind.WAITING, Optional.of(product), Optional.empty(), Optional.empty());
        }

        /** The step's effect. */
        public enum Kind {
            /** The application had already left {@code AWAITING_DECISION} - another step, or the customer, moved it. */
            NOTHING,
            /**
             * Credit's request is still open and the session still open - or credit approved and no terms version is
             * {@code ACTIVE} to pin (A3-I1): a later step reads again.
             */
            WAITING,
            /** The offer was born and the application {@code OFFERED}. */
            OFFERED,
            /** Credit declined: the application {@code DECLINED}. */
            DECLINED,
            /** Closed without an offer: {@code CLOSED_UNDECIDED} with its reason. */
            CLOSED_UNDECIDED
        }
    }

    // ------------------------------------------------------------------ the claims

    /** The applications due a step - see {@link BnplApplicationStore#claimAwaiting}. */
    public List<BnplApplicationId> claimApplications(Connection unitOfWork, int limit, Duration permit) {
        return applications.claimAwaiting(Objects.requireNonNull(unitOfWork, "unitOfWork"), limit,
                Objects.requireNonNull(permit, "permit"));
    }

    /** The offers due their expiry - see {@link BnplOfferStore#claimDue}. */
    public List<BnplOfferId> claimOffers(Connection unitOfWork, int limit, Duration permit) {
        return offers.claimDue(Objects.requireNonNull(unitOfWork, "unitOfWork"), limit,
                Objects.requireNonNull(permit, "permit"));
    }

    // ------------------------------------------------------------------ the hints

    /**
     * {@code credit.CreditDecisionRecorded}'s hint: the application whose request the decision {@code decision} answers
     * made due now - resolved through credit's own read, never the event's payload. {@code false} when there is nothing
     * to hint.
     */
    public boolean hintDecision(Connection unitOfWork, UUID decision) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        return credit.requestOf(unitOfWork, Objects.requireNonNull(decision, "decision"))
                .map(request -> applications.hintRequest(unitOfWork, request))
                .orElse(false);
    }

    /** {@code credit.CreditDecisionRequestClosed}'s hint: the application awaiting {@code decisionRequest} made due now. */
    public boolean hintRequest(Connection unitOfWork, UUID decisionRequest) {
        return applications.hintRequest(Objects.requireNonNull(unitOfWork, "unitOfWork"),
                Objects.requireNonNull(decisionRequest, "decisionRequest"));
    }

    /** {@code checkout.CheckoutSessionExpired}'s hint: the application awaiting a decision in the session made due now. */
    public boolean hintSession(Connection unitOfWork, UUID checkoutSession) {
        return applications.hintSession(Objects.requireNonNull(unitOfWork, "unitOfWork"),
                Objects.requireNonNull(checkoutSession, "checkoutSession"));
    }

    // ------------------------------------------------------------------ T-off

    /**
     * One application's origination step, on the caller's unit of work - a transaction of its own. The history and the
     * events carry the application's correlation: one flow from the submission to the offer.
     */
    public Outcome originate(Connection unitOfWork, BnplApplicationId id, Actor actor) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(actor, "actor");
        // B1 first: every judgement below is on the row as this lock found it.
        Optional<BnplApplication> locked = applications.lockClaimed(unitOfWork, id);
        if (locked.isEmpty() || locked.get().status() != BnplApplicationStatus.AWAITING_DECISION) {
            return Outcome.nothing();
        }
        BnplApplication application = locked.get();
        BnplCreditDecisions.CreditOutcome answer =
                credit.decisionFor(unitOfWork, application.decisionRequest(), application.party());
        return switch (answer) {
            case BnplCreditDecisions.CreditOutcome.Pending pending -> pending(unitOfWork, application, actor);
            case BnplCreditDecisions.CreditOutcome.Closed closed -> close(unitOfWork, application,
                    BnplApplicationStatus.CLOSED_UNDECIDED, Optional.of(closed.closure().reason()), actor);
            case BnplCreditDecisions.CreditOutcome.Declined declined -> close(unitOfWork, application,
                    BnplApplicationStatus.DECLINED, Optional.empty(), actor);
            case BnplCreditDecisions.CreditOutcome.Approved approved -> approved(unitOfWork, application, approved,
                    actor);
        };
    }

    /** Credit is still deciding: wait - unless the session has closed, which closes the application and the request. */
    private Outcome pending(Connection unitOfWork, BnplApplication application, Actor actor) {
        if (sessionOpen(unitOfWork, application, Optional.empty())) {
            return Outcome.waiting(application.product());
        }
        try {
            // Cancelled while still cancellable, under credit's own row lock and from the BNPL origin; once credit has
            // evaluated it, it decides, and its approval lapses unconsumed - nothing offers it.
            credit.cancel(unitOfWork, application.decisionRequest(), application.party(), actor,
                    application.correlation());
        } catch (BnplCreditDecisions.NotCancellable evaluated) {
            // Credit decides; the application closes all the same.
        }
        return close(unitOfWork, application, BnplApplicationStatus.CLOSED_UNDECIDED,
                Optional.of(BnplApplicationClosureReason.SESSION_CLOSED), actor);
    }

    private Outcome approved(Connection unitOfWork, BnplApplication application,
            BnplCreditDecisions.CreditOutcome.Approved approved, Actor actor) {
        if (approved.product() != application.product() || !approved.party().equals(application.party())) {
            throw new IllegalStateException("credit's decision is the application's own party's and product's");
        }
        if (!approved.termMonths().equals(application.plan().termMonths())) {
            throw new IllegalStateException("credit approves the plan's own term - pay-in-4 none, pay-in-N its months");
        }
        BnplCheckoutSessions.Session session = sessions.byRef(unitOfWork, application.checkoutSession())
                .orElseThrow(() -> new IllegalStateException(
                        "a checkout session bnpl's own record names does not exist - a broken database"));
        if (!session.open() || session.expired()) {
            return close(unitOfWork, application, BnplApplicationStatus.CLOSED_UNDECIDED,
                    Optional.of(BnplApplicationClosureReason.SESSION_CLOSED), actor);
        }
        Money gross = session.gross();
        if (!approved.amount().currency().equals(gross.currency())) {
            throw new IllegalStateException("credit approves in the purchase's currency");
        }
        int compared = approved.amount().compareTo(gross);
        if (compared > 0) {
            throw new IllegalStateException("credit approves at most its request - the purchase");
        }
        if (compared < 0) {
            // The price is fixed (section 12.1): a reduced approval is never offered.
            return close(unitOfWork, application, BnplApplicationStatus.CLOSED_UNDECIDED,
                    Optional.of(BnplApplicationClosureReason.APPROVED_BELOW_PURCHASE), actor);
        }
        BnplTermsVersion version;
        try {
            version = terms.pinActive(unitOfWork, application.product());
        } catch (BnplTermsAdministration.ProductNotOffered none) {
            // Nothing in force to pin (the Phase 12 transition gate's A3-I1; credit's DecisionProgress#collect): the step
            // waits - nothing written, the claim's permit lapses and a later sweep reads again - never a terminal closure.
            // The application was submitted under an ACTIVE version and a retirement commits only beside its successor,
            // so this is never the product's last word; the session's deadline above still closes it.
            return Outcome.waiting(application.product());
        }
        BnplTerms pinned = version.terms();
        if (!BnplApplications.offers(pinned, application.plan())) {
            return close(unitOfWork, application, BnplApplicationStatus.CLOSED_UNDECIDED,
                    Optional.of(BnplApplicationClosureReason.PRODUCT_NOT_OFFERED), actor);
        }
        // The database clock, read after B1 and the pin: what the step judges the lapse by. NOT the offering instant -
        // the insert stamps that itself, so a clock stepped back between this read and the insert cannot misplace it.
        Instant now = offers.databaseNow(unitOfWork);
        if (!approved.validUntil().isAfter(now)) {
            return close(unitOfWork, application, BnplApplicationStatus.CLOSED_UNDECIDED,
                    Optional.of(BnplApplicationClosureReason.DECISION_LAPSED), actor);
        }
        if (!session.expiresAt().isAfter(now)) {
            return close(unitOfWork, application, BnplApplicationStatus.CLOSED_UNDECIDED,
                    Optional.of(BnplApplicationClosureReason.SESSION_CLOSED), actor);
        }
        ZoneId zone = ZoneId.of(pinned.servicingZone());
        BnplIllustration illustration = BnplIllustration.of(pinned, application.plan(), gross,
                LocalDate.ofInstant(now, zone));

        BnplOfferId offerId = BnplOfferId.next(ids);
        BnplOfferStore.Offered born = offers.insert(unitOfWork, new BnplOfferStore.NewOffer(offerId, application.id(),
                application.party(), application.product(), application.plan(), approved.decision(), version.id(),
                application.checkoutSession(), approved.validUntil(), session.expiresAt(), application.correlation()));
        // The illustration starts on the offering instant's business date - one clock's with the expiry; only a step whose
        // read fell on another day than the insert re-projects.
        LocalDate offeredOn = LocalDate.ofInstant(born.offeredAt(), zone);
        if (!offeredOn.equals(illustration.start())) {
            illustration = BnplIllustration.of(pinned, application.plan(), gross, offeredOn);
        }
        String canonical = BnplOfferTerms.render(new BnplOfferTerms.Content(offerId, application.id(),
                approved.decision(), version.id(), version.row().version(), pinned, application.plan(), illustration));
        String termsHex = java.util.HexFormat.of().formatHex(BnplOfferTerms.sha256(canonical));
        byte[] documentSha256 = BnplOfferTerms.sha256(
                BnplOfferDocument.render(offerId, pinned, application.plan(), illustration, termsHex));
        BnplOfferStore.StoredTerms stored = BnplOfferStore.StoredTerms.of(application.plan(),
                pinned.nominalAnnualRate(), illustration, canonical, documentSha256);

        offers.insertTerms(unitOfWork, offerId, stored);
        offers.appendEvent(unitOfWork, new BnplOfferStore.HistoryRow(ids.next(), offerId, Optional.empty(),
                BnplOfferStatus.OFFERED, APPROVED_REASON, actor, application.correlation()));
        move(unitOfWork, application, BnplApplicationStatus.OFFERED, Optional.empty(), APPROVED_REASON, actor);
        publish(unitOfWork, OFFERED_EVENT, offerId, OFFER_AGGREGATE_TYPE, EventPayload.of()
                .with("offerId", offerId.value().toString())
                .with("applicationId", application.id().value().toString())
                .with("decisionId", approved.decision().toString())
                .with("product", application.product().name())
                .with("plan", application.plan().name())
                .with("checkoutSessionId", application.checkoutSession().toString())
                .with("expiresAtEpochMilli", Long.toString(born.expiresAt().toEpochMilli()))
                .with("termsVersionId", version.id().value().toString())
                .with("termsVersion", Integer.toString(version.row().version()))
                .with("termsSha256", stored.termsSha256Hex()), application.correlation());
        assessed(unitOfWork, application, Eligibility.ELIGIBLE);
        return new Outcome(Outcome.Kind.OFFERED, Optional.of(application.product()), Optional.empty(),
                Optional.of(Eligibility.ELIGIBLE));
    }

    /** Whether the application's session is still open and before its deadline on the database clock. */
    private boolean sessionOpen(Connection unitOfWork, BnplApplication application, Optional<Instant> now) {
        BnplCheckoutSessions.Session session = sessions.byRef(unitOfWork, application.checkoutSession())
                .orElseThrow(() -> new IllegalStateException(
                        "a checkout session bnpl's own record names does not exist - a broken database"));
        return session.open() && !session.expired() && now.map(session.expiresAt()::isAfter).orElse(true);
    }

    private Outcome close(Connection unitOfWork, BnplApplication application, BnplApplicationStatus to,
            Optional<BnplApplicationClosureReason> reason, Actor actor) {
        String code = reason.map(Enum::name).orElse(CREDIT_DECLINED_REASON);
        move(unitOfWork, application, to, reason, code, actor);
        publish(unitOfWork, BnplApplications.CLOSED_EVENT, application.id(), BnplApplications.AGGREGATE_TYPE,
                EventPayload.of()
                        .with("applicationId", application.id().value().toString())
                        .with("product", application.product().name())
                        .with("checkoutSessionId", application.checkoutSession().toString())
                        .with("decisionRequestId", application.decisionRequest().toString())
                        .with("status", to.name())
                        .with("reason", code),
                application.correlation());
        Optional<Eligibility> eligibility = to == BnplApplicationStatus.DECLINED
                        || reason.equals(Optional.of(BnplApplicationClosureReason.APPROVED_BELOW_PURCHASE))
                ? Optional.of(Eligibility.NOT_ELIGIBLE)
                : Optional.empty();
        eligibility.ifPresent(outcome -> assessed(unitOfWork, application, outcome));
        return new Outcome(to == BnplApplicationStatus.DECLINED ? Outcome.Kind.DECLINED : Outcome.Kind.CLOSED_UNDECIDED,
                Optional.of(application.product()), reason, eligibility);
    }

    private void move(Connection unitOfWork, BnplApplication application, BnplApplicationStatus to,
            Optional<BnplApplicationClosureReason> reason, String code, Actor actor) {
        if (!applications.close(unitOfWork, application.id(), to, reason)) {
            throw new IllegalStateException(
                    "the locked application moved under its own row lock: the FOR UPDATE protocol was bypassed");
        }
        applications.appendEvent(unitOfWork, new BnplApplicationStore.HistoryRow(ids.next(), application.id(),
                Optional.of(BnplApplicationStatus.AWAITING_DECISION), to, code, actor, application.correlation()));
    }

    /** The merchant's one signal: the session and the outcome - never a reason, a score or an amount (OQ-12). */
    private void assessed(Connection unitOfWork, BnplApplication application, Eligibility outcome) {
        publish(unitOfWork, ELIGIBILITY_EVENT, application.id(), BnplApplications.AGGREGATE_TYPE, EventPayload.of()
                .with("checkoutSessionId", application.checkoutSession().toString())
                .with("outcome", outcome.name()), application.correlation());
    }

    // ------------------------------------------------------------------ expiry

    /**
     * One claimed offer's expiry, on the caller's unit of work: (B2) the offer {@code FOR UPDATE}, {@code EXPIRED} only
     * while {@code OFFERED} and {@code expires_at <= clock_timestamp()} - the complement of the decline's and the
     * acceptance's condition, so exactly one wins at the boundary. An offer claimed before its expiry has its permit set
     * back to the expiry. The product of the offer that expired, or empty.
     */
    public Optional<BnplProduct> expire(Connection unitOfWork, BnplOfferId id, Actor actor) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(actor, "actor");
        Optional<BnplOffer> locked = offers.lockClaimed(unitOfWork, id);
        if (locked.isEmpty() || locked.get().status() != BnplOfferStatus.OFFERED) {
            return Optional.empty();
        }
        BnplOffer offer = locked.get();
        if (!offers.expire(unitOfWork, id)) {
            offers.permitAtExpiry(unitOfWork, id);
            return Optional.empty();
        }
        offers.appendEvent(unitOfWork, new BnplOfferStore.HistoryRow(ids.next(), id,
                Optional.of(BnplOfferStatus.OFFERED), BnplOfferStatus.EXPIRED, EXPIRED_REASON, actor,
                offer.correlation()));
        publishClosed(unitOfWork, outbox, ids, clock, offer, BnplOfferStatus.EXPIRED, EXPIRED_REASON,
                offer.correlation());
        return Optional.of(offer.product());
    }

    // ------------------------------------------------------------------ plumbing

    /** {@code bnpl.BnplOfferClosed} - the offer declined or expired; never a figure. */
    static void publishClosed(Connection unitOfWork, OutboxWriter<Connection> outbox, IdGenerator ids, Clock clock,
            BnplOffer offer, BnplOfferStatus status, String reason, CorrelationId correlation) {
        write(unitOfWork, outbox, ids, clock, OFFER_CLOSED_EVENT, offer.id(), OFFER_AGGREGATE_TYPE, EventPayload.of()
                .with("offerId", offer.id().value().toString())
                .with("applicationId", offer.application().value().toString())
                .with("product", offer.product().name())
                .with("plan", offer.plan().name())
                .with("checkoutSessionId", offer.checkoutSession().toString())
                .with("status", status.name())
                .with("reason", reason), correlation);
    }

    private void publish(Connection unitOfWork, String type, EntityId aggregate, String aggregateType,
            EventPayload payload, CorrelationId correlation) {
        write(unitOfWork, outbox, ids, clock, type, aggregate, aggregateType, payload, correlation);
    }

    private static void write(Connection unitOfWork, OutboxWriter<Connection> outbox, IdGenerator ids, Clock clock,
            String type, EntityId aggregate, String aggregateType, EventPayload payload, CorrelationId correlation) {
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        type,
                        BnplTermsAdministration.EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        aggregate,
                        aggregateType,
                        clock.instant(),
                        BnplTermsAdministration.PRODUCER,
                        correlation,
                        CausationId.of(correlation.value())),
                // Never an amount: the session holds what is bought, credit's decision what was approved.
                payload.toBytes(),
                EventPayload.MEDIA_TYPE);
    }
}
