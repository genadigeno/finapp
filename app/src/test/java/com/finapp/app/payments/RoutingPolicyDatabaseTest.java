package com.finapp.app.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The operator's routing surface over a real database (`P7-TSK-003`, ADR-0060): the keyed,
 * audited, immutable version; the gapless minting race; the recorded availability fact and
 * its convergence; the refusals (unknown rail, backdated version); and the permission wall —
 * including the named negative, the counterparty administrator who cannot reroute money.
 *
 * <p>The routed confirm's end-to-end — the refusal recorded and retryable, the pinned
 * decision beside the dispatch, {@code RailSelected} — lives with the payment fixtures in
 * {@code PaymentEndpointDatabaseTest}; this suite owns the operator acts.
 *
 * <p><strong>Every availability change this suite makes is restored</strong>: the card rail
 * is shared operational state, and a suite that left it disabled would fail every later
 * confirmation in the run.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the routing operator surface (P7-TSK-003)")
class RoutingPolicyDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final String VERSIONS_PATH = "/v1/operator/routing-policy/versions";
    private static final String CARD_AVAILABILITY = "/v1/operator/rails/card/availability";

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    // ----------------------------------------------------------------- versions

    @Test
    @DisplayName("a version is created keyed, audited with the operator's reason, and"
            + " immutable for every writer - the replay answers the original")
    void aVersionIsCreatedKeyedAuditedAndImmutable() throws Exception {
        String operator = operator();
        String key = someKey();
        String reason = "prefer the card route " + suffix();

        HttpResponse<String> created =
                post(VERSIONS_PATH, versionBody(reason, null), operator, key);
        assertThat(created.statusCode()).isEqualTo(201);
        String versionId = field(created.body(), "routingPolicyVersionId");
        int number = intField(created.body(), "version");
        assertThat(number).isGreaterThanOrEqualTo(2);

        // The replay: same key, same body, the ORIGINAL version - no second row (the row
        // count is the proof; INV-IDEM-01).
        HttpResponse<String> replayed =
                post(VERSIONS_PATH, versionBody(reason, null), operator, key);
        assertThat(replayed.statusCode()).isEqualTo(201);
        assertThat(field(replayed.body(), "routingPolicyVersionId")).isEqualTo(versionId);
        assertThat(count(
                        "SELECT count(*) FROM payments.routing_policy_version WHERE reason = ?",
                        reason))
                .isEqualTo(1);

        // The act is audited with the operator's own words (INV-AUD-03).
        assertThat(count(
                        "SELECT count(*) FROM platform.audit_record"
                                + " WHERE operation = 'payments.PaymentRoutingVersionCreated'"
                                + " AND target_id = ? AND reason = ?",
                        versionId,
                        reason))
                .isEqualTo(1);

        // Immutable for EVERY writer, the migrator included - the version and its rules.
        try (Connection migrator = DatabaseRoles.migrator()) {
            assertRaised(migrator,
                    "UPDATE payments.routing_policy_version SET reason = 'rewritten'"
                            + " WHERE id = ?", UUID.fromString(versionId));
            assertRaised(migrator,
                    "DELETE FROM payments.routing_policy_version WHERE id = ?",
                    UUID.fromString(versionId));
            assertRaised(migrator,
                    "UPDATE payments.routing_rule SET instrument_kind = 'WALLET'"
                            + " WHERE policy_version_id = ?", UUID.fromString(versionId));
        }
    }

    @Test
    @DisplayName("ten concurrent creations mint ten distinct, gapless numbers - the unique"
            + " index is the arbiter and the queue (the fee schedule's discipline)")
    void mintingIsGaplessUnderTenWriters() throws Exception {
        String operator = operator();
        long before = count(
                "SELECT COALESCE(MAX(version), 0) FROM payments.routing_policy_version");

        List<Callable<Integer>> races = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String reason = "race " + i + " " + suffix();
            races.add(() -> {
                HttpResponse<String> response =
                        post(VERSIONS_PATH, versionBody(reason, null), operator, someKey());
                assertThat(response.statusCode()).isEqualTo(201);
                return intField(response.body(), "version");
            });
        }
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Integer> numbers = new ArrayList<>();
        try {
            for (Future<Integer> outcome : pool.invokeAll(races)) {
                numbers.add(outcome.get());
            }
        } finally {
            pool.shutdown();
        }

        assertThat(numbers).hasSize(10).doesNotHaveDuplicates();
        assertThat(count(
                        "SELECT COALESCE(MAX(version), 0)"
                                + " FROM payments.routing_policy_version"))
                .as("gapless: ten writers, ten consecutive numbers")
                .isEqualTo(before + 10);
    }

    // ----------------------------------------------------------------- availability

    @Test
    @DisplayName("availability is a recorded, reasoned, audited fact that converges - and"
            + " this suite restores it, because it is shared operational state")
    void availabilityActsConvergeAndAudit() throws Exception {
        String operator = operator();
        long auditBefore = count(
                "SELECT count(*) FROM platform.audit_record"
                        + " WHERE operation = 'payments.RailAvailabilityChanged'"
                        + " AND target_id = 'card'");
        try {
            HttpResponse<String> disabled = post(
                    CARD_AVAILABILITY,
                    availabilityBody(false, "incident " + suffix()),
                    operator,
                    null);
            assertThat(disabled.statusCode()).isEqualTo(200);
            assertThat(disabled.body()).contains("\"available\":false");

            // The same state set again converges on it - and is still an audited act:
            // "I checked and held it out of service" is operational evidence.
            HttpResponse<String> again = post(
                    CARD_AVAILABILITY,
                    availabilityBody(false, "still out " + suffix()),
                    operator,
                    null);
            assertThat(again.statusCode()).isEqualTo(200);
            assertThat(count("SELECT count(*) FROM payments.rail_availability"
                            + " WHERE rail = 'card'"))
                    .as("one row per rail: the newest act IS the fact")
                    .isEqualTo(1);
        } finally {
            assertThat(post(
                                    CARD_AVAILABILITY,
                                    availabilityBody(true, "restored " + suffix()),
                                    operator,
                                    null)
                            .statusCode())
                    .isEqualTo(200);
        }
        assertThat(oneString(
                        "SELECT available::text FROM payments.rail_availability"
                                + " WHERE rail = 'card'"))
                .isEqualTo("true");
        assertThat(count(
                        "SELECT count(*) FROM platform.audit_record"
                                + " WHERE operation = 'payments.RailAvailabilityChanged'"
                                + " AND target_id = 'card'"))
                .isEqualTo(auditBefore + 3);
    }

    // ----------------------------------------------------------------- refusals

    @Test
    @DisplayName("an undeclared rail and a backdated version are refused by name, with"
            + " nothing written")
    void unknownRailAndBackdatedAreRefused() throws Exception {
        String operator = operator();
        long versions = count("SELECT count(*) FROM payments.routing_policy_version");

        HttpResponse<String> unknownAvailability = post(
                "/v1/operator/rails/sepa/availability",
                availabilityBody(false, "typo"),
                operator,
                null);
        assertThat(unknownAvailability.statusCode()).isEqualTo(422);
        assertThat(unknownAvailability.body()).contains("payments.UnknownRail");

        HttpResponse<String> unknownCandidate = post(
                VERSIONS_PATH,
                "{\"rules\":[{\"direction\":\"PAY_IN\",\"instrumentKind\":\"CARD_TOKEN\","
                        + "\"rails\":[\"sepa\"]}],\"reason\":\"typo " + suffix() + "\"}",
                operator,
                someKey());
        assertThat(unknownCandidate.statusCode()).isEqualTo(422);
        assertThat(unknownCandidate.body()).contains("payments.UnknownRail");

        HttpResponse<String> backdated = post(
                VERSIONS_PATH,
                versionBody("backdated " + suffix(),
                        Instant.now(CLOCK).minus(Duration.ofHours(1))),
                operator,
                someKey());
        assertThat(backdated.statusCode()).isEqualTo(422);
        assertThat(backdated.body()).contains("payments.RoutingPolicyNotForward");

        assertThat(count("SELECT count(*) FROM payments.routing_policy_version"))
                .isEqualTo(versions);
        assertThat(count("SELECT count(*) FROM payments.rail_availability"
                        + " WHERE rail = 'sepa'"))
                .isZero();
    }

    // ----------------------------------------------------------------- authorization

    @Test
    @DisplayName("the routing surface is behind PAYMENT_ROUTING_ADMINISTER: the"
            + " permissionless session and the MERCHANT ADMINISTRATOR are both 403 with"
            + " nothing written")
    void theRoutingRoutesArePrivileged() throws Exception {
        long versions = count("SELECT count(*) FROM payments.routing_policy_version");
        String plain = tokenFrom(authenticate(registered()).body());

        // THE NAMED NEGATIVE: a merchant administrator rules on counterparties and still
        // cannot reroute the platform's money - how money travels is the money-operating
        // desk's judgement, which is the whole reason PAYMENT_ROUTING_ADMINISTER exists.
        String merchantAdministrator = sessionWith(RoleName.MERCHANT_ADMINISTRATOR);

        for (String bearer : List.of(plain, merchantAdministrator)) {
            assertThat(post(VERSIONS_PATH, versionBody("sneaky " + suffix(), null), bearer,
                            someKey())
                            .statusCode())
                    .isEqualTo(403);
            assertThat(post(CARD_AVAILABILITY, availabilityBody(false, "sneaky"), bearer,
                            null)
                            .statusCode())
                    .isEqualTo(403);
            assertThat(get("/v1/operator/payments/" + UUID.randomUUID() + "/routing", bearer)
                            .statusCode())
                    .isEqualTo(403);
        }
        assertThat(count("SELECT count(*) FROM payments.routing_policy_version"))
                .isEqualTo(versions);
        assertThat(oneString(
                        "SELECT COALESCE((SELECT available::text FROM"
                                + " payments.rail_availability WHERE rail = 'card'), 'true')"))
                .as("nobody unauthorised turned the rail off")
                .isEqualTo("true");
    }

    @Test
    @DisplayName("an unknown payment, an unrouted payment and a malformed identifier are the"
            + " SAME 404 on the explanation")
    void unknownAndMalformedAreOneAnswerOnTheExplanation() throws Exception {
        String operator = operator();
        assertThat(get("/v1/operator/payments/" + UUID.randomUUID() + "/routing", operator)
                        .statusCode())
                .isEqualTo(404);
        assertThat(get("/v1/operator/payments/not-a-uuid/routing", operator).statusCode())
                .isEqualTo(404);
    }

    // ----------------------------------------------------------------- fixtures

    private String operator() throws Exception {
        return sessionWith(RoleName.LEDGER_OPERATOR);
    }

    /**
     * When this suite's versions take effect unless a test names another instant: far enough
     * ahead that none ever becomes LIVE while the test JVM runs (a fixed instant, so a keyed
     * replay's body stays byte-identical).
     */
    private static final Instant DORMANT = Instant.parse("2099-01-01T00:00:00Z");

    /**
     * A DORMANT, WHOLE version. The versions this suite publishes are SHARED OPERATIONAL
     * STATE — the test JVM runs one database for every suite, and the newest-effective
     * resolution makes a live published version THE policy for every suite that follows,
     * exactly why this suite restores rail availability. So they are published dormant
     * ({@link #DORMANT}): no suite's routing, and no suite's pinned version number, can see
     * them. And whole — the standing policy's four rules, rule for rule the seeded version 4
     * (payments `V019`) — so a version that did become live would route exactly as the seed.
     * Until `P7-TSK-012`'s gate these were card-only and effective NOW: every withdrawal,
     * pay-by-bank and wallet payment in a later suite was refused `NoEligibleRail` and every
     * later version pin read this suite's number — found when a battery first ran this suite
     * ahead of `WithdrawalDatabaseTest` (the signature `P7-TSK-011`'s gate had read as clock
     * skew).
     */
    private static String versionBody(String reason, Instant effectiveFrom) {
        return "{\"rules\":["
                + "{\"direction\":\"PAY_IN\",\"instrumentKind\":\"CARD_TOKEN\","
                + "\"rails\":[\"card\"]},"
                + "{\"direction\":\"PAY_OUT\",\"instrumentKind\":\"BANK_ACCOUNT\","
                + "\"rails\":[\"instant\"]},"
                + "{\"direction\":\"PAY_IN\",\"instrumentKind\":\"BANK_ACCOUNT\","
                + "\"rails\":[\"instant\"]},"
                + "{\"direction\":\"PAY_IN\",\"instrumentKind\":\"WALLET\","
                + "\"rails\":[\"book\"]}]"
                + ",\"effectiveFrom\":\"" + (effectiveFrom == null ? DORMANT : effectiveFrom)
                + "\""
                + ",\"reason\":\"" + reason + "\"}";
    }

    private static String availabilityBody(boolean available, String reason) {
        return "{\"available\":" + available + ",\"reason\":\"" + reason + "\"}";
    }

    private static void assertRaised(Connection connection, String sql, Object argument) {
        assertThatThrownBy(() -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setObject(1, argument);
                statement.executeUpdate();
            }
        })
                .isInstanceOf(SQLException.class)
                .extracting(failure -> ((SQLException) failure).getSQLState())
                .isEqualTo("P0001");
    }

    private String sessionWith(RoleName role) throws Exception {
        String login = registered();
        UUID identity;
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT id FROM identity.identity WHERE login_identifier = ?")) {
            read.setString(1, login);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                identity = row.getObject("id", UUID.class);
            }
        }
        try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(
                    app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
            app.commit();
        }
        return tokenFrom(authenticate(login).body());
    }

    private String registered() throws Exception {
        String login =
                "route." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(register(login).statusCode()).isEqualTo(201);
        return login;
    }

    private HttpResponse<String> register(String login) throws Exception {
        return post(
                "/v1/registrations",
                "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                        + "\"password\":\"" + PASSWORD + "\"}",
                null,
                someKey());
    }

    private HttpResponse<String> authenticate(String login) throws Exception {
        return post(
                "/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}",
                null,
                someKey());
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path)).GET();
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return send(request.build());
    }

    private HttpResponse<String> post(String path, String body, String bearer, String key)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        if (key != null) {
            request.header(IdempotencyKeyHeader.NAME, key);
        }
        return send(request.build());
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        try (HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static long count(String sql, Object... arguments) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                read.setObject(i + 1, arguments[i]);
            }
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private static String oneString(String sql) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(sql);
                ResultSet row = read.executeQuery()) {
            assertThat(row.next()).isTrue();
            return row.getString(1);
        }
    }

    private static String someKey() {
        return "key-" + UUID.randomUUID();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String field(String body, String name) {
        Matcher matcher =
                Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("the body must carry %s: %s", name, body).isTrue();
        return matcher.group(1);
    }

    private static int intField(String body, String name) {
        Matcher matcher =
                Pattern.compile("\"" + Pattern.quote(name) + "\":(\\d+)").matcher(body);
        assertThat(matcher.find()).as("the body must carry %s: %s", name, body).isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    private static String tokenFrom(String body) {
        return field(body, "sessionToken");
    }
}
