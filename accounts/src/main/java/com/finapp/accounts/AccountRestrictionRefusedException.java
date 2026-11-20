package com.finapp.accounts;

import java.io.Serial;
import java.util.Objects;

/**
 * A restriction act refused by the rules (`P17-TSK-028`, ADR-0153): nothing written, the reason enumerated so app's surface
 * maps each to its one code - {@link AccountsErrorCode#RESTRICTION_REFUSED}'s family.
 */
public final class AccountRestrictionRefusedException extends RuntimeException {

    /** Why. */
    public enum Reason {
        /** The agreement is not in the status the kind starts from (or is unknown to this act). */
        NOT_APPLICABLE,
        /** The agreement already has an open proposal. */
        ALREADY_PROPOSED,
        /** The restriction is decided already. */
        ALREADY_DECIDED,
        /** The decider proposed it ({@code INV-AUD-04}). */
        SELF_DECISION,
        /** The reason code is not of the kind, or the reason text is missing, too long or instrument-shaped. */
        REASON_INVALID
    }

    @Serial private static final long serialVersionUID = 1L;

    private final Reason reason;

    public AccountRestrictionRefusedException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
