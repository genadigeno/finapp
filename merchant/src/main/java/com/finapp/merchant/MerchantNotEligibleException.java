package com.finapp.merchant;

/**
 * The party cannot be onboarded ({@code merchant.NotEligible}). Carries no cause and no
 * identifier, deliberately: no-such-party, a person party, no customer relationship and an
 * unverified one are one refusal ({@link MerchantVerification}'s contract — the onboarding
 * surface is not an oracle over parties or their compliance standing).
 */
public class MerchantNotEligibleException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public MerchantNotEligibleException() {
        super("the party cannot be onboarded as a merchant");
    }
}
