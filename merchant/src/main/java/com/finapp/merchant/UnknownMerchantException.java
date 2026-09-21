package com.finapp.merchant;

/**
 * No merchant answers to the identifier — the surface's one {@code api.NotFound}, unknown and
 * malformed alike; when `P6-TSK-002`'s tenant-scoped reads arrive, another-tenant's folds into
 * the same answer ({@code INV-MER-01}).
 */
public class UnknownMerchantException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public UnknownMerchantException() {
        super("no merchant answers to the identifier");
    }
}
