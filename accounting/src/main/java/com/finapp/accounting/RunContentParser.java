package com.finapp.accounting;

import com.finapp.accounting.reporting.CanonicalJson;
import java.util.Objects;

/**
 * A strict reader of {@link CanonicalJson}'s form (`P14-TSK-018`) - the one way this module reads an issued report run's text
 * or an obligation's configuration back. {@code accounting} has no JSON dependency (ADR-0113 decision 1), and needs none: the
 * form has no whitespace, no JSON number, sorted keys and a closed list of escapes, so a reader of exactly that is small
 * enough to be sure of, and anything else is refused rather than guessed at.
 *
 * <p><strong>The proof it read what was issued:</strong> what it parsed is written back and must equal the text, byte for
 * byte. So a non-canonical text (an escaped solidus, an upper-case hex escape, unsorted or repeated keys) is refused, never
 * normalised into something a person did not attest.
 *
 * <p>A failure names a position, never the text: a run's content is {@code CONFIDENTIAL} and an exception's message can reach
 * a log line.
 */
public final class RunContentParser {

    /** The text is not canonical JSON. */
    public static final class NotCanonical extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        NotCanonical(String detail) {
            super(detail);
        }
    }

    private final String text;
    private int at;

    private RunContentParser(String text) {
        this.text = text;
    }

    /**
     * {@code text} as the object it is.
     *
     * @throws NotCanonical when it is not an object in {@link CanonicalJson}'s form
     */
    public static CanonicalJson.Obj object(String text) {
        Objects.requireNonNull(text, "text");
        RunContentParser parser = new RunContentParser(text);
        CanonicalJson.Value value = parser.value();
        if (parser.at != text.length()) {
            throw new NotCanonical("text follows the value at " + parser.at);
        }
        if (!(value instanceof CanonicalJson.Obj object)) {
            throw new NotCanonical("the value is not an object");
        }
        if (!CanonicalJson.text(object).equals(text)) {
            throw new NotCanonical("the text is not the canonical form of what it holds");
        }
        return object;
    }

    private CanonicalJson.Value value() {
        char next = peek();
        return switch (next) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> text();
            case 't' -> literal("true", CanonicalJson.Flag.TRUE);
            case 'f' -> literal("false", CanonicalJson.Flag.FALSE);
            case 'n' -> literal("null", CanonicalJson.Null.INSTANCE);
            default -> throw new NotCanonical("no value of the form starts at " + at);
        };
    }

    private CanonicalJson.Value text() {
        String read = string();
        try {
            return new CanonicalJson.Text(read);
        } catch (IllegalArgumentException refused) {
            throw new NotCanonical("a string the form refuses ends before " + at);
        }
    }

    private CanonicalJson.Obj object() {
        expect('{');
        CanonicalJson.Obj object = CanonicalJson.object();
        if (peek() == '}') {
            at++;
            return object;
        }
        while (true) {
            String key = string();
            expect(':');
            CanonicalJson.Value member = value();
            try {
                object.put(key, member);
            } catch (IllegalArgumentException repeated) {
                throw new NotCanonical("a key is repeated or refused before " + at);
            }
            if (peek() == ',') {
                at++;
                continue;
            }
            expect('}');
            return object;
        }
    }

    private CanonicalJson.Arr array() {
        expect('[');
        CanonicalJson.Arr array = CanonicalJson.array();
        if (peek() == ']') {
            at++;
            return array;
        }
        while (true) {
            array.add(value());
            if (peek() == ',') {
                at++;
                continue;
            }
            expect(']');
            return array;
        }
    }

    private String string() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char escape = next();
            switch (escape) {
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> out.append(unicode());
                default -> throw new NotCanonical("an escape outside the form at " + at);
            }
        }
    }

    private char unicode() {
        if (at + 4 > text.length()) {
            throw new NotCanonical("a truncated escape at " + at);
        }
        try {
            char c = (char) Integer.parseInt(text.substring(at, at + 4), 16);
            at += 4;
            return c;
        } catch (NumberFormatException malformed) {
            throw new NotCanonical("a malformed escape at " + at);
        }
    }

    private CanonicalJson.Value literal(String word, CanonicalJson.Value value) {
        if (!text.startsWith(word, at)) {
            throw new NotCanonical("no value of the form starts at " + at);
        }
        at += word.length();
        return value;
    }

    private char peek() {
        if (at >= text.length()) {
            throw new NotCanonical("the text ends early");
        }
        return text.charAt(at);
    }

    private char next() {
        char c = peek();
        at++;
        return c;
    }

    private void expect(char wanted) {
        if (next() != wanted) {
            throw new NotCanonical("the form is broken before " + at);
        }
    }
}
