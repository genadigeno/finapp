package com.finapp.payments;

/**
 * A reversal was asked of a rail whose declared capabilities do not list it
 * (`P7-TSK-004`, `INV-REV-03`): refused by the domain before anything is written or sent —
 * never attempted and failed at a provider, because attempting the impossible produces
 * indeterminate state and stranded value. The surface maps it to
 * {@code payments.ReversalNotSupported} (409).
 */
public class ReversalNotSupportedException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public ReversalNotSupportedException(RailId rail) {
        super("the rail '" + rail.value() + "' declares no reversal: a void is refused before"
                + " anything is written or sent (INV-REV-03, ADR-0059)");
    }
}
