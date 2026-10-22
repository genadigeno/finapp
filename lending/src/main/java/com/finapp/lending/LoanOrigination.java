package com.finapp.lending;

import com.finapp.creditmath.Schedule;
import com.finapp.creditmath.ScheduleOutcome;
import com.finapp.creditmath.ScheduleRefusal;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
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
 * The origination step - T-off (`P11-TSK-011`; {@code PHASE_11_PLAN.md} sections 5.1, 5.2, 7.1 and 7.5,
 * {@code LENDING_LIFECYCLES.md} sections 4.3 and 4.4; {@code INV-LND-05}, {@code INV-HIST-04}, {@code INV-EVT-04},
 * {@code INV-LIFE-01}): every application {@code AWAITING_DECISION} leaves that state exactly once, on credit's
 * authoritative answer, and every offer past its expiry expires. A system act - no person originates (section 11).
 *
 * <p><strong>One application, one transaction, in this order</strong> ({@link #originate}): (L1) the application
 * {@code FOR UPDATE} and acted on only while {@code AWAITING_DECISION}; credit's request read through
 * {@link LoanCreditDecisions#decisionFor} in the same transaction - still open: nothing (the claim's permit lapses and a
 * later sweep asks again); closed without a decision: {@code CLOSED_UNDECIDED}; declined: {@code DECLINED}; approved:
 * the product's terms version {@code ACTIVE} now pinned {@code FOR SHARE} ({@link LoanTermsAdministration#pinActive} -
 * the version recorded on the application is informational), the database clock read after the pin, and the offer born
 * with exactly the approved amount and term (A20), the pinned version's whole term set in its canonical terms, the loan's
 * illustrative schedule from the offering day or the line's minimum-payment rule (A5), the application moved
 * {@code OFFERED} beside it. The offering instant is the insert's own database stamp, never the step's read
 * (`P11-TSK-036`, {@code lending V028}). No {@code ACTIVE} terms to pin waits - nothing written, a later sweep reads
 * again (the Phase 12 transition gate's A3-I1; until then a terminal {@code PRODUCT_NOT_OFFERED} the activation race could
 * reach). An approval that cannot be offered closes the application {@code CLOSED_UNDECIDED} with its reason and no
 * offer: the chosen day no longer offered
 * ({@code REPAYMENT_DAY_NOT_OFFERED}), the decision already lapsed ({@code DECISION_LAPSED}), or a loan whose own
 * schedule would not amortise ({@code TERMS_NOT_AMORTISING}).
 *
 * <p><strong>Ten sweepers, one offer</strong>: the claim's {@code SKIP LOCKED} gives disjoint pages; a step whose permit
 * lapsed under a slow sweeper waits on the row lock and finds the application moved; {@code UNIQUE (application_id)}
 * and {@code UNIQUE (decision_id)} beneath. <strong>A credit event is a hint</strong> ({@link #hintDecision},
 * {@link #hintRequest}): it makes the application due now and nothing else - delivered twice, late, forged or never, the
 * read decides, within one sweep interval.
 */
@RequiredArgsConstructor
public final class LoanOrigination {

    public static final String OFFERED_EVENT = "lending.LoanOffered";
    public static final String OFFER_CLOSED_EVENT = "lending.LoanOfferClosed";

    /** The offer's history's and events' aggregate type. */
    static final String OFFER_AGGREGATE_TYPE = "loan_offer";

    public static final String APPROVED_REASON = "DECISION_APPROVED";
    public static final String CREDIT_DECLINED_REASON = "CREDIT_DECLINED";
    /**
     * A closure reason the step no longer writes (the Phase 12 transition gate's A3-I1: an empty pin waits) - kept, since
     * history rows written before it may carry it.
     */
    public static final String PRODUCT_NOT_OFFERED_REASON = "PRODUCT_NOT_OFFERED";
    public static final String DAY_NOT_OFFERED_REASON = "REPAYMENT_DAY_NOT_OFFERED";
    public static final String DECISION_LAPSED_REASON = "DECISION_LAPSED";
    public static final String NOT_AMORTISING_REASON = "TERMS_NOT_AMORTISING";
    public static final String EXPIRED_REASON = "OFFER_EXPIRED";

    @NonNull private final LoanApplicationStore applications;
    @NonNull private final LoanOfferStore offers;
    @NonNull private final LoanTermsAdministration terms;
    @NonNull private final LoanCreditDecisions credit;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** What one origination step did - for the meter; never a figure. */
    public record Outcome(Kind kind, Optional<LoanProduct> product, Optional<String> reason) {

        public Outcome {
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(product, "product must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
        }

        static Outcome nothing() {
            return new Outcome(Kind.NOTHING, Optional.empty(), Optional.empty());
        }

        static Outcome waiting(LoanProduct product) {
            return new Outcome(Kind.WAITING, Optional.of(product), Optional.empty());
        }

        /** The step's effect. */
        public enum Kind {
            /** The application had already left {@code AWAITING_DECISION} - another step, or the customer, moved it. */
            NOTHING,
            /** Credit's request is still open - or credit approved and no terms version is {@code ACTIVE} to pin (A3-I1). */
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

    /** The applications due a step - see {@link LoanApplicationStore#claimAwaiting}. */
    public List<LoanApplicationId> claimApplications(Connection unitOfWork, int limit, Duration permit) {
        return applications.claimAwaiting(Objects.requireNonNull(unitOfWork, "unitOfWork"), limit,
                Objects.requireNonNull(permit, "permit"));
    }

    /** The offers due their expiry - see {@link LoanOfferStore#claimDue}. */
    public List<LoanOfferId> claimOffers(Connection unitOfWork, int limit, Duration permit) {
        return offers.claimDue(Objects.requireNonNull(unitOfWork, "unitOfWork"), limit,
                Objects.requireNonNull(permit, "permit"));
    }

    // ------------------------------------------------------------------ the hints

    /**
     * {@code credit.CreditDecisionRecorded}'s hint: the application whose request the decision {@code decision} answers
     * made due now - resolved through credit's own read, never the event's payload. {@code false} when there is nothing
     * to hint - an unknown decision, or an application no longer awaiting one.
     */
    public boolean hintDecision(Connection unitOfWork, UUID decision) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        return credit.requestOf(unitOfWork, Objects.requireNonNull(decision, "decision"))
                .map(request -> applications.hint(unitOfWork, request))
                .orElse(false);
    }

    /** {@code credit.CreditDecisionRequestClosed}'s hint: the application awaiting {@code decisionRequest} made due now. */
    public boolean hintRequest(Connection unitOfWork, UUID decisionRequest) {
        return applications.hint(Objects.requireNonNull(unitOfWork, "unitOfWork"),
                Objects.requireNonNull(decisionRequest, "decisionRequest"));
    }

    // ------------------------------------------------------------------ T-off

    /**
     * One application's origination step, on the caller's unit of work - a transaction of its own. The history and the
     * events carry the application's correlation: one flow from the submission to the offer.
     */
    public Outcome originate(Connection unitOfWork, LoanApplicationId id, Actor actor) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(actor, "actor");
        Optional<LoanApplication> locked = applications.lockClaimed(unitOfWork, id);
        if (locked.isEmpty() || locked.get().status() != LoanApplicationStatus.AWAITING_DECISION) {
            return Outcome.nothing();
        }
        LoanApplication application = locked.get();
        LoanCreditDecisions.CreditOutcome answer =
                credit.decisionFor(unitOfWork, application.decisionRequest(), application.party());
        return switch (answer) {
            case LoanCreditDecisions.CreditOutcome.Pending pending -> Outcome.waiting(application.product());
            case LoanCreditDecisions.CreditOutcome.Closed closed -> close(unitOfWork, application,
                    LoanApplicationStatus.CLOSED_UNDECIDED, "CREDIT_REQUEST_" + closed.closure().name(), actor);
            case LoanCreditDecisions.CreditOutcome.Declined declined -> close(unitOfWork, application,
                    LoanApplicationStatus.DECLINED, CREDIT_DECLINED_REASON, actor);
            case LoanCreditDecisions.CreditOutcome.Approved approved -> offer(unitOfWork, application, approved, actor);
        };
    }

    private Outcome offer(
            Connection unitOfWork, LoanApplication application, LoanCreditDecisions.CreditOutcome.Approved approved,
            Actor actor) {
        if (approved.product() != application.product() || !approved.party().equals(application.party())) {
            throw new IllegalStateException("credit's decision is the application's own party's and product's");
        }
        if (approved.termMonths().isPresent() != (application.product().kind() == LoanKind.INSTALMENT)) {
            throw new IllegalStateException("an approval names a term exactly for an instalment product");
        }
        LoanTermsStore.LoanTermsVersion version;
        try {
            version = terms.pinActive(unitOfWork, application.product());
        } catch (LoanTermsAdministration.TermsNotFound none) {
            // Nothing in force to pin (the Phase 12 transition gate's A3-I1; credit's DecisionProgress#collect): the step
            // waits - nothing written, the claim's permit lapses and a later sweep reads again - never a terminal closure.
            // The application was submitted under an ACTIVE version and a retirement commits only beside its successor.
            return Outcome.waiting(application.product());
        }
        LoanTerms pinned = version.terms();
        if (!pinned.allowedDays().contains(application.repaymentDay())) {
            return close(unitOfWork, application, LoanApplicationStatus.CLOSED_UNDECIDED, DAY_NOT_OFFERED_REASON, actor);
        }
        // The database clock, read after the application's lock and the terms pin: what the step judges by - the decision's
        // lapse and whether a loan's schedule amortises. It is NOT the offering instant (P11-TSK-036): the insert stamps
        // that itself (lending V028), since a database clock stepped back between this read and the insert would put this
        // reading after the insert's clock - the refusal V005/V006 met as an opaque P0001.
        Instant now = offers.databaseNow(unitOfWork);
        if (!approved.validUntil().isAfter(now)) {
            return close(unitOfWork, application, LoanApplicationStatus.CLOSED_UNDECIDED, DECISION_LAPSED_REASON, actor);
        }
        ZoneId zone = ZoneId.of(pinned.servicingZone());
        LocalDate today = LocalDate.ofInstant(now, zone);
        Optional<Schedule> projected = Optional.empty();
        Optional<LoanOffer.MinimumPaymentRule> rule = Optional.empty();
        if (application.product().kind() == LoanKind.INSTALMENT) {
            ScheduleOutcome schedule = project(pinned, approved, application, today);
            if (schedule instanceof ScheduleOutcome.Refused refused) {
                if (refused.reason() == ScheduleRefusal.NOT_AMORTISING) {
                    return close(unitOfWork, application, LoanApplicationStatus.CLOSED_UNDECIDED, NOT_AMORTISING_REASON,
                            actor);
                }
                // The terms door judged these terms whole; anything else is a defect, never a closure.
                throw new IllegalStateException("the pinned schedule engine refused an approved offer: " + refused.reason());
            }
            projected = Optional.of(((ScheduleOutcome.Projected) schedule).schedule());
        } else {
            StatementTerms statement = StatementTerms.of(pinned, application.repaymentDay());
            rule = Optional.of(new LoanOffer.MinimumPaymentRule(statement.minimumPaymentFloor(),
                    statement.minimumPaymentRatioBps(), statement.paymentDueDays()));
        }
        LoanOfferId offerId = LoanOfferId.next(ids);
        LoanOfferStore.Offered born = offers.insert(unitOfWork, new LoanOfferStore.NewOffer(offerId, application.id(),
                application.party(), application.product(), approved.decision(), version.row().id(),
                approved.validUntil(), application.correlation()));
        Instant expiresAt = born.expiresAt();
        // The illustration starts on the offering instant's business date - one clock's with the expiry. Only a step that
        // put the read on another day than the insert re-projects; a refusal then is a defect of the moment, rolled back
        // whole and re-judged by the next sweep on one clock.
        Optional<LoanOffer.Illustration> illustration = Optional.empty();
        if (projected.isPresent()) {
            LocalDate offeredOn = LocalDate.ofInstant(born.offeredAt(), zone);
            Schedule schedule = projected.get();
            if (!offeredOn.equals(today)) {
                if (!(project(pinned, approved, application, offeredOn) instanceof ScheduleOutcome.Projected again)) {
                    throw new IllegalStateException("the offering date's schedule does not amortise where the read's did");
                }
                schedule = again.schedule();
            }
            illustration = Optional.of(LoanOffer.Illustration.of(schedule));
        }
        String canonical = CanonicalOfferTerms.render(new CanonicalOfferTerms.Content(offerId, application.id(),
                approved.decision(), version.row().id(), version.row().version(), pinned, approved.amount(),
                approved.termMonths(), application.repaymentDay(), illustration));
        LoanOffer.Terms unhashedDocument = terms(pinned, approved, application, illustration, rule, canonical,
                new byte[32]);
        byte[] documentSha256 = CanonicalOfferTerms.sha256(
                OfferDocument.render(offerId, application.product(), unhashedDocument));
        LoanOffer.Terms offered = terms(pinned, approved, application, illustration, rule, canonical, documentSha256);

        offers.insertTerms(unitOfWork, offerId, application.product(), offered);
        offers.appendEvent(unitOfWork, new LoanOfferStore.HistoryRow(ids.next(), offerId, Optional.empty(),
                LoanOfferStatus.OFFERED, APPROVED_REASON, actor, application.correlation()));
        move(unitOfWork, application, LoanApplicationStatus.OFFERED, APPROVED_REASON, actor);
        EventPayload payload = EventPayload.of()
                .with("offerId", offerId.value().toString())
                .with("applicationId", application.id().value().toString())
                .with("decisionId", approved.decision().toString())
                .with("product", application.product().name())
                .with("amountMinor", Long.toString(approved.amount().minorUnits()))
                .with("currency", approved.amount().currency().code())
                .with("nominalAnnualRateE8", pinned.nominalAnnualRate().movePointRight(8).toBigIntegerExact().toString())
                .with("expiresAtEpochMilli", Long.toString(expiresAt.toEpochMilli()))
                .with("termsVersionId", version.row().id().value().toString())
                .with("termsVersion", Integer.toString(version.row().version()))
                .with("termsSha256", offered.termsSha256Hex());
        if (approved.termMonths().isPresent()) {
            payload = payload.with("termMonths", approved.termMonths().get().toString());
        }
        publish(unitOfWork, OFFERED_EVENT, offerId, OFFER_AGGREGATE_TYPE, payload, application.correlation());
        return new Outcome(Outcome.Kind.OFFERED, Optional.of(application.product()), Optional.of(APPROVED_REASON));
    }

    /** The pinned engine's projection of the approved loan from {@code start}. */
    private static ScheduleOutcome project(
            LoanTerms pinned, LoanCreditDecisions.CreditOutcome.Approved approved, LoanApplication application,
            LocalDate start) {
        return LoanEngines.project(pinned, approved.amount(), approved.termMonths().orElseThrow(),
                application.repaymentDay(), start);
    }

    private static LoanOffer.Terms terms(
            LoanTerms pinned,
            LoanCreditDecisions.CreditOutcome.Approved approved,
            LoanApplication application,
            Optional<LoanOffer.Illustration> illustration,
            Optional<LoanOffer.MinimumPaymentRule> rule,
            String canonical,
            byte[] documentSha256) {
        return new LoanOffer.Terms(approved.amount(), approved.termMonths(), pinned.nominalAnnualRate(),
                application.repaymentDay(), illustration, rule,
                pinned.instalment().map(LoanTerms.InstalmentTerms::scheduleEngine), pinned.accrualEngine(),
                pinned.allocationEngine(), pinned.revolving().map(LoanTerms.RevolvingTerms::statementEngine),
                pinned.agreementTemplate(), canonical, CanonicalOfferTerms.sha256(canonical), documentSha256);
    }

    private Outcome close(
            Connection unitOfWork, LoanApplication application, LoanApplicationStatus to, String reason, Actor actor) {
        move(unitOfWork, application, to, reason, actor);
        publish(unitOfWork, LoanApplications.CLOSED_EVENT, application.id(), LoanApplications.AGGREGATE_TYPE,
                EventPayload.of()
                        .with("applicationId", application.id().value().toString())
                        .with("product", application.product().name())
                        .with("decisionRequestId", application.decisionRequest().toString())
                        .with("status", to.name())
                        .with("reason", reason),
                application.correlation());
        return new Outcome(to == LoanApplicationStatus.DECLINED ? Outcome.Kind.DECLINED : Outcome.Kind.CLOSED_UNDECIDED,
                Optional.of(application.product()), Optional.of(reason));
    }

    private void move(
            Connection unitOfWork, LoanApplication application, LoanApplicationStatus to, String reason, Actor actor) {
        if (!applications.transition(unitOfWork, application.id(), LoanApplicationStatus.AWAITING_DECISION, to)) {
            throw new IllegalStateException(
                    "the locked application moved under its own row lock: the FOR UPDATE protocol was bypassed");
        }
        applications.appendEvent(unitOfWork, new LoanApplicationStore.HistoryRow(ids.next(), application.id(),
                Optional.of(LoanApplicationStatus.AWAITING_DECISION), to, reason, actor, application.correlation()));
    }

    // ------------------------------------------------------------------ expiry

    /**
     * One claimed offer's expiry, on the caller's unit of work: (L2) the offer {@code FOR UPDATE}, {@code EXPIRED} only
     * while {@code OFFERED} and {@code expires_at <= statement_timestamp()} - the complement of the decline's and the
     * acceptance's condition, so exactly one wins at the boundary. An offer claimed before its expiry has its permit set
     * back to the expiry. The product of the offer that expired, or empty.
     */
    public Optional<LoanProduct> expire(Connection unitOfWork, LoanOfferId id, Actor actor) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(actor, "actor");
        Optional<LoanOffer> locked = offers.lockClaimed(unitOfWork, id);
        if (locked.isEmpty() || locked.get().status() != LoanOfferStatus.OFFERED) {
            return Optional.empty();
        }
        LoanOffer offer = locked.get();
        if (!offers.expire(unitOfWork, id)) {
            offers.permitAtExpiry(unitOfWork, id);
            return Optional.empty();
        }
        offers.appendEvent(unitOfWork, new LoanOfferStore.HistoryRow(ids.next(), id,
                Optional.of(LoanOfferStatus.OFFERED), LoanOfferStatus.EXPIRED, EXPIRED_REASON, actor,
                offer.correlation()));
        publishClosed(unitOfWork, outbox, ids, clock, offer, LoanOfferStatus.EXPIRED, EXPIRED_REASON, offer.correlation());
        return Optional.of(offer.product());
    }

    // ------------------------------------------------------------------ plumbing

    /** {@code lending.LoanOfferClosed} - the offer declined or expired; never a figure. */
    static void publishClosed(
            Connection unitOfWork,
            OutboxWriter<Connection> outbox,
            IdGenerator ids,
            Clock clock,
            LoanOffer offer,
            LoanOfferStatus status,
            String reason,
            CorrelationId correlation) {
        write(unitOfWork, outbox, ids, clock, OFFER_CLOSED_EVENT, offer.id(), OFFER_AGGREGATE_TYPE, EventPayload.of()
                .with("offerId", offer.id().value().toString())
                .with("applicationId", offer.application().value().toString())
                .with("decisionId", offer.decision().toString())
                .with("product", offer.product().name())
                .with("status", status.name())
                .with("reason", reason), correlation);
    }

    private void publish(
            Connection unitOfWork,
            String type,
            EntityId aggregate,
            String aggregateType,
            EventPayload payload,
            CorrelationId correlation) {
        write(unitOfWork, outbox, ids, clock, type, aggregate, aggregateType, payload, correlation);
    }

    private static void write(
            Connection unitOfWork,
            OutboxWriter<Connection> outbox,
            IdGenerator ids,
            Clock clock,
            String type,
            EntityId aggregate,
            String aggregateType,
            EventPayload payload,
            CorrelationId correlation) {
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        type,
                        LoanTermsAdministration.EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        aggregate,
                        aggregateType,
                        clock.instant(),
                        LoanTermsAdministration.PRODUCER,
                        correlation,
                        CausationId.of(correlation.value())),
                payload.toBytes(),
                EventPayload.MEDIA_TYPE);
    }
}
