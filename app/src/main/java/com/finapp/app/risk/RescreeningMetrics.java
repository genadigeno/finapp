package com.finapp.app.risk;

import com.finapp.risk.RescreeningObserver;
import com.finapp.risk.RescreeningPermitStore;
import com.finapp.risk.RiskProofs;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;

/**
 * The rescreening leg's meters (`P13-TSK-022`; {@code PHASE_13_PLAN.md} section 15; the Phase 12 → 13 gate's N5), every
 * series registered eagerly and every tag a closed vocabulary - never a party, a list entry, a screening or a name
 * ({@code INV-RSK-08}, {@code MetricNames.ALLOWED_TAG_KEYS}):
 *
 * <ul>
 *   <li>{@code finapp.risk.rescreening{outcome}} - what became of each claimed permit: {@code cleared}, {@code hit}
 *       (always alerted since the review - a fact alert, `hit_not_alerted` retired), {@code skipped},
 *       {@code not_onboarded} (an onboarding party with no KYC case - never screened, nothing to rescreen; never an
 *       exposure), {@code late_hit} (a hit kyc recorded after the permit concluded otherwise, told with its own signal and
 *       alert - the last review's item 3), {@code deferred}, {@code failed} - the exposure: alert on any {@code failed}
 *       increase (`-024` writes the rules);</li>
 *   <li>{@code finapp.risk.rescreening.backlog} - the permits {@code PENDING}, counted up to a million (saturating); NaN
 *       when unreadable or stale. Every instance reports the same figure: aggregate with {@code max()};</li>
 *   <li>{@code finapp.risk.sweeper.claim.failed{kind=rescreening}} - an enqueue batch, a claim or a conclusion that failed
 *       and rolled back; a later tick claims again.</li>
 * </ul>
 *
 * <p><strong>The backlog is never read on the scrape thread</strong> (the review's MINOR 9, {@code RiskProofMetrics}'
 * pattern, `-023`'s). A scrape that finds the figure older than {@link #MIN_REFRESH} <em>starts</em> one reading on the
 * refresher and answers the last figure meanwhile - a compare-and-set in-flight flag makes it single-flight per
 * instance; the reading is one read-only snapshot armed with {@code statement_timeout} ({@link #over}); a failed
 * reading, or a figure older than {@link #STALE_AFTER} (a refresher that never finished), is NaN - "absent", never a
 * stale count. A kyc call that threw writes one line: the cause's class, never its message.
 *
 * <p>{@code finapp.risk.sweeper.enabled{kind=rescreening}} is {@code RiskRescreeningBeans}' gauge, eager either way.
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

    /** The refresh floor: a scrape older than this starts one reading. */
    static final Duration MIN_REFRESH = Duration.ofSeconds(15);

    /** Past this age the figure is NaN: two floors - the refresher did not finish a reading in time. */
    static final Duration STALE_AFTER = MIN_REFRESH.multipliedBy(2);

    private record Cached(Instant takenAt, OptionalLong backlog) {}

    private final LongSupplier backlog;
    private final Executor refresher;
    private final Clock clock;
    private final Map<Conclusion, Counter> outcomes = new EnumMap<>(Conclusion.class);
    private final Counter claimFailures;
    private final AtomicReference<Cached> cached = new AtomicReference<>(new Cached(Instant.EPOCH, OptionalLong.empty()));
    private final AtomicBoolean inFlight = new AtomicBoolean();

    /**
     * The backlog read on one read-only snapshot of {@code transactions}, its statement bounded by
     * {@code statementTimeout} - the composition's.
     */
    public static LongSupplier over(RescreeningPermitStore permits, RiskTransactions transactions,
            Duration statementTimeout) {
        Objects.requireNonNull(permits, "permits");
        Objects.requireNonNull(transactions, "transactions");
        Objects.requireNonNull(statementTimeout, "statementTimeout");
        return () -> transactions.inSnapshot(snapshot -> {
            RiskProofs.bound(snapshot, statementTimeout);
            return permits.pending(snapshot, BACKLOG_CAP);
        });
    }

    /** A refresher that runs each reading on a virtual thread of its own - the composition's. */
    public static Executor virtualThreads() {
        return reading -> Thread.ofVirtual().name("risk-rescreening-backlog").start(reading);
    }

    /**
     * @param backlog one bounded reading of the backlog ({@link #over})
     * @param refresher where a reading runs - never the scrape's thread in production ({@link #virtualThreads})
     */
    public RescreeningMetrics(LongSupplier backlog, Executor refresher, Clock clock, MeterRegistry registry) {
        this.backlog = Objects.requireNonNull(backlog, "backlog");
        this.refresher = Objects.requireNonNull(refresher, "refresher");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(registry, "registry");
        for (Conclusion conclusion : Conclusion.values()) {
            outcomes.put(conclusion, Counter.builder(RESCREENING).tag("outcome", conclusion.tag())
                    .description("Rescreening permits by what became of each claim: cleared, hit, skipped,"
                            + " not_onboarded, late_hit (a hit told after the permit concluded otherwise), deferred or"
                            + " failed - alert on any failed increase")
                    .register(registry));
        }
        Gauge.builder(BACKLOG, this, self -> {
                    OptionalLong figure = self.figure();
                    return figure.isPresent() ? (double) figure.getAsLong() : Double.NaN;
                })
                .description("Rescreening permits PENDING, up to a million. NaN when unreadable or stale. Read off the"
                        + " scrape thread. Fleet-wide from every instance: aggregate with max(), never sum()")
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
        // No meter: a version's census is its own row's (screening_list_version.enqueued), read by -024's census.
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

    /** The figure served: the last reading's, empty when it failed or is past {@link #STALE_AFTER} - the gauge's NaN. */
    private OptionalLong figure() {
        Cached current = refreshed();
        if (Duration.between(current.takenAt(), clock.instant()).compareTo(STALE_AFTER) >= 0) {
            return OptionalLong.empty();
        }
        return current.backlog();
    }

    /** The last figure; when it is past the floor, one more reading started on the refresher unless one already runs. */
    private Cached refreshed() {
        Cached current = cached.get();
        if (Duration.between(current.takenAt(), clock.instant()).compareTo(MIN_REFRESH) < 0
                || !inFlight.compareAndSet(false, true)) {
            return current;
        }
        try {
            refresher.execute(() -> {
                try {
                    cached.set(read());
                } finally {
                    inFlight.set(false);
                }
            });
        } catch (RuntimeException refused) {
            // A refused start releases the flag; the figure keeps the last and goes NaN at two floors.
            inFlight.set(false);
            log.warn("The rescreening backlog's reading could not start: {}", refused.getClass().getSimpleName());
        }
        return cached.get();
    }

    private Cached read() {
        try {
            return new Cached(clock.instant(), OptionalLong.of(backlog.getAsLong()));
        } catch (RuntimeException unreadable) {
            log.warn("The rescreening backlog could not be read: {}", unreadable.getClass().getSimpleName());
            return new Cached(clock.instant(), OptionalLong.empty());
        }
    }
}
