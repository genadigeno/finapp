package com.finapp.app.bnpl;

import com.finapp.app.credit.ConsentBackedCreditConsentGate;
import com.finapp.bnpl.BnplApplication;
import com.finapp.bnpl.BnplApplicationId;
import com.finapp.bnpl.BnplApplications;
import com.finapp.bnpl.BnplCheckoutSessions;
import com.finapp.bnpl.BnplErrorCode;
import com.finapp.bnpl.BnplPlan;
import com.finapp.bnpl.BnplProduct;
import com.finapp.consent.ConsentNotGrantedException;
import com.finapp.credit.CreditErrorCode;
import com.finapp.credit.DecisionRequests;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityErrorCode;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.MfaEnrolmentStore;
import com.finapp.identity.MfaFactorType;
import com.finapp.identity.Session;
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
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The boundary half of the BNPL application doors (`P12-TSK-010`; lending's {@code LoanApplicationDesk}): parse the body
 * exactly into the domain's words, key each act per principal, run it in one transaction under the
 * {@link IdempotentExecutor} claim - the claim, the application, its history, credit's request and both modules' events
 * and audit records commit together, so a retried key replays the recorded response and a refusal, which writes nothing,
 * rolls the claim back with it - and answer the refusals on the error contract. The machine, the one-open rules and the
 * bounds are the domain's, credit's and {@code bnpl V003}'s.
 *
 * <p><strong>Submission requires a {@code MULTI_FACTOR} session, always</strong> (A18): applying for BNPL opens credit,
 * enrolled or not - refused {@code identity.AssuranceRequired} before any write. <strong>Withdrawal keeps the conditional
 * step-up</strong> ({@code BNPL_LIFECYCLES.md} section 3): an identity with an active factor withdraws from a
 * {@code MULTI_FACTOR} session - withdrawing moves no money and opens no exposure.
 *
 * <p><strong>The session token</strong> stays wrapped from the body to checkout's digest: it is never logged, stored,
 * answered or hashed into the fingerprint here - the fingerprint names the SESSION the token resolves to (a plain read
 * inside the same transaction), so the same key replayed with another session's token is {@code api.Conflict}, and an
 * unknown token is fingerprinted as none and refused by the domain.
 *
 * <p><strong>Credit's refusals pass through unchanged</strong> where they are the cause ({@code 409
 * credit.ApplicantNotEligible}, {@code 409 consent.ConsentRequired}, {@code 422 credit.ProductNotOffered} for a product
 * no credit policy offers); credit's open request for the party and product is {@code 409 bnpl.ApplicationOpen}, and an
 * amount outside credit's bounds {@code 422 bnpl.AmountOutOfRange}.
 */
@RequiredArgsConstructor
public final class BnplApplicationDesk {

    static final String SCOPE = "bnpl.application:";
    static final String WITHDRAWAL_SCOPE = "bnpl.application-withdrawal:";

    @NonNull private final BnplApplications applications;
    @NonNull private final BnplCheckoutSessions sessions;
    @NonNull private final IdentityStore<Connection> identities;
    @NonNull private final MfaEnrolmentStore<Connection> enrolments;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final BnplTransactions transactions;
    @NonNull private final BnplApplicationMetrics metrics;

    /**
     * An application as its customer sees it - its status, product and plan, credit's request id, the terms version in
     * force at submission, its instants, the closure reason once closed undecided, and once declined credit's reasons in
     * the customer's words. Never a figure, a score, a decision, the session's token, the session or the merchant.
     */
    public record BnplApplicationView(
            String applicationId,
            String product,
            String plan,
            String status,
            String decisionRequestId,
            int termsVersion,
            String submittedAt,
            String closedAt,
            String closureReason,
            List<String> reasons) {}

    public BnplApplicationView submit(
            Session current, String idempotencyKey, BnplApplicationController.BnplApplicationBody body) {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        BnplApplications.Submission submission = parse(body);
        IdempotencyKey key = new IdempotencyKey(SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        IdempotentExecutor.ExecutionOutcome outcome;
        try {
            outcome = transactions.inTransaction(unitOfWork -> {
                UUID party = partyOf(unitOfWork, current);
                requireAssurance(unitOfWork, current);
                String session = sessions.read(unitOfWork, submission.sessionToken())
                        .map(found -> found.sessionRef().toString())
                        .orElse("-");
                RequestFingerprint fingerprint = RequestFingerprint.sha256(String.join("|", "bnpl.application",
                                party.toString(), session, submission.product().name(), submission.plan().name())
                        .getBytes(StandardCharsets.UTF_8));
                return executor.execute(unitOfWork, key, fingerprint, claimed -> CommandResult.succeeded(
                        stored(view(applications.submit(claimed, party, submission, actor, correlation), List.of()))));
            });
        } catch (BnplApplications.NotInGoodStanding | DecisionRequests.ApplicantNotEligible notEligible) {
            metrics.record(submission.product(), BnplApplicationMetrics.Outcome.NOT_ELIGIBLE);
            throw new ApiException(CreditErrorCode.APPLICANT_NOT_ELIGIBLE, "The applicant is not eligible");
        } catch (BnplApplications.SessionNotEligible notEligible) {
            metrics.record(submission.product(), BnplApplicationMetrics.Outcome.SESSION_NOT_ELIGIBLE);
            throw new ApiException(BnplErrorCode.SESSION_NOT_ELIGIBLE, "The checkout session is not eligible for BNPL",
                    notEligible.getMessage() + ".");
        } catch (BnplApplications.MerchantNotEnabled notEnabled) {
            metrics.record(submission.product(), BnplApplicationMetrics.Outcome.MERCHANT_NOT_ENABLED);
            throw new ApiException(BnplErrorCode.MERCHANT_NOT_ENABLED, "The merchant does not offer this BNPL product",
                    "the merchant does not offer this BNPL product.");
        } catch (BnplApplications.ProductNotOffered notOffered) {
            metrics.record(submission.product(), BnplApplicationMetrics.Outcome.PRODUCT_NOT_OFFERED);
            throw new ApiException(BnplErrorCode.PRODUCT_NOT_OFFERED, "The BNPL product is not offered",
                    notOffered.getMessage() + ".");
        } catch (DecisionRequests.ProductNotOffered notOffered) {
            metrics.record(submission.product(), BnplApplicationMetrics.Outcome.PRODUCT_NOT_OFFERED);
            throw new ApiException(CreditErrorCode.PRODUCT_NOT_OFFERED, "The product is not offered",
                    "the product is not offered.");
        } catch (BnplApplications.AmountOutOfRange outOfRange) {
            metrics.record(submission.product(), BnplApplicationMetrics.Outcome.OUT_OF_RANGE);
            throw new ApiException(BnplErrorCode.AMOUNT_OUT_OF_RANGE,
                    "The purchase amount is outside the BNPL product's bounds", outOfRange.getMessage() + ".");
        } catch (DecisionRequests.ConsentAbsent absent) {
            // Refused before any provider is asked; the platform's one consent refusal names the purpose (INV-CNS-01).
            metrics.record(submission.product(), BnplApplicationMetrics.Outcome.CONSENT_REQUIRED);
            throw new ConsentNotGrantedException(ConsentBackedCreditConsentGate.purposeOf(absent.kind()));
        } catch (BnplApplications.ApplicationOpen open) {
            metrics.record(submission.product(), BnplApplicationMetrics.Outcome.APPLICATION_OPEN);
            throw new ApiException(BnplErrorCode.APPLICATION_OPEN, "A BNPL application is already open",
                    open.getMessage() + ".");
        }
        if (outcome.executed()) {
            metrics.record(submission.product(), BnplApplicationMetrics.Outcome.SUBMITTED);
        }
        return replayed(outcome.body().orElseThrow(() -> new IllegalStateException("a submission stores its view")));
    }

    /** The caller's own application; another party's is the same {@code 404} as an unknown or malformed id. */
    public BnplApplicationView read(Session current, String rawId) {
        BnplApplicationId id = applicationId(rawId);
        return transactions.inTransaction(unitOfWork -> {
            UUID party = partyOf(unitOfWork, current);
            return applications.read(unitOfWork, id, party).map(read -> view(read.application(), read.reasonTexts()));
        }).orElseThrow(BnplApplicationDesk::notFound);
    }

    public BnplApplicationView withdraw(Session current, String rawId, String idempotencyKey) {
        BnplApplicationId id = applicationId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        IdempotencyKey key = new IdempotencyKey(WITHDRAWAL_SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint =
                RequestFingerprint.sha256(("bnpl.application-withdrawal|" + id.value()).getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome;
        try {
            outcome = transactions.inTransaction(unitOfWork -> {
                UUID party = partyOf(unitOfWork, current);
                requireConditionalAssurance(unitOfWork, current);
                return executor.execute(unitOfWork, key, fingerprint, claimed -> CommandResult.succeeded(
                        stored(view(applications.withdraw(claimed, id, party, actor, correlation), List.of()))));
            });
        } catch (BnplApplications.ApplicationNotFound absent) {
            throw notFound();
        } catch (BnplApplications.ApplicationNotWithdrawable past) {
            metrics.record(past.product(), BnplApplicationMetrics.Outcome.NOT_WITHDRAWABLE);
            throw new ApiException(BnplErrorCode.APPLICATION_NOT_WITHDRAWABLE,
                    "The BNPL application can no longer be withdrawn", past.getMessage() + ".");
        }
        BnplApplicationView view =
                replayed(outcome.body().orElseThrow(() -> new IllegalStateException("a withdrawal stores its view")));
        if (outcome.executed()) {
            metrics.record(BnplProduct.valueOf(view.product()), BnplApplicationMetrics.Outcome.WITHDRAWN);
        }
        return view;
    }

    // ------------------------------------------------------------------ the body in the domain's words

    /**
     * The closed body as the domain's submission: an unknown product is {@code bnpl.ProductNotOffered} (lending's
     * precedent - the product vocabulary is the offer's), an unknown plan or a plan of another product
     * {@code api.ValidationFailed}.
     */
    static BnplApplications.Submission parse(BnplApplicationController.BnplApplicationBody body) {
        BnplProduct product;
        try {
            product = BnplProduct.valueOf(body.product());
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw new ApiException(BnplErrorCode.PRODUCT_NOT_OFFERED, "The BNPL product is not offered",
                    "the product is not offered.");
        }
        BnplPlan plan;
        try {
            plan = BnplPlan.valueOf(body.plan());
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw invalid("plan is not one this platform offers");
        }
        if (body.sessionToken() == null) {
            throw invalid("sessionToken is required");
        }
        try {
            return new BnplApplications.Submission(body.sessionToken(), product, plan);
        } catch (BnplApplications.SubmissionInvalid invalid) {
            throw invalid(invalid.getMessage());
        }
    }

    // ------------------------------------------------------------------ assurance

    /** A submission's assurance (A18): a {@code MULTI_FACTOR} session, always - enrolled or not. */
    private void requireAssurance(Connection unitOfWork, Session current) {
        if (current.assurance().atLeast(AssuranceLevel.MULTI_FACTOR)) {
            return;
        }
        boolean hasFactor = enrolments.findActive(unitOfWork, current.identityId(), MfaFactorType.TOTP).isPresent();
        throw new ApiException(IdentityErrorCode.ASSURANCE_REQUIRED,
                "A BNPL application requires a MULTI_FACTOR session",
                hasFactor
                        ? "applying for BNPL requires a second factor: step up with your enrolled factor and retry."
                        : "applying for BNPL requires a second factor: enrol one, step up with it, and retry.");
    }

    /** A withdrawal's assurance: the platform's conditional step-up. */
    private void requireConditionalAssurance(Connection unitOfWork, Session current) {
        boolean hasFactor = enrolments.findActive(unitOfWork, current.identityId(), MfaFactorType.TOTP).isPresent();
        if (hasFactor && !current.assurance().atLeast(AssuranceLevel.MULTI_FACTOR)) {
            throw new ApiException(IdentityErrorCode.ASSURANCE_REQUIRED,
                    "A BNPL application withdrawal from an MFA-enrolled identity requires a MULTI_FACTOR session");
        }
    }

    // ------------------------------------------------------------------ the view

    static BnplApplicationView view(BnplApplication application, List<String> reasons) {
        return new BnplApplicationView(
                application.id().value().toString(),
                application.product().name(),
                application.plan().name(),
                application.status().name(),
                application.decisionRequest().toString(),
                application.termsVersion(),
                application.submittedAt().toString(),
                application.closedAt().map(Instant::toString).orElse(null),
                application.closureReason().map(Enum::name).orElse(null),
                reasons.isEmpty() ? null : reasons);
    }

    /** A keyed act's recorded response: the application as it stood then - submitted or withdrawn, never decided. */
    static StoredResponse stored(BnplApplicationView view) {
        String body = String.join("|", "OK", view.applicationId(), view.product(), view.plan(), view.status(),
                view.decisionRequestId(), Integer.toString(view.termsVersion()), view.submittedAt(),
                view.closedAt() == null ? "" : view.closedAt());
        return StoredResponse.of(body.getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    static BnplApplicationView replayed(byte[] body) {
        String[] fields = new String(body, StandardCharsets.UTF_8).split("\\|", -1);
        return new BnplApplicationView(fields[1], fields[2], fields[3], fields[4], fields[5],
                Integer.parseInt(fields[6]), fields[7], fields[8].isEmpty() ? null : fields[8], null, null);
    }

    // ------------------------------------------------------------------ plumbing

    private static BnplApplicationId applicationId(String raw) {
        try {
            return BnplApplicationId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw notFound();
        }
    }

    private static ApiException notFound() {
        return new ApiException(BnplErrorCode.NOT_FOUND, "No BNPL application matches the requested identifier",
                "no such BNPL application.");
    }

    private static ApiException invalid(String detail) {
        return new ApiException(PlatformErrorCode.VALIDATION_FAILED, "The BNPL application was not valid", detail + ".");
    }

    private UUID partyOf(Connection unitOfWork, Session current) {
        return identities.findById(unitOfWork, current.identityId())
                .map(identity -> identity.partyId())
                .orElseThrow(() -> new IllegalStateException(
                        "A proven session resolved to no identity; registration should make this impossible"));
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a BNPL application command runs inside a correlation scope"))
                .correlationId();
    }
}
