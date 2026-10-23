package com.finapp.app.risk;

import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.telemetry.Spans;
import com.finapp.risk.JdbcRiskPolicyStore;
import com.finapp.risk.RiskPolicies;
import com.finapp.risk.RiskPolicyAdministration;
import com.finapp.risk.RiskPolicyStore;
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
 * Wiring for risk (`P13-TSK-002`) - the module's first beans: its unit of work, the risk policy versions' store, the
 * four-eyes administration publishing through the outbox, the reads and the pin ({@link RiskPolicies}, which `-004`'s
 * evaluation consumes on the caller's connection), the risk officer's policy doors, the active-version gauge and the
 * flow scope's door leg (`-001`'s {@code RiskFlowScope}, given its spans here).
 */
@Configuration(proxyBeanMethods = false)
public class RiskBeans {

    /** risk's unit of work - one transaction, default propagation. */
    @Bean
    RiskTransactions riskTransactions(PlatformTransactionManager transactionManager, DataSource dataSource) {
        return new RiskTransactions(new TransactionTemplate(transactionManager), dataSource);
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
}
