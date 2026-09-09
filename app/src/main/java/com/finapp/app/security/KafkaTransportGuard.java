package com.finapp.app.security;

import java.util.Objects;

/**
 * Refuses to start when the Kafka bootstrap points off this machine over plaintext
 * (`P2-TSK-001`).
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
 * {@code DOD-BUILD} requires a clean clone to run with no machine-specific setup.
 *
 * <p><strong>The bar is "not PLAINTEXT", not "verify-full".</strong> Kafka's secured protocols
 * ({@code SSL}, {@code SASL_SSL}) verify the broker certificate by default — hostname
 * verification is on unless deliberately disabled — so unlike the JDBC driver there is no
 * widely-used mode that encrypts while verifying nothing. What this guard cannot see is a
 * configuration that disabled that verification elsewhere; recorded as the limit, with Phase 15
 * owning the full TLS/SASL deployment posture.
 */
public class KafkaTransportGuard {

    public KafkaTransportGuard(String bootstrapServers, String securityProtocol) {
        Objects.requireNonNull(bootstrapServers, "bootstrapServers must not be null");
        Objects.requireNonNull(securityProtocol, "securityProtocol must not be null");
        if (!"PLAINTEXT".equalsIgnoreCase(securityProtocol.trim())) {
            return;
        }
        if (DatabaseEndpoint.allLoopbackHostPorts(bootstrapServers)) {
            return;
        }
        throw new IllegalStateException(
                "Refusing to start: finapp.kafka.bootstrap-servers points at '"
                        + bootstrapServers
                        + "', which is not (entirely) on this machine, while the security protocol"
                        + " is PLAINTEXT. Every published event would cross the network in the"
                        + " clear. Set finapp.kafka.security-protocol to SSL or SASL_SSL with the"
                        + " matching client configuration, or point the bootstrap at loopback."
                        + " (ADR-0023; the deployment posture is Phase 15's.)");
    }
}
