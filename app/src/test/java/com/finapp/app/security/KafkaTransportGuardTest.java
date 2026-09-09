package com.finapp.app.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Kafka half of ADR-0023's promise: the guard arrives with the first client (`P2-TSK-001`).
 */
@DisplayName("the Kafka transport guard (P2-TSK-001)")
class KafkaTransportGuardTest {

    @Test
    @DisplayName("a non-loopback bootstrap over PLAINTEXT is refused, with the fix in the message")
    void plaintextOffTheMachineIsRefused() {
        assertThatThrownBy(() -> new KafkaTransportGuard("kafka.internal:9092", "PLAINTEXT"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kafka.internal:9092")
                .hasMessageContaining("SASL_SSL");
    }

    @Test
    @DisplayName("one remote host in a loopback list is enough to refuse")
    void aMixedListIsRefused() {
        // The PostgreSQL failover lesson (P0-TSK-034): checking only the first entry lets the
        // second be anywhere at all.
        assertThatThrownBy(
                        () ->
                                new KafkaTransportGuard(
                                        "localhost:29092,kafka.internal:9092", "PLAINTEXT"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("loopback over PLAINTEXT is the local development shape and is accepted")
    void loopbackIsExempt() {
        assertThatCode(
                        () -> {
                            new KafkaTransportGuard("localhost:29092", "PLAINTEXT");
                            new KafkaTransportGuard("127.0.0.1:9092", "PLAINTEXT");
                            new KafkaTransportGuard("[::1]:9092", "PLAINTEXT");
                        })
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a secured protocol is accepted anywhere")
    void securedIsAcceptedOffTheMachine() {
        assertThatCode(() -> new KafkaTransportGuard("kafka.internal:9092", "SASL_SSL"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an empty or unreadable bootstrap has not been shown to be local — fail closed")
    void emptyFailsClosed() {
        assertThatThrownBy(() -> new KafkaTransportGuard("   ", "PLAINTEXT"))
                .isInstanceOf(IllegalStateException.class);
    }
}
