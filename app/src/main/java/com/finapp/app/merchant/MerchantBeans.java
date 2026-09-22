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
    com.finapp.merchant.MerchantApiKeys merchantApiKeys(
            IdempotentExecutor idempotentExecutor,
            AuditWriter<Connection> auditWriter,
            IdGenerator ids,
            Clock clock) {
        return new com.finapp.merchant.MerchantApiKeys(
                new com.finapp.merchant.JdbcMerchantApiKeyStore(),
                new JdbcMerchantStore(),
                idempotentExecutor,
                auditWriter,
                ids,
                clock,
                // One SecureRandom for the application, seeded by the platform: the key
                // secret's entropy IS the security argument (MerchantApiKeySecret), so where
                // it comes from is not an implementation detail.
                new java.security.SecureRandom());
    }

    @Bean
    MerchantApiKeyOperations merchantApiKeyOperations(
            com.finapp.merchant.MerchantApiKeys merchantApiKeys,
            PlatformTransactionManager transactionManager,
            DataSource dataSource) {
        return new MerchantApiKeyOperations(
                merchantApiKeys, new TransactionTemplate(transactionManager), dataSource);
    }

    @Bean
    MerchantKeyAuthenticationInterceptor merchantKeyAuthenticationInterceptor(
            PlatformTransactionManager transactionManager, DataSource dataSource) {
        return new MerchantKeyAuthenticationInterceptor(
                new com.finapp.merchant.JdbcMerchantApiKeyStore(),
                new TransactionTemplate(transactionManager),
                dataSource);
    }

    @Bean
    MerchantSelfView merchantSelfView(
            PlatformTransactionManager transactionManager, DataSource dataSource) {
        return new MerchantSelfView(
                new JdbcMerchantStore(), new TransactionTemplate(transactionManager), dataSource);
    }

    @Bean
    com.finapp.merchant.FeeSchedules feeSchedules(
            AuditWriter<Connection> auditWriter, IdGenerator ids, Clock clock) {
        return new com.finapp.merchant.FeeSchedules(
                new com.finapp.merchant.JdbcFeeScheduleStore(),
                new JdbcMerchantStore(),
                auditWriter,
                ids,
                clock);
    }

    @Bean
    FeeScheduleOperations feeScheduleOperations(
            com.finapp.merchant.FeeSchedules feeSchedules,
            PlatformTransactionManager transactionManager,
            DataSource dataSource) {
        return new FeeScheduleOperations(
                feeSchedules, new TransactionTemplate(transactionManager), dataSource);
    }

    /**
     * ADR-0050 §3's entry, composed (`P6-TSK-005`). Reads the pin, prices under the PINNED
     * version, resolves the payable and the fee revenue account, and writes
     * {@code merchant.FeeAssessed} on the capture's own connection.
     */
    @Bean
    com.finapp.merchant.MerchantSettlement merchantSettlement(
            com.finapp.ledger.LedgerAccountStore<Connection> ledgerAccountStore,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator ids) {
        return new com.finapp.merchant.MerchantSettlement(
                new com.finapp.merchant.JdbcPaymentFeePinStore(),
                new com.finapp.merchant.JdbcFeeScheduleStore(),
                ledgerAccountStore,
                new com.finapp.ledger.ChartOfAccounts<>(ledgerAccountStore),
                outboxWriter,
                ids);
    }

    /**
     * The seam itself (`P6-TSK-005`): the bean {@code PaymentOutcomes} posts through. Declared
     * HERE rather than in {@code PaymentBeans}, because the reason it exists is that
     * {@code payments} cannot name the type it delegates to.
     */
    @Bean
    com.finapp.payments.CaptureComposition<Connection> captureComposition(
            com.finapp.merchant.MerchantSettlement merchantSettlement) {
        return new MerchantBoundCaptureComposition(
                merchantSettlement, new com.finapp.payments.WalletTopUpComposition());
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
