package com.finapp.payments;

/**
 * No dispute visible to the caller has the identifier (`P7-TSK-014`) — another tenant's, an
 * unknown one and a malformed one are the same absence at the boundary: one {@code 404}, so the
 * surface is no oracle over other merchants' disputes ({@code INV-MER-01}). Unknown evidence on
 * a visible dispute is the same answer.
 */
public final class UnknownDisputeException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public UnknownDisputeException() {
        super("no dispute visible to the caller matches the identifier");
    }
}
