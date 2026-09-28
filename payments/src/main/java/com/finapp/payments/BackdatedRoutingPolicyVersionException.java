package com.finapp.payments;

import java.time.Instant;

/**
 * A routing policy version would take effect before it was created — a re-routing of
 * history's explanations (`INV-HIST-04`, ADR-0060 §1: <em>"effective forward, and reprices
 * nothing in flight"</em> — the fee schedule's `INV-MER-03` argument, applied to routing).
 *
 * <p>Refused here and again by `V013`'s {@code CHECK} over the two columns, because a rule
 * this load-bearing must not rest on every future writer remembering it.
 */
public class BackdatedRoutingPolicyVersionException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public BackdatedRoutingPolicyVersionException(Instant effectiveFrom, Instant createdAt) {
        super(
                "a routing policy version takes effect forward, never backward: effectiveFrom "
                        + effectiveFrom
                        + " precedes its creation at "
                        + createdAt
                        + " (ADR-0060, INV-HIST-04)");
    }
}
