package com.finapp.app.credit;

import com.finapp.app.api.DecimalText;
import com.finapp.consent.ConsentNotGrantedException;
import com.finapp.credit.ClosureReason;
import com.finapp.credit.CreditDecision;
import com.finapp.credit.CreditDecisions;
import com.finapp.credit.CreditErrorCode;
import com.finapp.credit.JdbcCreditReads;
import com.finapp.credit.ReasonCode;
import com.finapp.credit.CreditProduct;
import com.finapp.credit.DecisionRequest;
import com.finapp.credit.DecisionRequestId;
import com.finapp.credit.DecisionRequests;
import com.finapp.credit.TransactionRunner;
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
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The customer's credit decision request doors (`P10-TSK-014`; CREDIT_DECISIONING_LIFECYCLES.md section 3.1): submit
 * (keyed, asynchronous - {@code 202} with the request, its outcome on the read), read (the caller's own requests only)
 * and cancel (keyed, synchronous). Each runs in one transaction under the {@link IdempotentExecutor} claim: the claim,
 * the request, its history and its event commit together, so a retried key replays the recorded response and a refusal -
 * which writes nothing - rolls the claim back with it and is judged afresh.
 *
 * <p><strong>Submission requires an MFA-assured session</strong> (PHASE_10_PLAN.md's gate wording, restored by the owner's
 * decision of 2026-10-10 at the Phase 10 to 11 transition): applying for credit is a {@code MULTI_FACTOR} act, enrolled or
 * not - a customer with no active second factor is refused {@code identity.AssuranceRequired} until they enrol one and
 * step up, and an enrolled customer on a password-only session is refused until they step up; both before any write.
 * <strong>Cancelling keeps the conditional step-up</strong> (the `P4-TSK-007` conditional, the cross-border precedent):
 * an identity with an active TOTP factor cancels from a {@code MULTI_FACTOR} session - withdrawing an application moves
 * no money and opens no exposure, so it is not held behind an enrolment.
 */
@RequiredArgsConstructor
public final class CreditDecisionRequestDesk {

    static final String SCOPE = "credit.decision:";
    static final String CANCEL_SCOPE = "credit.decision-cancellation:";

    @NonNull private final DecisionRequests requests;
    @NonNull private final IdentityStore<Connection> identities;
    @NonNull private final MfaEnrolmentStore<Connection> enrolments;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final TransactionRunner transactions;
    @NonNull private final CreditDecisions<Connection> decisions;
    @NonNull private final JdbcCreditReads reads;

    /** A request as its applicant sees it - never a declared figure, never a score, a rate or a decision. */
    public record CreditDecisionRequestView(
            String requestId,
            String product,
            String status,
            String requestedAmount,
            String currency,
            Integer termMonths,
            String submittedAt,
            String expiresAt,
            String closureReason,
            String statusText,
            String outcome,
            String approvedAmount,
            Integer approvedTermMonths,
            String validUntil,
            List<String> reasons) {}

    /**
     * The customer's credit profile (`P10-TSK-017`): the sources on file with when each last answered, and the
     * decisions still in their validity - never a figure, a score or a datum the sources gave.
     */
    public record CustomerCreditProfileView(List<CreditSourceView> sources, List<CreditCurrentDecisionView> decisions) {}

    /** A source on file: its kind and when it last answered. */
    public record CreditSourceView(String sourceKind, String retrievedAt) {}

    /** A decision still valid: which request, which product, the outcome and until when. */
    public record CreditCurrentDecisionView(String requestId, String product, String outcome, String validUntil) {}

    public CreditDecisionRequestView submit(
            Session current, String idempotencyKey, CreditDecisionRequestController.CreditDecisionRequestBody body) {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        DecisionRequests.Terms terms = parse(body);
        IdempotencyKey key = new IdempotencyKey(SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        IdempotentExecutor.ExecutionOutcome outcome;
        try {
            outcome = transactions.inTransaction(unitOfWork -> {
                UUID party = partyOf(unitOfWork, current);
                requireAssurance(unitOfWork, current);
                RequestFingerprint fingerprint = RequestFingerprint.sha256(String.join("|", "credit.decision",
                                party.toString(), terms.product().name(), Long.toString(terms.requested().minorUnits()),
                                terms.requested().currency().code(), terms.termMonths().map(String::valueOf).orElse("-"),
                                minor(terms.declaredMonthlyIncome()), minor(terms.declaredMonthlyExpenditure()))
                        .getBytes(StandardCharsets.UTF_8));
                return executor.execute(unitOfWork, key, fingerprint, claimed -> CommandResult.succeeded(
                        stored(view(requests.submit(claimed, party, terms, actor, correlation)))));
            });
        } catch (DecisionRequests.ApplicantNotEligible notEligible) {
            throw new ApiException(CreditErrorCode.APPLICANT_NOT_ELIGIBLE, "The applicant is not eligible");
        } catch (DecisionRequests.ProductNotOffered notOffered) {
            throw new ApiException(CreditErrorCode.PRODUCT_NOT_OFFERED, "The product is not offered",
                    "the product is not offered.");
        } catch (DecisionRequest.AmountOutOfRange outOfRange) {
            throw new ApiException(CreditErrorCode.AMOUNT_OUT_OF_RANGE, "The terms are outside the product's bounds",
                    outOfRange.getMessage() + ".");
        } catch (DecisionRequests.ConsentAbsent absent) {
            // Refused before any provider is asked; the platform's one consent refusal names the purpose (INV-CRD-03).
            throw new ConsentNotGrantedException(ConsentBackedCreditConsentGate.purposeOf(absent.kind()));
        } catch (DecisionRequests.DecisionRequestOpen open) {
            throw new ApiException(CreditErrorCode.DECISION_REQUEST_OPEN, "An open decision request exists",
                    "decision request " + open.open().value() + " is open for this product; wait for it or cancel it.");
        }
        return replayed(outcome.body().orElseThrow());
    }

    /**
     * The caller's own request (`P10-TSK-014`), and since `P10-TSK-017` its outcome: a closed request's status in plain
     * words; a decided one's outcome, approved amount and term, validity, and its reasons' customer texts in ordinal
     * order - never a bureau datum, a score, a threshold or a risk signal.
     */
    public CreditDecisionRequestView read(Session current, String rawId) {
        DecisionRequestId id = requestId(rawId);
        return transactions.inTransaction(unitOfWork -> requests.read(unitOfWork, id, partyOf(unitOfWork, current))
                        .map(request -> view(request, decisions.forRequest(unitOfWork, request.id().value()))))
                .orElseThrow(CreditDecisionRequestDesk::notFound);
    }

    /** The caller's own credit profile (`P10-TSK-017`) - read in one transaction, its owner the session's party. */
    public CustomerCreditProfileView profile(Session current) {
        return transactions.inTransaction(unitOfWork -> {
            UUID party = partyOf(unitOfWork, current);
            return new CustomerCreditProfileView(
                    reads.sourcesOnFile(unitOfWork, party).stream()
                            .map(source -> new CreditSourceView(source.kind().name(), source.retrievedAt().toString()))
                            .toList(),
                    reads.currentDecisions(unitOfWork, party).stream()
                            .map(decision -> new CreditCurrentDecisionView(decision.decisionRequest().toString(),
                                    decision.product().name(), decision.outcome().name(), decision.validUntil().toString()))
                            .toList());
        });
    }

    public CreditDecisionRequestView cancel(Session current, String rawId, String idempotencyKey) {
        DecisionRequestId id = requestId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        IdempotencyKey key = new IdempotencyKey(CANCEL_SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint =
                RequestFingerprint.sha256(("credit.decision-cancellation|" + id.value()).getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome;
        try {
            outcome = transactions.inTransaction(unitOfWork -> {
                UUID party = partyOf(unitOfWork, current);
                requireConditionalAssurance(unitOfWork, current);
                return executor.execute(unitOfWork, key, fingerprint, claimed -> CommandResult.succeeded(
                        stored(view(requests.cancel(claimed, id, party, actor, correlation)))));
            });
        } catch (DecisionRequests.DecisionRequestNotFound absent) {
            throw notFound();
        } catch (DecisionRequests.RequestNotCancellable closed) {
            throw new ApiException(CreditErrorCode.REQUEST_NOT_CANCELLABLE, "The request is past cancellation",
                    "the decision request can no longer be cancelled.");
        }
        return replayed(outcome.body().orElseThrow());
    }

    // ------------------------------------------------------------------ plumbing

    private static DecisionRequests.Terms parse(CreditDecisionRequestController.CreditDecisionRequestBody body) {
        CreditProduct product;
        try {
            product = CreditProduct.valueOf(body.product());
        } catch (IllegalArgumentException unknown) {
            throw new ApiException(CreditErrorCode.PRODUCT_NOT_OFFERED, "The product is not offered",
                    "the product is not offered.");
        }
        CurrencyCode currency;
        try {
            currency = CurrencyCode.of(body.currency());
        } catch (IllegalArgumentException unknown) {
            throw invalid("currency must be an ISO 4217 code");
        }
        return new DecisionRequests.Terms(product, money(body.amount(), currency, "amount"),
                Optional.ofNullable(body.termMonths()),
                Optional.ofNullable(body.declaredMonthlyIncome()).map(text -> money(text, currency, "declaredMonthlyIncome")),
                Optional.ofNullable(body.declaredMonthlyExpenditure())
                        .map(text -> money(text, currency, "declaredMonthlyExpenditure")));
    }

    private static Money money(String text, CurrencyCode currency, String field) {
        try {
            // The shape first: an exponent never reaches Money.of's rescale.
            return Money.of(DecimalText.parse(text), currency);
        } catch (RuntimeException malformed) {
            throw invalid(field + " must be an exact decimal at the currency's minor units");
        }
    }

    private static String minor(Optional<Money> amount) {
        return amount.map(money -> Long.toString(money.minorUnits())).orElse("-");
    }

    /**
     * A submission's assurance (the owner's decision of 2026-10-10): a {@code MULTI_FACTOR} session, always. A customer
     * with no active factor cannot hold one, so they are refused until they enrol - the same code, the detail telling
     * them which step is theirs.
     */
    private void requireAssurance(Connection unitOfWork, Session current) {
        if (current.assurance().atLeast(AssuranceLevel.MULTI_FACTOR)) {
            return;
        }
        boolean hasFactor = enrolments.findActive(unitOfWork, current.identityId(), MfaFactorType.TOTP).isPresent();
        throw new ApiException(IdentityErrorCode.ASSURANCE_REQUIRED,
                "A credit decision request requires a MULTI_FACTOR session",
                hasFactor
                        ? "applying for credit requires a second factor: step up with your enrolled factor and retry."
                        : "applying for credit requires a second factor: enrol one, step up with it, and retry.");
    }

    private void requireConditionalAssurance(Connection unitOfWork, Session current) {
        boolean hasFactor = enrolments.findActive(unitOfWork, current.identityId(), MfaFactorType.TOTP).isPresent();
        if (hasFactor && !current.assurance().atLeast(AssuranceLevel.MULTI_FACTOR)) {
            throw new ApiException(IdentityErrorCode.ASSURANCE_REQUIRED,
                    "A credit decision request from an MFA-enrolled identity requires a MULTI_FACTOR session");
        }
    }

    static CreditDecisionRequestView view(DecisionRequest request) {
        return view(request, Optional.empty());
    }

    static CreditDecisionRequestView view(DecisionRequest request, Optional<CreditDecision> decision) {
        DecisionRequest.Application application = request.application();
        return new CreditDecisionRequestView(
                request.id().value().toString(),
                application.product().name(),
                request.status().name(),
                application.requested().toBigDecimal().toPlainString(),
                application.requested().currency().code(),
                application.termMonths().orElse(null),
                request.submittedAt().toString(),
                request.expiresAt().toString(),
                request.closureReason().map(Enum::name).orElse(null),
                statusText(request),
                decision.map(made -> made.outcome().name()).orElse(null),
                decision.flatMap(CreditDecision::approved).map(amount -> amount.toBigDecimal().toPlainString()).orElse(null),
                decision.flatMap(CreditDecision::termMonths).orElse(null),
                decision.map(made -> made.validUntil().toString()).orElse(null),
                decision.map(made -> made.reasons().stream().map(ReasonCode::customerText).toList()).orElse(null));
    }

    /** A closed request's status in plain words - the closure, never a figure. */
    private static String statusText(DecisionRequest request) {
        return switch (request.status()) {
            case CANCELLED -> "You cancelled this application.";
            case EXPIRED -> "We could not reach a decision on this application in time; you may apply again.";
            case ABANDONED -> request.closureReason().orElseThrow() == ClosureReason.STANDING_LOST
                    ? "We could not continue this application because your account is not currently in good standing."
                    : "This application stopped because a consent it needed was withdrawn.";
            default -> null;
        };
    }

    private static StoredResponse stored(CreditDecisionRequestView view) {
        String body = String.join("|", "OK", view.requestId(), view.product(), view.status(), view.requestedAmount(),
                view.currency(), view.termMonths() == null ? "" : view.termMonths().toString(), view.submittedAt(),
                view.expiresAt(), view.closureReason() == null ? "" : view.closureReason());
        return StoredResponse.of(body.getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    private static CreditDecisionRequestView replayed(byte[] body) {
        String[] fields = new String(body, StandardCharsets.UTF_8).split("\\|", -1);
        // A keyed act's recorded response is the request as it stood then - submitted or cancelled, never decided.
        return new CreditDecisionRequestView(fields[1], fields[2], fields[3], fields[4], fields[5],
                fields[6].isEmpty() ? null : Integer.valueOf(fields[6]), fields[7], fields[8],
                fields[9].isEmpty() ? null : fields[9], "CANCELLED".equals(fields[3]) ? "You cancelled this application." : null,
                null, null, null, null, null);
    }

    private static DecisionRequestId requestId(String raw) {
        try {
            return DecisionRequestId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    private static ApiException notFound() {
        return new ApiException(CreditErrorCode.NOT_FOUND, "No decision request matches the requested identifier",
                "no such decision request.");
    }

    private static ApiException invalid(String detail) {
        return new ApiException(PlatformErrorCode.VALIDATION_FAILED, "The decision request was not valid", detail);
    }

    private UUID partyOf(Connection unitOfWork, Session current) {
        return identities.findById(unitOfWork, current.identityId())
                .map(identity -> identity.partyId())
                .orElseThrow(() -> new IllegalStateException(
                        "A proven session resolved to no identity; registration should make this impossible"));
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a credit command runs inside a correlation scope"))
                .correlationId();
    }
}
