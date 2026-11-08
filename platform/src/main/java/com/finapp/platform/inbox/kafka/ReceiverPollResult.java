package com.finapp.platform.inbox.kafka;

/**
 * What one {@link KafkaEventReceiver#pollOnce()} did, for the loop that feeds metrics
 * (`P2-TSK-002`) — the {@code RelayPollResult} shape on the consuming side.
 *
 * <p>{@code duplicates} and {@code contended} are the two rates the inbox-metrics debt row named:
 * a rising duplicate rate is a signal about the transport, a rising contention rate about
 * consumer concurrency. {@code failed} counts records that stalled their partition — a handler
 * failure, an unopenable unit of work, or a malformed record — each of which is also logged with
 * its class.
 *
 * @param processed records whose handler effect committed in this poll
 * @param duplicates records skipped because every interested consumer had already handled them,
 *     a parked or discarded record's redelivery among them
 * @param contended records left unacknowledged because another instance held the dedupe key
 * @param failed records that stalled their partition for redelivery
 * @param parked records set aside in this poll, their budget spent (`P15-TSK-028`); acknowledged,
 *     so their partition went on
 */
public record ReceiverPollResult(int processed, int duplicates, int contended, int failed, int parked) {

    /** An empty poll — nothing arrived, or the receiver was woken for shutdown mid-poll. */
    public static final ReceiverPollResult NOTHING = new ReceiverPollResult(0, 0, 0, 0, 0);

    /** A poll that parked nothing. */
    public ReceiverPollResult(int processed, int duplicates, int contended, int failed) {
        this(processed, duplicates, contended, failed, 0);
    }
}
