package com.finapp.app.risk;

import com.finapp.identity.IdentityStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.telemetry.Spans;
import com.finapp.risk.AlertFolding;
import com.finapp.risk.AlertQueue;
import com.finapp.risk.AlertRaiser;
import com.finapp.risk.Alerts;
import com.finapp.risk.Cases;
import com.finapp.risk.JdbcRiskAlertStore;
import com.finapp.risk.RiskAlertStore;
import com.finapp.risk.RiskSubjectParties;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the born-once alert (`P13-TSK-015`): the alert's store, the folding - since `P13-TSK-016` the case's
 * ({@code Cases::fold}, {@code RiskCaseBeans}' {@link Cases}: a born alert attaches to its subject's open case of the
 * kind, or opens one, in the raising transaction; the alerts born {@code OPEN} before it are the case leg's drain) - the
 * meters, and the machine, whose analysts' party is read through credit's {@code IdentityActingParty} (the one read of
 * it the platform has) and whose subjects' party through {@link RiskSubjectParties} ({@code RiskProfileBeans}'
 * {@code IdentitySubjectParties}: a party and an identity placed today). The rules raise through {@link AlertRaiser}
 * ({@code alerts::raiseAll}): `-013`'s account-takeover rules in T-eval and the profile leg; `-014`'s abuse rules; and
 * `-020`'s AML monitoring rules in the profile leg and T-mon's rule leg (one call per party, on the PARTY subject this
 * join places); the suppression door is `-018`'s ({@link RiskAlertDesk}), the alert queue its {@link com.finapp.risk.AlertQueue}.
 */
@Configuration(proxyBeanMethods = false)
public class RiskAlertBeans {

    @Bean
    RiskAlertStore riskAlertStore() {
        return new JdbcRiskAlertStore();
    }

    /**
     * `P13-TSK-016`'s folding in place of {@code AlertFolding.NONE}: the case (R4), then the alert's attach (R5), on the
     * raising transaction's unit of work. {@code Cases} holds the alert store, never {@code Alerts}, so the composition
     * has no cycle.
     */
    @Bean
    AlertFolding alertFolding(Cases cases) {
        return cases::fold;
    }

    /** {@code finapp.risk.alert{kind,severity}}, {@code .alert.suppressed{reason_code}} - eager. */
    @Bean
    RiskAlertMetrics riskAlertMetrics(MeterRegistry meterRegistry) {
        return new RiskAlertMetrics(meterRegistry);
    }

    @Bean
    Alerts alerts(
            RiskAlertStore riskAlertStore,
            AlertFolding alertFolding,
            IdentityStore<Connection> identityStore,
            RiskSubjectParties riskSubjectParties,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock,
            Spans domainSpans,
            RiskAlertMetrics riskAlertMetrics) {
        return new Alerts(riskAlertStore, alertFolding, new com.finapp.app.credit.IdentityActingParty(identityStore),
                riskSubjectParties, auditWriter, outboxWriter, idGenerator, clock, domainSpans, riskAlertMetrics);
    }

    /**
     * How T-eval and the profile leg raise (`P13-TSK-013`): one {@code raiseAll} per transaction - births in key order,
     * then the folds.
     */
    @Bean
    AlertRaiser alertRaiser(Alerts alerts) {
        return alerts::raiseAll;
    }

    /**
     * The alert queue (`P13-TSK-018`): a read model over the alert table, its readers placed through credit's
     * {@code IdentityActingParty} and every row's subject through the accountable join (a merchant's organisation too).
     */
    @Bean
    AlertQueue alertQueue(
            RiskAlertStore riskAlertStore,
            IdentityStore<Connection> identityStore,
            RiskSubjectParties riskSubjectParties,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new AlertQueue(riskAlertStore, new com.finapp.app.credit.IdentityActingParty(identityStore),
                riskSubjectParties, auditWriter, idGenerator, clock);
    }

    /** The suppression door (`P13-TSK-018`) over `-015`'s machine. */
    @Bean
    RiskAlertDesk riskAlertDesk(
            Alerts alerts,
            IdempotentExecutor idempotentExecutor,
            RiskTransactions riskTransactions,
            RiskFlowScope riskFlowScope) {
        return new RiskAlertDesk(alerts, idempotentExecutor, riskTransactions, riskFlowScope);
    }
}
