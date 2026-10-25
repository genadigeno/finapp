package com.finapp.app.risk;

import com.finapp.identity.IdentityStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.risk.Cases;
import com.finapp.risk.JdbcRegulatoryReportStore;
import com.finapp.risk.MonitoredParties;
import com.finapp.risk.RegulatoryFilingProvider;
import com.finapp.risk.RegulatoryReportStore;
import com.finapp.risk.RegulatoryReports;
import com.finapp.risk.RiskAlertStore;
import com.finapp.risk.RiskCaseStore;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the regulatory report (`P13-TSK-021`; {@code PHASE_13_PLAN.md} sections 7.1 T-rep, 7.5, 9, 12.9; ADR-0111
 * decisions 5-7; {@code DESIGN-021}): the report's store; the filing port implemented by the simulated FIU
 * ({@link SimulatedFilingProvider} - the authority's answer the knob {@code finapp.risk.filing.simulation}, the platform's
 * reference with it {@code finapp.risk.filing.reporting-entity}); the machine ({@link RegulatoryReports}, over
 * {@code RiskCaseBeans}' {@link Cases} and stores, {@code RiskMonitoringBeans}' {@link MonitoredParties} and identity's
 * acting party); the meters; the drive that takes a filed report to the authority as the platform
 * ({@link RegulatoryFilingDriver} - the lease {@code finapp.risk.filing.lease}, the inquiry interval
 * {@code finapp.risk.filing.inquiry-interval}, the leg's bound {@code finapp.risk.filing.reports-per-tick}), which is
 * also {@code RiskActionSchedule}'s report leg ({@code RiskActionBeans}); and the AML desk.
 */
@Configuration(proxyBeanMethods = false)
public class RiskReportBeans {

    @Bean
    RegulatoryReportStore regulatoryReportStore() {
        return new JdbcRegulatoryReportStore();
    }

    /** The simulated FIU - stateless, deterministic by the knob; correct on every instance. */
    @Bean
    RegulatoryFilingProvider regulatoryFilingProvider(
            @Value("${finapp.risk.filing.simulation:ACKNOWLEDGE}") SimulatedFilingProvider.Behaviour behaviour,
            @Value("${finapp.risk.filing.reporting-entity:FINAPP-EU-REF-0001}") String reportingEntity) {
        return new SimulatedFilingProvider(behaviour, reportingEntity);
    }

    /** {@code finapp.risk.report{status}}, {@code .report.filing{outcome}}, the claim failures - eager. */
    @Bean
    RegulatoryReportMetrics regulatoryReportMetrics(MeterRegistry meterRegistry) {
        return new RegulatoryReportMetrics(meterRegistry);
    }

    @Bean
    RegulatoryReports regulatoryReports(
            RegulatoryReportStore regulatoryReportStore,
            Cases cases,
            RiskCaseStore riskCaseStore,
            RiskAlertStore riskAlertStore,
            MonitoredParties monitoredParties,
            IdentityStore<Connection> identityStore,
            RegulatoryFilingProvider regulatoryFilingProvider,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock,
            RegulatoryReportMetrics regulatoryReportMetrics) {
        return new RegulatoryReports(regulatoryReportStore, cases, riskCaseStore, riskAlertStore, monitoredParties,
                new com.finapp.app.credit.IdentityActingParty(identityStore), regulatoryFilingProvider, auditWriter,
                outboxWriter, idGenerator, clock, regulatoryReportMetrics);
    }

    /**
     * The filing's drive: the lease a claim moves the permit by (longer than the provider's timeout), the interval an
     * unknown standing waits, and the inquiry leg's bound per tick.
     */
    @Bean
    RegulatoryFilingDriver regulatoryFilingDriver(
            RegulatoryReports regulatoryReports,
            RiskTransactions riskTransactions,
            RegulatoryReportMetrics regulatoryReportMetrics,
            RiskFlowScope riskFlowScope,
            @Value("${finapp.risk.filing.lease:PT2M}") Duration lease,
            @Value("${finapp.risk.filing.inquiry-interval:PT15M}") Duration inquiryInterval,
            @Value("${finapp.risk.filing.reports-per-tick:10}") int perTick) {
        return new RegulatoryFilingDriver(regulatoryReports, riskTransactions, regulatoryReportMetrics, riskFlowScope,
                lease, inquiryInterval, perTick);
    }

    @Bean
    RiskReportDesk riskReportDesk(
            RegulatoryReports regulatoryReports,
            RegulatoryFilingDriver regulatoryFilingDriver,
            IdempotentExecutor idempotentExecutor,
            RiskTransactions riskTransactions,
            RiskFlowScope riskFlowScope) {
        return new RiskReportDesk(regulatoryReports, regulatoryFilingDriver, idempotentExecutor, riskTransactions,
                riskFlowScope);
    }
}
