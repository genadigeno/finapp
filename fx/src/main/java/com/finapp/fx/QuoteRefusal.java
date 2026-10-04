package com.finapp.fx;

/**
 * Why a quote was not issued (`P9-TSK-008`) - each a typed outcome, recorded on the claim and
 * answered by its error code; the rate-trouble outcomes also name their metric cause.
 */
public enum QuoteRefusal {
    CUSTOMER_NOT_ELIGIBLE(FxErrorCode.CUSTOMER_NOT_ELIGIBLE, null),
    PAIR_NOT_OFFERED(FxErrorCode.PAIR_NOT_OFFERED, null),
    AMOUNT_OUT_OF_RANGE(FxErrorCode.AMOUNT_OUT_OF_RANGE, null),
    PAIR_SUSPENDED(FxErrorCode.PAIR_SUSPENDED, null),
    POLICY_STALE(FxErrorCode.POLICY_STALE, null),
    TOO_MANY_OPEN_QUOTES(FxErrorCode.TOO_MANY_OPEN_QUOTES, "refused_cap"),
    /** Every candidate failed to answer usably, or the window left was under five seconds. */
    RATE_UNAVAILABLE(FxErrorCode.RATE_UNAVAILABLE, "refused_rate_unavailable"),
    /** The reference was stale on the database clock: fail closed. */
    REFERENCE_STALE(FxErrorCode.RATE_UNAVAILABLE, "refused_reference_stale"),
    /** The chosen provider's rate left the band against a newer reference. */
    IMPLAUSIBLE(FxErrorCode.RATE_UNAVAILABLE, "refused_implausible"),
    /** Every quoting candidate stated a counter its own rate contradicts. */
    INCOHERENT(FxErrorCode.RATE_UNAVAILABLE, "refused_incoherent");

    private final FxErrorCode code;
    private final String metricOutcome;

    QuoteRefusal(FxErrorCode code, String metricOutcome) {
        this.code = code;
        this.metricOutcome = metricOutcome;
    }

    /** The answer the caller receives. */
    public FxErrorCode code() {
        return code;
    }

    /** The {@code finapp.fx.quote} outcome, or null for a caller's own refusal (not counted). */
    public String metricOutcome() {
        return metricOutcome;
    }

    /** A refused quote request: the claim is completed with this refusal. */
    public static final class Refused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final QuoteRefusal refusal;

        public Refused(QuoteRefusal refusal) {
            super("quote refused: " + refusal.name());
            this.refusal = refusal;
        }

        public QuoteRefusal refusal() {
            return refusal;
        }
    }
}
