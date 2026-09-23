package com.finapp.merchant;

/**
 * A key state move outside the machine's one edge ({@code INV-LIFE-02}) — in practice,
 * revoking an already-revoked key, which the command converges on rather than surfacing.
 * Names the state only ({@code INV-AUD-02}): no key id, no hash, nothing a log should hold.
 */
public class IllegalMerchantApiKeyTransitionException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public IllegalMerchantApiKeyTransitionException(MerchantApiKeyStatus from) {
        super("a merchant api key cannot move out of " + from);
    }
}
