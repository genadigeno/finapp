package com.finapp.app.crossborder;

import com.finapp.app.api.PendingDecisions;
import com.finapp.app.api.PendingDecisions.PendingDecision;
import com.finapp.app.api.PendingDecisions.PendingDecisionPage;
import com.finapp.crossborder.CrossBorderCollections;
import com.finapp.crossborder.TransactionRunner;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Cross-border's proposals awaiting a decision, in the one pending-decision shape (`P17-TSK-040`, ADR-C133 decision 3): the
 * corridor enable requests and the corridor policy versions - each {@code PROPOSED} alone, oldest first. Each queue is one
 * read-only transaction over committed rows; the decision stays at each kind's own approve and reject doors, whose conditional
 * transitions answer {@code 409} to a proposal decided after the page was served.
 *
 * <p>Not recorded, as the corridor enable-request and corridor-policy lists are not: platform configuration, never a
 * customer's data.
 */
@RequiredArgsConstructor
public final class CrossBorderPendingDecisionDesk {

    static final String PENDING = "PROPOSED";
    static final String CORRIDOR_ENABLE_REQUESTS = "pending.crossborder.corridor-enable-requests";
    static final String CORRIDOR_POLICIES = "pending.crossborder.corridor-policies";

    @NonNull private final CrossBorderCollections collections;
    @NonNull private final TransactionRunner transactions;

    /** The corridor enable requests awaiting a decision; the subject is the corridor ({@code EUR-USD-US}). */
    public PendingDecisionPage corridorEnableRequests(String status, String cursor, String limit) {
        return queue(CORRIDOR_ENABLE_REQUESTS, "crossborder.CorridorEnableRequest", status, cursor, limit,
                collections::pendingCorridorEnableRequests);
    }

    /** The corridor policy versions awaiting a decision; the subject is the version number. */
    public PendingDecisionPage corridorPolicies(String status, String cursor, String limit) {
        return queue(CORRIDOR_POLICIES, "crossborder.CorridorPolicy", status, cursor, limit,
                collections::pendingCorridorPolicies);
    }

    /** One store read of a queue. */
    @FunctionalInterface
    private interface PendingRead {
        List<CrossBorderCollections.PendingProposal> read(
                Connection unitOfWork, Optional<CrossBorderCollections.PendingPosition> after, int limit);
    }

    private PendingDecisionPage queue(
            String collection, String kind, String status, String cursor, String limit, PendingRead read) {
        PendingDecisions.pendingOnly(status, PENDING);
        int size = PendingDecisions.limit(limit);
        Optional<CrossBorderCollections.PendingPosition> after = PendingDecisions.position(collection, cursor)
                .map(position -> new CrossBorderCollections.PendingPosition(position.proposedAt(), position.id()));
        List<PendingDecision> items = transactions.inTransaction(uow -> read.read(uow, after, size + 1)).stream()
                .map(row -> PendingDecisions.of(row.id(), kind, row.proposedBy(), row.proposedAt(), row.reason(), row.subject()))
                .toList();
        return PendingDecisions.page(collection, items, size);
    }
}
