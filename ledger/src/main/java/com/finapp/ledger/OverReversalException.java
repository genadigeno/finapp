package com.finapp.ledger;

import java.io.Serial;

/**
 * The {@code INV-REV-02} refusal: this reversal, plus every prior reversal of the same
 * original, would exceed the original — or the reversal mirrors a pair the original does not
 * have, or names a {@code REVERSAL} as its original ({@code INV-REV-01}'s chain refusal).
 *
 * <p>Over-reversal creates money, which is why the bound's authoritative arbiter is `V009`'s
 * trigger under the advisory lock (binding every writer); this exception is both the domain
 * pre-check's refusal and the store's translation of the trigger's {@code 23514}, so a
 * commanding flow — Phase 5's refunds foremost — sees one named outcome whichever layer
 * refused. The message names entries and accounts, <strong>never an amount</strong>
 * ({@code INV-AUD-02}).
 */
public final class OverReversalException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    public OverReversalException(String message) {
        super(message);
    }
}
