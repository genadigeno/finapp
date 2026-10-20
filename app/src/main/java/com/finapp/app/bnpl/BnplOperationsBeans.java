package com.finapp.app.bnpl;

import com.finapp.bnpl.BnplApplicationStore;
import com.finapp.bnpl.BnplConditionStore;
import com.finapp.bnpl.BnplProofs;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.telemetry.Spans;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Operating BNPL (`P12-TSK-026`; {@code PHASE_12_PLAN.md} section 15): the steps' spans linked by each flow's stored
 * correlation, the two series the plan names that no earlier task built ({@code finapp.bnpl.obligations{product, status}},
 * {@code finapp.bnpl.application.awaiting.age}) and the four operations reports. A configuration of its own, so no common
 * bean method is appended to.
 */
@Configuration(proxyBeanMethods = false)
public class BnplOperationsBeans {

    /** BNPL's step spans, linked by each flow's stored correlation - over the platform's domain spans, no attribute ever. */
    @Bean
    BnplFlowScope bnplFlowScope(BnplTransactions bnplTransactions, Spans domainSpans) {
        return new BnplFlowScope(bnplTransactions, domainSpans);
    }

    /** {@code finapp.bnpl.obligations{product, status}}, eager, read behind a 15 s floor. */
    @Bean
    BnplObligationMetrics bnplObligationMetrics(BnplConditionStore bnplConditionStore, DataSource dataSource, Clock clock,
            MeterRegistry meterRegistry) {
        return new BnplObligationMetrics(bnplConditionStore, dataSource::getConnection, clock, meterRegistry);
    }

    /** {@code finapp.bnpl.application.awaiting.age} - the origination sweep's oldest wait (A5-03). */
    @Bean
    BnplAwaitingDecisionMetrics bnplAwaitingDecisionMetrics(BnplApplicationStore bnplApplicationStore,
            DataSource dataSource, Clock clock, MeterRegistry meterRegistry) {
        return new BnplAwaitingDecisionMetrics(bnplApplicationStore, dataSource::getConnection, clock, meterRegistry);
    }

    /** The four operations reports - one read-only snapshot each, the three proofs read in it, recorded per serving. */
    @Bean
    BnplReports bnplReports(BnplTransactions bnplTransactions, BnplProofs bnplProofs, AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator, Clock clock) {
        return new BnplReports(BnplReports.over(bnplTransactions), new JdbcBnplReportReads(), bnplProofs::prove,
                auditWriter, idGenerator, clock);
    }
}
