package com.finapp.app.telemetry;

import com.finapp.platform.testing.RepositoryPaths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.FileSystemResource;

/**
 * The risk storm's reading of {@code infra/prometheus/rules/risk.yml} at rest (`P13-TST-001`; `P13-TSK-024` asked that the
 * storm read the rules after every round) - a public bridge over this package's {@link RuleEvaluator}: every rule's
 * condition evaluated over two scrapes of an instance's real registry (the round's start and its rest), the target's
 * labels attached as the committed scrape configuration attaches them. The rule's {@code for} is not modelled at rest - a
 * condition true at rest is reported, which is the stricter reading for a quiet system.
 */
public final class RiskStormRules {

    private static final String RULES = "infra/prometheus/rules/risk.yml";
    private static final String GROUP = "risk";

    private final Map<String, String> rules;

    private RiskStormRules(Map<String, String> rules) {
        this.rules = rules;
    }

    /** Every rule of the committed risk group, by alert name. */
    public static RiskStormRules load() {
        Map<String, String> rules = new LinkedHashMap<>();
        for (Map<String, Object> rule : ruleList()) {
            rules.put(String.valueOf(rule.get("alert")), String.valueOf(rule.get("expr")).strip());
        }
        return new RiskStormRules(rules);
    }

    /** The number of rules read. */
    public int size() {
        return rules.size();
    }

    /**
     * The alerts whose condition holds on {@code after} ({@code before} the scrape a window earlier), each with the label
     * set it fires for - {@code "RiskEvaluationUnavailable{operation=TRANSFER}"}.
     */
    public Set<String> firing(String before, String after, Map<String, String> target) {
        List<RuleEvaluator.Sample> earlier = RuleEvaluator.samples(before, target);
        List<RuleEvaluator.Sample> later = RuleEvaluator.samples(after, target);
        Set<String> firing = new TreeSet<>();
        rules.forEach((alert, expr) -> {
            for (Map<String, String> labels : RuleEvaluator.series(expr, earlier, later)) {
                Map<String, String> named = new LinkedHashMap<>(labels);
                target.keySet().forEach(named::remove);
                firing.add(alert + named);
            }
        });
        return firing;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> ruleList() {
        YamlMapFactoryBean factory = new YamlMapFactoryBean();
        factory.setResources(new FileSystemResource(RepositoryPaths.locate(RULES)));
        Map<String, Object> document = factory.getObject();
        List<Map<String, Object>> found = new ArrayList<>();
        for (Object group : (List<Object>) document.get("groups")) {
            Map<String, Object> named = (Map<String, Object>) group;
            if (GROUP.equals(named.get("name"))) {
                for (Object rule : (List<Object>) named.get("rules")) {
                    found.add((Map<String, Object>) rule);
                }
            }
        }
        if (found.isEmpty()) {
            throw new IllegalStateException("no rules group named " + GROUP + " in " + RULES);
        }
        return found;
    }
}
