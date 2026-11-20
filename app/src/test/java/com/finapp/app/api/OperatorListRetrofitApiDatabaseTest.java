package com.finapp.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcSessionStore;
import com.finapp.identity.RoleName;
import com.finapp.identity.SessionPolicy;
import com.finapp.identity.SessionStore;
import com.finapp.identity.SessionToken;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The retrofitted operator lists over HTTP, on PostgreSQL (`P17-TSK-042`; `API_CONVENTIONS.md` section 7, finapp-admin-panel
 * ADR-C133 decision 2, gaps G-03 and G-121).
 *
 * <p>Each list was capped and is now a section 7 collection. What only a real database can say: that every keyset read binds and
 * runs - a cursor at the far end of its order serves exactly the first page, so the resumed statement and the first one are one
 * order - that two rows read a page at a time are the first page of two, so each resumed read starts where its page ended,
 * and that a walk visits each row once in the declared order, the tie broken by the row's own identifier. The refusals
 * are section 7's, {@code 422 api.ValidationFailed} with nothing read, and a role without a list's permission is still
 * {@code 403}.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the retrofitted operator lists over HTTP: section 7 collections on PostgreSQL (P17-TSK-042)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class OperatorListRetrofitApiDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final Pattern NEXT = Pattern.compile("\"nextCursor\":\"([A-Za-z0-9_-]+)\"");
    private static final String EARLIEST = "1970-01-01T00:00:00Z";
    private static final String LATEST = "2999-01-01T00:00:00Z";
    private static final String LOWEST = "00000000-0000-0000-0000-000000000000";
    /** The lowest identifier an entity's id type admits: a UUIDv7 (ADR-0013). */
    private static final String LOWEST_V7 = "00000000-0000-7000-8000-000000000000";
    private static final String HIGHEST = "ffffffff-ffff-ffff-ffff-ffffffffffff";

    /**
     * One retrofitted list: its route, the role reading it, its maximum, a cursor at the far end of its order, and the member
     * naming an item - the walk's evidence of which row a page served.
     */
    private record Listed(String route, RoleName role, int maximum, String farCursor, String idField) {}

    private static final List<Listed> LISTS = List.of(
            new Listed("/v1/operator/reconciliation/breaks", RoleName.RECONCILIATION_OPERATOR, 200,
                    OperatorCollection.cursor("reconciliation.breaks", LATEST, HIGHEST), "id"),
            new Listed("/v1/operator/events/abandoned", RoleName.EVENT_OPERATOR, 200,
                    OperatorCollection.cursor("deadletter.abandoned", EARLIEST, LOWEST), "eventId"),
            new Listed("/v1/operator/events/parked", RoleName.EVENT_OPERATOR, 200,
                    OperatorCollection.cursor("deadletter.parked", EARLIEST, LOWEST), "parkId"),
            new Listed("/v1/operator/events/discards", RoleName.EVENT_OPERATOR, 200,
                    OperatorCollection.cursor("deadletter.discards", EARLIEST, LOWEST), "discardId"),
            new Listed("/v1/operator/settlement/files", RoleName.RECONCILIATION_OPERATOR, 200,
                    OperatorCollection.cursor("settlement.files", LATEST, HIGHEST), "fileId"),
            new Listed("/v1/operator/settlement/refused-deliveries", RoleName.RECONCILIATION_OPERATOR, 200,
                    OperatorCollection.cursor("settlement.refused-deliveries", LATEST, HIGHEST), "refusalId"),
            new Listed("/v1/operator/access-review/factorless-holdings", RoleName.ADMINISTRATOR, 200,
                    OperatorCollection.cursor("identity.factorless-holdings", "1000-01-01T00:00:00Z", LOWEST_V7, "ACCOUNTANT"),
                    "identityId"),
            new Listed("/v1/operator/credit/review-cases", RoleName.UNDERWRITER, 50,
                    OperatorCollection.cursor("credit.review-cases", EARLIEST, LOWEST_V7), "caseId"),
            new Listed("/v1/operator/accounting/periods", RoleName.ACCOUNTANT, 120,
                    OperatorCollection.cursor("accounting.periods", "2999-12"), "id"));

    /**
     * The lists this suite plants two rows in before it walks them. The breaks, the credit queue and the periods are walked when
     * the shared database holds two: a break or a case is raised only on its detector's or its decision's evidence, and a planted
     * one would be acted on by the instance's own workers. Their keysets' agreement with their orders is
     * {@code OperatorListKeysetsTest}'s, hermetically.
     */
    private static final java.util.Set<String> PLANTED = java.util.Set.of("/v1/operator/events/abandoned", "/v1/operator/events/parked", "/v1/operator/events/discards",
            "/v1/operator/settlement/files", "/v1/operator/settlement/refused-deliveries",
            "/v1/operator/access-review/factorless-holdings");

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    private final SessionStore<Connection> sessions = new JdbcSessionStore();
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    @DisplayName("every list answers a page, and a cursor at the far end of its order serves exactly the first page: the resumed"
            + " keyset read binds, runs and is the same order")
    void everyListResumesInItsOwnOrder() throws Exception {
        for (Listed list : LISTS) {
            String reader = sessionWith(list.role());
            HttpResponse<String> first = get(reader, list.route() + "?limit=" + list.maximum());
            assertThat(first.statusCode()).as("%s: %s", list.route(), first.body()).isEqualTo(200);
            assertThat(first.body()).as(list.route()).startsWith("{\"items\":[").doesNotContain("\"nextCursor\":null")
                    .doesNotContain("\"truncated\"");
            HttpResponse<String> resumed = get(reader, list.route() + "?limit=" + list.maximum() + "&cursor=" + list.farCursor());
            assertThat(resumed.statusCode()).as("%s resumed: %s", list.route(), resumed.body()).isEqualTo(200);
            assertThat(withoutVolatile(resumed.body())).as("%s: the far cursor's page is the first page", list.route())
                    .isEqualTo(withoutVolatile(first.body()));
        }
    }

    @Test
    @DisplayName("every list refuses what section 7 refuses, 422 api.ValidationFailed: a limit over its maximum or under one, a"
            + " cursor it did not write - another list's included")
    void everyListRefusesWhatSectionSevenRefuses() throws Exception {
        String foreign = OperatorCollection.cursor("settlement.batches", "2026-11-18", UUID.randomUUID().toString());
        for (Listed list : LISTS) {
            String reader = sessionWith(list.role());
            for (String query : new String[] {"?limit=" + (list.maximum() + 1), "?limit=0", "?limit=many", "?cursor=" + foreign,
                    "?cursor=not-a-cursor"}) {
                HttpResponse<String> refused = get(reader, list.route() + query);
                assertThat(refused.statusCode()).as("%s%s: %s", list.route(), query, refused.body()).isEqualTo(422);
                assertThat(refused.body()).as(list.route() + query).contains("api.ValidationFailed");
            }
        }
    }

    @Test
    @DisplayName("a role without a list's permission is still refused 403: the retrofit changed the answer, not who reads it")
    void theReadersAreUnchanged() throws Exception {
        String accountant = sessionWith(RoleName.ACCOUNTANT);
        for (Listed list : LISTS) {
            if (list.role() != RoleName.ACCOUNTANT) {
                assertThat(get(accountant, list.route()).statusCode()).as(list.route()).isEqualTo(403);
            }
        }
        assertThat(get(sessionWith(RoleName.RECONCILIATION_OPERATOR), "/v1/operator/accounting/periods").statusCode())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("two rows read a page at a time are the first page of two, in every list holding two - the keyset resumes where"
            + " its page ended, in its own order: dead letters, discards, files, refusals and holdings planted so each holds two; the"
            + " breaks, the credit queue and the periods walked when they do")
    void everyWalkIsItsFirstPageInPieces() throws Exception {
        String operator = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        for (int i = 0; i < 2; i++) {
            HttpResponse<String> landed = post(operator, "/v1/operator/settlement/files",
                    upload("walk-" + UUID.randomUUID() + ",alpha\n"), UUID.randomUUID().toString());
            assertThat(landed.statusCode()).as(landed.body()).isEqualTo(202);
            HttpResponse<String> refused = post(operator, "/v1/operator/settlement/files",
                    upload("id,amount,reference\n1,100,4111111111111111\n2,200,walk-" + UUID.randomUUID() + "\n"),
                    UUID.randomUUID().toString());
            assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
        }
        String eventOperator = sessionWith(RoleName.EVENT_OPERATOR);
        Path seed = repositoryRoot().resolve("infra/scripts/e2e-seed");
        for (int i = 0; i < 2; i++) {
            String abandoned = plant(Files.readString(seed.resolve("abandoned-event.sql")), "event_id");
            plant(Files.readString(seed.resolve("parked-record.sql")), "park_id");
            HttpResponse<String> proposed = post(eventOperator, "/v1/operator/events/discards",
                    "{\"subjectKind\":\"OUTBOX_EVENT\",\"subjectId\":\"" + abandoned + "\",\"reason\":\"P17-TSK-042 walk\"}",
                    UUID.randomUUID().toString());
            assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(200);
        }
        identityWith(RoleName.LEDGER_OPERATOR);
        identityWith(RoleName.LEDGER_OPERATOR);

        for (Listed list : LISTS) {
            String reader = sessionWith(list.role());
            String route = list.route().endsWith("/discards") ? list.route() + "?status=PROPOSED" : list.route();
            inPieces(reader, route, list.idField(), PLANTED.contains(list.route()));
        }
    }

    @Test
    @DisplayName("the factorless review walks once in its declared order: three holdings granted at one instant, tied, served one"
            + " a page in (assigned at, identity, role) order and none twice")
    void theFactorlessReviewWalksOnceInItsOrder() throws Exception {
        IdentityId admin = identityWith(RoleName.ADMINISTRATOR);
        List<String> planted = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            IdentityId holder = identityWith(RoleName.LEDGER_OPERATOR);
            planted.add(holder.value().toString());
        }
        // A tie at an instant before every fixture holding of the shared database (two suites age theirs by a century), so the
        // walk starts with these three - after the Gregorian cutover, as every stamp the platform writes is: a Timestamp reads a
        // date before 1582 in the Julian calendar.
        try (Connection app = DatabaseRoles.application();
                PreparedStatement tie = app.prepareStatement("UPDATE identity.role_assignment SET assigned_at = TIMESTAMPTZ"
                        + " '1700-01-01 00:00:00+00' WHERE identity_id = ANY (?) AND revoked_at IS NULL")) {
            tie.setArray(1, app.createArrayOf("uuid", planted.stream().map(UUID::fromString).toArray()));
            assertThat(tie.executeUpdate()).isEqualTo(3);
        }
        String session = sessionFor(admin);

        List<String> walked = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 3; page++) {
            HttpResponse<String> served = get(session, "/v1/operator/access-review/factorless-holdings?limit=1"
                    + (cursor == null ? "" : "&cursor=" + cursor));
            assertThat(served.statusCode()).as(served.body()).isEqualTo(200);
            Matcher holder = Pattern.compile("\"identityId\":\"([0-9a-f-]{36})\"").matcher(served.body());
            assertThat(holder.find()).as(served.body()).isTrue();
            walked.add(holder.group(1));
            cursor = next(served.body());
        }
        assertThat(walked).as("the tie broken by the identity, ascending, and each served once")
                .containsExactlyElementsOf(planted.stream().sorted(OperatorListRetrofitApiDatabaseTest::postgresUuidOrder).toList());
        assertThat(cursor).as("the review goes on past them: other suites' holdings").isNotNull();
    }

    @Test
    @DisplayName("the refused deliveries walk once, newest first: three refusals planted through the upload door, served one a"
            + " page in refusal order and none twice")
    void theRefusedDeliveriesWalkOnceNewestFirst() throws Exception {
        String operator = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        List<String> refusals = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String dirty = "id,amount,reference\n1,100,4111111111111111\n" + "2,200,ref-" + i + "\n";
            HttpResponse<String> refused = post(operator, "/v1/operator/settlement/files", upload(dirty), UUID.randomUUID().toString());
            assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
            refusals.add(single("SELECT id FROM settlement.refused_delivery ORDER BY refused_at DESC, id DESC LIMIT 1"));
        }

        List<String> walked = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 3; page++) {
            HttpResponse<String> served = get(operator, "/v1/operator/settlement/refused-deliveries?limit=1"
                    + (cursor == null ? "" : "&cursor=" + cursor));
            assertThat(served.statusCode()).as(served.body()).isEqualTo(200);
            Matcher refusal = Pattern.compile("\"refusalId\":\"([0-9a-f-]{36})\"").matcher(served.body());
            assertThat(refusal.find()).as(served.body()).isTrue();
            walked.add(refusal.group(1));
            cursor = next(served.body());
        }
        assertThat(walked).as("newest first, each once").containsExactly(refusals.get(2), refusals.get(1), refusals.get(0));
        assertThat(served(operator, "/v1/operator/settlement/refused-deliveries?limit=200").body())
                .as("the planted value never served").doesNotContain("4111111111111111");
    }

    // ------------------------------------------------------------------------------------------- plumbing

    /** A page with what may differ between two reads of it taken a moment apart removed: nothing, today. */
    private static String withoutVolatile(String body) {
        return body;
    }

    private static String next(String body) {
        Matcher next = NEXT.matcher(body);
        return next.find() ? next.group(1) : null;
    }

    /**
     * The first page of two, and the same two read a page at a time: equal, in order, the second page's cursor resuming exactly
     * where the first page ended. A list that must hold two is asserted to; one that may not is walked only when it does.
     * Answers the two identifiers.
     */
    private List<String> inPieces(String reader, String route, String idField, boolean holdsTwo) throws Exception {
        String joiner = route.contains("?") ? "&" : "?";
        HttpResponse<String> two = served(reader, route + joiner + "limit=2");
        List<String> whole = ids(two.body(), idField);
        if (holdsTwo) {
            assertThat(whole).as("%s holds the two planted: %s", route, two.body()).hasSize(2);
        } else if (whole.size() < 2) {
            return whole;
        }
        HttpResponse<String> first = served(reader, route + joiner + "limit=1");
        String cursor = next(first.body());
        assertThat(cursor).as("%s: a page of one with another after it names the next", route).isNotNull();
        HttpResponse<String> second = served(reader, route + joiner + "limit=1&cursor=" + cursor);
        List<String> pieces = new ArrayList<>(ids(first.body(), idField));
        pieces.addAll(ids(second.body(), idField));
        assertThat(pieces).as("%s: page one then page two is the first page of two", route).containsExactlyElementsOf(whole);
        return whole;
    }

    /** The items' identifiers in the order the page serves them - the member named exactly, never a longer name ending in it. */
    private static List<String> ids(String body, String idField) {
        Matcher found = Pattern.compile("[{,]\"" + Pattern.quote(idField) + "\":\"([^\"]+)\"").matcher(body);
        List<String> ids = new ArrayList<>();
        while (found.find()) {
            ids.add(found.group(1));
        }
        return ids;
    }

    /** One of the end-to-end seed's plants, run unchanged as the role the compose stack's psql uses; one row back. */
    private static String plant(String sql, String column) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator();
                java.sql.Statement statement = migrator.createStatement();
                java.sql.ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).as("the plant printed what it created").isTrue();
            String created = rows.getString(column);
            assertThat(rows.next()).as("one row per plant").isFalse();
            return created;
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

    /** PostgreSQL orders uuid by its bytes, unsigned; Java's {@code UUID.compareTo} by signed longs. The database's, here. */
    private static int postgresUuidOrder(String left, String right) {
        return left.replace("-", "").compareTo(right.replace("-", ""));
    }

    private HttpResponse<String> served(String token, String path) throws Exception {
        HttpResponse<String> answer = get(token, path);
        assertThat(answer.statusCode()).as(answer.body()).isEqualTo(200);
        return answer;
    }

    private HttpResponse<String> get(String token, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token).header("Accept", "application/json").GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String token, String path, String body, String key) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token).header("Accept", "application/json")
                .header("Content-Type", "application/json").header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** An upload of {@code csv} as the settlement routes' suite sends one. */
    private static String upload(String csv) {
        return "{\"sourceCode\":\"simulated-psp.settlement\",\"businessDate\":\"2026-09-29\",\"content\":\""
                + Base64.getEncoder().encodeToString(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "\"}";
    }

    /** A role holder's {@code MULTI_FACTOR} session, as the reconciliation suites make one. */
    private String sessionWith(RoleName role) throws SQLException {
        return sessionFor(identityWith(role));
    }

    private IdentityId identityWith(RoleName role) throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app, "INSERT INTO party.party (id, kind, display_name, registered_at) VALUES (?, 'PERSON', 'List Retrofit Person', now())",
                    party);
            execute(app, "INSERT INTO identity.identity (id, party_id, login_identifier, status, created_at, status_changed_at)"
                    + " VALUES (?, ?, ?, 'ACTIVE', now(), now())",
                    identity, party, "lr" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        }
        try (CorrelationContext.Scope correlation = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of("p17-042-" + UUID.randomUUID())));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
            app.commit();
        }
        return IdentityId.of(identity);
    }

    private String sessionFor(IdentityId identity) throws SQLException {
        byte[] bytes = new byte[32];
        RANDOMNESS.nextBytes(bytes);
        String plaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, com.finapp.identity.Session.issue(IDS, CLOCK, identity, SessionToken.of(plaintext),
                    AssuranceLevel.MULTI_FACTOR, SessionPolicy.current()));
        }
        return plaintext;
    }

    private static String single(String sql) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(sql);
                var rows = read.executeQuery()) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }

    private static void execute(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.executeUpdate();
        }
    }
}
