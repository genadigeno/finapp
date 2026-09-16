package com.finapp.app.telemetry;

import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.ProjectionVerification;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes how many accounts' projections disagree with the derivation (`P3-TSK-010`,
 * ADR-0041 rule 2, {@code INV-BAL-02}). <strong>Zero at all times; the alerting threshold is
 * zero</strong> — one drifting account is an incident, because the projection is trusted for
 * display only on the strength of this comparison.
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
final class LedgerMetrics {

    /** {@code finapp.ledger.projection.drift} — accounts where recomputation ≠ projection. */
    static final String PROJECTION_DRIFT = "finapp.ledger.projection.drift";

    /**
     * Six times the sibling gauges' floor, because this reading walks every posted account
     * and folds its history — the honest cost of recomputation-as-evidence, paid at a pace
     * that cannot become load on the database it is auditing.
     */
    static final Duration MIN_REFRESH = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(LedgerMetrics.class);

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

    private final Verification verification;
    private final Connections connections;
    private final Clock clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>(Cached.empty());

    LedgerMetrics(
            Verification verification,
            Connections connections,
            Clock clock,
            MeterRegistry registry) {
        this.verification = verification;
        this.connections = connections;
        this.clock = clock;

        Gauge.builder(PROJECTION_DRIFT, this, self -> self.reading().valueOrNaN())
                .description(
                        "Accounts whose balance projection disagrees with recomputation from"
                                + " postings (INV-BAL-02). Zero at all times; alert on any"
                                + " non-zero value. Fleet-wide from every instance: aggregate"
                                + " with max(), never sum()")
                // No baseUnit (the P0-TSK-029 finding: Micrometer appends it to the name).
                .strongReference(true)
                .register(registry);
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
