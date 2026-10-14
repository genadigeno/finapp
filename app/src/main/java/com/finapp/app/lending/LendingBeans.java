package com.finapp.app.lending;

import com.finapp.ledger.JdbcPurposeBalances;
import com.finapp.ledger.PostingService;
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
import com.finapp.lending.JdbcLoanStore;
import com.finapp.lending.JdbcLoanDisbursementStore;
import com.finapp.lending.JdbcLoanPayoutStore;
import com.finapp.lending.LoanPayoutStore;
import com.finapp.lending.LoanPayoutSteps;
import com.finapp.lending.LoanPayouts;
import com.finapp.lending.LoanDisbursementStore;
import com.finapp.lending.LoanDisbursements;
import com.finapp.lending.LoanSchedules;
import com.finapp.lending.JdbcLoanServicingStore;
import com.finapp.lending.InstalmentBillingStore;
import com.finapp.lending.InstalmentBillings;
import com.finapp.lending.JdbcInstalmentBillingStore;
import com.finapp.lending.LoanServicing;
import com.finapp.lending.LoanServicingStore;
import com.finapp.lending.LoanWallets;
import com.finapp.lending.LoanAcceptances;
import com.finapp.lending.LoanAccounts;
import com.finapp.lending.LoanApplications;
import com.finapp.lending.LoanCreditDecisions;
import com.finapp.lending.LoanOfferStore;
import com.finapp.lending.LoanOffers;
import com.finapp.lending.LoanOrigination;
import com.finapp.lending.LoanPartyStanding;
import com.finapp.lending.LoanPayoutDestinations;
import com.finapp.lending.LoanStore;
import com.finapp.lending.Loans;
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
 * sweep with the sweeper gauge, the two credit-event hints, the customer's offer doors and their meter. And the acceptance
 * (`P11-TSK-012`): the loan store (which the headroom's committed term reads), the two ports {@code app} implements for it
 * (credit's profile lock and consumption on {@code LoanCreditDecisions}; the payout destination over the payment-method
 * registry), the six-account opener, T-acc, the loan reads, the doors and their meter. And the disbursement
 * (`P11-TSK-014`): its store, the wallet port {@code app} implements over accounts, T-dis, the schedule read, and the
 * leaderless disbursement sweep with its meters and sweeper gauge. And the external payout (`P11-TSK-015`): its store, the
 * port {@code app} implements over payments' withdrawal machinery, the payout steps and the leaderless payout sweep with
 * its meters and sweeper gauge. And the servicing (`P11-TSK-016`): its store, T-svc with its accrual step, and the
 * leaderless servicing sweep with its meters and sweeper gauge. And the repayment (`P11-TSK-018`): its store, the closure
 * T-rep and T-svc share, T-rep with the wallet's available balance, the doors and their meters.
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

    /** The loan accounts' store (`P11-TSK-012`) - the headroom's committed term reads it. */
    @Bean
    LoanStore loanStore() {
        return new JdbcLoanStore();
    }

    @Bean
    LendingCapitalHeadroom lendingCapitalHeadroom(
            LedgerAccountStore<Connection> ledgerAccountStore,
            PurposeBalances<Connection> purposeBalances,
            LoanStore loanStore) {
        return new LendingCapitalHeadroom(ledgerAccountStore, purposeBalances, loanStore);
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
            com.finapp.credit.CreditDecisions<Connection> creditDecisions,
            com.finapp.credit.CreditDecisionConsumptions<Connection> creditDecisionConsumptions,
            IdGenerator idGenerator) {
        return new CreditLoanDecisions(decisionRequests, creditDecisions,
                new com.finapp.credit.JdbcCreditProfiles(idGenerator), creditDecisionConsumptions);
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

    // ------------------------------------------------------------------ the acceptance and the loan account (P11-TSK-012)

    /** The borrower's own verified bank account, by opaque reference - the payment-method registry's read. */
    @Bean
    LoanPayoutDestinations loanPayoutDestinations(
            com.finapp.paymentmethods.PaymentMethodStore<Connection> paymentMethodStore) {
        return new PaymentMethodLoanPayoutDestinations(paymentMethodStore);
    }

    /** One loan's six ledger accounts, opened in the acceptance's transaction (`P11-TSK-003`). */
    @Bean
    LoanAccounts loanAccounts(LedgerAccountStore<Connection> ledgerAccountStore, IdGenerator idGenerator, Clock clock) {
        return new LoanAccounts(ledgerAccountStore, idGenerator, clock);
    }

    /** T-acc - the customer's acceptance under L0, L2 and (a loan) L6. */
    @Bean
    LoanAcceptances loanAcceptances(
            LoanOfferStore loanOfferStore,
            LoanApplicationStore loanApplicationStore,
            LoanStore loanStore,
            LoanCreditDecisions loanCreditDecisions,
            LoanPartyStanding loanPartyStanding,
            LoanPayoutDestinations loanPayoutDestinations,
            LendingCapitalHeadroom lendingCapitalHeadroom,
            LoanAccounts loanAccounts,
            OutboxWriter<Connection> outboxWriter,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new LoanAcceptances(loanOfferStore, loanApplicationStore, loanStore, loanCreditDecisions,
                loanPartyStanding, loanPayoutDestinations, lendingCapitalHeadroom, loanAccounts, outboxWriter, auditWriter,
                idGenerator, clock);
    }

    /** The customer's loan reads - balances derived from journal lines, the condition derived (P11-TSK-024). */
    @Bean
    Loans loans(LoanStore loanStore, LedgerAccountStore<Connection> ledgerAccountStore,
            PurposeBalances<Connection> purposeBalances, LoanPayoutStore loanPayoutStore,
            com.finapp.lending.LoanConditions loanConditions) {
        return new Loans(loanStore, ledgerAccountStore, purposeBalances, loanPayoutStore, loanConditions);
    }

    /** The condition history's store (P11-TSK-024, lending V020) - {@code loan_condition_event}, INSERT-only. */
    @Bean
    com.finapp.lending.LoanConditionStore loanConditionStore() {
        return new com.finapp.lending.JdbcLoanConditionStore();
    }

    /**
     * Delinquency and default (P11-TSK-024): T-svc's condition step, the draw's suspension (A25) and the reads' condition -
     * one derivation over the billed items.
     */
    @Bean
    com.finapp.lending.LoanConditions loanConditions(
            InstalmentBillingStore instalmentBillingStore,
            com.finapp.lending.LoanConditionStore loanConditionStore,
            LoanTermsStore loanTermsStore,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new com.finapp.lending.LoanConditions(instalmentBillingStore, loanConditionStore, loanTermsStore,
                outboxWriter, idGenerator, clock);
    }

    /**
     * {@code finapp.lending.delinquency{product, bucket}} and {@code finapp.lending.default{product}} (P11-TSK-024), every
     * series registered at startup.
     */
    @Bean
    LoanConditionMetrics loanConditionMetrics(com.finapp.lending.LoanConditionStore loanConditionStore,
            DataSource dataSource, Clock clock, MeterRegistry meterRegistry) {
        return new LoanConditionMetrics(loanConditionStore, dataSource::getConnection, clock, meterRegistry);
    }

    /** {@code finapp.lending.acceptance{product, outcome}}, every series registered at startup. */
    @Bean
    LoanAcceptanceMetrics loanAcceptanceMetrics(MeterRegistry meterRegistry) {
        return new LoanAcceptanceMetrics(meterRegistry);
    }

    @Bean
    LoanAccountDesk loanAccountDesk(
            LoanAcceptances loanAcceptances,
            Loans loans,
            com.finapp.identity.IdentityStore<Connection> identityStore,
            IdempotentExecutor idempotentExecutor,
            LendingTransactions lendingTransactions,
            LoanAcceptanceMetrics loanAcceptanceMetrics,
            LoanSchedules loanSchedules,
            com.finapp.lending.CreditLineStatements creditLineStatements) {
        return new LoanAccountDesk(loanAcceptances, loans, identityStore, idempotentExecutor, lendingTransactions,
                loanAcceptanceMetrics, loanSchedules, creditLineStatements);
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

    // ------------------------------------------------------------------ the disbursement (P11-TSK-014)

    /** The disbursement, the accrual start and the schedule versions - their store. */
    @Bean
    LoanDisbursementStore loanDisbursementStore() {
        return new JdbcLoanDisbursementStore();
    }

    /** The borrower's wallet - the port over accounts (opened through accounts' doors if absent). */
    @Bean
    LoanWallets loanWallets(
            com.finapp.party.PartyStore<Connection> partyStore,
            com.finapp.accounts.CustomerAccountStore<Connection> customerAccountStore,
            LedgerAccountStore<Connection> ledgerAccountStore,
            com.finapp.accounts.AccountOpening accountOpening) {
        return new AccountsLoanWallets(partyStore, customerAccountStore, ledgerAccountStore, accountOpening);
    }

    /** T-dis - the disbursement to the wallet under L0, L3 and L5, posting rule 1 through the ledger. */
    @Bean
    LoanDisbursements loanDisbursements(
            LoanStore loanStore,
            LoanDisbursementStore loanDisbursementStore,
            LoanTermsStore loanTermsStore,
            LoanCreditDecisions loanCreditDecisions,
            LoanPartyStanding loanPartyStanding,
            LoanWallets loanWallets,
            LedgerAccountStore<Connection> ledgerAccountStore,
            PostingService postingService,
            com.finapp.ledger.HoldService holdService,
            LoanPayoutStore loanPayoutStore,
            OutboxWriter<Connection> outboxWriter,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new LoanDisbursements(loanStore, loanDisbursementStore, loanTermsStore, loanCreditDecisions,
                loanPartyStanding, loanWallets, ledgerAccountStore, postingService, holdService, loanPayoutStore,
                outboxWriter, auditWriter, idGenerator, clock);
    }

    /** The customer's schedule read, with each instalment's billed and paid status (P11-TSK-017). */
    @Bean
    LoanSchedules loanSchedules(
            LoanDisbursementStore loanDisbursementStore, InstalmentBillingStore instalmentBillingStore) {
        return new LoanSchedules(loanDisbursementStore, instalmentBillingStore);
    }

    /** {@code finapp.lending.disbursement{path, outcome}} and the pending age, every series registered at startup. */
    @Bean
    LoanDisbursementMetrics loanDisbursementMetrics(
            LoanDisbursementStore loanDisbursementStore, DataSource dataSource, Clock clock, MeterRegistry meterRegistry) {
        return new LoanDisbursementMetrics(loanDisbursementStore, dataSource::getConnection, clock, meterRegistry);
    }

    /** The disbursement sweep - every instance, no lease, off in test contexts. */
    @Bean
    @ConditionalOnProperty(name = "finapp.lending.disbursement.sweeper.enabled", havingValue = "true", matchIfMissing = true)
    LoanDisbursementSchedule loanDisbursementSchedule(
            LoanDisbursements loanDisbursements,
            LendingTransactions lendingTransactions,
            LoanDisbursementMetrics loanDisbursementMetrics,
            @Value("${finapp.lending.disbursement.poll:PT5S}") Duration pollInterval,
            @Value("${finapp.lending.disbursement.batch:20}") int batch,
            @Value("${finapp.lending.disbursement.permit:PT1M}") Duration permit) {
        return new LoanDisbursementSchedule(loanDisbursements, lendingTransactions, loanDisbursementMetrics, pollInterval,
                batch, permit);
    }

    /** Whether this instance runs the disbursement sweep - eager either way (`P1-TSK-029`'s rule). */
    @Bean
    Gauge lendingDisbursementSweeperEnabled(
            @Value("${finapp.lending.disbursement.sweeper.enabled:true}") boolean enabled, MeterRegistry meterRegistry) {
        return Gauge.builder("finapp.lending.disbursement.sweeper.enabled", () -> enabled ? 1 : 0)
                .description("Whether this instance runs the loan disbursement sweep")
                .register(meterRegistry);
    }

    // ------------------------------------------------------------------ the external payout (P11-TSK-015)

    /** The payout's store. */
    @Bean
    LoanPayoutStore loanPayoutStore() {
        return new JdbcLoanPayoutStore();
    }

    /** The external leg - the port over payments' system withdrawal entry and its plain outcome read. */
    @Bean
    LoanPayouts loanPayouts(
            org.springframework.beans.factory.ObjectProvider<com.finapp.payments.Withdrawals> withdrawals,
            com.finapp.payments.PaymentParticipants<Connection> paymentParticipants,
            com.finapp.payments.WithdrawalStore<Connection> withdrawalStore,
            com.finapp.party.PartyStore<Connection> partyStore,
            LoanPayoutStore loanPayoutStore) {
        return new PaymentsLoanPayouts(withdrawals, paymentParticipants, withdrawalStore, partyStore, loanPayoutStore);
    }

    /** T-pyo and T-pyr - the payout dispatched through payments and concluded from its word. */
    @Bean
    LoanPayoutSteps loanPayoutSteps(
            LoanStore loanStore,
            LoanPayoutStore loanPayoutStore,
            LoanDisbursementStore loanDisbursementStore,
            LoanTermsStore loanTermsStore,
            LoanPayouts loanPayouts,
            com.finapp.ledger.HoldService holdService,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new LoanPayoutSteps(loanStore, loanPayoutStore, loanDisbursementStore, loanTermsStore, loanPayouts,
                holdService, outboxWriter, idGenerator, clock);
    }

    /** {@code finapp.lending.payout{outcome}} and the dispatched age, every series registered at startup. */
    @Bean
    LoanPayoutMetrics loanPayoutMetrics(
            LoanPayoutStore loanPayoutStore, DataSource dataSource, Clock clock, MeterRegistry meterRegistry) {
        return new LoanPayoutMetrics(loanPayoutStore, dataSource::getConnection, clock, meterRegistry);
    }

    /** The payout sweep - every instance, no lease, off in test contexts. */
    @Bean
    @ConditionalOnProperty(name = "finapp.lending.payout.sweeper.enabled", havingValue = "true", matchIfMissing = true)
    LoanPayoutSchedule loanPayoutSchedule(
            LoanPayoutSteps loanPayoutSteps,
            LendingTransactions lendingTransactions,
            LoanPayoutMetrics loanPayoutMetrics,
            @Value("${finapp.lending.payout.poll:PT5S}") Duration pollInterval,
            @Value("${finapp.lending.payout.batch:20}") int batch,
            @Value("${finapp.lending.payout.permit:PT1M}") Duration permit) {
        return new LoanPayoutSchedule(loanPayoutSteps, lendingTransactions, loanPayoutMetrics, pollInterval, batch,
                permit);
    }

    /** Whether this instance runs the payout sweep - eager either way (`P1-TSK-029`'s rule). */
    @Bean
    Gauge lendingPayoutSweeperEnabled(
            @Value("${finapp.lending.payout.sweeper.enabled:true}") boolean enabled, MeterRegistry meterRegistry) {
        return Gauge.builder("finapp.lending.payout.sweeper.enabled", () -> enabled ? 1 : 0)
                .description("Whether this instance runs the loan payout sweep")
                .register(meterRegistry);
    }

    // ------------------------------------------------------------------ the servicing (P11-TSK-016)

    /** The servicing permit, the accrual start and schedule reads, and the accrual rows - their store. */
    @Bean
    LoanServicingStore loanServicingStore() {
        return new JdbcLoanServicingStore();
    }

    /** The billed items, the credit-balance applications and the facts billing reads - their store (P11-TSK-017). */
    @Bean
    InstalmentBillingStore instalmentBillingStore() {
        return new JdbcInstalmentBillingStore();
    }

    /** T-svc's billing step (P11-TSK-017) - posting rules 3 and 6 through the ledger, under the step's L3. */
    @Bean
    InstalmentBillings instalmentBillings(
            InstalmentBillingStore instalmentBillingStore,
            LedgerAccountStore<Connection> ledgerAccountStore,
            PurposeBalances<Connection> purposeBalances,
            PostingService postingService,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new InstalmentBillings(instalmentBillingStore, ledgerAccountStore, purposeBalances, postingService,
                outboxWriter, idGenerator, clock);
    }

    /** The line's statement store (P11-TSK-022, lending V017). */
    @Bean
    com.finapp.lending.CreditLineStatementStore creditLineStatementStore() {
        return new com.finapp.lending.JdbcCreditLineStatementStore();
    }

    /**
     * T-svc's statement step for a credit line (P11-TSK-022) - posting rules 3 and 6 through the ledger, under the step's
     * L3 - and the customer's statements read.
     */
    @Bean
    com.finapp.lending.CreditLineStatements creditLineStatements(
            com.finapp.lending.CreditLineStatementStore creditLineStatementStore,
            InstalmentBillingStore instalmentBillingStore,
            LoanStore loanStore,
            LedgerAccountStore<Connection> ledgerAccountStore,
            PurposeBalances<Connection> purposeBalances,
            PostingService postingService,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new com.finapp.lending.CreditLineStatements(creditLineStatementStore, instalmentBillingStore, loanStore,
                ledgerAccountStore, purposeBalances, postingService, outboxWriter, idGenerator, clock);
    }

    /**
     * T-svc - the servicing step under L3: daily accrual (posting rule 2, P11-TSK-016), then billing - or a line's
     * statement (P11-TSK-022) - and the credit-balance application (rules 3 and 6, P11-TSK-017), then auto-collection
     * (rule 5, P11-TSK-019), then the closure of a settled loan (P11-TSK-018), then - on an account it did not close - the
     * condition (P11-TSK-024), then the late fee (posting rule 4, P11-TSK-025).
     */
    @Bean
    LoanServicing loanServicing(
            LoanStore loanStore,
            LoanServicingStore loanServicingStore,
            LoanTermsStore loanTermsStore,
            LedgerAccountStore<Connection> ledgerAccountStore,
            PurposeBalances<Connection> purposeBalances,
            PostingService postingService,
            IdGenerator idGenerator,
            InstalmentBillings instalmentBillings,
            com.finapp.lending.LoanClosures loanClosures,
            com.finapp.lending.LoanCollections loanCollections,
            com.finapp.lending.CreditLineStatements creditLineStatements,
            com.finapp.lending.LoanConditions loanConditions,
            com.finapp.lending.LateFees lateFees) {
        return new LoanServicing(loanStore, loanServicingStore, loanTermsStore, ledgerAccountStore, purposeBalances,
                postingService, idGenerator, instalmentBillings, loanClosures, loanCollections, creditLineStatements,
                loanConditions, lateFees);
    }

    /**
     * {@code finapp.lending.accrual{outcome}}, the accrual lag, {@code finapp.lending.billing{kind, outcome}} and
     * {@code finapp.lending.collection{outcome}} (P11-TSK-019), every series registered at startup; a collection's
     * repayment counted in {@code finapp.lending.repayment{kind=auto_collection}}.
     */
    @Bean
    LoanServicingMetrics loanServicingMetrics(com.finapp.lending.AccrualLags accrualLags, DataSource dataSource,
            Clock clock, MeterRegistry meterRegistry, RepaymentMetrics repaymentMetrics) {
        return new LoanServicingMetrics(accrualLags, dataSource::getConnection, clock, meterRegistry, repaymentMetrics);
    }

    /** The accrual lag's two arms - an instalment loan's every date, a line's dates with principal drawn (P11-TSK-023). */
    @Bean
    com.finapp.lending.AccrualLags accrualLags(LoanServicingStore loanServicingStore,
            LedgerAccountStore<Connection> ledgerAccountStore, PurposeBalances<Connection> purposeBalances) {
        return new com.finapp.lending.AccrualLags(loanServicingStore, ledgerAccountStore, purposeBalances);
    }

    /** {@code finapp.lending.loans{product, status}} (P11-TSK-023), every series registered at startup. */
    @Bean
    LoanCountMetrics loanCountMetrics(LoanStore loanStore, DataSource dataSource, Clock clock, MeterRegistry meterRegistry) {
        return new LoanCountMetrics(loanStore, dataSource::getConnection, clock, meterRegistry);
    }

    /** The servicing sweep - every instance, no lease, off in test contexts. */
    @Bean
    @ConditionalOnProperty(name = "finapp.lending.servicing.sweeper.enabled", havingValue = "true", matchIfMissing = true)
    LoanServicingSchedule loanServicingSchedule(
            LoanServicing loanServicing,
            LendingTransactions lendingTransactions,
            LoanServicingMetrics loanServicingMetrics,
            @Value("${finapp.lending.servicing.poll:PT1M}") Duration pollInterval,
            @Value("${finapp.lending.servicing.batch:50}") int batch,
            @Value("${finapp.lending.servicing.retry:PT15M}") Duration retryCadence) {
        return new LoanServicingSchedule(loanServicing, lendingTransactions, loanServicingMetrics, pollInterval, batch,
                retryCadence);
    }

    /** Whether this instance runs the servicing sweep - eager either way (`P1-TSK-029`'s rule). */
    @Bean
    Gauge lendingServicingSweeperEnabled(
            @Value("${finapp.lending.servicing.sweeper.enabled:true}") boolean enabled, MeterRegistry meterRegistry) {
        return Gauge.builder("finapp.lending.servicing.sweeper.enabled", () -> enabled ? 1 : 0)
                .description("Whether this instance runs the loan servicing sweep")
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

    // ------------------------------------------------------------------ the credit line draw (P11-TSK-021)

    /** The draws' store - {@code credit_line_draw}, INSERT-only. */
    @Bean
    com.finapp.lending.CreditLineDrawStore creditLineDrawStore() {
        return new com.finapp.lending.JdbcCreditLineDrawStore();
    }

    /** T-drw - the draw under L3 and L6, posting rule 1 to the wallet through the ledger. */
    @Bean
    com.finapp.lending.CreditLineDraws creditLineDraws(
            LoanStore loanStore,
            com.finapp.lending.CreditLineDrawStore creditLineDrawStore,
            LoanTermsStore loanTermsStore,
            com.finapp.lending.LoanConditions loanConditions,
            LoanPartyStanding loanPartyStanding,
            LendingCapitalHeadroom lendingCapitalHeadroom,
            LoanWallets loanWallets,
            LedgerAccountStore<Connection> ledgerAccountStore,
            PurposeBalances<Connection> purposeBalances,
            PostingService postingService,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock) {
        // The draws-suspended condition (A25): the derived condition read under L3 (P11-TSK-024 replaced -021's placeholder).
        return new com.finapp.lending.CreditLineDraws(loanStore, creditLineDrawStore, loanTermsStore, loanConditions,
                loanPartyStanding, lendingCapitalHeadroom, loanWallets, ledgerAccountStore, purposeBalances,
                postingService, outboxWriter, idGenerator, clock);
    }

    /** {@code finapp.lending.draw{outcome}}, every series registered at startup. */
    @Bean
    CreditLineDrawMetrics creditLineDrawMetrics(MeterRegistry meterRegistry) {
        return new CreditLineDrawMetrics(meterRegistry);
    }

    @Bean
    CreditLineDrawDesk creditLineDrawDesk(
            com.finapp.lending.CreditLineDraws creditLineDraws,
            com.finapp.identity.IdentityStore<Connection> identityStore,
            IdempotentExecutor idempotentExecutor,
            LendingTransactions lendingTransactions,
            CreditLineDrawMetrics creditLineDrawMetrics) {
        return new CreditLineDrawDesk(creditLineDraws, identityStore, idempotentExecutor, lendingTransactions,
                creditLineDrawMetrics);
    }

    // ------------------------------------------------------------------ the repayment and the closure (P11-TSK-018)

    /** The repayments' store - {@code repayment} and {@code repayment_allocation}, INSERT-only. */
    @Bean
    com.finapp.lending.RepaymentStore repaymentStore() {
        return new com.finapp.lending.JdbcRepaymentStore();
    }

    /** The closure of a settled instalment loan - the refund (L7 after L3), the edge, the event - for T-rep and T-svc. */
    @Bean
    com.finapp.lending.LoanClosures loanClosures(
            LoanStore loanStore,
            LoanServicingStore loanServicingStore,
            InstalmentBillingStore instalmentBillingStore,
            LoanWallets loanWallets,
            LedgerAccountStore<Connection> ledgerAccountStore,
            PurposeBalances<Connection> purposeBalances,
            PostingService postingService,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new com.finapp.lending.LoanClosures(loanStore, loanServicingStore, instalmentBillingStore, loanWallets,
                ledgerAccountStore, purposeBalances, postingService, outboxWriter, idGenerator, clock);
    }

    /** T-rep - the repayment under L3 then L7, posting rule 5 from the wallet through the ledger, and any closure. */
    @Bean
    com.finapp.lending.Repayments repayments(
            LoanStore loanStore,
            com.finapp.lending.RepaymentStore repaymentStore,
            InstalmentBillingStore instalmentBillingStore,
            LoanTermsStore loanTermsStore,
            LoanWallets loanWallets,
            LedgerAccountStore<Connection> ledgerAccountStore,
            PurposeBalances<Connection> purposeBalances,
            PostingService postingService,
            com.finapp.lending.LoanClosures loanClosures,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new com.finapp.lending.Repayments(loanStore, repaymentStore, instalmentBillingStore, loanTermsStore,
                loanWallets, new com.finapp.ledger.AvailableBalance<>(new com.finapp.ledger.JdbcBalanceDerivation(),
                        new com.finapp.ledger.JdbcHoldStore()),
                ledgerAccountStore, purposeBalances, postingService, loanClosures, outboxWriter, idGenerator, clock);
    }

    /** {@code finapp.lending.repayment{kind, outcome}} and {@code finapp.lending.allocation.anomaly}, eager. */
    @Bean
    RepaymentMetrics repaymentMetrics(MeterRegistry meterRegistry) {
        return new RepaymentMetrics(meterRegistry);
    }

    @Bean
    LoanRepaymentDesk loanRepaymentDesk(
            com.finapp.lending.Repayments repayments,
            com.finapp.identity.IdentityStore<Connection> identityStore,
            com.finapp.identity.MfaEnrolmentStore<Connection> mfaEnrolmentStore,
            IdempotentExecutor idempotentExecutor,
            LendingTransactions lendingTransactions,
            RepaymentMetrics repaymentMetrics,
            com.finapp.lending.RepaymentReversalStore repaymentReversalStore) {
        return new LoanRepaymentDesk(repayments, identityStore, mfaEnrolmentStore, idempotentExecutor, lendingTransactions,
                repaymentMetrics, repaymentReversalStore);
    }

    // ------------------------------------------------------------------ the partial prepayment (P11-TSK-028)

    /**
     * T-ppy - the prepayment under L3: the servicing step's own catch-up, the due state, L7, rule 5 through the one posting
     * half, agreement and schedule version n+1.
     */
    @Bean
    com.finapp.lending.Prepayments prepayments(
            LoanStore loanStore,
            com.finapp.lending.RepaymentStore repaymentStore,
            LoanTermsStore loanTermsStore,
            LoanOfferStore loanOfferStore,
            LoanServicing loanServicing,
            LoanServicingStore loanServicingStore,
            InstalmentBillings instalmentBillings,
            InstalmentBillingStore instalmentBillingStore,
            LoanDisbursementStore loanDisbursementStore,
            LoanWallets loanWallets,
            LedgerAccountStore<Connection> ledgerAccountStore,
            PurposeBalances<Connection> purposeBalances,
            com.finapp.lending.Repayments repayments,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new com.finapp.lending.Prepayments(loanStore, repaymentStore, loanTermsStore, loanOfferStore, loanServicing,
                loanServicingStore, instalmentBillings, instalmentBillingStore, loanDisbursementStore, loanWallets,
                new com.finapp.ledger.AvailableBalance<>(new com.finapp.ledger.JdbcBalanceDerivation(),
                        new com.finapp.ledger.JdbcHoldStore()),
                ledgerAccountStore, purposeBalances, repayments, outboxWriter, idGenerator, clock);
    }

    @Bean
    LoanPrepaymentDesk loanPrepaymentDesk(
            com.finapp.lending.Prepayments prepayments,
            com.finapp.identity.IdentityStore<Connection> identityStore,
            IdempotentExecutor idempotentExecutor,
            LendingTransactions lendingTransactions,
            RepaymentMetrics repaymentMetrics) {
        return new LoanPrepaymentDesk(prepayments, identityStore, idempotentExecutor, lendingTransactions,
                repaymentMetrics);
    }

    // ------------------------------------------------------------------ the line's closure (P11-TSK-023)

    /** The closure requests' store - {@code line_closure_request}, INSERT-only, one per line. */
    @Bean
    com.finapp.lending.LineClosureStore lineClosureStore() {
        return new com.finapp.lending.JdbcLineClosureStore();
    }

    /** T-clo - a line's closure request under L3 (L7 for a refund at once): CLOSING, or CLOSED when nothing is owed. */
    @Bean
    com.finapp.lending.LineClosures lineClosures(
            LoanStore loanStore,
            com.finapp.lending.LineClosureStore lineClosureStore,
            LoanTermsStore loanTermsStore,
            com.finapp.lending.LoanClosures loanClosures,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new com.finapp.lending.LineClosures(loanStore, lineClosureStore, loanTermsStore, loanClosures, outboxWriter,
                idGenerator, clock);
    }

    @Bean
    LineClosureDesk lineClosureDesk(
            com.finapp.lending.LineClosures lineClosures,
            com.finapp.identity.IdentityStore<Connection> identityStore,
            IdempotentExecutor idempotentExecutor,
            LendingTransactions lendingTransactions) {
        return new LineClosureDesk(lineClosures, identityStore, idempotentExecutor, lendingTransactions);
    }

    // ------------------------------------------------------------------ the payoff (P11-TSK-026)

    /** The payoffs' store - {@code payoff_quote} and {@code payoff_execution}, INSERT-only. */
    @Bean
    com.finapp.lending.PayoffStore payoffStore() {
        return new com.finapp.lending.JdbcPayoffStore();
    }

    /**
     * The payoff - the quote under L3 (its catch-up accrual), and T-pay: L3, the execution, rules 2, 3 (and 6), the
     * recomputation, L7, rule 5 and the closure through the one {@code LoanClosures}.
     */
    @Bean
    com.finapp.lending.Payoffs payoffs(
            LoanStore loanStore,
            com.finapp.lending.PayoffStore payoffStore,
            LoanTermsStore loanTermsStore,
            LoanServicing loanServicing,
            LoanServicingStore loanServicingStore,
            InstalmentBillings instalmentBillings,
            com.finapp.lending.CreditLineStatements creditLineStatements,
            InstalmentBillingStore instalmentBillingStore,
            com.finapp.lending.RepaymentStore repaymentStore,
            LoanWallets loanWallets,
            LedgerAccountStore<Connection> ledgerAccountStore,
            PurposeBalances<Connection> purposeBalances,
            com.finapp.lending.Repayments repayments,
            com.finapp.lending.LoanClosures loanClosures,
            com.finapp.lending.LineClosures lineClosures,
            IdGenerator idGenerator) {
        return new com.finapp.lending.Payoffs(loanStore, payoffStore, loanTermsStore, loanServicing, loanServicingStore,
                instalmentBillings, creditLineStatements, instalmentBillingStore, repaymentStore, loanWallets,
                new com.finapp.ledger.AvailableBalance<>(new com.finapp.ledger.JdbcBalanceDerivation(),
                        new com.finapp.ledger.JdbcHoldStore()),
                ledgerAccountStore, purposeBalances, repayments, loanClosures, lineClosures, idGenerator);
    }

    /** {@code finapp.lending.payoff{outcome}}, every series registered at startup. */
    @Bean
    PayoffMetrics payoffMetrics(MeterRegistry meterRegistry) {
        return new PayoffMetrics(meterRegistry);
    }

    @Bean
    LoanPayoffDesk loanPayoffDesk(
            com.finapp.lending.Payoffs payoffs,
            com.finapp.identity.IdentityStore<Connection> identityStore,
            com.finapp.identity.MfaEnrolmentStore<Connection> mfaEnrolmentStore,
            IdempotentExecutor idempotentExecutor,
            LendingTransactions lendingTransactions,
            PayoffMetrics payoffMetrics,
            RepaymentMetrics repaymentMetrics) {
        return new LoanPayoffDesk(payoffs, identityStore, mfaEnrolmentStore, idempotentExecutor, lendingTransactions,
                payoffMetrics, repaymentMetrics);
    }

    // ------------------------------------------------------------------ the repayment reversal (P11-TSK-020)

    /** The reversals' store - {@code repayment_reversal} (its decision columns, once) and the INSERT-only negating rows. */
    @Bean
    com.finapp.lending.RepaymentReversalStore repaymentReversalStore() {
        return new com.finapp.lending.JdbcRepaymentReversalStore();
    }

    /**
     * T-rev - the four-eyes reversal under L0, L3, L5, L6 and L7, posting rule 10 through the ledger's ReversalService;
     * whose party an operator is read through the identity store (credit's {@code IdentityActingParty}, one read for both).
     */
    @Bean
    com.finapp.lending.RepaymentReversals repaymentReversals(
            LoanStore loanStore,
            com.finapp.lending.RepaymentStore repaymentStore,
            com.finapp.lending.RepaymentReversalStore repaymentReversalStore,
            InstalmentBillingStore instalmentBillingStore,
            LoanTermsStore loanTermsStore,
            LoanCreditDecisions loanCreditDecisions,
            LendingCapitalHeadroom lendingCapitalHeadroom,
            LedgerAccountStore<Connection> ledgerAccountStore,
            PurposeBalances<Connection> purposeBalances,
            com.finapp.ledger.JournalEntryStore<Connection> journalEntryStore,
            com.finapp.ledger.ReversalService reversalService,
            com.finapp.identity.IdentityStore<Connection> identityStore,
            OutboxWriter<Connection> outboxWriter,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new com.finapp.lending.RepaymentReversals(loanStore, repaymentStore, repaymentReversalStore,
                instalmentBillingStore, loanTermsStore, loanCreditDecisions, lendingCapitalHeadroom, ledgerAccountStore,
                purposeBalances, journalEntryStore, reversalService,
                new com.finapp.app.credit.IdentityActingParty(identityStore), outboxWriter, auditWriter, idGenerator, clock);
    }

    /** {@code finapp.lending.reversal{outcome}}, every series registered at startup. */
    @Bean
    RepaymentReversalMetrics repaymentReversalMetrics(MeterRegistry meterRegistry) {
        return new RepaymentReversalMetrics(meterRegistry);
    }

    @Bean
    LoanServicingDesk loanServicingDesk(
            com.finapp.lending.RepaymentReversals repaymentReversals,
            com.finapp.lending.RepaymentReversalStore repaymentReversalStore,
            Loans loans,
            LoanStore loanStore,
            com.finapp.lending.RepaymentStore repaymentStore,
            IdempotentExecutor idempotentExecutor,
            LendingTransactions lendingTransactions,
            RepaymentReversalMetrics repaymentReversalMetrics,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock,
            com.finapp.lending.FeeAssessmentStore feeAssessmentStore,
            com.finapp.lending.WaiverStore waiverStore,
            com.finapp.lending.LoanAmendmentStore loanAmendmentStore) {
        return new LoanServicingDesk(repaymentReversals, repaymentReversalStore, loans, loanStore, repaymentStore,
                idempotentExecutor, lendingTransactions, repaymentReversalMetrics, auditWriter, idGenerator, clock,
                feeAssessmentStore, waiverStore, loanAmendmentStore);
    }

    /**
     * T-svc's collection step (P11-TSK-019): what each billed item has due, from the borrower's wallet (L7 after the step's
     * L3), through the repayment's one posting half - when the pinned terms collect automatically.
     */
    @Bean
    com.finapp.lending.LoanCollections loanCollections(
            com.finapp.lending.RepaymentStore repaymentStore,
            com.finapp.lending.Repayments repayments,
            InstalmentBillingStore instalmentBillingStore,
            LoanWallets loanWallets,
            LedgerAccountStore<Connection> ledgerAccountStore,
            PurposeBalances<Connection> purposeBalances,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new com.finapp.lending.LoanCollections(repaymentStore, repayments, instalmentBillingStore, loanWallets,
                new com.finapp.ledger.AvailableBalance<>(new com.finapp.ledger.JdbcBalanceDerivation(),
                        new com.finapp.ledger.JdbcHoldStore()),
                ledgerAccountStore, purposeBalances, outboxWriter, idGenerator, clock);
    }

    // ------------------------------------------------------------------ late fees and waivers (P11-TSK-025)

    /** The fee assessments' store - {@code fee_assessment}, INSERT-only (lending V021). */
    @Bean
    com.finapp.lending.FeeAssessmentStore feeAssessmentStore() {
        return new com.finapp.lending.JdbcFeeAssessmentStore();
    }

    /**
     * T-svc's late-fee step (P11-TSK-025) - posting rule 4 through the ledger, under the step's L3, after the condition
     * step: once per billed item at the pinned late-fee day, capped per account.
     */
    @Bean
    com.finapp.lending.LateFees lateFees(
            com.finapp.lending.FeeAssessmentStore feeAssessmentStore,
            InstalmentBillingStore instalmentBillingStore,
            com.finapp.lending.CreditLineStatementStore creditLineStatementStore,
            LedgerAccountStore<Connection> ledgerAccountStore,
            PostingService postingService,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new com.finapp.lending.LateFees(feeAssessmentStore, instalmentBillingStore, creditLineStatementStore,
                ledgerAccountStore, postingService, outboxWriter, idGenerator, clock);
    }

    /** The waivers' store - {@code waiver} and {@code waiver_decision}, both INSERT-only (lending V021). */
    @Bean
    com.finapp.lending.WaiverStore waiverStore() {
        return new com.finapp.lending.JdbcWaiverStore();
    }

    /**
     * T-wvr - the four-eyes fee and interest waiver under L3 (and L7 before its posting), posting rule 8 through the
     * ledger's ReversalService, 8a and 9 through its PostingService; the closure of a settled account through the one
     * LoanClosures; whose party an operator is read through the identity store (credit's {@code IdentityActingParty}).
     */
    @Bean
    com.finapp.lending.Waivers waivers(
            LoanStore loanStore,
            com.finapp.lending.WaiverStore waiverStore,
            com.finapp.lending.FeeAssessmentStore feeAssessmentStore,
            InstalmentBillingStore instalmentBillingStore,
            LoanTermsStore loanTermsStore,
            LoanWallets loanWallets,
            LedgerAccountStore<Connection> ledgerAccountStore,
            PurposeBalances<Connection> purposeBalances,
            PostingService postingService,
            com.finapp.ledger.ReversalService reversalService,
            com.finapp.lending.LoanClosures loanClosures,
            com.finapp.identity.IdentityStore<Connection> identityStore,
            OutboxWriter<Connection> outboxWriter,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new com.finapp.lending.Waivers(loanStore, waiverStore, feeAssessmentStore, instalmentBillingStore,
                loanTermsStore, loanWallets, ledgerAccountStore, purposeBalances, postingService, reversalService,
                loanClosures, new com.finapp.app.credit.IdentityActingParty(identityStore), outboxWriter, auditWriter,
                idGenerator, clock);
    }

    /** {@code finapp.lending.waiver{kind, outcome}}, every series registered at startup. */
    @Bean
    LoanWaiverMetrics loanWaiverMetrics(MeterRegistry meterRegistry) {
        return new LoanWaiverMetrics(meterRegistry);
    }

    /** The waiver doors' boundary half - keyed per principal under the servicing doors' scope. */
    @Bean
    LoanWaiverDesk loanWaiverDesk(
            com.finapp.lending.Waivers waivers,
            IdempotentExecutor idempotentExecutor,
            LendingTransactions lendingTransactions,
            LoanWaiverMetrics loanWaiverMetrics) {
        return new LoanWaiverDesk(waivers, idempotentExecutor, lendingTransactions, loanWaiverMetrics);
    }

    // ------------------------------------------------------------------ restructuring by agreement amendment (P11-TSK-027)

    /** The amendments' store - {@code loan_amendment}, its decision and its re-scheduled arrears, all INSERT-only (V023). */
    @Bean
    com.finapp.lending.LoanAmendmentStore loanAmendmentStore() {
        return new com.finapp.lending.JdbcLoanAmendmentStore();
    }

    /**
     * T-amd and the customer's acceptance - the four-eyes amendment under L3; the acceptance's catch-up through the servicing
     * step's own accrual and billing, rule 13 through the ledger's PostingService, schedule n+1 by the pinned engine; whose
     * party an operator is read through the identity store (credit's {@code IdentityActingParty}).
     */
    @Bean
    com.finapp.lending.LoanAmendments loanAmendments(
            LoanStore loanStore,
            com.finapp.lending.LoanAmendmentStore loanAmendmentStore,
            LoanOfferStore loanOfferStore,
            LoanTermsStore loanTermsStore,
            LoanProductBounds loanProductBounds,
            LoanServicing loanServicing,
            LoanServicingStore loanServicingStore,
            InstalmentBillings instalmentBillings,
            InstalmentBillingStore instalmentBillingStore,
            LoanDisbursementStore loanDisbursementStore,
            LedgerAccountStore<Connection> ledgerAccountStore,
            PurposeBalances<Connection> purposeBalances,
            PostingService postingService,
            com.finapp.identity.IdentityStore<Connection> identityStore,
            OutboxWriter<Connection> outboxWriter,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new com.finapp.lending.LoanAmendments(loanStore, loanAmendmentStore, loanOfferStore, loanTermsStore,
                loanProductBounds, loanServicing, loanServicingStore, instalmentBillings, instalmentBillingStore,
                loanDisbursementStore, ledgerAccountStore, purposeBalances, postingService,
                new com.finapp.app.credit.IdentityActingParty(identityStore), outboxWriter, auditWriter, idGenerator, clock);
    }

    /** {@code finapp.lending.amendment{outcome}}, every series registered at startup. */
    @Bean
    LoanAmendmentMetrics loanAmendmentMetrics(MeterRegistry meterRegistry) {
        return new LoanAmendmentMetrics(meterRegistry);
    }

    /** The restructuring doors' boundary half - the operator's under the servicing scope, the customer's acceptance keyed. */
    @Bean
    LoanAmendmentDesk loanAmendmentDesk(
            com.finapp.lending.LoanAmendments loanAmendments,
            com.finapp.identity.IdentityStore<Connection> identityStore,
            IdempotentExecutor idempotentExecutor,
            LendingTransactions lendingTransactions,
            LoanAmendmentMetrics loanAmendmentMetrics) {
        return new LoanAmendmentDesk(loanAmendments, identityStore, idempotentExecutor, lendingTransactions,
                loanAmendmentMetrics);
    }
}
