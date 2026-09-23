package com.finapp.app.telemetry;

import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.ProjectionVerification;
import com.finapp.ledger.SupportedCurrencies;
import com.finapp.ledger.TrialBalance;
import com.finapp.sharedkernel.money.CurrencyCode;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * Publishes how many accounts' projections disagree with the derivation (`P3-TSK-010`,
 * ADR-0041 rule 2, {@code INV-BAL-02}) — and, since `P3-TSK-019`, whether the trial balance
 * is zero per currency ({@code INV-ACC-01}) — and, since `P3-TSK-020`, how many holds are
 * {@code ACTIVE} ({@code finapp.ledger.hold.active}, a count never an amount). <strong>Zero at all times; the alerting
 * threshold is zero</strong> — one drifting account or one imbalanced currency is an
 * incident, because everything above the journal is trusted on the strength of these
 * comparisons.
 *
 * <h2>The scrape is the schedule, and that is the design's answer to "no leader"</h2>
 *
 * <p>The backlog offered idempotent-per-run or lease-protected, and warned that a new
 * {@code DISTRIBUTED_EXECUTION.md} §3 exemption is a decision. This design needs neither: the
 * verification runs when a scrape finds the cached reading past its floor — the
 * {@code OutboxBacklog} shape — so nothing schedules ambiently and no exemption question
 * arises. The sweep is read-only and idempotent; every instance verifies independently and
 * publishes the same fleet-wide answer, so a dashboard aggregates with {@code max()},
 * <strong>never {@code sum()}</strong>.
 *
 * <h2>NaN, never zero</h2>
 *
 * <p>{@code P1-TSK-029}'s rule, and it bites hardest on this meter: a zero here means
 * "verified clean", so publishing zero when nothing could be verified would silence the one
 * alert this gauge exists to fire. Unreadable reports {@link Double#NaN} — absent, alertable.
 *
 * <h2>A finding is reported, never repaired</h2>
 *
 * <p>A non-zero reading logs the drifting account identifiers (bounded, amount-free —
 * {@code INV-AUD-02}) so an operator knows where to look. Nothing here writes anything: a
 * ledger that corrected itself would destroy the evidence of what went wrong.
 */
@Slf4j
final class LedgerMetrics {

    /** {@code finapp.ledger.projection.drift} — accounts where recomputation ≠ projection. */
    static final String PROJECTION_DRIFT = "finapp.ledger.projection.drift";

    /**
     * {@code finapp.ledger.trial.balance} — per currency: 0 verified balanced, 1 out of
     * balance, NaN unverifiable. The value is a <strong>verdict, never the imbalance
     * amount</strong>: a magnitude is a financial figure, and telemetry gets identifiers and
     * counts only ({@code INV-AUD-02}, the drift gauge's own discipline).
     */
    static final String TRIAL_BALANCE = "finapp.ledger.trial.balance";

    /** {@code finapp.ledger.hold.active} — how many holds are {@code ACTIVE}, fleet-wide. */
    static final String HOLD_ACTIVE = "finapp.ledger.hold.active";

    /**
     * Six times the sibling gauges' floor, because this reading walks every posted account
     * and folds its history — the honest cost of recomputation-as-evidence, paid at a pace
     * that cannot become load on the database it is auditing.
     */
    static final Duration MIN_REFRESH = Duration.ofSeconds(30);

    /**
     * The cheap-read floor, the sibling gauges' own ({@code IdentityMetrics},
     * {@code KycMetrics}): the hold count is one {@code COUNT(*)}, not a journal fold.
     */
    static final Duration HOLD_REFRESH = Duration.ofSeconds(5);

    /** The {@code IdentityMetrics.Connections} seam, for the same unit-testability reason. */
    @FunctionalInterface
    interface Connections {
        Connection open() throws SQLException;
    }

    /** The verification seam, so the unit test needs no database and no derivation. */
    @FunctionalInterface
    interface Verification {
        ProjectionVerification.Report verify(Connection connection);
    }

    /** The trial-balance seam — the same unit-testability reason as {@link Verification}. */
    @FunctionalInterface
    interface Trial {
        TrialBalance.Report sweep(Connection connection);
    }

    /** The hold-count seam ({@code HoldStore.countActive}) — the same reason again. */
    @FunctionalInterface
    interface Holds {
        long countActive(Connection connection);
    }

    private final Verification verification;
    private final Trial trial;
    private final Holds holds;
    private final Connections connections;
    private final Clock clock;
    private final MeterRegistry registry;
    private final AtomicReference<Cached> cached = new AtomicReference<>(Cached.empty());
    private final AtomicReference<TrialCached> trialCached =
            new AtomicReference<>(TrialCached.empty());
    private final AtomicReference<HoldCached> holdCached =
            new AtomicReference<>(HoldCached.empty());

    /** The currencies whose series exist, so a discovered one registers exactly once. */
    private final Set<String> trialSeries = ConcurrentHashMap.newKeySet();

    LedgerMetrics(
            Verification verification,
            Trial trial,
            Holds holds,
            Connections connections,
            Clock clock,
            MeterRegistry registry) {
        this.verification = verification;
        this.trial = trial;
        this.holds = holds;
        this.connections = connections;
        this.clock = clock;
        this.registry = registry;

        // Eager per supported currency (P1-TSK-029): a freshly started instance publishes
        // every series, so the zero-threshold alert has something to evaluate from the
        // first scrape. A currency found only in history registers at discovery.
        for (CurrencyCode currency : SupportedCurrencies.ALL) {
            registerTrialSeries(currency.code());
        }

        Gauge.builder(PROJECTION_DRIFT, this, self -> self.reading().valueOrNaN())
                .description(
                        "Accounts whose balance projection disagrees with recomputation from"
                                + " postings (INV-BAL-02). Zero at all times; alert on any"
                                + " non-zero value. Fleet-wide from every instance: aggregate"
                                + " with max(), never sum()")
                // No baseUnit (the P0-TSK-029 finding: Micrometer appends it to the name).
                .strongReference(true)
                .register(registry);

        Gauge.builder(HOLD_ACTIVE, this, self -> self.holdReading().valueOrNaN())
                .description(
                        "Holds currently ACTIVE, system-wide (P3-TSK-020). A count, never"
                                + " an amount. NaN when unreadable, never zero. Fleet-wide"
                                + " from every instance: aggregate with max(), never sum()")
                .strongReference(true)
                .register(registry);
    }

    private void registerTrialSeries(String currency) {
        if (!trialSeries.add(currency)) {
            return;
        }
        Gauge.builder(TRIAL_BALANCE, this, self -> self.trialReading().valueOrNaN(currency))
                .tag("currency", currency)
                .description(
                        "Whether the trial balance is zero for this currency (INV-ACC-01):"
                            + " 0 verified balanced, 1 out of balance, NaN unverifiable."
                            + " Zero at all times; alert on any non-zero value. Fleet-wide"
                            + " from every instance: aggregate with max(), never sum()")
                .strongReference(true)
                .register(registry);
    }

    private TrialCached trialReading() {
        TrialCached current = trialCached.get();
        if (!current.isStaleAt(clock.instant())) {
            return current;
        }
        TrialCached fresh;
        try (Connection connection = connections.open()) {
            TrialBalance.Report report = trial.sweep(connection);
            Map<String, Long> verdicts = new HashMap<>();
            for (CurrencyCode currency : report.outOfBalance()) {
                verdicts.put(currency.code(), 1L);
                // A series for a currency the supported set never named - historical rows
                // are still the journal's, and an imbalance there is still an incident.
                registerTrialSeries(currency.code());
            }
            if (!report.outOfBalance().isEmpty()) {
                // Identifiers only, never an amount (INV-AUD-02). WARN once per refresh,
                // not per scrape - the cache is also the log's rate limit.
                log.warn(
                        "Trial balance out of balance for {} (INV-ACC-01): total debits do"
                                + " not equal total credits - an incident, never"
                                + " self-corrected",
                        report.outOfBalance());
            }
            fresh = new TrialCached(clock.instant(), Map.copyOf(verdicts), false);
        } catch (Exception unreadable) {
            log.warn(
                    "Could not sweep the trial balance; the gauge reports absent rather than"
                            + " zero: {}",
                    unreadable.getClass().getSimpleName());
            fresh = new TrialCached(clock.instant(), Map.of(), true);
        }
        trialCached.set(fresh);
        return fresh;
    }

    private HoldCached holdReading() {
        HoldCached current = holdCached.get();
        if (!current.isStaleAt(clock.instant())) {
            return current;
        }
        HoldCached fresh;
        try (Connection connection = connections.open()) {
            fresh = new HoldCached(clock.instant(), holds.countActive(connection));
        } catch (Exception unreadable) {
            log.warn(
                    "Could not count the active holds; the gauge reports absent rather than"
                            + " zero: {}",
                    unreadable.getClass().getSimpleName());
            fresh = new HoldCached(clock.instant(), HoldCached.UNKNOWN);
        }
        holdCached.set(fresh);
        return fresh;
    }

    private Cached reading() {
        Cached current = cached.get();
        if (!current.isStaleAt(clock.instant())) {
            return current;
        }
        Cached fresh;
        try (Connection connection = connections.open()) {
            ProjectionVerification.Report report = verification.verify(connection);
            if (report.drifting() > 0) {
                // Identifiers and counts, never an amount (INV-AUD-02). WARN once per
                // refresh, not per scrape - the cache is also the log's rate limit.
                log.warn(
                        "Balance projection drift on {} account(s) (in flight: {}):"
                                + " {} - recomputation from postings disagrees with"
                                + " ledger.account_balance (INV-BAL-02)",
                        report.drifting(),
                        report.inFlight(),
                        summarise(report.driftingAccounts()));
            }
            fresh = new Cached(clock.instant(), report.drifting());
        } catch (Exception unreadable) {
            log.warn(
                    "Could not verify the balance projection; the gauge reports absent rather"
                            + " than zero: {}",
                    unreadable.getClass().getSimpleName());
            fresh = new Cached(clock.instant(), Cached.UNKNOWN);
        }
        cached.set(fresh);
        return fresh;
    }

    private static String summarise(List<LedgerAccountId> accounts) {
        StringBuilder summary = new StringBuilder();
        for (LedgerAccountId account : accounts) {
            if (summary.length() > 0) {
                summary.append(", ");
            }
            summary.append(account);
        }
        return summary.toString();
    }

    /**
     * One trial sweep's verdicts and when they were taken — the {@link Cached} stance
     * verbatim: non-authoritative, per instance, no §3 row. A currency absent from the map
     * verified balanced (a currency with no lines is vacuously the same verdict); the
     * {@code unknown} flag is the whole sweep failing, surfacing as NaN on
     * <strong>every</strong> series, because zero means "verified balanced".
     */
    private record TrialCached(Instant takenAt, Map<String, Long> outByCurrency, boolean unknown) {

        static TrialCached empty() {
            return new TrialCached(Instant.MIN, Map.of(), true);
        }

        boolean isStaleAt(Instant now) {
            return takenAt.isBefore(now.minus(MIN_REFRESH));
        }

        double valueOrNaN(String currency) {
            if (unknown) {
                return Double.NaN;
            }
            return outByCurrency.containsKey(currency) ? 1.0d : 0.0d;
        }
    }

    /**
     * One hold count and when it was taken — the {@link Cached} stance verbatim:
     * non-authoritative, per instance, no §3 row.
     */
    private record HoldCached(Instant takenAt, long value) {

        /** Not a count. A negative sentinel cannot collide with one, which zero could. */
        static final long UNKNOWN = -1L;

        static HoldCached empty() {
            return new HoldCached(Instant.MIN, UNKNOWN);
        }

        boolean isStaleAt(Instant now) {
            return takenAt.isBefore(now.minus(HOLD_REFRESH));
        }

        double valueOrNaN() {
            return value == UNKNOWN ? Double.NaN : (double) value;
        }
    }

    /**
     * One reading and when it was taken. Non-authoritative and per instance — the
     * {@code IdentityMetrics.Cached} stance, so no {@code DISTRIBUTED_EXECUTION.md} §3 row:
     * it decides nothing, and a stale reading is a stale <em>reading</em>.
     */
    private record Cached(Instant takenAt, long value) {

        /** Not a count. A negative sentinel cannot collide with one, which zero could. */
        static final long UNKNOWN = -1L;

        static Cached empty() {
            return new Cached(Instant.MIN, UNKNOWN);
        }

        boolean isStaleAt(Instant now) {
            return takenAt.isBefore(now.minus(MIN_REFRESH));
        }

        double valueOrNaN() {
            return value == UNKNOWN ? Double.NaN : (double) value;
        }
    }
}
