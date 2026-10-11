package com.finapp.lending;

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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * A customer's loan applications (`P11-TSK-010`; {@code LENDING_LIFECYCLES.md} section 4.3, {@code PHASE_11_PLAN.md}
 * section 7.1 T-app; {@code INV-LIFE-01}, {@code INV-LIFE-02}, {@code INV-CNS-01} through credit): the submission, the
 * withdrawal and the owner's read, each on the caller's unit of work - the door's idempotency claim wraps them, so a
 * retried key replays and a refusal, which writes nothing, rolls the claim back with it.
 *
 * <p><strong>The submission is one customer act, in this order</strong> (T-app): the party's standing, read
 * authoritatively ({@link LoanPartyStanding}); the product's {@code ACTIVE} terms version pinned {@code FOR SHARE}
 * ({@link LoanTermsAdministration#pinActive}) - none is {@link ProductNotOffered}; the repayment or statement day judged
 * against that version's allowed days; credit's decision request opened through {@link LoanCreditDecisions#open} -
 * credit's arbiters, its consent gate and its bounds; then the application born {@code SUBMITTED} with its history row
 * and moved {@code AWAITING_DECISION} with the second, and {@code lending.LoanApplicationSubmitted} through the outbox.
 * All in the caller's transaction: a credit refusal, a lending refusal or a crash anywhere leaves NEITHER the
 * application NOR the request ({@code lending V004}'s deferred trigger refuses to commit a row still {@code SUBMITTED}).
 *
 * <p><strong>No lock is taken by the submission</strong> beyond the terms pin: two keys for one party and product meet
 * credit's one-open-request partial unique first ({@link ApplicationOpen}), and lending's own one-open partial unique is
 * the backstop - it also holds an application open whose credit request closed before the origination step
 * (`P11-TSK-011`) closed it. <strong>The withdrawal</strong> locks the application (L1), acts only from
 * {@code AWAITING_DECISION}, and cancels credit's request under credit's own row lock in the same transaction - so a
 * withdrawal racing credit's evaluation leaves exactly one of {@code WITHDRAWN} and evaluated.
 */
@RequiredArgsConstructor
public final class LoanApplications {

    public static final String SUBMITTED_EVENT = "lending.LoanApplicationSubmitted";
    public static final String CLOSED_EVENT = "lending.LoanApplicationClosed";

    /** The history's and the events' aggregate type. */
    static final String AGGREGATE_TYPE = "loan_application";

    static final String SUBMITTED_REASON = "APPLICATION_SUBMITTED";
    static final String OPENED_REASON = "CREDIT_REQUEST_OPENED";
    public static final String WITHDRAWN_REASON = "CUSTOMER_WITHDREW";

    @NonNull private final LoanApplicationStore store;
    @NonNull private final LoanTermsAdministration terms;
    @NonNull private final LoanPartyStanding standing;
    @NonNull private final LoanCreditDecisions credit;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /**
     * What a customer submits: the product, the amount or limit and the term (an instalment product's) and the declared
     * figures - all passed to credit, never stored here - and lending's own choices: the repayment or statement day, the
     * repayment method and the payout preference. A credit line pays out to the wallet only (A25).
     */
    public record Submission(
            LoanProduct product,
            Money requested,
            Optional<Integer> termMonths,
            Optional<Money> declaredMonthlyIncome,
            Optional<Money> declaredMonthlyExpenditure,
            int repaymentDay,
            RepaymentMethod repaymentMethod,
            PayoutPreference payoutPreference) {

        public Submission {
            Objects.requireNonNull(product, "product must not be null");
            Objects.requireNonNull(requested, "requested must not be null");
            Objects.requireNonNull(termMonths, "termMonths must not be null");
            Objects.requireNonNull(declaredMonthlyIncome, "declaredMonthlyIncome must not be null");
            Objects.requireNonNull(declaredMonthlyExpenditure, "declaredMonthlyExpenditure must not be null");
            Objects.requireNonNull(repaymentMethod, "repaymentMethod must not be null");
            Objects.requireNonNull(payoutPreference, "payoutPreference must not be null");
            if (product.kind() == LoanKind.REVOLVING && payoutPreference != PayoutPreference.WALLET) {
                throw new SubmissionInvalid("a credit line draws to the wallet only: its payout preference is WALLET");
            }
        }

        /** What credit is asked for. */
        LoanCreditDecisions.CreditTerms creditTerms() {
            return new LoanCreditDecisions.CreditTerms(
                    product, requested, termMonths, declaredMonthlyIncome, declaredMonthlyExpenditure);
        }

        /** Never a figure ({@code INV-AUD-02}). */
        @Override
        public String toString() {
            return "Submission[" + product + ", redacted]";
        }
    }

    /** An application as its owner reads it: the row, and - once declined - credit's reasons in the customer's words. */
    public record Read(LoanApplication application, List<String> reasonTexts) {

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
     * @throws ProductNotOffered when no terms version of the product is {@code ACTIVE}
     * @throws AmountOutOfRange when the day is not one the terms offer, or the figures are outside credit's bounds
     * @throws ApplicationOpen when the party already has an open application (or credit request) for the product
     */
    public LoanApplication submit(
            Connection unitOfWork, UUID party, Submission submission, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(party, "party");
        Objects.requireNonNull(submission, "submission");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        if (!standing.inGoodStanding(unitOfWork, party)) {
            throw new NotInGoodStanding();
        }
        LoanTermsStore.LoanTermsVersion version;
        try {
            version = terms.pinActive(unitOfWork, submission.product());
        } catch (LoanTermsAdministration.TermsNotFound none) {
            throw new ProductNotOffered();
        }
        if (!version.terms().allowedDays().contains(submission.repaymentDay())) {
            throw new AmountOutOfRange("the " + (submission.product().kind() == LoanKind.INSTALMENT ? "repayment"
                    : "statement") + " day is one of " + version.terms().allowedDays());
        }
        UUID decisionRequest;
        try {
            decisionRequest = credit.open(unitOfWork, party, submission.creditTerms(), actor, correlation);
        } catch (LoanCreditDecisions.RequestOpen open) {
            throw new ApplicationOpen();
        } catch (LoanCreditDecisions.TermsOutOfBounds outOfBounds) {
            throw new AmountOutOfRange(outOfBounds.getMessage());
        }
        LoanApplicationId id = LoanApplicationId.next(ids);
        if (!store.insert(unitOfWork, new LoanApplicationStore.NewApplication(id, party, submission.product(),
                decisionRequest, version.row().id(), submission.repaymentDay(), submission.repaymentMethod(),
                submission.payoutPreference(), actor, correlation))) {
            throw new ApplicationOpen();
        }
        store.appendEvent(unitOfWork, new LoanApplicationStore.HistoryRow(ids.next(), id, Optional.empty(),
                LoanApplicationStatus.SUBMITTED, SUBMITTED_REASON, actor, correlation));
        if (!store.transition(unitOfWork, id, LoanApplicationStatus.SUBMITTED, LoanApplicationStatus.AWAITING_DECISION)) {
            throw new IllegalStateException("an application born in this transaction moved under it");
        }
        store.appendEvent(unitOfWork, new LoanApplicationStore.HistoryRow(ids.next(), id,
                Optional.of(LoanApplicationStatus.SUBMITTED), LoanApplicationStatus.AWAITING_DECISION, OPENED_REASON,
                actor, correlation));
        publish(unitOfWork, SUBMITTED_EVENT, id, EventPayload.of()
                .with("applicationId", id.value().toString())
                .with("partyId", party.toString())
                .with("product", submission.product().name())
                .with("decisionRequestId", decisionRequest.toString())
                .with("termsVersionId", version.row().id().value().toString())
                .with("termsVersion", Integer.toString(version.row().version())), correlation);
        return store.ownedBy(unitOfWork, id, party)
                .orElseThrow(() -> new IllegalStateException("a loan application is readable once written"));
    }

    // ------------------------------------------------------------------ withdraw

    /**
     * Withdraws {@code party}'s application {@code id} - while credit's request is still cancellable; the application,
     * {@code WITHDRAWN}, credit's request {@code CANCELLED} beside it.
     *
     * @throws ApplicationNotFound when the party has no application with this id (another party's included)
     * @throws ApplicationNotWithdrawable when it is no longer awaiting a decision, or credit has evaluated its request
     */
    public LoanApplication withdraw(
            Connection unitOfWork, LoanApplicationId id, UUID party, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(party, "party");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        LoanApplication application = store.lockOwnedBy(unitOfWork, id, party).orElseThrow(ApplicationNotFound::new);
        if (application.status() != LoanApplicationStatus.AWAITING_DECISION) {
            throw new ApplicationNotWithdrawable(application.status(), application.product());
        }
        try {
            credit.cancel(unitOfWork, application.decisionRequest(), party, actor, correlation);
        } catch (LoanCreditDecisions.NotCancellable evaluated) {
            throw new ApplicationNotWithdrawable(application.status(), application.product());
        }
        if (!store.transition(unitOfWork, id, LoanApplicationStatus.AWAITING_DECISION, LoanApplicationStatus.WITHDRAWN)) {
            throw new IllegalStateException(
                    "the locked application moved under its own row lock: the FOR UPDATE protocol was bypassed");
        }
        store.appendEvent(unitOfWork, new LoanApplicationStore.HistoryRow(ids.next(), id,
                Optional.of(LoanApplicationStatus.AWAITING_DECISION), LoanApplicationStatus.WITHDRAWN, WITHDRAWN_REASON,
                actor, correlation));
        publish(unitOfWork, CLOSED_EVENT, id, EventPayload.of()
                .with("applicationId", id.value().toString())
                .with("product", application.product().name())
                .with("decisionRequestId", application.decisionRequest().toString())
                .with("status", LoanApplicationStatus.WITHDRAWN.name())
                .with("reason", WITHDRAWN_REASON), correlation);
        return store.ownedBy(unitOfWork, id, party)
                .orElseThrow(() -> new IllegalStateException("a loan application is readable once written"));
    }

    // ------------------------------------------------------------------ read

    /** {@code party}'s application {@code id}; another party's is the same empty answer as an absent one. */
    public Optional<Read> read(Connection unitOfWork, LoanApplicationId id, UUID party) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        return store.ownedBy(unitOfWork, id, party).map(application -> new Read(application,
                application.status() == LoanApplicationStatus.DECLINED
                        ? credit.reasonTexts(unitOfWork, application.decisionRequest())
                        : List.of()));
    }

    // ------------------------------------------------------------------ plumbing

    private void publish(
            Connection unitOfWork, String type, LoanApplicationId id, EventPayload payload, CorrelationId correlation) {
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        type,
                        LoanTermsAdministration.EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        id,
                        AGGREGATE_TYPE,
                        clock.instant(),
                        LoanTermsAdministration.PRODUCER,
                        correlation,
                        CausationId.of(correlation.value())),
                // Never an amount, a term, a declared figure or a day - credit's request holds what was asked.
                payload.toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    // ------------------------------------------------------------------ the refusals, each writing nothing

    /** The applicant is not a verified customer in good standing - the answer credit would give. */
    public static final class NotInGoodStanding extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        NotInGoodStanding() {
            super("the applicant is not a verified customer in good standing");
        }
    }

    /** No terms version of the product is {@code ACTIVE}: the product is not offered. */
    public static final class ProductNotOffered extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ProductNotOffered() {
            super("the product is not offered");
        }
    }

    /** The day, amount, limit or term is outside the product's bounds; the message names the bound, never the figure. */
    public static final class AmountOutOfRange extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        AmountOutOfRange(String bound) {
            super(bound);
        }
    }

    /** The party already holds an open application - or an open credit request - for the product. */
    public static final class ApplicationOpen extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ApplicationOpen() {
            super("an application for this product is open; wait for its decision or withdraw it");
        }
    }

    /** The application is past withdrawal - decided or closed, or credit has evaluated its request. */
    public static final class ApplicationNotWithdrawable extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final LoanProduct product;

        ApplicationNotWithdrawable(LoanApplicationStatus status, LoanProduct product) {
            super(status == LoanApplicationStatus.AWAITING_DECISION
                    ? "credit has evaluated this application: its decision will arrive, and an offer may be declined"
                    : "a " + status + " application can no longer be withdrawn");
            this.product = product;
        }

        /** The application's product - for the meter. */
        public LoanProduct product() {
            return product;
        }
    }

    /** No application of the caller's has this id - another party's included. */
    public static final class ApplicationNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ApplicationNotFound() {
            super("no such loan application");
        }
    }

    /** A submission that is not well formed - a credit line preferring an external payout. */
    public static final class SubmissionInvalid extends IllegalArgumentException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        SubmissionInvalid(String detail) {
            super(detail);
        }
    }
}
