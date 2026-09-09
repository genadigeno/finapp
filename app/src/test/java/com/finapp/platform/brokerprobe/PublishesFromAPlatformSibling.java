package com.finapp.platform.brokerprobe;

/**
 * A deliberate violation fixture for {@code NoDirectBrokerPublicationRulesTest}: a class in a
 * platform package that is NOT the outbox, touching a broker client. Its job is to prove the
 * publishing exemption is the outbox package and not the platform module — the widening the
 * `P2-TSK-001` narrowing exists to prevent. Never production code; lives in test sources and is
 * only ever imported directly by the rule's own teeth test.
 */
@SuppressWarnings("unused")
public final class PublishesFromAPlatformSibling {
    void announce(org.apache.kafka.clients.producer.Probe producer) {
        producer.send("transfers", new byte[0]);
    }
}
