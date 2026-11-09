package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.RepositoryPaths;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.ByteArrayResource;

/**
 * The disagreement alert waits longer than any rollout an overlay allows, read from the manifests (`P15-TSK-031`, ADR-0125 decision
 * 4). A rollout's step is at most {@code progressDeadlineSeconds}, since Kubernetes calls a longer one failed; the fast path of
 * {@code startupProbe} and {@code minReadySeconds} is lower, and asserted to be, so a raised startup budget cannot pass the deadline
 * unnoticed. Whether the rule fires, through gaps and NaN, is {@code promtool}'s: {@code infra/prometheus/tests/config-fingerprint.test.yml}.
 */
@DisplayName("ConfigFingerprintDisagrees outlasts the longest rollout any overlay allows (P15-TSK-031)")
class ConfigFingerprintAlertTest {

    private static final String RULES = "infra/prometheus/rules/platform.yml";
    private static final String DEPLOYMENT = "deploy/base/deployment.yaml";
    private static final String HPA = "deploy/base/horizontalpodautoscaler.yaml";
    private static final List<String> OVERLAYS = List.of("kind", "staging", "production");
    private static final Pattern PRESENCE_WINDOW = Pattern.compile("present_over_time\\(finapp_config_fingerprint\\[(\\d+)m]\\)");
    private static final Pattern PATCHED_MAX_REPLICAS = Pattern.compile("path: /spec/maxReplicas\\s+value: (\\d+)");

    @Test
    @DisplayName("the for exceeds maxReplicas x the longest step + the last pod's grace + the presence window, per overlay, every number read")
    void theForOutlastsTheLongestRollout() {
        Duration waited = duration(String.valueOf(rule("ConfigFingerprintDisagrees").get("for")));
        for (String overlay : OVERLAYS) {
            assertThat(waited).as("%s: a rollout's expected disagreement must not page", overlay).isGreaterThan(longestRollout(overlay));
        }
        assertThat(longestRollout("production").toMinutes()).as("not vacuous: seven replicas of a ten-minute step").isGreaterThanOrEqualTo(70);
    }

    @ParameterizedTest
    @DisplayName("each overlay's rollout is bounded by the numbers read for it, and no overlay moves one of them behind this test's back")
    @ValueSource(strings = {"kind", "staging", "production"})
    void noOverlayPatchesARolloutBound(String overlay) {
        String text = RepositoryPaths.read("deploy/overlays/" + overlay + "/kustomization.yaml");
        assertThat(text).doesNotContain("/spec/progressDeadlineSeconds").doesNotContain("/spec/minReadySeconds")
                .doesNotContain("terminationGracePeriodSeconds").doesNotContain("startupProbe");
    }

    @Test
    @DisplayName("the slowest step the probes allow is below the deadline, so the deadline really is the bound")
    void theDeadlineIsTheLongerBound() {
        assertThat(seconds("progressDeadlineSeconds")).isGreaterThan(startupBudget() + seconds("minReadySeconds"));
    }

    @Test
    @DisplayName("the rule counts distinct hashes per environment over a presence window, pages, and its absent partner tickets")
    void theRulesAreAsWritten() {
        Map<String, Object> rule = rule("ConfigFingerprintDisagrees");
        assertThat(String.valueOf(rule.get("expr")).strip())
                .isEqualTo("count(count by (env, fingerprint) (present_over_time(finapp_config_fingerprint[5m]))) by (env) > 1");
        assertThat(labels(rule)).containsEntry("severity", "page");
        Map<String, Object> absent = rule("ConfigFingerprintAbsent");
        assertThat(String.valueOf(absent.get("expr")).strip()).isEqualTo("absent(finapp_config_fingerprint)");
        assertThat(labels(absent)).containsEntry("severity", "ticket");
    }

    private static Duration longestRollout(String overlay) {
        long maxReplicas = maxReplicas(overlay);
        long step = Math.max(seconds("progressDeadlineSeconds"), startupBudget() + seconds("minReadySeconds"));
        return Duration.ofSeconds(maxReplicas * step + seconds("terminationGracePeriodSeconds") + presenceWindow().toSeconds());
    }

    private static Duration presenceWindow() {
        Matcher window = PRESENCE_WINDOW.matcher(String.valueOf(rule("ConfigFingerprintDisagrees").get("expr")));
        assertThat(window.find()).as("the rule's presence window").isTrue();
        return Duration.ofMinutes(Long.parseLong(window.group(1)));
    }

    private static long maxReplicas(String overlay) {
        Matcher patched = PATCHED_MAX_REPLICAS.matcher(RepositoryPaths.read("deploy/overlays/" + overlay + "/kustomization.yaml"));
        if (patched.find()) {
            return Long.parseLong(patched.group(1));
        }
        return number(RepositoryPaths.read(HPA), "maxReplicas");
    }

    private static long seconds(String field) {
        return number(RepositoryPaths.read(DEPLOYMENT), field);
    }

    /** A startupProbe's periodSeconds x failureThreshold: the first two numbers after its heading. */
    private static long startupBudget() {
        String text = RepositoryPaths.read(DEPLOYMENT);
        int probe = text.indexOf("startupProbe:");
        assertThat(probe).as("the startup probe is in the Deployment").isNotNegative();
        String tail = text.substring(probe);
        return number(tail, "periodSeconds") * number(tail, "failureThreshold");
    }

    private static long number(String text, String field) {
        Matcher found = Pattern.compile("(?m)^\\s*" + field + ":\\s*(\\d+)\\s*$").matcher(text);
        assertThat(found.find()).as("%s is declared", field).isTrue();
        return Long.parseLong(found.group(1));
    }

    private static Duration duration(String prometheus) {
        Matcher found = Pattern.compile("^(\\d+)([smhd])$").matcher(prometheus);
        assertThat(found.matches()).as("a Prometheus duration: %s", prometheus).isTrue();
        long amount = Long.parseLong(found.group(1));
        return switch (found.group(2)) {
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            default -> Duration.ofDays(amount);
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> labels(Map<String, Object> rule) {
        return (Map<String, Object>) rule.get("labels");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rule(String alert) {
        YamlMapFactoryBean factory = new YamlMapFactoryBean();
        factory.setResources(new ByteArrayResource(RepositoryPaths.read(RULES).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        for (Object group : (List<Object>) factory.getObject().get("groups")) {
            for (Object entry : (List<Object>) ((Map<String, Object>) group).get("rules")) {
                Map<String, Object> rule = (Map<String, Object>) entry;
                if (alert.equals(rule.get("alert"))) {
                    return rule;
                }
            }
        }
        throw new AssertionError("no rule named " + alert);
    }
}
