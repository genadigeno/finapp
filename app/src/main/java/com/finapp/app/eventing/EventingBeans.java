package com.finapp.app.eventing;

import com.finapp.app.security.KafkaTransportGuard;
import com.finapp.platform.outbox.EventPublisher;
import com.finapp.platform.outbox.KafkaEventPublisher;
import com.finapp.platform.outbox.OutboxRelay;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
 * client).
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
            @Value("${finapp.kafka.security-protocol:PLAINTEXT}") String securityProtocol) {
        return new KafkaTransportGuard(bootstrapServers, securityProtocol);
    }

    @Bean(destroyMethod = "close")
    KafkaEventPublisher eventPublisher(
            @Value("${finapp.kafka.bootstrap-servers:localhost:29092}") String bootstrapServers,
            @Value("${finapp.kafka.security-protocol:PLAINTEXT}") String securityProtocol,
            @Value("${finapp.kafka.ack-timeout:PT10S}") Duration ackTimeout,
            // The guard as a parameter, not @DependsOn by name: the dependency is real, so it is
            // expressed where the compiler and the context both see it.
            KafkaTransportGuard guard) {
        return KafkaEventPublisher.connect(bootstrapServers, securityProtocol, ackTimeout);
    }

    @Bean
    OutboxRelay outboxRelay(DataSource dataSource, EventPublisher publisher) {
        // The relay opens its own connections OUTSIDE any Spring transaction: each poll is its
        // own unit of work, committed by the relay (P0-TSK-020). Default batch bounds.
        return new OutboxRelay(dataSource::getConnection, publisher);
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
}
