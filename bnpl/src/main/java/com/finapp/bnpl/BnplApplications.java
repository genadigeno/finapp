package com.finapp.bnpl;

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
import com.finapp.sharedkernel.security.Sensitive;
import java.sql.Connection;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * A customer's BNPL applications at checkout (`P12-TSK-010`; {@code BNPL_LIFECYCLES.md} section 4.2,
 * {@code PHASE_12_PLAN.md} sections 5.1 and 7.1 T-app; {@code INV-BNPL-03}, {@code INV-LIFE-01}, {@code INV-LIFE-02},
 * {@code INV-CRD-04}; lending's {@code LoanApplications} precedent): the submission, the withdrawal and the owner's read,
 * each on the caller's unit of work - the door's idempotency claim wraps them, so a retried key replays and a refusal,
 * which writes nothing, rolls the claim back with it.
 *
 * <p><strong>The submission is one customer act, in this order</strong> (T-app): the party's standing, read
 * authoritatively ({@link BnplPartyStanding}); the checkout session the customer's token opens
 * ({@link BnplCheckoutSessions#read} - {@code OPEN}, unexpired on the database clock, in the product's currency), its
 * gross within credit's product bounds ({@link BnplProductBounds}); the session's merchant BNPL-enabled for the product
 * and the sale covering its fee ({@link BnplMerchants#standing}, {@link BnplMerchants#coversFee}, both under the
 * merchant's share locks); the product's {@code ACTIVE} terms version pinned {@code FOR SHARE}
 * ({@link BnplTermsAdministration#pinActive}) and the plan judged against it; credit's decision request opened through
 * {@link BnplCreditDecisions#open} - from the BNPL origin, for <strong>exactly the session's gross</strong> and the plan's
 * term, credit's arbiters, consent gate and bounds; then the application born {@code SUBMITTED} with its history row and
 * moved {@code AWAITING_DECISION} with the second, {@code bnpl.BnplApplicationSubmitted} through the outbox and
 * {@link BnplAuditAction#APPLICATION_SUBMITTED}. All in the caller's transaction: a credit refusal, a bnpl refusal or a
 * crash anywhere leaves NEITHER the application NOR the request ({@code bnpl V003}'s deferred trigger refuses to commit a
 * row still {@code SUBMITTED}). bnpl never reads a score, an attribute or a threshold ({@code INV-CRD-04}): only the id
 * of the request it opened.
 *
 * <p><strong>No lock is taken by the submission</strong> beyond the share locks of its reads (the terms pin, the
 * merchant's standing): ten keys for one session meet the session's partial unique ({@link ApplicationOpen} for nine),
 * two sessions of one party for one product meet credit's one-open-request partial unique first and bnpl's own backstop
 * beneath it. The session is read, not locked: its later change is the origination sweep's to observe (`P12-TSK-011`),
 * and only the purchase's B3 judgement is binding (`P12-TSK-013`). <strong>The withdrawal</strong> locks the application
 * (B1), acts only from {@code AWAITING_DECISION}, and cancels credit's request under credit's own row lock in the same
 * transaction - so a withdrawal racing credit's evaluation, or the sweep's offer (which takes B1 too), leaves exactly
 * one of {@code WITHDRAWN} and evaluated.
 */
@RequiredArgsConstructor
public final class BnplApplications {

    public static final String SUBMITTED_EVENT = "bnpl.BnplApplicationSubmitted";
    public static final String CLOSED_EVENT = "bnpl.BnplApplicationClosed";

    /** The history's, the events' and the audit records' aggregate type. */
    public static final String AGGREGATE_TYPE = "bnpl_application";

    static final String SUBMITTED_REASON = "APPLICATION_SUBMITTED";
    static final String OPENED_REASON = "CREDIT_REQUEST_OPENED";
    public static final String WITHDRAWN_REASON = "CUSTOMER_WITHDREW";

    @NonNull private final BnplApplicationStore store;
    @NonNull private final BnplTermsAdministration terms;
    @NonNull private final BnplPartyStanding standing;
    @NonNull private final BnplCheckoutSessions sessions;
    @NonNull private final BnplMerchants merchants;
    @NonNull private final BnplProductBounds bounds;
    @NonNull private final BnplCreditDecisions credit;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /**
     * What a customer submits: the checkout session's token - a capability, wrapped from deserialisation and never
     * logged, stored or echoed - the product and the plan. No amount: the purchase amount is the session's gross.
     */
    public record Submission(Sensitive<String> sessionToken, BnplProduct product, BnplPlan plan) {

        public Submission {
            Objects.requireNonNull(sessionToken, "sessionToken must not be null");
            Objects.requireNonNull(product, "product must not be null");
            Objects.requireNonNull(plan, "plan must not be null");
            if (!product.plans().contains(plan)) {
                throw new SubmissionInvalid("the plan is one of " + product + "'s plans " + product.plans());
            }
        }

        /** Never the token ({@code INV-AUD-02}). */
        @Override
        public String toString() {
            return "Submission[" + product + ", " + plan + ", redacted]";
        }
    }

    /** An application as its owner reads it: the row, and - once declined - credit's reasons in the customer's words. */
    public record Read(BnplApplication application, List<String> reasonTexts) {

        public Read {
            Objects.requireNonNull(application, "application must not be null");
            reasonTexts = List.copyOf(reasonTexts);
        }
    }

    // ------------------------------------------------------------------ submit

    /**
     * Submits {@code submission} for {@code party} - the customer's act; the application, {@code AWAITING_DECISION}.
     *
     * @throws NotInGoodStanding when the party is not a verified customer in good standing
     * @throws SessionNotEligible when no open, unexpired session in the product's currency answers the token, or the sale
     *     does not cover the merchant's fee
     * @throws AmountOutOfRange when the session's gross is outside credit's bounds for the product
     * @throws MerchantNotEnabled when the session's merchant may not offer the product now
     * @throws ProductNotOffered when no terms version of the product is {@code ACTIVE}, or the active one does not offer
     *     the plan
     * @throws ApplicationOpen when the session already has an open application, or the party an open application or
     *     credit request for the product
     */
    public BnplApplication submit(
            Connection unitOfWork, UUID party, Submission submission, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(party, "party");
        Objects.requireNonNull(submission, "submission");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        BnplProduct product = submission.product();
        if (!standing.inGoodStanding(unitOfWork, party)) {
            throw new NotInGoodStanding();
        }
        BnplCheckoutSessions.Session session = sessions.read(unitOfWork, submission.sessionToken())
                .orElseThrow(() -> new SessionNotEligible(SessionNotEligible.Cause.NOT_OPEN));
        if (!session.open() || session.expired()) {
            throw new SessionNotEligible(SessionNotEligible.Cause.NOT_OPEN);
        }
        Money gross = session.gross();
        if (!gross.currency().equals(product.currency())) {
            throw new SessionNotEligible(SessionNotEligible.Cause.CURRENCY);
        }
        BnplProductBounds.Bounds within = bounds.of(product);
        if (gross.compareTo(within.minimumAmount()) < 0 || gross.compareTo(within.maximumAmount()) > 0) {
            throw new AmountOutOfRange("the purchase amount is " + within.minimumAmount().toBigDecimal().toPlainString()
                    + " to " + within.maximumAmount().toBigDecimal().toPlainString() + " " + product.currency()
                    + " for " + product);
        }
        BnplMerchants.Standing merchant = merchants.standing(unitOfWork, session.merchantRef(), product);
        if (merchant != BnplMerchants.Standing.ENABLED) {
            throw new MerchantNotEnabled(merchant);
        }
        if (!merchants.coversFee(unitOfWork, session.merchantRef(), product, gross)) {
            throw new SessionNotEligible(SessionNotEligible.Cause.BELOW_FEE);
        }
        BnplTermsVersion version;
        try {
            version = terms.pinActive(unitOfWork, product);
        } catch (BnplTermsAdministration.ProductNotOffered none) {
            throw new ProductNotOffered("the product is not offered");
        }
        if (!offers(version.terms(), submission.plan())) {
            throw new ProductNotOffered("the plan is not offered");
        }
        UUID decisionRequest;
        try {
            decisionRequest = credit.open(unitOfWork, party,
                    BnplCreditDecisions.CreditRequest.of(product, submission.plan(), gross), actor, correlation);
        } catch (BnplCreditDecisions.RequestOpen open) {
            throw new ApplicationOpen();
        } catch (BnplCreditDecisions.TermsOutOfBounds outOfBounds) {
            throw new AmountOutOfRange(outOfBounds.getMessage());
        }
        BnplApplicationId id = BnplApplicationId.next(ids);
        if (!store.insert(unitOfWork, new BnplApplicationStore.NewApplication(id, party, product, submission.plan(),
                session.sessionRef(), session.merchantRef(), decisionRequest, version.id(), actor, correlation))) {
            throw new ApplicationOpen();
        }
        store.appendEvent(unitOfWork, new BnplApplicationStore.HistoryRow(ids.next(), id, Optional.empty(),
                BnplApplicationStatus.SUBMITTED, SUBMITTED_REASON, actor, correlation));
        if (!store.transition(unitOfWork, id, BnplApplicationStatus.SUBMITTED, BnplApplicationStatus.AWAITING_DECISION)) {
            throw new IllegalStateException("an application born in this transaction moved under it");
        }
        store.appendEvent(unitOfWork, new BnplApplicationStore.HistoryRow(ids.next(), id,
                Optional.of(BnplApplicationStatus.SUBMITTED), BnplApplicationStatus.AWAITING_DECISION, OPENED_REASON,
                actor, correlation));
        publish(unitOfWork, SUBMITTED_EVENT, id, EventPayload.of()
                .with("applicationId", id.value().toString())
                .with("partyId", party.toString())
                .with("product", product.name())
                .with("plan", submission.plan().name())
                .with("checkoutSessionId", session.sessionRef().toString())
                .with("decisionRequestId", decisionRequest.toString())
                .with("termsVersionId", version.id().value().toString())
                .with("termsVersion", Integer.toString(version.row().version())), correlation);
        audited(unitOfWork, BnplAuditAction.APPLICATION_SUBMITTED, id, actor, correlation, "application " + id.value()
                + " submitted for " + product + "/" + submission.plan() + " in checkout session " + session.sessionRef()
                + ", credit request " + decisionRequest);
        return store.ownedBy(unitOfWork, id, party)
                .orElseThrow(() -> new IllegalStateException("a BNPL application is readable once written"));
    }

    // ------------------------------------------------------------------ withdraw

    /**
     * Withdraws {@code party}'s application {@code id} - while credit's request is still cancellable; the application,
     * {@code WITHDRAWN}, credit's request {@code CANCELLED} beside it.
     *
     * @throws ApplicationNotFound when the party has no application with this id (another party's included)
     * @throws ApplicationNotWithdrawable when it is no longer awaiting a decision, or credit has evaluated its request
     */
    public BnplApplication withdraw(
            Connection unitOfWork, BnplApplicationId id, UUID party, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(party, "party");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        // B1 first; every judgement below is on the row as this lock found it.
        BnplApplication application = store.lockOwnedBy(unitOfWork, id, party).orElseThrow(ApplicationNotFound::new);
        if (application.status() != BnplApplicationStatus.AWAITING_DECISION) {
            throw new ApplicationNotWithdrawable(application.status(), application.product());
        }
        try {
            credit.cancel(unitOfWork, application.decisionRequest(), party, actor, correlation);
        } catch (BnplCreditDecisions.NotCancellable evaluated) {
            throw new ApplicationNotWithdrawable(application.status(), application.product());
        }
        if (!store.transition(unitOfWork, id, BnplApplicationStatus.AWAITING_DECISION, BnplApplicationStatus.WITHDRAWN)) {
            throw new IllegalStateException(
                    "the locked application moved under its own row lock: the FOR UPDATE protocol was bypassed");
        }
        store.appendEvent(unitOfWork, new BnplApplicationStore.HistoryRow(ids.next(), id,
                Optional.of(BnplApplicationStatus.AWAITING_DECISION), BnplApplicationStatus.WITHDRAWN, WITHDRAWN_REASON,
                actor, correlation));
        publish(unitOfWork, CLOSED_EVENT, id, EventPayload.of()
                .with("applicationId", id.value().toString())
                .with("product", application.product().name())
                .with("checkoutSessionId", application.checkoutSession().toString())
                .with("decisionRequestId", application.decisionRequest().toString())
                .with("status", BnplApplicationStatus.WITHDRAWN.name())
                .with("reason", WITHDRAWN_REASON), correlation);
        audited(unitOfWork, BnplAuditAction.APPLICATION_WITHDRAWN, id, actor, correlation, "application " + id.value()
                + " withdrawn, credit request " + application.decisionRequest() + " cancelled");
        return store.ownedBy(unitOfWork, id, party)
                .orElseThrow(() -> new IllegalStateException("a BNPL application is readable once written"));
    }

    // ------------------------------------------------------------------ read

    /** {@code party}'s application {@code id}; another party's is the same empty answer as an absent one. */
    public Optional<Read> read(Connection unitOfWork, BnplApplicationId id, UUID party) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        return store.ownedBy(unitOfWork, id, party).map(application -> new Read(application,
                application.status() == BnplApplicationStatus.DECLINED
                        ? credit.reasonTexts(unitOfWork, application.decisionRequest())
                        : List.of()));
    }

    // ------------------------------------------------------------------ plumbing

    /** Whether {@code terms} offer {@code plan}: pay-in-4's one fixed plan, or a pay-in-N term among its allowed terms. */
    static boolean offers(BnplTerms terms, BnplPlan plan) {
        if (!terms.product().plans().contains(plan)) {
            return false;
        }
        return switch (terms.product().shape()) {
            case FIXED -> terms.payIn4().isPresent();
            case MONTHS -> terms.payInN()
                    .filter(own -> own.allowedTerms().contains(plan.termMonths().orElseThrow()))
                    .isPresent();
        };
    }

    private void publish(
            Connection unitOfWork, String type, BnplApplicationId id, EventPayload payload, CorrelationId correlation) {
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        type,
                        BnplTermsAdministration.EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        id,
                        AGGREGATE_TYPE,
                        clock.instant(),
                        BnplTermsAdministration.PRODUCER,
                        correlation,
                        CausationId.of(correlation.value())),
                // Never an amount or the token - credit's request holds what was asked, checkout the session.
                payload.toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    private void audited(Connection unitOfWork, BnplAuditAction action, BnplApplicationId id, Actor actor,
            CorrelationId correlation, String summary) {
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), action, AGGREGATE_TYPE,
                id.value().toString(), Optional.empty(), AuditOutcome.SUCCEEDED, correlation, Optional.of(summary)));
    }

    // ------------------------------------------------------------------ the refusals, each writing nothing

    /** The applicant is not a verified customer in good standing - the answer credit would give. */
    public static final class NotInGoodStanding extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        NotInGoodStanding() {
            super("the applicant is not a verified customer in good standing");
        }
    }

    /** The checkout session cannot be financed by BNPL; the detail names the cause, never the amount or the token. */
    public static final class SessionNotEligible extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        /** Why - one answer for an unknown token and a closed session: no oracle over tokens. */
        public enum Cause {
            /** No session answers the token, or it is no longer {@code OPEN}, or its deadline passed. */
            NOT_OPEN,
            /** The session is not in the product's currency. */
            CURRENCY,
            /** The sale does not cover the merchant's fee (ADR-0058). */
            BELOW_FEE
        }

        private final Cause cause;

        SessionNotEligible(Cause cause) {
            super(switch (cause) {
                case NOT_OPEN -> "no open checkout session answers the token";
                case CURRENCY -> "the checkout session is not in the product's currency";
                case BELOW_FEE -> "the purchase is not eligible for this BNPL product";
            });
            this.cause = cause;
        }

        public Cause reason() {
            return cause;
        }
    }

    /** The purchase amount is outside the product's bounds; the message names the bound, never the figure. */
    public static final class AmountOutOfRange extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        AmountOutOfRange(String bound) {
            super(bound);
        }
    }

    /** The checkout's merchant may not offer the product now; the standing is for the meter, never the customer. */
    public static final class MerchantNotEnabled extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final BnplMerchants.Standing standing;

        MerchantNotEnabled(BnplMerchants.Standing standing) {
            super("the merchant does not offer this BNPL product");
            this.standing = standing;
        }

        public BnplMerchants.Standing standing() {
            return standing;
        }
    }

    /** No terms version of the product is {@code ACTIVE}, or the active one does not offer the plan. */
    public static final class ProductNotOffered extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ProductNotOffered(String detail) {
            super(detail);
        }
    }

    /** The session already has an open application, or the party an open application or credit request for the product. */
    public static final class ApplicationOpen extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ApplicationOpen() {
            super("a BNPL application is already open for this checkout or product; wait for its decision or withdraw it");
        }
    }

    /** The application is past withdrawal - decided or closed, or credit has evaluated its request. */
    public static final class ApplicationNotWithdrawable extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final BnplProduct product;

        ApplicationNotWithdrawable(BnplApplicationStatus status, BnplProduct product) {
            super(status == BnplApplicationStatus.AWAITING_DECISION
                    ? "credit has evaluated this application: its decision will arrive, and an offer may be declined"
                    : "a " + status + " application can no longer be withdrawn");
            this.product = product;
        }

        /** The application's product - for the meter. */
        public BnplProduct product() {
            return product;
        }
    }

    /** No application of the caller's has this id - another party's included. */
    public static final class ApplicationNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ApplicationNotFound() {
            super("no such BNPL application");
        }
    }

    /** A submission that is not well formed - a plan of another product. */
    public static final class SubmissionInvalid extends IllegalArgumentException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        SubmissionInvalid(String detail) {
            super(detail);
        }
    }
}
