package com.finapp.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.crossborder.CorridorPolicyV1;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcRoleAssignmentStore;
import com.finapp.identity.JdbcSessionStore;
import com.finapp.identity.RoleAssignmentStore;
import com.finapp.identity.RoleConflicts;
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
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
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
 * The section 7 retrofit's second batch over HTTP, on PostgreSQL (`P17-TSK-122`; `API_CONVENTIONS.md` section 7, finapp-admin-panel
 * ADR-C133 decision 2): the SoD-conflict review, the corridor policies and the BNPL dispute queue.
 *
 * <p>What only a real database can say: that each keyset read binds and runs - a cursor at the far end of its order serves exactly
 * the first page - and that the last two rows of each order, read a page of one at a time by {@code nextCursor}, are served in
 * order and the second page names no next one: the look-ahead row decides the cursor. The refusals are section 7's,
 * {@code 422 api.ValidationFailed}: a limit over the maximum, and a cursor another collection wrote - the other status's queue's
 * included, so a dispute queue's cursor never positions the other queue.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the section 7 retrofit's second batch over HTTP: SoD conflicts, corridor policies, BNPL disputes (P17-TSK-122)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class OperatorListsSecondBatchApiDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final Pattern NEXT = Pattern.compile("\"nextCursor\":\"([A-Za-z0-9_-]+)\"");
    private static final String EARLIEST = "1000-01-01T00:00:00Z";
    /** The lowest identifier an entity's id type admits: a UUIDv7 (ADR-0013). */
    private static final String LOWEST_V7 = "00000000-0000-7000-8000-000000000000";

    private static final String SOD = "/v1/operator/access-review/sod-conflicts";
    private static final String CORRIDORS = "/v1/operator/cross-border/corridor-policies";
    private static final String DISPUTES = "/v1/operator/bnpl-disputes";

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    private final SessionStore<Connection> sessions = new JdbcSessionStore();
    private final HttpClient http = HttpClient.newHttpClient();

    // ------------------------------------------------------------------------------------------- the SoD-conflict review

    @Test
    @DisplayName("the SoD-conflict review: two conflicts planted at one instant apart served a page of one each in (since, identity,"
            + " first role, second role) order; the order's last two walked by nextCursor, the second page naming none; a far"
            + " cursor serves the first page")
    void theSodConflictReviewWalks() throws Exception {
        IdentityId admin = identityWith(RoleName.ADMINISTRATOR);
        String session = sessionFor(admin);
        // Two conflicts at a run's own instant in the nineteenth century - before every fixture conflict of the shared database
        // (two suites age theirs by a century), after the Gregorian cutover, and apart from another run's.
        Instant since = Instant.parse("1800-01-01T00:00:00Z").plusSeconds(RANDOMNESS.nextInt(3_000_000) * 1_000L);
        IdentityId earlier = identityWith(RoleName.AUDITOR);
        IdentityId later = identityWith(RoleName.AUDITOR);
        try (Connection app = DatabaseRoles.application()) {
            holding(app, earlier, "BNPL_SERVICING_AGENT", admin, since.minusSeconds(60));
            holding(app, earlier, "BNPL_OFFICER", admin, since);
            holding(app, later, "ACCOUNTANT", admin, since.plusSeconds(1));
            holding(app, later, "FINANCIAL_CONTROLLER", admin, since.minusSeconds(60));
        }
        String before = OperatorCollection.cursor("identity.sod-conflicts", since.minusSeconds(1).toString(), LOWEST_V7,
                "ACCOUNTANT", "ACCOUNTANT");
        HttpResponse<String> first = served(session, SOD + "?limit=1&cursor=" + before);
        assertThat(ids(first.body(), "identityId")).as(first.body()).containsExactly(earlier.value().toString());
        assertThat(first.body()).as("the pair's first named first").contains("\"roles\":[\"BNPL_SERVICING_AGENT\",\"BNPL_OFFICER\"]");
        HttpResponse<String> second = served(session, SOD + "?limit=1&cursor=" + next(first.body()));
        assertThat(ids(second.body(), "identityId")).as(second.body()).containsExactly(later.value().toString());

        // The order's last two, read from the store, walked from the position before them.
        List<RoleAssignmentStore.ConflictingHolding> all;
        try (Connection app = DatabaseRoles.application()) {
            all = new JdbcRoleAssignmentStore(IDS).conflictingHoldings(app, RoleConflicts.PAIRS, Optional.empty(), 1_000_000);
        }
        assertThat(all.size()).isGreaterThanOrEqualTo(2);
        List<RoleAssignmentStore.ConflictingHolding> lastTwo = all.subList(all.size() - 2, all.size());
        String from = all.size() > 2 ? sodCursor(all.get(all.size() - 3)) : null;
        List<String> walked = walkToTheEnd(session, SOD, from, "identityId");
        assertThat(walked).containsExactly(lastTwo.get(0).identityId().value().toString(),
                lastTwo.get(1).identityId().value().toString());

        resumesAtTheFarEnd(session, SOD + "?limit=200", OperatorCollection.cursor("identity.sod-conflicts", EARLIEST, LOWEST_V7,
                "ACCOUNTANT", "ACCOUNTANT"));
        refusesWhatSectionSevenRefuses(session, SOD, 200);
    }

    private static String sodCursor(RoleAssignmentStore.ConflictingHolding holding) {
        return OperatorCollection.cursor("identity.sod-conflicts", holding.since().toString(),
                holding.identityId().value().toString(), holding.first().name(), holding.second().name());
    }

    // ------------------------------------------------------------------------------------------- the corridor policies

    @Test
    @DisplayName("the corridor policies: two versions proposed and withdrawn served newest first a page of one each; the oldest two"
            + " walked by nextCursor, the second page naming none; a far cursor serves the first page")
    void theCorridorPoliciesWalk() throws Exception {
        String controller = sessionWith(RoleName.FX_CONTROLLER);
        List<String> proposed = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            HttpResponse<String> proposal = post(controller, CORRIDORS, CorridorPolicyV1.json("P17-TSK-122 walk " + i),
                    UUID.randomUUID().toString());
            assertThat(proposal.statusCode()).as(proposal.body()).isEqualTo(201);
            String id = field(proposal.body(), "id");
            proposed.add(id);
            // Withdrawn at once: one proposal stands at a time, and another suite's must not find this one pending.
            HttpResponse<String> withdrawn = post(controller, CORRIDORS + "/" + id + "/rejection",
                    "{\"reason\":\"P17-TSK-122 walk, withdrawn\"}", null);
            assertThat(withdrawn.statusCode()).as(withdrawn.body()).isEqualTo(200);
        }
        HttpResponse<String> newest = served(controller, CORRIDORS + "?limit=1");
        assertThat(ids(newest.body(), "id")).as(newest.body()).containsExactly(proposed.get(1));
        assertThat(next(newest.body())).as("the one proposed before it follows").isNotNull();
        HttpResponse<String> older = served(controller, CORRIDORS + "?limit=1&cursor=" + next(newest.body()));
        assertThat(ids(older.body(), "id")).as(older.body()).containsExactly(proposed.get(0));

        List<String> oldestFirst = new ArrayList<>();
        int[] versions = new int[2];
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement(
                        "SELECT id, version FROM crossborder.corridor_policy_version ORDER BY version LIMIT 2");
                ResultSet rows = select.executeQuery()) {
            for (int i = 0; rows.next(); i++) {
                oldestFirst.add(rows.getString("id"));
                versions[i] = rows.getInt("version");
            }
        }
        String from = OperatorCollection.cursor("crossborder.corridor-policies", Integer.toString(versions[1] + 1));
        assertThat(walkToTheEnd(controller, CORRIDORS, from, "id")).as("version descending to the very first")
                .containsExactly(oldestFirst.get(1), oldestFirst.get(0));

        resumesAtTheFarEnd(controller, CORRIDORS + "?limit=100",
                OperatorCollection.cursor("crossborder.corridor-policies", Integer.toString(Integer.MAX_VALUE)));
        refusesWhatSectionSevenRefuses(controller, CORRIDORS, 100);
    }

    // ------------------------------------------------------------------------------------------- the BNPL dispute queue

    @Test
    @DisplayName("the BNPL dispute queue, each status its own collection: the queue's last two walked by nextCursor in (entered,"
            + " id) order when it holds two, the second page naming none; a far cursor serves the first page; the other status's"
            + " cursor refused 422")
    void theDisputeQueueWalks() throws Exception {
        String agent = sessionWith(RoleName.BNPL_SERVICING_AGENT);
        for (String[] queue : new String[][] {{"OPEN", "opened_at", "bnpl.disputes.open"},
                {"MERCHANT_RESPONDED", "merchant_responded_at", "bnpl.disputes.merchant-responded"}}) {
            String route = DISPUTES + "?status=" + queue[0];
            // A dispute is opened only on a customer's purchase: the queue is walked when the shared database holds two,
            // as the first batch's breaks are. The keyset's agreement with its order is OperatorListKeysetsTest's.
            List<String[]> tail = new ArrayList<>();
            try (Connection app = DatabaseRoles.application();
                    PreparedStatement select = app.prepareStatement("SELECT " + queue[1] + ", id FROM bnpl.bnpl_dispute"
                            + " WHERE status = ? ORDER BY " + queue[1] + " DESC, id DESC LIMIT 3")) {
                select.setString(1, queue[0]);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        tail.add(0, new String[] {rows.getTimestamp(1).toInstant().toString(), rows.getString(2)});
                    }
                }
            }
            if (tail.size() >= 2) {
                String from = tail.size() == 3 ? OperatorCollection.cursor(queue[2], tail.get(0)[0], tail.get(0)[1]) : null;
                assertThat(walkToTheEnd(agent, route, from, "disputeId")).as(queue[0])
                        .containsExactly(tail.get(tail.size() - 2)[1], tail.get(tail.size() - 1)[1]);
            }
            resumesAtTheFarEnd(agent, route + "&limit=100", OperatorCollection.cursor(queue[2], EARLIEST, LOWEST_V7));
            refusesWhatSectionSevenRefuses(agent, route, 100);
        }
        String openCursor = OperatorCollection.cursor("bnpl.disputes.open", EARLIEST, LOWEST_V7);
        HttpResponse<String> crossed = get(agent, DISPUTES + "?status=MERCHANT_RESPONDED&cursor=" + openCursor);
        assertThat(crossed.statusCode()).as("the OPEN queue's cursor positions no other: %s", crossed.body()).isEqualTo(422);
        assertThat(crossed.body()).contains("api.ValidationFailed");
    }

    // ------------------------------------------------------------------------------------------- plumbing

    /**
     * Two pages of one from {@code from} (the first page when null): the first names a next, the second names none - the end of
     * the order. Answers the two identifiers in the order served.
     */
    private List<String> walkToTheEnd(String reader, String route, String from, String idField) throws Exception {
        String joiner = route.contains("?") ? "&" : "?";
        HttpResponse<String> first = served(reader, route + joiner + "limit=1" + (from == null ? "" : "&cursor=" + from));
        String cursor = next(first.body());
        assertThat(cursor).as("%s: a page of one with another after it names the next: %s", route, first.body()).isNotNull();
        HttpResponse<String> second = served(reader, route + joiner + "limit=1&cursor=" + cursor);
        assertThat(second.body()).as("%s: the last page names no next, never null", route).doesNotContain("nextCursor");
        List<String> walked = new ArrayList<>(ids(first.body(), idField));
        walked.addAll(ids(second.body(), idField));
        return walked;
    }

    /** The page {@code path} serves, and the same page from a cursor before the order's first row: equal, a collection's shape. */
    private void resumesAtTheFarEnd(String reader, String path, String farCursor) throws Exception {
        HttpResponse<String> first = served(reader, path);
        assertThat(first.body()).as(path).startsWith("{\"items\":[").doesNotContain("\"nextCursor\":null")
                .doesNotContain("\"truncated\"");
        HttpResponse<String> resumed = served(reader, path + "&cursor=" + farCursor);
        assertThat(resumed.body()).as("%s: the far cursor's page is the first page", path).isEqualTo(first.body());
    }

    private void refusesWhatSectionSevenRefuses(String reader, String route, int maximum) throws Exception {
        String joiner = route.contains("?") ? "&" : "?";
        String foreign = OperatorCollection.cursor("settlement.batches", "2026-11-18", UUID.randomUUID().toString());
        for (String query : new String[] {"limit=" + (maximum + 1), "limit=0", "cursor=" + foreign, "cursor=not-a-cursor"}) {
            HttpResponse<String> refused = get(reader, route + joiner + query);
            assertThat(refused.statusCode()).as("%s%s%s: %s", route, joiner, query, refused.body()).isEqualTo(422);
            assertThat(refused.body()).contains("api.ValidationFailed");
        }
    }

    private static String next(String body) {
        Matcher next = NEXT.matcher(body);
        return next.find() ? next.group(1) : null;
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

    private static String field(String body, String name) {
        Matcher found = Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(body);
        assertThat(found.find()).as(body).isTrue();
        return found.group(1);
    }

    /** A live holding granted around the door at {@code at}, as before the segregation rule: the conflict the review finds. */
    private static void holding(Connection app, IdentityId identity, String role, IdentityId by, Instant at) throws SQLException {
        execute(app, "INSERT INTO identity.role_assignment (id, identity_id, role_name, assigned_by, assigned_at)"
                + " VALUES (?, ?, ?, ?, ?)", IDS.next(), identity.value(), role, by.value(),
                java.time.OffsetDateTime.ofInstant(at, java.time.ZoneOffset.UTC));
    }

    private HttpResponse<String> served(String token, String path) throws Exception {
        HttpResponse<String> answer = get(token, path);
        assertThat(answer.statusCode()).as("%s: %s", path, answer.body()).isEqualTo(200);
        return answer;
    }

    private HttpResponse<String> get(String token, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token).header("Accept", "application/json").GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String token, String path, String body, String key) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token).header("Accept", "application/json")
                .header("Content-Type", "application/json");
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return http.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A role holder's {@code MULTI_FACTOR} session, as the first batch's suite makes one. */
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
                    identity, party, "lb" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        }
        try (CorrelationContext.Scope correlation = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of("p17-122-" + UUID.randomUUID())));
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

    private static void execute(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.executeUpdate();
        }
    }
}
