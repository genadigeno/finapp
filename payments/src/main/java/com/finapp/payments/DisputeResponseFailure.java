package com.finapp.payments;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Why a dispute response FAILED (`P7-TSK-014`) — the withdrawal's vocabulary, trimmed to the
 * two a response can prove: a response moves no money, so no deadline of ours ever concludes
 * one (the respond-by date is the network's, and the PSP judges it).
 */
public enum DisputeResponseFailure {

    /** The PSP refused the response in so many words — past its deadline, not respondable. */
    DECLINED,

    /** The FIRST send's connection was refused: nothing was ever transmitted (ADR-0057 §3). */
    PROVIDER_UNAVAILABLE;

    /** The quoted, comma-separated value list `V022`'s {@code CHECK} uses. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
