package com.finapp.app.credential;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * MFA gates the password change of an identity that has a factor (`P1-TSK-033`).
 *
 * <h2>The plan's row is corrected, and this is where the correction is proven</h2>
 *
 * <p>{@code PHASE_1_PLAN.md} §7 reads {@code MULTI_FACTOR}; taken literally that makes the endpoint
 * unreachable for every password-only customer. The requirement is <em>conditional on whether a
 * factor exists</em> — a domain check, because a boundary annotation is static per handler
 * ({@code P1-TSK-019}). So an MFA-enrolled identity presenting a {@code PASSWORD} session is
 * refused {@code identity.AssuranceRequired} (actionable — step up and retry — so distinguished
 * from the uniform refusal), and a password-only identity changes at {@code PASSWORD}.
 *
 * <p>The confirmed factor is seeded directly: the assurance check only asks whether one exists
 * ({@code findActive(...).isPresent()}) and never decrypts it, so a minimal {@code ACTIVE} row is
 * the honest fixture — enrolling and confirming over HTTP would test the enrolment flow, which is
 * {@code MfaEndpointDatabaseTest}'s subject, not this one's.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the password-change endpoint under MFA (P1-TSK-033)")
class ChangePasswordMfaDatabaseTest {

    private static final String OLD = "the-original-password-8";
    private static final String NEW = "a-brand-new-password-9";
    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());

    @LocalServerPort private int port;

    @Test
    @DisplayName("an MFA-enrolled identity on a PASSWORD session is refused, and can step up")
    void mfaEnrolledRequiresMultiFactor() throws Exception {
        String login = someLogin();
        assertThat(register(login, OLD).statusCode()).isEqualTo(201);
        String passwordSession = tokenIn(authenticate(login, OLD).body());
        seedConfirmedFactor(login);

        HttpResponse<String> refused = changePassword(passwordSession, OLD, NEW);
        assertThat(refused.statusCode())
                .as("an MFA-enrolled identity must present a MULTI_FACTOR session")
                .isEqualTo(IdentityAssuranceStatus.ASSURANCE_REQUIRED);
        assertThat(refused.body())
                .as("actionable and distinct: step up and retry, not a uniform refusal")
                .contains("identity.AssuranceRequired");

        // Nothing changed - the old password still works.
        assertThat(authenticate(login, OLD).statusCode()).isEqualTo(201);
        assertThat(authenticate(login, NEW).statusCode()).isEqualTo(401);
    }

    /** The status ADR-0030 / P1-TSK-018 assign to "step up and retry". */
    private static final class IdentityAssuranceStatus {
        static final int ASSURANCE_REQUIRED = 403;
    }

    private void seedConfirmedFactor(String login) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement insert =
                        app.prepareStatement(
                                "INSERT INTO identity.mfa_enrolment"
                                    + " (id, identity_id, type, secret_ciphertext, secret_nonce,"
                                    + " key_version, algorithm, digits, period_seconds, status,"
                                    + " created_at, confirmed_at)"
                                    + " SELECT ?, i.id, 'TOTP', ?, ?, 1, 'SHA1', 6, 30, 'ACTIVE',"
                                    + " now() - interval '1 hour', now() - interval '1 hour'"
                                    + " FROM identity.identity i WHERE i.login_identifier = ?")) {
            // IDS.next(), not UUID.randomUUID(): MfaEnrolmentId validates UUIDv7 on read
            // (ADR-0013), and a v4 is refused as malformed - the P1-TSK-028 lesson.
            insert.setObject(1, IDS.next());
            insert.setBytes(2, new byte[32]); // never decrypted by the assurance check
            insert.setBytes(3, new byte[12]); // a 96-bit nonce, per the size constraint
            insert.setString(4, login);
            assertThat(insert.executeUpdate()).as("the identity must exist").isEqualTo(1);
        }
    }

    // -----------------------------------------------------------------

    private HttpResponse<String> changePassword(String token, String current, String next)
            throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/v1/me/credential"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + token)
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        "{\"currentPassword\":\"" + current
                                                + "\",\"newPassword\":\"" + next + "\"}"))
                        .build();
        return send(request);
    }

    private HttpResponse<String> register(String login, String password) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/v1/registrations"))
                        .header("Content-Type", "application/json")
                        .header(IdempotencyKeyHeader.NAME, UUID.randomUUID().toString())
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        "{\"loginIdentifier\":\"" + login
                                                + "\",\"displayName\":\"Ada Lovelace\","
                                                + "\"password\":\"" + password + "\"}"))
                        .build();
        return send(request);
    }

    private HttpResponse<String> authenticate(String login, String password) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/v1/authentications"))
                        .header("Content-Type", "application/json")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        "{\"loginIdentifier\":\"" + login
                                                + "\",\"password\":\"" + password + "\"}"))
                        .build();
        return send(request);
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
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
}
