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
 *
 * <p><strong>An MFA-assured session by default</strong> (the Phase 10 to 11 transition, the owner's decision of
 * 2026-10-10): submitting a credit decision request requires a {@code MULTI_FACTOR} session, so {@link #customer} enrols a
 * TOTP factor and steps up through {@code POST /v1/authentications/mfa}; {@link #passwordOnlyCustomer} is the session a
 * login alone yields, for the refusals.
 */
final class CreditTestClient {

    static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    static final String PASSWORD = "a-perfectly-fine-pw-7";
    static final String REQUESTS = "/v1/me/credit/decision-requests";

    /** A customer: their session, login and party - and their factor's secret, when one is enrolled. */
    record Customer(String token, String login, UUID party, Sensitive<String> secret) {

        Customer(String token, String login, UUID party) {
            this(token, login, party, null);
        }
    }

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

    /**
     * A registered customer, {@code ACTIVE} when {@code verified}, otherwise still {@code PENDING}; no consent - on an
     * MFA-assured session: a TOTP factor enrolled and confirmed, and the login's session stepped up with it.
     */
    Customer customer(boolean verified) throws Exception {
        Customer password = passwordOnlyCustomer(verified);
        Sensitive<String> secret = Sensitive.of(newFactor(password.token()));
        long confirmed = confirm(secret, password.token());
        // The step after the one the confirmation consumed - a code the server's window still admits (see confirm).
        HttpResponse<String> elevated = post("/v1/authentications/mfa", "{\"code\":\"" + codeAt(secret, confirmed + 1)
                + "\"}", password.token(), null);
        if (elevated.statusCode() == 401) {
            // The server's step moved on between the two calls: the next one.
            elevated = post("/v1/authentications/mfa", "{\"code\":\"" + codeAt(secret, confirmed + 2) + "\"}",
                    password.token(), null);
        }
        assertThat(elevated.statusCode()).as("the step-up: %s", elevated.body()).isEqualTo(200);
        assertThat(field(elevated.body(), "assurance")).isEqualTo("MULTI_FACTOR");
        return new Customer(field(elevated.body(), "sessionToken"), password.login(), password.party(), secret);
    }

    /** A registered customer on the {@code PASSWORD} session a login alone yields - no factor enrolled. */
    Customer passwordOnlyCustomer(boolean verified) throws Exception {
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
        confirm(Sensitive.of(newFactor(sessionToken)), sessionToken);
    }

    /** A new {@code PASSWORD} session for {@code customer}'s login - a login alone, whatever factor is enrolled. */
    String login(Customer customer) throws Exception {
        HttpResponse<String> session = post("/v1/authentications",
                "{\"loginIdentifier\":\"" + customer.login() + "\",\"password\":\"" + PASSWORD + "\"}", null, key());
        return field(session.body(), "sessionToken");
    }

    /** A new TOTP factor enrolled on {@code sessionToken}'s identity - its base32 secret. */
    private String newFactor(String sessionToken) throws Exception {
        Matcher matcher = Pattern.compile("secret=([A-Z2-7]+)").matcher(post("/v1/me/mfa", null, sessionToken, null).body());
        assertThat(matcher.find()).as("the response must carry a base32 secret").isTrue();
        return matcher.group(1);
    }

    /**
     * Confirms the factor and answers the step the confirmation consumed. Confirmed with the PREVIOUS step's code (inside
     * the window) so the current one is left for the step-up - and, should the server's clock run ahead of this JVM's
     * (the storm skews its instances by up to five seconds) so that the previous step has left its window, with the
     * current step's: one refused attempt at most, never near a lock.
     */
    private long confirm(Sensitive<String> secret, String sessionToken) throws Exception {
        long step = Instant.now().getEpochSecond() / TotpParameters.current().periodSeconds();
        for (long candidate : new long[] {step - 1, step, step + 1}) {
            int status = post("/v1/me/mfa/confirmation", "{\"code\":\"" + codeAt(secret, candidate) + "\"}",
                    sessionToken, null).statusCode();
            if (status == 204) {
                return candidate;
            }
        }
        throw new AssertionError("no step around this JVM's confirmed the factor");
    }

    private static String codeAt(Sensitive<String> secret, long step) {
        return Authenticator.codeAt(secret, TotpParameters.current(),
                Instant.ofEpochSecond(step * TotpParameters.current().periodSeconds()));
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
