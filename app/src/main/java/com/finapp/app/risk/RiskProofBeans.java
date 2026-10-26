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
import com.finapp.risk.MonitoringCensus;
import com.finapp.risk.MonitoringWatermarkStore;
import com.finapp.risk.RiskOperationsReports;
import com.finapp.risk.RiskPolicies;
import com.finapp.risk.RiskProofReads;
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

    @Bean
    RiskOperationsReports riskOperationsReports(
            RiskProofReads riskProofReads,
            JudgedCommands judgedCommands,
            MonitoredLedger monitoredLedger,
            MonitoringWatermarkStore monitoringWatermarkStore,
            VelocityProof velocityProof,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new RiskOperationsReports(riskProofReads, new AssessmentCensus(riskProofReads, judgedCommands),
                new MonitoringCensus(monitoredLedger, monitoringWatermarkStore, riskProofReads),
                new AlertBornOnceCensus(riskProofReads), new CaseOneOpenCensus(riskProofReads),
                new FilingCensus(riskProofReads), velocityProof, auditWriter, idGenerator, clock);
    }

    /** The day's replay; whose party a reader is through credit's {@code IdentityActingParty}. */
    @Bean
    DailyReplay dailyReplay(
            RiskProofReads riskProofReads,
            RiskPolicies riskPolicies,
            IdentityStore<Connection> identityStore,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new DailyReplay(riskProofReads, riskPolicies,
                new com.finapp.app.credit.IdentityActingParty(identityStore), auditWriter, idGenerator, clock);
    }

    @Bean
    CaseEvidence caseEvidence(CaseWorklist caseWorklist, RiskProofReads riskProofReads, AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator, Clock clock) {
        return new CaseEvidence(caseWorklist, riskProofReads, auditWriter, idGenerator, clock);
    }

    @Bean
    RiskOperationsReportDesk riskOperationsReportDesk(RiskOperationsReports riskOperationsReports,
            RiskTransactions riskTransactions, RiskFlowScope riskFlowScope) {
        return new RiskOperationsReportDesk(riskOperationsReports, riskTransactions, riskFlowScope);
    }

    @Bean
    RiskEvidenceDesk riskEvidenceDesk(CaseEvidence caseEvidence, RiskTransactions riskTransactions,
            RiskFlowScope riskFlowScope) {
        return new RiskEvidenceDesk(caseEvidence, riskTransactions, riskFlowScope);
    }

    /**
     * {@code finapp.risk.velocity.proof{verdict}} and {@code finapp.risk.census{kind,verdict}}, registered eagerly; read
     * behind {@code finapp.risk.proofs.refresh} (15 minutes by default, the reading's cost control).
     */
    @Bean
    RiskProofMetrics riskProofMetrics(RiskOperationsReports riskOperationsReports, RiskTransactions riskTransactions,
            Clock clock, MeterRegistry meterRegistry,
            @Value("${finapp.risk.proofs.refresh:PT15M}") Duration refresh) {
        return new RiskProofMetrics(RiskProofMetrics.over(riskOperationsReports, riskTransactions), clock,
                meterRegistry, refresh);
    }
}
