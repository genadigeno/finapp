package com.finapp.reconciliation;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The domain rank of the case file's free-text screen (`P8-TSK-014`, ADR-0069 §7;
 * {@code INV-PAY-02}, {@code INV-RAIL-03}): a note body or an evidence reference holding a
 * Luhn-valid 13–19-digit run, or an IBAN shape, is refused with NOTHING stored. The
 * database rank is `V004`'s {@code break_note} and {@code break_evidence_link} {@code CHECK}s
 * — {@code holds_luhn_valid_digit_run} and the unanchored account shape — and this seat
 * mirrors them exactly, so the door refuses what the table would, before any claim is
 * taken. The conservative over-refusal is the table's too (identifier-dense prose may trip
 * the account shape; identifiers belong in evidence links, whose references are screened
 * the same way).
 */
public final class NoteScreen {

    /** A digit run long enough to hold a card number — the function's own scan. */
    private static final Pattern DIGIT_RUN = Pattern.compile("[0-9]{13,}");

    /**
     * `V004`'s account shape: {@code \m[A-Za-z]{2}[0-9]{2}[A-Za-z0-9]{11,30}\M}. PostgreSQL's
     * {@code \m}/{@code \M} are word-start and word-end; with ASCII word characters, Java's
     * lookarounds say the same.
     */
    private static final Pattern ACCOUNT_SHAPE =
            Pattern.compile(
                    "(?<![A-Za-z0-9_])[A-Za-z]{2}[0-9]{2}[A-Za-z0-9]{11,30}(?![A-Za-z0-9_])");

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

    /** The card-number and account shapes alone — the evidence reference's screen. */
    public static java.util.Optional<Refusal> screenShapes(String text) {
        Objects.requireNonNull(text, "text must not be null");
        if (holdsLuhnValidDigitRun(text)) {
            return java.util.Optional.of(Refusal.CARD_NUMBER_SHAPE);
        }
        if (ACCOUNT_SHAPE.matcher(text).find()) {
            return java.util.Optional.of(Refusal.ACCOUNT_SHAPE);
        }
        return java.util.Optional.empty();
    }

    /**
     * `V004`'s {@code holds_luhn_valid_digit_run}, window for window: every 13..19-digit
     * window of every run of 13 or more digits, Luhn-checked.
     */
    static boolean holdsLuhnValidDigitRun(String text) {
        Matcher runs = DIGIT_RUN.matcher(text);
        while (runs.find()) {
            String run = runs.group();
            for (int width = 13; width <= 19 && width <= run.length(); width++) {
                for (int start = 0; start + width <= run.length(); start++) {
                    if (luhnValid(run, start, width)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean luhnValid(String run, int start, int width) {
        int total = 0;
        for (int i = 1; i <= width; i++) {
            int digit = run.charAt(start + width - i) - '0';
            if (i % 2 == 0) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            total += digit;
        }
        return total % 10 == 0;
    }
}
