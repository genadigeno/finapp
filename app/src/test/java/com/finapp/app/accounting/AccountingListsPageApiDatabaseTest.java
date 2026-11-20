package com.finapp.app.accounting;

import static com.finapp.app.accounting.AccountingPeriodFixture.IDS;
import static com.finapp.app.accounting.AccountingPeriodFixture.NOW;
import static com.finapp.app.accounting.AccountingPeriodFixture.correlation;
import static com.finapp.app.accounting.AccountingPeriodFixture.inScope;
import static com.finapp.app.accounting.AccountingPeriodFixture.person;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finapp.accounting.AccountingBreaks;
import com.finapp.accounting.BreakFinding;
import com.finapp.accounting.BreakType;
import com.finapp.accounting.CloseCheckCode;
import com.finapp.accounting.JdbcAccountingBreakStore;
import com.finapp.accounting.JdbcRegulatoryObligationStore;
import com.finapp.accounting.RegulatoryAdapters;
import com.finapp.accounting.RegulatoryObligations;
import com.finapp.accounting.reporting.CanonicalJson;
import com.finapp.accounting.reporting.ReportDefinitions;
import com.finapp.accounting.testing.RegulatoryWorld;
import com.finapp.accounting.testing.ScriptedRegulatoryProvider;
import com.finapp.accounting.testing.TestReportDefinition;
import com.finapp.app.api.OperatorCollection;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcSessionStore;
import com.finapp.identity.RoleName;
import com.finapp.identity.SessionPolicy;
import com.finapp.identity.SessionStore;
import com.finapp.identity.SessionToken;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Accounting's operator lists as section 7 collections over HTTP, on PostgreSQL (`P17-TSK-122`; `API_CONVENTIONS.md` section 7,
 * finapp-admin-panel ADR-C133 decision 2, gap G-03): the regulatory obligations (newest first, 50/100), a period's accounting
 * breaks (birth order, 100/500) and the regulatory submissions standing (recorded order, 50/100) - and the two drill-downs that
 * keep their view and take the opaque cursor, a GL account's activity and a report run's lines (100/500).
 *
 * <p>What only a real database says: each keyset read binds and runs; two rows walked a page of one at a time are served once
 * each, in the declared order, the walk ending on a page that names no {@code nextCursor}; a cursor at the far end of an order
 * serves exactly the first page. The refusals are section 7's, {@code 422 api.ValidationFailed}, before anything is read.
 *
 * <p>Planted: two obligation versions (proposed, never activated - nothing drafts from them) and two breaks in a far month of
 * the case's own (no close run reaches it). Not planted: a standing submission needs a closed month and a FINAL run, which the
 * shared database's other suites must not see move ({@code RegulatorySubmissionDatabaseTest}, in a database of its own, walks
 * the store's keyset); and a journal line no expectation names would stay unexplained for good
 * ({@code GlReadModelDatabaseTest}'s reason). Those two are walked when the shared database holds two.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("accounting's operator lists over HTTP: section 7 pages on PostgreSQL (P17-TSK-122)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class AccountingListsPageApiDatabaseTest {

    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OBLIGATIONS = "/v1/operator/regulatory/obligations";
    private static final String BREAKS = "/v1/operator/accounting/breaks";
    private static final String SUBMISSIONS = "/v1/operator/regulatory/submissions";
    private static final String ACTIVITY = "/v1/operator/accounting/gl/accounts/%s/activity?period=%s&currency=%s";
    private static final String LINES = "/v1/operator/accounting/report-runs/%s/lines?account=1000&month=2026-01&currency=EUR";
    private static final String FOREIGN = OperatorCollection.cursor("settlement.batches", "2026-11-18", UUID.randomUUID().toString());

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    private final SessionStore<Connection> sessions = new JdbcSessionStore();
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    @DisplayName("the obligations walk newest first, a page of one at a time: the two proposed last are the first two pages,"
            + " each once, and the walk ends on a page naming no nextCursor; a cursor past the oldest serves exactly the first page")
    void theObligationsWalkNewestFirst() throws Exception {
        String reporter = sessionWith(RoleName.REGULATORY_REPORTER);
        String older = proposed();
        String newer = proposed();

        HttpResponse<String> first = served(reporter, OBLIGATIONS + "?limit=1");
        assertThat(ids(first, "obligationId")).containsExactly(newer);
        String cursor = next(first);
        assertThat(cursor).as("a page of one with another after it names the next").isNotNull();
        HttpResponse<String> second = served(reporter, OBLIGATIONS + "?limit=1&cursor=" + cursor);
        assertThat(ids(second, "obligationId")).containsExactly(older);

        List<String> walked = walk(reporter, OBLIGATIONS + "?limit=100", "obligationId");
        assertThat(walked).as("each version once, the two planted first").startsWith(newer, older).doesNotHaveDuplicates();

        HttpResponse<String> whole = served(reporter, OBLIGATIONS + "?limit=100");
        HttpResponse<String> farEnd = served(reporter, OBLIGATIONS + "?limit=100&cursor="
                + OperatorCollection.cursor("accounting.regulatory-obligations", "2999-01-01T00:00:00Z", "ffffffff-ffff-7fff-bfff-ffffffffffff"));
        assertThat(farEnd.body()).as("the resumed keyset read is the first one's order").isEqualTo(whole.body());
    }

    @Test
    @DisplayName("a period's breaks walk in birth order, a page of one at a time: two breaks, two pages, the second naming no"
            + " nextCursor; a status narrows the walk")
    void aPeriodsBreaksWalkInBirthOrder() throws Exception {
        String accountant = sessionWith(RoleName.ACCOUNTANT);
        YearMonth month = YearMonth.of(2190 + RANDOMNESS.nextInt(9), 1 + RANDOMNESS.nextInt(12));
        raise(month);
        raise(month);
        String route = BREAKS + "?period=" + month;

        HttpResponse<String> two = served(accountant, route + "&limit=2");
        List<String> whole = ids(two, "id");
        assertThat(whole).as(two.body()).hasSize(2);
        assertThat(two.body()).as("exactly a page: no next").doesNotContain("nextCursor");

        HttpResponse<String> first = served(accountant, route + "&limit=1");
        String cursor = next(first);
        assertThat(cursor).isNotNull();
        HttpResponse<String> second = served(accountant, route + "&limit=1&cursor=" + cursor);
        assertThat(next(second)).as("the last page names no next").isNull();
        List<String> pieces = new ArrayList<>(ids(first, "id"));
        pieces.addAll(ids(second, "id"));
        assertThat(pieces).as("page one then page two is the first page of two").containsExactlyElementsOf(whole);

        assertThat(served(accountant, route + "&status=OPEN&limit=1&cursor=" + cursor).body()).isEqualTo(second.body());
        assertThat(served(accountant, route + "&status=RESOLVED").body()).isEqualTo("{\"items\":[]}");
    }

    @Test
    @DisplayName("the submissions standing answer a page, a cursor past the newest serves exactly the first page, and two -"
            + " when the shared database holds them - walk a page of one at a time, each once")
    void theSubmissionsStandingWalkInRecordedOrder() throws Exception {
        String reporter = sessionWith(RoleName.REGULATORY_REPORTER);
        HttpResponse<String> whole = served(reporter, SUBMISSIONS + "?limit=100");
        assertThat(whole.body()).startsWith("{\"items\":[").doesNotContain("nextAfter").doesNotContain("\"submissions\"");
        HttpResponse<String> farEnd = served(reporter, SUBMISSIONS + "?limit=100&cursor="
                + OperatorCollection.cursor("accounting.regulatory-submissions", "0", "00000000-0000-7000-8000-000000000000"));
        assertThat(farEnd.body()).as("the resumed keyset read binds, runs and is the first one's order").isEqualTo(whole.body());

        List<String> standing = ids(whole, "submissionId");
        if (standing.size() >= 2) {
            HttpResponse<String> first = served(reporter, SUBMISSIONS + "?limit=1");
            HttpResponse<String> second = served(reporter, SUBMISSIONS + "?limit=1&cursor=" + next(first));
            List<String> pieces = new ArrayList<>(ids(first, "submissionId"));
            pieces.addAll(ids(second, "submissionId"));
            assertThat(pieces).containsExactlyElementsOf(standing.subList(0, 2));
        }
    }

    @Test
    @DisplayName("a GL account's activity, when the shared database holds a figure of two lines or more, walks a page of one at"
            + " a time to a page naming no nextCursor, each line once, as many as the figure counts")
    void anAccountsActivityWalksToItsEnd() throws Exception {
        String accountant = sessionWith(RoleName.ACCOUNTANT);
        for (String month : monthsWithEntries()) {
            HttpResponse<String> balances = get(accountant, "/v1/operator/accounting/gl/balances?period=" + month);
            if (balances.statusCode() != 200) {
                continue; // no version in force for that month
            }
            for (JsonNode figure : JSON.readTree(balances.body()).path("figures")) {
                long lines = figure.path("lines").asLong();
                if (lines < 2 || lines > 40) {
                    continue;
                }
                String route = String.format(ACTIVITY, figure.path("glAccountCode").asText(), month, figure.path("currency").asText());
                Set<String> served = new HashSet<>();
                String cursor = null;
                int pages = 0;
                do {
                    HttpResponse<String> page = served(accountant, route + "&limit=1" + (cursor == null ? "" : "&cursor=" + cursor));
                    for (JsonNode line : JSON.readTree(page.body()).path("lines")) {
                        assertThat(served.add(line.path("entryId").asText() + "/" + line.path("seq").asInt())).as("no line twice").isTrue();
                    }
                    cursor = next(page);
                    assertThat((long) ++pages).as("the walk ends").isLessThanOrEqualTo(lines + 1);
                } while (cursor != null);
                assertThat(served).as("%s: every line the figure counts, the last page naming no next", route).hasSize((int) lines);
                return;
            }
        }
    }

    @Test
    @DisplayName("each list refuses what section 7 refuses, 422 api.ValidationFailed: a limit over its maximum or under one, a"
            + " cursor it did not write - another list's included - and the drill-downs the same, before any run or figure is read")
    void eachRefusesWhatSectionSevenRefuses() throws Exception {
        String reporter = sessionWith(RoleName.REGULATORY_REPORTER);
        String accountant = sessionWith(RoleName.ACCOUNTANT);
        refuses(reporter, OBLIGATIONS + "?", 100, OperatorCollection.cursor("accounting.regulatory-submissions", "1", UUID.randomUUID().toString()));
        refuses(reporter, SUBMISSIONS + "?", 100, OperatorCollection.cursor("accounting.regulatory-obligations", "2026-01-01T00:00:00Z", UUID.randomUUID().toString()));
        refuses(accountant, BREAKS + "?period=2026-01&", 500, OperatorCollection.cursor("accounting.regulatory-obligations", "2026-01-01T00:00:00Z", UUID.randomUUID().toString()));
        refuses(accountant, String.format(ACTIVITY, "1000", "2026-01", "EUR") + "&", 500, OperatorCollection.cursor("accounting.breaks", UUID.randomUUID().toString()));
        refuses(accountant, String.format(LINES, IDS.next()) + "&", 500, OperatorCollection.cursor("accounting.breaks", UUID.randomUUID().toString()));
        // A drill-down cursor of another month: a position outside the period read.
        HttpResponse<String> elsewhere = get(accountant, String.format(ACTIVITY, "1000", "2026-01", "EUR") + "&cursor="
                + OperatorCollection.cursor("accounting.gl-lines", "2026-02-01", UUID.randomUUID().toString(), "0"));
        assertThat(elsewhere.statusCode()).as(elsewhere.body()).isEqualTo(422);
    }

    // ------------------------------------------------------------------------------------------- plumbing

    private void refuses(String token, String route, int maximum, String otherAccountingCursor) throws Exception {
        for (String query : new String[] {"limit=" + (maximum + 1), "limit=0", "limit=many", "cursor=" + FOREIGN,
                "cursor=" + otherAccountingCursor, "cursor=not-a-cursor"}) {
            HttpResponse<String> refused = get(token, route + query);
            assertThat(refused.statusCode()).as("%s%s: %s", route, query, refused.body()).isEqualTo(422);
            assertThat(refused.body()).as(route + query).contains("api.ValidationFailed");
        }
    }

    /** Every page from the first to the one naming no next: the items' identifiers, in the order served. */
    private List<String> walk(String token, String route, String idField) throws Exception {
        List<String> walked = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            HttpResponse<String> page = served(token, route + (cursor == null ? "" : "&cursor=" + cursor));
            walked.addAll(ids(page, idField));
            cursor = next(page);
            assertThat(++pages).as("the walk ends").isLessThan(1000);
        } while (cursor != null);
        return walked;
    }

    private static String next(HttpResponse<String> page) throws Exception {
        JsonNode next = JSON.readTree(page.body()).get("nextCursor");
        assertThat(next == null || next.isTextual()).as("nextCursor is absent or a cursor, never null: %s", page.body()).isTrue();
        return next == null ? null : next.asText();
    }

    private static List<String> ids(HttpResponse<String> page, String idField) throws Exception {
        List<String> ids = new ArrayList<>();
        for (JsonNode item : JSON.readTree(page.body()).path("items")) {
            ids.add(item.path(idField).asText());
        }
        return ids;
    }

    /** An obligation version proposed under a form of the case's own - never activated, so nothing drafts from it. */
    private static String proposed() {
        RegulatoryObligations obligations = new RegulatoryObligations(new JdbcRegulatoryObligationStore(),
                new RegulatoryAdapters(List.of(new ScriptedRegulatoryProvider())),
                ReportDefinitions.of(new TestReportDefinition(RegulatoryWorld.SOURCE, 1, Set.of(), false, (snapshot, inputs) -> CanonicalJson.object())),
                new JdbcAuditWriter(), IDS, NOW);
        Actor proposer = person();
        String form = "LIST-PAGE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return inScope(proposer, uow -> obligations.propose(uow, new RegulatoryObligations.Proposal(ScriptedRegulatoryProvider.JURISDICTION, form, 1,
                ScriptedRegulatoryProvider.NAME, RegulatoryWorld.configuration()), "P17-TSK-122 walk", proposer, correlation())).id().value().toString();
    }

    /** A break raised by the system in {@code month}, as a check raises one. */
    private static void raise(YearMonth month) {
        AccountingBreaks breaks = new AccountingBreaks(new JdbcAccountingBreakStore(), new LedgerJournalReads(), new JdbcAuditWriter(),
                new JdbcOutboxWriter(), IDS, NOW);
        BreakFinding finding = new BreakFinding(CloseCheckCode.RULE_CONFORMANCE, BreakType.SHAPE_MISMATCH, "shape:" + IDS.next(),
                "planted by the list walk", "posting-rules:v2");
        boolean raised = inScope(Actor.SYSTEM, uow -> breaks.raise(uow, month, finding, Actor.SYSTEM, correlation()));
        assertThat(raised).isTrue();
    }

    /** The latest months the journal holds entries in, newest first. */
    private static List<String> monthsWithEntries() throws SQLException {
        List<String> months = new ArrayList<>();
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement("SELECT DISTINCT to_char(posting_date, 'YYYY-MM') AS month FROM ledger.journal_entry"
                        + " WHERE posting_date >= DATE '2000-01-01' AND posting_date < DATE '2200-01-01' ORDER BY month DESC LIMIT 24");
                ResultSet rows = read.executeQuery()) {
            while (rows.next()) {
                months.add(rows.getString(1));
            }
        }
        return months;
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

    /** A role holder's {@code MULTI_FACTOR} session, as the operator lists' suite makes one. */
    private String sessionWith(RoleName role) throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app, "INSERT INTO party.party (id, kind, display_name, registered_at) VALUES (?, 'PERSON', 'Accounting Pages Person', now())",
                    party);
            execute(app, "INSERT INTO identity.identity (id, party_id, login_identifier, status, created_at, status_changed_at)"
                    + " VALUES (?, ?, ?, 'ACTIVE', now(), now())",
                    identity, party, "ap" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
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
            sessions.insert(app, com.finapp.identity.Session.issue(IDS, NOW, IdentityId.of(identity), SessionToken.of(plaintext),
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
