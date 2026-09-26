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
     *
     * <p><strong>Wrapped in the metering decorator</strong> (`P5-TSK-017`): every provider
     * call is timed by provider and operation at the one interface they all pass through,
     * with the exception propagating unchanged. Wrapping HERE rather than inside the adapter
     * is what keeps {@code payments} free of a metrics library, and the decorator implements
     * the port, so a fifth provider method cannot be added without a compiler error naming
     * the decision.
     */
    @Bean
    @ConditionalOnProperty("finapp.payments.provider.url")
    PaymentProvider paymentProvider(
            @Value("${finapp.payments.provider.url}") java.net.URI url,
            @Value("${finapp.payments.provider.timeout:PT2S}") java.time.Duration timeout,
            @Value("${finapp.payments.provider.key:" + com.finapp.app.mfa.MfaKey.MARKED_LOCAL_DEFAULT + "}")
                    String configuredKey,
            Environment environment,
            com.finapp.app.telemetry.PaymentMeters paymentMeters,
            Clock clock) {
        boolean loopback = DatabaseEndpoint.isEntirelyLoopback(DatabaseEndpoint.url(environment));
        return new com.finapp.app.telemetry.MeteredPaymentProvider(
                new SimulatedCardPspAdapter(
                        url, timeout, ProviderApiKey.decode(configuredKey, loopback)),
                paymentMeters,
                clock);
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
                clock,
                PaymentCreation.IDEMPOTENCY_SCOPE);
    }

    @Bean
    com.finapp.payments.RefundStore<Connection> refundStore() {
        return new com.finapp.payments.JdbcRefundStore();
    }

    /**
     * The rails this build declares (`P7-TSK-001`, ADR-0059 §1) — deliberately
     * <strong>unconditional</strong>: the declaration is code, not deployment configuration,
     * so an outcome resolver can key a stored rail back into capabilities even where no
     * provider endpoint is configured. Which rails are LIVE is routing's recorded database
     * fact when it exists (`P7-TSK-003`, ADR-0060), never this directory's.
     */
    @Bean
    com.finapp.payments.PaymentRails paymentRails() {
        return com.finapp.payments.PaymentRails.of(
                java.util.List.of(
                        SimulatedCardPspAdapter.RAIL,
                        com.finapp.payments.SimulatedInstantSchemeAdapter.RAIL));
    }

    /**
     * The instant scheme adapter (`P7-TSK-006`, ADR-0062 §1) — wired when
     * configured: the bean is what makes the confined credential a property
     * the application really reads, and the port a thing an operator can point at an
     * environment. Metered from `P7-TSK-008` on (the flow the `P7-TSK-006` javadoc said the
     * meters would arrive with): {@code send} is the {@code WITHDRAW} operation, the
     * inquiries {@code QUERY} — {@code MeteredPaymentProvider}'s shape at the second port.
     */
    @Bean
    @ConditionalOnProperty("finapp.payments.instant.url")
    com.finapp.payments.PushRail instantRail(
            @Value("${finapp.payments.instant.url}") java.net.URI url,
            @Value("${finapp.payments.instant.timeout:PT2S}") java.time.Duration timeout,
            @Value("${finapp.payments.instant.key:" + com.finapp.app.mfa.MfaKey.MARKED_LOCAL_DEFAULT + "}")
                    String configuredKey,
            Environment environment,
            com.finapp.app.telemetry.PaymentMeters paymentMeters,
            Clock clock) {
        boolean loopback = DatabaseEndpoint.isEntirelyLoopback(DatabaseEndpoint.url(environment));
        return new com.finapp.app.telemetry.MeteredPushRail(
                new com.finapp.payments.SimulatedInstantSchemeAdapter(
                        url, timeout, InstantSchemeKey.decode(configuredKey, loopback)),
                paymentMeters,
                clock);
    }

    // ------------------------------------------------------------------
    // The wallet withdrawal (P7-TSK-008, ADR-0062 §6).
    // ------------------------------------------------------------------

    @Bean
    com.finapp.payments.WithdrawalStore<Connection> withdrawalStore() {
        return new com.finapp.payments.JdbcWithdrawalStore();
    }

    @Bean
    com.finapp.payments.WithdrawalOutcomes withdrawalOutcomes(
            com.finapp.payments.WithdrawalStore<Connection> withdrawalStore,
            com.finapp.ledger.HoldService holdService,
            com.finapp.ledger.PostingService postingService,
            com.finapp.ledger.LedgerAccountStore<Connection> ledgerAccountStore,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator ids,
            Clock clock) {
        return new com.finapp.payments.WithdrawalOutcomes(
                withdrawalStore,
                holdService,
                postingService,
                new com.finapp.ledger.ChartOfAccounts<>(ledgerAccountStore),
                auditWriter,
                outboxWriter,
                ids,
                clock);
    }

    /**
     * The withdrawal command engine — present exactly when the push rail is
     * ({@code finapp.payments.instant.url}): {@link WithdrawalService} takes an
     * {@code ObjectProvider} and answers the honest {@code payments.ProviderUnavailable}
     * 503 on an unconfigured deployment (the tokenisation contract's reasoning).
     */
    @Bean
    @ConditionalOnProperty("finapp.payments.instant.url")
    com.finapp.payments.Withdrawals withdrawals(
            com.finapp.payments.WithdrawalStore<Connection> withdrawalStore,
            com.finapp.payments.WithdrawalOutcomes withdrawalOutcomes,
            com.finapp.ledger.HoldService holdService,
            com.finapp.payments.RoutingStore<Connection> routingStore,
            com.finapp.payments.PaymentRails paymentRails,
            com.finapp.payments.PushRail instantRail,
            ProviderEvidenceStore<Connection> providerEvidenceStore,
            IdempotentExecutor idempotentExecutor,
            AuditWriter<Connection> auditWriter,
            IdGenerator ids,
            Clock clock,
            TransactionRunner paymentTransactionRunner) {
        return new com.finapp.payments.Withdrawals(
                withdrawalStore,
                withdrawalOutcomes,
                holdService,
                routingStore,
                paymentRails,
                instantRail,
                com.finapp.payments.SimulatedInstantSchemeAdapter.RAIL.id(),
                providerEvidenceStore,
                idempotentExecutor,
                auditWriter,
                ids,
                clock,
                paymentTransactionRunner);
    }

    @Bean
    WithdrawalService withdrawalService(
            org.springframework.beans.factory.ObjectProvider<com.finapp.payments.Withdrawals>
                    withdrawals,
            com.finapp.payments.PaymentParticipants<Connection> paymentParticipants,
            com.finapp.payments.WithdrawalStore<Connection> withdrawalStore,
            com.finapp.identity.MfaEnrolmentStore<Connection> mfaEnrolmentStore,
            com.finapp.identity.IdentityStore<Connection> identityStore,
            TransactionRunner paymentTransactionRunner) {
        return new WithdrawalService(
                withdrawals,
                paymentParticipants,
                withdrawalStore,
                mfaEnrolmentStore,
                identityStore,
                paymentTransactionRunner);
    }

    /**
     * The inquiry sweep and its schedule (`P7-TSK-008`, ADR-0062 §3) — present with the
     * rail, leaderless on every instance. The margin rides ON TOP of the rail's DECLARED
     * outcome deadline; a zero bound is refused at construction (ADR-0057 §4's rule).
     */
    @Bean
    @ConditionalOnProperty("finapp.payments.instant.url")
    com.finapp.payments.WithdrawalResolution withdrawalResolution(
            com.finapp.payments.WithdrawalStore<Connection> withdrawalStore,
            com.finapp.payments.WithdrawalOutcomes withdrawalOutcomes,
            com.finapp.payments.PushRail instantRail,
            com.finapp.payments.PaymentRails paymentRails,
            ProviderEvidenceStore<Connection> providerEvidenceStore,
            @Value("${finapp.payments.withdrawal.sweeper.dispatched-age:PT10M}")
                    java.time.Duration dispatchedAge,
            @Value("${finapp.payments.withdrawal.sweeper.unknown-age:PT2M}")
                    java.time.Duration unknownAge,
            @Value("${finapp.payments.withdrawal.sweeper.margin:PT5M}")
                    java.time.Duration margin,
            @Value("${finapp.payments.withdrawal.sweeper.batch:25}") int batchSize,
            IdGenerator ids,
            Clock clock,
            TransactionRunner paymentTransactionRunner) {
        return new com.finapp.payments.WithdrawalResolution(
                withdrawalStore,
                withdrawalOutcomes,
                instantRail,
                paymentRails,
                com.finapp.payments.SimulatedInstantSchemeAdapter.RAIL.id(),
                providerEvidenceStore,
                new com.finapp.payments.WithdrawalResolution.Config(
                        dispatchedAge, unknownAge, margin, batchSize),
                ids,
                clock,
                paymentTransactionRunner);
    }

    @Bean
    @ConditionalOnProperty("finapp.payments.instant.url")
    WithdrawalResolutionSchedule withdrawalResolutionSchedule(
            com.finapp.payments.WithdrawalResolution withdrawalResolution,
            @Value("${finapp.payments.withdrawal.sweeper.poll:PT30S}")
                    java.time.Duration pollInterval) {
        return new WithdrawalResolutionSchedule(withdrawalResolution, pollInterval);
    }

    @Bean
    com.finapp.payments.RoutingStore<Connection> routingStore() {
        return new com.finapp.payments.JdbcRoutingStore();
    }

    /**
     * The operator's routing acts (`P7-TSK-003`, ADR-0060): version creation, availability,
     * the explanation read - each surfaced under {@code PAYMENT_ROUTING_ADMINISTER}.
     */
    @Bean
    com.finapp.payments.RoutingAdministration routingAdministration(
            IdempotentExecutor idempotentExecutor,
            com.finapp.payments.RoutingStore<Connection> routingStore,
            com.finapp.payments.PaymentRails paymentRails,
            AuditWriter<Connection> auditWriter,
            IdGenerator ids,
            Clock clock) {
        return new com.finapp.payments.RoutingAdministration(
                idempotentExecutor, routingStore, paymentRails, auditWriter, ids, clock);
    }

    /**
     * The decision meter behind the routing port (`P7-TSK-003`): tags are bounded enums -
     * the declared rail names and {@code RoutingRejection} - so cardinality is the
     * vocabulary's. Telemetry only; the counts of record are the decision rows.
     */
    @Bean
    RoutingPolicyOperations routingPolicyOperations(
            com.finapp.payments.RoutingAdministration routingAdministration,
            org.springframework.transaction.support.TransactionTemplate paymentTransactions,
            javax.sql.DataSource dataSource) {
        return new RoutingPolicyOperations(
                routingAdministration, paymentTransactions, dataSource);
    }

    @Bean
    com.finapp.payments.RoutingTelemetry routingTelemetry(
            com.finapp.app.telemetry.PaymentMeters paymentMeters) {
        return (chosen, leadingRejection) ->
                paymentMeters.routingDecision(
                        chosen.map(com.finapp.payments.RailId::value).orElse("none"),
                        chosen.isPresent()
                                ? "CHOSEN"
                                : leadingRejection
                                        .map(Enum::name)
                                        .orElse("NO_RULE_MATCHED"));
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
            // THE COMPOSITION SEAM (P6-TSK-005, ADR-0050 section 6). payments cannot see
            // merchant and must not; the bean injected here is app's join, and it is the only
            // reason a capture can settle four lines instead of two.
            com.finapp.payments.CaptureComposition<Connection> captureComposition,
            // AND ITS MIRROR (P6-TSK-014): the refund got its seam a task later, which is why
            // refundFeePolicy spent a milestone pinned and read by nothing.
            com.finapp.payments.RefundComposition<Connection> refundComposition,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator ids,
            Clock clock,
            com.finapp.payments.PaymentRails paymentRails) {
        return new com.finapp.payments.PaymentOutcomes(
                paymentIntentStore,
                paymentAttemptStore,
                refundStore,
                holdService,
                postingService,
                new com.finapp.ledger.ChartOfAccounts<>(ledgerAccountStore),
                captureComposition,
                refundComposition,
                auditWriter,
                outboxWriter,
                ids,
                clock,
                paymentRails);
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
            com.finapp.payments.RoutingStore<Connection> routingStore,
            com.finapp.payments.PaymentRails paymentRails,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator ids,
            Clock clock,
            com.finapp.payments.RoutingTelemetry routingTelemetry) {
        return new PaymentConfirmation(
                paymentTransactionRunner,
                paymentIntentStore,
                paymentAttemptStore,
                providerEvidenceStore,
                paymentParticipants,
                paymentProvider,
                paymentOutcomes,
                // The rail is routing's per-payment decision now (P7-TSK-003, ADR-0060):
                // the version in force over stored inputs, pinned in Tx1.
                routingStore,
                paymentRails,
                auditWriter,
                outboxWriter,
                ids,
                clock,
                routingTelemetry);
    }

    /**
     * The void command (`P7-TSK-004`): the card rail's declared reversal, ADR-0046's
     * choreography. Conditional like every provider-calling command; the capability check
     * ({@code INV-REV-03}) is the command's own, from the stored rail's declaration.
     */
    @Bean
    @ConditionalOnProperty("finapp.payments.provider.url")
    com.finapp.payments.PaymentVoid paymentVoid(
            TransactionRunner paymentTransactionRunner,
            PaymentIntentStore<Connection> paymentIntentStore,
            PaymentAttemptStore<Connection> paymentAttemptStore,
            ProviderEvidenceStore<Connection> providerEvidenceStore,
            PaymentProvider paymentProvider,
            com.finapp.payments.PaymentOutcomes paymentOutcomes,
            com.finapp.payments.PaymentRails paymentRails,
            AuditWriter<Connection> auditWriter,
            IdGenerator ids,
            Clock clock) {
        return new com.finapp.payments.PaymentVoid(
                paymentTransactionRunner,
                paymentIntentStore,
                paymentAttemptStore,
                providerEvidenceStore,
                paymentProvider,
                paymentOutcomes,
                paymentRails,
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
            com.finapp.payments.PaymentVoid paymentVoid,
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
                paymentVoid,
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
            org.springframework.beans.factory.ObjectProvider<com.finapp.payments.PaymentVoid>
                    paymentVoid,
            org.springframework.beans.factory.ObjectProvider<com.finapp.payments.PaymentRefund>
                    paymentRefundCommand,
            PaymentIntentStore<Connection> paymentIntentStore,
            PaymentAttemptStore<Connection> paymentAttemptStore,
            com.finapp.payments.RefundStore<Connection> refundStore,
            com.finapp.app.telemetry.PaymentMeters paymentMeters,
            com.finapp.identity.IdentityStore<Connection> identityStore,
            TransactionTemplate paymentTransactions,
            DataSource dataSource) {
        return new PaymentService(
                paymentCreation,
                paymentCancellation,
                paymentConfirmation,
                paymentCapture,
                paymentVoid,
                paymentRefundCommand,
                paymentIntentStore,
                paymentAttemptStore,
                refundStore,
                paymentMeters,
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
            Clock clock,
            com.finapp.payments.PaymentRails paymentRails) {
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
                clock,
                paymentRails);
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
            com.finapp.payments.RefundStore<Connection> refundStore,
            com.finapp.app.telemetry.PaymentMeters paymentMeters,
            com.finapp.payments.PaymentOutcomes paymentOutcomes,
            com.finapp.platform.inbox.InboxConsumer<Connection> inboxConsumer,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            tools.jackson.databind.ObjectMapper objectMapper,
            Clock clock,
            TransactionTemplate paymentTransactions,
            DataSource dataSource) {
        return new PaymentWebhookService(
                webhookSignature,
                providerEvidenceStore,
                paymentAttemptStore,
                paymentIntentStore,
                refundStore,
                paymentMeters,
                paymentOutcomes,
                // The clearing recorder (P7-TSK-005): the door's non-transition effect,
                // over the same outbox the outcome events travel.
                new com.finapp.payments.PaymentClearing(
                        new com.finapp.payments.JdbcClearingRecordStore(),
                        outboxWriter,
                        idGenerator,
                        clock),
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
     * asked about after {@code unknown-age}. Server-clock judged (ADR-0014). Both must be
     * positive: either licenses a {@code NEVER_RECEIVED} conclusion, and at zero the sweep
     * could draw it about a request still in flight (the Phase 6 → 7 transition). Since that
     * transition it also chains the capture of a stranded authorization and resolves refunds.
     */
    @Bean
    @ConditionalOnProperty("finapp.payments.provider.url")
    com.finapp.payments.PaymentSweeper paymentSweeper(
            TransactionRunner paymentTransactionRunner,
            PaymentAttemptStore<Connection> paymentAttemptStore,
            PaymentIntentStore<Connection> paymentIntentStore,
            com.finapp.payments.RefundStore<Connection> refundStore,
            ProviderEvidenceStore<Connection> providerEvidenceStore,
            PaymentProvider paymentProvider,
            com.finapp.payments.PaymentOutcomes paymentOutcomes,
            com.finapp.payments.PaymentCapture paymentCapture,
            com.finapp.payments.PaymentVoid paymentVoid,
            IdGenerator ids,
            Clock clock,
            @Value(PaymentSweeperSchedule.DISPATCHED_AGE) java.time.Duration dispatchedAge,
            @Value("${finapp.payments.sweeper.unknown-age:PT1M}") java.time.Duration unknownAge,
            @Value("${finapp.payments.sweeper.batch:50}") int batchSize) {
        return new com.finapp.payments.PaymentSweeper(
                paymentTransactionRunner,
                paymentAttemptStore,
                paymentIntentStore,
                refundStore,
                providerEvidenceStore,
                paymentProvider,
                paymentOutcomes,
                paymentCapture,
                paymentVoid,
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
            com.finapp.app.telemetry.PaymentMeters paymentMeters,
            @Value("${finapp.payments.sweeper.poll-interval:PT30S}")
                    java.time.Duration pollInterval) {
        return new PaymentSweeperSchedule(paymentSweeper, paymentMeters, pollInterval);
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
