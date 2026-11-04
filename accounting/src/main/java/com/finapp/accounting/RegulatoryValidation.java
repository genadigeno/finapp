package com.finapp.accounting;

import com.finapp.accounting.reporting.CanonicalJson;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A return's validation (`P14-TSK-018`; ADR-0118 decision 1): each rule of the closed vocabulary judged over the form's lines,
 * per currency and never across currencies, on the exact amounts. A pure function of the lines and the configuration, both
 * frozen, so a repeat gives the same answer: a failed validation is recorded once and the way on is a new draft.
 *
 * <p><strong>Never a zero for what is not there.</strong> A rule naming a line with no amount in a currency another of its lines
 * has is a finding ({@code MISSING:<line>}), and fails: nobody issued that zero.
 *
 * @param passed every rule passed in every currency it reached
 * @param findings one per rule and currency, in rule then currency order
 */
public record RegulatoryValidation(boolean passed, List<Finding> findings) {

    /** One rule's judgement in one currency; {@code detail} names codes, never an amount. */
    public record Finding(String rule, RegulatoryRuleType type, String currency, boolean passed, List<String> detail) {

        public Finding {
            Objects.requireNonNull(rule, "rule");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(currency, "currency");
            detail = List.copyOf(detail);
        }
    }

    public RegulatoryValidation {
        findings = List.copyOf(findings);
    }

    /** {@code configuration}'s rules over {@code lines}. */
    public static RegulatoryValidation of(RegulatoryObligationConfiguration configuration, List<RegulatoryFormLine> lines) {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(lines, "lines");
        TreeMap<String, TreeMap<String, BigDecimal>> byLine = new TreeMap<>();
        for (RegulatoryFormLine line : lines) {
            byLine.computeIfAbsent(line.code(), ignored -> new TreeMap<>()).put(line.currency(), line.amount());
        }
        List<Finding> findings = new ArrayList<>();
        for (RegulatoryObligationConfiguration.Rule rule : configuration.rules()) {
            TreeSet<String> currencies = new TreeSet<>();
            for (List<String> side : List.of(rule.left(), rule.right())) {
                for (String code : side) {
                    currencies.addAll(byLine.getOrDefault(code, new TreeMap<>()).keySet());
                }
            }
            if (currencies.isEmpty()) {
                findings.add(new Finding(rule.code(), rule.type(), "NONE", false, List.of("NO_LINE_HOLDS_AN_AMOUNT")));
            }
            for (String currency : currencies) {
                findings.add(judged(rule, currency, byLine));
            }
        }
        return new RegulatoryValidation(findings.stream().allMatch(Finding::passed), findings);
    }

    private static Finding judged(RegulatoryObligationConfiguration.Rule rule, String currency, TreeMap<String, TreeMap<String, BigDecimal>> byLine) {
        List<String> detail = new ArrayList<>();
        BigDecimal left = sum(rule.left(), currency, byLine, detail);
        BigDecimal right = sum(rule.right(), currency, byLine, detail);
        if (!detail.isEmpty()) {
            return new Finding(rule.code(), rule.type(), currency, false, detail);
        }
        boolean passed = switch (rule.type()) {
            case NON_NEGATIVE -> rule.left().stream().allMatch(code -> byLine.get(code).get(currency).signum() >= 0);
            case EQUALS -> left.compareTo(right) == 0;
            case NOT_LESS_THAN -> left.compareTo(right) >= 0;
        };
        return new Finding(rule.code(), rule.type(), currency, passed, passed ? List.of() : List.of(rule.type().name() + "_FAILED"));
    }

    private static BigDecimal sum(List<String> codes, String currency, TreeMap<String, TreeMap<String, BigDecimal>> byLine, List<String> detail) {
        BigDecimal sum = BigDecimal.ZERO;
        for (String code : codes) {
            BigDecimal amount = byLine.getOrDefault(code, new TreeMap<>()).get(currency);
            if (amount == null) {
                detail.add("MISSING:" + code);
            } else {
                sum = sum.add(amount);
            }
        }
        return sum;
    }

    /** The canonical text stored and served - rules, currencies and codes; never an amount. */
    public String canonical() {
        CanonicalJson.Arr rows = CanonicalJson.array();
        for (Finding finding : findings) {
            CanonicalJson.Arr detail = CanonicalJson.array();
            finding.detail().forEach(detail::add);
            rows.add(CanonicalJson.object()
                    .put("rule", finding.rule())
                    .put("type", finding.type().name())
                    .put("currency", finding.currency())
                    .put("passed", finding.passed())
                    .put("detail", detail));
        }
        return CanonicalJson.text(CanonicalJson.object().put("passed", passed).put("findings", rows));
    }

    /**
     * The outcome a row stores, read back as written - never recomputed, so a reader sees what was recorded.
     *
     * @throws RunContentParser.NotCanonical when it is not what {@link #canonical} wrote
     */
    public static RegulatoryValidation parse(String text) {
        CanonicalJson.Obj root = RunContentParser.object(Objects.requireNonNull(text, "text"));
        List<Finding> findings = new ArrayList<>();
        for (CanonicalJson.Value element : ((CanonicalJson.Arr) root.members().get("findings")).elements()) {
            CanonicalJson.Obj finding = (CanonicalJson.Obj) element;
            List<String> detail = new ArrayList<>();
            for (CanonicalJson.Value code : ((CanonicalJson.Arr) finding.members().get("detail")).elements()) {
                detail.add(((CanonicalJson.Text) code).value());
            }
            findings.add(new Finding(((CanonicalJson.Text) finding.members().get("rule")).value(), RegulatoryRuleType.valueOf(((CanonicalJson.Text) finding.members().get("type")).value()),
                    ((CanonicalJson.Text) finding.members().get("currency")).value(), finding.members().get("passed") == CanonicalJson.Flag.TRUE, detail));
        }
        return new RegulatoryValidation(root.members().get("passed") == CanonicalJson.Flag.TRUE, findings);
    }
}
