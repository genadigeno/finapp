package com.finapp.merchant;

import com.finapp.platform.security.Actor;
import java.io.Serial;
import java.util.Objects;

/**
 * A fee schedule version or merchant fee assignment proposal, or its decision, refused by the rules (`P17-TSK-123`):
 * nothing written. Its message never repeats a reason ({@code INV-AUD-02}).
 */
public final class FeeProposalRefused extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    /** Why. */
    public enum Reason { ALREADY_PROPOSED, ALREADY_DECIDED, SELF_DECISION }

    private final Reason reason;

    public FeeProposalRefused(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }

    /**
     * Refuses {@code outcome} by {@code decider} on a proposal in {@code status} proposed by {@code proposedBy} - decided
     * already, or the decider proposed it ({@code INV-AUD-04}) - before anything else.
     */
    static void requireDecidable(String what, FeeProposalStatus status, String proposedBy, FeeProposalStatus outcome,
            Actor decider) {
        if (!status.permittedTransitions().contains(outcome)) {
            throw new FeeProposalRefused(Reason.ALREADY_DECIDED, "the " + what + " proposal is " + status
                    + "; it is decided once");
        }
        if (decider.id().equals(proposedBy)) {
            throw new FeeProposalRefused(Reason.SELF_DECISION,
                    "a " + what + " proposal is decided by a person who did not propose it");
        }
    }
}
