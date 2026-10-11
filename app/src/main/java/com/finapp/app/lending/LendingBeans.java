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
import com.finapp.lending.JdbcLoanTermsStore;
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
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
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
 * outbox, the lending officer's doors and the active-version gauge.
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
}
