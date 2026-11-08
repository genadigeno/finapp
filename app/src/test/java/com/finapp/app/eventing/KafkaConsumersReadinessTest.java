package com.finapp.app.eventing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * A refused consumer fails readiness and never liveness (`P15-TSK-026`), read from the running
 * application's own health groups rather than from {@code application.yaml}'s text.
 *
 * <p>The chain, link by link: the receiver turns the client's refusal into
 * {@code BrokerAccessRefusedException} ({@code KafkaEventReceiverConfigurationTest}, and against a
 * real SCRAM listener {@code KafkaAuthenticationKafkaTest}, owed); the loop records it and the
 * contributor reports DOWN ({@code KafkaConsumersHealthIndicatorTest}); and here, the contributor
 * is registered under the name the readiness group includes, and the liveness group does not.
 *
 * <p>PostgreSQL is deliberately absent, as in {@code HealthEndpointTest}: the groups are
 * configuration, and the application starts without its database.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/absent")
@Tag("slice")
@DisplayName("the consumers' contributor is in readiness and not in liveness (P15-TSK-026)")
class KafkaConsumersReadinessTest {

    @Autowired private HealthEndpointGroups groups;

    @Autowired private HealthContributorRegistry registry;

    @Test
    @DisplayName("readiness includes kafkaConsumers; liveness does not")
    void readinessAndNotLiveness() {
        assertThat(groups.get("readiness").isMember("kafkaConsumers"))
                .as("a refused credential must stop this replica taking traffic")
                .isTrue();
        assertThat(groups.get("liveness").isMember("kafkaConsumers"))
                .as("restarting a replica does not fix a credential, so liveness never reads it")
                .isFalse();
    }

    @Test
    @DisplayName("the name the readiness group includes is the consumers' contributor")
    void theContributorIsRegistered() {
        assertThat(registry.getContributor("kafkaConsumers"))
                .as("a group member nothing answers to would be a readiness that cannot fail")
                .isInstanceOf(KafkaConsumersHealthIndicator.class);
    }
}
