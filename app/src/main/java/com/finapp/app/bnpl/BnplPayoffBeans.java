package com.finapp.app.bnpl;

import com.finapp.bnpl.BnplAccrualStore;
import com.finapp.bnpl.BnplAccruals;
import com.finapp.bnpl.BnplBook;
import com.finapp.bnpl.BnplClosures;
import com.finapp.bnpl.BnplPayoffStore;
import com.finapp.bnpl.BnplPayoffs;
import com.finapp.bnpl.BnplPurchaseStore;
import com.finapp.bnpl.BnplRepaymentStore;
import com.finapp.bnpl.BnplRepayments;
import com.finapp.bnpl.BnplServicing;
import com.finapp.bnpl.BnplServicingStore;
import com.finapp.bnpl.BnplTermsStore;
import com.finapp.bnpl.BnplWallets;
import com.finapp.bnpl.JdbcBnplPayoffStore;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.MfaEnrolmentStore;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The BNPL early payoff's wiring (`P12-TSK-022`): the store, T-pay ({@link BnplPayoffs} - the quote and the execution,
 * through the servicing step's billing, T-rep's posting half and the one closure), the meter and the desk. A configuration
 * of its own beside {@code BnplRepaymentBeans} and {@code BnplRefundBeans}, so parallel tasks append to no common bean
 * method.
 */
@Configuration(proxyBeanMethods = false)
public class BnplPayoffBeans {

    @Bean
    BnplPayoffStore bnplPayoffStore() {
        return new JdbcBnplPayoffStore();
    }

    /** T-pay - the quote under B4 and its execution: billing, applications, the repayment and the closure in one commit. */
    @Bean
    BnplPayoffs bnplPayoffs(
            BnplRepaymentStore bnplRepaymentStore,
            BnplPayoffStore bnplPayoffStore,
            BnplServicingStore bnplServicingStore,
            BnplPurchaseStore bnplPurchaseStore,
            BnplTermsStore bnplTermsStore,
            BnplAccrualStore bnplAccrualStore,
            BnplWallets bnplWallets,
            BnplBook bnplBook,
            BnplServicing bnplServicing,
            BnplRepayments bnplRepayments,
            BnplClosures bnplClosures,
            IdGenerator idGenerator) {
        return new BnplPayoffs(bnplRepaymentStore, bnplPayoffStore, bnplServicingStore, bnplPurchaseStore, bnplTermsStore,
                bnplAccrualStore, bnplWallets, bnplBook, bnplServicing, bnplRepayments, bnplClosures, idGenerator);
    }

    /** {@code finapp.bnpl.payoff{product, outcome}} - every series eager. */
    @Bean
    BnplPayoffMetrics bnplPayoffMetrics(MeterRegistry meterRegistry) {
        return new BnplPayoffMetrics(meterRegistry);
    }

    @Bean
    BnplPayoffDesk bnplPayoffDesk(
            BnplPayoffs bnplPayoffs,
            BnplAccruals bnplAccruals,
            IdentityStore<Connection> identityStore,
            MfaEnrolmentStore<Connection> mfaEnrolmentStore,
            IdempotentExecutor idempotentExecutor,
            BnplTransactions bnplTransactions,
            BnplPayoffMetrics bnplPayoffMetrics,
            BnplRepaymentMetrics bnplRepaymentMetrics) {
        return new BnplPayoffDesk(bnplPayoffs, bnplAccruals, identityStore, mfaEnrolmentStore, idempotentExecutor,
                bnplTransactions, bnplPayoffMetrics, bnplRepaymentMetrics);
    }
}
