package com.finapp.bnpl;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What one servicing step did (`P12-TSK-015`) - for the meters; never a figure, a date or a party: its kind, the obligation's
 * product, the instalments it {@code billed} (F2), the credit-balance applications born at them ({@code appliedAtBilling},
 * F3a), whether a catch-up application settled what was already due ({@code caughtUp}, the Phase 11 gate's R2) and whether
 * the step's closure closed the obligation ({@code closed}, `P12-TSK-018`) and its collection attempts by outcome
 * ({@code collections}, `P12-TSK-019`), and the condition changes its condition step recorded ({@code conditionChanges},
 * 0 to 2) and the late fees its fee step assessed ({@code fees}, of which {@code feesCapped} at less than the pinned fee by
 * the agreement's cap) (`P12-TSK-020`).
 */
public record ServicingOutcome(Kind kind, Optional<BnplProduct> product, int billed, int appliedAtBilling,
        boolean caughtUp, boolean closed, Map<CollectionOutcome, Integer> collections, int conditionChanges, int fees,
        int feesCapped) {

    public ServicingOutcome {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(product, "product must not be null");
        collections = Map.copyOf(Objects.requireNonNull(collections, "collections must not be null"));
        if (billed < 0 || appliedAtBilling < 0 || appliedAtBilling > billed
                || collections.values().stream().anyMatch(count -> count < 0)) {
            throw new IllegalArgumentException("counts are not negative, and at most one application per billing");
        }
        if (conditionChanges < 0 || conditionChanges > 2 || fees < 0 || feesCapped < 0 || feesCapped > fees) {
            throw new IllegalArgumentException("at most two condition changes a step, and a capped fee is an assessed one");
        }
        if (closed && (conditionChanges > 0 || fees > 0)) {
            throw new IllegalArgumentException("a closed obligation is never evaluated nor charged (INV-LND-08 by analogy)");
        }
        if (kind != Kind.SERVICED && (billed > 0 || caughtUp || closed || !collections.isEmpty() || conditionChanges > 0
                || fees > 0)) {
            throw new IllegalArgumentException("only a serviced step writes anything");
        }
        if ((kind == Kind.NOT_SERVICED) == product.isPresent()) {
            throw new IllegalArgumentException("a step names its obligation's product exactly when it found one in servicing");
        }
    }

    /** A step that recorded no condition and assessed no fee - `P12-TSK-019`'s shape. */
    public ServicingOutcome(Kind kind, Optional<BnplProduct> product, int billed, int appliedAtBilling, boolean caughtUp,
            boolean closed, Map<CollectionOutcome, Integer> collections) {
        this(kind, product, billed, appliedAtBilling, caughtUp, closed, collections, 0, 0, 0);
    }

    /** A step that collected nothing - `P12-TSK-018`'s shape. */
    public ServicingOutcome(Kind kind, Optional<BnplProduct> product, int billed, int appliedAtBilling, boolean caughtUp,
            boolean closed) {
        this(kind, product, billed, appliedAtBilling, caughtUp, closed, Map.of());
    }

    /** A step that closed nothing - `P12-TSK-015`'s shape. */
    public ServicingOutcome(Kind kind, Optional<BnplProduct> product, int billed, int appliedAtBilling, boolean caughtUp) {
        this(kind, product, billed, appliedAtBilling, caughtUp, false);
    }

    /** The collection attempts of {@code outcome} the step made (`P12-TSK-019`). */
    public int collected(CollectionOutcome outcome) {
        return collections.getOrDefault(outcome, 0);
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
