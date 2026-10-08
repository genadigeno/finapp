package com.finapp.app.credit;

import com.finapp.app.api.DecimalText;
import com.finapp.consent.ConsentNotGrantedException;
import com.finapp.credit.CreditErrorCode;
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
 * <p><strong>Step-up when a factor is enrolled</strong> (the `P4-TSK-007` conditional, the cross-border precedent): an
 * identity with an active TOTP factor submits and cancels from a {@code MULTI_FACTOR} session, refused
 * {@code identity.AssuranceRequired} before any write.
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
            String closureReason) {}

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
                requireConditionalAssurance(unitOfWork, current);
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

    public CreditDecisionRequestView read(Session current, String rawId) {
        DecisionRequestId id = requestId(rawId);
        return transactions.inTransaction(unitOfWork -> requests.read(unitOfWork, id, partyOf(unitOfWork, current)))
                .map(CreditDecisionRequestDesk::view)
                .orElseThrow(CreditDecisionRequestDesk::notFound);
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

    private void requireConditionalAssurance(Connection unitOfWork, Session current) {
        boolean hasFactor = enrolments.findActive(unitOfWork, current.identityId(), MfaFactorType.TOTP).isPresent();
        if (hasFactor && !current.assurance().atLeast(AssuranceLevel.MULTI_FACTOR)) {
            throw new ApiException(IdentityErrorCode.ASSURANCE_REQUIRED,
                    "A credit decision request from an MFA-enrolled identity requires a MULTI_FACTOR session");
        }
    }

    static CreditDecisionRequestView view(DecisionRequest request) {
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
                request.closureReason().map(Enum::name).orElse(null));
    }

    private static StoredResponse stored(CreditDecisionRequestView view) {
        String body = String.join("|", "OK", view.requestId(), view.product(), view.status(), view.requestedAmount(),
                view.currency(), view.termMonths() == null ? "" : view.termMonths().toString(), view.submittedAt(),
                view.expiresAt(), view.closureReason() == null ? "" : view.closureReason());
        return StoredResponse.of(body.getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    private static CreditDecisionRequestView replayed(byte[] body) {
        String[] fields = new String(body, StandardCharsets.UTF_8).split("\\|", -1);
        return new CreditDecisionRequestView(fields[1], fields[2], fields[3], fields[4], fields[5],
                fields[6].isEmpty() ? null : Integer.valueOf(fields[6]), fields[7], fields[8],
                fields[9].isEmpty() ? null : fields[9]);
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
