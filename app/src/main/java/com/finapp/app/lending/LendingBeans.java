package com.finapp.app.lending;

import com.finapp.ledger.JdbcPurposeBalances;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PurposeBalances;
import com.finapp.ledger.SupportedCurrencies;
import com.finapp.lending.CapitalContributionStore;
import com.finapp.lending.CapitalExpectations;
import com.finapp.lending.CapitalRecognitions;
import com.finapp.lending.JdbcCapitalContributionStore;
import com.finapp.lending.LendingCapitalAdministration;
import com.finapp.lending.LendingCapitalHeadroom;
import com.finapp.lending.LendingCapitalProof;
import com.finapp.lending.JdbcLoanApplicationStore;
import com.finapp.lending.JdbcLoanOfferStore;
import com.finapp.lending.JdbcLoanTermsStore;
import com.finapp.lending.LoanApplicationStore;
import com.finapp.lending.LoanApplications;
import com.finapp.lending.LoanCreditDecisions;
import com.finapp.lending.LoanOfferStore;
import com.finapp.lending.LoanOffers;
import com.finapp.lending.LoanOrigination;
import com.finapp.lending.LoanPartyStanding;
import com.finapp.lending.LoanProductBounds;
import com.finapp.lending.LoanTermsAdministration;
import com.finapp.lending.LoanTermsStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.reconciliation.CapitalRecognitionReadings;
import com.finapp.reconciliation.ExpectationRegister;
import com.finapp.reconciliation.RuleSets;
import com.finapp.settlement.SettlementFileStore;
import com.finapp.settlement.SettlementSources;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wiring for lending capital (`P11-TSK-004`; ADR-0096 sections 4-6): lending's first beans - the contribution store and
 * its four-eyes administration, the two ports {@code app} implements over reconciliation (the expectation opener and
 * the recognition read), the headroom, the capital proof and its gauges, and the treasury's doors. And the loan terms
 * versions (`P11-TSK-005`; ADR-0092 sections 1-3): the store, the four-eyes administration publishing through the
 * outbox, the lending officer's doors and the active-version gauge. And the loan application (`P11-TSK-010`): its store,
 * the two ports {@code app} implements over credit (the decision request and the party's standing), the domain service,
 * the customer's doors and their meter. And the offer (`P11-TSK-011`): its store, the origination step and its leaderless
 * sweep with the sweeper gauge, the two credit-event hints, the customer's offer doors and their meter.
 */
@Configuration(proxyBeanMethods = false)
public class LendingBeans {

    /** Lending's unit of work - one transaction, default propagation. */
    @Bean
    LendingTransactions lendingTransactions(PlatformTransactionManager transactionManager, DataSource dataSource) {
        return new LendingTransactions(new TransactionTemplate(transactionManager), dataSource);
    }

    /** The ledger's purpose-wide and per-entry reads, for the headroom and the proof. */
    @Bean
    PurposeBalances<Connection> purposeBalances() {
        return new JdbcPurposeBalances();
    }

    @Bean
    CapitalContributionStore capitalContributionStore() {
        return new JdbcCapitalContributionStore();
    }

    /** The opener port over reconciliation's register, under the settlement bank's statement source. */
    @Bean
    CapitalExpectations capitalExpectations(
            ExpectationRegister expectationRegister,
            RuleSets ruleSets,
            SettlementSources settlementSources,
            SettlementFileStore<Connection> settlementFileStore) {
        return new ReconciliationCapitalExpectations(
                expectationRegister, ruleSets, settlementSources, settlementFileStore);
    }

    /** The recognition read over reconciliation's facts - recognition is never a lending state. */
    @Bean
    CapitalRecognitions capitalRecognitions(CapitalRecognitionReadings capitalRecognitionReadings) {
        return new ReconciliationCapitalRecognitions(capitalRecognitionReadings);
    }

    @Bean
    LendingCapitalHeadroom lendingCapitalHeadroom(
            LedgerAccountStore<Connection> ledgerAccountStore, PurposeBalances<Connection> purposeBalances) {
        return new LendingCapitalHeadroom(ledgerAccountStore, purposeBalances);
    }

    @Bean
    LendingCapitalProof lendingCapitalProof(
            LendingCapitalHeadroom lendingCapitalHeadroom,
            PurposeBalances<Connection> purposeBalances,
            CapitalRecognitions capitalRecognitions) {
        return new LendingCapitalProof(lendingCapitalHeadroom, purposeBalances, capitalRecognitions);
    }

    @Bean
    LendingCapitalAdministration lendingCapitalAdministration(
            CapitalContributionStore capitalContributionStore,
            CapitalExpectations capitalExpectations,
            LendingCapitalHeadroom lendingCapitalHeadroom,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new LendingCapitalAdministration(
                capitalContributionStore, capitalExpectations, lendingCapitalHeadroom, auditWriter, idGenerator, clock);
    }

    @Bean
    LendingCapitalDesk lendingCapitalDesk(
            LendingCapitalAdministration lendingCapitalAdministration,
            CapitalContributionStore capitalContributionStore,
            CapitalRecognitions capitalRecognitions,
            LendingCapitalProof lendingCapitalProof,
            IdempotentExecutor idempotentExecutor,
            LendingTransactions lendingTransactions,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new LendingCapitalDesk(
                lendingCapitalAdministration,
                capitalContributionStore,
                capitalRecognitions,
                lendingCapitalProof,
                idempotentExecutor,
                lendingTransactions,
                auditWriter,
                idGenerator,
                clock);
    }

    // ------------------------------------------------------------------ the terms versions (P11-TSK-005)

    @Bean
    LoanTermsStore loanTermsStore() {
        return new JdbcLoanTermsStore();
    }

    /** Credit's amount and term bounds, for the amortisation judgement at the terms door (`P11-TSK-006`). */
    @Bean
    LoanProductBounds loanProductBounds() {
        return new CreditLoanProductBounds();
    }

    @Bean
    LoanTermsAdministration loanTermsAdministration(
            LoanTermsStore loanTermsStore,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock,
            LoanProductBounds loanProductBounds) {
        return new LoanTermsAdministration(
                loanTermsStore, auditWriter, outboxWriter, idGenerator, clock, loanProductBounds);
    }

    @Bean
    LoanTermsDesk loanTermsDesk(
            LoanTermsAdministration loanTermsAdministration,
            IdempotentExecutor idempotentExecutor,
            LendingTransactions lendingTransactions) {
        return new LoanTermsDesk(loanTermsAdministration, idempotentExecutor, lendingTransactions);
    }

    /**
     * {@code finapp.lending.terms.active{product}}: each product's ACTIVE terms version, 0 for none - the product is not
     * offered, which production expects until the activation gate opens.
     */
    @Bean
    LoanTermsMetrics loanTermsMetrics(
            LoanTermsStore loanTermsStore, DataSource dataSource, Clock clock, MeterRegistry meterRegistry) {
        return new LoanTermsMetrics(loanTermsStore, dataSource::getConnection, clock, meterRegistry);
    }

    /**
     * The capital proof's gauges, registered eagerly at startup; the headroom floor per supported currency from
     * {@code finapp.lending.capital.headroom-floor.<CCY>} (minor units, 0 when unset).
     */
    @Bean
    LendingCapitalMetrics lendingCapitalMetrics(
            LendingCapitalProof lendingCapitalProof,
            DataSource dataSource,
            Environment environment,
            Clock clock,
            MeterRegistry meterRegistry) {
        Map<String, Long> floors = new LinkedHashMap<>();
        for (CurrencyCode currency : SupportedCurrencies.ALL) {
            long floor = environment.getProperty(
                    "finapp.lending.capital.headroom-floor." + currency.code(), Long.class, 0L);
            if (floor < 0) {
                throw new IllegalStateException("a lending capital headroom floor is never negative");
            }
            floors.put(currency.code(), floor);
        }
        return new LendingCapitalMetrics(lendingCapitalProof, dataSource::getConnection, floors, clock, meterRegistry);
    }

    // ------------------------------------------------------------------ the loan application (P11-TSK-010)

    @Bean
    LoanApplicationStore loanApplicationStore() {
        return new JdbcLoanApplicationStore();
    }

    /** Credit decisioning as lending reaches it - the port over credit's published API (A16). */
    @Bean
    LoanCreditDecisions loanCreditDecisions(
            com.finapp.credit.DecisionRequests decisionRequests,
            com.finapp.credit.CreditDecisions<Connection> creditDecisions) {
        return new CreditLoanDecisions(decisionRequests, creditDecisions);
    }

    /** The party's standing - credit's one rule, read authoritatively. */
    @Bean
    LoanPartyStanding loanPartyStanding(com.finapp.credit.CreditPartyStanding<Connection> creditPartyStanding) {
        return new CreditLoanPartyStanding(creditPartyStanding);
    }

    @Bean
    LoanApplications loanApplications(
            LoanApplicationStore loanApplicationStore,
            LoanTermsAdministration loanTermsAdministration,
            LoanPartyStanding loanPartyStanding,
            LoanCreditDecisions loanCreditDecisions,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new LoanApplications(loanApplicationStore, loanTermsAdministration, loanPartyStanding,
                loanCreditDecisions, outboxWriter, idGenerator, clock);
    }

    /** {@code finapp.lending.application{product, outcome}}, every series registered at startup. */
    @Bean
    LoanApplicationMetrics loanApplicationMetrics(MeterRegistry meterRegistry) {
        return new LoanApplicationMetrics(meterRegistry);
    }

    @Bean
    LoanApplicationDesk loanApplicationDesk(
            LoanApplications loanApplications,
            com.finapp.identity.IdentityStore<Connection> identityStore,
            com.finapp.identity.MfaEnrolmentStore<Connection> mfaEnrolmentStore,
            IdempotentExecutor idempotentExecutor,
            LendingTransactions lendingTransactions,
            LoanApplicationMetrics loanApplicationMetrics,
            LoanOffers loanOffers) {
        return new LoanApplicationDesk(loanApplications, identityStore, mfaEnrolmentStore, idempotentExecutor,
                lendingTransactions, loanApplicationMetrics, loanOffers);
    }

    // ------------------------------------------------------------------ the offer (P11-TSK-011)

    @Bean
    LoanOfferStore loanOfferStore() {
        return new JdbcLoanOfferStore();
    }

    /** The origination step (T-off) and the expiry - the system's acts on applications and offers. */
    @Bean
    LoanOrigination loanOrigination(
            LoanApplicationStore loanApplicationStore,
            LoanOfferStore loanOfferStore,
            LoanTermsAdministration loanTermsAdministration,
            LoanCreditDecisions loanCreditDecisions,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new LoanOrigination(loanApplicationStore, loanOfferStore, loanTermsAdministration, loanCreditDecisions,
                outboxWriter, idGenerator, clock);
    }

    /** The customer's offers - the read and the decline. */
    @Bean
    LoanOffers loanOffers(
            LoanOfferStore loanOfferStore,
            LoanTermsStore loanTermsStore,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new LoanOffers(loanOfferStore, loanTermsStore, outboxWriter, idGenerator, clock);
    }

    /** {@code finapp.lending.offer{product, outcome}}, every series registered at startup. */
    @Bean
    LoanOfferMetrics loanOfferMetrics(MeterRegistry meterRegistry) {
        return new LoanOfferMetrics(meterRegistry);
    }

    @Bean
    LoanOfferDesk loanOfferDesk(
            LoanOffers loanOffers,
            com.finapp.identity.IdentityStore<Connection> identityStore,
            com.finapp.identity.MfaEnrolmentStore<Connection> mfaEnrolmentStore,
            IdempotentExecutor idempotentExecutor,
            LendingTransactions lendingTransactions,
            LoanOfferMetrics loanOfferMetrics) {
        return new LoanOfferDesk(loanOffers, identityStore, mfaEnrolmentStore, idempotentExecutor, lendingTransactions,
                loanOfferMetrics);
    }

    /** The origination sweep - every instance, no lease, off in test contexts. */
    @Bean
    @ConditionalOnProperty(name = "finapp.lending.origination.sweeper.enabled", havingValue = "true", matchIfMissing = true)
    LoanOriginationSchedule loanOriginationSchedule(
            LoanOrigination loanOrigination,
            LendingTransactions lendingTransactions,
            LoanOfferMetrics loanOfferMetrics,
            @Value("${finapp.lending.origination.poll:PT5S}") Duration pollInterval,
            @Value("${finapp.lending.origination.batch:20}") int batch,
            @Value("${finapp.lending.origination.permit:PT30S}") Duration permit) {
        return new LoanOriginationSchedule(loanOrigination, lendingTransactions, loanOfferMetrics, pollInterval, batch,
                permit);
    }

    /** Whether this instance runs the origination sweep - eager either way (`P1-TSK-029`'s rule). */
    @Bean
    Gauge lendingOriginationSweeperEnabled(
            @Value("${finapp.lending.origination.sweeper.enabled:true}") boolean enabled, MeterRegistry meterRegistry) {
        return Gauge.builder("finapp.lending.origination.sweeper.enabled", () -> enabled ? 1 : 0)
                .description("Whether this instance runs the loan origination sweep")
                .register(meterRegistry);
    }

    /** {@code credit.CreditDecisionRecorded} as a hint - the application made due now, nothing else. */
    @Bean
    LoanOriginationHint loanOriginationDecisionHint(LoanOrigination loanOrigination) {
        return new LoanOriginationHint(loanOrigination, LoanOriginationHint.Kind.DECISION_RECORDED);
    }

    /** {@code credit.CreditDecisionRequestClosed} as a hint. */
    @Bean
    LoanOriginationHint loanOriginationClosureHint(LoanOrigination loanOrigination) {
        return new LoanOriginationHint(loanOrigination, LoanOriginationHint.Kind.REQUEST_CLOSED);
    }
}
