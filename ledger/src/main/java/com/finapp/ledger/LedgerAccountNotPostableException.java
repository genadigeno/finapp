package com.finapp.ledger;

import java.io.Serial;

/**
 * A posting named an account that is no longer {@code ACTIVE} (`P3-TSK-014`).
 *
 * <p>The refusal itself is the database's — `V007`'s trigger, whose locked status read is what
 * makes it race-proof against a concurrent close — and this is its domain-shaped translation, so
 * a caller (Phase 4's transfers foremost) can treat "the account stopped accepting postings" as
 * a domain outcome rather than a storage failure. The message names the account, never an
 * amount ({@code INV-AUD-02}).
 */
public final class LedgerAccountNotPostableException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    public LedgerAccountNotPostableException(String message) {
        super(message);
    }
}
