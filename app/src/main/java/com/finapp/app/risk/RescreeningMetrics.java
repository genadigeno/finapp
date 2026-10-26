package com.finapp.app.risk;

import com.finapp.risk.RescreeningObserver;
import com.finapp.risk.RescreeningPermitStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * The rescreening leg's meters (`P13-TSK-022`; {@code PHASE_13_PLAN.md} section 15; the Phase 12 → 13 gate's N5), every
 * series registered eagerly and every tag a closed vocabulary - never a party, a list entry, a screening or a name
 * ({@code INV-RSK-08}, {@code MetricNames.ALLOWED_TAG_KEYS}):
 *
 * <ul>
 *   <li>{@code finapp.risk.rescreening{outcome}} - what became of each claimed permit: {@code cleared}, {@code hit}
 *       (always alerted since the review - a fact alert, `hit_not_alerted` retired), {@code skipped}, {@code deferred},
 *       {@code failed} - the exposure: alert on any {@code failed} increase (`-024` writes the rules);</li>
 *   <li>{@code finapp.risk.rescreening.backlog} - the permits {@code PENDING}, counted up to a million (saturating); NaN
 *       when unreadable. Read from the database behind a 15 s floor, so every instance reports the same figure: aggregate
 *       with {@code max()};</li>
 *   <li>{@code finapp.risk.sweeper.claim.failed{kind=rescreening}} - an enqueue batch, a claim or a conclusion that failed
 *       and rolled back; a later tick claims again.</li>
 * </ul>
 *
 * <p>{@code finapp.risk.sweeper.enabled{kind=rescreening}} is {@code RiskRescreeningBeans}' gauge, eager either way. A
 * kyc call that threw writes one line: the cause's class, never its message.
 */
@Slf4j
public final class RescreeningMetrics implements RescreeningObserver {

    /** Exported as {@code finapp_risk_rescreening_total}. */
    public static final String RESCREENING = "finapp.risk.rescreening";

    /** Exported as {@code finapp_risk_rescreening_backlog}. */
    public static final String BACKLOG = "finapp.risk.rescreening.backlog";

    /** Exported as {@code finapp_risk_sweeper_claim_failed_total}, beside the other risk sweepers'. */
    public static final String CLAIM_FAILED = RiskMonitoringMetrics.CLAIM_FAILED;

    /** The {@code kind} tag of the rescreening leg - a leg of {@code RiskActionSchedule}. */
    public static final String KIND = "rescreening";

    /** The backlog's saturation: beyond this the figure says "many", not how many. */
    static final long BACKLOG_CAP = 1_000_000L;

    static final Duration MIN_REFRESH = Duration.ofSeconds(15);

    /** A connection source - its own seam, the metrics precedent. */
    @FunctionalInterface
    public interface Connections {
        Connection open() throws SQLException;
    }

    private record Cached(Instant takenAt, OptionalLong backlog) {}

    private final RescreeningPermitStore permits;
    private final Connections connections;
    private final Clock clock;
    private final Map<Conclusion, Counter> outcomes = new EnumMap<>(Conclusion.class);
    private final Counter claimFailures;
    private final AtomicReference<Cached> cached = new AtomicReference<>(new Cached(Instant.EPOCH, OptionalLong.empty()));

    public RescreeningMetrics(RescreeningPermitStore permits, Connections connections, Clock clock,
            MeterRegistry registry) {
        this.permits = Objects.requireNonNull(permits, "permits");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(registry, "registry");
        for (Conclusion conclusion : Conclusion.values()) {
            outcomes.put(conclusion, Counter.builder(RESCREENING).tag("outcome", conclusion.tag())
                    .description("Rescreening permits by what became of each claim: cleared, hit, skipped, deferred or"
                            + " failed - alert on any failed increase")
                    .register(registry));
        }
        Gauge.builder(BACKLOG, this, self -> {
                    OptionalLong figure = self.refreshed().backlog();
                    return figure.isPresent() ? (double) figure.getAsLong() : Double.NaN;
                })
                .description("Rescreening permits PENDING, up to a million. NaN when unreadable. Fleet-wide from every"
                        + " instance: aggregate with max(), never sum()")
                .strongReference(true)
                .register(registry);
        this.claimFailures = Counter.builder(CLAIM_FAILED).tag("kind", KIND)
                .description("Rescreening enqueue batches, claims and conclusions that failed and rolled back - a later"
                        + " tick claims again. Alert on any increase")
                .register(registry);
    }

    @Override
    public void concluded(Conclusion conclusion) {
        outcomes.get(Objects.requireNonNull(conclusion, "conclusion")).increment();
    }

    @Override
    public void enqueued(int born) {
        // No meter: a version's census is its own row's (screening_list_version.enqueued), read by -023's census.
    }

    @Override
    public void askFailed(Class<? extends Throwable> failed) {
        // The cause's class name only - never its message (a storage failure's can quote a row), never the party.
        String cause = failed.getSimpleName();
        log.warn("Rescreening kyc ask failed: {}", cause);
    }

    /** A batch, claim or conclusion that failed and rolled back. */
    public void claimFailed() {
        claimFailures.increment();
    }

    private Cached refreshed() {
        Cached current = cached.get();
        if (Duration.between(current.takenAt(), clock.instant()).compareTo(MIN_REFRESH) < 0) {
            return current;
        }
        Cached fresh;
        try (Connection connection = connections.open()) {
            connection.setReadOnly(true);
            fresh = new Cached(clock.instant(), OptionalLong.of(permits.pending(connection, BACKLOG_CAP)));
        } catch (SQLException | RuntimeException unreadable) {
            log.warn("The rescreening backlog could not be read: {}", unreadable.getClass().getSimpleName());
            fresh = new Cached(clock.instant(), OptionalLong.empty());
        }
        cached.set(fresh);
        return fresh;
    }
}
