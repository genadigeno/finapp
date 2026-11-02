package com.finapp.app.accounting;

import com.finapp.accounting.AccountingBreakStore;
import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriodStore;
import com.finapp.accounting.CloseRuns;
import com.finapp.accounting.CloseStatusReads;
import com.finapp.accounting.ValuationRateSetStatus;
import com.finapp.accounting.ValuationRateSetStore;
import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * {@code CLOSE_STATUS}'s reads over accounting's own stores (`P14-TSK-017`) - in {@code app} because report code reaches no store:
 * a store also writes, and {@code ReportingNeverWritesTest} refuses report code one on its reach. This class calls only their reads
 * (the same guard holds its accesses to that list, method references counted) on a {@code ReadOnlySnapshot} that prepares nothing
 * else - the run through {@link CloseRuns#view}, so "complete" is the very judgement {@code P14-TSK-012}'s proposal will read.
 */
@RequiredArgsConstructor
public final class AccountingCloseStatusReads implements CloseStatusReads {

    @NonNull private final AccountingPeriodStore periods;
    @NonNull private final CloseRuns runs;
    @NonNull private final AccountingBreakStore breaks;
    @NonNull private final ValuationRateSetStore sets;

    @Override
    public Reading read(Connection snapshot, AccountingPeriodId id) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(id, "id");
        // The view first: it refuses a period that does not exist.
        Optional<CloseRuns.RunView> view = runs.view(snapshot, id);
        AccountingPeriodStore.PeriodRow period = periods.find(snapshot, id).orElseThrow(() -> new IllegalStateException(
                "a period the snapshot just answered is in the snapshot"));
        SortedMap<String, Long> byStatus = new TreeMap<>();
        for (ValuationRateSetStatus status : ValuationRateSetStatus.values()) {
            byStatus.put(status.name(), 0L);
        }
        sets.countByStatus(snapshot, id).forEach((status, count) -> byStatus.put(status.name(), count));
        return new Reading(period.month(), period.status(), period.revision(), period.lockedAt(),
                period.pinned().map(AccountingPeriodStore.Pin::version), view.map(AccountingCloseStatusReads::run),
                breaks.countByStatus(snapshot, period.month()),
                sets.approvedFor(snapshot, id).map(set -> new ApprovedSet(set.id().value(), set.asOf(), set.rateCount(),
                        set.decidedAt())),
                byStatus);
    }

    private static Run run(CloseRuns.RunView view) {
        return new Run(view.run().lockedAt(), view.current(), view.complete(), view.run().startedAt(),
                view.run().checksConcludedAt(), view.checks().stream()
                        .map(check -> new Check(check.check(), check.attempts(), check.latest().status(),
                                check.latest().failures(), check.latest().judgedBy(), check.latest().concludedAt()))
                        .toList());
    }
}
