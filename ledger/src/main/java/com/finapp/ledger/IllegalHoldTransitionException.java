package com.finapp.ledger;

import java.io.Serial;

/**
 * A hold was asked to move along an edge its machine does not have ({@code INV-LIFE-02};
 * `P3-TSK-015`). With two states the only refusable ask is re-releasing a released hold —
 * refused by the aggregate itself, because every aggregate eventually meets a second caller.
 *
 * <p>Names the hold and the states, never the amount ({@code INV-AUD-02}).
 */
public final class IllegalHoldTransitionException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    public IllegalHoldTransitionException(HoldId hold, HoldStatus from, HoldStatus to) {
        super(
                "hold "
                        + hold
                        + " cannot move "
                        + from
                        + " -> "
                        + to
                        + ": "
                        + from
                        + " permits "
                        + from.permittedTransitions());
    }
}
