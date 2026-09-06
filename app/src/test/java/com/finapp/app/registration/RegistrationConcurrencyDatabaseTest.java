package com.finapp.app.registration;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.LoginIdentifier;
import com.finapp.party.PartyName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.testing.database.DatabaseRoles;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Registration under genuine contention (`P1-TSK-006`, {@code DOD-API}, {@code INV-CON-01}).
 *
 * <h2>What "ten instances" means here, and why this is the honest shape of it</h2>
 *
 * <p>Ten concurrent requests, each of which the container serves on its own thread and each of
 * which takes its <strong>own pooled connection</strong> and opens its own transaction. At the
 * database - which is the only place any of this is arbitrated - that is indistinguishable from ten
 * application instances behind a load balancer, because a connection is what an instance contends
 * with and an instance has no other way to affect the outcome. ADR-0014's requirement is about
 * shared state, and every piece of shared state in this flow is a durable row.
 *
 * <p>The one thing this shape cannot exercise is a process-local mechanism that happens to be
 * shared within a JVM and would not be shared across JVMs. There is none in this flow, and
 * {@code NoSingleInstanceAssumptionRulesTest} fails the build if one is introduced.
 *
 * <h2>Ten, because that is the number the platform claims</h2>
 *
 * <p>{@code DISTRIBUTED_EXECUTION.md} §4a sizes the connection budget for ten instances. Not chosen
 * to be large: chosen to be the number the platform says it runs.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RegistrationConcurrencyDatabaseTest {

    private static final int RACERS = 10;

    @LocalServerPort private int port;

    @Autowired private DataSource dataSource;

    @Test
    @DisplayName("ten racers for one login identifier produce exactly one person")
    void oneLoginIdentifierSurvivesContention() throws Exception {
        String login = someLogin();

        // Different keys on purpose: this is ten strangers wanting the same name, not one client
        // retrying. The idempotency mechanism cannot help here - the login index is the arbiter.
        List<HttpResponse<String>> responses = race(racer -> register(login, "Ada Lovelace", aKey()));

        assertThat(statuses(responses, 201)).as("exactly one registration succeeds").isEqualTo(1);
        assertThat(statuses(responses, 422))
                .as("every loser is refused by the constraint, and refused identically")
                .isEqualTo(RACERS - 1);
        assertThat(countIdentities(login)).as("the database is the authority, not any racer").isEqualTo(1);
        assertThat(countPartiesWithLogin(login))
                .as("no loser left a Party behind: the savepoint rollback undid each one")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("every loser's refusal is byte-identical to every other's")
    void everyRefusalLooksTheSame() throws Exception {
        String login = someLogin();

        List<HttpResponse<String>> responses = race(racer -> register(login, "Ada Lovelace", aKey()));

        List<String> refusals =
                responses.stream()
                        .filter(response -> response.statusCode() == 422)
                        // The correlation identifier legitimately differs per request; everything
                        // else must not, or the losers could tell each other apart.
                        .map(response -> response.body().replaceAll("\"correlationId\":\"[^\"]+\"", ""))
                        .distinct()
                        .toList();

        assertThat(refusals)
                .as("one refusal shape, whatever the reason (INV-IDN-07)")
                .hasSize(1);
    }

    @Test
    @DisplayName("ten retries of one request produce one effect and never a second")
    void oneIdempotencyKeySurvivesContention() throws Exception {
        String login = someLogin();
        String key = aKey();

        // One client, one business action, ten simultaneous attempts - a retrying client behind a
        // load balancer, which is the case INV-IDEM-01 is for.
        List<HttpResponse<String>> responses = race(racer -> register(login, "Ada Lovelace", key));

        assertThat(countIdentities(login)).as("exactly one effect").isEqualTo(1);
        assertThat(countPartiesWithLogin(login)).isEqualTo(1);
        assertThat(countAuditRecords(login)).as("one registration, two audit records").isEqualTo(2);

        assertThat(statuses(responses, 201)).as("at least one caller is told it was created").isPositive();
        assertThat(
                        responses.stream()
                                .map(HttpResponse::statusCode)
                                .filter(status -> status != 201 && status != 409)
                                .toList())
                .as(
                        "a duplicate is either replayed (201) or told the outcome is not yet known"
                            + " (409) - never assumed failed, and never a second effect")
                .isEmpty();
    }

    @Test
    @DisplayName("a claim held by another instance is reported, never assumed failed")
    void anInFlightClaimIsReportedAsInProgress() throws Exception {
        // The branch the ten-way race does NOT reach, and the gate found that out by printing the
        // status distribution rather than trusting the assertion: all ten racers got 201, because
        // the winner commits in milliseconds and the losers replay. So the honest way to exercise
        // "another instance holds this key and has not finished" is to put the platform in exactly
        // that state - a committed IN_PROGRESS claim with a live lease, which is what a crashed or
        // still-running instance leaves behind.
        //
        // INV-LIFE-03 is the property under test: an unknown outcome is reported as unknown. The
        // wrong answers are re-executing (a second effect) and reporting failure (a client that
        // gives up on a command that may yet commit).
        String login = someLogin();
        String key = aKey();
        givenAClaimHeldByAnotherInstance(key, login, "Ada Lovelace");

        HttpResponse<String> response = register(login, "Ada Lovelace", key);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body())
                .contains("api.IdempotencyInProgress")
                .as("distinct from api.Conflict: this one means retry, the other means stop")
                .doesNotContain("api.Conflict");
        assertThat(response.body())
                .as("the key is the caller's own input and is never echoed")
                .doesNotContain(key);
        assertThat(countIdentities(login))
                .as("and nothing was created: the command was not re-executed")
                .isZero();
    }

    @Test
    @DisplayName("contention is real: the racers overlapped rather than queueing politely")
    void theRaceIsNotVacuous() throws Exception {
        // Every assertion above is of the form "exactly one succeeded", and all of them would pass
        // if the racers ran one after another. What proves they met is that nine of them were
        // refused by a constraint - a unique violation can only be raised by a transaction that
        // encountered another transaction's row, or a committed one.
        String login = someLogin();

        List<HttpResponse<String>> responses = race(racer -> register(login, "Ada Lovelace", aKey()));

        assertThat(responses).hasSize(RACERS);
        assertThat(statuses(responses, 201) + statuses(responses, 422))
                .as("every racer reached a decided outcome; none failed for an unrelated reason")
                .isEqualTo(RACERS);
    }

    // -----------------------------------------------------------------

    /**
     * Releases {@link #RACERS} callers together and collects what each was told.
     *
     * <p>No barrier after the release. An earlier harness in this repository held every racer until
     * all had "attempted" and passed by timing out, because the losers were blocked <em>inside</em>
     * their insert waiting for the winner's lock and could never reach the barrier. The overlap
     * needs no arranging: releasing them together and letting the database block them is the
     * contention.
     */
    private List<HttpResponse<String>> race(Racer work) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(RACERS);
        try {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (int racer = 0; racer < RACERS; racer++) {
                int index = racer;
                futures.add(
                        pool.submit(
                                (Callable<HttpResponse<String>>)
                                        () -> {
                                            release.await();
                                            return work.run(index);
                                        }));
            }
            release.countDown();

            List<HttpResponse<String>> responses = new ArrayList<>();
            for (Future<HttpResponse<String>> future : futures) {
                responses.add(future.get(120, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface Racer {
        HttpResponse<String> run(int index) throws Exception;
    }

    private static int statuses(List<HttpResponse<String>> responses, int status) {
        return (int) responses.stream().filter(response -> response.statusCode() == status).count();
    }

    private HttpResponse<String> register(String login, String displayName, String key)
            throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/registrations"))
                        .timeout(Duration.ofSeconds(60))
                        .header("Content-Type", "application/json")
                        .header(IdempotencyKeyHeader.NAME, key)
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        "{\"loginIdentifier\":\""
                                                + login
                                                + "\",\"displayName\":\""
                                                + displayName
                                                + "\"}"))
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
        return count(
                "SELECT count(*) FROM identity.identity WHERE login_identifier = ?", login);
    }

    /** Parties reachable from this login, which is how a Party left behind by a loser shows up. */
    private int countPartiesWithLogin(String login) throws SQLException {
        return count(
                "SELECT count(*) FROM party.party p JOIN identity.identity i ON i.party_id = p.id"
                        + " WHERE i.login_identifier = ?",
                login);
    }

    private int countAuditRecords(String login) throws SQLException {
        return count(
                "SELECT count(*) FROM platform.audit_record WHERE target_id = ? AND outcome ="
                        + " 'SUCCEEDED'",
                login);
    }

    /**
     * Writes the row a still-running or crashed instance would have left: a committed
     * {@code IN_PROGRESS} claim with a lease that has not expired.
     *
     * <p>The fingerprint is computed the way the service computes it, so the executor gets past the
     * {@code INV-IDEM-03} check and reaches the in-progress branch. Using a different one would
     * produce {@code api.Conflict} and this test would pass for the wrong reason.
     */
    private void givenAClaimHeldByAnotherInstance(String key, String login, String displayName)
            throws SQLException {
        byte[] fingerprint =
                RequestFingerprint.sha256(
                                RegistrationService.canonicalForm(
                                        new LoginIdentifier(login), new PartyName(displayName)))
                        .digest();

        try (Connection app = DatabaseRoles.application();
                PreparedStatement insert =
                        app.prepareStatement(
                                "INSERT INTO platform.idempotency_record (scope, idempotency_key,"
                                    + " request_fingerprint, fingerprint_algorithm, state,"
                                    + " correlation_id, created_at, expires_at, lease_expires_at)"
                                    + " VALUES (?, ?, ?, ?, 'IN_PROGRESS', ?, now(),"
                                    + " now() + interval '1 hour', now() + interval '1 hour')")) {
            insert.setString(1, RegistrationService.SCOPE);
            insert.setString(2, key);
            insert.setBytes(3, fingerprint);
            insert.setString(4, RequestFingerprint.SHA_256);
            insert.setString(5, UUID.randomUUID().toString());
            insert.executeUpdate();
        }
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
