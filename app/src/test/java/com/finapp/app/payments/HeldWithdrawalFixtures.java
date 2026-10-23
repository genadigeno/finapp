package com.finapp.app.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingService;
import com.finapp.payments.ProviderReference;
import com.finapp.payments.SimulatedInstantSchemeAdapter;
import com.finapp.payments.Withdrawals;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
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
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The held withdrawal's database fixtures (`P13-TSK-009`), shared by {@code WithdrawalRiskSeamDatabaseTest} and
 * {@code WithdrawalHoldDatabaseTest}: a customer registered over HTTP and promoted (the endpoint suites' shape), a
 * wallet opened over the real endpoint and funded by a real posting from the operational fee-revenue account, the
 * wallet's {@link Withdrawals.Resolved} for the engine's own door, a bank account registered through the real grant
 * exchange for the HTTP door, and the counters - the tables read as the migrator.
 */
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
final class HeldWithdrawalFixtures {

    static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    static final CurrencyCode EUR = CurrencyCode.of("EUR");
    static final String PASSWORD = "a-perfectly-fine-pw-7";

    /** Words no customer answer about a judged withdrawal may carry (INV-RSK-08). */
    static final List<String> RISK_WORDS = List.of("risk_unavailable", "amount_band", "velocity", "score",
            "assessment", "challenge", "screening", "rule", "reason1", "no_policy", "hold_expired", "held_until",
            "case");

    /** One customer: the login, a session token, the identity, the party, the customer, the wallet product. */
    record Customer(String login, String token, UUID identity, UUID party, UUID customer, UUID product) {}

    private final int port;
    private final SimulatedProvider provider;

    HeldWithdrawalFixtures(int port, SimulatedProvider provider) {
        this.port = port;
        this.provider = provider;
    }

    /** A promoted customer with a EUR wallet funded with {@code minor}. */
    Customer funded(long minor) throws Exception {
        Customer customer = customer();
        fund(customer.product(), EUR, minor);
        return customer;
    }

    /** Registers over HTTP, promotes the customer to ACTIVE, signs in, opens a EUR wallet. */
    Customer customer() throws Exception {
        String login = "held." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(post("/v1/registrations", "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada"
                + " Lovelace\",\"password\":\"" + PASSWORD + "\"}", null, someKey()).statusCode()).isEqualTo(201);
        UUID identity;
        UUID party;
        try (Connection app = DatabaseRoles.application()) {
            try (PreparedStatement promote = app.prepareStatement("UPDATE party.customer SET status = 'ACTIVE',"
                    + " status_changed_at = GREATEST(now(), opened_at) WHERE party_id = (SELECT party_id FROM"
                    + " identity.identity WHERE login_identifier = ?)")) {
                promote.setString(1, login);
                promote.executeUpdate();
            }
            try (PreparedStatement read = app.prepareStatement(
                    "SELECT id, party_id FROM identity.identity WHERE login_identifier = ?")) {
                read.setString(1, login);
                try (ResultSet row = read.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    identity = row.getObject("id", UUID.class);
                    party = row.getObject("party_id", UUID.class);
                }
            }
        }
        String token = tokenFrom(post("/v1/authentications", "{\"loginIdentifier\":\"" + login
                + "\",\"password\":\"" + PASSWORD + "\"}", null, null).body());
        HttpResponse<String> opened = post("/v1/me/accounts", "{\"productType\":\"WALLET\",\"currency\":\"EUR\"}",
                token, someKey());
        assertThat(opened.statusCode()).as(opened.body()).isEqualTo(201);
        UUID product = UUID.fromString(field(opened.body(), "id"));
        UUID customer = uuid("SELECT id FROM party.customer WHERE party_id = ?", party);
        return new Customer(login, token, identity, party, customer, product);
    }

    /** Adds {@code currency} to the customer's wallet over the real endpoint. */
    void addCurrency(Customer customer, CurrencyCode currency) throws Exception {
        HttpResponse<String> added = post("/v1/me/accounts/" + customer.product() + "/currencies",
                "{\"currency\":\"" + currency.code() + "\"}", customer.token(), someKey());
        assertThat(added.statusCode()).as(added.body()).isEqualTo(201);
    }

    /** The wallet's ledger account in {@code currency}. */
    static LedgerAccountId ledgerWallet(UUID product, CurrencyCode currency) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            return new JdbcLedgerAccountStore().findAllOwned(app, product).stream()
                    .filter(account -> account.purpose() == AccountPurpose.CUSTOMER_WALLET
                            && account.currency().equals(currency))
                    .findFirst().orElseThrow().id();
        }
    }

    /** Funds (or, negative, drains) the wallet with a real posting against the operational fee-revenue account. */
    static void fund(UUID product, CurrencyCode currency, long minor) throws Exception {
        JdbcLedgerAccountStore ledgerAccounts = new JdbcLedgerAccountStore();
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope scope = SecurityContext.enter(new Actor(IDS.next().toString(), ActorType.CUSTOMER));
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(IDS)))) {
            app.setAutoCommit(false);
            LedgerAccountId wallet = ledgerAccounts.findAllOwned(app, product).stream()
                    .filter(a -> a.purpose() == AccountPurpose.CUSTOMER_WALLET && a.currency().equals(currency))
                    .findFirst().orElseThrow().id();
            LedgerAccount operational = ledgerAccounts.findOperational(app, AccountPurpose.FEE_REVENUE, currency)
                    .orElseThrow();
            LocalDate today = LocalDate.now(CLOCK);
            Money amount = Money.ofMinorUnits(Math.abs(minor), currency);
            IdempotentExecutor executor = new IdempotentExecutor(new JdbcIdempotencyRecordStore(), CLOCK,
                    Duration.ofDays(1), Duration.ofMinutes(5));
            new PostingService(executor, new JdbcJournalEntryStore(IDS), new JdbcAuditWriter(), new JdbcOutboxWriter(),
                    new JdbcBalanceProjection(), IDS, CLOCK, PostingObserver.NONE)
                    .post(app, new PostingCommand("fund-" + IDS.next(), today, today, "funding", List.of(
                            new JournalLine(operational.id(), minor >= 0 ? Direction.DEBIT : Direction.CREDIT, amount),
                            new JournalLine(wallet, minor >= 0 ? Direction.CREDIT : Direction.DEBIT, amount))));
            app.commit();
        }
    }

    /** The engine's resolution of {@code customer}'s wallet in {@code currency} - an opaque destination, by value. */
    static Withdrawals.Resolved resolved(Customer customer, CurrencyCode currency) throws SQLException {
        return new Withdrawals.Resolved(customer.party(), customer.customer(),
                ledgerWallet(customer.product(), currency), currency, IDS.next(),
                new ProviderReference("dest-held-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10)));
    }

    /** Registers a bank account through the real exchange; returns the method id. */
    String registerBankAccount(Customer customer) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        provider.succeedsWith(SimulatedInstantSchemeAdapter.EXCHANGES_PATH, 200,
                "{\"status\":\"exchanged\",\"destination\":\"dest-held-" + suffix
                        + "\",\"suffix\":\"6819\",\"payee\":\"match\"}");
        HttpResponse<String> registered = post("/v1/me/payment-methods/bank-accounts",
                "{\"grant\":\"blg-" + suffix + "\",\"acknowledgeNoMatch\":false}", customer.token(), someKey());
        assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
        return field(registered.body(), "id");
    }

    /** The scheme accepts every transfer, its reference minted from our idempotency header. */
    void schemeAccepts() {
        provider.succeedsWith(SimulatedInstantSchemeAdapter.TRANSFERS_PATH, 200,
                "{\"status\":\"accepted\",\"reference\":\"held-{{request.headers.Idempotency-Key}}\","
                        + "\"cycle\":\"C1\"}");
    }

    /** The scheme refuses every transfer. */
    void schemeRejects() {
        provider.succeedsWith(SimulatedInstantSchemeAdapter.TRANSFERS_PATH, 200, "{\"status\":\"rejected\"}");
    }

    int sends() {
        return provider.requestCount(SimulatedInstantSchemeAdapter.TRANSFERS_PATH);
    }

    // ------------------------------------------------------------------ HTTP

    HttpResponse<String> withdraw(String token, String methodId, String amount, String key) throws Exception {
        return post("/v1/me/withdrawals", "{\"paymentMethodId\":\"" + methodId + "\",\"amount\":\"" + amount
                + "\",\"currency\":\"EUR\"}", token, key);
    }

    HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token).GET().build();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    HttpResponse<String> delete(String path, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token).DELETE().build();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
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
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    static String someKey() {
        return UUID.randomUUID().toString();
    }

    static String field(String body, String name) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("the body must carry %s: %s", name, body).isTrue();
        return matcher.group(1);
    }

    static String tokenFrom(String body) {
        return field(body, "sessionToken");
    }

    static void assertNoRiskWord(String body) {
        String lower = body.toLowerCase(Locale.ROOT);
        for (String word : RISK_WORDS) {
            assertThat(lower).as("a customer answer naming '%s'", word).doesNotContain(word);
        }
    }

    static Money eur(long minor) {
        return Money.ofMinorUnits(minor, EUR);
    }

    // ------------------------------------------------------------------ counters - read as the migrator

    static long count(String sql, Object... arguments) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement read = migrator.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                read.setObject(i + 1, arguments[i]);
            }
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    static List<String> column(String sql, Object... arguments) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement read = migrator.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                read.setObject(i + 1, arguments[i]);
            }
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    values.add(row.getString(1));
                }
            }
        }
        return values;
    }

    static UUID uuid(String sql, Object argument) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement read = migrator.prepareStatement(sql)) {
            read.setObject(1, argument);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).as("expected one row: %s", sql).isTrue();
                return row.getObject(1, UUID.class);
            }
        }
    }

    static long windowCount(String dimension, UUID subject, String kind) throws SQLException {
        return count("SELECT COALESCE(sum(count), 0) FROM risk.velocity_window WHERE dimension = ? AND subject_ref = ?"
                + " AND window_kind = ?", dimension, subject.toString(), kind);
    }

    /** The withdrawal's hold: its status in the ledger. */
    static String holdStatus(UUID withdrawal) throws SQLException {
        return column("SELECT h.status FROM ledger.hold h JOIN payments.withdrawal w ON w.hold_reference = h.id"
                + " WHERE w.id = ?", withdrawal).get(0);
    }
}
