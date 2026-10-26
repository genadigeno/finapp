package com.finapp.app.telemetry;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.LongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An alert rule run through TIME (`P13-TSK-024`, the review's IMPORTANT 1): {@link RuleEvaluator} answers whether an
 * expression holds over two scrapes; this steps a series of scrapes at Prometheus' cadence - a scrape every
 * {@link #SCRAPE}, the rule evaluated every {@link #EVALUATION} - and applies the rule's {@code for} as Prometheus does:
 * the alert is pending from the first evaluation its expression holds, FIRES at an evaluation where it has held for at
 * least {@code for}, and is reset by any evaluation where it does not. A range function's window is the rule's own
 * ({@code [15m]}), read against the scrape that window ago - so a window shorter than the gap between two of a series'
 * increments shows the gap, which is exactly what a two-scrape check cannot see.
 *
 * <p>The scrapes are the test's: {@code scrapeAt} answers the samples a scrape at a given second would read.
 */
final class RuleTimeline {

    /** Prometheus' scrape interval the timeline models. */
    static final Duration SCRAPE = Duration.ofSeconds(15);

    /** The rule evaluation interval the timeline models. */
    static final Duration EVALUATION = Duration.ofSeconds(30);

    private static final Pattern WINDOW = Pattern.compile("\\[(\\d+)([smhd])\\]");

    private final String expr;
    private final long forSeconds;
    private final long windowSeconds;
    private final LongFunction<List<RuleEvaluator.Sample>> scrapeAt;

    private RuleTimeline(String expr, Duration wait, LongFunction<List<RuleEvaluator.Sample>> scrapeAt) {
        this.expr = expr;
        this.forSeconds = wait.toSeconds();
        this.windowSeconds = window(expr);
        this.scrapeAt = scrapeAt;
    }

    /** The rule {@code expr} with its {@code for} of {@code wait}, over the scrapes {@code scrapeAt} answers. */
    static RuleTimeline of(String expr, Duration wait, LongFunction<List<RuleEvaluator.Sample>> scrapeAt) {
        return new RuleTimeline(expr, wait, scrapeAt);
    }

    /** {@code text} ("15m", "1h", "1d") as a duration - a rule's {@code for}. */
    static Duration duration(String text) {
        Matcher unit = Pattern.compile("(\\d+)([smhd])").matcher(text);
        if (!unit.matches()) {
            throw new IllegalArgumentException("not a duration: " + text);
        }
        return Duration.ofSeconds(Long.parseLong(unit.group(1)) * seconds(unit.group(2)));
    }

    /**
     * The evaluations, from 0 to {@code until} seconds, at which the alert is FIRING - for any of its series, each series
     * (label set) pending and firing on its own clock, as Prometheus keys an alert by its labels.
     */
    TreeSet<Long> firing(long until) {
        TreeSet<Long> firing = new TreeSet<>();
        Map<Map<String, String>, Long> pendingSince = new HashMap<>();
        for (long now = 0; now <= until; now += EVALUATION.toSeconds()) {
            Set<Map<String, String>> holding = RuleEvaluator.series(expr, scrape(Math.max(0, now - windowSeconds)),
                    scrape(now));
            pendingSince.keySet().retainAll(holding);
            for (Map<String, String> series : holding) {
                pendingSince.putIfAbsent(series, now);
                if (now - pendingSince.get(series) >= forSeconds) {
                    firing.add(now);
                }
            }
        }
        return firing;
    }

    /** The first evaluation at which the alert fires, within {@code until} seconds. */
    Optional<Long> firstFiring(long until) {
        TreeSet<Long> firing = firing(until);
        return firing.isEmpty() ? Optional.empty() : Optional.of(firing.first());
    }

    /** The samples of the last scrape at or before {@code second}. */
    private List<RuleEvaluator.Sample> scrape(long second) {
        return scrapeAt.apply(second - second % SCRAPE.toSeconds());
    }

    private static long window(String expr) {
        Matcher window = WINDOW.matcher(expr);
        Long found = null;
        while (window.find()) {
            long seconds = Long.parseLong(window.group(1)) * seconds(window.group(2));
            if (found != null && found != seconds) {
                throw new IllegalArgumentException("one window per rule is modelled: " + expr);
            }
            found = seconds;
        }
        return found == null ? 0 : found;
    }

    private static long seconds(String unit) {
        return switch (unit) {
            case "s" -> 1;
            case "m" -> 60;
            case "h" -> 3600;
            case "d" -> 86_400;
            default -> throw new IllegalArgumentException(unit);
        };
    }
}
