package com.finapp.app.transfers;

import com.finapp.app.api.PendingDecisionKind;
import com.finapp.app.api.PendingDecisions;
import com.finapp.app.api.PendingDecisions.PendingDecision;
import com.finapp.app.api.PendingDecisions.PendingDecisionPage;
import com.finapp.transfers.TransferReversalProposalStore;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The transfer reversals (`P17-TSK-031`) awaiting a decision, in the one pending-decision shape (`P17-TSK-040`, ADR-C133 decision 3): {@code PROPOSED}
 * alone, oldest first. One read-only transaction over committed rows; the decision stays at the kind's own approve and reject
 * doors, whose conditional transitions answer {@code 409} to a proposal decided after the page was served.
 *
 * <p>Not recorded: each item names the transfer, the proposer and the proposer's reason, never an amount or a customer's data.
 */
@RequiredArgsConstructor
public final class TransferReversalPendingDecisionDesk {

    static final String PENDING = "PROPOSED";
    static final String QUEUE = "pending.transfers.reversals";
    static final PendingDecisionKind KIND = PendingDecisionKind.TRANSFERS_REVERSAL;

    @NonNull private final TransferReversalProposalStore<Connection> store;
    @NonNull private final TransactionTemplate transactions;
    @NonNull private final DataSource dataSource;

    /** A page of the queue. */
    public PendingDecisionPage pending(String status, String cursor, String limit) {
        PendingDecisions.pendingOnly(status, PENDING);
        int size = PendingDecisions.limit(limit);
        Optional<TransferReversalProposalStore.PendingPosition> after = PendingDecisions.position(QUEUE, cursor)
                .map(position -> new TransferReversalProposalStore.PendingPosition(position.proposedAt(), position.id()));
        List<TransferReversalProposalStore.PendingProposal> rows = transactions.execute(transaction -> {
            Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
            try {
                return store.pendingReversals(unitOfWork, after, size + 1);
            } finally {
                DataSourceUtils.releaseConnection(unitOfWork, dataSource);
            }
        });
        List<PendingDecision> items = rows.stream()
                .map(row -> PendingDecisions.of(row.id(), KIND, row.proposedBy(), row.proposedAt(), row.reason(), row.subject()))
                .toList();
        return PendingDecisions.page(QUEUE, items, size);
    }
}
