package com.finapp.payments;

/**
 * The intent does not exist, or is not the caller's — one answer for both, deliberately
 * ({@code INV-IDN-07}'s reasoning, ADR-0031): the surface's one 404, never an oracle over
 * other people's payments. Nothing was written.
 */
public final class UnknownPaymentException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public UnknownPaymentException() {
        super("no such payment for this caller");
    }
}
