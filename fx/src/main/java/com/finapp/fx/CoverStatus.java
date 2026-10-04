package com.finapp.fx;

/** A cover's position (`P9-TSK-009`; the lifecycle document section 3.4, ADR-0077). */
public enum CoverStatus {
    DISPATCHED,
    UNKNOWN,
    EXECUTED,
    REJECTED,
    VOIDED
}
