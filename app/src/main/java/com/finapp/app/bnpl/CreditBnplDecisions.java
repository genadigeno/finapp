package com.finapp.app.bnpl;

import com.finapp.bnpl.BnplCreditDecisions;
import com.finapp.credit.CreditDecision;
import com.finapp.credit.CreditDecisions;
import com.finapp.credit.CreditProduct;
import com.finapp.credit.DecisionRequest;
import com.finapp.credit.DecisionRequestId;
import com.finapp.credit.DecisionRequests;
import com.finapp.credit.ReasonCode;
import com.finapp.credit.RequestOrigin;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * bnpl's {@link BnplCreditDecisions} over credit's published API (`P12-TSK-010`; {@code PHASE_12_PLAN.md} sections 3.3
 * and 7.1 T-app) - the composition root's half, because bnpl has no build edge to {@code credit} (ADR-0101 decision 6,
 * lending's {@code CreditLoanDecisions} precedent). Every call runs on the caller's connection, so credit's request, its
 * history, its event and its audit commit or roll back with bnpl's application.
 *
 * <p><strong>Always the BNPL origin</strong> ({@link #ORIGIN}): credit's {@code RequestOrigin.BNPL_APPLICATION}, the
 * one origin admitting a BNPL product (`P12-TSK-001`) - the submission is opened from it and the cancellation asked from
 * it, so a request opened here is cancelled here and nowhere else. The product crosses by name
 * ({@code BnplVocabularyTest} holds bnpl's products to exactly credit's BNPL family); the amount is the session's gross;
 * pay-in-4 sends no term, pay-in-N its plan's months; no declared figure is sent. The refusals bnpl gives meaning to are
 * translated into the port's own; every other refusal of credit's ({@code ApplicantNotEligible}, {@code ConsentAbsent},
 * {@code ProductNotOffered}) passes through untouched, so the door answers it exactly as credit's own door would.
 */
@RequiredArgsConstructor
final class CreditBnplDecisions implements BnplCreditDecisions {

    /** The one origin every BNPL request is opened and cancelled from. */
    static final RequestOrigin ORIGIN = RequestOrigin.BNPL_APPLICATION;

    @NonNull private final DecisionRequests requests;
    @NonNull private final CreditDecisions<Connection> decisions;

    @Override
    public UUID open(Connection unitOfWork, UUID party, CreditRequest request, Actor actor, CorrelationId correlation) {
        try {
            return requests.submit(unitOfWork, party, terms(request), ORIGIN, actor, correlation).id().value();
        } catch (DecisionRequests.DecisionRequestOpen open) {
            throw new RequestOpen();
        } catch (DecisionRequest.AmountOutOfRange outOfRange) {
            throw new TermsOutOfBounds(outOfRange.getMessage());
        }
    }

    @Override
    public void cancel(Connection unitOfWork, UUID decisionRequest, UUID party, Actor actor, CorrelationId correlation) {
        try {
            requests.cancel(unitOfWork, DecisionRequestId.of(decisionRequest), party, ORIGIN, actor, correlation);
        } catch (DecisionRequests.RequestNotCancellable pastCancellation) {
            throw new NotCancellable();
        } catch (DecisionRequests.DecisionRequestNotFound absent) {
            throw new IllegalStateException(
                    "a BNPL application's credit request is its own party's, opened from the BNPL origin, never deleted");
        }
    }

    @Override
    public List<String> reasonTexts(Connection unitOfWork, UUID decisionRequest) {
        return decisions.forRequest(unitOfWork, decisionRequest)
                .map(CreditDecision::reasons)
                .map(reasons -> reasons.stream().map(ReasonCode::customerText).toList())
                .orElse(List.of());
    }

    /**
     * What credit is asked: credit's product of the same name, the purchase amount, the plan's term when the product's
     * term shape takes one, and no declared figures.
     */
    static DecisionRequests.Terms terms(CreditRequest request) {
        Objects.requireNonNull(request, "request");
        CreditProduct product = CreditProduct.valueOf(request.product().name());
        if (product.family() != CreditProduct.Family.BNPL) {
            throw new IllegalStateException(request.product() + " maps onto a " + product.family() + " product");
        }
        Optional<Integer> term = request.termMonths().isPresent()
                ? Optional.of(request.termMonths().getAsInt())
                : Optional.empty();
        return new DecisionRequests.Terms(product, request.amount(), term, Optional.empty(), Optional.empty());
    }
}
