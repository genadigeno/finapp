package com.finapp.crossborder;

import com.finapp.sharedkernel.security.InstrumentShapes;
import java.util.Objects;
import java.util.function.Function;

/**
 * The reason screen every reasoned corridor act applies (`P9-TSK-015`): 1..1000 characters, never
 * blank, and never a card-number or bank-account shape - a reason is CONFIDENTIAL prose that reaches
 * the version or the request, its history and the audit record ({@code INV-AUD-02}). {@code
 * crossborder V002}'s twin refuses a writer that bypassed it.
 */
final class CrossborderReasons {

    static final int MAX_LENGTH = 1000;

    private CrossborderReasons() {}

    /** Throws {@code refusal}'s exception when {@code reason} is not acceptable. */
    static void refuse(String reason, Function<String, ? extends RuntimeException> refusal) {
        Objects.requireNonNull(reason, "reason must not be null");
        if (reason.isBlank() || reason.length() > MAX_LENGTH) {
            throw refusal.apply("a corridor policy act is reasoned: reason must be 1.." + MAX_LENGTH + " characters");
        }
        if (InstrumentShapes.holdsAny(reason)) {
            throw refusal.apply("reason must not hold a card-number or bank-account shape");
        }
    }

    /** {@code reason} cut to the bound - a derived history reason must still fit its column. */
    static String bounded(String reason) {
        if (reason.length() <= MAX_LENGTH) {
            return reason;
        }
        int end = MAX_LENGTH;
        if (Character.isHighSurrogate(reason.charAt(end - 1))) {
            end--;
        }
        return reason.substring(0, end);
    }
}
