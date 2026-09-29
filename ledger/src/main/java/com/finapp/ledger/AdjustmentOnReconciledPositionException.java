package com.finapp.ledger;

import java.util.Objects;

/**
 * A {@code MANUAL} adjustment named a reconciled position (`P8-TSK-006`, ADR-0071,
 * {@code INV-REC-06}): value in a clearing or suspense account moves only through a break
 * resolution. Thrown by the domain <em>before the idempotency claim</em>, so the refused
 * request never consumes its key — and again at approval, the re-check that covers the one
 * row `V015`'s insert trigger could not see (a proposal born before the migration).
 *
 * <p>Names the purpose, never an account identifier or a value.
 */
public final class AdjustmentOnReconciledPositionException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    private final AccountPurpose position;

    public AdjustmentOnReconciledPositionException(AccountPurpose position) {
        super(
                "a reconciled position ("
                        + position
                        + ") is closed to free adjustments: value there moves only through a"
                        + " break resolution (ADR-0071, INV-REC-06)");
        this.position = Objects.requireNonNull(position, "position must not be null");
    }

    public AccountPurpose position() {
        return position;
    }
}
