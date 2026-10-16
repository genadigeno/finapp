package com.finapp.creditmath;

import java.util.Objects;

/**
 * An accrual engine refused to compute (`P11-TSK-007`; ADR-0094, {@code INV-LND-09}): terms naming a convention the
 * engine version does not implement, a negative base, a base in another currency or scale, a date outside its period,
 * or a predecessor that is not an earlier accrual of the same period by the same engine. Never a figure: a refusal
 * computes nothing, so a caller cannot post half an answer.
 *
 * <p>Each of these is a defect in the caller's inputs, not a business outcome - the servicing step that met one rolls
 * its transaction back. The detail names the defect, never an amount, a rate or a date ({@code INV-AUD-02}).
 */
public final class AccrualRefused extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /** Why the engine refused. */
    public enum Reason {
        /** A day count, an interest rounding mode or an engine version this engine version does not implement. */
        UNIMPLEMENTED_CONVENTION,
        /** A nominal annual rate outside {@code 0..1} or past {@link Act365Fixed#RATE_PLACES} places. */
        RATE_OUT_OF_BOUNDS,
        /** A negative base: principal outstanding is never below zero. */
        NEGATIVE_BASE,
        /** A base in another currency than the terms', or not at the currency's scale. */
        CURRENCY_MISMATCH,
        /** A period that does not end after it starts, or periods that overlap or run backwards. */
        PERIOD_INVALID,
        /** A date outside the period it is accrued in, or one no supplied period holds. */
        DATE_OUTSIDE_PERIOD,
        /**
         * A predecessor that is not an earlier accrual of the same period by this engine - a date accrued again, out of
         * order, or across a period's reset.
         */
        OUT_OF_ORDER
    }

    private final Reason reason;

    AccrualRefused(Reason reason, String detail) {
        super(Objects.requireNonNull(reason, "reason") + ": " + detail);
        this.reason = reason;
    }

    /** Why the engine refused. */
    public Reason reason() {
        return reason;
    }
}
