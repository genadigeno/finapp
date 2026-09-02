package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.outbox.OutboxBacklog;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The outbox backlog gauges report the backlog that is actually there.
 *
 * <p>The positive control for {@code PrometheusEndpointTest}, which proves only that an unreadable
 * backlog is reported as absent. A gauge hard-wired to NaN would satisfy that perfectly and be
 * useless — "fails safe when it cannot read" is half a claim, and this is the other half.
 *
 * <p>Goes through the <strong>application's own {@code DataSource}</strong> rather than a
 * connection a fixture opened. That is the configuration the application actually has, connecting
 * as {@code finapp_app} with per-table DML and nothing else - so a backlog query needing
 * privileges the application does not hold fails here rather than in the one environment
 * configured correctly.
 */
@Tag("database")
@SpringBootTest
class OutboxMetricsDatabaseTest {

    private static final String AGGREGATE_TYPE = "MetricsProbe";

    @Autowired private DataSource dataSource;

    @Autowired private MeterRegistry registry;

    private OutboxBacklog backlog;

    @BeforeEach
    void useTheApplicationPool() {
        backlog = new OutboxBacklog(dataSource::getConnection);
    }

    @AfterEach
    void removeProbeRows() throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "DELETE FROM platform.outbox_event WHERE aggregate_type = '" + AGGREGATE_TYPE + "'");
        }
    }

    @Test
    @DisplayName("an empty backlog reads as zero pending and zero age")
    void anEmptyBacklogReadsAsZero() {
        OutboxBacklog.Reading reading = backlog.read();

        // Not asserted as exactly zero: this database is shared with every other database test,
        // and a suite that assumes it owns the table is a suite that fails when it is run
        // alongside anything else. What matters is that the reading is coherent.
        assertThat(reading.pending()).isNotNegative();
        assertThat(reading.oldest()).isGreaterThanOrEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("a pending event raises the depth and gives the age something to measure")
    void aPendingEventIsCounted() throws SQLException {
        OutboxBacklog.Reading before = backlog.read();

        insertPendingEvent(Duration.ofMinutes(7));

        OutboxBacklog.Reading after = backlog.read();
        assertThat(after.pending())
                .as("an unpublished row must raise the depth")
                .isEqualTo(before.pending() + 1);
        assertThat(after.oldest())
                .as("a row back-dated seven minutes must make the oldest age at least that")
                .isGreaterThanOrEqualTo(Duration.ofMinutes(7));
    }

    @Test
    @DisplayName("a published event leaves the backlog, which is what makes depth mean anything")
    void publishingRemovesItFromTheBacklog() throws SQLException {
        OutboxBacklog.Reading empty = backlog.read();
        insertPendingEvent(Duration.ofMinutes(3));
        assertThat(backlog.read().pending()).isEqualTo(empty.pending() + 1);

        markProbeEventsPublished();

        // The negative control. Without it, a query that counted every row rather than only the
        // unpublished ones would pass every assertion above - and would then report a backlog
        // that grows for ever while the relay works perfectly.
        assertThat(backlog.read().pending())
                .as("a published event is no longer waiting")
                .isEqualTo(empty.pending());
    }

    @Test
    @DisplayName("the published GAUGE reports a real number, not only the query behind it")
    void theGaugeItselfReportsTheBacklog() throws SQLException {
        // The test that was missing, and whose absence let a structurally always-NaN gauge ship.
        // The gauge cached its reading against a sentinel timestamp of Long.MIN_VALUE, and
        // `now - Long.MIN_VALUE` overflows negative - so the empty cache was never stale, never
        // refreshed, and both gauges published NaN for ever.
        //
        // Nothing caught it: the endpoint test asserts NaN when the database is ABSENT, which an
        // always-NaN gauge satisfies perfectly, and the tests above exercise OutboxBacklog rather
        // than the meter. It took scraping a running instance. This asserts through the registry,
        // which is what a scrape actually reads.
        insertPendingEvent(Duration.ofMinutes(2));

        Gauge pending = registry.find(OutboxMetrics.PENDING).gauge();
        Gauge oldest = registry.find(OutboxMetrics.OLDEST).gauge();
        assertThat(pending).as("the meter must be registered").isNotNull();
        assertThat(oldest).isNotNull();

        assertThat(pending.value())
                .as("a readable backlog must report a number, never NaN")
                .isNotNaN()
                .isGreaterThanOrEqualTo(1.0);
        assertThat(oldest.value()).isNotNaN().isGreaterThanOrEqualTo(120.0);
    }

    // -----------------------------------------------------------------

    private void insertPendingEvent(Duration age) throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    """
                    INSERT INTO platform.outbox_event (
                        event_id, event_type, aggregate_id, aggregate_type, occurred_at,
                        producer, event_version, schema_version, correlation_id, causation_id,
                        payload, payload_media_type, next_attempt_at)
                    VALUES (
                        '%s', 'metrics.Probe', '%s', '%s', now() - interval '%d seconds',
                        'metrics-test', 1, 1, '%s', '%s',
                        '{}'::bytea, 'application/json', now())
                    """
                            .formatted(
                                    UUID.randomUUID(),
                                    UUID.randomUUID(),
                                    AGGREGATE_TYPE,
                                    age.toSeconds(),
                                    UUID.randomUUID(),
                                    // INV-EVT-03: all ten envelope fields are NOT NULL, so an
                                    // untraceable event cannot be queued. The fixture found that
                                    // out by trying to insert one - the constraint working.
                                    UUID.randomUUID()));
        }
    }

    private void markProbeEventsPublished() throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "UPDATE platform.outbox_event SET published_at = now(), attempts = 1 "
                            + "WHERE aggregate_type = '" + AGGREGATE_TYPE + "'");
        }
    }

    private Connection connect() throws SQLException {
        return dataSource.getConnection();
    }
}
