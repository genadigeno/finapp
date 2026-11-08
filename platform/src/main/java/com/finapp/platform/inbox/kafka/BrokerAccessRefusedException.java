package com.finapp.platform.inbox.kafka;

/**
 * The broker refused this consumer: its credential failed to authenticate, or its principal may
 * not read a topic or join the group (`P15-TSK-026`).
 *
 * <p>The receiver's translation of the client's authentication and authorization exceptions, so
 * that the composition root can tell a configuration fault from an outage without touching a
 * Kafka type ({@code NoDirectBrokerPublicationRulesTest}). The distinction matters there: a broker
 * that is down costs consumption and nothing else, while a refused credential is what a release
 * with a wrong secret looks like, and fails the consumers' readiness contributor.
 *
 * <p>A failed TLS handshake is not translated, though the client files it under authentication
 * (`P15-TSK-026`'s review fix round): the broker's side fails a handshake - an expired or rotated
 * certificate - as surely as this client's, and failing readiness on a fault every replica meets at
 * once would take the fleet out of service for something no rollout carried.
 *
 * <p>Carries the refusing exception's class name and nothing else: the client's message can name
 * the broker and the principal ({@code INV-AUD-02}).
 */
public final class BrokerAccessRefusedException extends RuntimeException {

    // An exception is serializable, so the pin is mandatory under -Werror.
    private static final long serialVersionUID = 1L;

    /**
     * @param refusal the refusing exception's simple class name, never its message. Public so the
     *     composition root's tests can drive a refused loop without a Kafka type on their classpath.
     */
    public BrokerAccessRefusedException(String refusal) {
        super("the broker refused this consumer: " + refusal);
    }
}
