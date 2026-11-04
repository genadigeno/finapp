package com.finapp.accounting;

import com.finapp.accounting.reporting.CanonicalJson;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a form line's figure stands in an issued report run (`P14-TSK-018`; ADR-0118 decision 2; {@code INV-ACC-13}): a path
 * from {@code body} to one amount, in a closed grammar and nothing else.
 *
 * <pre>
 * body.equation[currency=EUR].assets
 * body.sections[section=CUSTOMER_FUNDS].rows[glAccount=2000,currency=*].closing
 * </pre>
 *
 * <p>Each step names an object member, and a member holding an array names, in brackets, what selects its element: a member of
 * the element equal to the value. <strong>{@code currency=*}</strong>, at most once, makes one form line per currency the run
 * holds there - per currency, unless the form says otherwise. The header is out of reach: a path starts at {@code body}.
 *
 * <p><strong>Refused, never zero-filled.</strong> A path resolving to nothing, to more than one element, or to something that
 * is not {@code {currency, minor, scale}} is {@link Unresolved}, and so is an array named with no selector, or a selector on
 * what is not an array. A currency with no row is not a zero: nobody issued one. A failure names the path and the cause, never
 * a figure.
 *
 * @param text the path as the obligation states it
 */
public record FigurePath(String text) {

    /** A path's longest length - {@code accounting V010}'s {@code regulatory_submission_line_bounded}. */
    public static final int MAX_LENGTH = 256;

    private static final int MAX_STEPS = 8;
    private static final String WILDCARD = "*";
    private static final String CURRENCY = "currency";
    private static final String NAME = "[A-Za-z][A-Za-z0-9]{0,63}";
    private static final Pattern STEP = Pattern.compile("(" + NAME + ")(?:\\[([^\\]]{1,128})\\])?");
    private static final Pattern SELECTOR = Pattern.compile("(" + NAME + ")=([A-Za-z0-9_-]{1,64}|\\*)");
    private static final Pattern CURRENCY_CODE = Pattern.compile("[A-Z]{3}");
    private static final Pattern MINOR = Pattern.compile("-?[0-9]{1,40}");
    private static final Pattern SCALE = Pattern.compile("[0-9]{1,2}");

    /** The path is not of the grammar - the obligation's proposal is refused. */
    public static final class Malformed extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        Malformed(String detail) {
            super(detail);
        }
    }

    /** Why a well-formed path found no figure. */
    public enum Why { NOTHING, MORE_THAN_ONE, NOT_AN_AMOUNT }

    /** The path found no one figure in this run. */
    public static final class Unresolved extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final Why why;

        Unresolved(Why why, String path) {
            super("the figure path " + path + " resolves to " + why.name());
            this.why = why;
        }

        /** Why. */
        public Why why() {
            return why;
        }
    }

    /**
     * One amount of a run, as issued: minor units as an exact integer, its currency and its scale - never a float.
     *
     * @param path the path that found it, the wildcard replaced by the currency - what the line records
     */
    public record Figure(String path, String currency, BigInteger minor, int scale) {

        public Figure {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(minor, "minor");
        }

        /** An amount stays out of a log line. */
        @Override
        public String toString() {
            return "Figure[" + path + "]";
        }
    }

    private record Step(String member, Map<String, String> selectors) {}

    private record Found(CanonicalJson.Value value, String path, String selected) {}

    /**
     * {@code text}, checked against the grammar.
     *
     * @throws Malformed when it is not of the grammar
     */
    public static FigurePath of(String text) {
        FigurePath path = new FigurePath(Objects.requireNonNull(text, "text"));
        path.steps();
        return path;
    }

    /** Whether it makes one line per currency. */
    public boolean perCurrency() {
        return text.contains(CURRENCY + "=" + WILDCARD);
    }

    /**
     * The figures this path finds - one, or one per currency for {@code currency=*}, sorted by currency.
     *
     * @param content a run's whole content; the path starts at its {@code body}
     * @throws Unresolved when it finds nothing, more than one, or something that is not an amount
     */
    public List<Figure> resolve(CanonicalJson.Obj content) {
        Objects.requireNonNull(content, "content");
        CanonicalJson.Value body = content.members().get("body");
        if (body == null) {
            throw new Unresolved(Why.NOTHING, text);
        }
        TreeMap<String, Figure> perCurrency = new TreeMap<>();
        for (Found found : walk(body, steps(), 0, "body", null)) {
            Figure figure = amount(found.value(), found.path());
            if (found.selected() != null && !found.selected().equals(figure.currency())) {
                throw new Unresolved(Why.NOT_AN_AMOUNT, text);
            }
            if (perCurrency.putIfAbsent(figure.currency(), figure) != null) {
                throw new Unresolved(Why.MORE_THAN_ONE, text);
            }
        }
        if (perCurrency.isEmpty()) {
            throw new Unresolved(Why.NOTHING, text);
        }
        return List.copyOf(perCurrency.values());
    }

    // ------------------------------------------------------------------ the walk

    private List<Found> walk(CanonicalJson.Value at, List<Step> steps, int index, String path, String selected) {
        if (index == steps.size()) {
            return List.of(new Found(at, path, selected));
        }
        if (!(at instanceof CanonicalJson.Obj object)) {
            throw new Unresolved(Why.NOTHING, text);
        }
        Step step = steps.get(index);
        CanonicalJson.Value member = object.members().get(step.member());
        if (member == null) {
            throw new Unresolved(Why.NOTHING, text);
        }
        if (step.selectors().isEmpty()) {
            if (member instanceof CanonicalJson.Arr) {
                throw new Unresolved(Why.MORE_THAN_ONE, text);
            }
            return walk(member, steps, index + 1, path + "." + step.member(), selected);
        }
        if (!(member instanceof CanonicalJson.Arr array)) {
            throw new Unresolved(Why.NOTHING, text);
        }
        List<CanonicalJson.Obj> matched = new ArrayList<>();
        for (CanonicalJson.Value element : array.elements()) {
            if (element instanceof CanonicalJson.Obj candidate && selectedBy(candidate, step.selectors())) {
                matched.add(candidate);
            }
        }
        if (matched.isEmpty()) {
            throw new Unresolved(Why.NOTHING, text);
        }
        boolean wildcard = WILDCARD.equals(step.selectors().get(CURRENCY));
        if (!wildcard && matched.size() > 1) {
            throw new Unresolved(Why.MORE_THAN_ONE, text);
        }
        List<Found> found = new ArrayList<>();
        for (CanonicalJson.Obj element : matched) {
            String code = step.selectors().containsKey(CURRENCY) ? textOf(element, CURRENCY) : selected;
            found.addAll(walk(element, steps, index + 1, path + "." + rendered(step, code), code));
        }
        return found;
    }

    private static boolean selectedBy(CanonicalJson.Obj candidate, Map<String, String> selectors) {
        for (Map.Entry<String, String> selector : selectors.entrySet()) {
            String held = textOf(candidate, selector.getKey());
            if (held == null || (!WILDCARD.equals(selector.getValue()) && !selector.getValue().equals(held))) {
                return false;
            }
        }
        return true;
    }

    private static String textOf(CanonicalJson.Obj object, String member) {
        return object.members().get(member) instanceof CanonicalJson.Text held ? held.value() : null;
    }

    private static String rendered(Step step, String currency) {
        StringBuilder out = new StringBuilder(step.member()).append('[');
        boolean first = true;
        for (Map.Entry<String, String> selector : step.selectors().entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append(selector.getKey()).append('=').append(WILDCARD.equals(selector.getValue()) ? currency : selector.getValue());
        }
        return out.append(']').toString();
    }

    private Figure amount(CanonicalJson.Value value, String path) {
        if (!(value instanceof CanonicalJson.Obj object)) {
            throw new Unresolved(Why.NOT_AN_AMOUNT, text);
        }
        String currency = textOf(object, CURRENCY);
        String minor = textOf(object, "minor");
        String scale = textOf(object, "scale");
        if (currency == null || minor == null || scale == null || !CURRENCY_CODE.matcher(currency).matches()
                || !MINOR.matcher(minor).matches() || !SCALE.matcher(scale).matches()) {
            throw new Unresolved(Why.NOT_AN_AMOUNT, text);
        }
        return new Figure(path, currency, new BigInteger(minor), Integer.parseInt(scale));
    }

    // ------------------------------------------------------------------ the grammar

    private List<Step> steps() {
        if (text.isEmpty() || text.length() > MAX_LENGTH) {
            throw new Malformed("a figure path is 1 to " + MAX_LENGTH + " characters");
        }
        String[] parts = text.split("\\.", -1);
        if (parts.length < 2 || parts.length > MAX_STEPS + 1 || !"body".equals(parts[0])) {
            throw new Malformed("a figure path starts at body and takes 1 to " + MAX_STEPS + " steps");
        }
        List<Step> steps = new ArrayList<>();
        int wildcards = 0;
        for (int i = 1; i < parts.length; i++) {
            Matcher matcher = STEP.matcher(parts[i]);
            if (!matcher.matches()) {
                throw new Malformed("step " + i + " of a figure path is a member and an optional selector");
            }
            Map<String, String> selectors = new LinkedHashMap<>();
            if (matcher.group(2) != null) {
                for (String pair : matcher.group(2).split(",", -1)) {
                    Matcher selector = SELECTOR.matcher(pair);
                    if (!selector.matches() || selectors.putIfAbsent(selector.group(1), selector.group(2)) != null) {
                        throw new Malformed("step " + i + " of a figure path has a malformed or repeated selector");
                    }
                    if (WILDCARD.equals(selector.group(2))) {
                        if (!CURRENCY.equals(selector.group(1))) {
                            throw new Malformed("only a currency is selected by the wildcard");
                        }
                        wildcards++;
                    }
                }
            }
            steps.add(new Step(matcher.group(1), selectors));
        }
        if (wildcards > 1) {
            throw new Malformed("a figure path makes one line per currency at most once");
        }
        if (!steps.get(steps.size() - 1).selectors().isEmpty()) {
            throw new Malformed("a figure path ends at the amount's own member");
        }
        return steps;
    }
}
