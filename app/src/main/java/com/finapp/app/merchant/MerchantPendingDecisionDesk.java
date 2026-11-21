package com.finapp.app.merchant;

import com.finapp.app.api.PendingDecisionKind;
import com.finapp.app.api.PendingDecisions;
import com.finapp.app.api.PendingDecisions.PendingDecision;
import com.finapp.app.api.PendingDecisions.PendingDecisionPage;
import com.finapp.merchant.MerchantCollections;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The merchant domain's proposals awaiting a decision, in the one pending-decision shape (`P17-TSK-040`, ADR-C133 decision 3):
 * payout destination changes (`P17-TSK-114`), operator payouts above the four-eyes threshold and merchant closures
 * (`P17-TSK-031`), and BNPL fee assignments and BNPL enablements (`P17-TSK-116`, `P12-TSK-006`) - each {@code PROPOSED} alone, oldest first, each naming its merchant as its subject, which the destination's
 * doors take in their path. Each queue is one read-only transaction over committed rows; the decision stays at each kind's
 * own approve and reject doors, whose conditional transitions answer {@code 409} to a proposal decided after the page was
 * served.
 *
 * <p>Not recorded, as the merchant reads beside them are not (the open destination change's own read included): the queues
 * name proposals by id, their merchant, their proposer and the proposer's reason - never an amount or a destination.
 */
@RequiredArgsConstructor
public final class MerchantPendingDecisionDesk {

    static final String PENDING = "PROPOSED";
    static final String PAYOUT_DESTINATIONS = "pending.merchant.payout-destinations";
    static final String PAYOUT_PROPOSALS = "pending.merchant.payout-proposals";
    static final String CLOSURES = "pending.merchant.closures";
    static final String BNPL_FEE_ASSIGNMENTS = "pending.bnpl.fee-assignments";
    static final String BNPL_ENABLEMENTS = "pending.merchant.bnpl-enablements";

    @NonNull private final MerchantCollections collections;
    @NonNull private final TransactionTemplate transactions;
    @NonNull private final DataSource dataSource;

    /** The payout destination changes awaiting a decision. */
    public PendingDecisionPage payoutDestinations(String status, String cursor, String limit) {
        return queue(PAYOUT_DESTINATIONS, PendingDecisionKind.MERCHANT_PAYOUT_DESTINATION, status, cursor, limit,
                collections::pendingPayoutDestinations);
    }

    /** The operator payouts above the four-eyes threshold awaiting a decision. */
    public PendingDecisionPage payoutProposals(String status, String cursor, String limit) {
        return queue(PAYOUT_PROPOSALS, PendingDecisionKind.MERCHANT_PAYOUT_PROPOSAL, status, cursor, limit, collections::pendingPayoutProposals);
    }

    /** The merchant closures awaiting a decision. */
    public PendingDecisionPage closures(String status, String cursor, String limit) {
        return queue(CLOSURES, PendingDecisionKind.MERCHANT_CLOSURE, status, cursor, limit, collections::pendingClosures);
    }

    /** The BNPL fee assignment proposals awaiting a decision (`P17-TSK-116`'s four eyes). */
    public PendingDecisionPage bnplFeeAssignments(String status, String cursor, String limit) {
        return queue(BNPL_FEE_ASSIGNMENTS, PendingDecisionKind.BNPL_FEE_ASSIGNMENT, status, cursor, limit, collections::pendingBnplFeeAssignments);
    }

    /** The merchant BNPL enablements awaiting a decision. */
    public PendingDecisionPage bnplEnablements(String status, String cursor, String limit) {
        return queue(BNPL_ENABLEMENTS, PendingDecisionKind.MERCHANT_BNPL_ENABLEMENT, status, cursor, limit, collections::pendingBnplEnablements);
    }

    /** One store read of a queue. */
    @FunctionalInterface
    private interface PendingRead {
        List<MerchantCollections.PendingProposal> read(
                Connection unitOfWork, Optional<MerchantCollections.PendingPosition> after, int limit);
    }

    private PendingDecisionPage queue(
            String collection, PendingDecisionKind kind, String status, String cursor, String limit, PendingRead read) {
        PendingDecisions.pendingOnly(status, PENDING);
        int size = PendingDecisions.limit(limit);
        Optional<MerchantCollections.PendingPosition> after = PendingDecisions.position(collection, cursor)
                .map(position -> new MerchantCollections.PendingPosition(position.proposedAt(), position.id()));
        List<MerchantCollections.PendingProposal> rows = transactions.execute(transaction -> {
            Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
            try {
                return read.read(unitOfWork, after, size + 1);
            } finally {
                DataSourceUtils.releaseConnection(unitOfWork, dataSource);
            }
        });
        List<PendingDecision> items = rows.stream()
                .map(row -> PendingDecisions.of(row.id(), kind, row.proposedBy(), row.proposedAt(), row.reason(), row.subject()))
                .toList();
        return PendingDecisions.page(collection, items, size);
    }
}
