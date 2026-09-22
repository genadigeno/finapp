package com.finapp.merchant;

/**
 * No key of this merchant answers to the identifier — unknown, malformed and
 * <em>another merchant's</em> alike, because the tenant rides in the statement
 * ({@code INV-MER-01}) and all three produce the same empty answer. The surface renders one
 * {@code api.NotFound}: a distinct code would make the key routes an oracle over other
 * merchants' credentials.
 */
public class UnknownMerchantApiKeyException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public UnknownMerchantApiKeyException() {
        super("no api key of this merchant answers to the identifier");
    }
}
