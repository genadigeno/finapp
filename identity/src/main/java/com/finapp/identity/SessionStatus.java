package com.finapp.identity;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * What a session row records about itself (`P1-TSK-013`, {@code INV-LIFE-01}).
 *
 * <h2>Two values, and the absence of a third is the design</h2>
 *
 * <p>There is no {@code EXPIRED}. Expiry is <strong>derived from the row's own bounds</strong> - see
 * {@link Session} - because a stored {@code EXPIRED} needs a sweep to write it, and between the
 * moment a session expires and the moment that sweep runs the database would say {@code ACTIVE}
 * about a session that is not. Every consumer would then check the bounds anyway, making the status
 * a second answer free to disagree with the first.
 *
 * <p>{@code REVOKED} is terminal ({@code INV-LIFE-04}): a session that has been ended deliberately
 * is never resumed. Nothing transitions into it yet - {@code P1-TSK-014} owns revocation - and the
 * value exists here because the <em>lookup</em> must already treat it exactly as it treats expiry.
 *
 * <p>Persisted values, in a generated {@code CHECK} constraint - the {@code P0-TSK-022} pattern.
 */
public enum SessionStatus {

    /** Issued, and within both of its bounds unless time says otherwise. */
    ACTIVE,

    /** Ended deliberately. Terminal. */
    REVOKED;

    /** The statuses as a SQL literal list, for the {@code CHECK} constraint. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
