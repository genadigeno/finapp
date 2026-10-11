package com.finapp.lending;

import java.util.Objects;

/**
 * A statement engine refused to compute (`P11-TSK-009`; ADR-0100 point 6, {@code INV-LND-09}): terms naming a
 * convention the engine version does not implement or a value outside its bounds, a cycle that is not one of the
 * statement day's, an amount that is negative, in another currency or scale or beyond the engine's range, or accruals
 * that are not one chain of the cycle's own by the accrual engine version 1. Never a figure: a refusal computes
 * nothing, so a caller cannot post half a statement.
 *
 * <p>Each of these is a defect in the caller's inputs, not a business outcome - a statement always exists for a cycle,
 * empty when nothing was drawn or accrued - so the servicing step that met one rolls its transaction back
 * ({@link AccrualRefused}'s precedent). The detail names the defect, never an amount, a rate or a date
 * ({@code INV-AUD-02}).
 */
public final class StatementRefused extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /** Why the engine refused. */
    public enum Reason {
        /** Not a credit line's terms, another statement engine version, or a minimum-payment rounding other than UP. */
        UNIMPLEMENTED_CONVENTION,
        /** A statement day outside 1-28, a floor not positive or not in the currency, a ratio or due days out of range. */
        TERMS_OUT_OF_BOUNDS,
        /** A date before the line opened or outside the engine's years; a cycle not ending on the statement day. */
        CYCLE_INVALID,
        /** An amount below zero, in another currency or scale, or beyond the engine's range. */
        AMOUNT_INVALID,
        /**
         * An accrual outside the cycle, by another accrual engine version, in another currency or on a base beyond the
         * engine's range; two on one date or out
         * of order; or a list whose postings do not sum to its last accrual's once-rounded cumulative figure - not one
         * period's chain, a row missing or foreign.
         */
        ACCRUALS_INVALID
    }

    private final Reason reason;

    StatementRefused(Reason reason, String detail) {
        super(Objects.requireNonNull(reason, "reason") + ": " + detail);
        this.reason = reason;
    }

    /** Why the engine refused. */
    public Reason reason() {
        return reason;
    }
}
