package com.finapp.app.accounting;

import com.finapp.accounting.AccountingPeriodStore;
import com.finapp.accounting.JdbcRegulatoryObligationStore;
import com.finapp.accounting.JdbcRegulatorySubmissionStore;
import com.finapp.accounting.RegulatoryAdapters;
import com.finapp.accounting.RegulatoryObligationStore;
import com.finapp.accounting.RegulatoryObligations;
import com.finapp.accounting.RegulatoryReportingProvider;
import com.finapp.accounting.RegulatorySubmissionStore;
import com.finapp.accounting.RegulatorySubmissions;
import com.finapp.accounting.ReportRunStore;
import com.finapp.accounting.reporting.ReportDefinitions;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the regulatory reporting framework (`P14-TSK-018`; ADR-0118; {@code RiskReportBeans}' shape) - its own configuration, so the module's parallel tasks each add theirs without editing one file: the stores; the port implemented by the simulated reference adapter, the answer the knob
 * {@code finapp.accounting.regulatory.simulation}; the machines; the meters; the drive that takes a sent return to the provider as the platform - the lease {@code finapp.accounting.regulatory.lease}, the interval {@code .inquiry-interval}, the leg's bound {@code .submissions-per-tick}, the horizon
 * {@code .answer-horizon} - which is also {@code AccountingCloseSchedule}'s inquiry leg; and the desks.
 *
 * <p><strong>For a real adapter:</strong> a second {@link RegulatoryReportingProvider} bean here, its timeout below the lease; a jurisdiction chosen by the owner; a form verified against that regulator's text; and a migration admitting a standing other than {@code REFERENCE}. Nothing else knows.
 */
@Configuration(proxyBeanMethods = false)
public class RegulatoryReportingBeans {

    @Bean
    RegulatoryObligationStore regulatoryObligationStore() {
        return new JdbcRegulatoryObligationStore();
    }

    @Bean
    RegulatorySubmissionStore regulatorySubmissionStore() {
        return new JdbcRegulatorySubmissionStore();
    }

    /** The simulated reference adapter - stateless, deterministic by the knob; correct on every instance. */
    @Bean
    RegulatoryReportingProvider regulatoryReportingProvider(@Value("${finapp.accounting.regulatory.simulation:ACKNOWLEDGE}") SimulatedRegulatoryReportingProvider.Behaviour behaviour) {
        return new SimulatedRegulatoryReportingProvider(behaviour);
    }

    @Bean
    RegulatoryAdapters regulatoryAdapters(ObjectProvider<RegulatoryReportingProvider> adapters) {
        List<RegulatoryReportingProvider> held = adapters.orderedStream().toList();
        return new RegulatoryAdapters(held);
    }

    @Bean
    RegulatoryObligations regulatoryObligations(
            RegulatoryObligationStore regulatoryObligationStore,
            RegulatoryAdapters regulatoryAdapters,
            ReportDefinitions reportDefinitions,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new RegulatoryObligations(regulatoryObligationStore, regulatoryAdapters, reportDefinitions, auditWriter, idGenerator, clock);
    }

    @Bean
    RegulatorySubmissions regulatorySubmissions(
            AccountingPeriodStore accountingPeriodStore,
            RegulatorySubmissionStore regulatorySubmissionStore,
            RegulatoryObligationStore regulatoryObligationStore,
            ReportRunStore reportRunStore,
            RegulatoryAdapters regulatoryAdapters,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock,
            @Value("${finapp.accounting.regulatory.answer-horizon:P1D}") Duration answerHorizon) {
        return new RegulatorySubmissions(accountingPeriodStore, regulatorySubmissionStore, regulatoryObligationStore, reportRunStore, regulatoryAdapters, auditWriter, outboxWriter, idGenerator, clock, answerHorizon);
    }

    /** {@code finapp.accounting.regulatory.submission{outcome}} - eager. */
    @Bean
    RegulatorySubmissionMetrics regulatorySubmissionMetrics(MeterRegistry meterRegistry) {
        return new RegulatorySubmissionMetrics(meterRegistry);
    }

    /** {@code finapp.accounting.regulatory.attention{kind}} - whether or not this instance runs the schedule: the fleet with its leg off is exactly the one that must say so. */
    @Bean
    RegulatoryAttentionMetrics regulatoryAttentionMetrics(RegulatorySubmissions regulatorySubmissions,
            @org.springframework.beans.factory.annotation.Qualifier(AccountingGaugeBeans.SNAPSHOTS) GlSnapshots snapshots, AccountingGaugeRefresher accountingGaugeRefresher, Clock clock, MeterRegistry meterRegistry) {
        return new RegulatoryAttentionMetrics(regulatorySubmissions, snapshots, accountingGaugeRefresher, clock, meterRegistry);
    }

    /**
     * The drive: the lease a claim moves the permit by (longer than an adapter's timeout), the interval an answer deciding nothing waits, and the inquiry leg's bound per tick.
     */
    @Bean
    RegulatorySubmissionDriver regulatorySubmissionDriver(
            RegulatorySubmissions regulatorySubmissions,
            AccountingTransactions accountingTransactions,
            RegulatorySubmissionMetrics regulatorySubmissionMetrics,
            CloseRunMetrics closeRunMetrics,
            AccountingFlowScope accountingFlowScope,
            @Value("${finapp.accounting.regulatory.lease:PT2M}") Duration lease,
            @Value("${finapp.accounting.regulatory.inquiry-interval:PT15M}") Duration inquiryInterval,
            @Value("${finapp.accounting.regulatory.submissions-per-tick:10}") int perTick) {
        return new RegulatorySubmissionDriver(regulatorySubmissions, accountingTransactions, regulatorySubmissionMetrics, closeRunMetrics, accountingFlowScope, lease, inquiryInterval, perTick);
    }

    @Bean
    RegulatorySubmissionDesk regulatorySubmissionDesk(
            RegulatorySubmissions regulatorySubmissions,
            RegulatorySubmissionDriver regulatorySubmissionDriver,
            IdempotentExecutor idempotentExecutor,
            AccountingTransactions accountingTransactions,
            AccountingFlowScope accountingFlowScope,
            RegulatorySubmissionMetrics regulatorySubmissionMetrics) {
        return new RegulatorySubmissionDesk(regulatorySubmissions, regulatorySubmissionDriver, idempotentExecutor, accountingTransactions, accountingFlowScope, regulatorySubmissionMetrics);
    }

    @Bean
    RegulatoryObligationDesk regulatoryObligationDesk(
            RegulatoryObligations regulatoryObligations,
            IdempotentExecutor idempotentExecutor,
            AccountingTransactions accountingTransactions,
            AccountingFlowScope accountingFlowScope) {
        return new RegulatoryObligationDesk(regulatoryObligations, idempotentExecutor, accountingTransactions, accountingFlowScope);
    }
}
