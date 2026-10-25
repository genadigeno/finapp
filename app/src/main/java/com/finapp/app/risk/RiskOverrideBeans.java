package com.finapp.app.risk;

import com.finapp.identity.IdentityStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.risk.DecisionOverrideStore;
import com.finapp.risk.DecisionOverrides;
import com.finapp.risk.JdbcDecisionOverrideStore;
import com.finapp.risk.RiskActions;
import com.finapp.risk.RiskAssessmentStore;
import com.finapp.risk.RiskPolicies;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the decision override (`P13-TSK-012`): the override's store, the machine (T-ovr) over the sealed
 * assessments and the pinned versions, whose party an officer is through credit's {@code IdentityActingParty} (the one
 * read of it the platform has), issuing its action into the action machine ({@code RiskActionBeans}' {@link RiskActions},
 * `P13-TSK-017`'s fold: the {@code risk_action} row under the override's own id), the meters and the desk - whose
 * approval drives the issued action through {@code RiskActionBeans}' {@link RiskActionDriver} as the platform after its
 * commit, re-driven by {@code RiskActionSchedule} (the leg on the monitoring sweep is gone).
 */
@Configuration(proxyBeanMethods = false)
public class RiskOverrideBeans {

    @Bean
    DecisionOverrideStore decisionOverrideStore() {
        return new JdbcDecisionOverrideStore();
    }

    @Bean
    DecisionOverrides decisionOverrides(
            DecisionOverrideStore decisionOverrideStore,
            RiskAssessmentStore riskAssessmentStore,
            RiskPolicies riskPolicies,
            IdentityStore<Connection> identityStore,
            AuditWriter<Connection> auditWriter,
            RiskActions riskActions,
            IdGenerator idGenerator,
            Clock clock) {
        return new DecisionOverrides(decisionOverrideStore, riskAssessmentStore, riskPolicies,
                new com.finapp.app.credit.IdentityActingParty(identityStore), auditWriter, riskActions, idGenerator,
                clock);
    }

    /** {@code finapp.risk.override{outcome}} - eager (the action's series are {@code RiskActionMetrics}'). */
    @Bean
    RiskOverrideMetrics riskOverrideMetrics(MeterRegistry meterRegistry) {
        return new RiskOverrideMetrics(meterRegistry);
    }

    @Bean
    RiskOverrideDesk riskOverrideDesk(
            DecisionOverrides decisionOverrides,
            RiskActionDriver riskActionDriver,
            IdempotentExecutor idempotentExecutor,
            RiskTransactions riskTransactions,
            RiskFlowScope riskFlowScope,
            RiskOverrideMetrics riskOverrideMetrics) {
        return new RiskOverrideDesk(decisionOverrides, riskActionDriver, idempotentExecutor, riskTransactions,
                riskFlowScope, riskOverrideMetrics);
    }
}
