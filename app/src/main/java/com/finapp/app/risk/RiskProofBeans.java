package com.finapp.app.risk;

import com.finapp.identity.IdentityStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.risk.AlertBornOnceCensus;
import com.finapp.risk.AssessmentCensus;
import com.finapp.risk.CaseEvidence;
import com.finapp.risk.CaseOneOpenCensus;
import com.finapp.risk.CaseWorklist;
import com.finapp.risk.DailyReplay;
import com.finapp.risk.FilingCensus;
import com.finapp.risk.JdbcRiskProofReads;
import com.finapp.risk.JdbcVelocityEvidence;
import com.finapp.risk.JudgedCommands;
import com.finapp.risk.MonitoredLedger;
import com.finapp.risk.MonitoredParties;
import com.finapp.risk.MonitoringCensus;
import com.finapp.risk.MonitoringWatermarkStore;
import com.finapp.risk.RescreeningCensus;
import com.finapp.risk.RiskOperationsReports;
import com.finapp.risk.RiskPolicies;
import com.finapp.risk.RiskProofReads;
import com.finapp.risk.RiskSubjectParties;
import com.finapp.risk.VelocityEvidence;
import com.finapp.risk.VelocityProof;
import com.finapp.risk.VelocityWindowStore;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The risk proofs' wiring (`P13-TSK-023`; {@code PHASE_13_PLAN.md} sections 9, 12.4, 13.3, 15; {@code DESIGN-023}): the
 * proofs' reads of risk's own tables, the owners' judged commands ({@link PlatformJudgedCommands}), the four censuses and
 * the filing row, the velocity proof made whole (the counted assessments as the record, `-019`'s monitored transfers as
 * the journal's cross-check), the operations reports and the proofs' one reading, the day's replay, the evidence door and
 * the meters. A configuration of its own (bnpl's {@code BnplProofBeans}), so no other bean method is appended to.
 * <strong>No schedule</strong>: the meters read on scrape behind {@code finapp.risk.proofs.refresh}.
 */
@Configuration(proxyBeanMethods = false)
public class RiskProofBeans {

    @Bean
    RiskProofReads riskProofReads() {
        return new JdbcRiskProofReads();
    }

    @Bean
    JudgedCommands judgedCommands() {
        return new PlatformJudgedCommands();
    }

    /** The velocity proof made whole: the counted assessments as the record, the monitored journal's transfers beside. */
    @Bean
    VelocityProof velocityProof(VelocityWindowStore velocityWindowStore, VelocityEvidence velocityEvidence,
            MonitoringWatermarkStore monitoringWatermarkStore) {
        return new VelocityProof(velocityWindowStore, velocityEvidence, new JdbcVelocityEvidence(monitoringWatermarkStore));
    }

    /**
     * The reports and the meters' reading; a reader placed through credit's {@code IdentityActingParty}, a velocity
     * divergence's subject through the accountable join ({@link RiskSubjectParties}) and the accounts' owners
     * ({@link MonitoredParties}).
     */
    @Bean
    RiskOperationsReports riskOperationsReports(
            RiskProofReads riskProofReads,
            JudgedCommands judgedCommands,
            MonitoredLedger monitoredLedger,
            MonitoringWatermarkStore monitoringWatermarkStore,
            VelocityProof velocityProof,
            IdentityStore<Connection> identityStore,
            RiskSubjectParties riskSubjectParties,
            MonitoredParties monitoredParties,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new RiskOperationsReports(riskProofReads, new AssessmentCensus(riskProofReads, judgedCommands),
                new MonitoringCensus(monitoredLedger, monitoringWatermarkStore, riskProofReads),
                new AlertBornOnceCensus(riskProofReads), new CaseOneOpenCensus(riskProofReads),
                new FilingCensus(riskProofReads), new RescreeningCensus(riskProofReads), velocityProof,
                new com.finapp.app.credit.IdentityActingParty(identityStore), riskSubjectParties, monitoredParties,
                auditWriter, idGenerator, clock);
    }

    /**
     * The day's replay; whose party a reader is through credit's {@code IdentityActingParty}; a day closed the
     * monitoring intake's settle horizon after its end ({@code finapp.risk.monitoring.horizon}, 60 s).
     */
    @Bean
    DailyReplay dailyReplay(
            RiskProofReads riskProofReads,
            RiskPolicies riskPolicies,
            IdentityStore<Connection> identityStore,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock,
            @Value("${finapp.risk.monitoring.horizon:PT60S}") Duration settleHorizon) {
        return new DailyReplay(riskProofReads, riskPolicies,
                new com.finapp.app.credit.IdentityActingParty(identityStore), auditWriter, idGenerator, clock,
                settleHorizon);
    }

    @Bean
    CaseEvidence caseEvidence(CaseWorklist caseWorklist, RiskProofReads riskProofReads, AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator, Clock clock) {
        return new CaseEvidence(caseWorklist, riskProofReads, auditWriter, idGenerator, clock);
    }

    /** The reports' door - every snapshot armed with {@code finapp.risk.proofs.statement-timeout}. */
    @Bean
    RiskOperationsReportDesk riskOperationsReportDesk(RiskOperationsReports riskOperationsReports,
            RiskTransactions riskTransactions, RiskFlowScope riskFlowScope,
            @Value("${finapp.risk.proofs.statement-timeout:PT5S}") Duration statementTimeout) {
        return new RiskOperationsReportDesk(riskOperationsReports, riskTransactions, riskFlowScope, statementTimeout);
    }

    @Bean
    RiskEvidenceDesk riskEvidenceDesk(CaseEvidence caseEvidence, RiskTransactions riskTransactions,
            RiskFlowScope riskFlowScope) {
        return new RiskEvidenceDesk(caseEvidence, riskTransactions, riskFlowScope);
    }

    /**
     * {@code finapp.risk.velocity.proof{verdict}}, {@code finapp.risk.census{kind,verdict}} and
     * {@code finapp.risk.proof.reading{outcome}}, registered eagerly; read behind {@code finapp.risk.proofs.refresh}
     * (15 minutes by default, the reading's cost control) on a virtual thread of its own, single-flight, never the
     * scrape's - each statement bounded by {@code finapp.risk.proofs.statement-timeout} (5 s by default).
     */
    @Bean
    RiskProofMetrics riskProofMetrics(RiskOperationsReports riskOperationsReports, RiskTransactions riskTransactions,
            Clock clock, MeterRegistry meterRegistry,
            @Value("${finapp.risk.proofs.refresh:PT15M}") Duration refresh,
            @Value("${finapp.risk.proofs.statement-timeout:PT5S}") Duration statementTimeout) {
        return new RiskProofMetrics(RiskProofMetrics.over(riskOperationsReports, riskTransactions, statementTimeout),
                RiskProofMetrics.virtualThreads(), clock, meterRegistry, refresh);
    }
}
