package com.finapp.app.checkout;

import com.finapp.app.payments.PaymentService;
import com.finapp.checkout.JdbcCheckoutSessionStore;
import com.finapp.checkout.JdbcOrderStore;
import com.finapp.identity.JdbcIdentityStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.merchant.FeeSchedules;
import com.finapp.merchant.JdbcMerchantStore;
import com.finapp.merchant.MerchantSettlement;
import com.finapp.payments.PaymentParticipants;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wiring for the checkout flow (`P6-TSK-007`). The stores are stateless, so direct instances
 * are the wiring (the {@code MerchantBeans} stance).
 *
 * <p>{@link CheckoutService} is conditional on the payment surface existing, for
 * {@code PaymentBeans}' recorded reason: an unconfigured provider means the platform cannot
 * take a payment at all, and a checkout that could open sessions nobody could pay would be a
 * worse answer than the honest absence.
 */
@Configuration
public class CheckoutBeans {

    @Bean
    CheckoutSessions checkoutSessions(
            FeeSchedules feeSchedules,
            IdempotentExecutor idempotentExecutor,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator ids,
            Clock clock) {
        return new CheckoutSessions(
                new JdbcCheckoutSessionStore(),
                new JdbcOrderStore(),
                new JdbcMerchantStore(),
                feeSchedules,
                idempotentExecutor,
                auditWriter,
                outboxWriter,
                ids,
                clock,
                // One SecureRandom for the application, seeded by the platform: the token's
                // entropy IS the security argument (CheckoutSessionToken).
                new java.security.SecureRandom());
    }

    /**
     * The seam's second moment, wired (`P6-TSK-007`): what a landed capture means to the flow
     * that created its intent.
     *
     * <p>A {@code Consumer} rather than a typed port, deliberately: the composition lives in
     * {@code app.merchant} and the completion in {@code app.checkout}, and inventing a shared
     * interface for one call between two packages of the same module would be ceremony. What
     * matters — that {@code payments} names neither — is already true.
     */
    @Bean
    java.util.function.Consumer<
                    com.finapp.app.merchant.MerchantBoundCaptureComposition.Completion>
            captureCompletion(CheckoutSessions checkoutSessions) {
        return landed ->
                checkoutSessions.completed(
                        landed.unitOfWork(),
                        landed.capture().intent().value(),
                        landed.entryRef(),
                        landed.capture().correlation());
    }

    @Bean
    CheckoutService checkoutService(
            CheckoutSessions checkoutSessions,
            MerchantSettlement merchantSettlement,
            ObjectProvider<PaymentService> paymentService,
            PaymentParticipants<Connection> paymentParticipants,
            LedgerAccountStore<Connection> ledgerAccountStore,
            IdempotentExecutor idempotentExecutor,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator ids,
            Clock clock,
            PlatformTransactionManager transactionManager,
            DataSource dataSource) {
        return new CheckoutService(
                checkoutSessions,
                merchantSettlement,
                paymentService.getObject(),
                paymentParticipants,
                ledgerAccountStore,
                new JdbcIdentityStore(),
                idempotentExecutor,
                auditWriter,
                outboxWriter,
                ids,
                clock,
                new TransactionTemplate(transactionManager),
                dataSource);
    }

    @Bean
    LedgerAccountStore<Connection> checkoutLedgerAccountStore() {
        return new JdbcLedgerAccountStore();
    }
}
