package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A namespace's worst moment outside {@code finapp}, counted from its committed documents the way {@code finapp.rego}'s
 * {@code worst_moment} counts it (`P16-TSK-004`; {@code THREAT_MODEL.md} section 6 item 43 (c)): a second statement of the
 * arithmetic, so a quota held equal by the policy in CI is held equal on every build too.
 *
 * <p>Per Deployment of R replicas: a {@code Recreate} rollout holds R pods; a {@code RollingUpdate} holds old and new together, at
 * most 2R, and with {@code minReadySeconds} at least the grace no more than {@code R + 2 x maxSurge + maxUnavailable}. Outside the
 * rollout, the {@code maxUnavailable} of the PodDisruptionBudget selecting its pods, or R where none does. A pod inside its grace is
 * still charged, which is why the terminating pods count at all.
 */
final class WorstMoment {

    static final List<String> KEYS = List.of("pods", "requests.cpu", "requests.memory", "limits.cpu", "limits.memory");

    private WorstMoment() {}

    /** The five keys a quota states, each the namespace's worst moment, from every Deployment and budget among the documents. */
    static Map<String, BigDecimal> of(List<Map<String, Object>> documents) {
        assertThat(ofKind(documents, "HorizontalPodAutoscaler")).as("no autoscaler here: R is the replicas").isEmpty();
        Map<String, BigDecimal> needed = new LinkedHashMap<>();
        KEYS.forEach(key -> needed.put(key, BigDecimal.ZERO));
        List<Map<String, Object>> deployments = ofKind(documents, "Deployment");
        assertThat(deployments).as("the workloads were read").isNotEmpty();
        for (Map<String, Object> deployment : deployments) {
            BigDecimal peak = BigDecimal.valueOf(peak(deployment, ofKind(documents, "PodDisruptionBudget")));
            needed.merge("pods", peak, BigDecimal::add);
            Map<String, Object> pod = map(map(map(deployment, "spec"), "template"), "spec");
            assertThat(pod).as("no init container: a pod's share is its containers' sum").doesNotContainKey("initContainers");
            for (Map<String, Object> container : list(pod, "containers")) {
                for (String bound : List.of("requests", "limits")) {
                    for (String resource : List.of("cpu", "memory")) {
                        Object value = map(map(container, "resources"), bound).get(resource);
                        needed.merge(bound + "." + resource, quantity(value).multiply(peak), BigDecimal::add);
                    }
                }
            }
        }
        return needed;
    }

    static int peak(Map<String, Object> deployment, List<Map<String, Object>> budgets) {
        Map<String, Object> spec = map(deployment, "spec");
        int replicas = (Integer) spec.getOrDefault("replicas", 1);
        Map<String, Object> strategy = map(spec, "strategy");
        int rollout;
        if ("Recreate".equals(strategy.get("type"))) {
            rollout = replicas;
        } else {
            assertThat(strategy.get("type")).as("a stated strategy").isEqualTo("RollingUpdate");
            Map<String, Object> rolling = map(strategy, "rollingUpdate");
            int surge = (Integer) rolling.get("maxSurge");
            int unavailable = (Integer) rolling.get("maxUnavailable");
            int ready = (Integer) spec.getOrDefault("minReadySeconds", 0);
            int grace = (Integer) map(map(spec, "template"), "spec").getOrDefault("terminationGracePeriodSeconds", 30);
            rollout = ready >= grace ? Math.min(2 * replicas, replicas + 2 * surge + unavailable) : 2 * replicas;
        }
        Map<String, Object> labels = map(map(map(spec, "template"), "metadata"), "labels");
        int outside = budgets.stream()
                .filter(budget -> labels.entrySet().containsAll(map(map(map(budget, "spec"), "selector"), "matchLabels").entrySet()))
                .mapToInt(budget -> (Integer) map(budget, "spec").get("maxUnavailable"))
                .max()
                .orElse(replicas);
        return rollout + outside;
    }

    static BigDecimal quantity(Object value) {
        String text = String.valueOf(value);
        if (text.endsWith("Gi")) {
            return new BigDecimal(text.substring(0, text.length() - 2)).multiply(BigDecimal.valueOf(1024L * 1024L * 1024L));
        }
        if (text.endsWith("Mi")) {
            return new BigDecimal(text.substring(0, text.length() - 2)).multiply(BigDecimal.valueOf(1024L * 1024L));
        }
        if (text.endsWith("m")) {
            return new BigDecimal(text.substring(0, text.length() - 1)).movePointLeft(3);
        }
        return new BigDecimal(text);
    }

    /** The quota's five keys as quantities, its spec holding nothing but them: no scope, no selector. */
    static Map<String, BigDecimal> stated(Map<String, Object> quota) {
        assertThat(map(quota, "spec")).as("a scope leaves some pods unmeasured").containsOnlyKeys("hard");
        Map<String, Object> hard = map(map(quota, "spec"), "hard");
        assertThat(hard).containsOnlyKeys(KEYS.toArray(String[]::new));
        Map<String, BigDecimal> stated = new LinkedHashMap<>();
        KEYS.forEach(key -> stated.put(key, quantity(hard.get(key))));
        return stated;
    }

    static List<Map<String, Object>> ofKind(List<Map<String, Object>> documents, String kind) {
        return documents.stream().filter(doc -> kind.equals(doc.get("kind"))).toList();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Map<String, Object> from, String key) {
        assertThat(from).as("key %s", key).containsKey(key);
        assertThat(from.get(key)).as("%s is a map", key).isInstanceOf(Map.class);
        return (Map<String, Object>) from.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> from, String key) {
        assertThat(from.get(key)).as("%s is a list", key).isInstanceOf(List.class);
        return (List<Map<String, Object>>) from.get(key);
    }
}
