package com.finapp.merchant;

import java.io.Serial;
import java.util.Objects;

/**
 * A closure proposal or decision refused by the rules (`P17-TSK-031`, ADR-0154): nothing written, the reason enumerated so
 * app's surface maps each to its one code.
 */
public final class MerchantClosureRefusedException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    /** Why. */
    public enum Reason {
        /** The merchant is not {@code ACTIVE}: suspended (reinstate it first) or already closed. */
        NOT_CLOSABLE,
        /** The merchant already has an open closure proposal. */
        ALREADY_PROPOSED,
        /** The proposal is decided already. */
        ALREADY_DECIDED,
        /** The decider proposed it ({@code INV-AUD-04}). */
        SELF_DECISION
    }

    private final Reason reason;

    public MerchantClosureRefusedException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
