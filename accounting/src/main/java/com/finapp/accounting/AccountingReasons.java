package com.finapp.accounting;

import com.finapp.sharedkernel.security.InstrumentShapes;

/**
 * The reason screen every person-written accounting reason passes (`P14-TSK-003`; risk's {@code RiskReasons};
 * {@code INV-AUD-02}): required, 1 to {@link #BOUND} characters, and holding no card-number or bank-account shape
 * ({@link InstrumentShapes}, the platform's one screen). Reasons are {@code RESTRICTED-PII} and never served.
 * {@code accounting V002} bounds every reason column; it has no SQL twin of the shape screen - a CHECK calling another
 * schema's function would be a cross-module edge (ADR-0029) - so this is the screen.
 */
public final class AccountingReasons {

    /** A reason's longest length - every reason column's {@code CHECK}. */
    public static final int BOUND = 1000;

    private AccountingReasons() {}

    /** A proposal or decision carries no reason, one past its bound, or one holding an instrument shape. */
    public static final class ReasonRequired extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ReasonRequired(String detail) {
            super(detail);
        }
    }

    /**
     * {@code reason}, screened.
     *
     * @throws ReasonRequired for a missing, blank, unbounded or instrument-bearing reason
     */
    public static String reasoned(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > BOUND) {
            throw new ReasonRequired("a reason of 1 to " + BOUND + " characters is required");
        }
        if (InstrumentShapes.holdsAny(reason)) {
            throw new ReasonRequired("a reason must not hold a card-number or bank-account shape");
        }
        return reason;
    }

    /** {@code reason} cut to {@link #BOUND} - for a derived reason (a retirement's) built from a screened one. */
    static String bounded(String reason) {
        return reason.length() <= BOUND ? reason : reason.substring(0, BOUND);
    }
}
