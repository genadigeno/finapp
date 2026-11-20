package com.finapp.app.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.api.OperatorCollection;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcSessionStore;
import com.finapp.identity.RoleName;
import com.finapp.identity.SessionPolicy;
import com.finapp.identity.SessionStore;
import com.finapp.identity.SessionToken;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.reconciliation.ExpectationDirection;
import com.finapp.reconciliation.ExpectationKind;
import com.finapp.reconciliation.ExternalItems;
import com.finapp.reconciliation.ExternalLineType;
import com.finapp.reconciliation.ItemKeyKind;
import com.finapp.reconciliation.JdbcExpectationRegister;
import com.finapp.reconciliation.JdbcExternalItems;
import com.finapp.reconciliation.JdbcReconciliationRuns;
import com.finapp.reconciliation.KeyKind;
import com.finapp.reconciliation.Matching;
import com.finapp.reconciliation.NewExpectation;
import com.finapp.reconciliation.ReconciliationRuns;
import com.finapp.reconciliation.RunKind;
import com.finapp.settlement.SettlementFileStore;
import com.finapp.settlement.SourceKind;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The reconciliation runs, expectations and one source's rule sets over HTTP as section 7 collections, on PostgreSQL
 * (`P17-TSK-122`; `API_CONVENTIONS.md` section 7, finapp-admin-panel ADR-C133 decision 2).
 *
 * <p>What only a real database can say: that each keyset read binds and runs, that two rows read a page at a time with
 * {@code limit=1} come in the declared order - runs by {@code (created_at, id)} descending, expectations by
 * {@code (opened_at, id)} descending, rule sets by version descending - each resumed by the page before's {@code nextCursor},
 * and that the walk ends with {@code nextCursor} absent. The refusals are section 7's, {@code 422 api.ValidationFailed}: another
 * collection's cursor, and a limit over the list's maximum.
 *
 * <p>The suite owns a private source (settlement's register answers its one code through the double below, as
 * {@code ReconciliationAdministrationDatabaseTest}'s does): two rule set versions, and two runs whose one item each settles one
 * of two expectations under the composed matcher - so at rest every planted item is MATCHED and every planted expectation SETTLED
 * and held by its allocation, and the position verdicts read as the shared container left them (asserted).
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the reconciliation runs, expectations and rule sets over HTTP: section 7 collections on PostgreSQL (P17-TSK-122)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class ReconciliationListsPageApiDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final Actor PLATFORM = new Actor("system", ActorType.SYSTEM);
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final UUID PRIVATE_SOURCE = UUID.fromString("01a0e2bc-8200-7122-8000-000000000122");
    private static final UUID VERSION_ONE = UUID.fromString("01a0e2bd-8300-7122-8000-000000000122");
    private static final UUID VERSION_TWO = UUID.fromString("01a0e2bd-8300-7122-8000-000000000222");
    /** The one code settlement's register answers for this suite (the double below). */
    static final String PRIVATE_CODE = "p17t122-private.reconciliation";
    private static final LocalDate SETTLED_ON = LocalDate.parse("2026-09-25");
    private static final AtomicLong SEQUENCES = new AtomicLong(Math.floorMod(System.nanoTime(), 70_000L) + 1);
    private static final String BASE = "/v1/operator/reconciliation";
    private static final Pattern NEXT = Pattern.compile("\"nextCursor\":\"([A-Za-z0-9_-]+)\"");
    private static final String EARLIEST = "1970-01-01T00:00:00Z";
    private static final String LOWEST = "00000000-0000-0000-0000-000000000000";
    private static final String HIGHEST = "ffffffff-ffff-ffff-ffff-ffffffffffff";

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private Matching matching;
    @Autowired private PositionProof positionProof;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SessionStore<Connection> sessions = new JdbcSessionStore();
    private final JdbcReconciliationRuns runs = new JdbcReconciliationRuns();
    private final JdbcExternalItems items = new JdbcExternalItems();
    private final JdbcExpectationRegister expectations = new JdbcExpectationRegister(IDS);

    @Test
    @DisplayName("the runs walk a page at a time, born newest first: two planted runs, limit=1, each page resumed by the one"
            + " before's nextCursor, and the far end of the order answers no items and no nextCursor")
    void theRunsWalkNewestFirst() throws Exception {
        seedPrivateRuleSets();
        Planted planted = plantTwoSettledRuns();
        String operator = sessionWith(RoleName.RECONCILIATION_OPERATOR);

        // Start just above the newer run's key: the unfiltered list holds every suite's runs, and this walk is about these two.
        String start = OperatorCollection.cursor(
                "reconciliation.runs", createdAt(planted.runs().get(1)), HIGHEST);
        HttpResponse<String> first = served(operator, BASE + "/runs?limit=1&cursor=" + start);
        assertThat(ids(first.body(), "id")).as(first.body()).containsExactly(planted.runs().get(1).toString());
        assertThat(first.body()).as("the per-run outcome counts are kept").contains("\"outcomes\":{").contains("\"MATCHED\":1");
        String cursor = next(first.body());
        assertThat(cursor).as("a page of one with another after it names the next").isNotNull();
        HttpResponse<String> second = served(operator, BASE + "/runs?limit=1&cursor=" + cursor);
        assertThat(ids(second.body(), "id")).as(second.body()).containsExactly(planted.runs().get(0).toString());

        HttpResponse<String> end = served(operator, BASE + "/runs?limit=1&cursor="
                + OperatorCollection.cursor("reconciliation.runs", EARLIEST, LOWEST));
        assertThat(end.body()).as("past the oldest run: the last page").isEqualTo("{\"items\":[]}");

        HttpResponse<String> page = served(operator, BASE + "/runs");
        assertThat(page.body()).startsWith("{\"items\":[").doesNotContain("\"truncated\"").doesNotContain("\"runs\"");
        refusals(operator, BASE + "/runs?", 200);
    }

    @Test
    @DisplayName("the expectations walk a page at a time, opened newest first: the private source's two, limit=1, resumed by"
            + " nextCursor, the second page the last - nextCursor absent; the filters kept")
    void theExpectationsWalkNewestFirst() throws Exception {
        seedPrivateRuleSets();
        Planted planted = plantTwoSettledRuns();
        String operator = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        String route = BASE + "/expectations?status=SETTLED&source=" + PRIVATE_SOURCE;

        List<String> whole = ids(served(operator, route + "&limit=200").body(), "id");
        assertThat(whole).as("the private source's expectations, newest first")
                .startsWith(planted.expectations().get(1).toString(), planted.expectations().get(0).toString());

        List<String> walked = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < whole.size(); page++) {
            HttpResponse<String> served = served(operator, route + "&limit=1" + (cursor == null ? "" : "&cursor=" + cursor));
            walked.addAll(ids(served.body(), "id"));
            cursor = next(served.body());
            if (page < whole.size() - 1) {
                assertThat(cursor).as("page %d of %d names the next", page + 1, whole.size()).isNotNull();
            }
        }
        assertThat(walked).as("each served once, in the one order").containsExactlyElementsOf(whole);
        assertThat(cursor).as("the last page names no next").isNull();

        assertThat(served(operator, BASE + "/expectations?status=OPEN&source=" + PRIVATE_SOURCE).body())
                .as("the status filter kept: nothing of the private source is open").isEqualTo("{\"items\":[]}");
        refusals(operator, BASE + "/expectations?", 200);
    }

    @Test
    @DisplayName("one source's rule sets walk a page at a time, version descending: v2 then v1, limit=1, the second page the"
            + " last; the source still required")
    void theRuleSetsWalkVersionDescending() throws Exception {
        seedPrivateRuleSets();
        String controller = sessionWith(RoleName.RECONCILIATION_CONTROLLER);
        String route = BASE + "/rule-sets?source=" + PRIVATE_CODE;

        HttpResponse<String> first = served(controller, route + "&limit=1");
        assertThat(ids(first.body(), "ruleSetId")).as(first.body()).containsExactly(VERSION_TWO.toString());
        String cursor = next(first.body());
        assertThat(cursor).as("v1 is after it").isNotNull();
        HttpResponse<String> second = served(controller, route + "&limit=1&cursor=" + cursor);
        assertThat(ids(second.body(), "ruleSetId")).as(second.body()).containsExactly(VERSION_ONE.toString());
        assertThat(next(second.body())).as("v1 is the last").isNull();

        HttpResponse<String> whole = served(controller, route);
        assertThat(ids(whole.body(), "ruleSetId")).containsExactly(VERSION_TWO.toString(), VERSION_ONE.toString());
        assertThat(whole.body()).doesNotContain("\"truncated\"").doesNotContain("\"nextCursor\"");

        assertThat(get(controller, BASE + "/rule-sets").statusCode()).as("the source stays required").isNotEqualTo(200);
        refusals(controller, route + "&", 50);
    }

    // ------------------------------------------------------------------------------------------- the refusals

    /** Section 7's refusals for one list: another collection's cursor, and a limit over its maximum - 422, nothing read. */
    private void refusals(String token, String routeWithJoiner, int maximum) throws Exception {
        String foreign = OperatorCollection.cursor("reconciliation.breaks", EARLIEST, LOWEST);
        for (String query : new String[] {"cursor=" + foreign, "limit=" + (maximum + 1), "cursor=not-a-cursor"}) {
            HttpResponse<String> refused = get(token, routeWithJoiner + query);
            assertThat(refused.statusCode()).as("%s%s: %s", routeWithJoiner, query, refused.body()).isEqualTo(422);
            assertThat(refused.body()).as(routeWithJoiner + query).contains("api.ValidationFailed");
        }
        HttpResponse<String> atMaximum = get(token, routeWithJoiner + "limit=" + maximum);
        assertThat(atMaximum.statusCode()).as("%slimit=%d: %s", routeWithJoiner, maximum, atMaximum.body()).isEqualTo(200);
    }

    // ------------------------------------------------------------------------------------------- the plants

    private record Planted(List<UUID> runs, List<UUID> expectations) {}

    /**
     * Two expectations, then two runs of one item each settling one of them, decided by the composed matcher: older first.
     * The position identity is asserted unchanged over the plant.
     */
    private Planted plantTwoSettledRuns() throws Exception {
        PositionProof.Report before = sweep();
        List<UUID> plantedExpectations = new ArrayList<>();
        List<UUID> plantedRuns = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String key = "CAP-122-" + UUID.randomUUID().toString().substring(0, 8);
            plantedExpectations.add(openExpectation(key, 12_00 + i));
            plantedRuns.add(seedRun(key, 12_00 + i));
        }
        matching.sweep();
        try (Connection app = DatabaseRoles.application()) {
            for (UUID run : plantedRuns) {
                assertThat(one(app, "SELECT status FROM reconciliation.reconciliation_batch WHERE id = ?", run))
                        .isEqualTo("COMPLETED");
            }
            for (UUID expectation : plantedExpectations) {
                assertThat(one(app, "SELECT status FROM reconciliation.expectation WHERE id = ?", expectation))
                        .isEqualTo("SETTLED");
            }
        }
        PositionResiduals.assertUnchanged(before, sweep(), "the planted runs");
        return new Planted(plantedRuns, plantedExpectations);
    }

    private PositionProof.Report sweep() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            app.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                return positionProof.sweep(app);
            } finally {
                app.rollback();
            }
        }
    }

    /**
     * The private source's v1 ACTIVE and v2 PROPOSED, each with v1's content; idempotent across the suite's tests. In that
     * order: a source holds one PROPOSED version at a time ({@code rule_set_one_proposed}), so v1 is activated before v2 is born.
     */
    private static void seedPrivateRuleSets() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            seedVersion(app, VERSION_ONE, 1);
            execute(app,
                    "UPDATE reconciliation.rule_set SET status = 'ACTIVE', decided_by = 'test-activator', decided_at = now()"
                            + " WHERE id = ? AND status = 'PROPOSED'",
                    VERSION_ONE);
            seedVersion(app, VERSION_TWO, 2);
            app.commit();
        }
    }

    private static void seedVersion(Connection app, UUID version, int number) throws SQLException {
        execute(app,
                "INSERT INTO reconciliation.rule_set (id, source_id, version, status, funding_lag_days,"
                        + " gain_min_age_days, effective_from, proposed_by, decided_by, reason, created_at,"
                        + " correlation_id) VALUES (?, ?, ?, 'PROPOSED', 2, 90, ?, 'test', NULL,"
                        + " 'ReconciliationListsPageApiDatabaseTest private rule set', now(), 'p17-tsk-122-app-test')"
                        + " ON CONFLICT (id) DO NOTHING",
                version, PRIVATE_SOURCE, number, java.sql.Date.valueOf(SETTLED_ON));
        execute(app,
                "INSERT INTO reconciliation.rule (rule_set_id, priority, line_type, key_kind, expectation_kind,"
                        + " cardinality, operation_anchored, grace_hours) VALUES (?, 1, 'CAPTURE', 'PSP_CAPTURE_REF',"
                        + " 'CARD_CAPTURE', 'ONE_TO_ONE', false, 48) ON CONFLICT DO NOTHING",
                version);
        execute(app,
                "INSERT INTO reconciliation.tolerance (rule_set_id, comparison, currency, absolute_minor, days)"
                        + " VALUES (?, 'SETTLEMENT_DATE_DAYS', NULL, NULL, 2) ON CONFLICT DO NOTHING",
                version);
        execute(app,
                "INSERT INTO reconciliation.severity_threshold (rule_set_id, currency, high_value_minor)"
                        + " VALUES (?, 'EUR', 100000) ON CONFLICT DO NOTHING",
                version);
    }

    private UUID seedRun(String keyValue, long minor) throws SQLException {
        UUID runId = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            runs.birth(app, new ReconciliationRuns.NewRun(runId, PRIVATE_SOURCE, Optional.of(IDS.next()), RunKind.BATCH,
                    VERSION_ONE, SETTLED_ON, Optional.of(SEQUENCES.incrementAndGet()), 1, Optional.empty(), Optional.empty(),
                    PLATFORM, Instant.now(CLOCK), CorrelationId.generate(IDS)));
            byte[] fingerprint = new byte[32];
            RANDOMNESS.nextBytes(fingerprint);
            items.birthAll(app, PLATFORM, List.of(new ExternalItems.NewItem(IDS.next(), runId, PRIVATE_SOURCE, IDS.next(), 1,
                    ExternalLineType.CAPTURE, ExpectationDirection.INBOUND, Money.ofPersisted(minor, EUR, 2),
                    AccountPurpose.SETTLEMENT_CLEARING, SETTLED_ON, Optional.of(SETTLED_ON), Optional.of(SETTLED_ON),
                    fingerprint, Map.of(ItemKeyKind.PSP_CAPTURE_REF, keyValue), Instant.now(CLOCK),
                    CorrelationId.generate(IDS))));
            app.commit();
        }
        return runId;
    }

    private UUID openExpectation(String keyValue, long minor) throws SQLException {
        String operationRef = "op-122-" + keyValue;
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            UUID position = new JdbcLedgerAccountStore()
                    .findOperational(app, AccountPurpose.SETTLEMENT_CLEARING, EUR).orElseThrow().id().value();
            expectations.open(app, new NewExpectation(ExpectationKind.CARD_CAPTURE, operationRef,
                    "payment-capture:" + operationRef, PRIVATE_SOURCE, AccountPurpose.SETTLEMENT_CLEARING, position,
                    ExpectationDirection.INBOUND, Money.ofPersisted(minor, EUR, 2), Optional.of(IDS.next()), SETTLED_ON,
                    Optional.empty(), SETTLED_ON.plusDays(3), VERSION_ONE,
                    List.of(new NewExpectation.ExpectationKey(KeyKind.PSP_CAPTURE_REF, keyValue)), PLATFORM,
                    Instant.now(CLOCK), CorrelationId.generate(IDS)));
            app.commit();
            return (UUID) one(app, "SELECT id FROM reconciliation.expectation WHERE operation_ref = ?", operationRef);
        }
    }

    /** A run's birth instant as its cursor carries it. */
    private static String createdAt(UUID run) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(
                        "SELECT created_at FROM reconciliation.reconciliation_batch WHERE id = ?")) {
            read.setObject(1, run);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getObject(1, OffsetDateTime.class).toInstant().toString();
            }
        }
    }

    /** Settlement's register answering one more code - this suite's - and delegating every other call untouched. */
    @TestConfiguration
    static class PrivateSourceCode {

        @Bean
        @Primary
        @SuppressWarnings("unchecked")
        SettlementFileStore<Connection> privateCodeAwareSettlementFileStore(
                @Qualifier("settlementFileStore") SettlementFileStore<Connection> settlementFileStore) {
            return (SettlementFileStore<Connection>) Proxy.newProxyInstance(
                    SettlementFileStore.class.getClassLoader(),
                    new Class<?>[] {SettlementFileStore.class},
                    (proxy, method, arguments) -> {
                        if ("sourceByCode".equals(method.getName()) && arguments != null && arguments.length == 2
                                && PRIVATE_CODE.equals(arguments[1])) {
                            return Optional.of(new SettlementFileStore.SourceRow(
                                    PRIVATE_SOURCE, PRIVATE_CODE, SourceKind.PSP_SETTLEMENT_REPORT, true));
                        }
                        try {
                            return method.invoke(settlementFileStore, arguments);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }
    }

    // ------------------------------------------------------------------------------------------- plumbing

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

    /** A role holder's {@code MULTI_FACTOR} session, as the reconciliation suites make one. */
    private String sessionWith(RoleName role) throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app, "INSERT INTO party.party (id, kind, display_name, registered_at) VALUES (?, 'PERSON',"
                    + " 'Reconciliation Lists Person', now())", party);
            execute(app, "INSERT INTO identity.identity (id, party_id, login_identifier, status, created_at, status_changed_at)"
                    + " VALUES (?, ?, ?, 'ACTIVE', now(), now())",
                    identity, party, "rl" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        }
        try (CorrelationContext.Scope correlation = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of("p17-122-" + UUID.randomUUID())));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
            app.commit();
        }
        byte[] bytes = new byte[32];
        RANDOMNESS.nextBytes(bytes);
        String plaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, com.finapp.identity.Session.issue(IDS, CLOCK, IdentityId.of(identity),
                    SessionToken.of(plaintext), AssuranceLevel.MULTI_FACTOR, SessionPolicy.current()));
        }
        return plaintext;
    }

    private static Object one(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getObject(1) : null;
            }
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
