package com.finapp.app.bnpl;

import com.finapp.bnpl.BnplBook;
import com.finapp.bnpl.BnplClosures;
import com.finapp.bnpl.BnplPurchaseStore;
import com.finapp.bnpl.BnplRepaymentStore;
import com.finapp.bnpl.BnplRepayments;
import com.finapp.bnpl.BnplServicingStore;
import com.finapp.bnpl.BnplTermsStore;
import com.finapp.bnpl.BnplWallets;
import com.finapp.bnpl.JdbcBnplRepaymentStore;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.MfaEnrolmentStore;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The BNPL repayment and closure wiring (`P12-TSK-018`): the store, the one closure ({@link BnplClosures} - called by the
 * repayment here and by the servicing step, {@code BnplServicingBeans}), T-rep, its meters and the desk. A configuration
 * of its own beside {@code BnplPurchaseBeans} and {@code BnplServicingBeans}, so parallel tasks append to no common bean
 * method.
 */
@Configuration(proxyBeanMethods = false)
public class BnplRepaymentBeans {

    @Bean
    BnplRepaymentStore bnplRepaymentStore() {
        return new JdbcBnplRepaymentStore();
    }

    /** The closure - one implementation for every zeroing transaction (the repayment, the servicing step, later more). */
    @Bean
    BnplClosures bnplClosures(
            BnplRepaymentStore bnplRepaymentStore,
            BnplServicingStore bnplServicingStore,
            BnplPurchaseStore bnplPurchaseStore,
            BnplWallets bnplWallets,
            BnplBook bnplBook,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new BnplClosures(bnplRepaymentStore, bnplServicingStore, bnplPurchaseStore, bnplWallets, bnplBook,
                outboxWriter, idGenerator, clock);
    }

    /** T-rep - the customer's repayment from the wallet, its allocation, posting and closure in one transaction. */
    @Bean
    BnplRepayments bnplRepayments(
            BnplRepaymentStore bnplRepaymentStore,
            BnplServicingStore bnplServicingStore,
            BnplPurchaseStore bnplPurchaseStore,
            BnplTermsStore bnplTermsStore,
            BnplWallets bnplWallets,
            BnplBook bnplBook,
            BnplClosures bnplClosures,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new BnplRepayments(bnplRepaymentStore, bnplServicingStore, bnplPurchaseStore, bnplTermsStore, bnplWallets,
                bnplBook, bnplClosures, outboxWriter, idGenerator, clock);
    }

    /** {@code finapp.bnpl.repayment{kind, outcome}} and {@code finapp.bnpl.allocation.anomaly} - every series eager. */
    @Bean
    BnplRepaymentMetrics bnplRepaymentMetrics(MeterRegistry meterRegistry) {
        return new BnplRepaymentMetrics(meterRegistry);
    }

    @Bean
    BnplRepaymentDesk bnplRepaymentDesk(
            BnplRepayments bnplRepayments,
            IdentityStore<Connection> identityStore,
            MfaEnrolmentStore<Connection> mfaEnrolmentStore,
            IdempotentExecutor idempotentExecutor,
            BnplTransactions bnplTransactions,
            BnplRepaymentMetrics bnplRepaymentMetrics,
            com.finapp.bnpl.BnplRepaymentReversals bnplRepaymentReversals) {
        return new BnplRepaymentDesk(bnplRepayments, identityStore, mfaEnrolmentStore, idempotentExecutor,
                bnplTransactions, bnplRepaymentMetrics, bnplRepaymentReversals);
    }
}
