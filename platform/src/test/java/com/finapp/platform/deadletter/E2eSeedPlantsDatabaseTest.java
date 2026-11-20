package com.finapp.platform.deadletter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.api.ApiException;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.EventPublisher;
import com.finapp.platform.outbox.OutboxRelay;
import com.finapp.platform.outbox.PendingEvent;
import com.finapp.platform.outbox.RetryPolicy;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The end-to-end seed's two plants (`P17-TSK-106`) are what the dead-letter doors act on, and what the fleet leaves alone until a person
 * does. The files under test are the ones {@code infra/scripts/e2e-seed.sh} applies, read from the repository and run unchanged, as the
 * compose stack's migrator role: a plant that drifted from this module's rules fails here rather than in another repository's end-to-end
 * run.
 */
@Tag("database")
@DisplayName("the end-to-end seed's dead letters are real dead letters (P17-TSK-106)")
class E2eSeedPlantsDatabaseTest {

    private static final Duration RETENTION = Duration.ofDays(14);
    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CorrelationId FLOW = CorrelationId.of("e2e-seed-proof");
    private static final Actor FIRST = new Actor("operator-1", ActorType.EMPLOYEE);
    private static final Actor SECOND = new Actor("operator-2", ActorType.EMPLOYEE);
    /** The sweep's smallest legal bound, past the broker's redelivery horizon (V012). */
    private static final long SWEEP_BOUND_MS = 691_500_001L;

    private static Connection admin;
    private static String abandonedPlant;
    private static String parkedPlant;

    private final DeadLetterOperations operations =
            new DeadLetterOperations(new JdbcDeadLetterStore(), new JdbcAuditWriter(), IDS, CLOCK, RETENTION, type -> false);

    @BeforeAll
    static void connectAndRead() throws SQLException {
        admin = DatabaseRoles.bootstrap();
        Path seed = repositoryRoot().resolve("infra/scripts/e2e-seed");
        abandonedPlant = read(seed.resolve("abandoned-event.sql"));
        parkedPlant = read(seed.resolve("parked-record.sql"));
    }

    @AfterAll
    static void disconnect() throws SQLException {
        if (admin != null) {
            admin.close();
        }
    }

    @BeforeEach
    void empty() throws SQLException {
        try (Statement statement = admin.createStatement()) {
            statement.executeUpdate("DELETE FROM platform.dead_letter_discard");
            statement.executeUpdate("DELETE FROM platform.inbox_parked_record");
            statement.executeUpdate("DELETE FROM platform.inbox_message");
            statement.executeUpdate("DELETE FROM platform.outbox_event");
        }
    }

    @Test
    @DisplayName("an abandoned plant is listed, outlives a relay poll and a sweep, and a retry returns it to a relay that publishes it")
    void theAbandonedPlantIsRetried() throws Exception {
        UUID event = plant(abandonedPlant, rows -> rows.getObject(1, UUID.class));

        assertThat(listed().abandoned()).extracting(DeadLetterStore.AbandonedEvent::eventId).containsExactly(event);
        RecordingPublisher publisher = new RecordingPublisher();
        relay(publisher).pollOnce();
        sweep();
        assertThat(publisher.published).as("no relay takes an aggregate whose head is abandoned").doesNotContain(event);
        assertThat(state(event)).isEqualTo("abandoned|unpublished|attempts=14");

        try (Connection connection = DatabaseRoles.application()) {
            connection.setAutoCommit(false);
            operations.retry(connection, event, FIRST, "the console retries the seeded event", FLOW);
            connection.commit();
        }
        relay(publisher).pollOnce();

        assertThat(publisher.published).as("the retried event goes out on its producer's declared topic").containsExactly(event);
        assertThat(publisher.producers).containsExactly("reconciliation");
        assertThat(state(event)).isEqualTo("live|published|attempts=1");
    }

    @Test
    @DisplayName("an abandoned plant is discarded by two persons, never by one")
    void theAbandonedPlantIsDiscardedByTwo() throws Exception {
        UUID event = plant(abandonedPlant, rows -> rows.getObject(1, UUID.class));

        UUID discard = propose(DeadLetterStore.SubjectKind.OUTBOX_EVENT, event);
        assertThatThrownBy(() -> decide(discard, FIRST))
                .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.errorCode()).isEqualTo(DeadLetterErrorCode.SELF_APPROVAL_REFUSED));
        decide(discard, SECOND);

        assertThat(listed().abandoned()).isEmpty();
        assertThat(exists("SELECT 1 FROM platform.outbox_event WHERE discarded_at IS NOT NULL AND event_id = '" + event + "'")).isTrue();
    }

    @Test
    @DisplayName("a parked plant is listed, outlives a relay poll and a sweep, and a release republishes its event")
    void theParkedPlantIsReleased() throws Exception {
        UUID[] planted = plant(parkedPlant, rows -> new UUID[] {rows.getObject("park_id", UUID.class), UUID.fromString(rows.getString("event_id"))});
        UUID park = planted[0];
        UUID event = planted[1];

        assertThat(listed().parked()).extracting(DeadLetterStore.ParkedRecord::parkId).containsExactly(park);
        assertThat(listed().parked().getFirst().group()).as("the page names whose record it is").isEqualTo("finapp.e2e-seed");
        RecordingPublisher publisher = new RecordingPublisher();
        relay(publisher).pollOnce();
        sweep();
        assertThat(publisher.published).as("a published event is not the relay's").isEmpty();
        assertThat(parkedState(park)).isEqualTo("PARKED");
        assertThat(state(event)).as("the sweep keeps what a standing parked record names").isEqualTo("live|published|attempts=1");

        try (Connection connection = DatabaseRoles.application()) {
            connection.setAutoCommit(false);
            operations.release(connection, park, FIRST, "the console releases the seeded record", FLOW);
            connection.commit();
        }
        relay(publisher).pollOnce();

        assertThat(parkedState(park)).as("released to a consumer that will never settle it").isEqualTo("FAILING");
        assertThat(publisher.published).as("the release republishes the one event it names").containsExactly(event);
        assertThat(listed().parked()).isEmpty();
    }

    @Test
    @DisplayName("a parked plant is discarded by two persons, never by one")
    void theParkedPlantIsDiscardedByTwo() throws Exception {
        UUID park = plant(parkedPlant, rows -> rows.getObject("park_id", UUID.class));

        UUID discard = propose(DeadLetterStore.SubjectKind.INBOX_RECORD, park);
        assertThatThrownBy(() -> decide(discard, FIRST))
                .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.errorCode()).isEqualTo(DeadLetterErrorCode.SELF_APPROVAL_REFUSED));
        decide(discard, SECOND);

        assertThat(parkedState(park)).isEqualTo("DISCARDED");
        assertThat(listed().parked()).isEmpty();
    }

    @Test
    @DisplayName("the seed reruns without collision: every run plants fresh identifiers, as the compose stack's migrator")
    void everyRunPlantsFreshRows() throws Exception {
        Set<UUID> events = new HashSet<>();
        Set<UUID> parks = new HashSet<>();
        for (int run = 0; run < 3; run++) {
            events.add(plant(abandonedPlant, rows -> rows.getObject(1, UUID.class)));
            parks.add(plant(parkedPlant, rows -> rows.getObject("park_id", UUID.class)));
        }

        assertThat(events).hasSize(3);
        assertThat(parks).hasSize(3);
        assertThat(listed().abandoned()).hasSize(3);
        assertThat(listed().parked()).hasSize(3);
        assertThat(count("SELECT count(DISTINCT correlation_id) FROM platform.outbox_event")).as("one flow per plant").isEqualTo(6);
    }

    // -----------------------------------------------------------------------------------------------------------------------------

    @FunctionalInterface
    private interface Row<T> {
        T read(ResultSet rows) throws SQLException;
    }

    /** One plant, run as the role the compose stack's psql uses: one statement, one row back. */
    private static <T> T plant(String sql, Row<T> row) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).as("the plant printed what it created").isTrue();
            T created = row.read(rows);
            assertThat(rows.next()).as("one row per plant").isFalse();
            return created;
        }
    }

    /** The first page of the abandoned and of the parked queue, as the console reads them (`P17-TSK-042`: two collections). */
    private record Listed(java.util.List<DeadLetterStore.AbandonedEvent> abandoned, java.util.List<DeadLetterStore.ParkedRecord> parked) {}

    private Listed listed() throws SQLException {
        try (Connection connection = DatabaseRoles.application()) {
            connection.setAutoCommit(false);
            Listed letters = new Listed(
                    operations.abandoned(connection, java.util.Optional.empty(), DeadLetterOperations.MAX_PAGE, FIRST, FLOW).rows(),
                    operations.parked(connection, java.util.Optional.empty(), DeadLetterOperations.MAX_PAGE, FIRST, FLOW).rows());
            connection.commit();
            return letters;
        }
    }

    private UUID propose(DeadLetterStore.SubjectKind kind, UUID subject) throws SQLException {
        try (Connection connection = DatabaseRoles.application()) {
            connection.setAutoCommit(false);
            UUID discard = operations.proposeDiscard(connection, kind, subject, FIRST, "the console proposes discarding the seed", FLOW);
            connection.commit();
            return discard;
        }
    }

    private void decide(UUID discard, Actor approver) throws SQLException {
        try (Connection connection = DatabaseRoles.application()) {
            connection.setAutoCommit(false);
            try {
                operations.approveDiscard(connection, discard, approver, "a second person agrees", FLOW);
                connection.commit();
            } catch (RuntimeException refused) {
                connection.rollback();
                throw refused;
            }
        }
    }

    private static OutboxRelay relay(EventPublisher publisher) {
        return new OutboxRelay(DatabaseRoles::bootstrap, publisher, RetryPolicy.DEFAULT, 8, 100, OutboxRelay.Abandonments.NOT_RECORDED);
    }

    /** The fleet's retention sweep, at its smallest legal bound, as the application calls it. */
    private static void sweep() throws SQLException {
        try (Connection connection = DatabaseRoles.application();
                PreparedStatement sweep = connection.prepareStatement("SELECT platform.sweep_published_outbox_events(?, ?)")) {
            sweep.setLong(1, SWEEP_BOUND_MS);
            sweep.setInt(2, 1000);
            try (ResultSet rows = sweep.executeQuery()) {
                rows.next();
            }
        }
    }

    private static String state(UUID event) throws SQLException {
        return single("SELECT CASE WHEN dead_lettered_at IS NULL THEN 'live' ELSE 'abandoned' END || '|' "
                + "|| CASE WHEN published_at IS NULL THEN 'unpublished' ELSE 'published' END || '|attempts=' || attempts "
                + "FROM platform.outbox_event WHERE event_id = '" + event + "'");
    }

    private static String parkedState(UUID park) throws SQLException {
        return single("SELECT state FROM platform.inbox_parked_record WHERE park_id = '" + park + "'");
    }

    private static String single(String sql) throws SQLException {
        try (Statement statement = admin.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).as("a row for %s", sql).isTrue();
            return rows.getString(1);
        }
    }

    private static long count(String sql) throws SQLException {
        return Long.parseLong(single(sql));
    }

    private static boolean exists(String sql) throws SQLException {
        try (Statement statement = admin.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            return rows.next();
        }
    }

    private static Path repositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            if (Files.isRegularFile(directory.resolve("settings.gradle.kts"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("No settings.gradle.kts above " + Path.of("").toAbsolutePath());
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
    }

    /** What a relay would have put on the broker, and under which producer's topic. */
    private static final class RecordingPublisher implements EventPublisher {
        private final List<UUID> published = Collections.synchronizedList(new ArrayList<>());
        private final List<String> producers = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void publish(PendingEvent event) {
            published.add(event.eventId().value());
            producers.add(event.producer());
        }
    }
}
