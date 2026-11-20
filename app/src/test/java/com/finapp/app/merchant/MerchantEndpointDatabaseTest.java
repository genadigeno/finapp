package com.finapp.app.merchant;

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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The merchant operator surface over real HTTP (`P6-TSK-003`): the privileged boundary — the
 * permissionless 403 with nothing written, the WRONG population's 403 (a ledger operator is
 * not a merchant administrator — the pairwise split over HTTP, {@code INV-AUD-03}), the
 * onboarding 201 with its replay, the refusal codes, the required reason, and the one 404.
 * The money-and-books proofs are {@code MerchantOnboardingDatabaseTest}'s and are cited, not
 * repeated.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the merchant operator endpoints (P6-TSK-003)")
class MerchantEndpointDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    // -----------------------------------------------------------------

    @Test
    @DisplayName("the permissionless session is 403 with NOTHING written; the ledger operator"
            + " is refused identically - two populations, split over HTTP")
    void theWrongPopulationsAreRefusedWithNothingWritten() throws Exception {
        long merchants = count("SELECT count(*) FROM merchant.merchant");
        String body = onboardBody(verifiedOrganisation());

        String plain = tokenFrom(authenticate(registered()).body());
        assertThat(post("/v1/operator/merchants", body, plain, someKey()).statusCode())
                .isEqualTo(403);

        String ledgerOperator = sessionWith(RoleName.LEDGER_OPERATOR);
        assertThat(post("/v1/operator/merchants", body, ledgerOperator, someKey()).statusCode())
                .as("operating the money is not administering counterparties")
                .isEqualTo(403);

        assertThat(count("SELECT count(*) FROM merchant.merchant")).isEqualTo(merchants);
    }

    @Test
    @DisplayName("the administrator onboards over HTTP - 201, and the same key replays the"
            + " same merchant with this same 201")
    void theAdministratorOnboardsAndTheKeyReplays() throws Exception {
        String admin = sessionWith(RoleName.MERCHANT_ADMINISTRATOR);
        String body = onboardBody(verifiedOrganisation());
        String key = someKey();

        HttpResponse<String> created = post("/v1/operator/merchants", body, admin, key);
        assertThat(created.statusCode()).isEqualTo(201);
        String merchantId = field(created.body(), "merchantId");
        assertThat(field(created.body(), "status")).isEqualTo("ACTIVE");

        HttpResponse<String> replayed = post("/v1/operator/merchants", body, admin, key);
        assertThat(replayed.statusCode()).isEqualTo(201);
        assertThat(field(replayed.body(), "merchantId")).isEqualTo(merchantId);
        assertThat(
                        count(
                                "SELECT count(*) FROM merchant.merchant WHERE id = ?",
                                UUID.fromString(merchantId)))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the ineligible party is one 422 merchant.NotEligible, causes conflated,"
            + " nothing written")
    void theIneligiblePartyIsOne422() throws Exception {
        String admin = sessionWith(RoleName.MERCHANT_ADMINISTRATOR);
        long merchants = count("SELECT count(*) FROM merchant.merchant");

        for (String body :
                new String[] {
                    onboardBody(verifiedPerson()), onboardBody(UUID.randomUUID())
                }) {
            HttpResponse<String> refused =
                    post("/v1/operator/merchants", body, admin, someKey());
            assertThat(refused.statusCode()).isEqualTo(422);
            assertThat(refused.body()).contains("merchant.NotEligible");
        }
        assertThat(count("SELECT count(*) FROM merchant.merchant")).isEqualTo(merchants);
    }

    @Test
    @DisplayName("suspension demands its reason - blank is the boundary's 400; with one, the"
            + " move lands and the illegal successor is 409 merchant.IllegalTransition")
    void theStandingMovesDemandReasonsAndTheMachineRules() throws Exception {
        String admin = sessionWith(RoleName.MERCHANT_ADMINISTRATOR);
        HttpResponse<String> created =
                post(
                        "/v1/operator/merchants",
                        onboardBody(verifiedOrganisation()),
                        admin,
                        someKey());
        String merchantId = field(created.body(), "merchantId");

        assertThat(
                        post(
                                        "/v1/operator/merchants/" + merchantId + "/suspension",
                                        "{\"reason\":\"  \"}",
                                        admin,
                                        someKey())
                                .statusCode())
                .as("a judgement without its reasoning is refused at the boundary - the"
                        + " platform's validation refusal shape")
                .isEqualTo(422);

        HttpResponse<String> suspended =
                post(
                        "/v1/operator/merchants/" + merchantId + "/suspension",
                        "{\"reason\":\"chargeback ratio breached\"}",
                        admin,
                        someKey());
        assertThat(suspended.statusCode()).isEqualTo(200);
        assertThat(field(suspended.body(), "status")).isEqualTo("SUSPENDED");

        HttpResponse<String> refused =
                post(
                        "/v1/operator/merchants/" + merchantId + "/closure",
                        "{\"reason\":\"tidy up\"}",
                        admin,
                        someKey());
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(refused.body()).contains("merchant.IllegalTransition");
    }

    @Test
    @DisplayName("TEN INSTANCES suspending one ACTIVE merchant make ONE move - one history row,"
            + " one audit record, and ten 200s that all say SUSPENDED")
    void tenConcurrentSuspensionsMakeOneMove() throws Exception {
        // P6-DOC-001: the phase review found the standing move's convergence proven one retry at
        // a time (MerchantOnboardingDatabaseTest), so the merchant row's FOR UPDATE - what makes
        // nine racers re-read the SUSPENDED the first one wrote and converge on it, rather than
        // lose the conditional write's row count or be refused the edge - had no race test.
        String admin = sessionWith(RoleName.MERCHANT_ADMINISTRATOR);
        HttpResponse<String> created =
                post(
                        "/v1/operator/merchants",
                        onboardBody(verifiedOrganisation()),
                        admin,
                        someKey());
        String merchantId = field(created.body(), "merchantId");
        int racers = 10;

        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> results = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            for (int i = 0; i < racers; i++) {
                results.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return post(
                                            "/v1/operator/merchants/" + merchantId
                                                    + "/suspension",
                                            "{\"reason\":\"chargeback ratio breached\"}",
                                            admin,
                                            someKey());
                                }));
            }
            start.countDown();
            for (Future<HttpResponse<String>> result : results) {
                HttpResponse<String> answered = result.get(60, TimeUnit.SECONDS);
                // A racer that finds the merchant already SUSPENDED has found its move done
                // (MerchantAdministration's convergence): the same 200, never a 409.
                assertThat(answered.statusCode()).as(answered.body()).isEqualTo(200);
                assertThat(field(answered.body(), "status")).isEqualTo("SUSPENDED");
            }
        } finally {
            pool.shutdownNow();
        }

        UUID merchant = UUID.fromString(merchantId);
        assertThat(
                        count(
                                "SELECT count(*) FROM merchant.merchant_event WHERE merchant_id"
                                        + " = ? AND from_status = 'ACTIVE' AND to_status ="
                                        + " 'SUSPENDED'",
                                merchant))
                .as("one suspension, however many operators asked at once")
                .isEqualTo(1);
        assertThat(
                        count(
                                "SELECT count(*) FROM merchant.merchant_event WHERE merchant_id"
                                        + " = ?",
                                merchant))
                .as("and no other history")
                .isEqualTo(1);
        assertThat(
                        count(
                                "SELECT count(*) FROM platform.audit_record WHERE operation ="
                                        + " 'merchant.MerchantSuspended' AND target_id = ?",
                                merchantId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a closure is four eyes (P17-TSK-031): the administrator proposes and cannot approve, a controller approves"
            + " and the merchant is CLOSED; a rejection moves nothing and a decided proposal is decided once; approvals racing"
            + " rejections produce one winner; V019's CHECK refuses the proposer's decision written by hand")
    void aClosureIsFourEyes() throws Exception {
        String admin = sessionWith(RoleName.MERCHANT_ADMINISTRATOR);
        String controller = sessionWith(RoleName.FINANCIAL_CONTROLLER);
        String merchantId = field(post("/v1/operator/merchants", onboardBody(verifiedOrganisation()), admin, someKey())
                .body(), "merchantId");

        HttpResponse<String> proposed = post("/v1/operator/merchants/" + merchantId + "/closure",
                "{\"reason\":\"relationship ended\"}", admin, someKey());
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        assertThat(field(proposed.body(), "status")).isEqualTo("PROPOSED");
        assertThat(field(proposed.body(), "merchantStatus")).as("a proposal moves nothing").isEqualTo("ACTIVE");
        String proposal = field(proposed.body(), "proposalId");
        assertThat(post("/v1/operator/merchant-closures/" + proposal + "/approval", "{\"reason\":\"mine\"}", admin,
                someKey()).statusCode()).as("the proposer's population holds no approval").isEqualTo(403);
        try (Connection app = DatabaseRoles.application();
                PreparedStatement forged = app.prepareStatement("UPDATE merchant.merchant_closure_proposal"
                        + " SET status = 'APPROVED', decided_by = proposed_by, decided_by_type = 'EMPLOYEE',"
                        + " decided_at = proposed_at + interval '1 second', decision_reason = 'mine',"
                        + " decision_correlation_id = 'c' WHERE id = ?")) {
            forged.setObject(1, UUID.fromString(proposal));
            assertThatThrownBy(forged::executeUpdate).isInstanceOf(java.sql.SQLException.class)
                    .hasMessageContaining("merchant_closure_proposal_four_eyes");
        }
        HttpResponse<String> approved = post("/v1/operator/merchant-closures/" + proposal + "/approval",
                "{\"reason\":\"agreed: nothing owed\"}", controller, someKey());
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(field(approved.body(), "merchantStatus")).isEqualTo("CLOSED");
        assertThat(field(get("/v1/operator/merchants/" + merchantId, admin).body(), "status")).isEqualTo("CLOSED");
        assertThat(post("/v1/operator/merchants/" + merchantId + "/closure", "{\"reason\":\"again\"}", admin,
                someKey()).body()).contains("merchant.IllegalTransition");

        String other = field(post("/v1/operator/merchants", onboardBody(verifiedOrganisation()), admin, someKey()).body(),
                "merchantId");
        String rejectedOne = field(post("/v1/operator/merchants/" + other + "/closure", "{\"reason\":\"maybe\"}", admin,
                someKey()).body(), "proposalId");
        HttpResponse<String> rejected = post("/v1/operator/merchant-closures/" + rejectedOne + "/rejection",
                "{\"reason\":\"they still trade\"}", controller, someKey());
        assertThat(field(rejected.body(), "status")).isEqualTo("REJECTED");
        assertThat(field(rejected.body(), "merchantStatus")).isEqualTo("ACTIVE");
        assertThat(post("/v1/operator/merchant-closures/" + rejectedOne + "/approval", "{\"reason\":\"late\"}",
                controller, someKey()).body()).contains("merchant.ClosureAlreadyDecided");

        String raced = field(post("/v1/operator/merchants/" + other + "/closure", "{\"reason\":\"again\"}", admin,
                someKey()).body(), "proposalId");
        int racers = 10;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> results = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            for (int i = 0; i < racers; i++) {
                String door = i % 2 == 0 ? "/approval" : "/rejection";
                String racerKey = someKey();
                results.add(pool.submit(() -> {
                    start.await();
                    HttpResponse<String> answer = post("/v1/operator/merchant-closures/" + raced + door,
                            "{\"reason\":\"the race\"}", controller, racerKey);
                    // A shed request (503 api.Overloaded) did nothing: retried under the same key (P17-TSK-028's discipline).
                    for (int attempt = 0; attempt < 50 && answer.statusCode() == 503; attempt++) {
                        Thread.sleep(100L + java.util.concurrent.ThreadLocalRandom.current().nextInt(200));
                        answer = post("/v1/operator/merchant-closures/" + raced + door, "{\"reason\":\"the race\"}",
                                controller, racerKey);
                    }
                    return answer;
                }));
            }
            start.countDown();
            int won = 0;
            for (Future<HttpResponse<String>> result : results) {
                HttpResponse<String> answered = result.get(60, TimeUnit.SECONDS);
                if (answered.statusCode() == 200) {
                    won++;
                } else {
                    assertThat(answered.body()).contains("merchant.ClosureAlreadyDecided");
                }
            }
            assertThat(won).as("one decision, whichever door won").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("unknown and malformed identifiers are one 404 on every route")
    void unknownAndMalformedAreOne404() throws Exception {
        String admin = sessionWith(RoleName.MERCHANT_ADMINISTRATOR);
        HttpResponse<String> unknown =
                get("/v1/operator/merchants/" + UUID.randomUUID(), admin);
        HttpResponse<String> malformed = get("/v1/operator/merchants/not-a-uuid", admin);
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(malformed.statusCode()).isEqualTo(404);
        assertThat(unknown.body()).contains("api.NotFound");
        assertThat(malformed.body()).contains("api.NotFound");
    }

    @Test
    @DisplayName("the onboarding without its idempotency key is refused before anything runs")
    void theOnboardingDemandsItsKey() throws Exception {
        String admin = sessionWith(RoleName.MERCHANT_ADMINISTRATOR);
        HttpResponse<String> refused =
                post("/v1/operator/merchants", onboardBody(verifiedOrganisation()), admin, null);
        // The interceptor's refusal is the platform's validation shape, before the handler
        // runs - and the merchant count standing still in the first test proves the writes.
        assertThat(refused.statusCode()).isEqualTo(422);
    }

    // -----------------------------------------------------------------

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
        return com.finapp.app.session.OperatorStepUp.elevated(
                tokenFrom(authenticate(login).body()), this::post);
    }

    private String registered() throws Exception {
        String login = someLogin();
        assertThat(register(login).statusCode()).isEqualTo(201);
        return login;
    }

    private static String onboardBody(UUID party) {
        return "{\"partyId\":\"" + party + "\",\"legalName\":\"Acme GmbH\","
                + "\"displayName\":\"Acme\",\"settlementCurrency\":\"EUR\"}";
    }

    private static UUID verifiedOrganisation() throws SQLException {
        return partyWithCustomer("ORGANISATION", "ACTIVE");
    }

    private static UUID verifiedPerson() throws SQLException {
        return partyWithCustomer("PERSON", "ACTIVE");
    }

    private static UUID partyWithCustomer(String kind, String status) throws SQLException {
        UUID party = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at) VALUES"
                            + " (?, '" + kind + "', 'Acme Holdings', now())",
                    party);
            execute(
                    app,
                    "INSERT INTO party.customer (id, party_id, status, opened_at,"
                            + " status_changed_at) VALUES (?, ?, '" + status + "',"
                            + " now() - interval '1 hour', now())",
                    IDS.next(),
                    party);
        }
        return party;
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

    private HttpResponse<String> get(String path, String token) throws Exception {
        return send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build());
    }

    private HttpResponse<String> post(String path, String body, String token, String key)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
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

    private static void execute(Connection connection, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            statement.executeUpdate();
        }
    }

    private static String someKey() {
        return UUID.randomUUID().toString();
    }

    private static String someLogin() {
        return "merop." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String field(String body, String name) {
        Matcher matcher =
                Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("the body must carry %s: %s", name, body).isTrue();
        return matcher.group(1);
    }

    private static String tokenFrom(String body) {
        return field(body, "sessionToken");
    }
}
