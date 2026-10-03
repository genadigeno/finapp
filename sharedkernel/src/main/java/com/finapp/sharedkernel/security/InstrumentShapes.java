package com.finapp.sharedkernel.security;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The platform's one screen for instrument shapes in prose a PERSON writes — reasons, notes,
 * narratives, evidence references ({@code INV-PAY-02}, {@code INV-RAIL-03}, {@code INV-AUD-02};
 * the Phase 8 → 9 transition's correction of the audit's {@code SEC-03} and {@code SEC-04}).
 * Pure: no I/O, no clock, no state, so a verdict is a function of the text alone, and every
 * schema that stores such prose carries a PL/pgSQL twin of it, statement for statement, so a
 * raw writer meets the same refusal the domain gives.
 *
 * <h2>A card number</h2>
 *
 * <p>A <em>run</em> is digits in which a single space or a single dash may sit between two
 * digits — {@code 4111 1111 1111 1111} and {@code 4111-1111-1111-1111} are one run of four
 * groups, the way a person writes a card number. A run holds a card-number shape when
 *
 * <ul>
 *   <li>any 12..19-digit window of one contiguous group is Luhn-valid (the card hidden inside a
 *       longer digit string), or
 *   <li>any span of two or more WHOLE consecutive groups, 12..19 digits once joined, is
 *       Luhn-valid (the card written in groups; a span starts and ends on a group because a
 *       printed card number does).
 * </ul>
 *
 * <p>12..19 is ISO/IEC 7812's length band, the 12-digit cards included; the Luhn checksum is what
 * separates instrument data from invoice numbers and timestamps — the {@code INV-PAY-02} sweep's
 * own rule. The conservative over-refusal is deliberate and the table's too: a refused reason
 * costs a retype, a stored card number costs PCI scope for a store that cannot be cleaned.
 *
 * <h2>An account identifier</h2>
 *
 * <p>A <em>token</em> is a maximal run of ASCII letters and digits. The text holds an account
 * identifier shape when a token is the contiguous international shape — two letters, two
 * digits, 11..30 more letters or digits ({@code INTERNATIONAL_ACCOUNT_SHAPE}, the payments
 * boundary's rule, shape alone as it has always been) — or when a token of two letters and two
 * digits opens its ISO 13616 PRINT form: groups of four separated by a single space or dash, the
 * last group one to four, 15..34 characters in all, whose mod-97 check holds. The printed form
 * is checksum-gated for the reason card numbers are Luhn-gated: groups of four collide with
 * ordinary words ({@code FY26 plan 2027 will need more}), and the checksum is what tells an
 * identifier from prose.
 *
 * <h2>The platform's own identifiers are not instrument data</h2>
 *
 * <p>Before either scan, every UUID as the platform prints one — canonical and dashed, or a
 * version-7 UUID without its dashes (ADR-0013) — standing as a whole token is masked. Its
 * dash-joined hex can hold a Luhn-valid digit span and a dashless one can open with two letters
 * and two digits, and a screen that refused the platform's own references at random would refuse
 * investigators' evidence links and the platform's own history reasons (the formats' precedent:
 * each admits the platform's minted references exactly). A UUID is 32 hex digits: no card number
 * hides in one, and no person writes an account identifier as one.
 *
 * <p><em>(Corrected 2026-10-03 by the Phase 8 → 9 transition's re-gate, NEW-SEC-2: the card
 * scan collapsed only a single space or dash between digit groups, so a card number grouped by
 * the machine separators ':' or '_' — {@code 4111:1111:1111:1111},
 * {@code 4111_1111_1111_1111}, both inside the PSP reference alphabet — passed every prose
 * door. The domain scan behind {@link #find} and {@link #holdsAny} now collapses ':' and '_'
 * beside the printed separators. THE PARITY BOUND: the applied PL/pgSQL twins (settlement
 * {@code V012}, reconciliation {@code V019}) keep the printed forms — settlement {@code V014+}
 * and reconciliation {@code V020+} are reserved for Phase 9 — so the Java rank is strictly
 * WIDER than the database's, and {@link #holdsCardNumber} stays the twin's verdict, statement
 * for statement, which is exactly what the corpus-parity tests prove. The asymmetry is defence
 * in depth, not a gap: every writer of such prose runs the domain screen first, and the twin is
 * the second rank for a raw writer. A machine reference whose ':'/'_'-grouped digits happen to
 * be Luhn-valid is refused by the domain alone — the over-refusal the screen already accepts
 * for dashes.)</em>
 */
public final class InstrumentShapes {

    /** ISO/IEC 7812's card-number length band. */
    public static final int MIN_INSTRUMENT_DIGITS = 12;

    public static final int MAX_INSTRUMENT_DIGITS = 19;

    /**
     * A platform UUID standing as a whole token — dashed (any version, either case) or a
     * lower-case version-7 UUID without its dashes. The twin's {@code regexp_replace} pattern,
     * character for character.
     */
    public static final String PLATFORM_IDENTIFIER_REGEX =
            "(?<![0-9A-Za-z])(?:[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}"
                    + "-[0-9A-Fa-f]{12}|[0-9a-f]{12}7[0-9a-f]{3}[89ab][0-9a-f]{15})(?![0-9A-Za-z])";

    /** Digit groups joined by a single space or dash — the twin's run pattern. */
    public static final String DIGIT_RUN_REGEX = "[0-9]+(?:[ -][0-9]+)*";

    /** Letter-or-digit tokens joined by a single space or dash — the twin's token pattern. */
    public static final String ALPHANUMERIC_RUN_REGEX = "[A-Za-z0-9]+(?:[ -][A-Za-z0-9]+)*";

    private static final Pattern PLATFORM_IDENTIFIER = Pattern.compile(PLATFORM_IDENTIFIER_REGEX);
    private static final Pattern DIGIT_RUN = Pattern.compile(DIGIT_RUN_REGEX);
    private static final Pattern ALPHANUMERIC_RUN = Pattern.compile(ALPHANUMERIC_RUN_REGEX);
    private static final Pattern SEPARATOR = Pattern.compile("[ -]");

    /**
     * Digit groups joined by a single space, dash, colon or underscore — the DOMAIN scan's
     * run pattern behind {@link #find} and {@link #holdsAny} (NEW-SEC-2): the machine
     * separators group a card number as readily as a dash does. The twins keep
     * {@link #DIGIT_RUN_REGEX}, the printed forms.
     */
    private static final Pattern WIDENED_DIGIT_RUN = Pattern.compile("[0-9]+(?:[ :_-][0-9]+)*");

    private static final Pattern WIDENED_SEPARATOR = Pattern.compile("[ :_-]");
    private static final Pattern CONTIGUOUS_ACCOUNT =
            Pattern.compile("[A-Za-z]{2}[0-9]{2}[A-Za-z0-9]{11,30}");
    private static final Pattern COUNTRY_AND_CHECK = Pattern.compile("[A-Za-z]{2}[0-9]{2}");

    /** The printed form's bounds: country, check digits and an 11..30-character remainder. */
    private static final int MIN_PRINTED_LENGTH = 15;

    private static final int MAX_PRINTED_LENGTH = 34;

    private static final int PRINTED_GROUP = 4;

    private InstrumentShapes() {}

    /** Which instrument shape a text holds. */
    public enum Shape {
        CARD_NUMBER,
        ACCOUNT_IDENTIFIER
    }

    /**
     * The shape the text holds, card numbers first — empty when it may be stored. Never the
     * position or the value: a caller's refusal names the shape alone.
     */
    public static Optional<Shape> find(CharSequence text) {
        String masked = masked(text);
        if (cardNumberIn(masked, WIDENED_DIGIT_RUN, WIDENED_SEPARATOR)) {
            return Optional.of(Shape.CARD_NUMBER);
        }
        if (accountIdentifierAt(masked) >= 0) {
            return Optional.of(Shape.ACCOUNT_IDENTIFIER);
        }
        return Optional.empty();
    }

    /** True when the text holds either shape. */
    public static boolean holdsAny(CharSequence text) {
        return find(text).isPresent();
    }

    /**
     * The card-number half as the TWIN scans it — groups joined by single spaces or dashes
     * alone, {@code holds_card_number_shape} statement for statement. {@link #find} and
     * {@link #holdsAny} are strictly wider (NEW-SEC-2): they collapse ':' and '_' too.
     */
    public static boolean holdsCardNumber(CharSequence text) {
        return cardNumberIn(masked(text), DIGIT_RUN, SEPARATOR);
    }

    /** The account-identifier half alone — the twin's {@code holds_account_identifier_shape}. */
    public static boolean holdsAccountIdentifier(CharSequence text) {
        return accountIdentifierAt(masked(text)) >= 0;
    }

    /**
     * Where the first account-identifier shape begins, or -1 — the settlement door's line count
     * reads it. The mask preserves length, so the index is the text's own.
     */
    public static int firstAccountIdentifier(CharSequence text) {
        return accountIdentifierAt(masked(text));
    }

    // ----------------------------------------------------------------------- the mask

    /** Every platform UUID replaced, character for character, by {@code #}. */
    private static String masked(CharSequence text) {
        Objects.requireNonNull(text, "text must not be null");
        StringBuilder out = new StringBuilder(text);
        Matcher identifiers = PLATFORM_IDENTIFIER.matcher(text);
        while (identifiers.find()) {
            for (int i = identifiers.start(); i < identifiers.end(); i++) {
                out.setCharAt(i, '#');
            }
        }
        return out.toString();
    }

    // ----------------------------------------------------------------- the card number

    private static boolean cardNumberIn(String masked, Pattern run, Pattern separator) {
        Matcher runs = run.matcher(masked);
        while (runs.find()) {
            String[] groups = separator.split(runs.group());
            for (String group : groups) {
                if (windowIsLuhnValid(group)) {
                    return true;
                }
            }
            for (int first = 0; first < groups.length; first++) {
                StringBuilder span = new StringBuilder(groups[first]);
                for (int last = first + 1; last < groups.length; last++) {
                    span.append(groups[last]);
                    if (span.length() > MAX_INSTRUMENT_DIGITS) {
                        break;
                    }
                    if (span.length() >= MIN_INSTRUMENT_DIGITS && luhnValid(span, 0, span.length())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Every 12..19-digit window of one contiguous group, Luhn-checked. */
    private static boolean windowIsLuhnValid(String group) {
        for (int width = MIN_INSTRUMENT_DIGITS;
                width <= MAX_INSTRUMENT_DIGITS && width <= group.length();
                width++) {
            for (int start = 0; start + width <= group.length(); start++) {
                if (luhnValid(group, start, width)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean luhnValid(CharSequence digits, int start, int width) {
        int total = 0;
        for (int i = 1; i <= width; i++) {
            int digit = digits.charAt(start + width - i) - '0';
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

    // ------------------------------------------------------------ the account identifier

    private static int accountIdentifierAt(String masked) {
        Matcher runs = ALPHANUMERIC_RUN.matcher(masked);
        while (runs.find()) {
            String run = runs.group();
            String[] tokens = SEPARATOR.split(run);
            int offset = 0;
            for (int first = 0; first < tokens.length; first++) {
                String token = tokens[first];
                if (CONTIGUOUS_ACCOUNT.matcher(token).matches()
                        || (COUNTRY_AND_CHECK.matcher(token).matches()
                                && printedFormFollows(tokens, first))) {
                    return runs.start() + offset;
                }
                offset += token.length() + 1;
            }
        }
        return -1;
    }

    /** Groups of four after the opening token, the last one to four, checksum-valid. */
    private static boolean printedFormFollows(String[] tokens, int opening) {
        StringBuilder compact = new StringBuilder(tokens[opening]);
        for (int next = opening + 1; next < tokens.length; next++) {
            String group = tokens[next];
            if (group.length() > PRINTED_GROUP) {
                return false;
            }
            compact.append(group);
            if (compact.length() > MAX_PRINTED_LENGTH) {
                return false;
            }
            if (compact.length() >= MIN_PRINTED_LENGTH && mod97Valid(compact)) {
                return true;
            }
            if (group.length() < PRINTED_GROUP) {
                return false;
            }
        }
        return false;
    }

    /** ISO 13616's check: the opening four moved to the end, letters as 10..35, mod 97 is 1. */
    private static boolean mod97Valid(CharSequence compact) {
        int remainder = 0;
        int length = compact.length();
        for (int i = 0; i < length; i++) {
            char c = Character.toUpperCase(compact.charAt((i + 4) % length));
            if (c >= '0' && c <= '9') {
                remainder = (remainder * 10 + (c - '0')) % 97;
            } else {
                remainder = (remainder * 100 + (c - 'A' + 10)) % 97;
            }
        }
        return remainder == 1;
    }
}
