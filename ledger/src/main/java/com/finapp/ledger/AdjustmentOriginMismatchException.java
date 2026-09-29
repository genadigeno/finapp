package com.finapp.ledger;

import java.util.Objects;

/**
 * A decision reached a proposal another door owns (`P8-TSK-006`, ADR-0071 §6, F-a): the
 * generic approve and reject refuse a {@code RECONCILIATION}-origin proposal — its decision
 * belongs to the resolution flow that also moves the break — and the owned methods refuse a
 * {@code MANUAL} one. Nothing is written; the proposal stands for its own door.
 */
public final class AdjustmentOriginMismatchException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    private final AdjustmentOrigin origin;

    public AdjustmentOriginMismatchException(
            AdjustmentProposalId proposal, AdjustmentOrigin origin) {
        super(
                "proposal "
                        + proposal
                        + " is "
                        + origin
                        + "-origin: its decision belongs to that origin's own door"
                        + " (ADR-0071 section 6)");
        this.origin = Objects.requireNonNull(origin, "origin must not be null");
    }

    public AdjustmentOrigin origin() {
        return origin;
    }
}
