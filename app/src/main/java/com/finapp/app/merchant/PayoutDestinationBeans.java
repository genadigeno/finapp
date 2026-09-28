package com.finapp.app.merchant;

import com.finapp.identity.MfaEnrolmentStore;
import com.finapp.merchant.JdbcMerchantStore;
import com.finapp.merchant.JdbcPayoutDestinationStore;
import com.finapp.merchant.PayoutDestinationEffectuation;
import com.finapp.merchant.PayoutDestinationStore;
import com.finapp.merchant.PayoutDestinationTokenisation;
import com.finapp.merchant.PayoutDestinations;
import com.finapp.merchant.SimulatedPayoutDestinationTokenisation;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.sharedkernel.id.IdGenerator;
import java.net.URI;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wiring for the payout destination flow (`P6-TSK-011`, ADR-0056).
 *
 * <h2>The cooling-off is configuration, and it cannot be switched off</h2>
 *
 * <p>{@code finapp.merchant.payout-destination.cooling-off}, default {@code PT72H} — a weekend
 * plus a working day, the window in which a change nobody meant can be noticed and withdrawn.
 * {@link PayoutDestinations} refuses a non-positive value, so a misconfiguration fails the
 * context rather than quietly disabling the control. Each approval pins its own deadline, so
 * changing the value never alters an approval already given.
 *
 * <h2>An unconfigured provider keeps the contract</h2>
 *
 * <p>The exchange bean exists only where {@code ...tokenisation.url} is configured, and the
 * operations service reaches it through an {@code ObjectProvider}: without one a proposal
 * answers the honest {@code merchant.DestinationTokenisationUnavailable} 503 while the reads and
 * decisions keep working — the {@code PaymentMethodBeans} decision, for its reasons.
 */
@Configuration
public class PayoutDestinationBeans {

    @Bean
    PayoutDestinationStore<Connection> payoutDestinationStore() {
        return new JdbcPayoutDestinationStore();
    }

    @Bean
    PayoutDestinations payoutDestinations(
            PayoutDestinationStore<Connection> payoutDestinationStore,
            IdempotentExecutor idempotentExecutor,
            AuditWriter<Connection> auditWriter,
            IdGenerator ids,
            Clock clock,
            @Value("${finapp.merchant.payout-destination.cooling-off:PT72H}") Duration coolingOff) {
        return new PayoutDestinations(
                payoutDestinationStore,
                new JdbcMerchantStore(),
                idempotentExecutor,
                auditWriter,
                ids,
                clock,
                coolingOff);
    }

    @Bean
    @ConditionalOnProperty("finapp.merchant.payout-destination.tokenisation.url")
    PayoutDestinationTokenisation payoutDestinationTokenisation(
            @Value("${finapp.merchant.payout-destination.tokenisation.url}") URI url,
            @Value("${finapp.merchant.payout-destination.tokenisation.timeout:PT2S}")
                    Duration timeout) {
        return new SimulatedPayoutDestinationTokenisation(url, timeout);
    }

    @Bean
    PayoutDestinationOperations payoutDestinationOperations(
            PayoutDestinations payoutDestinations,
            ObjectProvider<PayoutDestinationTokenisation> payoutDestinationTokenisation,
            MfaEnrolmentStore<Connection> mfaEnrolmentStore,
            PlatformTransactionManager transactionManager,
            DataSource dataSource) {
        return new PayoutDestinationOperations(
                payoutDestinations,
                payoutDestinationTokenisation,
                mfaEnrolmentStore,
                new TransactionTemplate(transactionManager),
                dataSource);
    }

    @Bean
    PayoutDestinationEffectuation payoutDestinationEffectuation(
            PayoutDestinationStore<Connection> payoutDestinationStore,
            AuditWriter<Connection> auditWriter,
            IdGenerator ids,
            Clock clock,
            PlatformTransactionManager transactionManager,
            DataSource dataSource,
            @Value("${finapp.merchant.payout-destination.effectuation.batch:50}") int batchSize) {
        return new PayoutDestinationEffectuation(
                new MerchantTransactions(new TransactionTemplate(transactionManager), dataSource),
                payoutDestinationStore,
                auditWriter,
                ids,
                clock,
                batchSize);
    }

    /**
     * The schedule — the {@code CheckoutExpirySweeperSchedule} gate shape for the identical
     * reason: a background worker effecting destinations under every {@code @SpringBootTest}
     * would race assertions, so the app test overlay disables it and the database suite drives
     * {@code sweep()} directly. {@code matchIfMissing = true}, because a deployment that forgets
     * the sweep leaves every approved destination cooling off for ever.
     */
    @Bean
    @ConditionalOnProperty(
            name = "finapp.merchant.payout-destination.effectuation.enabled",
            havingValue = "true",
            matchIfMissing = true)
    PayoutDestinationEffectuationSchedule payoutDestinationEffectuationSchedule(
            PayoutDestinationEffectuation payoutDestinationEffectuation,
            @Value("${finapp.merchant.payout-destination.effectuation.poll-interval:PT30S}")
                    Duration pollInterval) {
        return new PayoutDestinationEffectuationSchedule(
                payoutDestinationEffectuation, pollInterval);
    }
}
