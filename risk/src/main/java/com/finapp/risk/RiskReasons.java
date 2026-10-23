package com.finapp.risk;

import com.finapp.sharedkernel.security.InstrumentShapes;

/**
 * The reason screen every person-written risk reason passes (`P13-TSK-002`; {@code INV-AUD-02},
 * {@code RISK_LIFECYCLES.md} section 3; bnpl's {@code BnplReasons}): required, 1 to {@link #BOUND} characters, and
 * holding no card-number or bank-account shape ({@link InstrumentShapes}, the platform's one screen). Reasons are
 * {@code RESTRICTED-PII}; {@code risk V002}'s {@code risk.holds_instrument_shape} twin refuses a writer that bypassed
 * this one.
 */
public final class RiskReasons {

    /** A reason's longest length - every reason column's {@code CHECK}. */
    public static final int BOUND = 1000;

    private RiskReasons() {}

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
