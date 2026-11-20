package com.finapp.kyc;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What a reviewer asks the customer, in words the customer will read (`P17-TSK-126`, ADR-0155 decision 4): <strong>the
 * tipping-off control, enforced by construction.</strong>
 *
 * <p>A case is in review because some check raised something, and that check may be a sanctions, PEP or adverse-media screening.
 * The request's text is the one channel from the review surface to the subject, so a text that could tip the subject off cannot
 * be built: {@link #of} refuses it with {@link Refused}, naming the {@link Rule} it broke, and nothing downstream - the
 * request row, the customer's read - ever holds one. The door answers {@code 422 kyc.RequestTextRefused} and records the refusal
 * ({@code door.ActRefused}, never the body).
 *
 * <p><strong>The rules.</strong>
 * <ul>
 *   <li>{@link Rule#LENGTH}: 20 to 1,000 characters (code points), the bound kyc {@code V016} holds too - long enough to say
 *       what is needed, short enough to read;</li>
 *   <li>{@link Rule#CHARACTERS}: no control character but a line break or a tab;</li>
 *   <li>{@link Rule#SCREENING_VOCABULARY}: none of {@link #STEMS} at the start of a word, and none of {@link #PHRASES} as whole
 *       words, after Unicode compatibility normalisation, case folding and punctuation folded to spaces - so "Sanctions",
 *       "S&#173;anctioned" written with a soft hyphen, "politically-exposed" and "PEP" are all refused;</li>
 *   <li>{@link Rule#INTERNAL_IDENTIFIER}: no UUID - a case, check, task or document identifier is the platform's, and one in a
 *       customer's hands names what was looked at;</li>
 *   <li>{@link Rule#LINK}: no link and no e-mail address - a text in the platform's name sends the customer nowhere but the
 *       app, which is also what makes a phished copy of it recognisable.</li>
 * </ul>
 *
 * <p><strong>What this is not.</strong> A vocabulary control stops the careless sentence and makes the deliberate one visible: a
 * reviewer who spells around it has written, under their own name, a text the case file shows every reviewer and a refusal trail
 * shows every attempt. The list grows by review, like the reason codes.
 *
 * <p><strong>Screened when written, not when read.</strong> {@link #stored} reads a row back unscreened: the text passed the
 * rules in force when it was asked, and a rule added since does not unwrite history (the rehydrate idiom - the row was already
 * valid).
 */
public final class CustomerRequestText {

    /** The fewest characters a request says what is needed in. */
    public static final int MIN_LENGTH = 20;

    /** The most characters a request may run to; kyc {@code V016}'s {@code information_request_text_is_bounded}. */
    public static final int MAX_LENGTH = 1000;

    /** Which rule a refused text broke. */
    public enum Rule {
        LENGTH,
        CHARACTERS,
        SCREENING_VOCABULARY,
        INTERNAL_IDENTIFIER,
        LINK
    }

    /**
     * Word beginnings that name a screening, a list, a suspicion or an investigation: refused at the start of any word, so each
     * covers its inflections ("sanction" refuses "sanctions" and "sanctioned").
     */
    static final List<String> STEMS = List.of(
            "sanction", "embargo", "watchlist", "blacklist", "blocklist", "terroris", "launder", "suspicio", "investigat",
            "fraud", "crimin", "extremis", "proliferat");

    /** Whole words and phrases, refused where they stand as words: the short ones a stem would over-match. */
    static final List<String> PHRASES = List.of(
            "pep", "peps", "politically exposed", "watch list", "black list", "block list", "adverse media", "adverse news",
            "negative news", "screening", "screened", "rescreen", "rescreening", "ofac", "ofsi", "hm treasury", "un list",
            "eu list", "consolidated list", "denied party", "denied persons", "specially designated", "sdn", "asset freeze",
            "assets frozen", "frozen", "freeze", "law enforcement", "police", "financial intelligence", "fiu", "fincen",
            "suspicious activity", "tipping off", "tip off", "proceeds of crime", "risk score", "risk rating", "flagged",
            "red flag", "aml", "cft");

    private static final Pattern UUID_SHAPE = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static final Pattern LINK_SHAPE = Pattern.compile(
            "(?i)(?:[a-z][a-z0-9+.-]*://|\\bwww\\.|[^\\s@]+@[^\\s@]+\\.[^\\s@]+)");

    /** Anything that is not a letter or a digit, folded to one space before the vocabulary is read. */
    private static final Pattern NOT_A_WORD_CHARACTER = Pattern.compile("[^\\p{L}\\p{N}]+");

    /** Format characters (a soft hyphen, a zero-width joiner): removed before folding, so they cannot split a word. */
    private static final Pattern FORMAT_CHARACTERS = Pattern.compile("\\p{Cf}+");

    private final String value;

    private CustomerRequestText(String value) {
        this.value = value;
    }

    /**
     * The text as the reviewer wrote it, unchanged - the customer reads what was screened - or {@link Refused}.
     *
     * @throws Refused the text breaks a {@link Rule}
     */
    public static CustomerRequestText of(String value) {
        Objects.requireNonNull(value, "value must not be null");
        int length = value.codePointCount(0, value.length());
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            throw new Refused(Rule.LENGTH,
                    "a request to the customer is " + MIN_LENGTH + " to " + MAX_LENGTH + " characters");
        }
        if (value.codePoints().anyMatch(CustomerRequestText::forbiddenControl)) {
            throw new Refused(Rule.CHARACTERS, "a request to the customer carries no control character but a line break or a tab");
        }
        if (UUID_SHAPE.matcher(value).find()) {
            throw new Refused(Rule.INTERNAL_IDENTIFIER,
                    "a request to the customer carries none of the platform's identifiers");
        }
        if (LINK_SHAPE.matcher(value).find()) {
            throw new Refused(Rule.LINK, "a request to the customer carries no link and no e-mail address");
        }
        Optional<String> term = screeningTerm(value);
        if (term.isPresent()) {
            throw new Refused(Rule.SCREENING_VOCABULARY,
                    "a request to the customer never names a screening, a list, a suspicion or an investigation: \""
                            + term.get() + "\"");
        }
        return new CustomerRequestText(value);
    }

    /** A text read back from storage: screened when it was written, so not again ({@code JdbcInformationRequestStore}). */
    static CustomerRequestText stored(String value) {
        return new CustomerRequestText(Objects.requireNonNull(value, "value must not be null"));
    }

    /** The text the customer reads. */
    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CustomerRequestText text && value.equals(text.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    /** The first screening term {@code text} carries, if any - the rule's own reading, exposed for its test. */
    static Optional<String> screeningTerm(String text) {
        String folded = " " + NOT_A_WORD_CHARACTER
                .matcher(FORMAT_CHARACTERS.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC)).replaceAll(""))
                .replaceAll(" ")
                .toLowerCase(Locale.ROOT)
                .trim() + " ";
        for (String phrase : PHRASES) {
            if (folded.contains(" " + phrase + " ")) {
                return Optional.of(phrase);
            }
        }
        for (String stem : STEMS) {
            if (folded.contains(" " + stem)) {
                return Optional.of(stem);
            }
        }
        return Optional.empty();
    }

    private static boolean forbiddenControl(int codePoint) {
        return Character.getType(codePoint) == Character.CONTROL && codePoint != '\n' && codePoint != '\t';
    }

    /** Never the text: a log line or an exception's message is no place for what the customer will read. */
    @Override
    public String toString() {
        return "CustomerRequestText[" + value.codePointCount(0, value.length()) + " characters]";
    }

    /**
     * A text the customer may not be sent. Its message names the rule and never echoes the text; the door renders it
     * {@code 422 kyc.RequestTextRefused}.
     */
    public static final class Refused extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        private final Rule rule;

        Refused(Rule rule, String message) {
            super(message);
            this.rule = rule;
        }

        public Rule rule() {
            return rule;
        }
    }
}
