package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Authenticator;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.identity.TotpParameters;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.security.Sensitive;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The operator's HTTP fixtures for the risk desks' database suites (`P13-TSK-018`; `-016`'s {@code RiskCaseApiTest}
 * shape, shared by {@code QueueApiTest} and {@code AlertSuppressionApiTest}): an identity registered over the real
 * endpoint and assigned a role, a session - password only, or stepped up through a factor confirmed now - and the
 * requests, each {@code POST} under a fresh idempotency key unless one is named. The tables are read as the
 * application.
 */
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
final class RiskOperatorSessions {

    static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    static final String PASSWORD = "a-perfectly-fine-pw-7";

    /** One operator: the session's token, the party and identity, the login. */
    record Session(String token, UUID party, UUID identity, String login) {}

    private final int port;
    private final Authorization authorization;

    RiskOperatorSessions(int port, Authorization authorization) {
        this.port = port;
        this.authorization = authorization;
    }

    /** A fresh identity holding {@code role}, signed in - stepped up when {@code multiFactor}. */
    Session sessionWith(RoleName role, boolean multiFactor) throws Exception {
        String login = "rq." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(post("/v1/registrations", "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                + "\"password\":\"" + PASSWORD + "\"}", null).statusCode()).isEqualTo(201);
        UUID identity;
        UUID party;
        try (Connection app = DatabaseRoles.application(); PreparedStatement read = app.prepareStatement(
                "SELECT id, party_id FROM identity.identity WHERE login_identifier = ?")) {
            read.setString(1, login);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                identity = row.getObject("id", UUID.class);
                party = row.getObject("party_id", UUID.class);
            }
        }
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
            app.commit();
        }
        return sessionOf(new Session(null, party, identity, login), multiFactor);
    }

    /** A fresh session of {@code who} - password only, or stepped up through a factor confirmed now. */
    Session sessionOf(Session who, boolean multiFactor) throws Exception {
        HttpResponse<String> session = post("/v1/authentications",
                "{\"loginIdentifier\":\"" + who.login() + "\",\"password\":\"" + PASSWORD + "\"}", null);
        String token = field(session.body(), "sessionToken");
        if (!multiFactor) {
            return new Session(token, who.party(), who.identity(), who.login());
        }
        Matcher secret = Pattern.compile("secret=([A-Z2-7]+)").matcher(post("/v1/me/mfa", null, token).body());
        assertThat(secret.find()).as("the response must carry a base32 secret").isTrue();
        Sensitive<String> factor = Sensitive.of(secret.group(1));
        long step = Instant.now().getEpochSecond() / TotpParameters.current().periodSeconds();
        long confirmed = -1;
        for (long candidate : new long[] {step - 1, step, step + 1}) {
            if (post("/v1/me/mfa/confirmation", "{\"code\":\"" + codeAt(factor, candidate) + "\"}", token)
                    .statusCode() == 204) {
                confirmed = candidate;
                break;
            }
        }
        assertThat(confirmed).as("some step around this JVM's confirms the factor").isNotNegative();
        HttpResponse<String> elevated = post("/v1/authentications/mfa", "{\"code\":\"" + codeAt(factor, confirmed + 1)
                + "\"}", token);
        if (elevated.statusCode() == 401) {
            elevated = post("/v1/authentications/mfa", "{\"code\":\"" + codeAt(factor, confirmed + 2) + "\"}", token);
        }
        assertThat(elevated.statusCode()).as("the step-up: %s", elevated.body()).isEqualTo(200);
        return new Session(field(elevated.body(), "sessionToken"), who.party(), who.identity(), who.login());
    }

    private static String codeAt(Sensitive<String> secret, long step) {
        return Authenticator.codeAt(secret, TotpParameters.current(),
                Instant.ofEpochSecond(step * TotpParameters.current().periodSeconds()));
    }

    HttpResponse<String> post(String path, String body, String bearer) throws Exception {
        return post(path, body, bearer, UUID.randomUUID().toString());
    }

    HttpResponse<String> post(String path, String body, String bearer, String key) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header(IdempotencyKeyHeader.NAME, key)
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path)).GET();
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    static long count(String sql) throws Exception {
        try (Connection app = DatabaseRoles.application(); PreparedStatement select = app.prepareStatement(sql);
                ResultSet row = select.executeQuery()) {
            row.next();
            return row.getLong(1);
        }
    }

    static String field(String body, String name) {
        Matcher matcher = Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(body);
        assertThat(matcher.find()).as("%s in %s", name, body).isTrue();
        return matcher.group(1);
    }

    /** {@code work} in one committed transaction on the application role's connection. */
    static <T> T inOneTransaction(Function<Connection, T> work) {
        try (Connection connection = DatabaseRoles.application()) {
            connection.setAutoCommit(false);
            try {
                T result = work.apply(connection);
                connection.commit();
                return result;
            } catch (RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
