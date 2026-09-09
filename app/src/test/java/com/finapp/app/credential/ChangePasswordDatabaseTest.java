package com.finapp.app.credential;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.api.IdempotencyKeyHeader;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * {@code POST /v1/me/credential}, over real HTTP (`P1-TSK-033`).
 *
 * <p>The endpoint composes machinery every part of which is already tested where it lives, so this
 * suite proves the composition and its boundary: that a change re-proves the current password,
 * that it kills other sessions and rotates the caller's own, that a wrong current password is a
 * throttled uniform refusal, and that ten instances changing one password concurrently produce one
 * new credential.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the password-change endpoint (P1-TSK-033)")
class ChangePasswordDatabaseTest {

    private static final String OLD = "the-original-password-8";
    private static final String NEW = "a-brand-new-password-9";

    @LocalServerPort private int port;

    @Test
    @DisplayName("a change kills other sessions, rotates the caller's own, and swaps the password")
    void aChangeReplacesEverything() throws Exception {
        String login = someLogin();
        assertThat(register(login, OLD).statusCode()).isEqualTo(201);

        String callerToken = tokenIn(authenticate(login, OLD).body());
        String otherToken = tokenIn(authenticate(login, OLD).body());
        assertThat(get("/v1/sessions", callerToken).statusCode()).isEqualTo(200);
        assertThat(get("/v1/sessions", otherToken).statusCode()).isEqualTo(200);

        HttpResponse<String> change = changePassword(callerToken, OLD, NEW);
        assertThat(change.statusCode()).isEqualTo(201);
        String rotated = tokenIn(change.body());

        // The caller's presented token is dead - rotation replaced it - and the response carries
        // the replacement, or the customer would be logged out by their own password change.
        assertThat(rotated).isNotBlank().isNotEqualTo(callerToken);
        assertThat(get("/v1/sessions", rotated).statusCode())
                .as("the rotated token is live")
                .isEqualTo(200);
        assertThat(get("/v1/sessions", callerToken).statusCode())
                .as("the pre-change token is dead - fixation defence (P1-TSK-015)")
                .isEqualTo(401);
        assertThat(get("/v1/sessions", otherToken).statusCode())
                .as("every OTHER session is dead too (INV-IDN-03)")
                .isEqualTo(401);

        // The password really changed: the old one no longer authenticates, the new one does.
        assertThat(authenticate(login, OLD).statusCode()).isEqualTo(401);
        assertThat(authenticate(login, NEW).statusCode()).isEqualTo(201);

        assertThat(auditSummaryFor(login, "SUCCEEDED"))
                .as("audited against the person, naming what changed and never the password")
                .contains("sessionsEnded=")
                .doesNotContain(OLD)
                .doesNotContain(NEW);
    }

    @Test
    @DisplayName("a wrong current password is a uniform 401, counted toward lockout")
    void aWrongCurrentPasswordIsRefusedAndCounted() throws Exception {
        String login = someLogin();
        assertThat(register(login, OLD).statusCode()).isEqualTo(201);
        String token = tokenIn(authenticate(login, OLD).body());

        HttpResponse<String> refused = changePassword(token, "not-the-current-pw", NEW);
        assertThat(refused.statusCode())
                .as("a failed re-proof is an authentication failure, not a validation error")
                .isEqualTo(401);
        assertThat(refused.body()).contains("identity.AuthenticationFailed").doesNotContain(NEW);
        assertThat(auditSummaryFor(login, "FAILED"))
                .as("the reason lives in the record, never the response")
                .contains("reason=wrongCurrentPassword");

        // COUNTED, and the counting is proven rather than asserted: a stolen session must not be an
        // unthrottled password oracle. The lockout threshold is 10 (LockoutPolicy.current), and MFA
        // and this endpoint share the account's budget - so ten wrong current passwords lock the
        // account, and then even the CORRECT current password is refused. That last step is what
        // catches a change that stops calling recordFailureFor: without the counter the account
        // never locks and the correct attempt succeeds.
        for (int i = 0; i < 9; i++) {
            assertThat(changePassword(token, "still-wrong-" + i, NEW).statusCode()).isEqualTo(401);
        }
        HttpResponse<String> whileLocked = changePassword(token, OLD, NEW);
        assertThat(whileLocked.statusCode())
                .as("the correct current password is refused once the budget is spent - a lock a"
                        + " correct guess cleared would signal the guess was right (P1-TSK-011)")
                .isEqualTo(401);
        assertThat(auditSummaryFor(login, "FAILED"))
                .as("the lock is the durable trace that the counter drove it")
                .contains("reason=locked");

        // Nothing changed: the old password still works (after the lock clears it would; here we
        // assert the new one never took), and the new one does not authenticate.
        assertThat(authenticate(login, NEW).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("a new password outside the length bound is a 422 naming the field")
    void aBadNewPasswordIsAValidationError() throws Exception {
        String login = someLogin();
        assertThat(register(login, OLD).statusCode()).isEqualTo(201);
        String token = tokenIn(authenticate(login, OLD).body());

        HttpResponse<String> refused = changePassword(token, OLD, "short");
        assertThat(refused.statusCode()).isEqualTo(422);
        assertThat(refused.body()).contains("api.ValidationFailed").contains("newPassword");
        assertThat(authenticate(login, OLD).statusCode())
                .as("the caller can correct it - nothing changed")
                .isEqualTo(201);
    }

    @Test
    @DisplayName("both endpoints refuse without a session")
    void refusesWithoutASession() throws Exception {
        assertThat(
                        changeRaw(null, "{\"currentPassword\":\"" + OLD + "\",\"newPassword\":\""
                                        + NEW + "\"}")
                                .statusCode())
                .isEqualTo(401);
    }

    @Test
    @DisplayName("ten instances changing one password produce exactly one new credential")
    void concurrentChangesProduceOneCredential() throws Exception {
        String login = someLogin();
        assertThat(register(login, OLD).statusCode()).isEqualTo(201);

        int instances = 10;
        // Each racer holds its own session, all against the one identity - ten instances of the
        // same person clicking "change password". The conditional supersede is the arbiter.
        List<String> tokens =
                IntStream.range(0, instances)
                        .mapToObj(i -> tokenIn(authenticateQuietly(login, OLD)))
                        .toList();

        ExecutorService pool = Executors.newFixedThreadPool(instances);
        CyclicBarrier start = new CyclicBarrier(instances);
        AtomicInteger created = new AtomicInteger();
        try {
            List<Callable<Integer>> racers =
                    IntStream.range(0, instances)
                            .<Callable<Integer>>mapToObj(
                                    i ->
                                            () -> {
                                                start.await();
                                                int code =
                                                        changePassword(tokens.get(i), OLD, NEW)
                                                                .statusCode();
                                                if (code == 201) {
                                                    created.incrementAndGet();
                                                }
                                                return code;
                                            })
                            .toList();
            for (Future<Integer> outcome : pool.invokeAll(racers)) {
                outcome.get();
            }
        } finally {
            pool.shutdownNow();
        }

        // At most one 201: the winner. The rest lost the supersede race or found the credential
        // already replaced, and each got a uniform 401 - never a second new credential.
        assertThat(created.get())
                .as("the conditional supersede admits exactly one winner")
                .isLessThanOrEqualTo(1);
        assertThat(activeCredentialCount(login))
                .as("exactly one active credential survives, whoever won")
                .isEqualTo(1);
        assertThat(authenticate(login, NEW).statusCode())
                .as("and it is the new one")
                .isEqualTo(201);
    }

    // -----------------------------------------------------------------

    private HttpResponse<String> changePassword(String token, String current, String next)
            throws Exception {
        return changeRaw(
                token,
                "{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\"}");
    }

    private HttpResponse<String> changeRaw(String token, String body) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/v1/me/credential"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        try (HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private HttpResponse<String> register(String login, String password) throws Exception {
        return post(
                "/v1/registrations",
                "{\"loginIdentifier\":\""
                        + login
                        + "\",\"displayName\":\"Ada Lovelace\",\"password\":\""
                        + password
                        + "\"}",
                UUID.randomUUID().toString());
    }

    private HttpResponse<String> authenticate(String login, String password) throws Exception {
        return post(
                "/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + password + "\"}",
                null);
    }

    private String authenticateQuietly(String login, String password) {
        try {
            return authenticate(login, password).body();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private HttpResponse<String> post(String path, String body, String key) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) {
            request.header(IdempotencyKeyHeader.NAME, key);
        }
        try (HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build();
        try (HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static String tokenIn(String body) {
        String marker = "\"sessionToken\":\"";
        int start = body.indexOf(marker);
        if (start < 0) {
            return "";
        }
        start += marker.length();
        return body.substring(start, body.indexOf('"', start));
    }

    private static String someLogin() {
        return "ada." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String auditSummaryFor(String login, String outcome) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT coalesce(string_agg(change_summary, ' '), '')"
                                        + " FROM platform.audit_record r"
                                        + " JOIN identity.identity i ON i.id = r.target_id::uuid"
                                        + " WHERE r.operation = 'identity.CredentialChanged'"
                                        + " AND r.outcome = ?"
                                        + " AND i.login_identifier = ?")) {
            select.setString(1, outcome);
            select.setString(2, login);
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    private static long activeCredentialCount(String login) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT count(*) FROM identity.credential c"
                                        + " JOIN identity.identity i ON i.id = c.identity_id"
                                        + " WHERE i.login_identifier = ? AND c.status = 'ACTIVE'")) {
            select.setString(1, login);
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }
}
