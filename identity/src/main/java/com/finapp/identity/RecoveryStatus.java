package com.finapp.identity;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Where a recovery request is (`P1-TSK-023`, {@code INV-LIFE-01}).
 *
 * <h2>There is no EXPIRED, for the reason there is no expired session</h2>
 *
 * <p>A stored {@code EXPIRED} needs a sweep to write it, and between the moment a request expires
 * and the moment that sweep runs the database says {@code INITIATED} about a request that is not.
 * Every consumer would check the bound anyway, making the status a second answer free to disagree
 * with the first. Derived, there is one - the {@code P1-TSK-013} decision, reached the same way.
 *
 * <h2>And no VERIFIED, which the plan named</h2>
 *
 * <p>{@code PHASE_1_PLAN.md} §4 sketches {@code INITIATED → VERIFIED → COMPLETED}, and §7 puts
 * completion at a <strong>single</strong> request: present the token and the new credential
 * together. So {@code VERIFIED} is a state nothing can ever observe, and a state nobody can observe
 * is a state that lies about what the system does. Two requests were considered and rejected: they
 * open a window in which control has been proven and nothing has been changed, which is a strictly
 * worse thing to hold.
 */
public enum RecoveryStatus {

    /** A token has been issued to a verified channel and not yet spent. */
    INITIATED,

    /** The token was presented and the credential replaced. Terminal ({@code INV-LIFE-04}). */
    COMPLETED,

    /**
     * Superseded by a later initiation, so the earlier token is dead.
     *
     * <p>Terminal. Without it an attacker who initiated once keeps a live token while the customer
     * initiates again and fixes their account - two valid paths in, which is one more than recovery
     * is allowed to have.
     */
    CANCELLED;

    /** The statuses as a SQL literal list, for the {@code CHECK} constraint. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
