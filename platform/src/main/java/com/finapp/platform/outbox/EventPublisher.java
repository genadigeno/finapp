package com.finapp.platform.outbox;

/**
 * Hands an event to the messaging transport. The one place in the platform allowed to do so.
 *
 * <p><strong>A port, not an implementation.</strong> ADR-0008 puts external systems behind
 * adapters, and a broker is an external system with all of the usual properties: it is slow,
 * it is sometimes absent, and it sometimes accepts a message and reports a failure anyway. The
 * relay is written against this interface so that its correctness — ordering, retry, backoff,
 * abandonment, crash recovery — can be exercised against a broker that behaves badly on demand,
 * which no real broker will do reliably enough to test against.
 *
 * <p><strong>No adapter exists yet, deliberately.</strong> Writing a Kafka adapter here would
 * decide the wire format, the topic naming scheme, the producer's acknowledgement and retry
 * configuration, and would put a broker client on the classpath — four decisions that belong
 * with the phase that has events to publish, not with the phase that builds the relay.
 * {@code EVENT_ARCHITECTURE.md} defers the wire format for exactly this reason. Until one
 * exists, {@code NoDirectBrokerPublicationRulesTest} forbids every module from touching a
 * broker client, including this package.
 *
 * <h2>What an adapter must guarantee</h2>
 *
 * <ul>
 *   <li><strong>Order.</strong> Use {@link PendingEvent#partitionKey()} as the partition key.
 *       Events for one aggregate must not be reordered by the transport.
 *   <li><strong>Bytes.</strong> Transmit {@link PendingEvent#payload()} unchanged. Consumers
 *       must receive what the producing transaction committed.
 *   <li><strong>Failure is an exception.</strong> Returning normally means the broker has
 *       accepted the message. An adapter that returns before its acknowledgement arrives has
 *       converted at-least-once delivery into at-most-once, which loses events silently.
 *   <li><strong>Blocking, and bounded.</strong> The relay holds a database transaction and a
 *       lock on the aggregate while this call runs, so an adapter with no timeout stalls that
 *       aggregate for as long as the broker takes to notice it is unwell.
 *   <li><strong>No event content in exception messages.</strong> The relay records the failure
 *       in {@code last_error}, which operators read ({@code INV-AUD-02}).
 * </ul>
 *
 * <h2>What it must not promise</h2>
 *
 * <p>Exactly-once delivery. The relay publishes and then records publication, and a crash
 * between the two republishes the event on restart. ADR-0005 chose at-least-once delivery with
 * consumer-side deduplication precisely because the alternative cannot be honestly provided,
 * and an adapter claiming otherwise would invite consumers to skip their inbox.
 */
@FunctionalInterface
public interface EventPublisher {

    /**
     * Publishes one event, returning only once the transport has accepted it.
     *
     * @throws RuntimeException if the event was not accepted; the relay treats any exception as
     *     a failed attempt and schedules a retry
     */
    void publish(PendingEvent event);
}
