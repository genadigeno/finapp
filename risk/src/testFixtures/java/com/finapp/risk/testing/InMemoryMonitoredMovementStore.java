package com.finapp.risk.testing;

import com.finapp.risk.MonitoredMovement;
import com.finapp.risk.MonitoredMovementStore;
import com.finapp.risk.MonitoredTransaction;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@link MonitoredMovementStore} in memory for the hermetic suites (`P13-TSK-020`) - the two reads as
 * {@code JdbcMonitoredTransactionStore} states them in SQL, over the rows a suite records: a party's movements by its
 * accounts, the subject side as stored and the customer-counterparty side inverted ({@link MonitoredMovement#seenBy}),
 * a row on both sides excluded, the corrections excluded, in {@link MonitoredMovement#ORDER}, at most the limit; the
 * last movement before a date over either side, a row on both sides included. Every call recorded in {@link #reads}.
 * Single-threaded by design: the statements' concurrency is the owed database suites'.
 */
public final class InMemoryMonitoredMovementStore implements MonitoredMovementStore {

    /** The monitored transactions, as the intake bore them. */
    public final List<MonitoredTransaction> rows = new ArrayList<>();

    /** Every read, in order: {@code movements FROM UNTIL LIMIT}, {@code last BEFORE}. */
    public final List<String> reads = new ArrayList<>();

    @Override
    public List<MonitoredMovement> movementsOf(Connection unitOfWork, Collection<UUID> accounts, LocalDate from,
            LocalDate until, int limit) {
        Objects.requireNonNull(accounts, "accounts");
        reads.add("movements " + from + " " + until + " " + limit);
        Set<UUID> held = new HashSet<>(accounts);
        List<MonitoredMovement> found = new ArrayList<>();
        for (MonitoredTransaction row : rows) {
            if (row.valueDate().isBefore(from) || !row.valueDate().isBefore(until)) {
                continue;
            }
            MonitoredMovement.seenBy(row, held).ifPresent(found::add);
        }
        found.sort(MonitoredMovement.ORDER);
        return List.copyOf(found.subList(0, Math.min(limit, found.size())));
    }

    @Override
    public Optional<LocalDate> lastMovementBefore(Connection unitOfWork, Collection<UUID> accounts, LocalDate before) {
        Objects.requireNonNull(accounts, "accounts");
        reads.add("last " + before);
        Set<UUID> held = new HashSet<>(accounts);
        Optional<LocalDate> last = Optional.empty();
        for (MonitoredTransaction row : rows) {
            if (!MonitoredMovement.COUNTED.contains(row.kind()) || !row.valueDate().isBefore(before)) {
                continue;
            }
            boolean ours = MonitoredMovement.subjectAccount(row).filter(held::contains).isPresent()
                    || MonitoredMovement.counterpartyAccount(row).filter(held::contains).isPresent();
            if (ours && last.map(date -> row.valueDate().isAfter(date)).orElse(true)) {
                last = Optional.of(row.valueDate());
            }
        }
        return last;
    }
}
