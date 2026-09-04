package com.finapp.platform.inbox;

import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.sharedkernel.correlation.Correlation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a message handler at most once per consumer, however many times the message arrives.
 *
 * <p>The consuming half of ADR-0005. The relay guarantees an event is <em>delivered</em>, and
 * deliberately guarantees no more than that: it publishes and then records publication, so a
 * crash between the two republishes. This class is what makes that acceptable — the effect
 * happens once even though the delivery may not.
 *
 * <h2>The dedupe record and the side effect commit together</h2>
 *
 * <p>Both are written in the caller's transaction, and neither can survive without the other.
 * The two orderings that don't do this are both broken, in opposite directions:
 *
 * <ul>
 *   <li><strong>Record, commit, then handle:</strong> a crash in between loses the message
 *       permanently. The record says it was handled; nothing did.
 *   <li><strong>Handle, commit, then record:</strong> a crash in between applies the effect
 *       twice on redelivery — the duplicate charge {@code INV-IDEM-04} exists to prevent.
 * </ul>
 *
 * <p>There is no ordering of two commits that is correct, which is the same shape of argument
 * ADR-0005 makes about publishing, and the same answer: one transaction.
 *
 * <h2>Record first, then handle</h2>
 *
 * <p>Within that one transaction the dedupe row is inserted <em>before</em> the handler runs.
 * Atomicity is identical either way, so this is not about correctness of the commit — it is
 * about what a concurrent duplicate does. Inserting first makes the duplicate block on the
 * primary key immediately, so it never enters the handler at all. Handling first would let two
 * instances run the same handler simultaneously and only discover the collision at the end,
 * after both had done whatever work the handler does.
 *
 * <h2>Running as N instances (ADR-0014)</h2>
 *
 * <p>Two instances receiving the same redelivery at the same moment is the case this is built
 * for, not an edge of it. The primary key on {@code (consumer, dedupe_key)} is the arbiter; the
 * loser is told {@link Outcome#CONTENDED} after a short bounded wait rather than blocking, and
 * the message is simply redelivered. That trade is available here and not in the idempotency
 * kernel, because no caller is waiting for an answer.
 *
 * <h2>What this does not do</h2>
 *
 * <p>{@code INV-EVT-04} requires consumers to tolerate delay, reordering and replay as well as
 * duplication. <strong>Only duplication is handled here.</strong> A handler that would be wrong
 * seeing {@code TransferCompleted} before {@code TransferInitiated} is wrong whether or not it
 * deduplicates; the answer is an order-independent handler or an explicit ordering key, never a
 * larger inbox. This is stated because a dedupe wrapper is precisely the component people later
 * assume solved ordering too.
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public final class InboxConsumer<T> {

    private static final Logger log = LoggerFactory.getLogger(InboxConsumer.class);

    /** What happened to one delivery. */
    public enum Outcome {
        /** The handler ran. Its effect and the dedupe record commit with the caller. */
        PROCESSED,

        /** This consumer has already handled this message. The handler did not run. */
        SKIPPED_DUPLICATE,

        /**
         * Another instance is handling this delivery right now.
         *
         * <p>The handler did not run and <strong>the message must not be acknowledged</strong>:
         * the other transaction may yet roll back, in which case this message still needs
         * handling by somebody. Leaving it unacknowledged costs one redelivery, which is free
         * under at-least-once delivery, and is the only answer that is correct whichever way
         * the other transaction goes.
         */
        CONTENDED
    }

    /** A message handler. Runs inside the caller's transaction, or does not run at all. */
    @FunctionalInterface
    public interface Handler<T> {
        void handle(T unitOfWork);
    }

    private final InboxRecordStore<T> store;
    private final Clock clock;
    private final Duration retention;

    /**
     * @param retention how long a dedupe record outlives processing. Must exceed every window
     *     in which the message can be redelivered — see {@code DATA_MIGRATIONS.md} §9. Too long
     *     costs storage; too short admits the duplicate effect this class exists to prevent.
     */
    public InboxConsumer(InboxRecordStore<T> store, Clock clock, Duration retention) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(retention, "retention must not be null");
        if (retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("retention must be positive but was " + retention);
        }
        this.retention = retention;
    }

    /**
     * Handles {@code key} exactly once for its consumer.
     *
     * @param unitOfWork the caller's transaction; the handler's effect and the dedupe record
     *     commit or roll back together
     * @return what happened; never null
     * @throws RuntimeException whatever the handler threw. It is deliberately not caught: the
     *     caller's transaction must roll back, taking the dedupe record with it, so that the
     *     redelivery is handled rather than skipped. A wrapper that swallowed handler failures
     *     would record the message as processed and drop it.
     */
    public Outcome consume(T unitOfWork, InboxKey key, String messageType, Handler<T> handler) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(handler, "handler must not be null");
        // Validated here as well as in the store, for the reason InboxKey gives about its own
        // bounds: a caller should get a domain error from the method it called, not one from
        // three layers down. Arguments are checked before the ambient correlation, so a caller
        // who got both wrong is told about the thing it passed rather than the thing it did not.
        Objects.requireNonNull(messageType, "messageType must not be null");
        if (messageType.isBlank()) {
            // Not defaulted to the key or to "unknown": the type is what makes a record
            // diagnosable months later, and a column full of "unknown" is not a record.
            throw new IllegalArgumentException("messageType must not be blank");
        }

        Correlation correlation =
                CorrelationContext.current()
                        .orElseThrow(
                                () ->
                                        // Not defaulted to a fresh identifier: that would produce
                                        // a row claiming a flow that never existed, and quietly
                                        // break the chain from the producing command to this
                                        // effect. The caller enters the scope from the message's
                                        // envelope before consuming.
                                        new IllegalStateException(
                                                "No correlation is in scope. Enter one from the "
                                                        + "message envelope before consuming, so the "
                                                        + "effect can be traced to the flow that "
                                                        + "caused it."));

        Instant now = Instant.now(clock);
        InboxRecordStore.RecordOutcome recorded =
                store.record(unitOfWork, key, messageType, correlation.correlationId(), now, retention);

        return switch (recorded) {
            case RECORDED -> {
                handler.handle(unitOfWork);
                yield Outcome.PROCESSED;
            }
            case ALREADY_PROCESSED -> {
                // Debug, not warn: a duplicate delivery is the normal operation of an
                // at-least-once transport. Logging it as a problem would train operators to
                // ignore the log, which is worse than not logging it at all.
                log.debug("Skipping {} for {}: already processed", messageType, key);
                yield Outcome.SKIPPED_DUPLICATE;
            }
            case CONTENDED -> {
                log.info(
                        "Another instance is handling {} for {}; leaving it unacknowledged for "
                                + "redelivery",
                        messageType,
                        key);
                yield Outcome.CONTENDED;
            }
        };
    }
}
