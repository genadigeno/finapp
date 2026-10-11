package com.finapp.app.lending;

import com.finapp.app.api.DecimalText;
import com.finapp.app.credit.ConsentBackedCreditConsentGate;
import com.finapp.consent.ConsentNotGrantedException;
import com.finapp.credit.CreditErrorCode;
import com.finapp.credit.DecisionRequests;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityErrorCode;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.MfaEnrolmentStore;
import com.finapp.identity.MfaFactorType;
import com.finapp.identity.Session;
import com.finapp.lending.LendingErrorCode;
import com.finapp.lending.LoanApplication;
import com.finapp.lending.LoanApplicationId;
import com.finapp.lending.LoanApplicationStatus;
import com.finapp.lending.LoanApplications;
import com.finapp.lending.LoanOfferId;
import com.finapp.lending.LoanOffers;
import com.finapp.lending.LoanProduct;
import com.finapp.lending.PayoutPreference;
import com.finapp.lending.RepaymentMethod;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The boundary half of the loan application doors (`P11-TSK-010`): parse the body exactly into the domain's words, key
 * each act per principal, run it in one transaction under the {@link IdempotentExecutor} claim - the claim, the
 * application, its history, credit's request and both modules' events commit together, so a retried key replays the
 * recorded response and a refusal, which writes nothing, rolls the claim back with it - and answer the refusals on the
 * error contract. The machine, the one-open rules and the bounds are the domain's, credit's and {@code lending V004}'s.
 *
 * <p><strong>Submission requires a {@code MULTI_FACTOR} session, always</strong> (A19, R14): applying for credit is an
 * MFA act, enrolled or not - refused {@code identity.AssuranceRequired} before any write. <strong>Withdrawal keeps the
 * conditional step-up</strong> (credit's cancellation precedent): an identity with an active factor withdraws from a
 * {@code MULTI_FACTOR} session - withdrawing moves no money and opens no exposure.
 *
 * <p><strong>Credit's refusals pass through unchanged</strong> where they are the cause ({@code 409
 * credit.ApplicantNotEligible}, {@code 409 consent.ConsentRequired}, {@code 422 credit.ProductNotOffered} for a product
 * no credit policy offers); credit's open request for the party and product is {@code 409 lending.ApplicationOpen}, and
 * terms outside credit's bounds {@code 422 lending.AmountOutOfRange}. The declared figures are passed to credit and
 * hashed into the fingerprint - never logged, stored or answered here.
 */
@RequiredArgsConstructor
public final class LoanApplicationDesk {

    static final String SCOPE = "lending.application:";
    static final String WITHDRAWAL_SCOPE = "lending.application-withdrawal:";

    @NonNull private final LoanApplications applications;
    @NonNull private final IdentityStore<Connection> identities;
    @NonNull private final MfaEnrolmentStore<Connection> enrolments;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final LendingTransactions transactions;
    @NonNull private final LoanApplicationMetrics metrics;
    @NonNull private final LoanOffers offers;

    /**
     * An application as its customer sees it - its status, credit's request id (the amount and term applied for are read
     * there), the terms version in force at submission, the customer's choices, and once declined credit's reasons in
     * the customer's words, and once offered its offer's id (`P11-TSK-011` - the offer is read at
     * {@code /v1/me/loan-offers/{id}}). Never a figure, a rate, a score or a decision.
     */
    public record LoanApplicationView(
            String applicationId,
            String product,
            String status,
            String decisionRequestId,
            int termsVersion,
            int repaymentDay,
            String repaymentMethod,
            String payoutPreference,
            String submittedAt,
            String closedAt,
            List<String> reasons,
            String offerId) {}

    public LoanApplicationView submit(
            Session current, String idempotencyKey, LoanApplicationController.LoanApplicationBody body) {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        LoanApplications.Submission submission = parse(body);
        IdempotencyKey key = new IdempotencyKey(SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        IdempotentExecutor.ExecutionOutcome outcome;
        try {
            outcome = transactions.inTransaction(unitOfWork -> {
                UUID party = partyOf(unitOfWork, current);
                requireAssurance(unitOfWork, current);
                RequestFingerprint fingerprint = RequestFingerprint.sha256(String.join("|", "lending.application",
                                party.toString(), submission.product().name(),
                                Long.toString(submission.requested().minorUnits()), submission.requested().currency().code(),
                                submission.termMonths().map(String::valueOf).orElse("-"),
                                Integer.toString(submission.repaymentDay()), submission.repaymentMethod().name(),
                                submission.payoutPreference().name(), minor(submission.declaredMonthlyIncome()),
                                minor(submission.declaredMonthlyExpenditure()))
                        .getBytes(StandardCharsets.UTF_8));
                return executor.execute(unitOfWork, key, fingerprint, claimed -> CommandResult.succeeded(
                        stored(view(applications.submit(claimed, party, submission, actor, correlation), List.of()))));
            });
        } catch (LoanApplications.NotInGoodStanding | DecisionRequests.ApplicantNotEligible notEligible) {
            metrics.record(submission.product(), LoanApplicationMetrics.Outcome.NOT_ELIGIBLE);
            throw new ApiException(CreditErrorCode.APPLICANT_NOT_ELIGIBLE, "The applicant is not eligible");
        } catch (LoanApplications.ProductNotOffered notOffered) {
            metrics.record(submission.product(), LoanApplicationMetrics.Outcome.PRODUCT_NOT_OFFERED);
            throw new ApiException(LendingErrorCode.PRODUCT_NOT_OFFERED, "The product is not offered",
                    "the product is not offered.");
        } catch (DecisionRequests.ProductNotOffered notOffered) {
            metrics.record(submission.product(), LoanApplicationMetrics.Outcome.PRODUCT_NOT_OFFERED);
            throw new ApiException(CreditErrorCode.PRODUCT_NOT_OFFERED, "The product is not offered",
                    "the product is not offered.");
        } catch (LoanApplications.AmountOutOfRange outOfRange) {
            metrics.record(submission.product(), LoanApplicationMetrics.Outcome.OUT_OF_RANGE);
            throw new ApiException(LendingErrorCode.AMOUNT_OUT_OF_RANGE, "The application is outside the product's bounds",
                    outOfRange.getMessage() + ".");
        } catch (DecisionRequests.ConsentAbsent absent) {
            // Refused before any provider is asked; the platform's one consent refusal names the purpose (INV-CNS-01).
            metrics.record(submission.product(), LoanApplicationMetrics.Outcome.CONSENT_REQUIRED);
            throw new ConsentNotGrantedException(ConsentBackedCreditConsentGate.purposeOf(absent.kind()));
        } catch (LoanApplications.ApplicationOpen open) {
            metrics.record(submission.product(), LoanApplicationMetrics.Outcome.APPLICATION_OPEN);
            throw new ApiException(LendingErrorCode.APPLICATION_OPEN, "An application for this product is open",
                    open.getMessage() + ".");
        }
        if (outcome.executed()) {
            metrics.record(submission.product(), LoanApplicationMetrics.Outcome.SUBMITTED);
        }
        return replayed(outcome.body().orElseThrow(() -> new IllegalStateException("a submission stores its view")));
    }

    /** The caller's own application; another party's is the same {@code 404} as an unknown or malformed id. */
    public LoanApplicationView read(Session current, String rawId) {
        LoanApplicationId id = applicationId(rawId);
        return transactions.inTransaction(unitOfWork -> {
            UUID party = partyOf(unitOfWork, current);
            return applications.read(unitOfWork, id, party).map(read -> view(read.application(), read.reasonTexts(),
                    read.application().status() == LoanApplicationStatus.OFFERED
                            ? offers.offerFor(unitOfWork, id, party) : Optional.empty()));
        }).orElseThrow(LoanApplicationDesk::notFound);
    }

    public LoanApplicationView withdraw(Session current, String rawId, String idempotencyKey) {
        LoanApplicationId id = applicationId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        IdempotencyKey key = new IdempotencyKey(WITHDRAWAL_SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint =
                RequestFingerprint.sha256(("lending.application-withdrawal|" + id.value()).getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome;
        try {
            outcome = transactions.inTransaction(unitOfWork -> {
                UUID party = partyOf(unitOfWork, current);
                requireConditionalAssurance(unitOfWork, current);
                return executor.execute(unitOfWork, key, fingerprint, claimed -> CommandResult.succeeded(
                        stored(view(applications.withdraw(claimed, id, party, actor, correlation), List.of()))));
            });
        } catch (LoanApplications.ApplicationNotFound absent) {
            throw notFound();
        } catch (LoanApplications.ApplicationNotWithdrawable past) {
            metrics.record(past.product(), LoanApplicationMetrics.Outcome.NOT_WITHDRAWABLE);
            throw new ApiException(LendingErrorCode.APPLICATION_NOT_WITHDRAWABLE,
                    "The application can no longer be withdrawn", past.getMessage() + ".");
        }
        LoanApplicationView view =
                replayed(outcome.body().orElseThrow(() -> new IllegalStateException("a withdrawal stores its view")));
        if (outcome.executed()) {
            metrics.record(LoanProduct.valueOf(view.product()), LoanApplicationMetrics.Outcome.WITHDRAWN);
        }
        return view;
    }

    // ------------------------------------------------------------------ the body in the domain's words

    private static LoanApplications.Submission parse(LoanApplicationController.LoanApplicationBody body) {
        LoanProduct product;
        try {
            product = LoanProduct.valueOf(body.product());
        } catch (IllegalArgumentException unknown) {
            throw new ApiException(LendingErrorCode.PRODUCT_NOT_OFFERED, "The product is not offered",
                    "the product is not offered.");
        }
        if (!product.currency().code().equals(body.currency())) {
            throw new ApiException(LendingErrorCode.AMOUNT_OUT_OF_RANGE, "The application is outside the product's bounds",
                    "the amount is in " + product.currency().code() + ".");
        }
        RepaymentMethod method = named(RepaymentMethod.class, body.repaymentMethod(), "repaymentMethod");
        PayoutPreference payout = body.payoutPreference() == null ? PayoutPreference.WALLET
                : named(PayoutPreference.class, body.payoutPreference(), "payoutPreference");
        try {
            return new LoanApplications.Submission(product, money(body.amount(), product, "amount"),
                    Optional.ofNullable(body.termMonths()),
                    Optional.ofNullable(body.declaredMonthlyIncome()).map(text -> money(text, product, "declaredMonthlyIncome")),
                    Optional.ofNullable(body.declaredMonthlyExpenditure())
                            .map(text -> money(text, product, "declaredMonthlyExpenditure")),
                    body.repaymentDay(), method, payout);
        } catch (LoanApplications.SubmissionInvalid invalid) {
            throw invalid(invalid.getMessage());
        }
    }

    private static Money money(String text, LoanProduct product, String field) {
        try {
            // The shape first: an exponent never reaches Money.of's rescale.
            return Money.of(DecimalText.parse(text), product.currency());
        } catch (RuntimeException malformed) {
            throw invalid(field + " must be an exact decimal at the currency's minor units");
        }
    }

    private static <E extends Enum<E>> E named(Class<E> type, String raw, String field) {
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw invalid(field + " is not one this platform offers");
        }
    }

    private static String minor(Optional<Money> amount) {
        return amount.map(money -> Long.toString(money.minorUnits())).orElse("-");
    }

    // ------------------------------------------------------------------ assurance

    /** A submission's assurance (A19): a {@code MULTI_FACTOR} session, always - enrolled or not. */
    private void requireAssurance(Connection unitOfWork, Session current) {
        if (current.assurance().atLeast(AssuranceLevel.MULTI_FACTOR)) {
            return;
        }
        boolean hasFactor = enrolments.findActive(unitOfWork, current.identityId(), MfaFactorType.TOTP).isPresent();
        throw new ApiException(IdentityErrorCode.ASSURANCE_REQUIRED,
                "A loan application requires a MULTI_FACTOR session",
                hasFactor
                        ? "applying for a loan requires a second factor: step up with your enrolled factor and retry."
                        : "applying for a loan requires a second factor: enrol one, step up with it, and retry.");
    }

    /** A withdrawal's assurance: the platform's conditional step-up. */
    private void requireConditionalAssurance(Connection unitOfWork, Session current) {
        boolean hasFactor = enrolments.findActive(unitOfWork, current.identityId(), MfaFactorType.TOTP).isPresent();
        if (hasFactor && !current.assurance().atLeast(AssuranceLevel.MULTI_FACTOR)) {
            throw new ApiException(IdentityErrorCode.ASSURANCE_REQUIRED,
                    "A loan application withdrawal from an MFA-enrolled identity requires a MULTI_FACTOR session");
        }
    }

    // ------------------------------------------------------------------ the view

    static LoanApplicationView view(LoanApplication application, List<String> reasons) {
        return view(application, reasons, Optional.empty());
    }

    static LoanApplicationView view(LoanApplication application, List<String> reasons, Optional<LoanOfferId> offer) {
        return new LoanApplicationView(
                application.id().value().toString(),
                application.product().name(),
                application.status().name(),
                application.decisionRequest().toString(),
                application.termsVersion(),
                application.repaymentDay(),
                application.repaymentMethod().name(),
                application.payoutPreference().name(),
                application.submittedAt().toString(),
                application.closedAt().map(Instant::toString).orElse(null),
                reasons.isEmpty() ? null : reasons,
                offer.map(id -> id.value().toString()).orElse(null));
    }

    /** A keyed act's recorded response: the application as it stood then - submitted or withdrawn, never decided. */
    private static StoredResponse stored(LoanApplicationView view) {
        String body = String.join("|", "OK", view.applicationId(), view.product(), view.status(),
                view.decisionRequestId(), Integer.toString(view.termsVersion()), Integer.toString(view.repaymentDay()),
                view.repaymentMethod(), view.payoutPreference(), view.submittedAt(),
                view.closedAt() == null ? "" : view.closedAt());
        return StoredResponse.of(body.getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    private static LoanApplicationView replayed(byte[] body) {
        String[] fields = new String(body, StandardCharsets.UTF_8).split("\\|", -1);
        return new LoanApplicationView(fields[1], fields[2], fields[3], fields[4], Integer.parseInt(fields[5]),
                Integer.parseInt(fields[6]), fields[7], fields[8], fields[9], fields[10].isEmpty() ? null : fields[10],
                null, null);
    }

    // ------------------------------------------------------------------ plumbing

    private static LoanApplicationId applicationId(String raw) {
        try {
            return LoanApplicationId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    private static ApiException notFound() {
        return new ApiException(LendingErrorCode.NOT_FOUND, "No loan application matches the requested identifier",
                "no such loan application.");
    }

    private static ApiException invalid(String detail) {
        return new ApiException(PlatformErrorCode.VALIDATION_FAILED, "The loan application was not valid", detail);
    }

    private UUID partyOf(Connection unitOfWork, Session current) {
        return identities.findById(unitOfWork, current.identityId())
                .map(identity -> identity.partyId())
                .orElseThrow(() -> new IllegalStateException(
                        "A proven session resolved to no identity; registration should make this impossible"));
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a loan application command runs inside a correlation scope"))
                .correlationId();
    }
}
