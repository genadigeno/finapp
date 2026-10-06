package com.finapp.fx;

import java.sql.Connection;
import java.util.Optional;
import java.util.UUID;

/** The trade reversal's persistence (`P9-TSK-025`, fx {@code V009}): the four-eyes machine and its history. */
public interface TradeReversalStore {

    /** A reversal as stored. */
    record Row(
            UUID id,
            FxTradeId tradeId,
            String status,
            String proposedBy,
            Optional<String> decidedBy,
            Optional<UUID> reversalEntryId) {}

    /** Births a {@code PROPOSED} reversal; false when the trade already carries a live one (the partial unique). */
    boolean propose(Connection unitOfWork, UUID id, FxTradeId tradeId, String proposedBy, String reason, String correlationId);

    /** The reversal, no lock. */
    Optional<Row> find(Connection unitOfWork, UUID id);

    /** The reversal {@code FOR UPDATE}. */
    Optional<Row> lock(Connection unitOfWork, UUID id);

    /** {@code PROPOSED -> to} with the decider, their reason and - when approved - the mirror entry; false otherwise. */
    boolean decide(Connection unitOfWork, UUID id, String to, String decidedBy, String reason, Optional<UUID> reversalEntryId);

    /** One history row per edge. */
    void appendEvent(Connection unitOfWork, UUID eventId, UUID reversalId, Optional<String> from, String to, String actor,
            String reason);
}
