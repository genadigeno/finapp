package com.finapp.merchant;

/**
 * No fee schedule answers to the identifier — the surface's one {@code api.NotFound}, unknown
 * and malformed alike ({@code MerchantErrorCode}'s recorded no-not-found-code rule).
 */
public class UnknownFeeScheduleException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public UnknownFeeScheduleException() {
        super("no fee schedule answers to the identifier");
    }
}
