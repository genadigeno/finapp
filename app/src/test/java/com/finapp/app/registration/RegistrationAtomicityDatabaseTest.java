package com.finapp.app.registration;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.event.EventEnvelope;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The acceptance criterion: a registration commits entirely or not at all (`P1-TSK-006`).
 *
 * <h2>Why this test is the one that matters</h2>
 *
 * <p>Every other assertion in this slice is about the happy path or a domain refusal, and all of
 * them would pass against an implementation that opened a second connection for one of its writes.
 * The atomicity guarantee is invisible from outside until something fails halfway - and by then it
 * has produced a Party with no Identity, or an Identity referencing a Party that does not exist,
 * which is precisely the orphan ADR-0029's missing foreign key cannot detect.
 *
 * <p>So the failure is injected at the <strong>last</strong> write of the command, after all three
 * rows, both audit records and two of the three events are already on the connection. If any of
 * those had gone to a transaction of its own, it would survive; nothing may.
 *
 * <h2>A test double, not a weakened control</h2>
 *
 * <p>{@code .claude/rules/security.md}: do not weaken security controls to simplify tests, use safe
 * test doubles instead. {@link FailingAtTheLastEvent} <em>delegates</em> to the real
 * {@link JdbcOutboxWriter} and adds a throw; no privilege is widened, no constraint is dropped and
 * no invariant is relaxed to make the test possible.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RegistrationAtomicityDatabaseTest {

    @LocalServerPort private int port;

    @Autowired private DataSource dataSource;

    @Autowired private FailingAtTheLastEvent outbox;

    @BeforeEach
    void resetTheDouble() {
        outbox.reset();
    }

    @Test
    @DisplayName("a failure part-way leaves no Party, Customer, Identity, audit record or event")
    void nothingSurvivesAPartialFailure() throws Exception {
        String login = someLogin();
        long eventsBefore = outboxEvents();
        outbox.failOnTheNextRegistration();

        HttpResponse<String> response = register(login, aKey());

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body())
                .as("the client is told nothing about the cause (INV-AUD-02)")
                .contains("api.InternalError")
                .doesNotContain("outbox")
                .doesNotContain(login);

        assertThat(outbox.writes())
                .as("the failure was injected after two events had already been written")
                .isEqualTo(3);

        assertThat(countIdentities(login)).as("no Identity").isZero();
        assertThat(countPartiesFor(login)).as("no Party").isZero();
        assertThat(countAuditRecords(login)).as("no audit record").isZero();
        assertThat(outboxEvents())
                .as("no event: the two already written went back with everything else")
                .isEqualTo(eventsBefore);
    }

    @Test
    @DisplayName("the idempotency claim rolls back too, so the retry is a real attempt")
    void theClaimDoesNotOutliveTheEffect() throws Exception {
        String login = someLogin();
        String key = aKey();
        outbox.failOnTheNextRegistration();

        assertThat(register(login, key).statusCode()).isEqualTo(500);

        assertThat(countIdempotencyRecords(key))
                .as(
                        "a claim that outlived a rolled-back command would block the key for work"
                            + " that never happened, and the client could never retry")
                .isZero();

        // The proof that it is genuinely retryable: the same key, the same request, and this time
        // nothing fails.
        assertThat(register(login, key).statusCode()).isEqualTo(201);
        assertThat(countIdentities(login)).isEqualTo(1);
    }

    @Test
    @DisplayName("an instance whose connection dies mid-transaction leaves nothing behind")
    void nothingSurvivesACrash() throws Exception {
        // The crash case, made deterministic: the double terminates its own backend rather than a
        // test guessing which backend to kill from pg_stat_activity. From the database's point of
        // view this is exactly a pod being killed with an open transaction - the server rolls back
        // work it never saw committed.
        String login = someLogin();
        outbox.killTheConnectionOnTheNextRegistration();

        HttpResponse<String> response = register(login, aKey());

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(countIdentities(login)).isZero();
        assertThat(countPartiesFor(login)).isZero();
        assertThat(countAuditRecords(login)).isZero();
    }

    @Test
    @DisplayName("with the double idle, registration is unaffected - the control is not the cause")
    void theDoubleIsInertUntilArmed() throws Exception {
        // Without this, every assertion above would also pass against a double that broke
        // registration permanently, and the suite would be proving nothing about rollback.
        String login = someLogin();

        assertThat(register(login, aKey()).statusCode()).isEqualTo(201);
        assertThat(countIdentities(login)).isEqualTo(1);
    }

    // -----------------------------------------------------------------

    @TestConfiguration
    static class Doubles {

        @Bean
        @Primary
        FailingAtTheLastEvent failingOutboxWriter() {
            return new FailingAtTheLastEvent();
        }
    }

    /**
     * The real outbox writer, with a fault that can be armed for one registration.
     *
     * <p>It fails on the <strong>third</strong> write, which is the last thing a successful
     * registration does. Everything the command produces is on the connection by then.
     */
    static final class FailingAtTheLastEvent implements OutboxWriter<Connection> {

        private static final int LAST_EVENT_OF_A_REGISTRATION = 3;

        private final JdbcOutboxWriter delegate = new JdbcOutboxWriter();
        private final AtomicInteger writes = new AtomicInteger();
        private volatile Fault armed = Fault.NONE;

        enum Fault {
            NONE,
            THROW,
            KILL_THE_CONNECTION
        }

        void reset() {
            writes.set(0);
            armed = Fault.NONE;
        }

        void failOnTheNextRegistration() {
            armed = Fault.THROW;
        }

        void killTheConnectionOnTheNextRegistration() {
            armed = Fault.KILL_THE_CONNECTION;
        }

        int writes() {
            return writes.get();
        }

        @Override
        public void write(
                Connection unitOfWork, EventEnvelope envelope, byte[] payload, String mediaType) {

            delegate.write(unitOfWork, envelope, payload, mediaType);
            int written = writes.incrementAndGet();
            if (written < LAST_EVENT_OF_A_REGISTRATION || armed == Fault.NONE) {
                return;
            }
            // One-shot. Disarmed before it fires, so a test can prove the retry after a failure
            // succeeds - which is the only way to show the idempotency claim really rolled back
            // rather than merely being absent from a query.
            Fault firing = armed;
            armed = Fault.NONE;
            if (firing == Fault.KILL_THE_CONNECTION) {
                terminateOwnBackend(unitOfWork);
            }
            throw new IllegalStateException("Injected failure at the last event of a registration");
        }

        private static void terminateOwnBackend(Connection unitOfWork) {
            try (Statement statement = unitOfWork.createStatement()) {
                statement.execute("SELECT pg_terminate_backend(pg_backend_pid())");
            } catch (SQLException expected) {
                // Killing your own backend is expected to fail the statement that did it.
            }
        }
    }

    private HttpResponse<String> register(String login, String key) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/registrations"))
                        .timeout(Duration.ofSeconds(60))
                        .header("Content-Type", "application/json")
                        .header(IdempotencyKeyHeader.NAME, key)
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        "{\"loginIdentifier\":\""
                                                + login
                                                + "\",\"displayName\":\"Ada Lovelace\""
                                                + ",\"password\":\"not-a-real-password\"}"))
                        .build();
        try (HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static String someLogin() {
        return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private static String aKey() {
        return UUID.randomUUID().toString();
    }

    private int countIdentities(String login) throws SQLException {
        return count("SELECT count(*) FROM identity.identity WHERE login_identifier = ?", login);
    }

    private int countPartiesFor(String login) throws SQLException {
        return count(
                "SELECT count(*) FROM party.party p JOIN identity.identity i ON i.party_id = p.id"
                        + " WHERE i.login_identifier = ?",
                login);
    }

    private int countAuditRecords(String login) throws SQLException {
        return count("SELECT count(*) FROM platform.audit_record WHERE target_id = ?", login);
    }

    /**
     * Every outbox row, not the ones naming this login.
     *
     * <p>A payload never carries a login identifier - that is the point of {@code EventPayload} -
     * so a query filtered by it would return zero whether or not the rollback worked, and the
     * assertion would be vacuous. The total is the only honest measure here.
     */
    private long outboxEvents() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT count(*) FROM platform.outbox_event");
                ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private int countIdempotencyRecords(String key) throws SQLException {
        return count("SELECT count(*) FROM platform.idempotency_record WHERE idempotency_key = ?", key);
    }

    private int count(String sql, String argument) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, argument);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }
}
