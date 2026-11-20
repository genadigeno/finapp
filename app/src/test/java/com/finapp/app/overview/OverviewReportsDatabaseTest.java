package com.finapp.app.overview;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finapp.app.api.CorrelationFilter;
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
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The operations overview's reports over HTTP (`P17-TSK-039`, gaps G-04, G-150, G-151): the customers and transfers reports and
 * the payments report, all three under {@code PLATFORM_OVERVIEW_READ} - the {@code OPERATIONS_VIEWER}'s one permission - each
 * stating its {@code asOf}, the database's instant, and each serving recorded; the viewer refused every other report; the support
 * agent no longer reading the payments report.
 *
 * <p>Shared-database safe: the day each case reads is a random day before 2021 that only its own rows occupy, and every record is
 * judged by the answer's own correlation - another suite's rows or records never count.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the operations overview's reports (P17-TSK-039)")
class OverviewReportsDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> STATUSES = List.of("PENDING", "ACTIVE", "SUSPENDED", "CLOSED", "REJECTED");

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    @Test
    @DisplayName("the customers report: every status's standing count summing to the total, the day's openings and its"
            + " changes after an opening, its asOf the database's instant, recorded once")
    void theCustomersReportCountsTheDay() throws Exception {
        String viewer = sessionWith(RoleName.OPERATIONS_VIEWER);
        LocalDate day = aQuietDay();
        Instant noon = day.atStartOfDay(ZoneOffset.UTC).toInstant().plus(Duration.ofHours(12));
        Map<String, long[]> before = customerCounts(get("/v1/operator/reports/customers?date=" + day, viewer));

        customer("ACTIVE", noon, noon); // opened on the day, never changed
        customer("SUSPENDED", noon, noon.plus(Duration.ofHours(2))); // opened and changed on the day
        customer("CLOSED", noon.minus(Duration.ofDays(3)), noon.plus(Duration.ofHours(3))); // changed on the day only
        customer("ACTIVE", noon.plus(Duration.ofDays(1)), noon.plus(Duration.ofDays(1))); // the next day: not this one's

        Instant earliest = databaseNow();
        HttpResponse<String> answer = get("/v1/operator/reports/customers?date=" + day, viewer);
        Instant latest = databaseNow();
        assertThat(answer.statusCode()).as(answer.body()).isEqualTo(200);
        JsonNode report = JSON.readTree(answer.body());
        assertThat(report.get("date").asText()).isEqualTo(day.toString());
        assertAsOfBetween(report, earliest, latest);
        Map<String, long[]> after = customerCounts(answer);
        assertThat(after.keySet()).as("every lifecycle status, one nobody stands in as zero").containsExactlyElementsOf(STATUSES);
        assertThat(delta(after, before, "ACTIVE")).containsExactly(1, 0);
        assertThat(delta(after, before, "SUSPENDED")).containsExactly(1, 1);
        assertThat(delta(after, before, "CLOSED")).containsExactly(0, 1);
        assertThat(report.get("total").asLong()).as("the total is the standing counts' sum")
                .isEqualTo(after.values().stream().mapToLong(counts -> counts[0]).sum());
        assertThat(recordsOf(answer, "party.CustomersReportRead")).as("the serving recorded").isEqualTo(1);
    }

    @Test
    @DisplayName("the transfers report: the day's transfers by outcome and currency, counted and totalled exactly at their"
            + " stored scale, the day before and after left out; its asOf the database's instant, recorded once")
    void theTransfersReportTotalsTheDay() throws Exception {
        String viewer = sessionWith(RoleName.OPERATIONS_VIEWER);
        LocalDate day = aQuietDay();
        Instant start = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        transfer(start, "COMPLETED", null, "EUR", 2, 10_000);
        transfer(start.plus(Duration.ofHours(23)).plusSeconds(3599), "COMPLETED", null, "EUR", 2, 2_345);
        transfer(start.plus(Duration.ofHours(9)), "FAILED", "INSUFFICIENT_FUNDS", "EUR", 2, 500);
        transfer(start.plus(Duration.ofHours(10)), "COMPLETED", null, "BHD", 3, 1_234);
        transfer(start.minusMillis(1), "COMPLETED", null, "EUR", 2, 99_999); // the day before
        transfer(start.plus(Duration.ofDays(1)), "COMPLETED", null, "EUR", 2, 88_888); // the day after

        Instant earliest = databaseNow();
        HttpResponse<String> answer = get("/v1/operator/reports/transfers?date=" + day, viewer);
        Instant latest = databaseNow();
        assertThat(answer.statusCode()).as(answer.body()).isEqualTo(200);
        JsonNode report = JSON.readTree(answer.body());
        assertAsOfBetween(report, earliest, latest);
        assertThat(report.get("outcomes")).hasSize(3);
        assertThat(answer.body()).contains(
                "{\"status\":\"COMPLETED\",\"currency\":\"BHD\",\"count\":1,\"amount\":\"1.234\"}",
                "{\"status\":\"COMPLETED\",\"currency\":\"EUR\",\"count\":2,\"amount\":\"123.45\"}",
                "{\"status\":\"FAILED\",\"failureReason\":\"INSUFFICIENT_FUNDS\",\"currency\":\"EUR\",\"count\":1,\"amount\":\"5.00\"}");
        assertThat(recordsOf(answer, "transfers.TransfersReportRead")).as("the serving recorded").isEqualTo(1);
    }

    @Test
    @DisplayName("PLATFORM_OVERVIEW_READ is the overview's one permission: the viewer reads all three reports and no other;"
            + " a support agent and a ledger operator are refused the three; a malformed day is refused, nothing recorded")
    void theOverviewIsOnePermission() throws Exception {
        String viewer = sessionWith(RoleName.OPERATIONS_VIEWER);
        String today = LocalDate.now(CLOCK).toString();
        List<String> overview = List.of("/v1/operator/reports/customers?date=" + today,
                "/v1/operator/reports/transfers?date=" + today, "/v1/operator/reports/payments?date=" + today);
        for (String report : overview) {
            HttpResponse<String> answer = get(report, viewer);
            assertThat(answer.statusCode()).as(report + ": " + answer.body()).isEqualTo(200);
            assertThat(answer.body()).as(report).containsPattern("\"asOf\":\"[0-9-]+T[0-9:.]+Z\"");
        }
        for (String other : List.of("/v1/operator/reports/fx/position", "/v1/operator/reports/risk/alerts",
                "/v1/operator/reports/reconciliation/summary", "/v1/operator/reports/chargeback-ratio",
                "/v1/operator/events/dead-letters")) {
            assertThat(get(other, viewer).statusCode()).as("the viewer holds only the overview: %s", other).isEqualTo(403);
        }
        for (RoleName refused : List.of(RoleName.CUSTOMER_SUPPORT_AGENT, RoleName.LEDGER_OPERATOR)) {
            String session = sessionWith(refused);
            for (String report : overview) {
                assertThat(get(report, session).statusCode()).as("%s at %s", refused, report).isEqualTo(403);
            }
        }
        for (String report : overview) {
            assertThat(get(report, null).statusCode()).as("no session at %s", report).isEqualTo(401);
        }
        for (String base : List.of("/v1/operator/reports/customers", "/v1/operator/reports/transfers")) {
            for (String refused : List.of(base, base + "?date=1999-12-31", base + "?date=2026-02-30", base + "?date=today")) {
                HttpResponse<String> answer = get(refused, viewer);
                assertThat(answer.statusCode()).as(refused).isEqualTo(422);
                assertThat(recordsOf(answer) - recordsOf(answer, "door.ActRefused")).as("nothing but the refusal: %s", refused)
                        .isZero();
            }
        }
    }

    // ----------------------------------------------------------------- fixtures

    /** A random day of 2001-2020: no other suite writes customers or transfers there. */
    private static LocalDate aQuietDay() {
        return LocalDate.of(2001, 1, 1).plusDays(ThreadLocalRandom.current().nextInt(7_300));
    }

    private static void customer(String status, Instant openedAt, Instant changedAt) throws SQLException {
        UUID party = IDS.next();
        raw("INSERT INTO party.party (id, kind, display_name, registered_at) VALUES (?, 'PERSON', 'Overview Fixture', ?)",
                party, Timestamp.from(openedAt));
        raw("INSERT INTO party.customer (id, party_id, status, opened_at, status_changed_at) VALUES (?, ?, ?, ?, ?)",
                IDS.next(), party, status, Timestamp.from(openedAt), Timestamp.from(changedAt));
    }

    /** A transfer initiated at {@code at}, its UUIDv7 identifier minted at that millisecond as the platform's are. */
    private static void transfer(Instant at, String status, String failureReason, String currency, int scale, long minor)
            throws SQLException {
        UUID id = new IdGenerator(Clock.fixed(at, ZoneOffset.UTC), new SecureRandom()).next();
        boolean moved = !status.equals("FAILED");
        UUID account = IDS.next();
        raw("INSERT INTO transfers.transfer (id, customer_id, source_account_id, destination_account_id, amount_minor, currency,"
                        + " scale, status, failure_reason, journal_entry_id, initiated_by, initiated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, IDS.next(), account, IDS.next(), minor, currency, (short) scale, status, failureReason,
                moved ? IDS.next() : null, IDS.next(), Timestamp.from(at));
    }

    // ----------------------------------------------------------------- reads

    private static Map<String, long[]> customerCounts(HttpResponse<String> answer) throws Exception {
        assertThat(answer.statusCode()).as(answer.body()).isEqualTo(200);
        Map<String, long[]> counts = new java.util.LinkedHashMap<>();
        for (JsonNode row : JSON.readTree(answer.body()).get("statuses")) {
            counts.put(row.get("status").asText(), new long[] {
                    row.get("standing").asLong(), row.get("openedOnDay").asLong(), row.get("changedOnDay").asLong()});
        }
        return counts;
    }

    /** The day's openings and changes of {@code status} added between two readings. */
    private static long[] delta(Map<String, long[]> after, Map<String, long[]> before, String status) {
        return new long[] {after.get(status)[1] - before.get(status)[1], after.get(status)[2] - before.get(status)[2]};
    }

    /** The as-of is the database's: between two readings of its clock (a second's grace for a VM clock that steps, ADR-0063). */
    private static void assertAsOfBetween(JsonNode report, Instant earliest, Instant latest) {
        Instant asOf = Instant.parse(report.get("asOf").asText());
        assertThat(asOf).as("the database's instant of the read")
                .isBetween(earliest.minusSeconds(1), latest.plusSeconds(1));
    }

    private static Instant databaseNow() throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement("SELECT statement_timestamp()");
                ResultSet row = read.executeQuery()) {
            assertThat(row.next()).isTrue();
            return row.getTimestamp(1).toInstant();
        }
    }

    /** The records the answer's own request wrote - by its correlation, so another suite's writes never count. */
    private static long recordsOf(HttpResponse<String> answer) throws SQLException {
        return count("SELECT count(*) FROM platform.audit_record WHERE correlation_id = ?", correlation(answer));
    }

    private static long recordsOf(HttpResponse<String> answer, String operation) throws SQLException {
        return count("SELECT count(*) FROM platform.audit_record WHERE correlation_id = ? AND operation = ?",
                correlation(answer), operation);
    }

    private static String correlation(HttpResponse<String> answer) {
        return answer.headers().firstValue(CorrelationFilter.HEADER)
                .orElseThrow(() -> new AssertionError("every answer carries its correlation: " + answer.uri()));
    }

    // ----------------------------------------------------------------- sessions and HTTP

    private String sessionWith(RoleName role) throws Exception {
        String login = "overview." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        HttpResponse<String> registration = post("/v1/registrations", "{\"loginIdentifier\":\"" + login
                + "\",\"displayName\":\"Ada Lovelace\",\"password\":\"" + PASSWORD + "\"}", null, UUID.randomUUID().toString());
        assertThat(registration.statusCode()).isEqualTo(201);
        UUID identity;
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement("SELECT id FROM identity.identity WHERE login_identifier = ?")) {
            read.setString(1, login);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                identity = row.getObject("id", UUID.class);
            }
        }
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
            app.commit();
        }
        String token = field(post("/v1/authentications", "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD
                + "\"}", null, UUID.randomUUID().toString()).body(), "sessionToken");
        return com.finapp.app.session.OperatorStepUp.elevated(token, this::post);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return send(request.build());
    }

    private HttpResponse<String> post(String path, String body, String token, String key) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (key != null) {
            request.header(IdempotencyKeyHeader.NAME, key);
        }
        return send(request.build());
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static void raw(String sql, Object... arguments) throws SQLException {
        try (Connection app = DatabaseRoles.application(); PreparedStatement statement = app.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            statement.executeUpdate();
        }
    }

    private static long count(String sql, Object... arguments) throws SQLException {
        try (Connection app = DatabaseRoles.application(); PreparedStatement read = app.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                read.setObject(i + 1, arguments[i]);
            }
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private static String field(String body, String name) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("the body must carry %s: %s", name, body).isTrue();
        return matcher.group(1);
    }
}
