package com.finapp.fx;

/**
 * An FX quote's position in its machine (`P9-TSK-008`; the lifecycle document section 3.1), held
 * for every writer by {@code fx V005}'s {@code quote_edge_is_legal}.
 */
public enum QuoteStatus {
    /** The rate lock: live until {@code expires_at} on the database clock. */
    ISSUED,
    /** Accepted by its subject (`P9-TSK-009`, `-019`). */
    ACCEPTED,
    /** The trade was booked. Terminal. */
    EXECUTED,
    /** The subject failed before booking. Terminal. */
    ABANDONED,
    /** The window lapsed. Terminal. */
    EXPIRED,
    /** The owner cancelled it while live. Terminal. */
    CANCELLED
}
