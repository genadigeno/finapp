package com.finapp.platform.inbox;

import java.sql.Connection;

/**
 * A consuming module's reaction to one kind of event (`P2-TSK-002`).
 *
 * <p>The registration seam between the consumer shell and business modules: a module declares
 * one of these as a bean, and the shell subscribes to its topic, filters on its event type,
 * enters the flow's correlation and runs {@link #handle} through {@link InboxConsumer} — so the
 * handler's effect happens at most once per {@link #consumerName()} however many times the
 * record is delivered ({@code INV-IDEM-04}). No Kafka type appears anywhere in this contract,
 * deliberately: the client dependency stays inside the shell's own package, and a handler is
 * testable with a connection and a {@link ReceivedEvent} and nothing else.
 *
 * <h2>What a handler must still be, and the shell does not make it</h2>
 *
 * <p>{@code INV-EVT-04} requires tolerance of delay, reordering and replay as well as
 * duplication, and <strong>only duplication is handled for you</strong> — the
 * {@link InboxConsumer} statement of that limit applies here verbatim. The shell preserves the
 * broker's per-partition order (one aggregate rides one partition, {@code P2-TSK-001}), but an
 * event delayed past a redelivery or replayed after the dedupe retention still reaches the
 * handler, which must be order-independent or carry its own ordering key.
 */
public interface InboxEventHandler {

    /**
     * The inbox identity of this handler: what {@code platform.inbox_message} scopes dedupe to.
     *
     * <p>Convention: {@code <module>.<component>} — {@code "kyc.caseOpening"}. The segment
     * before the first dot names the consuming module, and the composition root derives the
     * Kafka consumer group from it (one group per consuming module), so a module's consumption
     * progresses independently of its neighbours'. Stable for the record's whole retention:
     * renaming it makes every past delivery look unhandled and replays it into the new name.
     */
    String consumerName();

    /** The topic subscribed to — {@code finapp.<producing module>} ({@code P2-TSK-001}). */
    String topic();

    /** The event type this handler reacts to; everything else on the topic passes it by. */
    String eventType();

    /**
     * Reacts to the event, inside the shell's transaction.
     *
     * <p>The effect and the dedupe record commit together on {@code unitOfWork}, or neither
     * does. Throwing is the correct way to fail: the transaction rolls back — dedupe record
     * included — the record is not acknowledged, and the redelivery retries. A handler that
     * swallows its own failure records the message as processed and drops it.
     */
    void handle(Connection unitOfWork, ReceivedEvent event);
}
