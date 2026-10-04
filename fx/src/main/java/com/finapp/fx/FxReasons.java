package com.finapp.fx;

import com.finapp.sharedkernel.security.InstrumentShapes;
import java.util.Objects;
import java.util.function.Function;

/**
 * The reason screen every reasoned FX act applies (`P9-TSK-007`): 1..1000 characters, never blank,
 * and never a card-number or bank-account shape - a reason is CONFIDENTIAL prose that reaches the
 * version or the request, its history and the audit record ({@code INV-AUD-02}).
 */
final class FxReasons {

    static final int MAX_LENGTH = 1000;

    private FxReasons() {}

    /** Throws {@code refusal}'s exception when {@code reason} is not acceptable. */
    static void refuse(String reason, Function<String, ? extends RuntimeException> refusal) {
        Objects.requireNonNull(reason, "reason must not be null");
        if (reason.isBlank() || reason.length() > MAX_LENGTH) {
            throw refusal.apply("an FX policy act is reasoned: reason must be 1.." + MAX_LENGTH + " characters");
        }
        if (InstrumentShapes.holdsAny(reason)) {
            throw refusal.apply("reason must not hold a card-number or bank-account shape");
        }
    }
}
