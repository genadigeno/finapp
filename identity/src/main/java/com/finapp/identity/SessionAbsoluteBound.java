package com.finapp.identity;

import java.time.Duration;
import java.util.Objects;

/**
 * Where a {@link Session.Draft}'s absolute bound comes from (`X-TSK-007`).
 *
 * <p>Never an instant: either a lifetime the database adds to its own {@code now()}, or the
 * predecessor whose row already holds the bound, which the database copies verbatim.
 *
 * <p>Top-level rather than nested in {@code Session}, and not for style.
 * {@code NoProcessLocalSessionStateTest} matches the {@code Session} type on a word boundary, and a
 * nested type's binary name, {@code Session$AbsoluteBound}, has one: the draft's field would have
 * read as a retained session. Renaming the type is honest. Exempting the field would have taught
 * the rule that a field mentioning {@code Session} can be waved through.
 */
public sealed interface SessionAbsoluteBound {

    /** A new sitting. The database stamps its {@code now()} plus this lifetime. */
    record Lifetime(Duration value) implements SessionAbsoluteBound {
        public Lifetime {
            Objects.requireNonNull(value, "value must not be null");
            if (value.isNegative() || value.isZero()) {
                throw new IllegalArgumentException("An absolute lifetime of zero is not a lifetime");
            }
        }
    }

    /**
     * A rotation. The bound is the one on the <strong>predecessor's row</strong>, copied by the
     * statement that writes the replacement.
     *
     * <p>The row rather than the aggregate the caller holds: that is a copy read in an earlier
     * transaction, and a copy of the truth is not the truth ({@code SessionStore#revokeOwned}'s
     * reasoning). Rotation revokes the predecessor first, in the same transaction, so the row is
     * locked while the bound is copied from it. Found by mutating the revoke: with the row moved on
     * and the copy not, a rotation inherited a bound the row no longer had.
     */
    record Inherited(SessionId predecessor) implements SessionAbsoluteBound {
        public Inherited {
            Objects.requireNonNull(predecessor, "predecessor must not be null");
        }
    }
}
