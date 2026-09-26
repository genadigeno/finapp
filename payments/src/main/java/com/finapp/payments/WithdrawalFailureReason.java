package com.finapp.payments;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Why a withdrawal {@code FAILED} (`P7-TSK-008`) — ADR-0057 §2's vocabulary verbatim, on the
 * row, so a failed withdrawal is reconcilable without the audit trail.
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
    NEVER_RECEIVED;

    /** The values as a SQL literal list — `V016`'s {@code CHECK} is generated from this. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
