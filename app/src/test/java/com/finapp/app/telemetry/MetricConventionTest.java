package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.metrics.MetricNames;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Every meter this platform publishes obeys the naming and tagging convention.
 *
 * <p>{@code P0-TSK-029} exists because "metric naming decided late becomes inconsistent across
 * contexts". A convention written in a document repeats that failure one level up: the second
 * module reads it, the fifth does not, and by Phase 8 there are several vocabularies and no
 * dashboard that can be written once. This is the convention as a build failure.
 *
 * <p>It runs against the <strong>live registry</strong> rather than a list of expected names, so a
 * meter registered by a module that does not exist yet is covered without anyone remembering to
 * extend it — the same reasoning as {@code CorrelationSinkCoverageTest} and {@code
 * ProductionModules}.
 *
 * <h2>The tag rule is a security rule</h2>
 *
 * <p>A tag value a request can influence is two failures at once. It multiplies one time series
 * into as many as there are distinct values, until the metrics backend falls over and takes the
 * ability to observe the incident with it. And it is a disclosure into a system with different
 * access control and months of retention — worse than the same value in a log, which at least
 * rotates ({@code INV-AUD-02}). Correlation belongs on a trace and in a log; a metric answers how
 * many, how long and how often, never which one.
 */
@org.junit.jupiter.api.Tag("slice") // qualified: io.micrometer.core.instrument.Tag is imported here
@SpringBootTest(properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/absent")
class MetricConventionTest {

    @Autowired private MeterRegistry registry;

    @Test
    @DisplayName("every meter the platform publishes is named finapp.<module>.<noun>")
    void namesFollowTheConvention() {
        List<String> ours = ourMeterNames();
        assertThat(ours).as("the platform must publish some meters, or this guard checks nothing").isNotEmpty();

        assertThat(ours.stream().filter(name -> !MetricNames.isWellFormed(name)).toList())
                .as(
                        """
                        A meter does not follow the convention in MetricNames: \
                        finapp.<module>.<noun>[.<noun>], lower case, dot separated, at least two \
                        segments after the prefix so the owning module is always named.""")
                .isEmpty();
    }

    @Test
    @DisplayName("no meter carries a tag whose value a request could influence")
    void tagsCannotCarryUnboundedValues() {
        for (Meter meter : registry.getMeters()) {
            String name = meter.getId().getName();
            if (!MetricNames.isOurs(name)) {
                continue;
            }
            for (Tag tag : meter.getId().getTags()) {
                String key = tag.getKey().toLowerCase(Locale.ROOT);

                assertThat(MetricNames.ALLOWED_TAG_KEYS)
                        .as(
                                "meter '%s' carries tag '%s', which is not in the closed set. Adding a tag "
                                        + "key is a decision about cardinality and disclosure - make it in "
                                        + "MetricNames, deliberately",
                                name, key)
                        .contains(key);

                assertThat(MetricNames.FORBIDDEN_TAG_KEY_FRAGMENTS)
                        .as("meter '%s' carries tag '%s', whose name implies an unbounded value", name, key)
                        .noneSatisfy(fragment -> assertThat(key).contains(fragment));
            }
        }
    }

    @Test
    @DisplayName("the outbox backlog is published, so the recorded debt is actually paid")
    void theOutboxBacklogIsPublished() {
        // ADR-0005 names outbox depth and age as first-class monitored metrics, and P0-TSK-020
        // recorded their absence as debt. Asserting the meters exist is what stops this task
        // closing that debt on paper.
        assertThat(ourMeterNames())
                .contains(OutboxMetrics.PENDING, OutboxMetrics.OLDEST);
    }

    @Test
    @DisplayName("the guard is not vacuous: it can see the registry and reject a bad name")
    void theGuardWorks() {
        assertThat(registry.getMeters()).as("a real registry with real meters").isNotEmpty();

        // The convention itself, checked against the shapes it exists to refuse - so a change to
        // the pattern that accidentally admits everything fails here rather than silently.
        assertThat(MetricNames.isWellFormed("finapp.outbox.pending")).isTrue();
        assertThat(MetricNames.isWellFormed("finapp.errors")).as("one segment names no owner").isFalse();
        assertThat(MetricNames.isWellFormed("finapp.outbox.pendingEvents")).as("camel case").isFalse();
        assertThat(MetricNames.isWellFormed("outbox.pending")).as("no prefix").isFalse();
        assertThat(MetricNames.isWellFormed("finapp_outbox_pending"))
                .as("the Prometheus form would bake one backend into the name")
                .isFalse();
    }

    private List<String> ourMeterNames() {
        return registry.getMeters().stream()
                .map(meter -> meter.getId().getName())
                .filter(MetricNames::isOurs)
                .distinct()
                .sorted()
                .toList();
    }
}
