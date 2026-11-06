package com.finapp.ledger;

import java.io.Serial;

/**
 * A manual adjustment's reason is blank - whitespace only, Unicode spaces included ({@link String#isBlank()}) - or its reason or
 * reference holds a card-number or bank-account shape ({@code P14-DOC-001}, the exit review, routed by `P14-TSK-026`'s fix
 * round; {@code INV-REV-04}, {@code INV-AUD-02}): the caller's to correct, a {@code 422}, nothing written. The message never
 * repeats the text - it may be the very text the screen refused.
 *
 * <p>An {@link IllegalArgumentException}, as the blank reason's refusal always was, so a caller that caught that still does;
 * typed so the boundary maps it to the request's {@code 422} rather than the catch-all's {@code 500}.
 */
public final class AdjustmentReasonRefusedException extends IllegalArgumentException {

    @Serial private static final long serialVersionUID = 1L;

    public AdjustmentReasonRefusedException(String detail) {
        super(detail);
    }
}
