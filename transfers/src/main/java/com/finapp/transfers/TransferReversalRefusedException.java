package com.finapp.transfers;

import java.io.Serial;
import java.util.Objects;

/**
 * A reversal proposal or decision refused by the rules (`P17-TSK-031`, ADR-0154): nothing written, the reason enumerated so
 * app's surface maps each to its one code.
 */
public final class TransferReversalRefusedException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    /** Why. */
    public enum Reason {
        /** The transfer is not {@code COMPLETED}: nothing to reverse, or already reversed. */
        NOT_REVERSIBLE,
        /** The transfer already has an open reversal proposal. */
        ALREADY_PROPOSED,
        /** The proposal is decided already. */
        ALREADY_DECIDED,
        /** The decider proposed it ({@code INV-AUD-04}). */
        SELF_DECISION
    }

    private final Reason reason;

    public TransferReversalRefusedException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
