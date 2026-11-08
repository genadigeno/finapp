package com.finapp.app.security;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Refuses to start when the Kafka clients would leave this machine without TLS and an
 * authenticated identity (`P2-TSK-001`, widened by `P15-TSK-026`).
 *
 * <p>ADR-0023 guarded the database and recorded, for Kafka: <em>"there is no client for the
 * first two, and a guard for a connection that does not exist would be guarding nothing … the
 * risk arrives with the first client, which is also when it becomes enforceable."</em> The first
 * client arrived with the broker adapter, and this is the promised guard — the
 * {@link TransportSecurityGuard} shape, applied to the second transport: every event the
 * platform publishes crosses this connection, and a plaintext hop to a remote broker carries
 * every identifier in every envelope in the clear, silently.
 *
 * <p>Loopback is exempt for the database guard's exact reason: a connection that never leaves
 * the host would otherwise cost every developer broker certificates for a container, and
 * {@code DOD-BUILD} requires a clean clone to run with no machine-specific setup. The compose
 * stack and the test tiers stay on loopback {@code PLAINTEXT}.
 *
 * <h2>The bar off loopback is {@code SASL_SSL}, and nothing else (`P15-TSK-026`)</h2>
 *
 * <p>Until `P15-TSK-026` the bar was "not {@code PLAINTEXT}", which admitted {@code SASL_PLAINTEXT}
 * (an authenticated session whose every record crosses the network in the clear) and any string
 * at all that was not {@code PLAINTEXT}. Owner decision C11 is {@code SASL_SSL} with SCRAM-SHA-512:
 * TLS for confidentiality and the broker's identity, SCRAM for the client's. So off loopback:
 *
 * <ol>
 *   <li>the protocol must be {@code SASL_SSL} - {@code PLAINTEXT} and {@code SASL_PLAINTEXT} have
 *       no TLS, and {@code SSL} alone has no client identity C11 chose (a client certificate was
 *       its option C, decided against);
 *   <li>a SASL protocol needs a username and a password;
 *   <li>the password must not be the marked local default, which is published in this repository
 *       - {@link DatabaseCredentialGuard}'s confinement, applied to the broker's credential.
 * </ol>
 *
 * <p>An unknown protocol is refused anywhere, loopback included: a guard that cannot read the
 * protocol cannot say what it admits, so it fails closed. Hostname verification is no longer this
 * guard's stated limit: the clients take no property but the ones {@code BrokerConnection}
 * renders, and it fixes verification on.
 *
 * <p>No refusal echoes the password.
 */
public class KafkaTransportGuard {

    private static final Set<String> PROTOCOLS =
            Set.of("PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL");

    /**
     * The variable the refusal names, so the fix needs no documentation. Named for what it is - the
     * name of a variable - and not for the credential it carries, which {@code secretsAreWrapped}
     * would rightly read as a secret held in a String ({@link DatabaseCredentialGuard}'s recorded
     * lesson, met again here).
     */
    static final String REQUIRED_ENVIRONMENT_VARIABLE = "FINAPP_KAFKA_SASL_PASSWORD";

    /** A guard over a client with no credential configured. */
    public KafkaTransportGuard(String bootstrapServers, String securityProtocol) {
        this(bootstrapServers, securityProtocol, "", "");
    }

    /**
     * @throws IllegalStateException if the clients would leave this machine without
     *     {@code SASL_SSL}, without a credential, or with the published one; or if the protocol is
     *     not one Kafka defines
     */
    public KafkaTransportGuard(
            String bootstrapServers,
            String securityProtocol,
            String saslUsername,
            String saslPassword) {
        Objects.requireNonNull(bootstrapServers, "bootstrapServers must not be null");
        Objects.requireNonNull(securityProtocol, "securityProtocol must not be null");
        Objects.requireNonNull(saslUsername, "saslUsername must not be null");
        Objects.requireNonNull(saslPassword, "saslPassword must not be null");
        String protocol = securityProtocol.trim().toUpperCase(Locale.ROOT);
        if (!PROTOCOLS.contains(protocol)) {
            throw new IllegalStateException(
                    "Refusing to start: finapp.kafka.security-protocol is '"
                            + securityProtocol
                            + "', which is not one of "
                            + PROTOCOLS
                            + ". A protocol this guard cannot read is not one it can admit.");
        }
        boolean local = DatabaseEndpoint.allLoopbackHostPorts(bootstrapServers);
        if (!local && !"SASL_SSL".equals(protocol)) {
            throw new IllegalStateException(
                    "Refusing to start: finapp.kafka.bootstrap-servers points at '"
                            + bootstrapServers
                            + "', which is not (entirely) on this machine, while the security protocol"
                            + " is "
                            + protocol
                            + ". Off this machine the clients need TLS and an authenticated identity:"
                            + " set finapp.kafka.security-protocol to SASL_SSL with the SCRAM"
                            + " credential, or point the bootstrap at loopback."
                            + " (ADR-0023; owner decision C11.)");
        }
        if (protocol.startsWith("SASL_")) {
            if (saslUsername.isBlank() || saslPassword.isBlank()) {
                throw new IllegalStateException(
                        "Refusing to start: finapp.kafka.security-protocol is "
                                + protocol
                                + " and the SASL username or password is not set. Set"
                                + " FINAPP_KAFKA_SASL_USERNAME and "
                                + REQUIRED_ENVIRONMENT_VARIABLE
                                + ".");
            }
            if (!local && ConfinedCredential.isMarkedLocalDefault(saslPassword)) {
                throw new IllegalStateException(
                        "Refusing to start: the Kafka SCRAM password is still the published local"
                                + " default, and finapp.kafka.bootstrap-servers points at '"
                                + bootstrapServers
                                + "', which is not on this machine. Set "
                                + REQUIRED_ENVIRONMENT_VARIABLE
                                + ". The published default is not a secret: every reader of this"
                                + " repository has it. See docs/architecture/SECRET_MANAGEMENT.md.");
            }
        }
    }
}
