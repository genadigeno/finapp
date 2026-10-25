package com.finapp.risk;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * What a risk action acts on (`P13-TSK-017`; {@code PHASE_13_PLAN.md} section 12.8; ADR-0112 decision 2) - the kind of
 * the {@code risk_action.target_ref}. A party's and a merchant's actions act on the case's own subject; the held kinds
 * act on the judged movement itself - the withdrawal's or the payment intent's id, which is its sealed assessment's
 * command ref ({@link OverrideEffect#targetKind} names the same two). Referenced by identifier only, never by a row of
 * another module ({@code INV-RSK-02}).
 */
public enum ActionTarget {

    /** A party - every identity it holds is acted on by identity's commands. */
    PARTY,

    /** A merchant. */
    MERCHANT,

    /** A customer account (no owning command exists - {@link ActionKind#SUSPEND_ACCOUNT} is unavailable). */
    ACCOUNT,

    /** A cross-border beneficiary (no owning command exists - {@link ActionKind#BLOCK_BENEFICIARY} is unavailable). */
    BENEFICIARY,

    /** A held withdrawal, by its id. */
    WITHDRAWAL,

    /** A held card payment, by its payment intent's id. */
    PAYMENT_INTENT;

    /** The vocabulary as a SQL list - {@code V013}'s CHECK is held to it. */
    public static String sqlValueList() {
        return Arrays.stream(values()).map(value -> "'" + value.name() + "'").collect(Collectors.joining(", "));
    }
}
