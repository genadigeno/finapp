package com.finapp.platform.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.security.Sensitive;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.security.auth.login.AppConfigurationEntry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.security.JaasContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The connection half of both clients' configuration (`P15-TSK-026`): SCRAM-SHA-512 over TLS as
 * C11 decided, the password reaching the client intact and nowhere else.
 *
 * <p>Hermetic: the properties are parsed by Kafka's own {@code ConsumerConfig} and its JAAS loader,
 * which is what a real client does with them at construction, without dialling anything. A real
 * SCRAM handshake is {@code KafkaAuthenticationKafkaTest}'s (owed to the exit's database pass); a
 * real TLS handshake is {@code P15-TSK-012}'s rehearsal against the local CA.
 */
@DisplayName("the broker connection (P15-TSK-026)")
class BrokerConnectionTest {

    @Test
    @DisplayName("SASL_SSL renders SCRAM-SHA-512, hostname verification and the PEM truststore")
    void saslSslRendersScramAndTls() {
        Properties rendered =
                new BrokerConnection(
                                "broker-a:9096,broker-b:9096",
                                "sasl_ssl",
                                "finapp-app",
                                Sensitive.of("s3cr3t"),
                                "/etc/finapp/kafka-ca.pem")
                        .clientProperties();

        assertThat(rendered)
                .containsEntry("bootstrap.servers", "broker-a:9096,broker-b:9096")
                .containsEntry("security.protocol", "SASL_SSL")
                .containsEntry("sasl.mechanism", "SCRAM-SHA-512")
                .containsEntry("ssl.endpoint.identification.algorithm", "https")
                .containsEntry("ssl.truststore.type", "PEM")
                .containsEntry("ssl.truststore.location", "/etc/finapp/kafka-ca.pem");
        assertThat(loginOptions(rendered))
                .containsEntry("username", "finapp-app")
                .containsEntry("password", "s3cr3t");
        assertThat(loginModule(rendered))
                .isEqualTo("org.apache.kafka.common.security.scram.ScramLoginModule");
    }

    @Test
    @DisplayName("a password holding quotes, backslashes or line breaks reaches the client unchanged")
    void theJaasLineIsEscaped() {
        // StreamTokenizer reads `\` as an escape inside a quoted string, so an unescaped `"`
        // would end the password early and an unescaped `\` would vanish.
        String awkward = "a\"b\\c\\\"d";
        Properties rendered =
                new BrokerConnection("broker:9096", "SASL_SSL", "u\"ser", Sensitive.of(awkward), "")
                        .clientProperties();

        assertThat(loginOptions(rendered))
                .containsEntry("password", awkward)
                .containsEntry("username", "u\"ser");

        // The review fix round: a line feed or carriage return ends a quoted string in the same
        // tokenizer, so a password holding one (a secret file's trailing newline, read whole) was
        // cut there - the client then failed the line, or authenticated with less of a password.
        String broken = "first\nsecond\rthird\r\n";
        Properties withBreaks =
                new BrokerConnection("broker:9096", "SASL_SSL", "u\nser", Sensitive.of(broken), "")
                        .clientProperties();
        assertThat(withBreaks.getProperty("sasl.jaas.config"))
                .as("one line, whatever the credential holds")
                .doesNotContain("\n")
                .doesNotContain("\r");
        assertThat(loginOptions(withBreaks))
                .containsEntry("password", broken)
                .containsEntry("username", "u\nser");
    }

    @Test
    @DisplayName("without a truststore the JVM's own trust store decides, and verification stays on")
    void noTruststoreLeavesTheDefault() {
        Properties rendered =
                new BrokerConnection("broker:9096", "SASL_SSL", "u", Sensitive.of("p"), " ")
                        .clientProperties();

        assertThat(rendered)
                .doesNotContainKey("ssl.truststore.location")
                .doesNotContainKey("ssl.truststore.type")
                .containsEntry("ssl.endpoint.identification.algorithm", "https");
    }

    @Test
    @DisplayName("loopback PLAINTEXT, the development shape, carries no SASL and no TLS setting")
    void plaintextCarriesNothingElse() {
        Properties rendered = BrokerConnection.plaintext("localhost:29092").clientProperties();

        assertThat(rendered)
                .containsOnlyKeys("bootstrap.servers", "security.protocol")
                .containsEntry("security.protocol", "PLAINTEXT");
    }

    @Test
    @DisplayName("a SASL protocol without both credentials is refused, naming neither value")
    void saslNeedsBothCredentials() {
        assertThatThrownBy(
                        () ->
                                new BrokerConnection(
                                        "broker:9096", "SASL_SSL", "", Sensitive.of("pw-x7"), ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SASL username and a SASL password")
                .hasMessageNotContaining("pw-x7");
        assertThatThrownBy(
                        () ->
                                new BrokerConnection(
                                        "broker:9096", "SASL_SSL", "carol-x7", Sensitive.of(" "), ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("carol-x7");
    }

    @Test
    @DisplayName("a protocol Kafka does not define is refused")
    void anUnknownProtocolIsRefused() {
        assertThatThrownBy(
                        () -> new BrokerConnection("x:1", "PLAIN_TEXT", "", Sensitive.of(""), ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PLAIN_TEXT");
    }

    @Test
    @DisplayName("the password never appears in the connection's text")
    void thePasswordIsNeverPrinted() {
        BrokerConnection connection =
                new BrokerConnection("broker:9096", "SASL_SSL", "u", Sensitive.of("s3cr3t"), "");

        assertThat(connection.toString()).doesNotContain("s3cr3t").contains(Sensitive.MASK);
    }

    // -----------------------------------------------------------------

    /** Kafka's own reading of the JAAS line: the client config parsed, then its login loaded. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> loginOptions(Properties rendered) {
        return (Map<String, Object>) entry(rendered).getOptions();
    }

    private static String loginModule(Properties rendered) {
        return entry(rendered).getLoginModuleName();
    }

    private static AppConfigurationEntry entry(Properties rendered) {
        Properties consumer = new Properties();
        consumer.putAll(rendered);
        consumer.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        consumer.put(
                "value.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");
        List<AppConfigurationEntry> entries =
                JaasContext.loadClientContext(new ConsumerConfig(consumer).values())
                        .configurationEntries();
        assertThat(entries).hasSize(1);
        return entries.get(0);
    }
}
