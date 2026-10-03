package com.finapp.reconciliation;

import com.finapp.sharedkernel.security.InstrumentShapes;
import java.util.Objects;

/**
 * The domain rank of the case file's free-text screen (`P8-TSK-014`, ADR-0069 §7;
 * {@code INV-PAY-02}, {@code INV-RAIL-03}): a note body, an evidence reference or any reason a
 * person writes holding a card-number shape or an account-identifier shape is refused with
 * NOTHING stored. The rule is the platform's one {@link InstrumentShapes} screen; the database
 * rank is its PL/pgSQL twin ({@code reconciliation.holds_card_number_shape} and
 * {@code holds_account_identifier_shape}, `V019`) on every column that stores such prose, so the
 * door refuses what the table would, before any claim is taken. The conservative over-refusal
 * is the table's too (identifier-dense prose may trip a shape; identifiers belong in evidence
 * links, whose references are screened the same way).
 *
 * <p><em>(Corrected 2026-10-02 by the Phase 8 → 9 transition: this seat scanned contiguous
 * digit runs and the contiguous account shape alone, so a card number written
 * {@code 4111 1111 1111 1111} or {@code 4111-1111-1111-1111} and an account identifier in its
 * printed groups of four passed both ranks (the audit's {@code SEC-03}). It now delegates to
 * the shared screen — grouped runs, the 12..19 band, the printed form under its mod-97 check,
 * the platform's own UUIDs masked — and `V019` replaced `V004`'s and `V006`'s scans with the
 * twin.)</em>
 */
public final class NoteScreen {

    public static final int MAX_NOTE_LENGTH = 4000;

    private NoteScreen() {}

    /** Why a text was refused — the position of the fault is never echoed with its value. */
    public enum Refusal {
        EMPTY,
        TOO_LONG,
        CARD_NUMBER_SHAPE,
        ACCOUNT_SHAPE
    }

    /** The note body's verdict: empty when it may be stored. */
    public static java.util.Optional<Refusal> screenNote(String body) {
        Objects.requireNonNull(body, "body must not be null");
        if (body.isEmpty()) {
            return java.util.Optional.of(Refusal.EMPTY);
        }
        if (body.length() > MAX_NOTE_LENGTH) {
            return java.util.Optional.of(Refusal.TOO_LONG);
        }
        return screenShapes(body);
    }

    /** The card-number and account shapes alone — every person-written reason's screen. */
    public static java.util.Optional<Refusal> screenShapes(String text) {
        Objects.requireNonNull(text, "text must not be null");
        return InstrumentShapes.find(text)
                .map(
                        shape ->
                                shape == InstrumentShapes.Shape.CARD_NUMBER
                                        ? Refusal.CARD_NUMBER_SHAPE
                                        : Refusal.ACCOUNT_SHAPE);
    }

    /** The card-number half — the twin's {@code holds_card_number_shape}. */
    static boolean holdsLuhnValidDigitRun(String text) {
        return InstrumentShapes.holdsCardNumber(text);
    }
}
