package com.finapp.fx;

/**
 * The post-commit nudge a booked conversion gives the cover dispatcher (`P9-TSK-009`): a hint to
 * send now rather than on the next sweep, never the guarantee - the cover row, born DISPATCHED
 * with its reference and permit, is the guarantee. Until the sender exists (`P9-TSK-012`) the
 * nudge is {@link #NONE}.
 */
@FunctionalInterface
public interface CoverDispatchNudge {

    /** Does nothing: the sweep finds every DISPATCHED cover. */
    CoverDispatchNudge NONE = coverId -> { };

    /** Called after the booking transaction committed, never inside it. */
    void nudge(java.util.UUID coverId);
}
