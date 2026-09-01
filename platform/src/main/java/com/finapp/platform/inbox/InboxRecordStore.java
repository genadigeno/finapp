package com.finapp.platform.inbox;

import com.finapp.sharedkernel.correlation.CorrelationId;
import java.time.Duration;
import java.time.Instant;

/**
 * Storage for the record that a consumer has handled a message.
 *
 * <p><strong>The transaction is the caller's, and that is the whole guarantee.</strong> The
 * dedupe record and the side effect must commit together. If the record could commit without
 * the effect, a redelivery would be skipped and the message would be lost silently; if the
 * effect could commit without the record, the next redelivery would apply it twice. Both are
 * exactly what {@code INV-IDEM-04} forbids, and a store that opened its own transaction would
 * make one of them inevitable — so the unit of work is passed in and never created here, the
 * same reason {@link com.finapp.platform.outbox.OutboxWriter} takes one.
 *
 * <p><strong>Why a port.</strong> No data-access mechanism has been chosen (unresolved question
 * 12, due Phase 3). {@link InboxConsumer} depends on this interface so the decision stays open
 * rather than being made by accident here.
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection} today, whatever the
 *     Phase 3 decision produces later
 */
public interface InboxRecordStore<T> {

    /** What happened when a message was claimed for processing. */
    enum RecordOutcome {
        /** This delivery is ours to handle. */
        RECORDED,

        /** A committed record already exists: this consumer has handled this message. */
        ALREADY_PROCESSED,

        /**
         * Another instance holds this key in an uncommitted transaction and we declined to wait.
         *
         * <p>Distinct from {@link #ALREADY_PROCESSED} because the outcome is genuinely unknown:
         * the other transaction may still commit or roll back. The correct response is to leave
         * the message unacknowledged and let the broker redeliver it — which costs nothing,
         * because at-least-once delivery means redelivery is the normal state of affairs. This
         * is where a consumer differs from a command: an idempotent command has a caller
         * waiting for an answer, and a message does not.
         */
        CONTENDED
    }

    /**
     * Records that {@code key} is being handled now, in the caller's transaction.
     *
     * <p>Not an exception when the message was already processed: a duplicate delivery is the
     * mechanism working, not a fault. The broker is expected to deliver it again.
     *
     * @param retention how long the record must outlive processing. The instant is computed by
     *     the database, not from {@code processedAt}: a sweeper on another instance compares it
     *     against the server clock, and ADR-0014 does not allow that comparison to cross two
     *     clocks. Too long merely costs storage; too short admits a duplicate.
     */
    RecordOutcome record(
            T unitOfWork,
            InboxKey key,
            String messageType,
            CorrelationId correlationId,
            Instant processedAt,
            Duration retention);
}
