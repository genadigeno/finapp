package com.finapp.risk;

import java.sql.Connection;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The monitoring rules' two reads of the monitored transactions (`P13-TSK-020`; ADR-0033; {@code DESIGN-020} D3, D4,
 * F3) - a port of its own beside {@link MonitoredTransactionStore} (whose one write is the intake's), so the readings'
 * suites need no intake. Both read a party's {@linkplain MonitoredMovement movements} over its customer accounts, on
 * the caller's unit of work, SELECT only: the rows whose subject is one of {@code accounts} (V004's
 * {@code monitored_transaction_by_subject}) and the rows whose customer counterparty is one ({@code risk V015}'s
 * {@code monitored_transaction_by_counterparty}), a row with both sides on {@code accounts} excluded, the record's
 * corrections ({@code REVERSAL}, {@code ADJUSTMENT}) excluded ({@link MonitoredMovement#COUNTED}). Never a balance.
 */
public interface MonitoredMovementStore {

    /**
     * The movements of {@code accounts} whose value date is in {@code [from, until)}, each as the party sees it, in
     * {@link MonitoredMovement#ORDER} - at most {@code limit}, so a caller asking for one more than it may hold can tell a
     * window it may not seal from one it read whole.
     */
    List<MonitoredMovement> movementsOf(Connection unitOfWork, Collection<UUID> accounts, LocalDate from,
            LocalDate until, int limit);

    /** The latest value date before {@code before} of any movement of {@code accounts}; empty when none. */
    Optional<LocalDate> lastMovementBefore(Connection unitOfWork, Collection<UUID> accounts, LocalDate before);
}
