package com.finapp.app.accounting;

import com.finapp.accounting.PriorPeriodPostings;
import com.finapp.accounting.Side;
import com.finapp.ledger.AdjustmentOrigin;
import com.finapp.ledger.AdjustmentProposal;
import com.finapp.ledger.AdjustmentProposalId;
import com.finapp.ledger.AdjustmentReasonCode;
import com.finapp.ledger.AdjustmentService;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.OwnedAdjustmentCommand;
import com.finapp.ledger.PostingResult;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * accounting's {@link PriorPeriodPostings} over the ledger's accounting-owned door (`P14-TSK-013`; ADR-0113 decision 4; ADR-0071 section 6; {@code INV-ACC-10}) - in {@code app}, the one module that
 * sees both, and <strong>the one class in the codebase that may name that door</strong> ({@code OwnedAdjustmentSeamIsConfinedTest}). Each method runs on the caller's transaction, so accounting's row and the
 * ledger's proposal move together; the ledger's exceptions pass through as they are, its own error contract. One date for both of the entry's: this class has nowhere to put a second.
 */
@RequiredArgsConstructor
public final class LedgerPriorPeriodPostings implements PriorPeriodPostings {

    @NonNull private final AdjustmentService adjustments;
    @NonNull private final LedgerAccountStore<Connection> accounts;

    @Override
    public Map<UUID, Classified> classify(Connection unitOfWork, Collection<UUID> ledgerAccounts) {
        Map<UUID, Classified> classified = new HashMap<>();
        for (LedgerAccount account : accounts.findAllById(unitOfWork, ledgerAccounts.stream().map(LedgerAccountId::of).toList())) {
            classified.put(account.id().value(), new Classified(account.purpose().name(), account.purpose().ownerKind().name()));
        }
        return classified;
    }

    @Override
    public UUID propose(Connection unitOfWork, LocalDate date, String reference, String reason, List<Line> lines) {
        return adjustments.proposePriorPeriod(unitOfWork, new OwnedAdjustmentCommand(date, date, reference, reason, AdjustmentReasonCode.PRIOR_PERIOD_ADJUSTMENT,
                lines.stream().map(LedgerPriorPeriodPostings::journalLine).toList())).value();
    }

    @Override
    public Posted approve(Connection unitOfWork, UUID proposal) {
        PostingResult posted = adjustments.approvePriorPeriod(unitOfWork, AdjustmentProposalId.of(proposal));
        return new Posted(posted.entryId().value(), posted.replayed());
    }

    @Override
    public void reject(Connection unitOfWork, UUID proposal) {
        adjustments.rejectPriorPeriod(unitOfWork, AdjustmentProposalId.of(proposal));
    }

    @Override
    public List<Line> linesOf(Connection unitOfWork, UUID proposal) {
        return adjustments.find(unitOfWork, AdjustmentProposalId.of(proposal))
                .filter(found -> found.origin() == AdjustmentOrigin.ACCOUNTING)
                .map(AdjustmentProposal::lines)
                .orElse(List.of())
                .stream()
                .map(LedgerPriorPeriodPostings::line)
                .toList();
    }

    private static JournalLine journalLine(Line line) {
        return new JournalLine(LedgerAccountId.of(line.ledgerAccount()), line.side() == Side.DEBIT ? Direction.DEBIT : Direction.CREDIT, line.amount());
    }

    private static Line line(JournalLine line) {
        return new Line(line.account().value(), line.direction() == Direction.DEBIT ? Side.DEBIT : Side.CREDIT, line.amount());
    }
}
