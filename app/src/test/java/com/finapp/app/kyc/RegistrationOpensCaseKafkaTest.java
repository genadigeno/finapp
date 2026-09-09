package com.finapp.app.kyc;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.kyc.JdbcKycCaseStore;
import com.finapp.kyc.KycCase;
import com.finapp.kyc.KycCaseStore;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.outbox.KafkaEventPublisher;
import com.finapp.platform.outbox.PendingEvent;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The acceptance criterion of `P2-TSK-007`, end to end: <strong>registration alone yields
 * exactly one open case, through the real broker</strong>.
 *
 * <p>The application is booted whole, with the relay <em>and</em> the consumer enabled — the two
 * background workers every other test suite deliberately disables — so the chain under test is
 * the deployed one: HTTP registration → outbox → relay schedule → Kafka → consumer loop → inbox
 * → case row, with no call from the test anywhere in the middle.
 */
@Tag("kafka")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "finapp.outbox.relay.enabled=true",
            "finapp.inbox.consumer.enabled=true",
            "finapp.kafka.bootstrap-servers=${finapp.kafka.bootstrap}",
            "finapp.outbox.poll-interval=PT0.2S"
        })
// Closed after the class, deliberately: this context runs LIVE BACKGROUND WORKERS, and a cached
// context's relay kept polling the shared database after these tests finished - observed as a
// stray relay warning, and one alphabetical reordering away from racing a sibling test's own
// relay assertions. A context with workers does not get to outlive the class that wanted them.
@org.springframework.test.annotation.DirtiesContext
@DisplayName("a registration opens a case through the real broker (P2-TSK-007)")
class RegistrationOpensCaseKafkaTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String PASSWORD = "a-long-enough-password-for-kyc";

    @LocalServerPort private int port;

    @Autowired private MeterRegistry registry;

    private final HttpClient http = HttpClient.newHttpClient();
    private final KycCaseStore<Connection> cases = new JdbcKycCaseStore();

    @Test
    @DisplayName("registration alone yields exactly one open case, audited and announced")
    void registrationOpensExactlyOneCase() throws Exception {
        UUID customerId = registerSomebody();

        await(() -> caseCountFor(customerId) >= 1);

        assertThat(caseCountFor(customerId)).isEqualTo(1);
        KycCase kycCase = openCaseFor(customerId);
        assertThat(kycCase.status().name()).isEqualTo("OPEN");

        assertThat(auditCount(customerId))
                .as("kyc.CaseOpened, emitted for the first time - against the customer, by the"
                        + " platform, in the registration's own flow")
                .isEqualTo(1);
        assertThat(announcementCount(kycCase))
                .as("and the fact is announced: kyc.KycCaseOpened on the outbox, in the same"
                        + " transaction as the case (INV-EVT-01)")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the same event delivered again dies in the inbox: still one case")
    void aDuplicateDeliveryIsOneCase() throws Exception {
        UUID customerId = registerSomebody();
        await(() -> caseCountFor(customerId) >= 1);

        double duplicatesBefore = duplicateCount();
        try (KafkaEventPublisher publisher = directPublisher()) {
            // The SAME event, byte for byte and eventId for eventId, straight to the broker -
            // at-least-once delivery doing what it is allowed to do (P2-TSK-001 demonstrated
            // the relay really produces this).
            publisher.publish(customerOpenedEventOf(customerId));
        }
        await(() -> duplicateCount() > duplicatesBefore);

        assertThat(caseCountFor(customerId))
                .as("the inbox absorbed it (INV-IDEM-04, INV-KYC-03's mechanism)")
                .isEqualTo(1);
        assertThat(auditCount(customerId)).as("and nothing was recorded twice").isEqualTo(1);
    }

    @Test
    @DisplayName("a DISTINCT event for the same customer converges silently: one case, one record")
    void aSecondEventForTheSameCustomerConvergesSilently() throws Exception {
        // The converged path's real subject, and the mutation sweep is why this test exists: an
        // exact wire-duplicate never reaches the handler at all - the INBOX absorbs it by
        // eventId - so removing the handler's converged-is-silent guard survived the duplicate
        // test. What exercises the guard is a DIFFERENT event converging on an existing case,
        // which is exactly what the consumer racing POST /v1/me/kyc will produce. The handler
        // must open nothing, record nothing and announce nothing - a converged open that
        // audited itself would put two opening records on one case (INV-KYC-03's ambiguity).
        UUID customerId = registerSomebody();
        await(() -> caseCountFor(customerId) >= 1);
        KycCase kycCase = openCaseFor(customerId);
        EventId secondEvent = EventId.next(IDS);

        try (KafkaEventPublisher publisher = directPublisher()) {
            publisher.publish(craftedCustomerOpened(secondEvent, customerId));
        }
        await(() -> inboxHandled(secondEvent));

        assertThat(caseCountFor(customerId)).isEqualTo(1);
        assertThat(auditCount(customerId))
                .as("the converged delivery recorded nothing - the fact happened once")
                .isEqualTo(1);
        assertThat(announcementCount(kycCase))
                .as("and announced nothing - consumers must not hear of a second case")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the event racing the direct open path converges on one case")
    void theEventRacingTheOpenPathIsOneCase() throws Exception {
        // A customer nobody registered, so only this test's two racing paths can open the case:
        // the consumer (fed directly through the broker) and the direct openOrConverge - the
        // mechanism POST /v1/me/kyc will use. Whoever wins, the index arbitrates and the loser
        // converges.
        UUID customerId = IDS.next();
        EventId eventId = EventId.next(IDS);
        try (KafkaEventPublisher publisher = directPublisher()) {
            publisher.publish(craftedCustomerOpened(eventId, customerId));
        }
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            cases.openOrConverge(app, KycCase.open(IDS, Clock.systemUTC(), customerId));
            app.commit();
        }

        await(() -> inboxHandled(eventId));

        assertThat(caseCountFor(customerId))
                .as("both paths converge on one case - the one-open-case index is the arbiter,"
                        + " whichever path got there first")
                .isEqualTo(1);
    }

    // -----------------------------------------------------------------

    /** Registers a fresh person over HTTP and resolves their customer identifier. */
    private UUID registerSomebody() throws Exception {
        String login = "kyc." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/v1/registrations"))
                        .header("Content-Type", "application/json")
                        .header(IdempotencyKeyHeader.NAME, UUID.randomUUID().toString())
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        "{\"loginIdentifier\":\"" + login
                                                + "\",\"displayName\":\"Ada Lovelace\","
                                                + "\"password\":\"" + PASSWORD + "\"}"))
                        .build();
        assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(201);
        return customerIdOf(login);
    }

    private static UUID customerIdOf(String login) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT c.id FROM party.customer c"
                                        + " JOIN identity.identity i ON i.party_id = c.party_id"
                                        + " WHERE i.login_identifier = ?")) {
            select.setString(1, login);
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).as("the registration created a customer").isTrue();
                return row.getObject(1, UUID.class);
            }
        }
    }

    private static int caseCountFor(UUID customerId) {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement count =
                        app.prepareStatement(
                                "SELECT count(*) FROM kyc.kyc_case WHERE customer_id = ?")) {
            count.setObject(1, customerId);
            try (ResultSet row = count.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("could not count cases", failure);
        }
    }

    private KycCase openCaseFor(UUID customerId) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            return cases.findOpenFor(app, customerId).orElseThrow();
        }
    }

    private static long auditCount(UUID customerId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement count =
                        app.prepareStatement(
                                "SELECT count(*) FROM platform.audit_record"
                                        + " WHERE operation = 'kyc.CaseOpened' AND target_id = ?")) {
            count.setString(1, customerId.toString());
            try (ResultSet row = count.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static long announcementCount(KycCase kycCase) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement count =
                        app.prepareStatement(
                                "SELECT count(*) FROM platform.outbox_event"
                                        + " WHERE event_type = 'kyc.KycCaseOpened'"
                                        + " AND aggregate_id = ?")) {
            count.setObject(1, kycCase.id().value());
            try (ResultSet row = count.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static boolean inboxHandled(EventId eventId) {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT 1 FROM platform.inbox_message"
                                        + " WHERE consumer = 'kyc.caseOpening' AND dedupe_key = ?")) {
            select.setString(1, eventId.value().toString());
            try (ResultSet row = select.executeQuery()) {
                return row.next();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("could not read the inbox", failure);
        }
    }

    private double duplicateCount() {
        var counter = registry.find("finapp.inbox.consumption").tag("outcome", "duplicate").counter();
        return counter == null ? 0.0d : counter.count();
    }

    private static KafkaEventPublisher directPublisher() {
        return KafkaEventPublisher.connect(
                System.getProperty("finapp.kafka.bootstrap"), "PLAINTEXT", Duration.ofSeconds(10));
    }

    /** The registration's own {@code party.CustomerOpened} row, re-read so the bytes are real. */
    private static PendingEvent customerOpenedEventOf(UUID customerId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT event_id, event_version, schema_version, occurred_at,"
                                        + " correlation_id, causation_id, payload,"
                                        + " payload_media_type FROM platform.outbox_event"
                                        + " WHERE event_type = 'party.CustomerOpened'"
                                        + " AND aggregate_id = ?")) {
            select.setObject(1, customerId);
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).as("registration published CustomerOpened").isTrue();
                return new PendingEvent(
                        EventId.of(row.getObject("event_id", UUID.class)),
                        "party.CustomerOpened",
                        row.getInt("event_version"),
                        row.getInt("schema_version"),
                        customerId,
                        "Customer",
                        row.getTimestamp("occurred_at").toInstant(),
                        "party",
                        CorrelationId.of(row.getString("correlation_id")),
                        CausationId.of(row.getString("causation_id")),
                        row.getBytes("payload"),
                        row.getString("payload_media_type"),
                        0);
            }
        }
    }

    /** A CustomerOpened for a customer nobody registered — the race test's clean subject. */
    private static PendingEvent craftedCustomerOpened(EventId eventId, UUID customerId) {
        return new PendingEvent(
                eventId,
                "party.CustomerOpened",
                1,
                1,
                customerId,
                "Customer",
                Instant.now(),
                "party",
                CorrelationId.of(UUID.randomUUID().toString()),
                CausationId.of("race-probe"),
                "{\"customerId\":\"probe\"}".getBytes(StandardCharsets.UTF_8),
                "application/json",
                0);
    }

    /** Waits on the condition, never for a duration; the bound is generous (P1-TSK-002). */
    private static void await(BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(60);
        while (!condition.getAsBoolean() && Instant.now().isBefore(deadline)) {
            Thread.sleep(100);
        }
        assertThat(condition.getAsBoolean()).as("condition within the bound").isTrue();
    }
}
