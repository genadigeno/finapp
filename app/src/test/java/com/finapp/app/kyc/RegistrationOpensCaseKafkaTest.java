package com.finapp.app.kyc;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.kyc.JdbcKycCaseStore;
import com.finapp.kyc.KycCase;
import com.finapp.kyc.KycCaseKind;
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
 * The case-opening consumer through the real broker — `P2-TSK-007`'s chain, under
 * `P2-TSK-019`'s gate.
 *
 * <p>The application is booted whole, with the relay <em>and</em> the consumer enabled — the two
 * background workers every other test suite deliberately disables — so the chain under test is
 * the deployed one: HTTP registration → outbox → relay schedule → Kafka → consumer loop → inbox
 * → gate → case row, with no call from the test anywhere in the middle.
 *
 * <h2>`P2-TSK-007`'s headline changed when the gate arrived, and the change is the point</h2>
 *
 * <p>It read <em>"registration alone yields exactly one open case"</em>. Opening a case is now
 * consent-gated ({@code INV-CNS-01}), and a freshly registered person cannot yet hold a grant —
 * so registration alone yields <strong>no</strong> case, consumed and acknowledged, and that
 * refusal is the milestone's acceptance working at the eager door. A party <em>with</em> a basis
 * yields exactly one case, audited and announced, exactly as before. Every other property the
 * suite held — the inbox absorbing duplicates, distinct events converging silently, the race
 * against the direct open — keeps its test, on consented fixtures.
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
@DisplayName("a consented registration opens a case through the real broker (P2-TSK-007/-019)")
class RegistrationOpensCaseKafkaTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String PASSWORD = "a-long-enough-password-for-kyc";

    @LocalServerPort private int port;

    @Autowired private MeterRegistry registry;

    private final HttpClient http = HttpClient.newHttpClient();
    private final KycCaseStore<Connection> cases = new JdbcKycCaseStore();

    /**
     * The gate at the eager door, end to end — and the consented open landing after it.
     *
     * <p>The first half drives the WHOLE deployed chain: registration over HTTP, the relay, the
     * broker, the consumer — and asserts the event was <strong>consumed</strong> (inbox row
     * written, acknowledged, nothing stalled) with <strong>no case, no audit record and no
     * announcement</strong>, because the person has not granted. The second half grants for the
     * party and redelivers a distinct event — the consumer's answer for a now-consented party —
     * and exactly one case lands, audited and announced.
     */
    @Test
    @DisplayName("registration alone opens nothing; the same party consented opens exactly one")
    void theGateHoldsTheEagerDoorAndAConsentedOpenLands() throws Exception {
        UUID customerId = registerSomebody();
        EventId registrationEvent = customerOpenedEventOf(customerId).eventId();

        await(() -> inboxHandled(registrationEvent));

        assertThat(caseCountFor(customerId))
                .as("no basis, no case: the gate at the eager door (INV-CNS-01), and the event"
                        + " is acknowledged rather than stalled - a refusal is a domain outcome,"
                        + " not a poison record")
                .isZero();
        assertThat(auditCount(customerId)).as("nothing happened, so nothing is recorded").isZero();

        // The person grants - and a later distinct delivery opens the case. This is also the
        // recovery shape: the eager skip is not a dead end, because "ensure my case exists"
        // converges from whichever door asks next.
        grantConsentFor(partyOf(customerId));
        try (KafkaEventPublisher publisher = directPublisher()) {
            publisher.publish(craftedCustomerOpened(EventId.next(IDS), customerId));
        }
        await(() -> caseCountFor(customerId) >= 1);

        assertThat(caseCountFor(customerId)).isEqualTo(1);
        KycCase kycCase = openCaseFor(customerId);
        assertThat(kycCase.status().name()).isEqualTo("OPEN");
        assertThat(auditCount(customerId))
                .as("kyc.CaseOpened - against the customer, by the platform")
                .isEqualTo(1);
        assertThat(announcementCount(kycCase))
                .as("and the fact is announced: kyc.KycCaseOpened on the outbox, in the same"
                        + " transaction as the case (INV-EVT-01)")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the same event delivered again dies in the inbox: still one case")
    void aDuplicateDeliveryIsOneCase() throws Exception {
        UUID customerId = givenAConsentedCustomer();
        PendingEvent event = craftedCustomerOpened(EventId.next(IDS), customerId);
        try (KafkaEventPublisher publisher = directPublisher()) {
            publisher.publish(event);
        }
        await(() -> caseCountFor(customerId) >= 1);

        double duplicatesBefore = duplicateCount();
        try (KafkaEventPublisher publisher = directPublisher()) {
            // The SAME event, byte for byte and eventId for eventId, straight to the broker -
            // at-least-once delivery doing what it is allowed to do (P2-TSK-001 demonstrated
            // the relay really produces this).
            publisher.publish(event);
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
        UUID customerId = givenAConsentedCustomer();
        try (KafkaEventPublisher publisher = directPublisher()) {
            publisher.publish(craftedCustomerOpened(EventId.next(IDS), customerId));
        }
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
        // A consented customer created by fixture SQL rather than by registration, so no
        // registration event exists and only this test's two racing paths can open the case:
        // the consumer (fed directly through the broker) and the direct openOrConverge - the
        // mechanism POST /v1/me/kyc will use. The rows themselves must exist since P2-TSK-015:
        // the consumer resolves the case KIND (and now the consent basis, P2-TSK-019) from the
        // customer's rows, and an event naming a customer with no row is a broken invariant it
        // refuses loudly - stalling the partition by the block-don't-skip design.
        UUID customerId = givenAConsentedCustomer();
        EventId eventId = EventId.next(IDS);
        try (KafkaEventPublisher publisher = directPublisher()) {
            publisher.publish(craftedCustomerOpened(eventId, customerId));
        }
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            cases.openOrConverge(app, KycCase.open(IDS, Clock.systemUTC(), customerId, KycCaseKind.KYC));
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

    private static UUID partyOf(UUID customerId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT party_id FROM party.customer WHERE id = ?")) {
            select.setObject(1, customerId);
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).as("the customer has a party").isTrue();
                return row.getObject(1, UUID.class);
            }
        }
    }

    /** A current KYC_PROCESSING grant for the party — what the gate reads (P2-TSK-019). */
    private static void grantConsentFor(UUID partyId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement insert =
                        app.prepareStatement(
                                "INSERT INTO consent.consent_record"
                                        + " (id, party_id, purpose, action, text_version,"
                                        + " recorded_at) VALUES (?, ?, 'KYC_PROCESSING',"
                                        + " 'GRANT', 1, now())")) {
            insert.setObject(1, IDS.next());
            insert.setObject(2, partyId);
            insert.executeUpdate();
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

    /** A crafted CustomerOpened: a DISTINCT event (fresh eventId), never a wire duplicate. */
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

    /**
     * A PERSON party, a PENDING customer and a current KYC_PROCESSING grant, inserted directly
     * - rows without a registration, so nothing rides the outbox and no third opener exists.
     * In production a {@code party.CustomerOpened} commits in the same transaction as the
     * customer row, so the consumer's resolutions always find their rows; the fixture keeps
     * that invariant true, and the grant is what lets the gated open proceed (P2-TSK-019).
     */
    private static UUID givenAConsentedCustomer() throws SQLException {
        UUID party = IDS.next();
        UUID customer = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            try (PreparedStatement insert =
                    app.prepareStatement(
                            "INSERT INTO party.party (id, kind, display_name, registered_at)"
                                    + " VALUES (?, 'PERSON', 'Grace Hopper', now())")) {
                insert.setObject(1, party);
                insert.executeUpdate();
            }
            try (PreparedStatement insert =
                    app.prepareStatement(
                            "INSERT INTO party.customer (id, party_id, status, opened_at,"
                                    + " status_changed_at) VALUES (?, ?, 'PENDING',"
                                    + " now() - interval '1 hour', now() - interval '1 hour')")) {
                insert.setObject(1, customer);
                insert.setObject(2, party);
                insert.executeUpdate();
            }
        }
        grantConsentFor(party);
        return customer;
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
