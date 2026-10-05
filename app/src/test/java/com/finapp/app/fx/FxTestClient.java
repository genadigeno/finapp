package com.finapp.app.fx;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.fx.JdbcRateSnapshotStore;
import com.finapp.fx.RateObservation;
import com.finapp.fx.ReferencePair;
import com.finapp.fx.ReferenceSourceDeclaration;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingService;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.ExchangeRate;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The FX suites' shared client (`P9-TSK-009`): customers over HTTP, their wallets opened and funded,
 * fresh references, quotes and conversions - and the simulator's rates for all twenty directions.
 */
final class FxTestClient {

    static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    static final String PASSWORD = "a-perfectly-fine-pw-7";

    /** The reference mid per canonical pair; the simulator quotes these and their inverses. */
    static final Map<String, String> MIDS = Map.of(
            "EUR/GBP", "0.8543", "EUR/USD", "1.0850", "EUR/JPY", "162.21", "EUR/BHD", "0.4080",
            "GBP/USD", "1.2700", "GBP/JPY", "189.87", "GBP/BHD", "0.4776", "USD/JPY", "149.50",
            "USD/BHD", "0.3760", "BHD/JPY", "397.5121");

    /** A verified customer: their session, login and party. */
    record Customer(String token, String login, UUID party) {}

    private final int port;

    FxTestClient(int port) {
        this.port = port;
    }

    /** Sets the simulator's rate for every direction from the mids, then the overrides. */
    static void rates(SimulatedFxEngine engine, Map<String, String> overrides) {
        for (Map.Entry<String, String> mid : MIDS.entrySet()) {
            String[] pair = mid.getKey().split("/");
            BigDecimal forward = new BigDecimal(mid.getValue()).setScale(10, RoundingMode.UNNECESSARY);
            engine.rate(pair[0] + "/" + pair[1], forward.toPlainString());
            engine.rate(pair[1] + "/" + pair[0], BigDecimal.ONE.divide(forward, 10, RoundingMode.HALF_EVEN).toPlainString());
        }
        overrides.forEach(engine::rate);
    }

    /** A fresh reference observation for every canonical pair. */
    static void freshReferences() throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            for (Map.Entry<String, String> mid : MIDS.entrySet()) {
                String[] pair = mid.getKey().split("/");
                ReferencePair canonical = ReferencePair.of(pair[0], pair[1]);
                new JdbcRateSnapshotStore().record(app, ReferenceSourceDeclaration.SOURCE,
                        new RateObservation(ExchangeRate.of(canonical.base(), canonical.quote(), new BigDecimal(mid.getValue())),
                                Instant.now()),
                        UUID.randomUUID());
            }
            app.commit();
        }
    }

    Customer verifiedCustomer() throws Exception {
        String login = "fxc." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(post("/v1/registrations", "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                        + "\"password\":\"" + PASSWORD + "\"}", null, key()).statusCode())
                .isEqualTo(201);
        UUID party;
        try (Connection app = DatabaseRoles.application()) {
            try (PreparedStatement update = app.prepareStatement(
                    "UPDATE party.customer SET status = 'ACTIVE', status_changed_at = GREATEST(now(), opened_at)"
                            + " WHERE party_id = (SELECT party_id FROM identity.identity WHERE login_identifier = ?)")) {
                update.setString(1, login);
                update.executeUpdate();
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

    /** Opens the customer's WALLET agreement in {@code currency}; returns the agreement's id. */
    UUID openWallet(Customer customer, String currency) throws Exception {
        HttpResponse<String> opened = post("/v1/me/accounts",
                "{\"productType\":\"WALLET\",\"currency\":\"" + currency + "\"}", customer.token(), key());
        assertThat(opened.statusCode()).as(opened.body()).isEqualTo(201);
        return UUID.fromString(field(opened.body(), "id"));
    }

    void addCurrency(Customer customer, UUID product, String currency) throws Exception {
        HttpResponse<String> added = post("/v1/me/accounts/" + product + "/currencies",
                "{\"currency\":\"" + currency + "\"}", customer.token(), key());
        assertThat(added.statusCode()).as(added.body()).isEqualTo(201);
    }

    /** Credits the agreement's wallet in the amount's currency from FEE_REVENUE (no reconciled position). */
    @SuppressWarnings("try")
    static void fund(PostingService postings, UUID product, Money amount) throws Exception {
        JdbcLedgerAccountStore accounts = new JdbcLedgerAccountStore();
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            LedgerAccount wallet = accounts.findOwned(app, product, AccountPurpose.CUSTOMER_WALLET, amount.currency())
                    .orElseThrow();
            LedgerAccount revenue = accounts.findOperational(app, AccountPurpose.FEE_REVENUE, amount.currency())
                    .orElseThrow();
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            postings.post(app, new PostingCommand("fund-" + IDS.next(), today, today, "fx suite funding", List.of(
                    new JournalLine(revenue.id(), Direction.DEBIT, amount),
                    new JournalLine(wallet.id(), Direction.CREDIT, amount))));
            app.commit();
        }
    }

    static Money money(String amount, String currency) {
        return Money.of(new BigDecimal(amount), CurrencyCode.of(currency));
    }

    HttpResponse<String> quote(Customer customer, String source, String destination, String side, String amount)
            throws Exception {
        return post("/v1/me/fx/quotes", "{\"sourceCurrency\":\"" + source + "\",\"destinationCurrency\":\"" + destination
                + "\",\"fixedSide\":\"" + side + "\",\"amount\":\"" + amount + "\"}", customer.token(), key());
    }

    String quoteId(Customer customer, String source, String destination, String side, String amount) throws Exception {
        HttpResponse<String> quoted = quote(customer, source, destination, side, amount);
        assertThat(quoted.statusCode()).as(quoted.body()).isEqualTo(201);
        return field(quoted.body(), "id");
    }

    HttpResponse<String> convert(Customer customer, String quoteId, String key) throws Exception {
        return post("/v1/me/fx/conversions", "{\"quoteId\":\"" + quoteId + "\"}", customer.token(), key);
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
        try (Connection app = DatabaseRoles.application(); PreparedStatement select = app.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                select.setObject(i + 1, parameters[i]);
            }
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    static String scalar(String sql, Object... parameters) throws Exception {
        try (Connection app = DatabaseRoles.application(); PreparedStatement select = app.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                select.setObject(i + 1, parameters[i]);
            }
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? row.getString(1) : null;
            }
        }
    }
}
