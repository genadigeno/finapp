package com.finapp.app.fx;

import com.finapp.app.api.PendingDecisionKind;
import com.finapp.app.api.PendingDecisions;
import com.finapp.app.api.PendingDecisions.PendingDecision;
import com.finapp.app.api.PendingDecisions.PendingDecisionPage;
import com.finapp.fx.FxCollections;
import com.finapp.fx.TransactionRunner;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * FX's proposals awaiting a decision, in the one pending-decision shape (`P17-TSK-040`, ADR-C133 decision 3): the pair and
 * provider enable requests, the pricing policy versions and the trade reversals - each {@code PROPOSED} alone, oldest first.
 * Each queue is one read-only transaction over committed rows; the decision stays at each kind's own approve and reject doors,
 * whose conditional transitions answer {@code 409} to a proposal decided after the page was served.
 *
 * <p>Not recorded: the queues name proposals by id, their proposer and the proposer's reason, never a customer's amounts, as
 * FX's enable-request and pricing-policy lists are not recorded.
 */
@RequiredArgsConstructor
public final class FxPendingDecisionDesk {

    static final String PENDING = "PROPOSED";
    static final String ENABLE_REQUESTS = "pending.fx.enable-requests";
    static final String PRICING_POLICIES = "pending.fx.pricing-policies";
    static final String TRADE_REVERSALS = "pending.fx.trade-reversals";

    @NonNull private final FxCollections collections;
    @NonNull private final TransactionRunner transactions;

    /** The pair and provider enable requests awaiting a decision; the subject is the pair or the provider's code. */
    public PendingDecisionPage enableRequests(String status, String cursor, String limit) {
        PendingDecisions.pendingOnly(status, PENDING);
        int size = PendingDecisions.limit(limit);
        Optional<FxCollections.EnableRequestCursor> after = PendingDecisions.position(ENABLE_REQUESTS, cursor)
                .map(position -> new FxCollections.EnableRequestCursor(position.proposedAt(), position.id()));
        List<PendingDecision> items = transactions.inTransaction(uow -> collections.pendingEnableRequests(uow, after, size + 1))
                .stream()
                .map(request -> PendingDecisions.ofSubject(request.id(), PendingDecisionKind.FX_ENABLE_REQUEST,
                        request.proposedBy(), request.proposedAt(), request.reason(), request.subject().kind().name(),
                        request.subject().subject()))
                .toList();
        return PendingDecisions.page(ENABLE_REQUESTS, items, size);
    }

    /** The pricing policy versions awaiting activation; the subject is the version number. */
    public PendingDecisionPage pricingPolicies(String status, String cursor, String limit) {
        return queue(PRICING_POLICIES, PendingDecisionKind.FX_PRICING_POLICY, status, cursor, limit, collections::pendingPricingPolicies);
    }

    /** The trade reversals awaiting a decision; the subject is the trade, which the reversal's doors name. */
    public PendingDecisionPage tradeReversals(String status, String cursor, String limit) {
        return queue(TRADE_REVERSALS, PendingDecisionKind.FX_TRADE_REVERSAL, status, cursor, limit, collections::pendingTradeReversals);
    }

    /** One store read of a queue. */
    @FunctionalInterface
    private interface PendingRead {
        List<FxCollections.PendingProposal> read(Connection unitOfWork, Optional<FxCollections.PendingPosition> after, int limit);
    }

    private PendingDecisionPage queue(
            String collection, PendingDecisionKind kind, String status, String cursor, String limit, PendingRead read) {
        PendingDecisions.pendingOnly(status, PENDING);
        int size = PendingDecisions.limit(limit);
        Optional<FxCollections.PendingPosition> after = PendingDecisions.position(collection, cursor)
                .map(position -> new FxCollections.PendingPosition(position.proposedAt(), position.id()));
        List<PendingDecision> items = transactions.inTransaction(uow -> read.read(uow, after, size + 1)).stream()
                .map(row -> PendingDecisions.of(row.id(), kind, row.proposedBy(), row.proposedAt(), row.reason(), row.subject()))
                .toList();
        return PendingDecisions.page(collection, items, size);
    }
}
