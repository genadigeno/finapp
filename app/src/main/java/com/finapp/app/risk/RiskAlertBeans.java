package com.finapp.app.risk;

import com.finapp.identity.IdentityStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.telemetry.Spans;
import com.finapp.risk.AlertFolding;
import com.finapp.risk.Alerts;
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
 * Wiring for the born-once alert (`P13-TSK-015`): the alert's store, the folding - {@link AlertFolding#NONE} until
 * `P13-TSK-016` replaces this bean with its case folding, so every alert born today stays {@code OPEN}, queued - the
 * meters, and the machine, whose analysts' party is read through credit's {@code IdentityActingParty} (the one read of
 * it the platform has) and whose subjects' party through {@link RiskSubjectParties} ({@code RiskProfileBeans}'
 * {@code IdentitySubjectParties}: a party and an identity placed today). Nothing calls {@code Alerts} in production yet:
 * the rules that raise are `-013`, `-014` and `-020`'s, the suppression door `-018`'s.
 */
@Configuration(proxyBeanMethods = false)
public class RiskAlertBeans {

    @Bean
    RiskAlertStore riskAlertStore() {
        return new JdbcRiskAlertStore();
    }

    /** No case exists yet: an alert stays OPEN, queued - `P13-TSK-016` replaces this bean. */
    @Bean
    AlertFolding alertFolding() {
        return AlertFolding.NONE;
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
}
