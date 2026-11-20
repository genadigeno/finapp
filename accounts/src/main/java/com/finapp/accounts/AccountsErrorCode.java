package com.finapp.accounts;

import com.finapp.platform.api.ErrorCode;
import lombok.RequiredArgsConstructor;

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
@RequiredArgsConstructor
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
            "The platform does not operate accounts in this currency."),

    /**
     * The account still holds value, so the agreement cannot end (`P3-TSK-014`).
     *
     * <p>A {@code 409} and a distinct code because it is <strong>actionable</strong>: empty the
     * account and retry — the request was well-formed and the world's state refuses it, the
     * {@code consent.ConsentRequired} shape. The title names no amount and never will
     * ({@code INV-AUD-02}): which balance refused is the caller's own to read from the balance
     * endpoint they already own.
     */
    ACCOUNT_NOT_EMPTY(
            "accounts.AccountNotEmpty",
            409,
            "The account still holds a non-zero balance; empty it and retry."),

    /**
     * The caller's own agreement is not {@code ACTIVE}, so a wallet currency cannot be added
     * to it (`P9-TSK-004`).
     *
     * <p>A {@code 409}: the request was well-formed and the agreement's state refuses it.
     * Distinct from the {@code 404} for unknown and not-yours identifiers because ownership is
     * proven first — the answer is about the caller's own agreement, so it discloses nothing.
     */
    ACCOUNT_NOT_ACTIVE(
            "accounts.AccountNotActive",
            409,
            "The account is not active; a wallet currency cannot be added to it."),

    /**
     * A new outflow from a suspended agreement (`P17-TSK-028`, ADR-0153 decision 2): a withdrawal, a wallet payment, an FX
     * conversion or a cross-border credit, refused in the command's own transaction under the agreement's share lock.
     * Nothing is written; incoming money still lands. A {@code 409}: the caller's own agreement, so it discloses nothing.
     */
    OUTFLOW_SUSPENDED(
            "accounts.OutflowSuspended",
            409,
            "The account is suspended; it cannot send money until it is reinstated."),

    /** A restriction of a kind the agreement's status does not start from (`P17-TSK-028`): suspend a suspended one, reinstate an active one, either on a closed one. */
    RESTRICTION_NOT_APPLICABLE(
            "accounts.RestrictionNotApplicable",
            409,
            "The account's status does not admit this restriction."),

    /** A second proposal while one is open (`P17-TSK-028`): one open proposal per agreement. */
    RESTRICTION_ALREADY_PROPOSED(
            "accounts.RestrictionAlreadyProposed",
            409,
            "The account already has an open restriction proposal; decide it first."),

    /** A decision on a decided restriction (`P17-TSK-028`): decided once. */
    RESTRICTION_ALREADY_DECIDED(
            "accounts.RestrictionAlreadyDecided",
            409,
            "The restriction is already decided."),

    /** The proposer deciding their own restriction (`P17-TSK-028`, INV-AUD-04). */
    RESTRICTION_SELF_DECISION(
            "accounts.RestrictionSelfDecision",
            409,
            "A restriction is decided by a person who did not propose it."),

    /** A reason code of the other kind, or a missing, overlong or instrument-shaped reason (`P17-TSK-028`). */
    RESTRICTION_REASON_INVALID(
            "accounts.RestrictionReasonInvalid",
            422,
            "The restriction's reason code or reason is not acceptable.");

    private final String code;
    private final int status;
    private final String title;

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
