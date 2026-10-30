package com.finapp.app.accounting;

import com.finapp.accounting.GlMappingAdministration;
import com.finapp.accounting.GlMappingStore;
import com.finapp.accounting.GlMappings;
import com.finapp.accounting.JdbcGlMappingStore;
import com.finapp.accounting.LedgerChart;
import com.finapp.accounting.PostingRuleRegister;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.telemetry.Spans;
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
 * Wiring for accounting - the module's first beans (`P14-TSK-002`; {@code RiskBeans}' shape): its unit of work and the
 * posting rule register's audited door over {@link PostingRuleRegister#CURRENT}. Later tasks add theirs here.
 */
@Configuration(proxyBeanMethods = false)
public class AccountingBeans {

    /** accounting's unit of work - one transaction, default propagation. */
    @Bean
    AccountingTransactions accountingTransactions(PlatformTransactionManager transactionManager, DataSource dataSource) {
        return new SpringAccountingTransactions(new TransactionTemplate(transactionManager), dataSource);
    }

    @Bean
    PostingRuleDesk postingRuleDesk(
            AccountingTransactions accountingTransactions,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new PostingRuleDesk(PostingRuleRegister.CURRENT, accountingTransactions, auditWriter, idGenerator, clock);
    }

    // ------------------------------------------------------------------ the GL chart and mapping (P14-TSK-003)

    @Bean
    GlMappingStore glMappingStore() {
        return new JdbcGlMappingStore();
    }

    /** accounting's port over the ledger's chart - SELECT only, on the caller's unit of work. */
    @Bean
    LedgerChart ledgerChart() {
        return new LedgerPurposeChart();
    }

    @Bean
    GlMappingAdministration glMappingAdministration(
            GlMappingStore glMappingStore,
            LedgerChart ledgerChart,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new GlMappingAdministration(glMappingStore, ledgerChart, auditWriter, outboxWriter, idGenerator, clock);
    }

    @Bean
    GlMappings glMappings(GlMappingStore glMappingStore, LedgerChart ledgerChart) {
        return new GlMappings(glMappingStore, ledgerChart);
    }

    /** The door leg's spans, no attribute ({@code PHASE_14_PLAN.md} section 16). */
    @Bean
    AccountingFlowScope accountingFlowScope(Spans domainSpans) {
        return new AccountingFlowScope(domainSpans);
    }

    @Bean
    GlMappingDesk glMappingDesk(
            GlMappingAdministration glMappingAdministration,
            GlMappings glMappings,
            IdempotentExecutor idempotentExecutor,
            AccountingTransactions accountingTransactions,
            AccountingFlowScope accountingFlowScope) {
        return new GlMappingDesk(glMappingAdministration, glMappings, idempotentExecutor, accountingTransactions,
                accountingFlowScope);
    }

    /** {@code finapp.accounting.gl.unmapped}, registered at zero (`-010` feeds it). */
    @Bean
    AccountingMappingMetrics accountingMappingMetrics(MeterRegistry meterRegistry) {
        return new AccountingMappingMetrics(meterRegistry);
    }
}
