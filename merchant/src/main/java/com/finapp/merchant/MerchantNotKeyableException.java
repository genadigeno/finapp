package com.finapp.merchant;

/**
 * The merchant cannot hold an API credential — it is {@code CLOSED}. Issuing one would be a
 * door into a relationship that ended, which is the kind of credential nobody goes looking
 * for until it is used. A {@code SUSPENDED} merchant may still be issued keys: suspension is
 * reversible administrative state, and its keys already refuse at authentication through the
 * lookup's join, so the issuance is harmless and the operator is spared a second step when the
 * suspension lifts.
 */
public class MerchantNotKeyableException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public MerchantNotKeyableException() {
        super("a closed merchant cannot be issued an api key");
    }
}
