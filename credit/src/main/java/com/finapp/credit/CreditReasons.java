package com.finapp.credit;

import com.finapp.sharedkernel.security.InstrumentShapes;
import java.util.Optional;

/**
 * The screen every person-written credit reason passes (the Phase 10 to 11 transition; {@code INV-AUD-02},
 * {@code INV-PAY-02}; the {@code FxReasons} / {@code CrossborderReasons} / {@code NoteScreen} precedent): a policy or
 * scorecard proposal and its decision, an underwriter's decision reason and a refused or approved second approval, an
 * investigator's evidence-read and replay reasons. Each is CONFIDENTIAL prose that reaches a table no role can clean
 * and the audit record - so it never holds a card-number or bank-account shape. Credit {@code V016} holds the same
 * rule beneath every reason column (its PL/pgSQL twin), for a writer that bypassed this screen.
 */
public final class CreditReasons {

    /** The bound every credit reason column holds. */
    public static final int MAX_LENGTH = 1000;

    private CreditReasons() {}

    /**
     * What is wrong with {@code reason}, or empty when it is acceptable: present, not blank, at most
     * {@link #MAX_LENGTH} characters, and holding no instrument shape.
     */
    public static Optional<String> defect(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > MAX_LENGTH) {
            return Optional.of("a reason of 1 to " + MAX_LENGTH + " characters is required");
        }
        if (InstrumentShapes.holdsAny(reason)) {
            return Optional.of("a reason must not hold a card-number or bank-account shape");
        }
        return Optional.empty();
    }

    /** Whether {@code reason} - present - holds a card-number or bank-account shape. */
    public static boolean holdsInstrument(String reason) {
        return reason != null && InstrumentShapes.holdsAny(reason);
    }
}
