package com.finapp.app.bnpl;

import com.finapp.bnpl.BnplAccruals;
import com.finapp.bnpl.BnplBook;
import com.finapp.bnpl.BnplClosures;
import com.finapp.bnpl.BnplMerchants;
import com.finapp.bnpl.BnplPurchaseStore;
import com.finapp.bnpl.BnplRefundStore;
import com.finapp.bnpl.BnplRefunds;
import com.finapp.bnpl.BnplServicingStore;
import com.finapp.bnpl.BnplTermsStore;
import com.finapp.bnpl.BnplWallets;
import com.finapp.bnpl.JdbcBnplRefundStore;
import com.finapp.identity.IdentityStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The BNPL merchant refund's wiring (`P12-TSK-021`): the store, T-ref ({@link BnplRefunds} - one body for the merchant's
 * door, the operator's and, later, a dispute's refund outcome), whose party an operator is (credit's
 * {@code IdentityActingParty}, one read for credit, lending and bnpl), the meter and the desk. A configuration of its own
 * beside {@code BnplRepaymentBeans}, so parallel tasks append to no common bean method.
 */
@Configuration(proxyBeanMethods = false)
public class BnplRefundBeans {

    @Bean
    BnplRefundStore bnplRefundStore() {
        return new JdbcBnplRefundStore();
    }

    /** T-ref - the refund's bound, split, posting, versions and closure in one transaction. */
    @Bean
    BnplRefunds bnplRefunds(
            BnplRefundStore bnplRefundStore,
            BnplServicingStore bnplServicingStore,
            BnplPurchaseStore bnplPurchaseStore,
            BnplTermsStore bnplTermsStore,
            BnplMerchants bnplMerchants,
            BnplWallets bnplWallets,
            BnplBook bnplBook,
            BnplClosures bnplClosures,
            IdentityStore<Connection> identityStore,
            OutboxWriter<Connection> outboxWriter,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new BnplRefunds(bnplRefundStore, bnplServicingStore, bnplPurchaseStore, bnplTermsStore, bnplMerchants,
                bnplWallets, bnplBook, bnplClosures, new com.finapp.app.credit.IdentityActingParty(identityStore),
                outboxWriter, auditWriter, idGenerator, clock);
    }

    /** {@code finapp.bnpl.refund{kind, outcome}} - every series eager. */
    @Bean
    BnplRefundMetrics bnplRefundMetrics(MeterRegistry meterRegistry) {
        return new BnplRefundMetrics(meterRegistry);
    }

    @Bean
    BnplRefundDesk bnplRefundDesk(
            BnplRefunds bnplRefunds,
            BnplAccruals bnplAccruals,
            IdempotentExecutor idempotentExecutor,
            BnplTransactions bnplTransactions,
            BnplRefundMetrics bnplRefundMetrics) {
        return new BnplRefundDesk(bnplRefunds, bnplAccruals, idempotentExecutor, bnplTransactions, bnplRefundMetrics);
    }
}
