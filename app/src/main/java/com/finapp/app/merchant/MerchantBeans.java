package com.finapp.app.merchant;

import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.merchant.JdbcMerchantStore;
import com.finapp.merchant.MerchantAdministration;
import com.finapp.merchant.MerchantOnboarding;
import com.finapp.merchant.MerchantStore;
import com.finapp.merchant.MerchantVerification;
import com.finapp.party.JdbcPartyStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wiring for the merchant's operator surface (`P6-TSK-003`). The stores are stateless, so
 * direct instances are the wiring (the {@code AccountsBeans} stance); the KYB gate is the
 * composition root's port implementation over the party store — the module cannot see
 * {@code party}, which is the point.
 */
@Configuration
public class MerchantBeans {

    @Bean
    MerchantVerification<Connection> merchantVerification() {
        return new VerifiedMerchantOrganisation(new JdbcPartyStore());
    }

    @Bean
    MerchantOnboarding merchantOnboarding(
            MerchantVerification<Connection> merchantVerification,
            IdempotentExecutor idempotentExecutor,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator ids,
            Clock clock) {
        return new MerchantOnboarding(
                new JdbcMerchantStore(),
                new JdbcLedgerAccountStore(),
                merchantVerification,
                idempotentExecutor,
                auditWriter,
                outboxWriter,
                ids,
                clock);
    }

    @Bean
    MerchantAdministration merchantAdministration(
            AuditWriter<Connection> auditWriter, IdGenerator ids, Clock clock) {
        return new MerchantAdministration(new JdbcMerchantStore(), auditWriter, ids, clock);
    }

    @Bean
    MerchantOperations merchantOperations(
            MerchantOnboarding merchantOnboarding,
            MerchantAdministration merchantAdministration,
            PlatformTransactionManager transactionManager,
            DataSource dataSource) {
        return new MerchantOperations(
                merchantOnboarding,
                merchantAdministration,
                new TransactionTemplate(transactionManager),
                dataSource);
    }
}
