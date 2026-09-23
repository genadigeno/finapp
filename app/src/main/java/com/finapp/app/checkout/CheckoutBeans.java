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
            Clock clock,
            com.finapp.app.telemetry.CheckoutMeters checkoutMeters) {
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
                new java.security.SecureRandom(),
                checkoutMeters);
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

    /**
     * The expiry sweeper (`P6-TSK-008`): the producer of {@code EXPIRED}, and unlike every
     * other bean here it is NOT conditional on the payment surface. A deployment with no
     * provider can still have sessions that were opened and never paid, and an offer nothing
     * can ever end is exactly the stuck row ADR-0044's doctrine exists to prevent.
     *
     * <p>{@code payment-grace} is the safety margin the store's own javadoc argues for: a
     * PAYMENT_PENDING session is given longer than its deadline because a provider answers on
     * its own schedule, and expiring it at the instant of the deadline would make
     * COMPLETED_LATE the ordinary case instead of the countable exception.
     */
    @Bean
    com.finapp.checkout.CheckoutExpirySweeper checkoutExpirySweeper(
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator ids,
            Clock clock,
            PlatformTransactionManager transactionManager,
            DataSource dataSource,
            @org.springframework.beans.factory.annotation.Value(
                            "${finapp.checkout.sweeper.payment-grace:PT10M}")
                    java.time.Duration paymentGrace,
            @org.springframework.beans.factory.annotation.Value(
                            "${finapp.checkout.sweeper.batch:50}")
                    int batchSize) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        return new com.finapp.checkout.CheckoutExpirySweeper(
                new CheckoutTransactions(template, dataSource),
                new JdbcCheckoutSessionStore(),
                auditWriter,
                outboxWriter,
                ids,
                clock,
                paymentGrace,
                batchSize);
    }

    /**
     * The schedule — the {@code PaymentSweeperSchedule} gate shape for the identical reason: a
     * background worker expiring sessions under every {@code @SpringBootTest} would race
     * assertions, so the app test overlay disables it and the database suite drives
     * {@code sweep()} directly. {@code matchIfMissing = true}, because a deployment that forgets
     * the sweeper strands every unpaid offer for ever.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "finapp.checkout.sweeper.enabled",
            havingValue = "true",
            matchIfMissing = true)
    CheckoutExpirySweeperSchedule checkoutExpirySweeperSchedule(
            com.finapp.checkout.CheckoutExpirySweeper checkoutExpirySweeper,
            com.finapp.app.telemetry.CheckoutMeters checkoutMeters,
            @org.springframework.beans.factory.annotation.Value(
                            "${finapp.checkout.sweeper.poll-interval:PT30S}")
                    java.time.Duration pollInterval) {
        return new CheckoutExpirySweeperSchedule(
                checkoutExpirySweeper, checkoutMeters, pollInterval);
    }

    @Bean
    CheckoutService checkoutService(
            CheckoutSessions checkoutSessions,
            com.finapp.app.telemetry.CheckoutMeters checkoutMeters,
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
                checkoutMeters,
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
