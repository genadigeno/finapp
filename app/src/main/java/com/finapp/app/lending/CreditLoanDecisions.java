package com.finapp.app.lending;

import com.finapp.credit.CreditDecision;
import com.finapp.credit.CreditDecisionId;
import com.finapp.credit.CreditDecisions;
import com.finapp.credit.CreditProduct;
import com.finapp.credit.DecisionRequest;
import com.finapp.credit.DecisionOutcome;
import com.finapp.credit.DecisionRequestId;
import com.finapp.credit.DecisionRequests;
import com.finapp.credit.ReasonCode;
import com.finapp.lending.LoanCreditDecisions;
import com.finapp.lending.LoanProduct;
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
 * Lending's {@link LoanCreditDecisions} over credit's published API (`P11-TSK-010`; {@code PHASE_11_PLAN.md} section
 * 3.2) - the composition root's half, because lending has no build edge to {@code credit} (A16, the crossborder
 * precedent). Every call runs on the caller's connection, so credit's request, its history, its event and its audit
 * commit or roll back with lending's application.
 *
 * <p>The product crosses by name - {@code LendingVocabularyTest} holds {@code LoanProduct} and {@code CreditProduct} to
 * one bijection. The refusals lending gives meaning to are translated into the port's own; every other refusal of
 * credit's ({@code ApplicantNotEligible}, {@code ConsentAbsent}, {@code ProductNotOffered}) passes through untouched,
 * so the door answers it exactly as credit's own door would.
 *
 * <p><strong>The origination step's read</strong> (`P11-TSK-011`): {@link #decisionFor} reads credit's request through
 * credit's own owner-scoped read and, once {@code DECIDED}, its decision through the published {@code CreditDecisions}
 * read - plain reads on the caller's connection, no credit lock taken, so the offering transaction never waits on
 * credit's deciding one (credit locks no lending row either). {@link #requestOf} resolves a decision id - the aggregate
 * of {@code credit.CreditDecisionRecorded} - to its request for the hint.
 */
@RequiredArgsConstructor
public final class CreditLoanDecisions implements LoanCreditDecisions {

    @NonNull private final DecisionRequests requests;
    @NonNull private final CreditDecisions<Connection> decisions;

    @Override
    public UUID open(Connection unitOfWork, UUID party, CreditTerms terms, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(terms, "terms");
        DecisionRequests.Terms asked = new DecisionRequests.Terms(CreditProduct.valueOf(terms.product().name()),
                terms.requested(), terms.termMonths(), terms.declaredMonthlyIncome(), terms.declaredMonthlyExpenditure());
        try {
            return requests.submit(unitOfWork, party, asked, actor, correlation).id().value();
        } catch (DecisionRequests.DecisionRequestOpen open) {
            throw new RequestOpen();
        } catch (DecisionRequest.AmountOutOfRange outOfRange) {
            throw new TermsOutOfBounds(outOfRange.getMessage());
        }
    }

    @Override
    public void cancel(Connection unitOfWork, UUID decisionRequest, UUID party, Actor actor, CorrelationId correlation) {
        try {
            requests.cancel(unitOfWork, DecisionRequestId.of(decisionRequest), party, actor, correlation);
        } catch (DecisionRequests.RequestNotCancellable pastCancellation) {
            throw new NotCancellable();
        } catch (DecisionRequests.DecisionRequestNotFound absent) {
            throw new IllegalStateException("an application's credit request is its own party's, and never deleted");
        }
    }

    @Override
    public List<String> reasonTexts(Connection unitOfWork, UUID decisionRequest) {
        return decisions.forRequest(unitOfWork, decisionRequest)
                .map(CreditDecision::reasons)
                .map(reasons -> reasons.stream().map(ReasonCode::customerText).toList())
                .orElse(List.of());
    }

    @Override
    public CreditOutcome decisionFor(Connection unitOfWork, UUID decisionRequest, UUID party) {
        DecisionRequest request = requests.read(unitOfWork, DecisionRequestId.of(decisionRequest), party)
                .orElseThrow(() -> new IllegalStateException(
                        "an application's credit request is its own party's, and never deleted"));
        return switch (request.status()) {
            case SUBMITTED, COLLECTING, READY, EVALUATED, IN_REVIEW -> new CreditOutcome.Pending();
            case CANCELLED -> new CreditOutcome.Closed(CreditOutcome.Closure.CANCELLED);
            case EXPIRED -> new CreditOutcome.Closed(CreditOutcome.Closure.EXPIRED);
            case ABANDONED -> new CreditOutcome.Closed(CreditOutcome.Closure.ABANDONED);
            case DECIDED -> decided(decisions.forRequest(unitOfWork, decisionRequest)
                    .orElseThrow(() -> new IllegalStateException("a DECIDED request has its decision")));
        };
    }

    @Override
    public Optional<UUID> requestOf(Connection unitOfWork, UUID decision) {
        return decisions.byId(unitOfWork, CreditDecisionId.of(decision)).map(CreditDecision::decisionRequest);
    }

    private static CreditOutcome decided(CreditDecision decision) {
        if (decision.outcome() == DecisionOutcome.DECLINED) {
            return new CreditOutcome.Declined(decision.id().value());
        }
        return new CreditOutcome.Approved(decision.id().value(), decision.party(),
                LoanProduct.valueOf(decision.product().name()), decision.approved().orElseThrow(),
                decision.termMonths(), decision.validUntil());
    }
}
