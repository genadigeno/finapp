package com.finapp.merchant;

import java.util.regex.Pattern;

/**
 * The shapes raw bank details are written in, refused wherever a payout destination's grant or
 * reference is accepted (`P6-TSK-011`, ADR-0056 §7).
 *
 * <p><strong>Bank details never enter the platform</strong>: the operator's client tokenises
 * them at the payout provider and hands the platform a grant, and the platform stores only the
 * opaque reference the exchange returns — the {@code payment_method} precedent
 * ({@code INV-PAY-02}'s mechanism restated for bank data). The token charset
 * ({@code [A-Za-z0-9_-]}) admits both a domestic account number and an IBAN, so a careless
 * client could send the very detail the design keeps out; these shapes are where that is
 * refused — before any exchange, log line or store — and `V006` restates them at
 * {@code DB-CONSTRAINT} rank, pinned by {@code PayoutDestinationMigrationTest}.
 *
 * <p>Named for the shape rather than the data (the {@code DIGITS_AND_SEPARATORS} precedent):
 * {@code secretsAreWrapped} refuses a bank-detail word on an unwrapped field, and a
 * {@link Pattern} holding a shape rule is not one.
 */
final class BankDetailShapes {

    /**
     * The reference charset and bound, shared by the grant and the reference (`V006`'s bound).
     * Named for the reference rather than the token it is: {@code secretsAreWrapped} rightly
     * refuses a {@code token} word on an unwrapped field, and a shape rule is not one.
     */
    static final int MAX_LENGTH = 128;

    static final String REFERENCE_CHARSET_REGEX = "[A-Za-z0-9_-]{1," + MAX_LENGTH + "}";

    /**
     * Digits alone or with hyphen separators — how a domestic account number or a sort code is
     * written.
     */
    static final String DIGITS_AND_SEPARATORS_REGEX = "[0-9-]+";

    /**
     * Two letters, two digits, then 11 to 30 letters or digits — the international bank account
     * shape (15 to 34 characters), matched without regard to case. No provider mints a
     * reference of this shape, and a value of it is indistinguishable from the detail itself.
     */
    static final String INTERNATIONAL_ACCOUNT_REGEX = "[A-Za-z]{2}[0-9]{2}[A-Za-z0-9]{11,30}";

    private static final Pattern REFERENCE_CHARSET = Pattern.compile(REFERENCE_CHARSET_REGEX);
    private static final Pattern DIGITS_AND_SEPARATORS = Pattern.compile(DIGITS_AND_SEPARATORS_REGEX);
    private static final Pattern INTERNATIONAL_ACCOUNT = Pattern.compile(INTERNATIONAL_ACCOUNT_REGEX);

    private BankDetailShapes() {}

    /**
     * Refuses a value that is not a token, or that is shaped like a bank detail. The message
     * names the rule and never the value ({@code INV-AUD-02}).
     */
    static void requireTokenNotBankDetail(String value, String what) {
        if (!REFERENCE_CHARSET.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    what + " must be 1-" + MAX_LENGTH + " characters of [A-Za-z0-9_-]");
        }
        if (DIGITS_AND_SEPARATORS.matcher(value).matches()
                || INTERNATIONAL_ACCOUNT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    what
                            + " shaped like a bank account number is refused: raw bank details"
                            + " never enter the platform (ADR-0056)");
        }
    }
}
