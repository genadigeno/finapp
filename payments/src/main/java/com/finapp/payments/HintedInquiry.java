package com.finapp.payments;

import java.util.Set;

/** One rail's inquiries for the durable hints it raised (`P15-TSK-036`). */
public interface HintedInquiry {

    /** The hint kinds this rail answers. */
    Set<CallbackHint.Kind> kinds();

    /**
     * Asks the provider about {@code hint}, holding no connection, and applies only what it answers. Runs as the caller's
     * actor and inside the caller's correlation: an enumerated platform site at every caller.
     */
    HintedOutcome inquire(CallbackHint hint);
}
