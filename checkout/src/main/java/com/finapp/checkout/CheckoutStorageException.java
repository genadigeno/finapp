package com.finapp.checkout;

/** A storage failure in the checkout module — infrastructure, never a domain outcome. */
public class CheckoutStorageException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public CheckoutStorageException(String message) {
        super(message);
    }
}
