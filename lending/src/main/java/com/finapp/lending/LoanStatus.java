package com.finapp.lending;

/**
 * The loan account's lifecycle, both kinds (`P11-TSK-002`; {@code LENDING_LIFECYCLES.md} section 4.5, built by
 * `P11-TSK-012`, `-014`, `-023` and `-026`).
 *
 * <p>An {@link LoanKind#INSTALMENT} loan: {@code PENDING_DISBURSEMENT -> ACTIVE -> CLOSED}, or
 * {@code PENDING_DISBURSEMENT -> CANCELLED}. A {@link LoanKind#REVOLVING} line: born {@code ACTIVE},
 * {@code ACTIVE -> CLOSING -> CLOSED}, or {@code ACTIVE -> CLOSED}. Terminal: {@code CLOSED} (with its
 * {@link ClosureReason}) and {@code CANCELLED} (with its {@link CancellationReason}) - nothing leaves them
 * ({@code INV-LIFE-04}, {@code INV-LND-08}). <strong>Conditions are not states</strong>: delinquency, default, draws
 * suspended and awaiting payout are recorded beside the lifecycle, so an {@code ACTIVE} loan is {@code ACTIVE} whether
 * current or 120 days past due; and a restructuring is agreement version n+1, never a state.
 */
public enum LoanStatus {

    /** Instalment only: accepted, waiting for its disbursement to be credited to the wallet. */
    PENDING_DISBURSEMENT,

    /** Disbursed (instalment) or born (revolving): serviced, whatever its conditions. */
    ACTIVE,

    /** Revolving only: the customer asked to close; it closes when every receivable is zero. */
    CLOSING,

    /** Terminal: every receivable zero and any credit balance refunded - closure is a consequence, never a command. */
    CLOSED,

    /** Terminal, instalment only: never disbursed - nothing was posted, so nothing is owed. */
    CANCELLED
}
