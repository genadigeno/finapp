package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.kyc.ReviewTask;
import com.finapp.kyc.ReviewTaskStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The review-queue gauge's failure behaviour (`P2-TSK-010`) — {@code IdentityMetricsTest}'s two
 * essential halves and no more, because the caching mechanics are that class's proven shape and
 * a third copy of its clock tests would be duplication that drifts. What it counts is
 * {@code ReviewTaskStore.countOpen}'s subject, asserted against a real database in
 * {@code ScreeningRunDatabaseTest}.
 */
@DisplayName("the review-queue gauge (P2-TSK-010)")
class KycMetricsTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-10T12:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("an unreadable database reports absent, never zero")
    void anUnreadableDatabaseReportsAbsent() {
        MeterRegistry registry = new SimpleMeterRegistry();
        new KycMetrics(new FixedQueue(4), unreachable(), CLOCK, registry);

        // A ZERO would say "nobody is waiting" at the exact moment nothing can be known, and an
        // alert on queue depth would stay silent through the outage (P0-TSK-029's reasoning).
        assertThat(gauge(registry)).isNaN();
    }

    @Test
    @DisplayName("a readable database reports the count")
    void aReadableDatabaseReportsTheCount() {
        // The positive control, without which the assertion above passes against a gauge that
        // reports NaN unconditionally - never wrong and never useful.
        MeterRegistry registry = new SimpleMeterRegistry();
        new KycMetrics(new FixedQueue(4), () -> null, CLOCK, registry);

        assertThat(gauge(registry)).isEqualTo(4.0d);
    }

    // -----------------------------------------------------------------

    private static double gauge(MeterRegistry registry) {
        return registry.get(KycMetrics.REVIEW_QUEUE).gauge().value();
    }

    private static KycMetrics.Connections unreachable() {
        return () -> {
            throw new SQLException("the database is unreachable");
        };
    }

    private static final class FixedQueue implements ReviewTaskStore<Connection> {

        private final long open;

        FixedQueue(long open) {
            this.open = open;
        }

        @Override
        public boolean openForCheck(Connection unitOfWork, ReviewTask fresh) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long countOpen(Connection unitOfWork) {
            return open;
        }

        @Override
        public boolean resolve(
                Connection unitOfWork,
                com.finapp.kyc.ReviewTaskId taskId,
                com.finapp.kyc.KycCaseId caseId,
                java.util.UUID resolvedBy,
                String reason,
                java.time.Instant at) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Optional<ReviewTask> findByIdForCase(
                Connection unitOfWork,
                com.finapp.kyc.ReviewTaskId taskId,
                com.finapp.kyc.KycCaseId caseId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.List<ReviewTask> forCase(
                Connection unitOfWork, com.finapp.kyc.KycCaseId caseId) {
            throw new UnsupportedOperationException();
        }
    }
}
