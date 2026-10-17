package com.finapp.app.bnpl;

import com.finapp.bnpl.BnplCapital;
import com.finapp.bnpl.BnplProductBounds;
import com.finapp.bnpl.BnplTermsAdministration;
import com.finapp.bnpl.BnplTermsStore;
import com.finapp.bnpl.JdbcBnplTermsStore;
import com.finapp.lending.LendingCapitalHeadroom;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.OutboxWriter;
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
 * Wiring for bnpl (`P12-TSK-004`) - the module's first beans: its unit of work, the BNPL terms versions' store, the port
 * {@code app} implements over credit's products (the amount and plan bounds the terms door judges against), the
 * four-eyes administration publishing through the outbox, the BNPL officer's doors and the active-version gauge; and
 * (`P12-TSK-005`) the capital port over lending's headroom, judged under L6 in bnpl's transaction - its first consumer
 * the purchase (`P12-TSK-013`).
 */
@Configuration(proxyBeanMethods = false)
public class BnplBeans {

    /** bnpl's unit of work - one transaction, default propagation. */
    @Bean
    BnplTransactions bnplTransactions(PlatformTransactionManager transactionManager, DataSource dataSource) {
        return new BnplTransactions(new TransactionTemplate(transactionManager), dataSource);
    }

    @Bean
    BnplTermsStore bnplTermsStore() {
        return new JdbcBnplTermsStore();
    }

    /** Credit's amount and plan bounds, for the schedulability judgement at the terms door. */
    @Bean
    BnplProductBounds bnplProductBounds() {
        return new CreditBnplProductBounds();
    }

    /**
     * Lending capital as bnpl judges it (`P12-TSK-005`, ADR-0104 decision 3): lending's one headroom - BNPL principal
     * counted by ledger purpose - under the capital row's lock (L6), in bnpl's own transaction.
     */
    @Bean
    BnplCapital bnplCapital(LendingCapitalHeadroom lendingCapitalHeadroom) {
        return new LendingBnplCapital(lendingCapitalHeadroom);
    }

    @Bean
    BnplTermsAdministration bnplTermsAdministration(
            BnplTermsStore bnplTermsStore,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock,
            BnplProductBounds bnplProductBounds) {
        return new BnplTermsAdministration(
                bnplTermsStore, auditWriter, outboxWriter, idGenerator, clock, bnplProductBounds);
    }

    @Bean
    BnplTermsDesk bnplTermsDesk(
            BnplTermsAdministration bnplTermsAdministration,
            IdempotentExecutor idempotentExecutor,
            BnplTransactions bnplTransactions) {
        return new BnplTermsDesk(bnplTermsAdministration, idempotentExecutor, bnplTransactions);
    }

    /**
     * {@code finapp.bnpl.terms.active{product}}: each product's ACTIVE terms version, 0 for none - the product is not
     * offered, which production expects until the activation gate opens.
     */
    @Bean
    BnplTermsMetrics bnplTermsMetrics(
            BnplTermsStore bnplTermsStore, DataSource dataSource, Clock clock, MeterRegistry meterRegistry) {
        return new BnplTermsMetrics(bnplTermsStore, dataSource::getConnection, clock, meterRegistry);
    }
}
