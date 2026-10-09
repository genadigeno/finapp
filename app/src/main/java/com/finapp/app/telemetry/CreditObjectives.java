package com.finapp.app.telemetry;

import com.finapp.credit.CreditProduct;
import java.time.Duration;
import java.util.Arrays;

/**
 * The objectives Phase 10's credit alerts are drawn against (`P10-TSK-020`; PHASE_10_PLAN.md section 15) - declared once,
 * here, so the timer's buckets, the alert rules and the guard reading both cannot drift apart
 * ({@code AlertRulesResolveTest#theCreditRulesResolve} holds each rule's threshold equal to these).
 *
 * <ul>
 *   <li><strong>The platform's decision latency</strong>, p99 of decisions the platform made, from submission: within
 *       45 minutes - the default collection window (30 minutes, {@code finapp.credit.bureau.collection-window}) a request
 *       may wait out for an unavailable source, plus a quarter hour for the platform's own steps. A source that never
 *       answers therefore cannot breach it on its own (the unavailability ratio is that alert); a stalled progress sweep
 *       can. A person's decision is not held to it: a referral waits for an underwriter, which is the review objective.
 *   <li><strong>The review objective</strong>: no {@code OPEN} review case waits longer than a day - a referral queue
 *       nobody works is a silent decline, and a day leaves six of the request's seven days of validity to act.
 *   <li><strong>The request validity</strong>: the longest any offered product's request may stay open
 *       ({@link CreditProduct#requestValidity()}, seven days for both today). A request older than that and still open
 *       outside {@code IN_REVIEW} means the expiry is not being taken.
 * </ul>
 */
public final class CreditObjectives {

    /** p99 of the platform's own decisions, from submission. */
    public static final Duration DECISION_LATENCY_P99 = Duration.ofMinutes(45);

    /** The oldest {@code OPEN} review case's wait. */
    public static final Duration REVIEW_AGE = Duration.ofDays(1);

    /** The latency timer's buckets - the objective among them, so the alert reads its own boundary exactly. */
    public static Duration[] latencyBuckets() {
        return new Duration[] {
            Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(5),
            Duration.ofMinutes(15), Duration.ofMinutes(30), DECISION_LATENCY_P99, Duration.ofHours(1), Duration.ofHours(4)
        };
    }

    private CreditObjectives() {}

    /** The longest request validity of any offered product. */
    public static Duration requestValidity() {
        return Arrays.stream(CreditProduct.values())
                .map(CreditProduct::requestValidity)
                .max(Duration::compareTo)
                .orElseThrow();
    }
}
