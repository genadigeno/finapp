package com.finapp.accounting.reporting;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * What a report says about itself (`P14-TSK-017`'s acceptance criterion: "every report's source, rule, date, currency
 * semantics and freshness stated in its definition"; ADR-0115 As built): the five facts a reader needs before believing a
 * figure. {@link ReportDefinition#statement()} is abstract, so a definition cannot omit one and compile, and this
 * constructor refuses an empty or placeholder fact, so it cannot be omitted by writing nothing.
 *
 * <p>A definition's body writes {@link #asValue()} beside its figures ({@code TRIAL_BALANCE} v1 excepted: its bytes are
 * fixed), so the issued output carries it. A change to the words is therefore a change to what the definition produces - a
 * new version.
 *
 * @param source what the figures are read from, and what they are never read from
 * @param rule how a figure is computed from the source
 * @param date which date places a figure in the period, and what instant it is as of
 * @param currencySemantics how currencies and scales are held apart, and what is never summed
 * @param freshness FINAL or operational, and how old an answer can be
 */
public record ReportStatement(String source, String rule, String date, String currencySemantics, String freshness) {

    /** The fewest characters a fact is stated in: long enough that a placeholder cannot pass by accident. */
    static final int LEAST = 12;

    private static final Set<String> PLACEHOLDERS = Set.of("n/a", "na", "none", "todo", "tbd", "unknown", "see above");

    public ReportStatement {
        stated("source", source);
        stated("rule", rule);
        stated("date", date);
        stated("currencySemantics", currencySemantics);
        stated("freshness", freshness);
    }

    /** The five facts as the content's {@code statement} member. */
    public CanonicalJson.Obj asValue() {
        return CanonicalJson.object()
                .put("source", source)
                .put("rule", rule)
                .put("date", date)
                .put("currencySemantics", currencySemantics)
                .put("freshness", freshness);
    }

    /** The five facts, in the entry's order. */
    public List<String> facts() {
        return List.of(source, rule, date, currencySemantics, freshness);
    }

    private static void stated(String fact, String value) {
        Objects.requireNonNull(value, fact);
        String plain = value.strip();
        if (plain.length() < LEAST || PLACEHOLDERS.contains(plain.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("a report states its " + fact + ": at least " + LEAST
                    + " characters, and no placeholder");
        }
    }
}
