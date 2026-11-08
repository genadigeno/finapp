package com.finapp.app.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The Kafka half of ADR-0023's promise: the guard arrives with the first client (`P2-TSK-001`),
 * and is widened to C11's {@code SASL_SSL} with SCRAM off loopback (`P15-TSK-026`). Both sides are
 * held: what must be refused off this machine, and the loopback development shape the compose
 * stack and the test tiers run on.
 */
@DisplayName("the Kafka transport guard (P2-TSK-001, P15-TSK-026)")
class KafkaTransportGuardTest {

    private static final String REMOTE = "kafka.internal:9092";

    @Test
    @DisplayName("a non-loopback bootstrap over PLAINTEXT is refused, with the fix in the message")
    void plaintextOffTheMachineIsRefused() {
        assertThatThrownBy(() -> new KafkaTransportGuard(REMOTE, "PLAINTEXT"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(REMOTE)
                .hasMessageContaining("SASL_SSL");
    }

    @Test
    @DisplayName("SASL_PLAINTEXT off the machine is refused: authenticated, but every record in the clear")
    void saslWithoutTlsOffTheMachineIsRefused() {
        // The gap P15-TSK-001 recorded (THREAT_MODEL.md T9): the first guard refused only
        // PLAINTEXT, so this shape - with a real credential - started.
        assertThatThrownBy(() -> new KafkaTransportGuard(REMOTE, "SASL_PLAINTEXT", "finapp", "a-real-one"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SASL_PLAINTEXT")
                .hasMessageContaining("SASL_SSL");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PLAINTEXT", "SASL_PLAINTEXT", "SSL", "plaintext", " sasl_plaintext "})
    @DisplayName("off the machine, every protocol but SASL_SSL is refused, in any case or spacing")
    void everyProtocolButSaslSslIsRefusedOffTheMachine(String protocol) {
        // SSL alone has TLS but no client identity C11 chose (a client certificate was its option
        // C, decided against), so it is refused with the two that have no TLS at all.
        assertThatThrownBy(() -> new KafkaTransportGuard(REMOTE, protocol, "finapp", "a-real-one"))
                .isInstanceOf(IllegalStateException.class);
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
                            // The application's own wiring passes the configured credential,
                            // which defaults to the marked local one; PLAINTEXT never uses it.
                            new KafkaTransportGuard(
                                    "localhost:29092",
                                    "PLAINTEXT",
                                    "",
                                    ConfinedCredential.MARKED_LOCAL_DEFAULT);
                        })
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SASL_SSL with a supplied credential is accepted off the machine")
    void securedIsAcceptedOffTheMachine() {
        assertThatCode(
                        () ->
                                new KafkaTransportGuard(
                                        REMOTE, "SASL_SSL", "finapp-app", "supplied-by-the-store"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SASL_SSL with no credential is refused, naming the variables to set")
    void saslNeedsACredential() {
        assertThatThrownBy(() -> new KafkaTransportGuard(REMOTE, "SASL_SSL"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FINAPP_KAFKA_SASL_USERNAME")
                .hasMessageContaining("FINAPP_KAFKA_SASL_PASSWORD");
        assertThatThrownBy(() -> new KafkaTransportGuard(REMOTE, "SASL_SSL", "finapp-app", " "))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the marked local password is refused off the machine, and only there")
    void theMarkedLocalPasswordIsConfinedToLoopback() {
        // DatabaseCredentialGuard's confinement, applied to the broker: the fallback is what a
        // deployment gets when nobody sets the variable, and it is published in this repository.
        assertThatThrownBy(
                        () ->
                                new KafkaTransportGuard(
                                        REMOTE,
                                        "SASL_SSL",
                                        "finapp-app",
                                        ConfinedCredential.MARKED_LOCAL_DEFAULT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FINAPP_KAFKA_SASL_PASSWORD")
                .hasMessageContaining("published local default");
        assertThatCode(
                        () ->
                                new KafkaTransportGuard(
                                        "localhost:9093",
                                        "SASL_SSL",
                                        "finapp-app",
                                        ConfinedCredential.MARKED_LOCAL_DEFAULT))
                .as("a broker on this machine may use it, as the database may")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("no refusal echoes the password")
    void noRefusalEchoesThePassword() {
        assertThatThrownBy(() -> new KafkaTransportGuard(REMOTE, "SASL_PLAINTEXT", "u", "pw-6f1c"))
                .hasMessageNotContaining("pw-6f1c");
        assertThatThrownBy(() -> new KafkaTransportGuard(REMOTE, "SASL_SSL", "", "pw-6f1c"))
                .hasMessageNotContaining("pw-6f1c");
    }

    @Test
    @DisplayName("a protocol Kafka does not define is refused, on loopback too - fail closed")
    void anUnknownProtocolIsRefusedEverywhere() {
        // The first guard compared against PLAINTEXT alone, so any other string - a typo
        // included - passed it off the machine.
        assertThatThrownBy(() -> new KafkaTransportGuard(REMOTE, "PLAIN_TEXT"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PLAIN_TEXT");
        assertThatThrownBy(() -> new KafkaTransportGuard("localhost:29092", "TLS"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("an empty or unreadable bootstrap has not been shown to be local - fail closed")
    void emptyFailsClosed() {
        assertThatThrownBy(() -> new KafkaTransportGuard("   ", "PLAINTEXT"))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                // The review's bypass: kafka-clients 4.2.1 skips the prefix and connects to
                // kafka.internal; a first-colon reading saw localhost.
                "localhost://kafka.internal:9092",
                "PLAINTEXT://localhost:9092",
                "localhost:29092,localhost://kafka.internal:9092",
                "kafka.internal:9092@localhost:9092",
                "localhost:9092/kafka.internal",
                // An unbracketed IPv6 literal: its colons are not a port's.
                "localhost:9092,::ffff:10.0.0.5:9092",
                "localhost: 9092",
                "localhost",
                "localhost:99999",
                "127.999.0.1:9092",
                "localhost:9092,"
            })
    @DisplayName("an entry that is not strictly host:port or [IPv6]:port is refused, on loopback too")
    void aBootstrapEntryThatIsNotStrictlyHostAndPortIsRefused(String bootstrap) {
        assertThatThrownBy(() -> new KafkaTransportGuard(bootstrap, "PLAINTEXT"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("host:port")
                .hasMessageNotContaining(bootstrap);
        // Strictness is about reading the list, not about the protocol: SASL_SSL is refused too.
        assertThatThrownBy(
                        () -> new KafkaTransportGuard(bootstrap, "SASL_SSL", "finapp-app", "a-real-one"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("host:port");
    }

    @Test
    @DisplayName("a strict list is still read as the client lists it: spaces around commas, brackets, 127/8")
    void aStrictListIsRead() {
        assertThatCode(
                        () -> {
                            new KafkaTransportGuard("localhost:29092, 127.0.0.2:9092", "PLAINTEXT");
                            new KafkaTransportGuard("[::1]:9092,localhost:9093", "PLAINTEXT");
                        })
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new KafkaTransportGuard("localhost:29092, [fd00::5]:9092", "PLAINTEXT"))
                .as("a bracketed remote literal is read, and refused as remote")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not (entirely) on this machine");
    }
}
