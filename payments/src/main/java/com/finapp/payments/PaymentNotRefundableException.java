package com.finapp.payments;

/**
 * The intent has no {@code CAPTURED} attempt to refund against (`P5-TSK-015`,
 * {@code INV-PAY-05}: only a captured attempt has anything to return). The caller's 409,
 * nothing written.
 */
public final class PaymentNotRefundableException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    private final PaymentAttemptStatus status;

    public PaymentNotRefundableException(PaymentAttemptStatus status) {
        super("only a CAPTURED attempt can be refunded; the attempt is " + status);
        this.status = status;
    }

    private PaymentNotRefundableException() {
        super("only a CAPTURED attempt can be refunded; nothing was ever dispatched");
        this.status = null;
    }

    /** The intent was never confirmed: there is no attempt at all, and no state to invent. */
    public static PaymentNotRefundableException noAttempt() {
        return new PaymentNotRefundableException();
    }

    /** The refusing attempt's state — {@code null} when no attempt exists at all. */
    public PaymentAttemptStatus status() {
        return status;
    }
}
