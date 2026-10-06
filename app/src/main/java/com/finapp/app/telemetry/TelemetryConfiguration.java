package com.finapp.app.telemetry;

import com.finapp.platform.outbox.OutboxBacklog;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import java.time.Clock;
import io.opentelemetry.sdk.trace.SpanProcessor;
import javax.sql.DataSource;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the tracing implementation, which is the composition root's job.
 *
 * <p>{@code platform} records spans through a facade and names the attributes; the decision about
 * <em>what</em> records them — here, the OpenTelemetry SDK that {@code SYSTEM_ARCHITECTURE.md}
 * names — is made once, here. The same split as slf4j: a library that bound an implementation
 * would impose it on every consumer.
 */
@Configuration
class TelemetryConfiguration {

    /**
     * Stamps correlation onto every span the SDK starts.
     *
     * <p>Contributed as a bean rather than installed by hand so it composes with whatever else
     * exports spans, instead of replacing it.
     */
    @Bean
    SpanProcessor correlationSpanProcessor() {
        return new CorrelationSpanProcessor();
    }

    /**
     * The outbox backlog, as gauges (`P0-TSK-029`, paying down the debt `P0-TSK-020` recorded).
     *
     * <p>Reads through the application's own {@code DataSource}, so it measures what the
     * application can actually see - and so it goes through the traced wrapper and the pool the
     * rest of the platform uses, rather than opening a private connection that would report health
     * the application does not have.
     *
     * <p>Depth and age only. Relay throughput, failure and dead-letter counts come from
     * {@code RelayPollResult} and need a relay to be running; nothing schedules one yet, so
     * recording them here would produce meters that are structurally always zero - which reads as
     * "nothing is failing" rather than "nothing is running". Recorded as debt rather than faked.
     */
    @Bean
    OutboxMetrics outboxMetrics(DataSource dataSource, Clock clock, MeterRegistry registry) {
        return new OutboxMetrics(new OutboxBacklog(dataSource::getConnection), clock, registry);
    }

    /**
     * How many sessions are live, as a gauge (`P1-TSK-029`, criterion 6).
     *
     * <p>Reads through the application's own {@code DataSource} for {@code outboxMetrics}' reason:
     * it measures what the application can actually see, through the pool the rest of the platform
     * uses, rather than opening a private connection that would report health the application does
     * not have.
     */
    @Bean
    IdentityMetrics identityMetrics(
            com.finapp.identity.SessionStore<java.sql.Connection> sessionStore,
            DataSource dataSource,
            Clock clock,
            MeterRegistry registry) {
        return new IdentityMetrics(sessionStore, dataSource::getConnection, clock, registry);
    }

    /**
     * The manual-review queue depth, as a gauge (`P2-TSK-010`, `PHASE_2_PLAN.md` §10).
     *
     * <p>Arrives with the queue it measures — the trigger-reached rule: a gauge registered before
     * `P2-TSK-010` would have been structurally always zero, which reads as "nobody is waiting"
     * rather than "nothing exists yet". Same {@code DataSource} reasoning as its siblings.
     */
    @Bean
    KycMetrics kycMetrics(
            com.finapp.kyc.ReviewTaskStore<java.sql.Connection> reviewTaskStore,
            DataSource dataSource,
            Clock clock,
            MeterRegistry registry) {
        return new KycMetrics(reviewTaskStore, dataSource::getConnection, clock, registry);
    }

    /**
     * The open payout destination changes, as a gauge (`P6-TSK-011`, {@code PHASE_6_PLAN.md}
     * §15).
     *
     * <p>Arrives with the flow it measures — the trigger-reached rule, `P2-TSK-010`'s reasoning.
     * Same {@code DataSource} reasoning as its siblings.
     */
    @Bean
    MerchantMetrics merchantMetrics(
            com.finapp.merchant.PayoutDestinationStore<java.sql.Connection> payoutDestinationStore,
            DataSource dataSource,
            Clock clock,
            MeterRegistry registry) {
        return new MerchantMetrics(payoutDestinationStore, dataSource::getConnection, clock, registry);
    }

    /**
     * The balance-projection drift, as a gauge (`P3-TSK-010`, ADR-0041 rule 2).
     *
     * <p>The verification job's whole schedule is this gauge's cache floor: a scrape past the
     * floor recomputes every balance from postings and compares — read-only, idempotent, no
     * leader, no ambient schedule, so no {@code DISTRIBUTED_EXECUTION.md} §3 question arises.
     * Same {@code DataSource} reasoning as its siblings: it verifies what the application can
     * actually see, through the pool the writes go through.
     */
    @Bean
    LedgerMetrics ledgerMetrics(DataSource dataSource, Clock clock, MeterRegistry registry) {
        com.finapp.ledger.ProjectionVerification verification =
                new com.finapp.ledger.ProjectionVerification(
                        new com.finapp.ledger.JdbcBalanceDerivation());
        // P3-TSK-019: the trial balance rides the same scrape-is-the-schedule stance - one
        // read-only sweep per cache floor, no leader, no ambient schedule, no §3 question.
        com.finapp.ledger.TrialBalance trialBalance = new com.finapp.ledger.TrialBalance();
        // P3-TSK-020: the hold count - constructed here like its siblings, because the
        // hold store has no bean until something beyond the gauge consumes one.
        com.finapp.ledger.HoldStore<java.sql.Connection> holdStore =
                new com.finapp.ledger.JdbcHoldStore();
        return new LedgerMetrics(
                verification::verify,
                trialBalance::sweep,
                // P3-TSK-020: the hold gauge joins, at the cheap-read floor.
                holdStore::countActive,
                dataSource::getConnection,
                clock,
                registry);
    }

    /**
     * The negative-position gauge (`P7-TSK-013`, ADR-0061 §5, plan §15): counterparties below
     * zero after a chargeback — merchant debt and customer receivables — counted from the
     * projection at the cheap-read floor; the scrape is the schedule, no leader, no §3 row.
     */
    @Bean
    NegativePositionMetrics negativePositionMetrics(
            DataSource dataSource, Clock clock, MeterRegistry registry) {
        com.finapp.ledger.NegativePositions<java.sql.Connection> positions =
                new com.finapp.ledger.JdbcNegativePositions();
        return new NegativePositionMetrics(
                positions::countBelowZero, dataSource::getConnection, clock, registry);
    }

    /**
     * The deadline alarm (`P7-TSK-014`, ADR-0061 §7): chargebacks near or past the network's
     * respond-by date with no answer the PSP took — read from the dispute rows at the
     * cheap-read floor; the scrape is the schedule, no leader, no §3 row.
     */
    @Bean
    DisputeDeadlineMetrics disputeDeadlineMetrics(
            DataSource dataSource,
            Clock clock,
            @org.springframework.beans.factory.annotation.Value(
                            "${finapp.payments.dispute.deadline-alarm-window:P3D}")
                    java.time.Duration window,
            MeterRegistry registry) {
        com.finapp.payments.DisputeStore<java.sql.Connection> disputes =
                new com.finapp.payments.JdbcDisputeStore();
        return new DisputeDeadlineMetrics(
                disputes::countDeadlinesNear, dataSource::getConnection, clock, window, registry);
    }

    /**
     * The dispute stage gauges (`P7-TSK-015`, plan §15): every dispute's stage visible, one
     * floored {@code GROUP BY} over the dispute rows; the scrape is the schedule, no leader, no
     * §3 row.
     */
    @Bean
    DisputeStageMetrics disputeStageMetrics(
            DataSource dataSource, Clock clock, MeterRegistry registry) {
        com.finapp.payments.DisputeStore<java.sql.Connection> disputes =
                new com.finapp.payments.JdbcDisputeStore();
        return new DisputeStageMetrics(
                disputes::countByStage, dataSource::getConnection, clock, registry);
    }

    /**
     * The stuck-withdrawal gauges (`P7-TSK-015`, plan §15), in the payout's shape: over the
     * withdrawal sweep's own dispatched bound, read through the one placeholder the sweep reads,
     * so the two can never disagree about when an answer was due. Unconditional — the store is.
     */
    @Bean
    StuckOperationMetrics withdrawalMetrics(
            DataSource dataSource,
            Clock clock,
            MeterRegistry registry,
            @org.springframework.beans.factory.annotation.Value(
                            com.finapp.app.payments.WithdrawalResolutionSchedule.DISPATCHED_AGE)
                    java.time.Duration dispatchedAge) {
        com.finapp.payments.WithdrawalStore<java.sql.Connection> withdrawals =
                new com.finapp.payments.JdbcWithdrawalStore();
        return new StuckOperationMetrics(
                "stuck-withdrawal",
                new StuckOperationMetrics.Series(
                        "finapp.payments.withdrawal.unknown.active",
                        "Withdrawals the platform has no answer for past the point one was due:"
                                + " every UNKNOWN withdrawal, and every DISPATCHED one whose send"
                                + " permit is older than the resolution sweep's own bound. Each"
                                + " is a customer's money behind a standing hold. A count, never"
                                + " an amount. NaN when unreadable, never zero. Fleet-wide:"
                                + " aggregate with max(), never sum()",
                        "finapp.payments.withdrawal.unknown.age",
                        "Seconds the OLDEST unanswered withdrawal has waited, measured the way"
                                + " the resolution sweep measures it: an UNKNOWN one from its"
                                + " entry into that state, an overdue DISPATCHED one from its"
                                + " latest send permit. The stuck-withdrawal alert's series. NaN"
                                + " when unreadable, never zero. Fleet-wide: aggregate with"
                                + " max(), never sum()"),
                connection -> withdrawals.unknownReading(connection, dispatchedAge),
                dataSource::getConnection,
                clock,
                registry);
    }

    /**
     * The stuck-outbound-credit gauges (`P9-TSK-020`, {@code INV-LIFE-03}): over the resolution sweep's own
     * dispatched bound, read through the one placeholder the sweep reads. Unconditional - the store is.
     */
    @Bean
    StuckOperationMetrics outboundCreditMetrics(
            DataSource dataSource,
            Clock clock,
            MeterRegistry registry,
            @org.springframework.beans.factory.annotation.Value(
                            com.finapp.app.payments.OutboundCreditResolutionSchedule.DISPATCHED_AGE)
                    java.time.Duration dispatchedAge) {
        com.finapp.payments.OutboundCreditStore credits = new com.finapp.payments.JdbcOutboundCreditStore();
        return new StuckOperationMetrics(
                "stuck-outbound-credit",
                new StuckOperationMetrics.Series(
                        "finapp.payments.outbound.unknown.active",
                        "Cross-border outbound credits the platform has no answer for past the point one was due:"
                                + " every UNKNOWN credit, and every DISPATCHED one whose send permit is older than the"
                                + " resolution sweep's own bound. Each is a customer's money behind a standing hold. A"
                                + " count, never an amount. NaN when unreadable, never zero. Fleet-wide: aggregate with"
                                + " max(), never sum()",
                        "finapp.payments.outbound.unknown.age",
                        "Seconds the OLDEST unanswered outbound credit has waited since its latest send permit. The"
                                + " stuck-outbound-credit alert's series. NaN when unreadable, never zero. Fleet-wide:"
                                + " aggregate with max(), never sum()"),
                connection -> credits.unknownReading(connection, dispatchedAge),
                dataSource::getConnection,
                clock,
                registry);
    }

    /**
     * The received-outbound-credit gauges (`P9-TSK-020`): credits the corridor provider acknowledged but has not
     * committed to - its own screening pending - and the oldest one's wait. Unconditional - the store is.
     */
    @Bean
    StuckOperationMetrics receivedOutboundCreditMetrics(DataSource dataSource, Clock clock, MeterRegistry registry) {
        com.finapp.payments.OutboundCreditStore credits = new com.finapp.payments.JdbcOutboundCreditStore();
        return new StuckOperationMetrics(
                "received-outbound-credit",
                new StuckOperationMetrics.Series(
                        "finapp.payments.outbound.received.active",
                        "Cross-border outbound credits the corridor provider holds RECEIVED - acknowledged, not yet"
                                + " committed. A count, never an amount. NaN when unreadable, never zero. Fleet-wide:"
                                + " aggregate with max(), never sum()",
                        "finapp.payments.outbound.received.age",
                        "Seconds the OLDEST RECEIVED outbound credit has waited since its latest send permit. NaN when"
                                + " unreadable, never zero. Fleet-wide: aggregate with max(), never sum()"),
                credits::receivedReading,
                dataSource::getConnection,
                clock,
                registry);
    }

    /**
     * The stuck-dispute-answer gauges (`P7-TSK-015`): {@code INV-LIFE-03}'s own "unknown-state age
     * metric" for the response machine `P7-TSK-014` added, over the response sweep's dispatched
     * bound through its one placeholder.
     */
    @Bean
    StuckOperationMetrics disputeResponseMetrics(
            DataSource dataSource,
            Clock clock,
            MeterRegistry registry,
            @org.springframework.beans.factory.annotation.Value(
                            com.finapp.app.payments.DisputeResponseResolutionSchedule
                                    .DISPATCHED_AGE)
                    java.time.Duration dispatchedAge) {
        com.finapp.payments.DisputeResponseStore<java.sql.Connection> responses =
                new com.finapp.payments.JdbcDisputeResponseStore();
        return new StuckOperationMetrics(
                "stuck-dispute-response",
                new StuckOperationMetrics.Series(
                        "finapp.payments.dispute.response.unknown.active",
                        "Dispute answers the platform has no word from the PSP for past the point"
                                + " one was due: every UNKNOWN response, and every DISPATCHED one"
                                + " whose send permit is older than the resolution sweep's own"
                                + " bound - an answer the network's deadline may overtake. A"
                                + " count, never an identifier. NaN when unreadable, never zero."
                                + " Fleet-wide: aggregate with max(), never sum()",
                        "finapp.payments.dispute.response.unknown.age",
                        "Seconds the OLDEST unanswered dispute response has waited, measured the"
                                + " way the resolution sweep measures it. NaN when unreadable,"
                                + " never zero. Fleet-wide: aggregate with max(), never sum()"),
                connection -> responses.unknownReading(connection, dispatchedAge),
                dataSource::getConnection,
                clock,
                registry);
    }

    /**
     * The write path's observer (`P3-TSK-020`): counters and the latency timer behind the
     * {@code ledger} module's {@link com.finapp.ledger.PostingObserver} port — published as
     * the port, so wiring that constructs a journal-write command autowires it and the
     * Micrometer class stays package-private here.
     */
    @Bean
    com.finapp.ledger.PostingObserver postingObserver(MeterRegistry registry) {
        return new LedgerWriteMeters(registry);
    }

    /** The account lifecycle counters (`P3-TSK-020`), eager for the same reason. */
    @Bean
    AccountMetrics accountMetrics(MeterRegistry registry) {
        return new AccountMetrics(registry);
    }

    /** The transfer surface's meters (`P4-TSK-011`), eager for the same reason. */
    @Bean
    TransferMetrics transferMetrics(MeterRegistry registry) {
        return new TransferMetrics(registry);
    }

    /**
     * The payment surface's meters (`P5-TSK-017`), eager for the same reason — and
     * <strong>unconditional</strong>, deliberately: the command beans are conditional on a
     * configured provider, but a deployment that has not configured one still publishes
     * healthy zeros rather than absences an alert cannot evaluate (the {@code KycMetrics}
     * precedent, which the pinned planned-meters guard proves by booting with nothing
     * configured at all). The provider tag is the adapter's own compile-time constant. Since
     * `P7-TSK-015` the rail series of every DECLARED rail register here too, from the
     * unconditional rail directory — so they exist before any rail is configured or called.
     */
    @Bean
    PaymentMeters paymentMeters(
            MeterRegistry registry, com.finapp.payments.PaymentRails paymentRails) {
        return new PaymentMeters(
                registry, com.finapp.payments.SimulatedCardPspAdapter.NAME, paymentRails);
    }

    /**
     * Where the three payment appliers report their acting judgements (`P7-TSK-015`) — the
     * {@link com.finapp.payments.RailOutcomeObserver} port, published as the port so the wiring
     * that constructs an applier autowires it (the {@code postingObserver} shape), counting into
     * {@link PaymentMeters} only once each judgement's transaction commits.
     */
    @Bean
    com.finapp.payments.RailOutcomeObserver railOutcomeObserver(PaymentMeters paymentMeters) {
        return new CommittedRailOutcomes(paymentMeters);
    }

    /**
     * The checkout surface's meters (`P6-TSK-008`): how the platform's offers END. Eager and
     * unconditional for the reason above — a deployment with no checkout traffic publishes
     * four healthy zeros rather than four absences, and {@code completed_late} is precisely a
     * number an operator wants to alert on BEFORE it has ever been non-zero.
     */
    @Bean
    CheckoutMeters checkoutMeters(MeterRegistry registry) {
        return new CheckoutMeters(registry);
    }

    /**
     * The merchant surface's counters (`P6-TSK-013`): fee assessments and payout judgements.
     * Eager and <strong>unconditional</strong> for the {@code PaymentMeters} reason: the payout
     * command exists only where a provider is configured, but a deployment without one still
     * publishes healthy zeros rather than absences an alert cannot evaluate.
     */
    @Bean
    MerchantMeters merchantMeters(MeterRegistry registry) {
        return new MerchantMeters(registry);
    }

    /**
     * The stuck-payout gauges (`P6-TSK-013`): the {@code PaymentMetrics} stance verbatim — the
     * scrape is the schedule, one floored read-only aggregate per instance, no leader, nothing
     * written, and NaN rather than a false zero when the database cannot be read — over the
     * sweep's own dispatched bound, read through the one placeholder the sweep reads. The store
     * bean is unconditional, so the gauges exist without a configured provider too.
     */
    @Bean
    MerchantPayoutMetrics merchantPayoutMetrics(
            com.finapp.merchant.MerchantPayoutStore<java.sql.Connection> merchantPayoutStore,
            DataSource dataSource,
            Clock clock,
            MeterRegistry registry,
            @org.springframework.beans.factory.annotation.Value(
                            com.finapp.app.merchant.MerchantPayoutBeans.DISPATCHED_AGE)
                    java.time.Duration dispatchedAge) {
        return new MerchantPayoutMetrics(
                connection -> merchantPayoutStore.unknownReading(connection, dispatchedAge),
                dataSource::getConnection,
                clock,
                registry);
    }

    /**
     * The stuck-payment gauges (`P5-TSK-017`): the {@code LedgerMetrics} stance verbatim —
     * the scrape is the schedule, one floored read-only pair of aggregates per instance, no
     * leader, no ambient schedule, nothing written, and NaN rather than a false zero when the
     * database cannot be read. The stores are constructed here like the ledger's hold store,
     * because nothing else in this context consumes them as beans.
     */
    @Bean
    PaymentMetrics paymentMetrics(
            DataSource dataSource,
            Clock clock,
            MeterRegistry registry,
            // The sweep's own bound, through the one placeholder it reads (the Phase 6 -> 7
            // transition, the payout's shape): a dispatched or authorized operation is stuck
            // only once the sweep would have asked about it.
            @org.springframework.beans.factory.annotation.Value(
                            com.finapp.app.payments.PaymentSweeperSchedule.DISPATCHED_AGE)
                    java.time.Duration dispatchedAge) {
        com.finapp.payments.PaymentAttemptStore<java.sql.Connection> attempts =
                new com.finapp.payments.JdbcPaymentAttemptStore();
        com.finapp.payments.RefundStore<java.sql.Connection> refunds =
                new com.finapp.payments.JdbcRefundStore();
        return new PaymentMetrics(
                connection -> reading(attempts.unknownReading(connection, dispatchedAge)),
                connection -> reading(refunds.unknownReading(connection, dispatchedAge)),
                dataSource::getConnection,
                clock,
                registry);
    }

    private static PaymentMetrics.Reading reading(
            com.finapp.payments.PaymentAttemptStore.UnknownReading stored) {
        return new PaymentMetrics.Reading(stored.active(), stored.oldestAgeSeconds());
    }

    /**
     * The pay-by-bank gauges (`P7-TSK-009`): the awaiting-payer age {@code AWAITING_PAYER}
     * is deliberately excluded from the stuck gauges above, and the suspense parkings —
     * {@code INV-REC-05}'s standing alert. The {@code paymentMetrics} stance verbatim.
     */
    @Bean
    PayInMetrics payInMetrics(DataSource dataSource, Clock clock, MeterRegistry registry) {
        com.finapp.payments.PaymentAttemptStore<java.sql.Connection> attempts =
                new com.finapp.payments.JdbcPaymentAttemptStore();
        com.finapp.payments.UnmatchedConfirmationStore<java.sql.Connection> unmatched =
                new com.finapp.payments.JdbcUnmatchedConfirmationStore();
        return new PayInMetrics(
                connection -> payInReading(attempts.awaitingReading(connection)),
                connection -> payInReading(unmatched.parkedReading(connection)),
                dataSource::getConnection,
                clock,
                registry);
    }

    private static PayInMetrics.Reading payInReading(
            com.finapp.payments.PaymentAttemptStore.UnknownReading stored) {
        return new PayInMetrics.Reading(stored.active(), stored.oldestAgeSeconds());
    }

    /**
     * Wraps the auto-configured connection pool so acquiring a connection is visible in a trace.
     *
     * <p><strong>A {@code BeanPostProcessor} because a {@code @Bean} cannot do this.</strong>
     * Boot's data-source auto-configuration is conditional on no {@code DataSource} bean existing,
     * so declaring one here would not decorate the pool — it would prevent the pool from being
     * created at all, and leave this wrapping nothing. Decoration after the fact is the only
     * mechanism that composes with a conditional auto-configuration.
     *
     * <p>The {@link Tracer} is taken through an {@link ObjectProvider} and resolved lazily. A
     * post-processor is constructed very early, and demanding a fully-built tracing stack at that
     * point makes the data source depend on the order two unrelated auto-configurations happen to
     * initialise in — which works until the day it does not.
     */
    @Bean
    static BeanPostProcessor traceDataSourceConnections(ObjectProvider<Tracer> tracer) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName)
                    throws BeansException {
                if (bean instanceof DataSource pool && !(bean instanceof TracedDataSource)) {
                    return new TracedDataSource(pool, tracer::getObject);
                }
                return bean;
            }
        };
    }
}
