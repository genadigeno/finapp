package com.finapp.app.eventing;

import com.finapp.app.lifecycle.WorkHold;
import com.finapp.app.security.ConfinedCredential;
import com.finapp.app.security.KafkaTransportGuard;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.deadletter.DeadLetterOperations;
import com.finapp.platform.deadletter.DeadLetterStore;
import com.finapp.platform.deadletter.JdbcDeadLetterStore;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.inbox.InboxConsumer;
import com.finapp.platform.inbox.InboxEventHandler;
import com.finapp.platform.inbox.InboxParking;
import com.finapp.platform.inbox.JdbcInboxParking;
import com.finapp.platform.inbox.JdbcInboxRecordStore;
import com.finapp.platform.inbox.ParkingPolicy;
import com.finapp.platform.inbox.kafka.KafkaEventReceiver;
import com.finapp.platform.outbox.AuditedAbandonments;
import com.finapp.platform.outbox.BrokerConnection;
import com.finapp.platform.outbox.EventPublisher;
import com.finapp.platform.outbox.KafkaEventPublisher;
import com.finapp.platform.outbox.OutboxRelay;
import com.finapp.platform.retention.RetentionConnectionSource;
import com.finapp.platform.retention.RetentionSweep;
import com.finapp.platform.telemetry.Spans;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.security.Sensitive;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires the outbox to the broker (`P2-TSK-001`).
 *
 * <p>The composition root passes strings and durations and never sees a Kafka type: producer
 * construction and its acknowledgement configuration live in
 * {@link KafkaEventPublisher#connect}, inside the one package
 * {@code NoDirectBrokerPublicationRulesTest} permits to touch the client.
 *
 * <p>Defaults point at the compose stack's HOST listener ({@code localhost:29092}) so a clean
 * clone runs with no configuration ({@code DOD-BUILD}); a deployment overrides through the
 * environment, and {@link KafkaTransportGuard} refuses a non-loopback bootstrap over plaintext
 * before the first byte leaves the machine (ADR-0023's promise, kept by the task that added the
 * client). Since `P15-TSK-026` it refuses anything but {@code SASL_SSL} off loopback, and the
 * marked local SCRAM password there; both clients connect through one {@link BrokerConnection}.
 *
 * <p>The application starts with the broker down (ADR-0016's reasoning): the producer dials
 * lazily, publish failures are the relay's ordinary backoff, and the events wait durably where
 * they already were.
 */
@Configuration(proxyBeanMethods = false)
public class EventingBeans {

    @Bean
    KafkaTransportGuard kafkaTransportGuard(
            @Value("${finapp.kafka.bootstrap-servers:localhost:29092}") String bootstrapServers,
            @Value("${finapp.kafka.security-protocol:PLAINTEXT}") String securityProtocol,
            @Value("${finapp.kafka.sasl.username:}") String saslUsername,
            @Value("${finapp.kafka.sasl.password:" + ConfinedCredential.MARKED_LOCAL_DEFAULT + "}")
                    String saslPassword) {
        return new KafkaTransportGuard(bootstrapServers, securityProtocol, saslUsername, saslPassword);
    }

    /**
     * Where the broker is and how both clients prove themselves to it (`P15-TSK-026`): one value,
     * built only after the guard has ruled on it, so the producer and every consumer connect the
     * same way. The SCRAM credential comes from the environment (`FINAPP_KAFKA_SASL_USERNAME`,
     * `FINAPP_KAFKA_SASL_PASSWORD`), its local default the marked one, which the guard refuses off
     * loopback; the truststore is a PEM file of CA certificates, or the JVM's own when unset.
     */
    @Bean
    BrokerConnection brokerConnection(
            @Value("${finapp.kafka.bootstrap-servers:localhost:29092}") String bootstrapServers,
            @Value("${finapp.kafka.security-protocol:PLAINTEXT}") String securityProtocol,
            @Value("${finapp.kafka.sasl.username:}") String saslUsername,
            @Value("${finapp.kafka.sasl.password:" + ConfinedCredential.MARKED_LOCAL_DEFAULT + "}")
                    String saslPassword,
            @Value("${finapp.kafka.ssl.truststore-location:}") String truststoreLocation,
            // The guard as a parameter, not @DependsOn by name: the dependency is real, so it is
            // expressed where the compiler and the context both see it.
            KafkaTransportGuard guard) {
        String protocol = securityProtocol.trim().toUpperCase(Locale.ROOT);
        boolean sasl = protocol.startsWith("SASL_");
        // A protocol without SASL carries no credential, so the marked local default - present
        // whenever nothing is configured - is not handed to a client that would never use it.
        return new BrokerConnection(
                bootstrapServers,
                protocol,
                sasl ? saslUsername : "",
                Sensitive.of(sasl ? saslPassword : ""),
                truststoreLocation);
    }

    @Bean(destroyMethod = "close")
    KafkaEventPublisher eventPublisher(
            BrokerConnection brokerConnection,
            @Value("${finapp.kafka.ack-timeout:PT10S}") Duration ackTimeout) {
        return KafkaEventPublisher.connect(brokerConnection, ackTimeout);
    }

    @Bean
    OutboxRelay outboxRelay(DataSource dataSource, EventPublisher publisher, AuditWriter<Connection> auditWriter, IdGenerator idGenerator, Clock clock) {
        // The relay opens its own connections OUTSIDE any Spring transaction: each poll is its
        // own unit of work, committed by the relay (P0-TSK-020). Default batch bounds.
        return new OutboxRelay(dataSource::getConnection, publisher, new AuditedAbandonments(auditWriter, idGenerator, clock));
    }

    // matchIfMissing=true: a DEPLOYED instance polls without configuration. The app test suite
    // sets the property false (src/test/resources/application.properties) because a background
    // worker mutating outbox rows mid-assertion turns deterministic tests into races - and the
    // kafka tier exercises the schedule DELIBERATELY, which is the difference between disabling
    // a control in tests (forbidden, .claude/rules/security.md) and choosing when a worker runs:
    // the schedule is throughput machinery, not a control, and every correctness property it
    // relies on (locks, conditional marks) is tested with it running in KafkaOutboxDeliveryKafkaTest.
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "finapp.outbox.relay.enabled",
            havingValue = "true",
            matchIfMissing = true)
    OutboxRelaySchedule outboxRelaySchedule(
            OutboxRelay relay,
            @Value("${finapp.outbox.poll-interval:PT1S}") Duration pollInterval,
            MeterRegistry registry) {
        return new OutboxRelaySchedule(relay, pollInterval, registry);
    }

    /**
     * The consuming half (`P2-TSK-002`). One instance app-wide; retention is the dedupe window
     * and must exceed every window in which a record can be redelivered — for Kafka that is
     * bounded by topic retention (7 days by default), so the default here is twice that
     * ({@code DATA_MIGRATIONS.md} §9: too long costs storage, too short admits the duplicate
     * effect the inbox exists to prevent).
     *
     * <p>Since `P15-TSK-026` the bound is asserted, not assumed: {@code TopicCatalogueTest} reads
     * this default and {@code application.yaml}, and fails unless every catalogued topic's
     * {@code retention.ms}, plus its {@code segment.ms} (the broker deletes whole segments, so a
     * record outlives retention by up to one segment) and the broker's retention check interval,
     * stays below it ({@code infra/kafka/topics.yaml}: 7 days + 1 day + 5 minutes).
     */
    @Bean
    InboxConsumer<Connection> inboxConsumer(
            Clock clock, @Value("${finapp.inbox.retention:P14D}") Duration retention) {
        return new InboxConsumer<>(new JdbcInboxRecordStore(), clock, retention);
    }

    /** The connection a retention sweep's batch runs on: the instance's own pool, so the fleet's budget does not move (`P15-TSK-029`). */
    @Bean
    RetentionConnectionSource retentionConnections(DataSource dataSource) {
        return dataSource::getConnection;
    }

    /** Eager either way, as the consumers' counters are: the two tables' series exist on an instance whose sweeps are off. */
    @Bean
    RetentionSweepMeters retentionSweepMeters(MeterRegistry registry) {
        return new RetentionSweepMeters(registry, List.of("outbox_event", "inbox_message", "window_counter"));
    }

    // The relay schedule's gate, for its reason: a background worker deleting rows would race the suites' assertions.
    /**
     * Published outbox rows, kept {@code finapp.outbox.retention} for diagnosis and replay. Refused at start below the inbox's
     * dedupe window, read from the consumer itself, because an audited replay reads the outbox across that window.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "finapp.retention.sweep.enabled",
            havingValue = "true",
            matchIfMissing = true)
    RetentionSweepSchedule outboxRetentionSweepSchedule(
            RetentionConnectionSource connections,
            InboxConsumer<Connection> inboxConsumer,
            @Value("${finapp.outbox.retention:P30D}") Duration retention,
            @Value("${finapp.retention.sweep.interval:PT5M}") Duration interval,
            @Value("${finapp.retention.sweep.batch-size:500}") int batchSize,
            RetentionSweepMeters meters) {
        return new RetentionSweepSchedule(
                "outbox-retention-sweep",
                RetentionSweep.ofPublishedOutboxEvents(connections, retention, inboxConsumer.retention(), batchSize),
                interval,
                meters);
    }

    /**
     * Expired inbox records. The bound is the retention the consumer writes with, from the consumer, so this sweep has no setting
     * of its own. {@link RetentionSweep} refuses that retention at or below the oldest record the broker can redeliver, so an
     * instance whose {@code finapp.inbox.retention} would admit a second effect does not start.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "finapp.retention.sweep.enabled",
            havingValue = "true",
            matchIfMissing = true)
    RetentionSweepSchedule inboxRetentionSweepSchedule(
            RetentionConnectionSource connections,
            InboxConsumer<Connection> inboxConsumer,
            @Value("${finapp.retention.sweep.interval:PT5M}") Duration interval,
            @Value("${finapp.retention.sweep.batch-size:500}") int batchSize,
            RetentionSweepMeters meters) {
        return new RetentionSweepSchedule(
                "inbox-retention-sweep",
                RetentionSweep.ofExpiredInboxMessages(connections, inboxConsumer.retention(), batchSize),
                interval,
                meters);
    }

    /**
     * How long a record may fail for a reason of its own before it is parked (`P15-TSK-028`; {@link ParkingPolicy}): five failures
     * and two minutes, both, on the database's clock. Two minutes outlasts a failover and a rolling step, which are what a valid
     * record most plausibly fails through; a truly poison record costs its partition that long.
     */
    @Bean
    InboxParking inboxParking(
            @Value("${finapp.inbox.park-after-attempts:5}") int attempts,
            @Value("${finapp.inbox.park-after-failing-for:PT2M}") Duration failingFor,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new JdbcInboxParking(new ParkingPolicy(attempts, failingFor), auditWriter, idGenerator, clock);
    }

    @Bean
    DeadLetterStore deadLetterStore() {
        return new JdbcDeadLetterStore();
    }

    /**
     * The replay's window is {@link InboxConsumer#retention()}, read from the one bean that dated every dedupe row, so there is one
     * {@code finapp.inbox.retention} in this application and not a second default to drift from it (`P15-TSK-028`).
     */
    @Bean
    DeadLetterOperations deadLetterOperations(
            DeadLetterStore deadLetterStore,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock,
            InboxConsumer<Connection> inboxConsumer,
            ObjectProvider<InboxEventHandler> handlers) {
        return new DeadLetterOperations(
                deadLetterStore, auditWriter, idGenerator, clock, inboxConsumer.retention(), everyConsumerIsMarked(handlers.orderedStream().toList()));
    }

    /**
     * Whether every handler of a type carries {@link InboxEventHandler#naturallyIdempotent()}, the door's own declaration. False for a
     * type no handler consumes, because nobody has claimed anything for it.
     */
    static Predicate<String> everyConsumerIsMarked(List<InboxEventHandler> handlers) {
        return eventType -> {
            List<InboxEventHandler> consumers = handlers.stream().filter(handler -> handler.eventType().equals(eventType)).toList();
            return !consumers.isEmpty() && consumers.stream().allMatch(InboxEventHandler::naturallyIdempotent);
        };
    }

    @Bean
    DeadLetterTransactions deadLetterTransactions(PlatformTransactionManager transactionManager, DataSource dataSource) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        return new DeadLetterTransactions() {
            @Override
            public <R> R inTransaction(Function<Connection, R> work) {
                return template.execute(status -> {
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

    @Bean
    DeadLetterDesk deadLetterDesk(
            DeadLetterOperations deadLetterOperations,
            IdempotentExecutor idempotentExecutor,
            DeadLetterTransactions deadLetterTransactions,
            Spans domainSpans) {
        return new DeadLetterDesk(deadLetterOperations, idempotentExecutor, deadLetterTransactions, domainSpans);
    }

    /**
     * {@code finapp.inbox.consumption{outcome}} eager either way (`P13-TSK-024`): the consumer's counters exist on an
     * instance whose consumer is off, so the stalled-partition alert evaluates a series everywhere it is scraped.
     */
    @Bean
    InboxConsumptionMeters inboxConsumptionMeters(MeterRegistry registry) {
        return new InboxConsumptionMeters(registry);
    }

    // The same gate shape as the relay schedule, for the same reason: a background worker
    // consuming records under every @SpringBootTest would race assertions, so the app test
    // overlay disables it and the kafka tier runs the real thing deliberately. matchIfMissing:
    // a DEPLOYED instance consumes without configuration.
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "finapp.inbox.consumer.enabled",
            havingValue = "true",
            matchIfMissing = true)
    InboxConsumers inboxConsumers(
            ObjectProvider<InboxEventHandler> handlers,
            DataSource dataSource,
            InboxConsumer<Connection> inboxConsumer,
            InboxParking inboxParking,
            MeterRegistry registry,
            // Built after the transport guard ruled on it (see brokerConnection): the consumer
            // dials the same bootstrap, so it must not exist before the guard has ruled.
            BrokerConnection brokerConnection,
            WorkHold workHold,
            @Value("${finapp.inbox.poll-timeout:PT1S}") Duration pollTimeout,
            @Value("${finapp.inbox.failure-backoff:PT1S}") Duration failureBackoff) {
        return new InboxConsumers(
                handlers.orderedStream().toList(),
                (groupId, groupHandlers) ->
                        KafkaEventReceiver.connect(
                                brokerConnection,
                                groupId,
                                dataSource::getConnection,
                                inboxConsumer,
                                inboxParking,
                                groupHandlers,
                                pollTimeout),
                failureBackoff,
                registry,
                workHold);
    }

    /**
     * The consumers' readiness contributor (`P15-TSK-026`), present whether or not the consumers
     * run: the readiness group names it, and a group naming a contributor that does not exist
     * refuses to start. With the consumers off there is nothing to refuse, and it reports UP.
     */
    @Bean
    KafkaConsumersHealthIndicator kafkaConsumersHealthIndicator(ObjectProvider<InboxConsumers> consumers) {
        return new KafkaConsumersHealthIndicator(
                () -> {
                    InboxConsumers running = consumers.getIfAvailable();
                    return running != null && running.brokerAccessRefused();
                });
    }
}
