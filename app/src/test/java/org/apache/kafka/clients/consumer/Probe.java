package org.apache.kafka.clients.consumer;

/**
 * A stand-in for a broker <em>consumer</em> client, used only by
 * {@code NoDirectBrokerPublicationRulesTest} — the consuming twin of the producer-side
 * {@code org.apache.kafka.clients.producer.Probe}, and in a Kafka package for that class's
 * reason: the rule matches broker types by package name, so looking like a client means being in
 * the package. It exists because `P2-TSK-002` widened the rule's exemption to the inbox's Kafka
 * adapter, and a widening without a fixture proving its precision is the exact drift the
 * producer-side probe was written to prevent.
 */
@SuppressWarnings("unused")
public final class Probe {

    public Object poll(long timeoutMillis) {
        // Deliberately empty. Being called is the violation, not what it does.
        return null;
    }
}
