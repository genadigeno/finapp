package com.finapp.accounting;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A GL mapping version judged against the ledger's chart (`P14-TSK-003`; ADR-0113 decision 3; {@code INV-ACC-06},
 * {@code -07}) - pure, so the activation, the reviewer's diff and the hermetic suites read one judgement:
 *
 * <ul>
 *   <li><strong>Total</strong>: every (purpose, owner kind) of the chart has a rule - else {@link #unmapped()} names it,
 *       and a journal line of that purpose would map to no GL account.
 *   <li><strong>Compatible</strong>: no rule names a (purpose, owner kind) the chart does not hold
 *       ({@link #unknown()}), and every mapped purpose holds exactly one account type, which is the GL account's
 *       classification by name ({@link #incompatible()}) - an asset never presented as a liability, a purpose with two
 *       types or none never judged compatible.
 * </ul>
 */
public record MappingJudgement(List<String> unmapped, List<String> unknown, List<String> incompatible) {

    public MappingJudgement {
        unmapped = List.copyOf(unmapped);
        unknown = List.copyOf(unknown);
        incompatible = List.copyOf(incompatible);
    }

    /** {@code version} judged against {@code chart}. Deterministic: each list in key order. */
    public static MappingJudgement judge(GlMappingVersion version, LedgerChart.Chart chart) {
        Map<String, LedgerChart.ChartPurpose> held = new TreeMap<>();
        chart.purposes().forEach(purpose -> held.put(purpose.key(), purpose));
        Map<String, MappingRule> rules = new TreeMap<>(version.rulesByKey());
        List<String> unmapped = new ArrayList<>();
        for (String key : held.keySet()) {
            if (!rules.containsKey(key)) {
                unmapped.add(key);
            }
        }
        List<String> unknown = new ArrayList<>();
        List<String> incompatible = new ArrayList<>();
        for (MappingRule rule : rules.values()) {
            LedgerChart.ChartPurpose purpose = held.get(rule.key());
            if (purpose == null) {
                unknown.add(rule.key());
                continue;
            }
            GlClassification classification = version.account(rule.glAccountCode())
                    .orElseThrow(() -> new IllegalStateException("a coherent version holds every rule's account"))
                    .classification();
            Set<String> types = purpose.types();
            if (types.size() != 1 || !types.contains(classification.name())) {
                incompatible.add(rule.key() + " is " + (types.isEmpty() ? "of no known type" : String.join("+",
                        new java.util.TreeSet<>(types))) + ", mapped to " + rule.glAccountCode() + " ("
                        + classification.name() + ")");
            }
        }
        return new MappingJudgement(unmapped, unknown, incompatible);
    }

    /** Every purpose of the chart is mapped. */
    public boolean total() {
        return unmapped.isEmpty();
    }

    /** Every rule names a purpose the chart holds, of the GL account's classification. */
    public boolean compatible() {
        return unknown.isEmpty() && incompatible.isEmpty();
    }
}
