package com.finapp.app.telemetry;

import com.finapp.credit.ScorecardFamily;
import com.finapp.credit.ScorecardStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code finapp.credit.scorecard.awaited} (`P15-TSK-025`; the debt row "No alert names a missing {@code ACTIVE} scorecard"): how
 * many requests wait at {@code SUBMITTED} because no {@code RETAIL_SCORECARD} is {@code ACTIVE}.
 *
 * <p>A product with no {@code ACTIVE} policy is refused at submission, but a missing scorecard is not: the request is admitted
 * and waits until it expires, and {@code CreditRequestOpenPastValidity} fires a week later. This reads the wait directly, so a
 * ticket is raised within minutes. Fleet-wide from every instance: aggregate with {@code max()}, never {@code sum()}. NaN when
 * unreadable, never zero: a zero says nobody is waiting.
 */
@Slf4j
public final class CreditScorecardMetrics {

    /** Exported as {@code finapp_credit_scorecard_awaited}. */
    public static final String AWAITED = "finapp.credit.scorecard.awaited";

    private static final Duration MIN_REFRESH = Duration.ofSeconds(15);

    /** A connection source - its own seam, the metrics precedent. */
    public interface Connections {
        Connection open() throws SQLException;
    }

    private final ScorecardStore store;
    private final Connections connections;
    private final Clock clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>(new Cached(null, Double.NaN));

    private record Cached(Instant takenAt, double awaited) {}

    public CreditScorecardMetrics(ScorecardStore store, Connections connections, Clock clock, MeterRegistry registry) {
        this.store = store;
        this.connections = connections;
        this.clock = clock;
        Gauge.builder(AWAITED, this, CreditScorecardMetrics::awaited)
                .description("Credit requests waiting at SUBMITTED because no RETAIL_SCORECARD is ACTIVE; zero when one is,"
                        + " NaN when unreadable. Fleet-wide from every instance: aggregate with max(), never sum()")
                .strongReference(true)
                .register(registry);
    }

    private double awaited() {
        Instant now = clock.instant();
        Cached current = cached.get();
        if (current.takenAt() != null && Duration.between(current.takenAt(), now).compareTo(MIN_REFRESH) < 0) {
            return current.awaited();
        }
        Cached fresh = new Cached(now, read());
        cached.compareAndSet(current, fresh);
        return fresh.awaited();
    }

    private double read() {
        try (Connection connection = connections.open()) {
            connection.setReadOnly(true);
            return store.awaitingAnActiveVersion(connection, ScorecardFamily.RETAIL_SCORECARD);
        } catch (SQLException | RuntimeException unreadable) {
            // The class only: a JDBC message can name the host, the database and the user (INV-AUD-02).
            log.warn("The requests awaiting a scorecard could not be counted: {}", unreadable.getClass().getSimpleName());
            return Double.NaN;
        }
    }
}
