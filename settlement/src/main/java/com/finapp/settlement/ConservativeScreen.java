package com.finapp.settlement;

import com.finapp.sharedkernel.security.InstrumentShapes;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The whole-stream screen (`P8-TSK-002`, ADR-0066 §3): every byte treated as text, no field
 * classes — what the door runs until a source's format version exists to declare them.
 *
 * <p><strong>Deliberately over-cautious.</strong> A 16-digit Luhn-valid order number in a
 * descriptor refuses a genuine file here; the recovery is re-presentation once `P8-TSK-008`'s
 * format screens that field by its declared class (a reference field is never tested as free
 * text). The asymmetry is ADR-0066 §4's: a wrongly refused file costs a re-presentation, a
 * wrongly stored PAN costs PCI scope for the whole store.
 *
 * <p><strong>What it looks for.</strong>
 *
 * <ul>
 *   <li><strong>Card numbers:</strong> digit runs of 13–19, with single spaces or dashes
 *       between digit groups collapsed (a PAN written {@code 4111 1111 1111 1111} is still a
 *       PAN), Luhn-checked — the checksum is what separates instrument data from invoice
 *       numbers and timestamps, the {@code INV-PAY-02} sweep's own rule.
 *   <li><strong>Account identifiers:</strong> the international shape — two letters, two
 *       digits, 11–30 more alphanumerics, bounded by non-alphanumerics — the
 *       {@code INTERNATIONAL_ACCOUNT_SHAPE} rule the payments boundary enforces
 *       ({@code INV-RAIL-03}), applied at this door; and the same identifier in its ISO 13616
 *       printed form, groups of four separated by single spaces or dashes, whose mod-97 check
 *       holds — the platform's one {@link InstrumentShapes} rule for it.
 * </ul>
 *
 * <p><em>(Corrected 2026-10-02 by the Phase 8 → 9 transition: the account shape was the
 * contiguous one alone, so {@code GB82 WEST 1234 5698 7654 32} in a statement's free text passed
 * the door and rested in the stored bytes (the audit's {@code SEC-03}, its related gap). The
 * card-number walk is unchanged: whole runs of 13–19, the door's documented band.)</em>
 *
 * <p>Lines are counted by this walk ({@code \n}, the count 1-based for the last unterminated
 * record), and the count is the 50,000-record bound's input — the screen walks the bytes
 * anyway, and a second counter could disagree with the first.
 */
public final class ConservativeScreen implements DeliveryScreen {

    public static final ConservativeScreen INSTANCE = new ConservativeScreen();

    /** 13–19 digits, the payment-card length band. */
    private static final int MIN_PAN_DIGITS = 13;

    private static final int MAX_PAN_DIGITS = 19;

    private ConservativeScreen() {}

    @Override
    public Screening screen(byte[] content) {
        // ISO-8859-1 maps every byte to a char, so binary content cannot break the walk and
        // digits and letters read as themselves in every ASCII-compatible encoding.
        String text = new String(content, StandardCharsets.ISO_8859_1);
        int line = text.isEmpty() ? 0 : 1;
        Optional<Finding> finding = Optional.empty();
        Run run = new Run();
        for (int i = 0; i < text.length() && finding.isEmpty(); i++) {
            char c = text.charAt(i);
            // A newline is a closing character like any other: the run it ends is judged on
            // the line it accumulated on, BEFORE the line count moves - a card number at the
            // end of a record is still a card number.
            if (run.offer(c)) {
                finding = run.panAt(line);
            }
            if (c == '\n') {
                line++;
            }
        }
        if (finding.isEmpty()) {
            finding = run.closedPanAt(line);
        }
        if (finding.isEmpty()) {
            finding = accountShape(text);
        }
        if (!text.isEmpty() && text.charAt(text.length() - 1) == '\n') {
            line--;
        }
        return new Screening(Math.max(line, 0), finding);
    }

    /**
     * A digit run in flight: digits accumulate; a single space or dash between digits is a
     * separator and keeps the run open; anything else closes it. {@code offer} returns true
     * when the character CLOSED a run worth judging.
     */
    private static final class Run {
        private final StringBuilder digits = new StringBuilder();
        private boolean pendingSeparator;

        boolean offer(char c) {
            if (c >= '0' && c <= '9') {
                digits.append(c);
                pendingSeparator = false;
                return false;
            }
            if ((c == ' ' || c == '-') && !pendingSeparator && !digits.isEmpty()) {
                pendingSeparator = true;
                return false;
            }
            return !digits.isEmpty();
        }

        Optional<Finding> panAt(int line) {
            Optional<Finding> verdict = judged(line);
            reset();
            return verdict;
        }

        /** The stream ended inside a run — judge what accumulated. */
        Optional<Finding> closedPanAt(int line) {
            return judged(line);
        }

        private Optional<Finding> judged(int line) {
            int length = digits.length();
            if (length >= MIN_PAN_DIGITS && length <= MAX_PAN_DIGITS && luhn(digits)) {
                return Optional.of(
                        new Finding(
                                RefusalReason.PRIMARY_ACCOUNT_NUMBER, line, Optional.empty()));
            }
            return Optional.empty();
        }

        void reset() {
            digits.setLength(0);
            pendingSeparator = false;
        }

        private static boolean luhn(CharSequence digits) {
            int sum = 0;
            boolean doubled = false;
            for (int i = digits.length() - 1; i >= 0; i--) {
                int digit = digits.charAt(i) - '0';
                if (doubled) {
                    digit *= 2;
                    if (digit > 9) {
                        digit -= 9;
                    }
                }
                sum += digit;
                doubled = !doubled;
            }
            return sum % 10 == 0;
        }
    }

    /**
     * The first account-identifier shape in the text, with the 1-based line it sits on — a
     * second pass, independent of the digit walk, because an identifier's letters sit outside
     * any digit run.
     */
    private static Optional<Finding> accountShape(String text) {
        int start = InstrumentShapes.firstAccountIdentifier(text);
        if (start < 0) {
            return Optional.empty();
        }
        int line = 1;
        for (int i = 0; i < start; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return Optional.of(
                new Finding(RefusalReason.ACCOUNT_IDENTIFIER, line, Optional.empty()));
    }
}
