package com.finapp.app.accounting;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The accounting lists over HTTP (`P17-TSK-023`; `API_CONVENTIONS.md` section 7, finapp-admin-panel ADR-C133): each under the
 * permission of the desk that needs it, the finance roles a partition - the accountant the report runs and its own rate sets, the
 * controller the FINAL statements, the mapping versions and the rate sets - each other's refused {@code 403} by the interceptor,
 * and a ledger operator refused all five; each refusing what section 7 refuses, {@code 422} with nothing recorded; the report runs'
 * two lists recorded per page with the reader and the filter, the others not. The walks and their order are
 * {@code AccountingCollectionsDatabaseTest}'s; the parsing and the views {@code AccountingCollectionDeskTest}'s.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the accounting lists over HTTP (P17-TSK-023)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class AccountingListsApiDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final String RUNS = "/v1/operator/accounting/report-runs";
    private static final String STATEMENTS = "/v1/operator/accounting/report-runs/final-statements";
    private static final String VERSIONS = "/v1/operator/accounting/mapping-versions";
    private static final String SETS = "/v1/operator/accounting/valuation-rate-sets";
    private static final String OWN_SETS = "/v1/operator/accounting/valuation-rate-sets/mine";

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    private final SessionStore<Connection> sessions = new JdbcSessionStore();
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    @DisplayName("the finance roles are a partition: each list served to its own desk, refused 403 to the other's and to a"
            + " ledger operator - nothing recorded for a refusal")
    void eachListIsItsOwnDesks() throws Exception {
        Operator accountant = sessionWith(RoleName.ACCOUNTANT);
        Operator controller = sessionWith(RoleName.FINANCIAL_CONTROLLER);
        Operator operator = sessionWith(RoleName.LEDGER_OPERATOR);
        Map<String, Operator> owners = Map.of(RUNS, accountant, OWN_SETS, accountant, STATEMENTS, controller,
                VERSIONS, controller, SETS, controller);

        for (Map.Entry<String, Operator> list : owners.entrySet()) {
            HttpResponse<String> served = get(list.getValue(), list.getKey() + "?limit=1");
            assertThat(served.statusCode()).as("%s: %s", list.getKey(), served.body()).isEqualTo(200);
            assertThat(served.body()).as(list.getKey()).startsWith("{\"items\":[");
            Operator other = list.getValue() == accountant ? controller : accountant;
            assertThat(get(other, list.getKey()).statusCode()).as("%s to the other desk", list.getKey()).isEqualTo(403);
            assertThat(get(operator, list.getKey()).statusCode()).as("%s to a ledger operator", list.getKey()).isEqualTo(403);
        }
        assertThat(records(controller, "accounting.ReportRunsListed")).isZero();
        assertThat(records(accountant, "accounting.FinalStatementsListed")).isZero();
        assertThat(records(operator, "accounting.ReportRunsListed") + records(operator, "accounting.FinalStatementsListed"))
                .isZero();
    }

    @Test
    @DisplayName("the report runs and the FINAL statements are recorded per page with the reader and the filter; a period"
            + " holding none is an empty last page, no nextCursor")
    void theRunListsAreRecorded() throws Exception {
        Operator accountant = sessionWith(RoleName.ACCOUNTANT);
        Operator controller = sessionWith(RoleName.FINANCIAL_CONTROLLER);
        UUID period = IDS.next();

        HttpResponse<String> runs = get(accountant, RUNS + "?periodId=" + period + "&status=FINAL");
        assertThat(runs.statusCode()).as(runs.body()).isEqualTo(200);
        assertThat(runs.body()).isEqualTo("{\"items\":[]}");
        HttpResponse<String> statements = get(controller, STATEMENTS + "?periodId=" + period);
        assertThat(statements.statusCode()).as(statements.body()).isEqualTo(200);
        assertThat(statements.body()).isEqualTo("{\"items\":[]}");
        assertThat(get(accountant, RUNS + "?limit=1").statusCode()).isEqualTo(200);

        assertThat(records(accountant, "accounting.ReportRunsListed")).isEqualTo(2);
        assertThat(summary(accountant, "accounting.ReportRunsListed", period.toString()))
                .isEqualTo("period=" + period + ", status=FINAL, continued=false, served=0");
        assertThat(summary(accountant, "accounting.ReportRunsListed", "all")).startsWith("period=all, status=all, continued=false,"
                + " served=");
        assertThat(records(controller, "accounting.FinalStatementsListed")).isEqualTo(1);
        assertThat(summary(controller, "accounting.FinalStatementsListed", period.toString()))
                .isEqualTo("period=" + period + ", continued=false, served=0");
    }

    @Test
    @DisplayName("the mapping versions and the rate sets are not recorded, and a proposer's own sets are its own: none for a"
            + " fresh accountant")
    void theDecidedListsAreNotRecorded() throws Exception {
        Operator accountant = sessionWith(RoleName.ACCOUNTANT);
        Operator controller = sessionWith(RoleName.FINANCIAL_CONTROLLER);

        assertThat(get(controller, VERSIONS + "?status=PROPOSED").statusCode()).isEqualTo(200);
        assertThat(get(controller, SETS + "?status=PROPOSED&periodId=" + IDS.next()).body()).isEqualTo("{\"items\":[]}");
        assertThat(get(accountant, OWN_SETS + "?status=PROPOSED").body()).isEqualTo("{\"items\":[]}");
        assertThat(get(accountant, OWN_SETS).body()).isEqualTo("{\"items\":[]}");

        assertThat(anyRecords(controller) + anyRecords(accountant)).as("no list here writes an audit record").isZero();
    }

    @Test
    @DisplayName("each list refuses 422, recording nothing: a status outside its resource's vocabulary, a period that is not an"
            + " identifier, a limit outside 1..200, another list's cursor or an unreadable one")
    void eachRefusesWhatSectionSevenRefuses() throws Exception {
        Operator accountant = sessionWith(RoleName.ACCOUNTANT);
        Operator controller = sessionWith(RoleName.FINANCIAL_CONTROLLER);
        String foreign = OperatorCollection.cursor("ledger.adjustments", "1", UUID.randomUUID().toString());
        String unreadable = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "accounting.report-runs|v1|soon|x".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        List<String> common = List.of("?limit=0", "?limit=201", "?cursor=" + foreign, "?cursor=not-a-cursor");

        refuses(accountant, RUNS, common, "?status=APPROVED", "?status=final", "?periodId=2091-01",
                "?periodId=" + UUID.randomUUID(), "?cursor=" + unreadable);
        refuses(controller, STATEMENTS, common, "?periodId=x", "?cursor=" + OperatorCollection.cursor(
                "accounting.report-runs", "1", IDS.next().toString()));
        refuses(controller, VERSIONS, common, "?status=APPROVED", "?cursor=" + OperatorCollection.cursor(
                "accounting.mapping-versions", "seven"));
        refuses(controller, SETS, common, "?status=ACTIVE", "?periodId=x", "?cursor=" + OperatorCollection.cursor(
                "accounting.valuation-rate-sets.mine", "1", IDS.next().toString()));
        refuses(accountant, OWN_SETS, common, "?status=ACTIVE", "?cursor=" + OperatorCollection.cursor(
                "accounting.valuation-rate-sets", "1", IDS.next().toString()));

        assertThat(anyRecords(accountant) + anyRecords(controller)).isZero();
    }

    // ------------------------------------------------------------------------------------------- plumbing

    private void refuses(Operator operator, String list, List<String> common, String... own) throws Exception {
        for (String query : java.util.stream.Stream.concat(common.stream(), java.util.Arrays.stream(own)).toList()) {
            HttpResponse<String> refused = get(operator, list + query);
            assertThat(refused.statusCode()).as("%s%s: %s", list, query, refused.body()).isEqualTo(422);
            assertThat(refused.body()).as(list + query).contains("api.ValidationFailed");
        }
    }

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

    /** Every record the operator's requests wrote, beyond the role assignment that made them an operator. */
    private static long anyRecords(Operator operator) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement("SELECT count(*) FROM platform.audit_record WHERE actor_id = ?"
                        + " AND operation LIKE 'accounting.%'")) {
            read.setString(1, operator.identity().toString());
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
            execute(app, "INSERT INTO party.party (id, kind, display_name, registered_at) VALUES (?, 'PERSON', 'Accounting Lists Person', now())",
                    party);
            execute(app, "INSERT INTO identity.identity (id, party_id, login_identifier, status, created_at, status_changed_at)"
                    + " VALUES (?, ?, ?, 'ACTIVE', now(), now())",
                    identity, party, "al" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        }
        try (CorrelationContext.Scope correlation = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of("p17-023-" + UUID.randomUUID())));
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
