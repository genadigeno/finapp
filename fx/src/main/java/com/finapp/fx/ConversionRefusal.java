package com.finapp.fx;

/**
 * Why a conversion was not booked (`P9-TSK-009`). Each but {@link #QUOTE_EXPIRED} rolls the
 * whole transaction back - the quote stays ISSUED and reusable; an acceptance that found the
 * quote lapsed instead COMMITS its expiry, its event and the claim's failed outcome.
 */
public enum ConversionRefusal {
    CUSTOMER_NOT_ELIGIBLE(FxErrorCode.CUSTOMER_NOT_ELIGIBLE),
    QUOTE_NOT_FOUND(FxErrorCode.QUOTE_NOT_FOUND),
    QUOTE_EXPIRED(FxErrorCode.QUOTE_EXPIRED),
    QUOTE_ALREADY_ACCEPTED(FxErrorCode.QUOTE_ALREADY_ACCEPTED),
    QUOTE_NOT_ACCEPTABLE(FxErrorCode.QUOTE_NOT_ACCEPTABLE),
    QUOTE_KIND_MISMATCH(FxErrorCode.QUOTE_KIND_MISMATCH),
    PAIR_SUSPENDED(FxErrorCode.PAIR_SUSPENDED),
    SOURCE_WALLET_MISSING(FxErrorCode.SOURCE_WALLET_MISSING),
    WALLET_NOT_POSTABLE(FxErrorCode.WALLET_NOT_POSTABLE),
    INSUFFICIENT_FUNDS(FxErrorCode.INSUFFICIENT_FUNDS);

    private final FxErrorCode code;

    ConversionRefusal(FxErrorCode code) {
        this.code = code;
    }

    public FxErrorCode code() {
        return code;
    }

    /** A refused conversion: the caller's transaction must roll back. */
    public static final class Refused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final ConversionRefusal refusal;

        public Refused(ConversionRefusal refusal) {
            super("conversion refused: " + refusal.name());
            this.refusal = refusal;
        }

        public ConversionRefusal refusal() {
            return refusal;
        }
    }
}
