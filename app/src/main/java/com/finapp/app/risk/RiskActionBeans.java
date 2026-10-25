package com.finapp.app.risk;

import com.finapp.identity.IdentityAdministration;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.SessionRevocation;
import com.finapp.merchant.JdbcMerchantStore;
import com.finapp.merchant.MerchantAdministration;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.risk.ActionDispatch;
import com.finapp.risk.Cases;
import com.finapp.risk.HeldPaymentActions;
import com.finapp.risk.HeldWithdrawalActions;
import com.finapp.risk.IdentityActions;
import com.finapp.risk.JdbcRiskActionStore;
import com.finapp.risk.MerchantActions;
import com.finapp.risk.RiskActionStore;
import com.finapp.risk.RiskActions;
import com.finapp.risk.RiskAssessmentStore;
import com.finapp.risk.RiskCaseStore;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wiring for the downstream action machine (`P13-TSK-017`; {@code PHASE_13_PLAN.md} sections 7.1 T-case and T-act, 7.5,
 * 9, 12.8; ADR-0112): the action's store; the ports risk declared implemented over the owning domains' existing
 * commands - identity's administration and session revocation ({@link IdentityRiskActions}), merchant's suspension
 * ({@link MerchantRiskActions}), payments' held doors ({@code RiskWithdrawalBeans}' and {@code RiskPaymentBeans}'), each
 * in its owner's own transaction; the dispatch over them; the machine ({@link RiskActions}, over {@code RiskCaseBeans}'
 * {@link Cases}, and the override's {@code ActionIssuer} - {@code RiskOverrideBeans}); the driver that runs T-act as the
 * platform; the analyst's desk; the meters; and {@link RiskActionSchedule} - leaderless, off in test contexts, its gauge
 * eager either way. Accounts' and crossborder's ports have no implementation: neither owner has the command (the kinds
 * are unavailable).
 */
@Configuration(proxyBeanMethods = false)
public class RiskActionBeans {

    @Bean
    RiskActionStore riskActionStore() {
        return new JdbcRiskActionStore();
    }

    /** An owner's own transaction - always new, so an owner's command never joins a caller's (T-act's shape). */
    private static TransactionTemplate ownersTransaction(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_DEFAULT);
        return template;
    }

    @Bean
    IdentityActions identityActions(
            IdentityAdministration identityAdministration,
            SessionRevocation sessionRevocation,
            IdentityStore<Connection> identityStore,
            IdempotentExecutor idempotentExecutor,
            PlatformTransactionManager transactionManager,
            DataSource dataSource) {
        return new IdentityRiskActions(identityAdministration, sessionRevocation, identityStore, idempotentExecutor,
                ownersTransaction(transactionManager), dataSource);
    }

    @Bean
    MerchantActions merchantActions(
            MerchantAdministration merchantAdministration,
            IdempotentExecutor idempotentExecutor,
            PlatformTransactionManager transactionManager,
            DataSource dataSource) {
        return new MerchantRiskActions(merchantAdministration, new JdbcMerchantStore(), idempotentExecutor,
                ownersTransaction(transactionManager), dataSource);
    }

    @Bean
    ActionDispatch actionDispatch(
            IdentityActions identityActions,
            MerchantActions merchantActions,
            HeldWithdrawalActions heldWithdrawalActions,
            HeldPaymentActions heldPaymentActions) {
        return new ActionDispatch(identityActions, merchantActions, heldWithdrawalActions, heldPaymentActions);
    }

    @Bean
    RiskActions riskActions(
            RiskActionStore riskActionStore,
            Cases cases,
            RiskCaseStore riskCaseStore,
            RiskAssessmentStore riskAssessmentStore,
            ActionDispatch actionDispatch,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new RiskActions(riskActionStore, cases, riskCaseStore, riskAssessmentStore, actionDispatch, auditWriter,
                outboxWriter, idGenerator, clock);
    }

    /** {@code finapp.risk.action{kind,outcome}}, {@code .action.failed{kind}}, the claim failures - eager. */
    @Bean
    RiskActionMetrics riskActionMetrics(MeterRegistry meterRegistry) {
        return new RiskActionMetrics(meterRegistry);
    }

    /**
     * T-act's drive: the lease a claim moves the permit by ({@code finapp.risk.action.lease}, longer than the slowest
     * owner's door) and the schedule's bound per tick ({@code finapp.risk.action.actions-per-tick}).
     */
    @Bean
    RiskActionDriver riskActionDriver(
            RiskActions riskActions,
            RiskTransactions riskTransactions,
            RiskActionMetrics riskActionMetrics,
            RiskFlowScope riskFlowScope,
            @Value("${finapp.risk.action.lease:PT2M}") Duration lease,
            @Value("${finapp.risk.action.actions-per-tick:20}") int perTick) {
        return new RiskActionDriver(riskActions, riskTransactions, riskActionMetrics, riskFlowScope, lease, perTick);
    }

    @Bean
    RiskActionDesk riskActionDesk(
            RiskActions riskActions,
            Cases cases,
            RiskActionDriver riskActionDriver,
            IdempotentExecutor idempotentExecutor,
            RiskTransactions riskTransactions,
            RiskFlowScope riskFlowScope) {
        return new RiskActionDesk(riskActions, cases, riskActionDriver, idempotentExecutor, riskTransactions,
                riskFlowScope);
    }

    /** The action schedule - every instance, no lease, off in test contexts ({@code finapp.risk.action.poll}). */
    @Bean
    @ConditionalOnProperty(name = "finapp.risk.action.sweeper.enabled", havingValue = "true", matchIfMissing = true)
    RiskActionSchedule riskActionSchedule(
            RiskActionDriver riskActionDriver,
            RiskActionMetrics riskActionMetrics,
            @Value("${finapp.risk.action.poll:PT5S}") Duration pollInterval) {
        return new RiskActionSchedule(riskActionDriver, riskActionMetrics, pollInterval);
    }

    /** Whether this instance runs the action schedule - eager either way (`P1-TSK-029`'s rule). */
    @Bean
    Gauge riskActionSweeperEnabled(
            @Value("${finapp.risk.action.sweeper.enabled:true}") boolean enabled, MeterRegistry meterRegistry) {
        return Gauge.builder("finapp.risk.sweeper.enabled", () -> enabled ? 1 : 0)
                .tag("kind", RiskActionMetrics.KIND)
                .description("Whether this instance runs the risk action schedule")
                .register(meterRegistry);
    }
}
