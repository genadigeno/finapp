package com.finapp.app.risk;

import com.finapp.identity.IdentityStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.risk.CaseOpener;
import com.finapp.risk.Cases;
import com.finapp.risk.JdbcRiskCaseStore;
import com.finapp.risk.ProfileRecompute;
import com.finapp.risk.RiskAlertStore;
import com.finapp.risk.RiskCaseStore;
import com.finapp.risk.RiskSubjectParties;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the case (`P13-TSK-016`; {@code PHASE_13_PLAN.md} sections 7.1 T-case, 9, 12.8; ADR-0110): the case's store,
 * the meters, {@link Cases} - its analysts' party read through credit's {@code IdentityActingParty} (the one read of it
 * the platform has) and its subjects' accountable party through {@code RiskProfileBeans}' {@code IdentitySubjectParties}
 * (a party, an identity's party, a merchant's organisation) - the two seams it implements ({@code RiskAlertBeans}'
 * {@code AlertFolding} is {@code cases::fold}; {@link CaseOpener} here, given to T-eval by {@code RiskEvaluationBeans}),
 * the prohibition's driver (after the case commits, never under it) and the analyst's desk. The case leg rides
 * {@code RiskMonitoringBeans}' schedule.
 */
@Configuration(proxyBeanMethods = false)
public class RiskCaseBeans {

    @Bean
    RiskCaseStore riskCaseStore() {
        return new JdbcRiskCaseStore();
    }

    /** {@code finapp.risk.case{kind,status}}, {@code .case.resolution{kind}} - eager. */
    @Bean
    RiskCaseMetrics riskCaseMetrics(MeterRegistry meterRegistry) {
        return new RiskCaseMetrics(meterRegistry);
    }

    @Bean
    Cases cases(
            RiskCaseStore riskCaseStore,
            RiskAlertStore riskAlertStore,
            IdentityStore<Connection> identityStore,
            RiskSubjectParties riskSubjectParties,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock,
            RiskCaseMetrics riskCaseMetrics) {
        return new Cases(riskCaseStore, riskAlertStore, new com.finapp.app.credit.IdentityActingParty(identityStore),
                riskSubjectParties, auditWriter, outboxWriter, idGenerator, clock, riskCaseMetrics);
    }

    /** T-eval's case (`-004`'s seam): a {@code REVIEW} or {@code HOLD} opens or joins its party's case of the kind. */
    @Bean
    CaseOpener caseOpener(Cases cases) {
        return cases::openFor;
    }

    /** An approved disposition's {@code PROHIBITED}, after the case commits - the door's follow-up and the leg's. */
    @Bean
    CaseProhibitionDriver caseProhibitionDriver(
            Cases cases,
            ProfileRecompute profileRecompute,
            RiskTransactions riskTransactions,
            IdGenerator idGenerator,
            @Value("${finapp.risk.case.prohibitions-per-tick:20}") int prohibitionsPerTick) {
        return new CaseProhibitionDriver(cases, profileRecompute, riskTransactions, idGenerator, prohibitionsPerTick);
    }

    @Bean
    RiskCaseDesk riskCaseDesk(
            Cases cases,
            CaseProhibitionDriver caseProhibitionDriver,
            IdempotentExecutor idempotentExecutor,
            RiskTransactions riskTransactions,
            RiskFlowScope riskFlowScope) {
        return new RiskCaseDesk(cases, caseProhibitionDriver, idempotentExecutor, riskTransactions, riskFlowScope);
    }
}
