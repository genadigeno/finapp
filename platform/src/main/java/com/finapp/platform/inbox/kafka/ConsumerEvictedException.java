package com.finapp.platform.inbox.kafka;

/**
 * The group refused this consumer's offset commit because it had already evicted the member
 * (`P15-TSK-025`): a batch ran past {@link KafkaEventReceiver#MAX_POLL_INTERVAL}, or the session
 * lapsed.
 *
 * <p>The receiver's translation of the client's {@code CommitFailedException}, so that the
 * composition root can count it apart from an ordinary thrown poll without touching a Kafka type
 * ({@code NoDirectBrokerPublicationRulesTest}). Nothing is lost and nothing is applied twice: the
 * batch's effects are committed and its records redeliver into the dedupe. What it costs is
 * throughput, so it is an operator's signal rather than a correctness one.
 *
 * <p>Carries the failing exception's class name and nothing else: the client's message can name the
 * group and the member ({@code INV-AUD-02}).
 */
public final class ConsumerEvictedException extends RuntimeException {

    // An exception is serializable, so the pin is mandatory under -Werror.
    private static final long serialVersionUID = 1L;

    /**
     * @param cause the failing exception's simple class name, never its message. Public so the
     *     composition root's tests can drive an evicted loop without a Kafka type on their classpath.
     */
    public ConsumerEvictedException(String cause) {
        super("the group evicted this consumer: " + cause);
    }
}
