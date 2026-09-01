package com.finapp.platform.outbox;

import com.finapp.sharedkernel.event.EventEnvelope;

/**
 * Queues an event for publication, in the caller's transaction ({@code INV-EVT-01}).
 *
 * <p><strong>The transaction is the caller's, and that is the entire point.</strong> ADR-0005
 * exists because there is no safe moment to publish directly: inside the transaction the broker
 * cannot know whether it will commit, and after it there is a window in which the process dies
 * having committed a fact nobody will ever hear about. Writing the publication record with the
 * fact removes the window by making them one commit.
 *
 * <p>A writer that opened its own transaction would look identical in every test and would
 * silently reintroduce that window, so the unit of work is passed in and never created here —
 * the same reason {@code IdempotencyRecordStore} takes one.
 *
 * <p><strong>Nothing here publishes.</strong> Handing the event to a broker is the relay's job
 * (P0-TSK-020), and no domain code may do it at all — enforced by
 * {@code NoDirectBrokerPublicationRulesTest}.
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection} today, whatever the
 *     Phase 3 data-access decision produces later
 */
public interface OutboxWriter<T> {

    /**
     * Writes {@code envelope} and its payload to the outbox.
     *
     * @param payload the event's own data, opaque to the platform
     * @param payloadMediaType how a consumer should interpret those bytes
     * @throws OutboxWriteException if the event could not be queued — which must fail the
     *     caller's transaction, because a fact committed without its publication record is the
     *     lost event {@code INV-EVT-01} exists to prevent
     */
    void write(T unitOfWork, EventEnvelope envelope, byte[] payload, String payloadMediaType);
}
