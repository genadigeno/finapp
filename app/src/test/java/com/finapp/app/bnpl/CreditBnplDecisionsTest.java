package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.bnpl.BnplCreditDecisions;
import com.finapp.bnpl.BnplPlan;
import com.finapp.bnpl.BnplProduct;
import com.finapp.credit.CreditConsentGate;
import com.finapp.credit.CreditDecisions;
import com.finapp.credit.CreditPartyStanding;
import com.finapp.credit.CreditPolicyStore;
import com.finapp.credit.CreditProduct;
import com.finapp.credit.CreditProfiles;
import com.finapp.credit.DecisionRequestStore;
import com.finapp.credit.DecisionRequests;
import com.finapp.credit.RequestOrigin;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * bnpl's credit port over credit's published door, hermetic (`P12-TSK-010`; {@code INV-CRD-04}, B5): every request
 * opened from the BNPL origin - credit's own origin judgement admits it and goes on to the applicant's standing, where
 * the customer door's origin would have been refused {@code ProductNotOffered} before anything is read - for credit's
 * product of the same name, exactly the purchase amount, pay-in-4 no term and pay-in-N its plan's months, no declared
 * figures. Credit's request against a real schema is {@code BnplApplicationDatabaseTest}'s.
 */
@DisplayName("the BNPL credit port opens from the BNPL origin, for exactly the purchase (P12-TSK-010)")
class CreditBnplDecisionsTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final Connection UOW = unsupported(Connection.class);
    private static final Actor CUSTOMER = new Actor("customer-1", ActorType.CUSTOMER);

    @Test
    @DisplayName("credit is asked for its product of the same name, exactly the purchase amount, the plan's term or none,"
            + " and no declared figure")
    void theTermsAreExactlyThePurchase() {
        Money gross = Money.ofMinorUnits(600_03, EUR);
        DecisionRequests.Terms four = CreditBnplDecisions.terms(
                BnplCreditDecisions.CreditRequest.of(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4, gross));
        assertThat(four.product()).isEqualTo(CreditProduct.BNPL_PAY_IN_4);
        assertThat(four.requested()).isEqualTo(gross);
        assertThat(four.termMonths()).as("a fixed plan sends no term").isEmpty();
        assertThat(four.declaredMonthlyIncome()).isEmpty();
        assertThat(four.declaredMonthlyExpenditure()).isEmpty();
        for (BnplPlan plan : BnplProduct.BNPL_PAY_IN_N.plans()) {
            DecisionRequests.Terms n = CreditBnplDecisions.terms(
                    BnplCreditDecisions.CreditRequest.of(BnplProduct.BNPL_PAY_IN_N, plan, gross));
            assertThat(n.product()).isEqualTo(CreditProduct.BNPL_PAY_IN_N);
            assertThat(n.termMonths()).as(plan.name()).contains(plan.instalments());
        }
        assertThatThrownBy(() -> BnplCreditDecisions.CreditRequest.of(BnplProduct.BNPL_PAY_IN_4,
                BnplPlan.PAY_IN_6_MONTHS, gross)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the request is opened from the BNPL origin: credit admits it and judges the applicant's standing next"
            + " - the customer door's origin would be ProductNotOffered before anything is read")
    void theRequestIsOpenedFromTheBnplOrigin() {
        assertThat(CreditBnplDecisions.ORIGIN).isEqualTo(RequestOrigin.BNPL_APPLICATION);
        List<String> asked = new ArrayList<>();
        CreditPartyStanding<Connection> standing = new CreditPartyStanding<>() {
            @Override
            public PartyFacts facts(Connection unitOfWork, UUID partyId) {
                throw new UnsupportedOperationException("not the submission's read");
            }

            @Override
            public boolean inGoodStanding(Connection unitOfWork, UUID partyId) {
                asked.add("standing");
                return false;
            }
        };
        @SuppressWarnings("unchecked")
        CreditDecisions<Connection> decisions = unsupported(CreditDecisions.class);
        @SuppressWarnings("unchecked")
        CreditProfiles<Connection> profiles = unsupported(CreditProfiles.class);
        @SuppressWarnings("unchecked")
        com.finapp.credit.CreditDecisionConsumptions<Connection> consumptions =
                unsupported(com.finapp.credit.CreditDecisionConsumptions.class);
        CreditBnplDecisions port = new CreditBnplDecisions(requests(standing), decisions, profiles, consumptions);
        for (BnplProduct product : BnplProduct.values()) {
            assertThatThrownBy(() -> port.open(UOW, UUID.randomUUID(), BnplCreditDecisions.CreditRequest.of(product,
                    product.plans().get(0), Money.ofMinorUnits(600_00, EUR)), CUSTOMER, CorrelationId.of("p12t10")))
                    .as(product.name()).isInstanceOf(DecisionRequests.ApplicantNotEligible.class);
        }
        assertThat(asked).as("credit reached the standing - the origin admitted").hasSize(BnplProduct.values().length);
        assertThatThrownBy(() -> requests(standing).submit(UOW, UUID.randomUUID(), CreditBnplDecisions.terms(
                        BnplCreditDecisions.CreditRequest.of(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4,
                                Money.ofMinorUnits(600_00, EUR))),
                RequestOrigin.CUSTOMER_DOOR, CUSTOMER, CorrelationId.of("p12t10")))
                .as("the control: the other origin is refused first").isInstanceOf(DecisionRequests.ProductNotOffered.class);
    }

    @Test
    @DisplayName("the acceptance's consumption asks credit for ConsumerRef(BNPL_AGREEMENT, agreement) and folds credit's"
            + " refusals into the port's by name; the exposure lock is credit's own profile lock (P12-TSK-013)")
    void theConsumptionIsTheAgreementsAndRefusalsFoldByName() {
        IdGenerator ids = new IdGenerator(Clock.systemUTC(), new SecureRandom());
        com.finapp.credit.CreditDecisionId decision = com.finapp.credit.CreditDecisionId.of(ids.next());
        com.finapp.bnpl.BnplAgreementId agreement = com.finapp.bnpl.BnplAgreementId.next(ids);
        java.util.List<Object> asked = new java.util.ArrayList<>();
        java.util.Map<String, com.finapp.credit.CreditDecisionConsumptions.Consumption> answers = new java.util.LinkedHashMap<>();
        answers.put("NOT_APPROVED", new com.finapp.credit.CreditDecisionConsumptions.Consumption.NotApproved(decision));
        answers.put("LAPSED", new com.finapp.credit.CreditDecisionConsumptions.Consumption.Lapsed(decision,
                java.time.Instant.parse("2026-10-17T10:00:00Z")));
        answers.put("ALREADY_CONSUMED", new com.finapp.credit.CreditDecisionConsumptions.Consumption.AlreadyConsumed(
                decision, new com.finapp.credit.ConsumerRef(com.finapp.credit.ConsumerKind.BNPL_AGREEMENT, ids.next())));
        answers.put("NOT_FOUND", new com.finapp.credit.CreditDecisionConsumptions.Consumption.NotFound(decision));
        for (java.util.Map.Entry<String, com.finapp.credit.CreditDecisionConsumptions.Consumption> answer
                : answers.entrySet()) {
            @SuppressWarnings("unchecked")
            com.finapp.credit.CreditDecisionConsumptions<Connection> consumptions =
                    (com.finapp.credit.CreditDecisionConsumptions<Connection>) Proxy.newProxyInstance(
                            CreditBnplDecisionsTest.class.getClassLoader(),
                            new Class<?>[] {com.finapp.credit.CreditDecisionConsumptions.class},
                            (proxy, method, args) -> {
                                asked.add(args[2]);
                                return answer.getValue();
                            });
            @SuppressWarnings("unchecked")
            CreditDecisions<Connection> decisions = unsupported(CreditDecisions.class);
            @SuppressWarnings("unchecked")
            CreditProfiles<Connection> profiles = (CreditProfiles<Connection>) Proxy.newProxyInstance(
                    CreditBnplDecisionsTest.class.getClassLoader(), new Class<?>[] {CreditProfiles.class},
                    (proxy, method, args) -> {
                        assertThat(method.getName()).isEqualTo("lockForDecision");
                        return java.util.Optional.empty();
                    });
            @SuppressWarnings("unchecked")
            CreditPartyStanding<Connection> unreached = unsupported(CreditPartyStanding.class);
            CreditBnplDecisions port = new CreditBnplDecisions(requests(unreached), decisions, profiles,
                    consumptions);
            assertThat(port.consume(UOW, decision.value(), agreement)).as(answer.getKey())
                    .isEqualTo(new BnplCreditDecisions.Consumption.Refused(
                            BnplCreditDecisions.Consumption.Refusal.valueOf(answer.getKey())));
            assertThat(port.lockExposure(UOW, UUID.randomUUID())).as("no profile").isFalse();
        }
        assertThat(asked).hasSize(4).allSatisfy(consumer -> assertThat(consumer).isEqualTo(
                new com.finapp.credit.ConsumerRef(com.finapp.credit.ConsumerKind.BNPL_AGREEMENT, agreement.value())));
    }

    // -----------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static DecisionRequests requests(CreditPartyStanding<Connection> standing) {
        IdGenerator ids = new IdGenerator(Clock.systemUTC(), new SecureRandom());
        return new DecisionRequests(unsupported(DecisionRequestStore.class), unsupported(CreditProfiles.class),
                unsupported(CreditPolicyStore.class), standing, unsupported(CreditConsentGate.class),
                unsupported(OutboxWriter.class), unsupported(AuditWriter.class), ids, Clock.systemUTC());
    }

    @SuppressWarnings("unchecked")
    private static <T> T unsupported(Class<T> type) {
        return (T) Proxy.newProxyInstance(CreditBnplDecisionsTest.class.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> {
                    if (method.getName().equals("toString")) {
                        return type.getSimpleName();
                    }
                    throw new UnsupportedOperationException(type.getSimpleName() + "." + method.getName()
                            + ": not reached before the origin and the standing");
                });
    }
}
