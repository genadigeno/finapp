package com.finapp.reconciliation;

import java.util.List;
import java.util.Objects;

/**
 * Where one operation stands in its settlement (`P8-TSK-014`) — DERIVED from stored rows on
 * every read, never stored: a stored status would be a second copy of facts the expectation,
 * its allocations and the remittances already hold, and would drift the first time one of
 * them moved.
 *
 * <p>Precedence, strongest first: {@code RESOLVED} (the expectation closed by an approved
 * resolution — {@code RESOLVED_BY_ADJUSTMENT}) · {@code CASH_CONFIRMED} (settled, and every
 * report batch it was settled from has its {@code REMITTANCE} settled by the bank — first
 * reachable with `P8-TSK-016`'s bank recognition) · {@code REPORTED} (the counterparty's
 * evidence allocated the whole amount) · {@code OVERDUE} (still open past its window — the
 * one-way {@code overdue_since}) · {@code PENDING}. A partially reported operation is
 * {@code OVERDUE} once its window passed and {@code PENDING} before: part of the money is
 * still unaccounted for. A zero-net batch opens no remittance (`P8-TSK-009`), so nothing
 * settled from it can reach {@code CASH_CONFIRMED}; it stays {@code REPORTED}.
 */
public enum SettlementStatus {
    PENDING,
    REPORTED,
    CASH_CONFIRMED,
    OVERDUE,
    RESOLVED;

    /**
     * @param expectation the expectation's stored status
     * @param overdue whether the ageing sweep recorded {@code overdue_since}
     * @param remittances for each report batch the expectation was allocated from, that
     *     batch's {@code REMITTANCE} status — empty when the batch opened none (zero net)
     */
    public static SettlementStatus derive(
            ExpectationStatus expectation,
            boolean overdue,
            List<java.util.Optional<ExpectationStatus>> remittances) {
        Objects.requireNonNull(expectation, "expectation must not be null");
        Objects.requireNonNull(remittances, "remittances must not be null");
        return switch (expectation) {
            case RESOLVED_BY_ADJUSTMENT -> RESOLVED;
            case SETTLED ->
                    !remittances.isEmpty()
                                    && remittances.stream()
                                            .allMatch(
                                                    remittance ->
                                                            remittance.isPresent()
                                                                    && remittance.get()
                                                                            == ExpectationStatus
                                                                                    .SETTLED)
                            ? CASH_CONFIRMED
                            : REPORTED;
            case OPEN, PARTIALLY_SETTLED -> overdue ? OVERDUE : PENDING;
        };
    }
}
