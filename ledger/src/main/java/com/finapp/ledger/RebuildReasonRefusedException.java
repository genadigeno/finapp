package com.finapp.ledger;

import java.io.Serial;

/**
 * A rebuild proposal's reason is missing, blank - a reason made only of whitespace, ideographic and other Unicode spaces
 * included - longer than {@value ProjectionRebuild#MAX_REASON_LENGTH} characters, or holds a card-number or bank-account
 * shape (`P14-TSK-026`'s fix round, M-3 and M-4; {@code INV-AUD-02}): the caller's to correct, a {@code 422}. Nothing was
 * written. The message never repeats the reason - it may be the very text the screen refused.
 */
public final class RebuildReasonRefusedException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    public RebuildReasonRefusedException(String detail) {
        super(detail);
    }
}
