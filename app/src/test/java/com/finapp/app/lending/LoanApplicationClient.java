package com.finapp.app.lending;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.testing.database.DatabaseRoles;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The loan application suites' HTTP client (`P11-TSK-010`; the {@code CreditTestClient} shape): the three doors, the
 * bodies, and counts read as the schema owner.
 */
final class LoanApplicationClient {

    static final String APPLICATIONS = "/v1/me/loan-applications";

    private final int port;

    LoanApplicationClient(int port) {
        this.port = port;
    }

    /** A personal loan application: {@code amount} EUR over {@code term} months, repaid on {@code day}. */
    static String loan(String amount, int term, int day) {
        return "{\"product\":\"PERSONAL_LOAN\",\"amount\":\"" + amount + "\",\"currency\":\"EUR\",\"termMonths\":" + term
                + ",\"repaymentDay\":" + day + ",\"repaymentMethod\":\"WALLET\",\"payoutPreference\":\"WALLET\","
                + "\"declaredMonthlyIncome\":\"3200.00\",\"declaredMonthlyExpenditure\":\"1400.00\"}";
    }

    static String loan(String amount, int term) {
        return loan(amount, term, 5);
    }

    /** A credit line application: a limit of {@code amount} EUR, stated on day 5. */
    static String line(String amount) {
        return "{\"product\":\"CREDIT_LINE\",\"amount\":\"" + amount + "\",\"currency\":\"EUR\",\"repaymentDay\":5,"
                + "\"repaymentMethod\":\"WALLET\"}";
    }

    HttpResponse<String> submit(String token, String body, String key) throws Exception {
        return post(APPLICATIONS, body, token, key);
    }

    HttpResponse<String> read(String token, String id) throws Exception {
        return get(APPLICATIONS + "/" + id, token);
    }

    HttpResponse<String> withdraw(String token, String id, String key) throws Exception {
        return post(APPLICATIONS + "/" + id + "/withdrawal", null, token, key);
    }

    HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path)).GET();
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return send(request.build());
    }

    HttpResponse<String> post(String path, String body, String bearer, String key) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
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

    static String key() {
        return UUID.randomUUID().toString();
    }

    static String field(String body, String name) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("the body must carry %s: %s", name, body).isTrue();
        return matcher.group(1);
    }

    static long count(String sql, Object... parameters) throws Exception {
        String value = scalar(sql, parameters);
        return value == null ? 0 : Long.parseLong(value);
    }

    static String scalar(String sql, Object... parameters) throws Exception {
        try (Connection owner = DatabaseRoles.migrator(); PreparedStatement select = owner.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                select.setObject(i + 1, parameters[i]);
            }
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? row.getString(1) : null;
            }
        }
    }
}
