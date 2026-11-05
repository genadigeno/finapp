package com.finapp.app.accounting;

import com.finapp.accounting.CloseCheckCode;
import com.finapp.accounting.CloseCheckStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The close run's meters (`P14-TSK-011`; {@code PHASE_14_PLAN.md} sections 8.4, 16): {@code
 * finapp.accounting.close.check}{check, outcome} - one count per attempt this instance concluded ({@code passed},
 * {@code failed}, {@code disclosed}), gave back ({@code deferred}) or found overtaken ({@code overtaken}), after the
 * commit; {@code finapp.accounting.close.duration} - a run's start to its checks' first full conclusion, once per run
 * across instances; {@code finapp.accounting.close.leg.failed}{leg} - a schedule's leg that failed, the plan's "every
 * leg meters its claim failures". Tags closed in the code - never a period, an id or a figure. Registered eagerly, so a
 * dashboard sees a zero rather than an absence.
 */
public final class CloseRunMetrics {

    /** Spelled with dots - exported as {@code finapp_accounting_close_check_total}. */
    public static final String CHECK = "finapp.accounting.close.check";

    /** Spelled with dots - exported as {@code finapp_accounting_close_duration_seconds}. */
    public static final String DURATION = "finapp.accounting.close.duration";

    /** Spelled with dots - exported as {@code finapp_accounting_close_leg_failed_total}. */
    public static final String LEG_FAILED = "finapp.accounting.close.leg.failed";

    /** The {@code outcome} tag's values. */
    public static final List<String> OUTCOMES = List.of("passed", "failed", "disclosed", "deferred", "overtaken");

    /** The {@code leg} tag's values. */
    public static final List<String> LEGS = List.of("open", "checks", "orphans", "inquiry");

    private final MeterRegistry registry;
    private final Timer duration;

    public CloseRunMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        for (CloseCheckCode check : CloseCheckCode.values()) {
            for (String outcome : OUTCOMES) {
                check(check, outcome);
            }
        }
        for (String leg : LEGS) {
            leg(leg);
        }
        this.duration = Timer.builder(DURATION)
                .description("A close run's start to its checks' first full conclusion")
                .register(registry);
    }

    /** An attempt this instance concluded. */
    public void concluded(CloseCheckCode check, CloseCheckStatus status) {
        check(check, status.name().toLowerCase(Locale.ROOT)).increment();
    }

    /** An attempt this instance gave back - its judge could not answer yet. */
    public void deferred(CloseCheckCode check) {
        check(check, "deferred").increment();
    }

    /** A conclusion the fence refused - a slower worker's. */
    public void overtaken(CloseCheckCode check) {
        check(check, "overtaken").increment();
    }

    /** A run's checks first all concluded. */
    public void tookFor(Duration took) {
        duration.record(took);
    }

    /** A leg failed, from its claim on. */
    public void legFailed(String leg) {
        leg(leg).increment();
    }

    private Counter check(CloseCheckCode check, String outcome) {
        return Counter.builder(CHECK)
                .description("Close check attempts, by check and outcome (passed, failed, disclosed, deferred, overtaken)")
                .tag("check", check.name())
                .tag("outcome", outcome)
                .register(registry);
    }

    private Counter leg(String leg) {
        return Counter.builder(LEG_FAILED)
                .description("The close schedule's legs that failed, by leg (open, checks, orphans, inquiry)")
                .tag("leg", leg)
                .register(registry);
    }
}
