package com.finapp.merchant;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Why a payout is {@code FAILED} (`P6-TSK-012`, ADR-0057 §2) — the payment attempt's
 * vocabulary, recorded on the row so a failed payout is reconcilable without reading the audit
 * trail (the refund, which lacks this, can tell a decline from an unreachable provider only by
 * its audit summary).
 */
public enum PayoutFailureReason {

    /** The rail refused the payout. */
    DECLINED,

    /**
     * The connection was refused before anything was sent — knowledge, not ambiguity (ADR-0046
     * §2) — and only ever on a payout's FIRST send: a re-send's refused connection says nothing
     * about the send before it (ADR-0057 §3).
     */
    PROVIDER_UNAVAILABLE,

    /**
     * The provider has no record of our reference, asked after the send permit aged past the
     * dispatched bound — the request never arrived, and no send can follow (ADR-0057 §4).
     */
    NEVER_RECEIVED;

    /** The reasons as a SQL literal list, for the {@code CHECK} constraint. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(reason -> "'" + reason.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
