package com.finapp.merchant;

import java.time.Instant;

/**
 * A fee schedule version would take effect before it was created — which is a repricing of
 * history, and the one thing {@code INV-MER-03} exists to forbid: <em>"change creates a new
 * version effective forward, repricing nothing."</em>
 *
 * <p>Refused here and again by a schema {@code CHECK} over the two columns, because a rule
 * this load-bearing must not rest on every future writer remembering it.
 */
public class BackdatedFeeScheduleVersionException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public BackdatedFeeScheduleVersionException(Instant effectiveFrom, Instant createdAt) {
        super(
                "a fee schedule version takes effect forward, never backward: effectiveFrom "
                        + effectiveFrom
                        + " precedes its creation at "
                        + createdAt
                        + " (INV-MER-03)");
    }
}
