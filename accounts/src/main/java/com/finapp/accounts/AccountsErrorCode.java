package com.finapp.accounts;

import com.finapp.platform.api.ErrorCode;

/**
 * The failures this module reports to a client (`P3-TSK-013`).
 *
 * <p>Namespaced {@code accounts.*} so two modules cannot give one string two meanings
 * ({@code ERROR_CONTRACT.md} §4), and permanent: a client's error handling is written against
 * these strings, so one is deprecated rather than renamed.
 *
 * <p><strong>There is no not-found code here, deliberately</strong>: an unknown, not-yours or
 * malformed account identifier is {@code api.NotFound}, byte-identical across its causes —
 * a distinct code would make the balance endpoint an oracle over other people's accounts
 * (the {@code P1-TSK-016} reasoning).
 */
public enum AccountsErrorCode implements ErrorCode {

    /**
     * The caller holds no customer eligible to hold accounts ({@code INV-KYC-05}'s gate,
     * `P3-TSK-012`).
     *
     * <p>A {@code 409} and a distinct code because it is <strong>actionable</strong>
     * ({@code P1-TSK-018}'s test for earning one): the remedy is to complete verification and
     * retry — the {@code consent.ConsentRequired} shape, and deliberately not a {@code 403},
     * which means "you hold no role" and is unfixable by the caller. <strong>Cause-blind, on
     * purpose</strong>: no relationship, a verification still pending and a terminal one are
     * one refusal — which of them refused is what the gate keeps indistinguishable, and the
     * exception deliberately cannot say.
     */
    ACCOUNT_OPENING_REFUSED(
            "accounts.AccountOpeningRefused",
            409,
            "The caller is not eligible to hold accounts; complete verification and retry."),

    /**
     * The requested account currency is not one the platform operates in (`P3-TSK-003`'s
     * {@code SupportedCurrencies} — "supported" means postable).
     *
     * <p>A {@code 422}: the currency is a value the caller chose and must be able to correct
     * (the {@code P1-TSK-026} short-password reasoning), and naming it discloses nothing —
     * which currencies the platform operates in is published by every account it opens.
     */
    UNSUPPORTED_CURRENCY(
            "accounts.UnsupportedCurrency",
            422,
            "The platform does not operate accounts in this currency.");

    private final String code;
    private final int status;
    private final String title;

    AccountsErrorCode(String code, int status, String title) {
        this.code = code;
        this.status = status;
        this.title = title;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public String title() {
        return title;
    }
}
