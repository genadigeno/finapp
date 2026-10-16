package com.finapp.app.lending;

import com.finapp.credit.ConsumerKind;
import com.finapp.credit.ConsumerRef;
import com.finapp.credit.CreditDecision;
import com.finapp.credit.CreditDecisionConsumptions;
import com.finapp.credit.CreditDecisionId;
import com.finapp.credit.CreditDecisions;
import com.finapp.credit.CreditProfiles;
import com.finapp.credit.DecisionOutcome;
import com.finapp.credit.DecisionRequest;
import com.finapp.credit.DecisionRequestId;
import com.finapp.credit.DecisionRequests;
import com.finapp.credit.ReasonCode;
import com.finapp.credit.RequestOrigin;
import com.finapp.lending.LoanAgreementId;
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
 * <p>The product crosses by name through {@link LendingCreditProducts} - {@code LendingVocabularyTest} holds
 * {@code LoanProduct} to exactly credit's lending products, so lending never opens a BNPL request; and every request
 * lending opens is {@code RequestOrigin.CUSTOMER_DOOR}'s, the customer's own door's (`P12-TSK-001`). The refusals lending gives meaning to are translated into the port's own; every other refusal of
 * credit's ({@code ApplicantNotEligible}, {@code ConsentAbsent}, {@code ProductNotOffered}) passes through untouched,
 * so the door answers it exactly as credit's own door would.
 *
 * <p><strong>The origination step's read</strong> (`P11-TSK-011`): {@link #decisionFor} reads credit's request through
 * credit's own owner-scoped read and, once {@code DECIDED}, its decision through the published {@code CreditDecisions}
 * read - plain reads on the caller's connection, no credit lock taken, so the offering transaction never waits on
 * credit's deciding one (credit locks no lending row either). {@link #requestOf} resolves a decision id - the aggregate
 * of {@code credit.CreditDecisionRecorded} - to its request for the hint.
 *
 * <p><strong>The acceptance's two calls</strong> (`P11-TSK-012`): {@link #lockExposure} is credit's own profile lock -
 * {@code CreditProfiles.lockForDecision}, lock-order element (1), Phase 11's L0 - taken first by the acceptance, so it and
 * a credit decision for the party serialise; {@link #consume} is credit's published consumption port
 * ({@code CreditDecisionConsumptions}), the consumer the agreement ({@code ConsumerKind.LOAN_AGREEMENT}, its id) - credit
 * re-takes the lock (re-entrant), judges the lapse on its own clock and writes its own audit record. Credit's five answers
 * are folded into the port's two: consumed (a replay too - the same agreement asking again), or refused by name.
 */
@RequiredArgsConstructor
public final class CreditLoanDecisions implements LoanCreditDecisions {

    @NonNull private final DecisionRequests requests;
    @NonNull private final CreditDecisions<Connection> decisions;
    @NonNull private final CreditProfiles<Connection> profiles;
    @NonNull private final CreditDecisionConsumptions<Connection> consumptions;

    @Override
    public UUID open(Connection unitOfWork, UUID party, CreditTerms terms, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(terms, "terms");
        DecisionRequests.Terms asked = new DecisionRequests.Terms(LendingCreditProducts.of(terms.product()),
                terms.requested(), terms.termMonths(), terms.declaredMonthlyIncome(), terms.declaredMonthlyExpenditure());
        try {
            return requests.submit(unitOfWork, party, asked, RequestOrigin.CUSTOMER_DOOR, actor, correlation).id().value();
        } catch (DecisionRequests.DecisionRequestOpen open) {
            throw new RequestOpen();
        } catch (DecisionRequest.AmountOutOfRange outOfRange) {
            throw new TermsOutOfBounds(outOfRange.getMessage());
        }
    }

    @Override
    public void cancel(Connection unitOfWork, UUID decisionRequest, UUID party, Actor actor, CorrelationId correlation) {
        try {
            requests.cancel(unitOfWork, DecisionRequestId.of(decisionRequest), party, RequestOrigin.CUSTOMER_DOOR, actor,
                    correlation);
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

    @Override
    public boolean lockExposure(Connection unitOfWork, UUID party) {
        return profiles.lockForDecision(unitOfWork, party).isPresent();
    }

    @Override
    public Consumption consume(Connection unitOfWork, UUID decision, LoanAgreementId agreement) {
        CreditDecisionConsumptions.Consumption answer = consumptions.consume(unitOfWork, CreditDecisionId.of(decision),
                new ConsumerRef(ConsumerKind.LOAN_AGREEMENT, agreement.value()));
        return switch (answer) {
            case CreditDecisionConsumptions.Consumption.Consumed consumed -> new Consumption.Consumed(
                    consumed.decision().id().value(), consumed.decision().party(),
                    consumed.decision().approved().orElseThrow(
                            () -> new IllegalStateException("a consumed decision is an approval with its amount")));
            case CreditDecisionConsumptions.Consumption.NotApproved notApproved ->
                    new Consumption.Refused(Consumption.Refusal.NOT_APPROVED);
            case CreditDecisionConsumptions.Consumption.Lapsed lapsed ->
                    new Consumption.Refused(Consumption.Refusal.LAPSED);
            case CreditDecisionConsumptions.Consumption.AlreadyConsumed taken ->
                    new Consumption.Refused(Consumption.Refusal.ALREADY_CONSUMED);
            case CreditDecisionConsumptions.Consumption.NotFound absent ->
                    new Consumption.Refused(Consumption.Refusal.NOT_FOUND);
        };
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
