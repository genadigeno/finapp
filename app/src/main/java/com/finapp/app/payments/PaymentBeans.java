package com.finapp.app.payments;

import com.finapp.accounts.CustomerAccountStore;
import com.finapp.app.security.DatabaseEndpoint;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.party.PartyStore;
import com.finapp.paymentmethods.PaymentMethodStore;
import com.finapp.payments.EvidenceCipher;
import com.finapp.payments.JdbcPaymentAttemptStore;
import com.finapp.payments.JdbcPaymentIntentStore;
import com.finapp.payments.JdbcProviderEvidenceStore;
import com.finapp.payments.PaymentAttemptStore;
import com.finapp.payments.PaymentCancellation;
import com.finapp.payments.PaymentConfirmation;
import com.finapp.payments.PaymentCreation;
import com.finapp.payments.PaymentIntentStore;
import com.finapp.payments.PaymentParticipants;
import com.finapp.payments.PaymentProvider;
import com.finapp.payments.ProviderEvidenceStore;
import com.finapp.payments.SimulatedCardPspAdapter;
import com.finapp.payments.TransactionRunner;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires the payment command slice (`P5-TSK-009`) — the {@code payments} stores and commands
 * meeting their composition root, the `P5-TSK-003` unconsumed-wiring licence expiring on
 * schedule. The HTTP consumer is `P5-TSK-011`'s controller (the licence's next named
 * consumer); until it lands, the commands' consumers are the database suite and the beans
 * themselves.
 *
 * <h2>The provider bean carries the whole ADR-0046 slice with it</h2>
 *
 * <p>{@code finapp.payments.provider.url} has <strong>no default</strong> (ADR-0008 simulates
 * providers; the {@code KycBeans} shape, and the property name `P5-TSK-003` fixed in the
 * adapter's javadoc so this task wired without a naming decision). {@link PaymentConfirmation}
 * requires a provider by constructor — a confirm without a provider is not a degraded mode, it
 * is unconfigurable — so the command bean shares the adapter's condition, and the surface task
 * answers the honest 503 for its absence (the {@code ObjectProvider} decision recorded there).
 */
@Configuration
class PaymentBeans {

    @Bean
    PaymentIntentStore<Connection> paymentIntentStore() {
        return new JdbcPaymentIntentStore();
    }

    @Bean
    PaymentAttemptStore<Connection> paymentAttemptStore() {
        return new JdbcPaymentAttemptStore();
    }

    /**
     * The evidence key, decoded through the confinement (`P5-TSK-002`): the marked local
     * default is confined to loopback via {@link DatabaseEndpoint} — the {@code DocumentCipher}
     * wiring, credential six.
     */
    @Bean
    EvidenceCipher evidenceCipher(
            @Value("${finapp.payments.evidence.key:" + com.finapp.app.mfa.MfaKey.MARKED_LOCAL_DEFAULT + "}")
                    String configuredKey,
            @Value("${finapp.payments.evidence.key-version:1}") int keyVersion,
            SecureRandom paymentsRandomness,
            Environment environment) {
        boolean loopback = DatabaseEndpoint.isEntirelyLoopback(DatabaseEndpoint.url(environment));
        return new EvidenceCipher(
                PaymentEvidenceKey.decode(configuredKey, loopback), keyVersion,
                paymentsRandomness);
    }

    @Bean
    SecureRandom paymentsRandomness() {
        return new SecureRandom();
    }

    @Bean
    ProviderEvidenceStore<Connection> providerEvidenceStore(
            EvidenceCipher evidenceCipher, IdGenerator ids) {
        return new JdbcProviderEvidenceStore(evidenceCipher, ids);
    }

    @Bean
    PaymentParticipants<Connection> paymentParticipants(
            PartyStore<Connection> partyStore,
            CustomerAccountStore<Connection> customerAccountStore,
            LedgerAccountStore<Connection> ledgerAccountStore,
            PaymentMethodStore<Connection> paymentMethodStore) {
        return new JdbcPaymentParticipants(
                partyStore, customerAccountStore, ledgerAccountStore, paymentMethodStore);
    }

    /**
     * The payment transaction: {@code REQUIRES_NEW} and default isolation — every contended
     * decision inside is a conditional {@code UPDATE}'s row count or a unique constraint
     * (the {@code paymentMethodTransactions} recorded reasons).
     */
    @Bean
    TransactionTemplate paymentTransactions(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_DEFAULT);
        return template;
    }

    /**
     * {@link TransactionRunner} over the template: the connection's lifetime is the call —
     * bound inside, released before return — which is what makes "no connection is held
     * during the provider call" a property of {@link PaymentConfirmation}'s code.
     */
    @Bean
    TransactionRunner paymentTransactionRunner(
            TransactionTemplate paymentTransactions, DataSource dataSource) {
        return new TransactionRunner() {
            @Override
            public <R> R inTransaction(Function<Connection, R> work) {
                return paymentTransactions.execute(
                        status -> {
                            Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
                            try {
                                return work.apply(unitOfWork);
                            } finally {
                                DataSourceUtils.releaseConnection(unitOfWork, dataSource);
                            }
                        });
            }
        };
    }

    /**
     * The simulated card PSP — present only where an endpoint is configured (ADR-0008; the
     * timeout default is the adapter's own documented {@code PT2S}). The API key is credential
     * five, decoded through the confinement `P5-TSK-003` prepared it for.
     */
    @Bean
    @ConditionalOnProperty("finapp.payments.provider.url")
    PaymentProvider paymentProvider(
            @Value("${finapp.payments.provider.url}") java.net.URI url,
            @Value("${finapp.payments.provider.timeout:PT2S}") java.time.Duration timeout,
            @Value("${finapp.payments.provider.key:" + com.finapp.app.mfa.MfaKey.MARKED_LOCAL_DEFAULT + "}")
                    String configuredKey,
            Environment environment) {
        boolean loopback = DatabaseEndpoint.isEntirelyLoopback(DatabaseEndpoint.url(environment));
        return new SimulatedCardPspAdapter(
                url, timeout, ProviderApiKey.decode(configuredKey, loopback));
    }

    @Bean
    PaymentCreation paymentCreation(
            IdempotentExecutor idempotentExecutor,
            PaymentParticipants<Connection> paymentParticipants,
            PaymentIntentStore<Connection> paymentIntentStore,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator ids,
            Clock clock) {
        return new PaymentCreation(
                idempotentExecutor,
                paymentParticipants,
                paymentIntentStore,
                auditWriter,
                outboxWriter,
                ids,
                clock);
    }

    @Bean
    com.finapp.payments.RefundStore<Connection> refundStore() {
        return new com.finapp.payments.JdbcRefundStore();
    }

    /**
     * The Phase 3 hold machinery meets its owed production consumer (`P3-TSK-015` →
     * `P5-TSK-015`, ADR-0048 §4): the refund's dispatch reserves the customer's funds inside
     * the account-row lock, so the composition that was built and proven two phases ago is
     * wired the day its caller arrives.
     */
    @Bean
    com.finapp.ledger.HoldService holdService(
            LedgerAccountStore<Connection> ledgerAccountStore,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator ids,
            Clock clock) {
        return new com.finapp.ledger.HoldService(
                ledgerAccountStore,
                new com.finapp.ledger.JdbcBalanceDerivation(),
                new com.finapp.ledger.JdbcHoldStore(),
                new com.finapp.ledger.JdbcBalanceProjection(),
                auditWriter,
                outboxWriter,
                ids,
                clock);
    }

    /**
     * The one shared outcome application (`P5-TSK-013`, ADR-0047 §4): the synchronous Tx2s,
     * the webhook resolver and the sweeper (`P5-TSK-014`) all apply judgements through this
     * single instance — unconditional, because it calls no provider and holds no state.
     */
    @Bean
    com.finapp.payments.PaymentOutcomes paymentOutcomes(
            PaymentIntentStore<Connection> paymentIntentStore,
            PaymentAttemptStore<Connection> paymentAttemptStore,
            com.finapp.payments.RefundStore<Connection> refundStore,
            com.finapp.ledger.HoldService holdService,
            com.finapp.ledger.PostingService postingService,
            com.finapp.ledger.LedgerAccountStore<Connection> ledgerAccountStore,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator ids,
            Clock clock) {
        return new com.finapp.payments.PaymentOutcomes(
                paymentIntentStore,
                paymentAttemptStore,
                refundStore,
                holdService,
                postingService,
                new com.finapp.ledger.ChartOfAccounts<>(ledgerAccountStore),
                auditWriter,
                outboxWriter,
                ids,
                clock);
    }

    @Bean
    @ConditionalOnProperty("finapp.payments.provider.url")
    PaymentConfirmation paymentConfirmation(
            TransactionRunner paymentTransactionRunner,
            PaymentIntentStore<Connection> paymentIntentStore,
            PaymentAttemptStore<Connection> paymentAttemptStore,
            ProviderEvidenceStore<Connection> providerEvidenceStore,
            PaymentParticipants<Connection> paymentParticipants,
            PaymentProvider paymentProvider,
            com.finapp.payments.PaymentOutcomes paymentOutcomes,
            AuditWriter<Connection> auditWriter,
            IdGenerator ids,
            Clock clock) {
        return new PaymentConfirmation(
                paymentTransactionRunner,
                paymentIntentStore,
                paymentAttemptStore,
                providerEvidenceStore,
                paymentParticipants,
                paymentProvider,
                paymentOutcomes,
                auditWriter,
                ids,
                clock);
    }

    @Bean
    @ConditionalOnProperty("finapp.payments.provider.url")
    com.finapp.payments.PaymentCapture paymentCapture(
            TransactionRunner paymentTransactionRunner,
            PaymentIntentStore<Connection> paymentIntentStore,
            PaymentAttemptStore<Connection> paymentAttemptStore,
            ProviderEvidenceStore<Connection> providerEvidenceStore,
            PaymentProvider paymentProvider,
            com.finapp.payments.PaymentOutcomes paymentOutcomes,
            AuditWriter<Connection> auditWriter,
            IdGenerator ids,
            Clock clock) {
        return new com.finapp.payments.PaymentCapture(
                paymentTransactionRunner,
                paymentIntentStore,
                paymentAttemptStore,
                providerEvidenceStore,
                paymentProvider,
                paymentOutcomes,
                auditWriter,
                ids,
                clock);
    }

    /**
     * The HTTP slice (`P5-TSK-011`) — the `P5-TSK-003` licence's named consumer arriving. The
     * confirmation and capture arrive as {@code ObjectProvider}s because they exist only where
     * a provider endpoint is configured; the service answers the honest 503 for their absence
     * (the recorded decision above).
     */
    @Bean
    PaymentService paymentService(
            PaymentCreation paymentCreation,
            PaymentCancellation paymentCancellation,
            org.springframework.beans.factory.ObjectProvider<PaymentConfirmation>
                    paymentConfirmation,
            org.springframework.beans.factory.ObjectProvider<com.finapp.payments.PaymentCapture>
                    paymentCapture,
            org.springframework.beans.factory.ObjectProvider<com.finapp.payments.PaymentRefund>
                    paymentRefundCommand,
            PaymentIntentStore<Connection> paymentIntentStore,
            PaymentAttemptStore<Connection> paymentAttemptStore,
            com.finapp.identity.IdentityStore<Connection> identityStore,
            TransactionTemplate paymentTransactions,
            DataSource dataSource) {
        return new PaymentService(
                paymentCreation,
                paymentCancellation,
                paymentConfirmation,
                paymentCapture,
                paymentRefundCommand,
                paymentIntentStore,
                paymentAttemptStore,
                identityStore,
                paymentTransactions,
                dataSource);
    }

    /**
     * The refund command (`P5-TSK-015`): hold, then post — provider-conditional like every
     * consumer of the wire.
     */
    @Bean
    @ConditionalOnProperty("finapp.payments.provider.url")
    com.finapp.payments.PaymentRefund paymentRefund(
            TransactionRunner paymentTransactionRunner,
            IdempotentExecutor idempotentExecutor,
            PaymentIntentStore<Connection> paymentIntentStore,
            PaymentAttemptStore<Connection> paymentAttemptStore,
            com.finapp.payments.RefundStore<Connection> refundStore,
            ProviderEvidenceStore<Connection> providerEvidenceStore,
            com.finapp.ledger.HoldService holdService,
            PaymentProvider paymentProvider,
            com.finapp.payments.PaymentOutcomes paymentOutcomes,
            AuditWriter<Connection> auditWriter,
            IdGenerator ids,
            Clock clock) {
        return new com.finapp.payments.PaymentRefund(
                paymentTransactionRunner,
                idempotentExecutor,
                paymentIntentStore,
                paymentAttemptStore,
                refundStore,
                providerEvidenceStore,
                holdService,
                paymentProvider,
                paymentOutcomes,
                auditWriter,
                ids,
                clock);
    }

    /**
     * The webhook signature verifier (`P5-TSK-012`, ADR-0047 §1) — credential seven through
     * the confinement, the freshness tolerance server-clock judged. Conditional with the
     * door: a deployment with no provider receives no webhooks.
     */
    @Bean
    @ConditionalOnProperty("finapp.payments.provider.url")
    com.finapp.payments.WebhookSignature webhookSignature(
            @Value("${finapp.payments.webhook.key:" + com.finapp.app.mfa.MfaKey.MARKED_LOCAL_DEFAULT + "}")
                    String configuredKey,
            @Value("${finapp.payments.webhook.tolerance:PT5M}") java.time.Duration tolerance,
            Environment environment,
            Clock clock) {
        boolean loopback = DatabaseEndpoint.isEntirelyLoopback(DatabaseEndpoint.url(environment));
        return new com.finapp.payments.WebhookSignature(
                PaymentWebhookKey.decode(configuredKey, loopback), tolerance, clock);
    }

    @Bean
    @ConditionalOnProperty("finapp.payments.provider.url")
    PaymentWebhookService paymentWebhookService(
            com.finapp.payments.WebhookSignature webhookSignature,
            ProviderEvidenceStore<Connection> providerEvidenceStore,
            PaymentAttemptStore<Connection> paymentAttemptStore,
            PaymentIntentStore<Connection> paymentIntentStore,
            com.finapp.payments.PaymentOutcomes paymentOutcomes,
            com.finapp.platform.inbox.InboxConsumer<Connection> inboxConsumer,
            tools.jackson.databind.ObjectMapper objectMapper,
            Clock clock,
            TransactionTemplate paymentTransactions,
            DataSource dataSource) {
        return new PaymentWebhookService(
                webhookSignature,
                providerEvidenceStore,
                paymentAttemptStore,
                paymentIntentStore,
                paymentOutcomes,
                inboxConsumer,
                objectMapper,
                clock,
                paymentTransactions,
                dataSource);
    }

    /**
     * The reconciliation-by-query sweeper (`P5-TSK-014`) — provider-conditional like every
     * consumer of the wire. Bounds explicit with documented defaults: a dispatch younger than
     * {@code dispatched-age} is probably mid-call and left alone; an {@code *_UNKNOWN} is
     * asked about after {@code unknown-age}. Server-clock judged (ADR-0014).
     */
    @Bean
    @ConditionalOnProperty("finapp.payments.provider.url")
    com.finapp.payments.PaymentSweeper paymentSweeper(
            TransactionRunner paymentTransactionRunner,
            PaymentAttemptStore<Connection> paymentAttemptStore,
            PaymentIntentStore<Connection> paymentIntentStore,
            ProviderEvidenceStore<Connection> providerEvidenceStore,
            PaymentProvider paymentProvider,
            com.finapp.payments.PaymentOutcomes paymentOutcomes,
            IdGenerator ids,
            Clock clock,
            @Value("${finapp.payments.sweeper.dispatched-age:PT10M}")
                    java.time.Duration dispatchedAge,
            @Value("${finapp.payments.sweeper.unknown-age:PT1M}") java.time.Duration unknownAge,
            @Value("${finapp.payments.sweeper.batch:50}") int batchSize) {
        return new com.finapp.payments.PaymentSweeper(
                paymentTransactionRunner,
                paymentAttemptStore,
                paymentIntentStore,
                providerEvidenceStore,
                paymentProvider,
                paymentOutcomes,
                ids,
                clock,
                dispatchedAge,
                unknownAge,
                batchSize);
    }

    /**
     * The schedule — the relay-gate shape for the identical reason: a background worker
     * resolving payments under every {@code @SpringBootTest} would race assertions, so the
     * app test overlay disables it and the database suite drives {@code sweep()} directly.
     * {@code matchIfMissing = true}: a deployment that says nothing gets the sweeper, because
     * a deployment that forgets it strands every ambiguous payment forever.
     */
    @Bean
    @ConditionalOnProperty(
            name = "finapp.payments.sweeper.enabled",
            havingValue = "true",
            matchIfMissing = true)
    // The provider half of the condition rides on the sweeper bean itself (declared above,
    // provider-conditional): no provider, no sweeper, no schedule.
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(
            com.finapp.payments.PaymentSweeper.class)
    PaymentSweeperSchedule paymentSweeperSchedule(
            com.finapp.payments.PaymentSweeper paymentSweeper,
            @Value("${finapp.payments.sweeper.poll-interval:PT30S}")
                    java.time.Duration pollInterval) {
        return new PaymentSweeperSchedule(paymentSweeper, pollInterval);
    }

    @Bean
    PaymentCancellation paymentCancellation(
            PaymentIntentStore<Connection> paymentIntentStore,
            AuditWriter<Connection> auditWriter,
            IdGenerator ids,
            Clock clock) {
        return new PaymentCancellation(paymentIntentStore, auditWriter, ids, clock);
    }
}
