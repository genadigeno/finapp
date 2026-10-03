package com.finapp.fx;

/**
 * Which leg of a conversion the customer fixed (ADR-0074 §2, owner decision O3). The margin and
 * the residual always arise in the currency of the OTHER leg - the computed one.
 */
public enum FixedSide {

    /** The customer sells exactly the source amount; the destination amount is computed. */
    FIXED_SOURCE,

    /** Exactly the destination amount is delivered; the source amount charged is computed. */
    FIXED_DESTINATION
}
