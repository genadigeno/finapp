package com.finapp.app.risk;

import com.finapp.identity.IdentityStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.risk.DecisionOverrideStore;
import com.finapp.risk.DecisionOverrides;
import com.finapp.risk.HeldPaymentActions;
import com.finapp.risk.HeldWithdrawalActions;
import com.finapp.risk.JdbcDecisionOverrideStore;
import com.finapp.risk.OverrideActions;
import com.finapp.risk.RiskAssessmentStore;
import com.finapp.risk.RiskPolicies;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the decision override (`P13-TSK-012`): the override's store, the machine (T-ovr) over the sealed
 * assessments and the pinned versions, whose party an officer is through credit's {@code IdentityActingParty} (the one
 * read of it the platform has), the issued action over payments' two keyed ports ({@code RiskWithdrawalBeans}' and
 * {@code RiskPaymentBeans}' - their first risk caller), the driver that dispatches it after the approval commits and
 * re-drives it from the monitoring sweep's leg ({@code RiskMonitoringBeans}), the meters and the desk.
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
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new DecisionOverrides(decisionOverrideStore, riskAssessmentStore, riskPolicies,
                new com.finapp.app.credit.IdentityActingParty(identityStore), auditWriter, outboxWriter, idGenerator,
                clock);
    }

    @Bean
    OverrideActions overrideActions(
            DecisionOverrideStore decisionOverrideStore,
            HeldWithdrawalActions heldWithdrawalActions,
            HeldPaymentActions heldPaymentActions,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new OverrideActions(decisionOverrideStore, heldWithdrawalActions, heldPaymentActions, auditWriter,
                outboxWriter, idGenerator, clock);
    }

    /** {@code finapp.risk.override{outcome}}, {@code .action{kind,outcome}}, {@code .action.failed{kind}} - eager. */
    @Bean
    RiskOverrideMetrics riskOverrideMetrics(MeterRegistry meterRegistry) {
        return new RiskOverrideMetrics(meterRegistry);
    }

    /**
     * The issued action's drive: the lease an approval's permit is issued with ({@code finapp.risk.override.action-lease},
     * longer than payments' slowest door) and the re-drive leg's bound per tick ({@code
     * finapp.risk.override.actions-per-tick}).
     */
    @Bean
    OverrideActionDriver overrideActionDriver(
            OverrideActions overrideActions,
            RiskTransactions riskTransactions,
            RiskOverrideMetrics riskOverrideMetrics,
            RiskFlowScope riskFlowScope,
            @Value("${finapp.risk.override.action-lease:PT2M}") Duration lease,
            @Value("${finapp.risk.override.actions-per-tick:5}") int perTick) {
        return new OverrideActionDriver(overrideActions, riskTransactions, riskOverrideMetrics, riskFlowScope, lease,
                perTick);
    }

    @Bean
    RiskOverrideDesk riskOverrideDesk(
            DecisionOverrides decisionOverrides,
            OverrideActionDriver overrideActionDriver,
            IdempotentExecutor idempotentExecutor,
            RiskTransactions riskTransactions,
            RiskFlowScope riskFlowScope,
            RiskOverrideMetrics riskOverrideMetrics) {
        return new RiskOverrideDesk(decisionOverrides, overrideActionDriver, idempotentExecutor, riskTransactions,
                riskFlowScope, riskOverrideMetrics);
    }
}
