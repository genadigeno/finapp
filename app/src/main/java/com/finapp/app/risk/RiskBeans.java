package com.finapp.app.risk;

import com.finapp.kyc.CheckStore;
import com.finapp.kyc.KycCaseStore;
import com.finapp.party.PartyStore;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.telemetry.Spans;
import com.finapp.risk.JdbcRiskSignalStore;
import com.finapp.risk.MonitoringHints;
import com.finapp.risk.RiskSignalStore;
import com.finapp.risk.RiskSignals;
import com.finapp.risk.ScreeningClearances;
import com.finapp.risk.SignalReadings;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Risk's composition (`P13-TSK-005`): the signal store and T-sig, the readings, the hint port (`NONE` until `-019`
 * implements it over its watermark), {@code ScreeningClearances} over kyc, the flow scope, and one
 * {@link RiskSignalIntake} per consumed event - every one a bean of {@code InboxEventHandler}, which the eventing
 * composition collects into the {@code finapp.risk} consumer group ({@code InboxConsumers.byModule}). The
 * origination hints of lending and bnpl are the precedent: one bean per source, declared where a reader can count
 * them against {@link RiskSignalIntake.Source} ({@code RiskBeansTest}).
 */
@Configuration
class RiskBeans {

    @Bean
    RiskSignalStore riskSignalStore() {
        return new JdbcRiskSignalStore();
    }

    @Bean
    RiskSignals riskSignals(RiskSignalStore riskSignalStore, OutboxWriter<Connection> outboxWriter, IdGenerator ids,
            Clock clock) {
        return new RiskSignals(riskSignalStore, outboxWriter, ids, clock);
    }

    @Bean
    SignalReadings signalReadings(RiskSignalStore riskSignalStore) {
        return new SignalReadings(riskSignalStore);
    }

    /** The completions' hint falls on the floor until `-019` pulls the monitoring permit with it. */
    @Bean
    MonitoringHints monitoringHints() {
        return MonitoringHints.NONE;
    }

    @Bean
    ScreeningClearances screeningClearances(PartyStore<Connection> partyStore, KycCaseStore<Connection> kycCaseStore,
            CheckStore<Connection> checkStore) {
        return new KycScreeningClearances(partyStore, kycCaseStore, checkStore);
    }

    @Bean
    RiskFlowScope riskFlowScope(Spans spans) {
        return new RiskFlowScope(spans);
    }

    // The eighteen sources, one handler bean each - the shell filters a delivery to its handler by topic and type.

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
