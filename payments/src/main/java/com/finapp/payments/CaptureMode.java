package com.finapp.payments;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Whether a payment's authorization is an instruction to take the money, or a reservation
 * awaiting a person (`P7-TSK-002`, ADR-0059).
 *
 * <p>A birth fact of the intent, frozen with the rest of them (payments `V012`), because it is
 * part of what the payer agreed to. It exists now — with {@link #AUTOMATIC} the only value any
 * path writes — so that the sweeper's stranded-chain leg (the Phase 6 → 7 transition's) is
 * <em>conditioned on it from the day it could matter</em>: the leg exists to finish a capture
 * a crash interrupted, and without this fact it would be structurally unable to tell that case
 * from an authorization somebody meant to leave resting. The card void (`P7-TSK-004`) is what
 * makes a resting authorization a real state of affairs; {@link #MANUAL}'s producer arrives
 * with the surface that owns that decision, never before.
 */
public enum CaptureMode {

    /**
     * Capture follows authorization without a further decision — every Phase 5 and 6 payment,
     * and every intent any current door creates. The surface chains the capture after a
     * synchronous {@code AUTHORIZED}, and the sweep finishes one a crash stranded.
     */
    AUTOMATIC,

    /**
     * Capture waits for an explicit act. No producer yet: declared with the sweep condition
     * that honours it, so the day a producer lands, the resting authorization is already safe
     * from every resolver.
     */
    MANUAL;

    /** The values as a SQL literal list, for `V012`'s `CHECK` (the P5-TSK-008 reconciliation). */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(mode -> "'" + mode.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
