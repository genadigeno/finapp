package com.finapp.app.risk;

import com.finapp.kyc.CheckStore;
import com.finapp.kyc.KycCaseStore;
import com.finapp.party.PartyStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.telemetry.Spans;
import com.finapp.risk.JdbcRiskPolicyStore;
import com.finapp.risk.JdbcRiskSignalStore;
import com.finapp.risk.MonitoringHints;
import com.finapp.risk.RiskAssessmentStore;
import com.finapp.risk.RiskPolicies;
import com.finapp.risk.RiskPolicyAdministration;
import com.finapp.risk.RiskPolicyStore;
import com.finapp.risk.RiskSignalStore;
import com.finapp.risk.RiskSignals;
import com.finapp.risk.ScreeningClearances;
import com.finapp.risk.SignalReadings;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wiring for risk - the module's first beans (`P13-TSK-002`): its unit of work, the risk policy versions' store, the
 * four-eyes administration publishing through the outbox, the reads and the pin ({@link RiskPolicies}, which `-004`'s
 * evaluation consumes on the caller's connection), the risk officer's policy doors, the active-version gauge and the
 * flow scope's door leg (`-001`'s {@code RiskFlowScope}, given its spans here); and the signal intake (`P13-TSK-005`):
 * the signal store and T-sig, the readings, the hint port (`NONE` until `-019` implements it over its watermark),
 * {@code ScreeningClearances} over kyc, and one {@link RiskSignalIntake} per consumed event - every one a bean of
 * {@code InboxEventHandler}, which the eventing composition collects into the {@code finapp.risk} consumer group
 * ({@code InboxConsumers.byModule}). The origination hints of lending and bnpl are the precedent: one bean per source,
 * declared where a reader can count them against {@link RiskSignalIntake.Source} ({@code RiskBeansTest}).
 */
@Configuration(proxyBeanMethods = false)
public class RiskBeans {

    /** risk's unit of work - one transaction, default propagation. */
    @Bean
    RiskTransactions riskTransactions(PlatformTransactionManager transactionManager, DataSource dataSource) {
        return new SpringRiskTransactions(new TransactionTemplate(transactionManager), dataSource);
    }

    @Bean
    RiskPolicyStore riskPolicyStore() {
        return new JdbcRiskPolicyStore();
    }

    @Bean
    RiskPolicyAdministration riskPolicyAdministration(
            RiskPolicyStore riskPolicyStore,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new RiskPolicyAdministration(riskPolicyStore, auditWriter, outboxWriter, idGenerator, clock);
    }

    /** The reads and the pin - what the evaluation (`-004`) will take on the caller's connection. */
    @Bean
    RiskPolicies riskPolicies(RiskPolicyStore riskPolicyStore) {
        return new RiskPolicies(riskPolicyStore);
    }

    /** The door leg's spans, no attribute (`PHASE_13_PLAN.md` section 15). */
    @Bean
    RiskFlowScope riskFlowScope(Spans domainSpans) {
        return new RiskFlowScope(domainSpans);
    }

    @Bean
    RiskPolicyDesk riskPolicyDesk(
            RiskPolicyAdministration riskPolicyAdministration,
            RiskPolicies riskPolicies,
            IdempotentExecutor idempotentExecutor,
            RiskTransactions riskTransactions,
            RiskFlowScope riskFlowScope) {
        return new RiskPolicyDesk(riskPolicyAdministration, riskPolicies, idempotentExecutor, riskTransactions,
                riskFlowScope);
    }

    /**
     * {@code finapp.risk.policy.active{operation}}: each operation's ACTIVE policy version number, 0 for none - the
     * operation is not judged, which production expects until the activation gate opens (the runbook's section 9).
     */
    @Bean
    RiskPolicyMetrics riskPolicyMetrics(
            RiskPolicies riskPolicies, DataSource dataSource, Clock clock, MeterRegistry meterRegistry) {
        return new RiskPolicyMetrics(riskPolicies, dataSource::getConnection, clock, meterRegistry);
    }

    // ------------------------------------------------------------------ the signal intake (P13-TSK-005)

    @Bean
    RiskSignalStore riskSignalStore() {
        return new JdbcRiskSignalStore();
    }

    /**
     * T-sig - with the customer risk profile's permit pull (`P13-TSK-007`, {@code RiskProfileBeans}) and, since
     * `P13-TSK-025`, a default's alert raised in the same transaction ({@link com.finapp.risk.CollectionsCases} over
     * {@code RiskAlertBeans}' {@code AlertRaiser}, the borrower placed first through {@code RiskProfileBeans}' join):
     * folded into the borrower's {@code COLLECTIONS} case; a borrower that cannot be placed counted
     * {@code finapp.risk.collections.unplaced} and the delivery refused.
     */
    @Bean
    RiskSignals riskSignals(RiskSignalStore riskSignalStore, OutboxWriter<Connection> outboxWriter, IdGenerator ids,
            Clock clock, com.finapp.risk.ProfileHints profileHints, com.finapp.risk.AlertRaiser alertRaiser,
            com.finapp.risk.RiskSubjectParties riskSubjectParties, RiskCollectionsMetrics riskCollectionsMetrics) {
        return new RiskSignals(riskSignalStore, outboxWriter, ids, clock, profileHints,
                new com.finapp.risk.CollectionsCases(alertRaiser, riskSubjectParties, riskCollectionsMetrics));
    }

    /** {@code finapp.risk.collections.unplaced} - eager (`P13-TSK-025`). */
    @Bean
    RiskCollectionsMetrics riskCollectionsMetrics(MeterRegistry meterRegistry) {
        return new RiskCollectionsMetrics(meterRegistry);
    }

    @Bean
    SignalReadings signalReadings(RiskSignalStore riskSignalStore) {
        return new SignalReadings(riskSignalStore);
    }

    /**
     * The completions' hint pulls the ledger journal's monitoring permit to now (`P13-TSK-019` closing `-005`'s seam):
     * one conditional {@code UPDATE} on the handler's own connection, a row a sweep holds skipped, a permit already due
     * left; the movement itself is not read. The sweep reads the journal whatever the hints said.
     */
    @Bean
    MonitoringHints monitoringHints(com.finapp.risk.MonitoringWatermarkStore monitoringWatermarkStore) {
        return new com.finapp.risk.WatermarkMonitoringHints(monitoringWatermarkStore);
    }

    @Bean
    ScreeningClearances screeningClearances(PartyStore<Connection> partyStore, KycCaseStore<Connection> kycCaseStore,
            CheckStore<Connection> checkStore) {
        return new KycScreeningClearances(partyStore, kycCaseStore, checkStore);
    }

    // The twenty-one sources, one handler bean each - the shell filters a delivery to its handler by topic and type.

    @Bean
    RiskSignalIntake riskSignalCredentialChanged(RiskSignals s, MonitoringHints h, IdGenerator ids, RiskFlowScope f) {
        return intake(RiskSignalIntake.Source.CREDENTIAL_CHANGED, s, h, ids, f);
    }

    @Bean
    RiskSignalIntake riskSignalRecoveryInitiated(RiskSignals s, MonitoringHints h, IdGenerator ids, RiskFlowScope f) {
        return intake(RiskSignalIntake.Source.RECOVERY_INITIATED, s, h, ids, f);
    }

    @Bean
    RiskSignalIntake riskSignalRecoveryCompleted(RiskSignals s, MonitoringHints h, IdGenerator ids, RiskFlowScope f) {
        return intake(RiskSignalIntake.Source.RECOVERY_COMPLETED, s, h, ids, f);
    }

    @Bean
    RiskSignalIntake riskSignalIdentitySuspended(RiskSignals s, MonitoringHints h, IdGenerator ids, RiskFlowScope f) {
        return intake(RiskSignalIntake.Source.IDENTITY_SUSPENDED, s, h, ids, f);
    }

    @Bean
    RiskSignalIntake riskSignalAccountOpened(RiskSignals s, MonitoringHints h, IdGenerator ids, RiskFlowScope f) {
        return intake(RiskSignalIntake.Source.ACCOUNT_OPENED, s, h, ids, f);
    }

    @Bean
    RiskSignalIntake riskSignalBeneficiaryRegistered(RiskSignals s, MonitoringHints h, IdGenerator ids,
            RiskFlowScope f) {
        return intake(RiskSignalIntake.Source.BENEFICIARY_REGISTERED, s, h, ids, f);
    }

    @Bean
    RiskSignalIntake riskSignalBeneficiaryActivated(RiskSignals s, MonitoringHints h, IdGenerator ids,
            RiskFlowScope f) {
        return intake(RiskSignalIntake.Source.BENEFICIARY_ACTIVATED, s, h, ids, f);
    }

    @Bean
    RiskSignalIntake riskSignalBeneficiaryBlocked(RiskSignals s, MonitoringHints h, IdGenerator ids, RiskFlowScope f) {
        return intake(RiskSignalIntake.Source.BENEFICIARY_BLOCKED, s, h, ids, f);
    }

    @Bean
    RiskSignalIntake riskSignalMerchantOnboarded(RiskSignals s, MonitoringHints h, IdGenerator ids, RiskFlowScope f) {
        return intake(RiskSignalIntake.Source.MERCHANT_ONBOARDED, s, h, ids, f);
    }

    @Bean
    RiskSignalIntake riskSignalLoanDelinquencyChanged(RiskSignals s, MonitoringHints h, IdGenerator ids,
            RiskFlowScope f) {
        return intake(RiskSignalIntake.Source.LOAN_DELINQUENCY_CHANGED, s, h, ids, f);
    }

    @Bean
    RiskSignalIntake riskSignalLoanDefaulted(RiskSignals s, MonitoringHints h, IdGenerator ids, RiskFlowScope f) {
        return intake(RiskSignalIntake.Source.LOAN_DEFAULTED, s, h, ids, f);
    }

    @Bean
    RiskSignalIntake riskSignalBnplDelinquencyChanged(RiskSignals s, MonitoringHints h, IdGenerator ids,
            RiskFlowScope f) {
        return intake(RiskSignalIntake.Source.BNPL_DELINQUENCY_CHANGED, s, h, ids, f);
    }

    @Bean
    RiskSignalIntake riskSignalBnplDefaulted(RiskSignals s, MonitoringHints h, IdGenerator ids, RiskFlowScope f) {
        return intake(RiskSignalIntake.Source.BNPL_DEFAULTED, s, h, ids, f);
    }

    // P13-TSK-014: the abuse sources - the refund and the chargeback attributed from risk's own assessments.

    @Bean
    RiskSignalIntake riskSignalRefundCompleted(RiskSignals s, MonitoringHints h, IdGenerator ids, RiskFlowScope f,
            RiskAssessmentStore riskAssessmentStore) {
        return new RiskSignalIntake(RiskSignalIntake.Source.REFUND_COMPLETED, s, h, ids, f,
                RiskSignalIntake.Attributions.over(riskAssessmentStore));
    }

    @Bean
    RiskSignalIntake riskSignalChargebackReceived(RiskSignals s, MonitoringHints h, IdGenerator ids, RiskFlowScope f,
            RiskAssessmentStore riskAssessmentStore) {
        return new RiskSignalIntake(RiskSignalIntake.Source.CHARGEBACK_RECEIVED, s, h, ids, f,
                RiskSignalIntake.Attributions.over(riskAssessmentStore));
    }

    @Bean
    RiskSignalIntake riskSignalBnplApplicationSubmitted(RiskSignals s, MonitoringHints h, IdGenerator ids,
            RiskFlowScope f) {
        return intake(RiskSignalIntake.Source.BNPL_APPLICATION_SUBMITTED, s, h, ids, f);
    }

    @Bean
    RiskSignalIntake riskHintTransferCompleted(RiskSignals s, MonitoringHints h, IdGenerator ids, RiskFlowScope f) {
        return intake(RiskSignalIntake.Source.TRANSFER_COMPLETED, s, h, ids, f);
    }

    @Bean
    RiskSignalIntake riskHintPaymentCaptured(RiskSignals s, MonitoringHints h, IdGenerator ids, RiskFlowScope f) {
        return intake(RiskSignalIntake.Source.PAYMENT_CAPTURED, s, h, ids, f);
    }

    @Bean
    RiskSignalIntake riskHintPaymentExecuted(RiskSignals s, MonitoringHints h, IdGenerator ids, RiskFlowScope f) {
        return intake(RiskSignalIntake.Source.PAYMENT_EXECUTED, s, h, ids, f);
    }

    @Bean
    RiskSignalIntake riskHintWithdrawalCompleted(RiskSignals s, MonitoringHints h, IdGenerator ids, RiskFlowScope f) {
        return intake(RiskSignalIntake.Source.WITHDRAWAL_COMPLETED, s, h, ids, f);
    }

    @Bean
    RiskSignalIntake riskHintCrossBorderDelivered(RiskSignals s, MonitoringHints h, IdGenerator ids, RiskFlowScope f) {
        return intake(RiskSignalIntake.Source.CROSS_BORDER_DELIVERED, s, h, ids, f);
    }

    private static RiskSignalIntake intake(RiskSignalIntake.Source source, RiskSignals signals, MonitoringHints hints,
            IdGenerator ids, RiskFlowScope scope) {
        return new RiskSignalIntake(source, signals, hints, ids, scope);
    }
}
