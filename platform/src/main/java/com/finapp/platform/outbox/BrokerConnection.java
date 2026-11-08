package com.finapp.platform.outbox;

import com.finapp.sharedkernel.security.Sensitive;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;

/**
 * Where the broker is and how a client proves itself to it (`P15-TSK-026`): the connection half of
 * both adapters' client configuration, rendered once so the producer and the consumer cannot
 * drift apart.
 *
 * <h2>Why it lives here</h2>
 *
 * <p>{@code NoDirectBrokerPublicationRulesTest} lets two packages touch a broker client, this one
 * and {@code com.finapp.platform.inbox.kafka}. The connection settings are Kafka vocabulary, so
 * they belong inside that boundary, and the publisher came first: the receiver reads them from
 * here rather than a third package being exempted. The composition root passes strings and a
 * {@link Sensitive} and never sees a Kafka type.
 *
 * <h2>What is fixed in code, and why it is not configurable</h2>
 *
 * <ul>
 *   <li><strong>The SASL mechanism is SCRAM-SHA-512</strong> (owner decision C11). A mechanism
 *       property would let a deployment choose {@code PLAIN}, which sends the password itself;
 *       SCRAM never puts the password on the wire.
 *   <li><strong>Hostname verification is {@code https}</strong>, written out although it is the
 *       client's default. {@code KafkaTransportGuard} recorded, as its limit, that it could not see
 *       verification switched off elsewhere; there is no elsewhere now, because no property passes
 *       through to the client but the ones rendered here.
 *   <li><strong>A truststore is a PEM file of CA certificates</strong>, or nothing, which leaves the
 *       JVM's default trust store in charge. A PEM bundle needs no password, so no truststore
 *       password exists to manage.
 * </ul>
 *
 * <h2>The one unwrap</h2>
 *
 * <p>The password is held {@link Sensitive} and comes off its wrapper in {@link #clientProperties()}
 * alone, into the JAAS line the client parses, which is where it has to go.
 * {@code SecretsAreUnwrappedInOnePlaceTest} names this class for that. The client logs its
 * configuration at startup and hides {@code sasl.jaas.config}, a {@code PASSWORD}-typed setting;
 * {@link #toString()} here prints the wrapper's mask.
 *
 * <p>Shape only is checked here: a known protocol, and both credentials for a SASL one. Whether a
 * protocol is acceptable for the bootstrap it points at is {@code KafkaTransportGuard}'s question,
 * asked before this is built.
 *
 * @param bootstrapServers the comma-separated {@code host:port} list
 * @param securityProtocol {@code PLAINTEXT}, {@code SSL}, {@code SASL_PLAINTEXT} or
 *     {@code SASL_SSL}, in any case
 * @param saslUsername the SCRAM user; blank unless the protocol is a SASL one
 * @param saslPassword the SCRAM password; blank unless the protocol is a SASL one
 * @param truststoreLocation a PEM file of CA certificates, or blank for the JVM's trust store
 */
public record BrokerConnection(
        String bootstrapServers,
        String securityProtocol,
        String saslUsername,
        Sensitive<String> saslPassword,
        String truststoreLocation) {

    /** The mechanism C11 decided; never configurable (see the class comment). */
    public static final String SASL_MECHANISM = "SCRAM-SHA-512";

    private static final Set<String> PROTOCOLS =
            Set.of("PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL");

    public BrokerConnection {
        Objects.requireNonNull(bootstrapServers, "bootstrapServers must not be null");
        Objects.requireNonNull(securityProtocol, "securityProtocol must not be null");
        Objects.requireNonNull(saslUsername, "saslUsername must not be null");
        Objects.requireNonNull(saslPassword, "saslPassword must not be null");
        Objects.requireNonNull(truststoreLocation, "truststoreLocation must not be null");
        securityProtocol = securityProtocol.trim().toUpperCase(Locale.ROOT);
        if (!PROTOCOLS.contains(securityProtocol)) {
            throw new IllegalArgumentException(
                    "securityProtocol must be one of " + PROTOCOLS + " but was " + securityProtocol);
        }
        if (securityProtocol.startsWith("SASL_")
                && (saslUsername.isBlank() || saslPassword.map(String::isBlank).expose())) {
            // Never the value: the message names what is missing, not what was configured.
            throw new IllegalArgumentException(
                    securityProtocol + " needs both a SASL username and a SASL password");
        }
        saslUsername = saslUsername.trim();
        truststoreLocation = truststoreLocation.trim();
    }

    /** A loopback development broker: no TLS, no SASL (the compose stack and the test tiers). */
    public static BrokerConnection plaintext(String bootstrapServers) {
        return new BrokerConnection(bootstrapServers, "PLAINTEXT", "", Sensitive.of(""), "");
    }

    /**
     * The client properties both adapters start from: the bootstrap, the protocol, and for a SASL
     * protocol the SCRAM login, for a TLS one hostname verification and the truststore.
     */
    public Properties clientProperties() {
        Properties config = new Properties();
        config.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, securityProtocol);
        if (securityProtocol.startsWith("SASL_")) {
            config.put(SaslConfigs.SASL_MECHANISM, SASL_MECHANISM);
            config.put(
                    SaslConfigs.SASL_JAAS_CONFIG,
                    "org.apache.kafka.common.security.scram.ScramLoginModule required"
                            + " username=\"" + jaasQuoted(saslUsername) + "\""
                            + " password=\"" + jaasQuoted(saslPassword.expose()) + "\";");
        }
        if (securityProtocol.endsWith("SSL")) {
            config.put(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG, "https");
            if (!truststoreLocation.isEmpty()) {
                config.put(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG, "PEM");
                config.put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, truststoreLocation);
            }
        }
        return config;
    }

    /**
     * A JAAS quoted string: the client parses the line with a {@code StreamTokenizer} whose quote
     * character is {@code "}, which reads a backslash as an escape. So a password holding either
     * character would end its own string early, or lose a character, unless both are escaped. The
     * same tokenizer ends a quoted string at a line feed or a carriage return (`P15-TSK-026`'s
     * review fix round), so those are written as its {@code \n} and {@code \r} escapes, which it
     * reads back as the characters themselves - the backslash first, so no escape is doubled.
     */
    static String jaasQuoted(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
