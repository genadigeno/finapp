package com.finapp.bnpl;

import java.util.Objects;
import java.util.Optional;

/**
 * What one servicing step did (`P12-TSK-015`) - for the meters; never a figure, a date or a party: its kind, the obligation's
 * product, the instalments it {@code billed} (F2), the credit-balance applications born at them ({@code appliedAtBilling},
 * F3a) and whether a catch-up application settled what was already due ({@code caughtUp}, the Phase 11 gate's R2). The
 * steps later tasks add (collection, closure, condition, late fee) extend it.
 */
public record ServicingOutcome(Kind kind, Optional<BnplProduct> product, int billed, int appliedAtBilling,
        boolean caughtUp) {

    public ServicingOutcome {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(product, "product must not be null");
        if (billed < 0 || appliedAtBilling < 0 || appliedAtBilling > billed) {
            throw new IllegalArgumentException("counts are not negative, and at most one application per billing");
        }
        if (kind != Kind.SERVICED && (billed > 0 || caughtUp)) {
            throw new IllegalArgumentException("only a serviced step writes anything");
        }
        if ((kind == Kind.NOT_SERVICED) == product.isPresent()) {
            throw new IllegalArgumentException("a step names its obligation's product exactly when it found one in servicing");
        }
    }

    static ServicingOutcome notServiced() {
        return new ServicingOutcome(Kind.NOT_SERVICED, Optional.empty(), 0, 0, false);
    }

    static ServicingOutcome behindAccrual(BnplProduct product) {
        return new ServicingOutcome(Kind.BEHIND_ACCRUAL, Optional.of(product), 0, 0, false);
    }

    /** The step's effect. */
    public enum Kind {
        /** The obligation was not in servicing - gone, or no longer {@code ACTIVE}. Nothing written. */
        NOT_SERVICED,
        /**
         * A pay-in-N obligation with an elapsed date not yet accrued: nothing written; T-acc (`P12-TSK-017`) runs first in
         * its own transaction and the step is asked again at the claim's retry cadence.
         */
        BEHIND_ACCRUAL,
        /** Serviced: whatever was due billed, money held applied, the permit moved to the next business date. */
        SERVICED
    }
}
