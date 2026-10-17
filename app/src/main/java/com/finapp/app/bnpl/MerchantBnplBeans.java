package com.finapp.app.bnpl;

import com.finapp.bnpl.BnplMerchants;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.merchant.BnplEnablements;
import com.finapp.merchant.BnplPricing;
import com.finapp.merchant.JdbcBnplEnablementStore;
import com.finapp.merchant.JdbcBnplPricingStore;
import com.finapp.merchant.JdbcFeeScheduleStore;
import com.finapp.merchant.JdbcMerchantStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the merchant side of BNPL (`P12-TSK-006`): merchant's four-eyes enablement and BNPL pricing, the operator
 * doors over them, and bnpl's {@link BnplMerchants} port over merchant - its first consumers the application
 * (`P12-TSK-010`), the purchase (`-013`) and the refund (`-021`). A configuration of its own beside {@code BnplBeans}, so
 * the two wave-A tasks append to no common bean method.
 */
@Configuration(proxyBeanMethods = false)
public class MerchantBnplBeans {

    @Bean
    BnplEnablements bnplEnablements(AuditWriter<Connection> auditWriter, IdGenerator idGenerator, Clock clock) {
        return new BnplEnablements(new JdbcBnplEnablementStore(), new JdbcMerchantStore(), auditWriter, idGenerator,
                clock);
    }

    @Bean
    BnplPricing bnplPricing(AuditWriter<Connection> auditWriter, IdGenerator idGenerator, Clock clock) {
        return new BnplPricing(new JdbcBnplPricingStore(), new JdbcBnplEnablementStore(), new JdbcMerchantStore(),
                new JdbcFeeScheduleStore(), auditWriter, idGenerator, clock);
    }

    /** The merchant as bnpl judges it: standing under share locks, the payable, the pin and the returned fee. */
    @Bean
    BnplMerchants bnplMerchants(BnplPricing bnplPricing) {
        return new MerchantBnplMerchants(bnplPricing, new JdbcLedgerAccountStore());
    }

    @Bean
    MerchantBnplDesk merchantBnplDesk(
            BnplEnablements bnplEnablements,
            BnplPricing bnplPricing,
            IdempotentExecutor idempotentExecutor,
            BnplTransactions bnplTransactions) {
        return new MerchantBnplDesk(bnplEnablements, bnplPricing, idempotentExecutor, bnplTransactions);
    }
}
