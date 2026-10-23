package com.finapp.payments;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Why a withdrawal {@code FAILED} (`P7-TSK-008`) — ADR-0057 §2's vocabulary verbatim, on the
 * row, so a failed withdrawal is reconcilable without the audit trail - the scheme's three, and
 * risk's refusal (`P13-TSK-009`), payments' own committed outcome of its seam's verdict.
 */
public enum WithdrawalFailureReason {

    /** The scheme refused it — knowledge, the rail's own word. */
    DECLINED,

    /** Nothing was sent: the FIRST send's connection was refused before anything left, and
     * no later permit had overtaken it (ADR-0057 §3, re-judged on the locked row). */
    PROVIDER_UNAVAILABLE,

    /** The scheme explicitly does not recognise our reference, past the declared outcome
     * deadline plus margin judged against the latest send permit (ADR-0062 §3): never
     * executed, and no send can follow the conclusion. */
    NEVER_RECEIVED,

    /**
     * Refused by risk (`P13-TSK-009`, payments {@code V033}; {@code PHASE_13_PLAN.md} §7.3; C6): at the dispatch's
     * in-lock judgement (born {@code FAILED}), by a keyed denial of a {@code HELD} withdrawal, or by the expiry of its
     * hold ({@link WithdrawalFailureDetail#HOLD_EXPIRED}). Nothing was ever sent; the hold was released in the same
     * transaction as the edge. Never after a dispatch: once the scheme may have the money, only its word fails a
     * withdrawal. The customer reads this word and nothing of the judgement ({@code INV-RSK-08}).
     */
    RISK_REFUSED;

    /** The values as a SQL literal list — `V016`'s {@code CHECK} is generated from this. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
