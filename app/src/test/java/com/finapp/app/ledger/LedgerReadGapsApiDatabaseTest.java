package com.finapp.app.ledger;

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
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The ledger's read gaps over HTTP (`P17-TSK-022`; `API_CONVENTIONS.md` section 7, finapp-admin-panel ADR-C133): the register and
 * the search under {@code ACCOUNTING_READ}, the two queues under {@code LEDGER_ADJUST} - the finance roles a partition, so neither
 * role reads the other's, each refused {@code 403} by the interceptor; each refusing what section 7 refuses, {@code 422} with
 * nothing read and nothing recorded; the last page carrying no {@code nextCursor} at all; and every page of the register and the
 * search recorded with its reader and its filter. The order, the walk and the sums are {@code LedgerCollectionsDatabaseTest}'s;
 * the parsing and the views {@code LedgerCollectionDeskTest}'s.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the ledger's read gaps over HTTP (P17-TSK-022)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class LedgerReadGapsApiDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final String ACCOUNTS = "/v1/operator/ledger/accounts";
    private static final String ENTRIES = "/v1/operator/ledger/entries";
    private static final String ADJUSTMENTS = "/v1/ledger/adjustments";
    private static final String REBUILDS = "/v1/ledger/projection-rebuilds";

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    private final SessionStore<Connection> sessions = new JdbcSessionStore();
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    @DisplayName("the finance roles are a partition: a ledger operator is refused the register and the search, an accountant the"
            + " two queues - 403, nothing recorded")
    void eachRoleIsRefusedTheOthersReads() throws Exception {
        Operator operator = sessionWith(RoleName.LEDGER_OPERATOR);
        Operator accountant = sessionWith(RoleName.ACCOUNTANT);

        assertThat(get(operator, ACCOUNTS).statusCode()).isEqualTo(403);
        assertThat(get(operator, ENTRIES + "?from=2026-01-01&to=2026-01-31").statusCode()).isEqualTo(403);
        assertThat(get(accountant, ADJUSTMENTS).statusCode()).isEqualTo(403);
        assertThat(get(accountant, REBUILDS + "?status=PROPOSED").statusCode()).isEqualTo(403);
        assertThat(records(operator, "ledger.AccountsListed") + records(operator, "ledger.EntriesSearched")).isZero();
    }

    @Test
    @DisplayName("the register: a page in its order with the next page's cursor, recorded ledger.AccountsListed with the reader"
            + " and the filter; a purpose and a currency narrow it")
    void theRegisterIsServedAndRecorded() throws Exception {
        Operator accountant = sessionWith(RoleName.ACCOUNTANT);

        HttpResponse<String> first = get(accountant, ACCOUNTS + "?limit=1");
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        assertThat(first.body()).startsWith("{\"items\":[{\"accountId\":\"").contains("\"nextCursor\":\"");
        String cursor = first.body().replaceAll("(?s).*\"nextCursor\":\"([^\"]+)\".*", "$1");
        HttpResponse<String> second = get(accountant, ACCOUNTS + "?limit=1&cursor=" + cursor);
        assertThat(second.statusCode()).as(second.body()).isEqualTo(200);
        assertThat(second.body()).isNotEqualTo(first.body());

        HttpResponse<String> fees = get(accountant, ACCOUNTS + "?purpose=FEE_REVENUE&currency=EUR&limit=200");
        assertThat(fees.statusCode()).as(fees.body()).isEqualTo(200);
        assertThat(fees.body()).contains("\"purpose\":\"FEE_REVENUE\"").doesNotContain("\"purpose\":\"CUSTOMER_WALLET\"")
                .doesNotContain("nextCursor");

        assertThat(records(accountant, "ledger.AccountsListed")).isEqualTo(3);
        assertThat(summary(accountant, "ledger.AccountsListed", "FEE_REVENUE"))
                .isEqualTo("purpose=FEE_REVENUE, currency=EUR, continued=false, served=1");
    }

    @Test
    @DisplayName("the search: a window holding none is an empty page with no nextCursor, recorded ledger.EntriesSearched with the"
            + " window and the filters")
    void anEmptyWindowIsTheLastPageAndIsRecorded() throws Exception {
        Operator accountant = sessionWith(RoleName.ACCOUNTANT);
        UUID account = IDS.next();

        HttpResponse<String> page = get(accountant, ENTRIES + "?from=1985-01-01&to=1985-01-31&reference=transfer:"
                + UUID.randomUUID() + "&accountId=" + account);

        assertThat(page.statusCode()).as(page.body()).isEqualTo(200);
        assertThat(page.body()).isEqualTo("{\"items\":[]}");
        assertThat(records(accountant, "ledger.EntriesSearched")).isEqualTo(1);
        assertThat(summary(accountant, "ledger.EntriesSearched", account.toString()))
                .startsWith("from=1985-01-01, to=1985-01-31, reference=transfer:").endsWith(", account=" + account
                        + ", continued=false, served=0");
    }

    @Test
    @DisplayName("the register and the search refuse 422, recording nothing: a window missing, reversed or over 31 dates; a"
            + " reference of free text; an account or a purpose outside its vocabulary; a limit outside 1..200; another's cursor")
    void theyRefuseWhatSectionSevenRefuses() throws Exception {
        Operator accountant = sessionWith(RoleName.ACCOUNTANT);
        String queueCursor = OperatorCollection.cursor("ledger.adjustments", "1", UUID.randomUUID().toString());
        String window = "?from=2026-01-01&to=2026-01-31";

        for (String query : new String[] {"", "?from=2026-01-01", "?to=2026-01-31", "?from=2026-02-01&to=2026-01-31",
                "?from=2026-01-01&to=2026-02-01", "?from=2026-01-01T00:00:00Z&to=2026-01-31", window + "&reference=rent%20for%20John",
                window + "&reference=" + "a".repeat(201), window + "&accountId=not-an-id", window + "&limit=201",
                window + "&limit=0", window + "&cursor=" + queueCursor, window + "&cursor=not-a-cursor"}) {
            HttpResponse<String> refused = get(accountant, ENTRIES + query);
            assertThat(refused.statusCode()).as("%s: %s", query, refused.body()).isEqualTo(422);
            assertThat(refused.body()).as(query).contains("api.ValidationFailed").doesNotContain("John");
        }
        for (String query : new String[] {"?purpose=WALLET", "?currency=eur", "?limit=1000", "?cursor=" + queueCursor}) {
            HttpResponse<String> refused = get(accountant, ACCOUNTS + query);
            assertThat(refused.statusCode()).as("%s: %s", query, refused.body()).isEqualTo(422);
            assertThat(refused.body()).as(query).contains("api.ValidationFailed");
        }
        assertThat(records(accountant, "ledger.AccountsListed") + records(accountant, "ledger.EntriesSearched")).isZero();
    }

    @Test
    @DisplayName("the queues: served in their shape to a holder of LEDGER_ADJUST; refused 422 for a status they do not hold, a"
            + " limit outside 1..200 or a cursor they did not write - each other's included")
    void theQueuesRefuseWhatSectionSevenRefuses() throws Exception {
        Operator operator = sessionWith(RoleName.LEDGER_OPERATOR);
        String adjustmentsCursor = OperatorCollection.cursor("ledger.adjustments", "1", UUID.randomUUID().toString());
        String rebuildsCursor = OperatorCollection.cursor("ledger.projection-rebuilds", "1", UUID.randomUUID().toString());

        for (String queue : new String[] {ADJUSTMENTS, REBUILDS}) {
            HttpResponse<String> served = get(operator, queue + "?status=PROPOSED&limit=200");
            assertThat(served.statusCode()).as(served.body()).isEqualTo(200);
            assertThat(served.body()).startsWith("{\"items\":[");
            String others = queue.equals(ADJUSTMENTS) ? rebuildsCursor : adjustmentsCursor;
            for (String query : new String[] {"?status=APPROVED", "?status=proposed", "?limit=1000", "?limit=0",
                    "?cursor=" + others, "?cursor=" + Base64.getUrlEncoder().withoutPadding().encodeToString(
                            "ledger.adjustments|v1|soon|x".getBytes(java.nio.charset.StandardCharsets.UTF_8))}) {
                HttpResponse<String> refused = get(operator, queue + query);
                assertThat(refused.statusCode()).as("%s%s: %s", queue, query, refused.body()).isEqualTo(422);
                assertThat(refused.body()).as(query).contains("api.ValidationFailed");
            }
        }
    }

    // ------------------------------------------------------------------------------------------- plumbing

    private record Operator(String token, UUID identity) {}

    private HttpResponse<String> get(Operator operator, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + operator.token()).header("Accept", "application/json").GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static long records(Operator operator, String operation) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(
                        "SELECT count(*) FROM platform.audit_record WHERE operation = ? AND actor_id = ?")) {
            read.setString(1, operation);
            read.setString(2, operator.identity().toString());
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static String summary(Operator operator, String operation, String targetId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement("SELECT change_summary FROM platform.audit_record WHERE operation = ?"
                        + " AND actor_id = ? AND target_id = ? AND outcome = 'SUCCEEDED'")) {
            read.setString(1, operation);
            read.setString(2, operator.identity().toString());
            read.setString(3, targetId);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).as("a record of %s on %s", operation, targetId).isTrue();
                return row.getString(1);
            }
        }
    }

    /** A role holder's {@code MULTI_FACTOR} session, as the operator lists' suite makes one. */
    private Operator sessionWith(RoleName role) throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app, "INSERT INTO party.party (id, kind, display_name, registered_at) VALUES (?, 'PERSON', 'Ledger Reads Person', now())",
                    party);
            execute(app, "INSERT INTO identity.identity (id, party_id, login_identifier, status, created_at, status_changed_at)"
                    + " VALUES (?, ?, ?, 'ACTIVE', now(), now())",
                    identity, party, "lr" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        }
        try (CorrelationContext.Scope correlation = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of("p17-022-" + UUID.randomUUID())));
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
            sessions.insert(app, com.finapp.identity.Session.issue(IDS, CLOCK, IdentityId.of(identity), SessionToken.of(plaintext),
                    AssuranceLevel.MULTI_FACTOR, SessionPolicy.current()));
        }
        return new Operator(plaintext, identity);
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
