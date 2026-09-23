package com.finapp.payments;

/**
 * The named instrument does not exist, is not the caller's, or is no longer live — one empty
 * answer for all three ({@code PaymentParticipants}' contract). Nothing was written: a create
 * is refused whole, and a confirm leaves the intent exactly where it stood.
 */
public final class UnknownPaymentInstrumentException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public UnknownPaymentInstrumentException() {
        super("no such payment instrument for this caller");
    }
}
