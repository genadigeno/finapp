package com.finapp.app.merchant;

import com.finapp.app.mfa.MfaKey;
import com.finapp.app.security.DatabaseEndpoint;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.HoldService;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingService;
import com.finapp.merchant.JdbcMerchantPayoutStore;
import com.finapp.merchant.JdbcMerchantStore;
import com.finapp.merchant.JdbcPayoutEvidenceStore;
import com.finapp.merchant.MerchantPayoutOutcomes;
import com.finapp.merchant.MerchantPayoutResolution;
import com.finapp.merchant.MerchantPayoutStore;
import com.finapp.merchant.MerchantPayouts;
import com.finapp.merchant.PayoutDestinationStore;
import com.finapp.merchant.PayoutEvidenceCipher;
import com.finapp.merchant.PayoutEvidenceStore;
import com.finapp.merchant.PayoutProvider;
import com.finapp.merchant.SimulatedPayoutProvider;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import java.net.URI;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wiring for the merchant payout (`P6-TSK-012`, ADR-0051, ADR-0057).
 *
 * <h2>The provider, and everything that sends to it, is conditional on its URL</h2>
 *
 * <p>{@code finapp.merchant.payout.provider.url} — the {@code PaymentBeans} shape: the adapter,
 * the command and the resolution sweep exist only where a provider is configured, and the
 * operations service reaches the command through an {@code ObjectProvider}, so an unconfigured
 * deployment answers {@code merchant.PayoutProviderUnavailable} 503 while a payout's read keeps
 * working.
 *
 * <h2>Two credentials, one concern each</h2>
 *
 * <p>The provider's API key ({@code finapp.merchant.payout.provider.key}, {@link
 * PayoutProviderKey}) and the evidence encryption key ({@code finapp.merchant.payout.evidence.key},
 * {@link PayoutEvidenceKey}), each decoded through the confinement: the marked local default is
 * refused anywhere the database is not on loopback.
 *
 * <h2>The sweep's bounds are its safety</h2>
 *
 * <p>{@code dispatched-age} (default {@code PT10M}) must exceed any plausible gap between a
 * committed send permit and its request reaching the provider — it is what makes
 * {@code NEVER_RECEIVED} a safe conclusion (ADR-0057 §4). {@code unknown-age} (default
 * {@code PT1M}) is how long an ambiguous payout waits before its first query.
 */
@Configuration
public class MerchantPayoutBeans {

    /**
     * The sweep's dispatched bound, as ONE placeholder (`P6-TSK-013`): the resolution sweep
     * concludes nothing about a younger permit, and the stuck-payout gauge counts a
     * {@code DISPATCHED} payout only past it. Both read this constant, so the two can never
     * disagree about when an answer was due.
     */
    public static final String DISPATCHED_AGE =
            "${finapp.merchant.payout.sweeper.dispatched-age:PT10M}";

    @Bean
    MerchantPayoutStore<Connection> merchantPayoutStore() {
        return new JdbcMerchantPayoutStore();
    }

    @Bean
    SecureRandom payoutEvidenceRandomness() {
        return new SecureRandom();
    }

    @Bean
    PayoutEvidenceCipher payoutEvidenceCipher(
            @Value("${finapp.merchant.payout.evidence.key:" + MfaKey.MARKED_LOCAL_DEFAULT + "}")
                    String configuredKey,
            @Value("${finapp.merchant.payout.evidence.key-version:1}") int keyVersion,
            SecureRandom payoutEvidenceRandomness,
            Environment environment) {
        boolean loopback = DatabaseEndpoint.isEntirelyLoopback(DatabaseEndpoint.url(environment));
        return new PayoutEvidenceCipher(
                PayoutEvidenceKey.decode(configuredKey, loopback),
                keyVersion,
                payoutEvidenceRandomness);
    }

    @Bean
    PayoutEvidenceStore<Connection> payoutEvidenceStore(
            PayoutEvidenceCipher payoutEvidenceCipher, IdGenerator ids) {
        return new JdbcPayoutEvidenceStore(payoutEvidenceCipher, ids);
    }

    @Bean
    MerchantPayoutOutcomes merchantPayoutOutcomes(
            MerchantPayoutStore<Connection> merchantPayoutStore,
            HoldService holdService,
            PostingService postingService,
            LedgerAccountStore<Connection> ledgerAccountStore,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator ids,
            Clock clock) {
        return new MerchantPayoutOutcomes(
                merchantPayoutStore,
                holdService,
                postingService,
                new ChartOfAccounts<>(ledgerAccountStore),
                ledgerAccountStore,
                auditWriter,
                outboxWriter,
                ids,
                clock);
    }

    @Bean
    @ConditionalOnProperty("finapp.merchant.payout.provider.url")
    PayoutProvider payoutProvider(
            @Value("${finapp.merchant.payout.provider.url}") URI url,
            @Value("${finapp.merchant.payout.provider.timeout:PT2S}") Duration timeout,
            @Value("${finapp.merchant.payout.provider.key:" + MfaKey.MARKED_LOCAL_DEFAULT + "}")
                    String configuredKey,
            Environment environment) {
        boolean loopback = DatabaseEndpoint.isEntirelyLoopback(DatabaseEndpoint.url(environment));
        return new SimulatedPayoutProvider(
                url, timeout, PayoutProviderKey.decode(configuredKey, loopback));
    }

    @Bean
    @ConditionalOnProperty("finapp.merchant.payout.provider.url")
    MerchantPayouts merchantPayouts(
            IdempotentExecutor idempotentExecutor,
            PayoutDestinationStore<Connection> payoutDestinationStore,
            LedgerAccountStore<Connection> ledgerAccountStore,
            HoldService holdService,
            MerchantPayoutStore<Connection> merchantPayoutStore,
            PayoutProvider payoutProvider,
            MerchantPayoutOutcomes merchantPayoutOutcomes,
            PayoutEvidenceStore<Connection> payoutEvidenceStore,
            AuditWriter<Connection> auditWriter,
            IdGenerator ids,
            Clock clock,
            PlatformTransactionManager transactionManager,
            DataSource dataSource) {
        return new MerchantPayouts(
                new MerchantTransactions(new TransactionTemplate(transactionManager), dataSource),
                idempotentExecutor,
                new JdbcMerchantStore(),
                payoutDestinationStore,
                ledgerAccountStore,
                holdService,
                merchantPayoutStore,
                payoutProvider,
                merchantPayoutOutcomes,
                payoutEvidenceStore,
                auditWriter,
                ids,
                clock);
    }

    @Bean
    MerchantPayoutOperations merchantPayoutOperations(
            ObjectProvider<MerchantPayouts> merchantPayouts,
            MerchantPayoutStore<Connection> merchantPayoutStore,
            PayoutDestinationStore<Connection> payoutDestinationStore,
            PlatformTransactionManager transactionManager,
            DataSource dataSource,
            com.finapp.app.telemetry.MerchantMeters merchantMeters) {
        return new MerchantPayoutOperations(
                merchantPayouts,
                merchantPayoutStore,
                payoutDestinationStore,
                new TransactionTemplate(transactionManager),
                dataSource,
                merchantMeters);
    }

    @Bean
    @ConditionalOnProperty("finapp.merchant.payout.provider.url")
    MerchantPayoutResolution merchantPayoutResolution(
            MerchantPayoutStore<Connection> merchantPayoutStore,
            PayoutProvider payoutProvider,
            MerchantPayoutOutcomes merchantPayoutOutcomes,
            PayoutEvidenceStore<Connection> payoutEvidenceStore,
            IdGenerator ids,
            Clock clock,
            PlatformTransactionManager transactionManager,
            DataSource dataSource,
            @Value(DISPATCHED_AGE) Duration dispatchedAge,
            @Value("${finapp.merchant.payout.sweeper.unknown-age:PT1M}") Duration unknownAge,
            @Value("${finapp.merchant.payout.sweeper.batch:50}") int batchSize) {
        return new MerchantPayoutResolution(
                new MerchantTransactions(new TransactionTemplate(transactionManager), dataSource),
                merchantPayoutStore,
                payoutProvider,
                merchantPayoutOutcomes,
                payoutEvidenceStore,
                ids,
                clock,
                dispatchedAge,
                unknownAge,
                batchSize);
    }

    /**
     * The schedule — the {@code PaymentSweeperSchedule} gate shape for the identical reason: a
     * background worker resolving payouts under every {@code @SpringBootTest} would race
     * assertions, so the app test overlay disables it and the database suite drives
     * {@code sweep()} directly. {@code matchIfMissing = true}, because a deployment that forgets
     * the sweep leaves every ambiguous payout's hold standing for ever.
     */
    @Bean
    @ConditionalOnProperty(
            name = "finapp.merchant.payout.sweeper.enabled",
            havingValue = "true",
            matchIfMissing = true)
    @ConditionalOnBean(MerchantPayoutResolution.class)
    MerchantPayoutResolutionSchedule merchantPayoutResolutionSchedule(
            MerchantPayoutResolution merchantPayoutResolution,
            com.finapp.app.telemetry.MerchantMeters merchantMeters,
            @Value("${finapp.merchant.payout.sweeper.poll-interval:PT30S}") Duration pollInterval) {
        return new MerchantPayoutResolutionSchedule(
                merchantPayoutResolution, merchantMeters, pollInterval);
    }
}
