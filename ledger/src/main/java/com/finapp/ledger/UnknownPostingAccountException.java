package com.finapp.ledger;

import java.io.Serial;

/**
 * A journal line named an account the chart does not have, or a currency foreign to that
 * account (`P3-TSK-017`).
 *
 * <p>The refusal itself is the database's — the {@code journal_line} FK and `V005`'s
 * composite currency binding ({@code 23503}) — and this is its domain-shaped translation
 * (the `V007` pattern), so a boundary can answer the caller's own {@code 422} rather than
 * our {@code 500}. The message names the entry, never a row and never an amount
 * ({@code INV-AUD-02}).
 */
public final class UnknownPostingAccountException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    public UnknownPostingAccountException(String message) {
        super(message);
    }
}
