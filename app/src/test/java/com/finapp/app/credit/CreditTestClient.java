package com.finapp.app.credit;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.consent.ConsentPurpose;
import com.finapp.consent.ConsentRecord;
import com.finapp.consent.JdbcConsentStore;
import com.finapp.identity.Authenticator;
import com.finapp.identity.TotpParameters;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.security.Sensitive;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The credit customer suites' HTTP client (`P10-TSK-014`; the {@code FxTestClient} shape): a registered customer -
 * verified ({@code ACTIVE}) or not, consenting to the credit purposes or not - its session, and the doors.
 */
final class CreditTestClient {

    static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    static final String PASSWORD = "a-perfectly-fine-pw-7";
    static final String REQUESTS = "/v1/me/credit/decision-requests";

    /** A customer: their session, login and party. */
    record Customer(String token, String login, UUID party) {}

    private final int port;

    CreditTestClient(int port) {
        this.port = port;
    }

    /** A customer KYC has approved ({@code ACTIVE}), consenting to both credit purposes. */
    Customer consentingCustomer() throws Exception {
        Customer customer = customer(true);
        consent(customer.party(), ConsentPurpose.CREDIT_BUREAU_ACCESS);
        consent(customer.party(), ConsentPurpose.FINANCIAL_DATA_ACCESS);
        return customer;
    }

    /** A registered customer, {@code ACTIVE} when {@code verified}, otherwise still {@code PENDING}; no consent. */
    Customer customer(boolean verified) throws Exception {
        String login = "crd." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(post("/v1/registrations", "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                + "\"password\":\"" + PASSWORD + "\"}", null, key()).statusCode()).isEqualTo(201);
        UUID party;
        try (Connection app = DatabaseRoles.application()) {
            if (verified) {
                try (PreparedStatement update = app.prepareStatement(
                        "UPDATE party.customer SET status = 'ACTIVE', status_changed_at = GREATEST(now(), opened_at)"
                                + " WHERE party_id = (SELECT party_id FROM identity.identity WHERE login_identifier = ?)")) {
                    update.setString(1, login);
                    update.executeUpdate();
                }
            }
            try (PreparedStatement select = app.prepareStatement(
                    "SELECT party_id FROM identity.identity WHERE login_identifier = ?")) {
                select.setString(1, login);
                try (ResultSet row = select.executeQuery()) {
                    row.next();
                    party = row.getObject(1, UUID.class);
                }
            }
        }
        HttpResponse<String> session = post("/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}", null, key());
        return new Customer(field(session.body(), "sessionToken"), login, party);
    }

    static void consent(UUID party, ConsentPurpose purpose) throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            new JdbcConsentStore().append(app, ConsentRecord.grant(IDS, Clock.systemUTC(), party, purpose, 1));
        }
    }

    static void withdraw(UUID party, ConsentPurpose purpose) throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            new JdbcConsentStore().append(app, ConsentRecord.withdrawal(IDS, Clock.systemUTC(), party, purpose, 1));
        }
    }

    /** Enrols and confirms a TOTP factor on {@code sessionToken}'s identity - which then needs a step-up. */
    void enrolAndConfirm(String sessionToken) throws Exception {
        Matcher matcher = Pattern.compile("secret=([A-Z2-7]+)").matcher(post("/v1/me/mfa", null, sessionToken, null).body());
        assertThat(matcher.find()).as("the response must carry a base32 secret").isTrue();
        Sensitive<String> secret = Sensitive.of(matcher.group(1));
        long step = Instant.now().getEpochSecond() / TotpParameters.current().periodSeconds();
        String confirming = Authenticator.codeAt(secret, TotpParameters.current(),
                Instant.ofEpochSecond((step - 1) * TotpParameters.current().periodSeconds()));
        assertThat(post("/v1/me/mfa/confirmation", "{\"code\":\"" + confirming + "\"}", sessionToken, null).statusCode())
                .isEqualTo(204);
    }

    /** A loan request body: {@code amount} EUR over {@code term} months. */
    static String loan(String amount, int term) {
        return "{\"product\":\"PERSONAL_LOAN\",\"amount\":\"" + amount + "\",\"currency\":\"EUR\",\"termMonths\":" + term
                + ",\"declaredMonthlyIncome\":\"3200.00\",\"declaredMonthlyExpenditure\":\"1400.00\"}";
    }

    /** A credit line request body: {@code amount} EUR, no term. */
    static String line(String amount) {
        return "{\"product\":\"CREDIT_LINE\",\"amount\":\"" + amount + "\",\"currency\":\"EUR\"}";
    }

    HttpResponse<String> submit(Customer customer, String body, String key) throws Exception {
        return post(REQUESTS, body, customer.token(), key);
    }

    HttpResponse<String> cancel(Customer customer, String id, String key) throws Exception {
        return post(REQUESTS + "/" + id + "/cancellation", null, customer.token(), key);
    }

    HttpResponse<String> read(Customer customer, String id) throws Exception {
        return get(REQUESTS + "/" + id, customer.token());
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
        try (Connection owner = DatabaseRoles.migrator(); PreparedStatement select = owner.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                select.setObject(i + 1, parameters[i]);
            }
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }
}
