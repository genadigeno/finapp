package com.finapp.lending;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PurposeBalances;
import com.finapp.lending.LoanServicingStore.LineAwaitingAccrual;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The accrual lag (`P11-TSK-016`'s instalment arm; its credit line arm `P11-TSK-023`, carried from `-016` through `-021`;
 * {@code PHASE_11_PLAN.md} section 15, ADR-0094): how long the oldest elapsed date that should have accrued and has not
 * has been over, on the database clock in its pinned zone - across every account in servicing.
 *
 * <ul>
 *   <li><strong>An instalment loan</strong> accrues every date from its accrual start ({@link LoanServicingStore#oldestUnaccruedLag},
 *       one statement);
 *   <li><strong>a credit line</strong> accrues only the dates that ended with principal drawn - a line paid down to zero
 *       (`P11-TSK-023`) writes no accrual row for its zero days, so "not yet accrued" alone would read every such day as
 *       lag. Its arm asks the journal lines: for each line in servicing whose next accrual date has elapsed
 *       ({@link LoanServicingStore#linesAwaitingAccrual}), the first of those dates whose base
 *       {@code LOAN_PRINCIPAL + LOAN_PRINCIPAL_DUE} - over the lines valued on or before it, ONE read - is positive (the
 *       accrual step's own rule). None: the line is not lagging.
 * </ul>
 *
 * <p>Read-only, lock-free and decides nothing: a gauge's figure (cached behind its refresh floor by the meter), never truth.
 * The line arm is one ledger read per candidate line - lines ever drawn and not accrued through yesterday - a cost the
 * meter's refresh floor bounds; a published read joining the journal lines is `P11-TSK-030`'s to choose if the fleet's
 * size asks for it (recorded).
 */
@RequiredArgsConstructor
public final class AccrualLags {

    @NonNull private final LoanServicingStore store;
    @NonNull private final LedgerAccountStore<Connection> ledgerAccounts;
    @NonNull private final PurposeBalances<Connection> balances;

    /** The oldest lag across every account in servicing, both kinds; empty when nothing is lagging. */
    public Optional<Duration> oldest(Connection unitOfWork) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Optional<Duration> oldest = store.oldestUnaccruedLag(unitOfWork);
        Instant now = store.databaseNow(unitOfWork);
        for (LineAwaitingAccrual line : store.linesAwaitingAccrual(unitOfWork)) {
            Optional<Duration> lag = lineLag(unitOfWork, line, now);
            if (lag.isPresent() && (oldest.isEmpty() || lag.get().compareTo(oldest.get()) > 0)) {
                oldest = lag;
            }
        }
        return oldest;
    }

    /** The line's lag: from the end of its first unaccrued date with principal outstanding; empty when there is none. */
    Optional<Duration> lineLag(Connection unitOfWork, LineAwaitingAccrual line, Instant now) {
        Optional<LedgerAccountId> principal = owned(unitOfWork, line, AccountPurpose.LOAN_PRINCIPAL);
        Optional<LedgerAccountId> principalDue = owned(unitOfWork, line, AccountPurpose.LOAN_PRINCIPAL_DUE);
        if (principal.isEmpty() || principalDue.isEmpty()) {
            return Optional.empty();
        }
        LocalDate lastElapsed = line.today().minusDays(1);
        List<PurposeBalances.DatedLine> lines =
                balances.datedLinesOn(unitOfWork, Set.of(principal.get(), principalDue.get()), lastElapsed);
        Money base = null;
        int next = 0;
        for (LocalDate date = line.nextDate(); !date.isAfter(lastElapsed); date = date.plusDays(1)) {
            while (next < lines.size() && !lines.get(next).valueDate().isAfter(date)) {
                PurposeBalances.DatedLine posted = lines.get(next++);
                Money amount = posted.amount();
                Money from = base == null ? Money.ofPersisted(0, amount.currency(), amount.scale()) : base;
                base = posted.direction() == Direction.DEBIT ? from.plus(amount) : from.minus(amount);
            }
            if (base != null && base.isPositive()) {
                Instant endOfDate = date.plusDays(1).atStartOfDay(ZoneId.of(line.zone())).toInstant();
                Duration lag = Duration.between(endOfDate, now);
                return Optional.of(lag.isNegative() ? Duration.ZERO : lag);
            }
        }
        return Optional.empty();
    }

    private Optional<LedgerAccountId> owned(Connection unitOfWork, LineAwaitingAccrual line, AccountPurpose purpose) {
        return ledgerAccounts.findOwned(unitOfWork, line.loan().value(), purpose, line.currency()).map(account -> account.id());
    }
}
