package com.finapp.app.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingService;
import com.finapp.merchant.MerchantPayoutOutcomes;
import com.finapp.merchant.MerchantPayoutResolution;
import com.finapp.merchant.MerchantPayoutStore;
import com.finapp.merchant.PayoutEvidenceStore;
import com.finapp.merchant.SimulatedPayoutProvider;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.correlation.CorrelationContext;
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
import java.math.BigDecimal;
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
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The payout endpoints (`P6-TSK-012`): a merchant's payout over its own API key, keyed and
 * replayed byte for byte; an operator's on its behalf, reasoned; the permission boundary in
 * both directions — the populations are disjoint, so each route refuses the other's credential;
 * the one {@code 404}; a client key that is each merchant's own; and every refusal writing
 * nothing. The merchant row, its payable and its effective destination are fixtures, their own
 * flows being other suites' subjects.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the payout endpoints (P6-TSK-012)")
class MerchantPayoutEndpointDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final String PATH = SimulatedPayoutProvider.PAYOUTS_PATH;
    private static final String PAID =
            "{\"status\":\"paid\",\"reference\":\"po-{{request.headers.Idempotency-Key}}\"}";

    private static SimulatedProvider provider;

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private LedgerAccountStore<Connection> ledgerAccountStore;
    @Autowired private PostingService postings;
    @Autowired private io.micrometer.core.instrument.MeterRegistry meterRegistry;
    @Autowired private MerchantPayoutStore<Connection> payoutStore;
    @Autowired private MerchantPayoutOutcomes outcomes;
    @Autowired private PayoutEvidenceStore<Connection> evidence;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataSource dataSource;

    @BeforeAll
    static void startProvider() {
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
    }

    @AfterAll
    static void stopProvider() {
        provider.close();
    }

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) {
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
        registry.add("finapp.merchant.payout.provider.url", () -> provider.baseUrl());
        registry.add("finapp.merchant.payout.provider.timeout", () -> "PT0.7S");
    }

    @BeforeEach
    void reset() {
        provider.reset();
        provider.succeedsWith(PATH, 200, PAID);
    }

    // -----------------------------------------------------------------

    @Test
    @DisplayName("a merchant pays out over its key: 201, read back, replayed byte for byte, key named")
    void aMerchantPaysOutOverHttp() throws Exception {
        Merchant merchant = funded("100.00");
        String key = someKey();
        double completedBefore = payoutMeter("completed");

        HttpResponse<String> paid = payout(merchant.key(), "40.00", "EUR", key);
        assertThat(paid.statusCode()).isEqualTo(201);
        assertThat(payoutMeter("completed") - completedBefore)
                .as("the acting judgement, counted post-commit at the door (P6-TSK-013)")
                .isEqualTo(1.0d);
        assertThat(field(paid.body(), "status")).isEqualTo("COMPLETED");
        assertThat(field(paid.body(), "amount")).isEqualTo("40.00");
        assertThat(field(paid.body(), "destinationSuffix")).isEqualTo("3000");
        String id = field(paid.body(), "id");

        HttpResponse<String> read = get("/v1/merchant/payouts/" + id, merchant.key());
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(field(read.body(), "status")).isEqualTo("COMPLETED");

        HttpResponse<String> replay = payout(merchant.key(), "40.00", "EUR", key);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.body()).as("the replay is the original bytes").isEqualTo(paid.body());
        assertThat(provider.requestCount(PATH)).as("one wire operation").isEqualTo(1);
        assertThat(payoutMeter("completed") - completedBefore)
                .as("and one judgement: a replay answers a recorded one, it never makes another")
                .isEqualTo(1.0d);

        assertThat(payout(merchant.key(), "41.00", "EUR", key).statusCode())
                .as("a reused key for a different request is the distinct 409 (INV-IDEM-03)")
                .isEqualTo(409);
        assertThat(count(
                        "SELECT count(*) FROM platform.audit_record WHERE operation ="
                                + " 'merchant.MerchantPayoutInitiated' AND target_id = ? AND"
                                + " actor_type = 'MERCHANT' AND change_summary LIKE '%key=%'",
                        id))
                .as("the key that acted is named (ADR-0052 section 2)")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an operator pays out on the merchant's behalf, reasoned; without a reason it is 422")
    void anOperatorPaysOutWithAReason() throws Exception {
        Merchant merchant = funded("100.00");
        String operator = sessionWith(RoleName.LEDGER_OPERATOR);

        assertThat(operatorPayout(merchant.id(), operator, "{\"amount\":\"10.00\",\"currency\":\"EUR\"}")
                        .statusCode())
                .isEqualTo(422);
        assertThat(payoutsOf(merchant)).as("a refused request writes nothing").isZero();

        HttpResponse<String> paid =
                operatorPayout(
                        merchant.id(),
                        operator,
                        "{\"amount\":\"25.00\",\"currency\":\"EUR\",\"reason\":\"support ticket 42\"}");
        assertThat(paid.statusCode()).isEqualTo(201);
        assertThat(field(paid.body(), "status")).isEqualTo("COMPLETED");
        assertThat(count(
                        "SELECT count(*) FROM platform.audit_record WHERE operation ="
                                + " 'merchant.MerchantPayoutInitiatedByOperator' AND target_id = ?"
                                + " AND reason = 'support ticket 42'",
                        field(paid.body(), "id")))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the permission boundary: no role, the wrong population, and each route refusing the other's credential")
    void thePermissionBoundary() throws Exception {
        Merchant merchant = funded("100.00");
        String body = "{\"amount\":\"10.00\",\"currency\":\"EUR\",\"reason\":\"r\"}";

        String nobody = tokenFrom(authenticate(registered()).body());
        assertThat(operatorPayout(merchant.id(), nobody, body).statusCode()).isEqualTo(403);
        String administrator = sessionWith(RoleName.MERCHANT_ADMINISTRATOR);
        assertThat(operatorPayout(merchant.id(), administrator, body).statusCode())
                .as("administering a counterparty is not moving its money")
                .isEqualTo(403);
        assertThat(operatorPayout(merchant.id(), merchant.key(), body).statusCode())
                .as("a merchant key is no operator session")
                .isEqualTo(401);
        String operator = sessionWith(RoleName.LEDGER_OPERATOR);
        assertThat(payout(operator, "10.00", "EUR", someKey()).statusCode())
                .as("an operator session is no merchant key")
                .isEqualTo(401);
        assertThat(payout(null, "10.00", "EUR", someKey()).statusCode()).isEqualTo(401);
        assertThat(payoutsOf(merchant)).isZero();
        assertThat(provider.requestCount(PATH)).isZero();
    }

    @Test
    @DisplayName("another merchant's, unknown and malformed payout identifiers are one 404")
    void oneNotFound() throws Exception {
        Merchant owner = funded("100.00");
        Merchant other = funded("100.00");
        String id = field(payout(owner.key(), "10.00", "EUR", someKey()).body(), "id");

        HttpResponse<String> foreign = get("/v1/merchant/payouts/" + id, other.key());
        HttpResponse<String> unknown = get("/v1/merchant/payouts/" + IDS.next(), owner.key());
        HttpResponse<String> malformed = get("/v1/merchant/payouts/not-a-uuid", owner.key());
        for (HttpResponse<String> response : List.of(foreign, unknown, malformed)) {
            assertThat(response.statusCode()).isEqualTo(404);
        }
        assertThat(normalised(foreign.body()))
                .as("the pairing is judged in the statement: another merchant's is unknown")
                .isEqualTo(normalised(unknown.body()))
                .isEqualTo(normalised(malformed.body()));
        String operator = sessionWith(RoleName.LEDGER_OPERATOR);
        assertThat(operatorPayout(
                                IDS.next().toString(),
                                operator,
                                "{\"amount\":\"10.00\",\"currency\":\"EUR\",\"reason\":\"r\"}")
                        .statusCode())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("a client key is each merchant's own: two merchants, one key, two payouts")
    void aClientKeyIsEachMerchantsOwn() throws Exception {
        Merchant first = funded("100.00");
        Merchant second = funded("100.00");
        String shared = someKey();

        HttpResponse<String> one = payout(first.key(), "10.00", "EUR", shared);
        HttpResponse<String> two = payout(second.key(), "10.00", "EUR", shared);

        assertThat(one.statusCode()).isEqualTo(201);
        assertThat(two.statusCode())
                .as("the claim's scope carries the merchant (ADR-0004): no cross-merchant 409")
                .isEqualTo(201);
        assertThat(field(two.body(), "id")).isNotEqualTo(field(one.body(), "id"));
        assertThat(payoutsOf(first)).isEqualTo(1);
        assertThat(payoutsOf(second)).isEqualTo(1);
    }

    @Test
    @DisplayName("unfunded, foreign currency, no destination, malformed amounts: each refused, nothing written")
    void theRefusalsWriteNothing() throws Exception {
        Merchant merchant = funded("10.00");
        assertThat(errorCode(payout(merchant.key(), "20.00", "EUR", someKey())))
                .isEqualTo("merchant.PayoutUnfunded");
        assertThat(errorCode(payout(merchant.key(), "5.00", "GBP", someKey())))
                .isEqualTo("merchant.PayoutCurrencyMismatch");
        for (String amount : new String[] {"0.00", "-1.00", "12.345", "twelve"}) {
            assertThat(payout(merchant.key(), amount, "EUR", someKey()).statusCode())
                    .as(amount)
                    .isEqualTo(422);
        }
        Merchant nowhere = fundedWithoutDestination("100.00");
        assertThat(errorCode(payout(nowhere.key(), "5.00", "EUR", someKey())))
                .isEqualTo("merchant.NoEffectiveDestination");

        assertThat(payoutsOf(merchant)).isZero();
        assertThat(payoutsOf(nowhere)).isZero();
        assertThat(count(
                        "SELECT count(*) FROM ledger.hold WHERE ledger_account_id IN (?, ?)",
                        merchant.payable().value(),
                        nowhere.payable().value()))
                .isZero();
        assertThat(provider.requestCount(PATH)).isZero();
    }

    @Test
    @DisplayName("a timeout is answered honestly: 201 UNKNOWN, the hold standing, and the replay the same")
    void aTimeoutIsHonest() throws Exception {
        Merchant merchant = funded("100.00");
        provider.reset();
        provider.neverResponds(PATH);
        String key = someKey();
        double unknownBefore = payoutMeter("unknown");

        HttpResponse<String> unknown = payout(merchant.key(), "40.00", "EUR", key);
        assertThat(unknown.statusCode()).isEqualTo(201);
        assertThat(field(unknown.body(), "status")).isEqualTo("UNKNOWN");
        assertThat(count(
                        "SELECT count(*) FROM ledger.hold WHERE ledger_account_id = ? AND status"
                                + " = 'ACTIVE'",
                        merchant.payable().value()))
                .isEqualTo(1);
        assertThat(payout(merchant.key(), "40.00", "EUR", key).body()).isEqualTo(unknown.body());
        assertThat(payoutMeter("unknown") - unknownBefore)
                .as("the honest UNKNOWN is a judgement, counted once - and the replay nothing")
                .isEqualTo(1.0d);

        // RESOLVED BY QUERY, NEVER BY A SECOND SEND (ADR-0057): the provider paid after all, the
        // sweep learns it, and the read shows the truth.
        String id = field(unknown.body(), "id");
        String reference =
                strings(
                                "SELECT provider_idempotency_reference FROM merchant.merchant_payout"
                                        + " WHERE id = ?",
                                UUID.fromString(id))
                        .get(0);
        provider.succeedsWith(
                PATH + "/" + reference,
                200,
                "{\"status\":\"paid\",\"reference\":\"po-q-" + reference + "\"}");
        resolution().sweep();

        HttpResponse<String> read = get("/v1/merchant/payouts/" + id, merchant.key());
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(field(read.body(), "status")).isEqualTo("COMPLETED");
        assertThat(count(
                        "SELECT count(*) FROM ledger.hold WHERE ledger_account_id = ? AND status"
                                + " = 'ACTIVE'",
                        merchant.payable().value()))
                .as("the standing hold is released once the query settles it")
                .isZero();
        // THE JUDGEMENT IS FIXED, THE READ IS THE TRUTH: the keyed replay answers what that
        // request was answered, byte for byte, even after the payout resolved.
        assertThat(payout(merchant.key(), "40.00", "EUR", key).body()).isEqualTo(unknown.body());
        assertThat(provider.requestCount(PATH)).as("one send, ever").isEqualTo(1);
    }

    /** A resolution whose UNKNOWN bound has already passed, so the sweep takes this payout now. */
    private MerchantPayoutResolution resolution() {
        return new MerchantPayoutResolution(
                new MerchantTransactions(new TransactionTemplate(transactionManager), dataSource),
                payoutStore,
                new SimulatedPayoutProvider(
                        URI.create(provider.baseUrl()), Duration.ofSeconds(2), new byte[32]),
                outcomes,
                evidence,
                IDS,
                CLOCK,
                Duration.ofHours(1),
                Duration.ZERO,
                1000);
    }

    // -----------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------

    /** A merchant, its API key, and its payable. */
    private record Merchant(String id, String key, LedgerAccountId payable) {}

    private Merchant funded(String amount) throws Exception {
        Merchant merchant = fundedWithoutDestination(amount);
        raw(
                "INSERT INTO merchant.payout_destination (id, merchant_id, destination_reference,"
                        + " display_suffix, status, proposed_by, proposed_at, proposal_reason,"
                        + " approved_by, approved_at, cooling_off_until, effective_at) VALUES"
                        + " (?, ?, ?, '3000', 'EFFECTIVE', 'fixture-a', now() - interval '4 days',"
                        + " 'fixture', 'fixture-b', now() - interval '4 days', now() - interval"
                        + " '1 day', now() - interval '1 hour')",
                IDS.next(),
                UUID.fromString(merchant.id()),
                "pdr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        return merchant;
    }

    private Merchant fundedWithoutDestination(String amount) throws Exception {
        UUID id = IDS.next();
        raw(
                "INSERT INTO merchant.merchant (id, party_ref, legal_name, display_name,"
                        + " settlement_currency, status, created_at, status_changed_at) VALUES"
                        + " (?, ?, 'Acme GmbH', 'Acme', 'EUR', 'ACTIVE', now(), now())",
                id,
                UUID.randomUUID());
        LedgerAccountId payable =
                asOperator(
                        uow ->
                                ledgerAccountStore
                                        .createOrConverge(
                                                uow,
                                                LedgerAccount.owned(
                                                        IDS, CLOCK, AccountType.LIABILITY,
                                                        AccountPurpose.MERCHANT_PAYABLE, EUR, id))
                                        .account()
                                        .id());
        asOperator(
                uow -> {
                    LedgerAccount clearing =
                            new ChartOfAccounts<>(ledgerAccountStore)
                                    .resolve(uow, AccountPurpose.SETTLEMENT_CLEARING, EUR);
                    LocalDate today = LocalDate.now(CLOCK);
                    UUID reference = UUID.randomUUID();
                    Money funds = Money.of(new BigDecimal(amount), EUR);
                    return postings.post(
                            uow,
                            new PostingCommand(
                                    "payout-endpoint-fixture:" + reference,
                                    today,
                                    today,
                                    reference.toString(),
                                    List.of(
                                            new JournalLine(clearing.id(), Direction.DEBIT, funds),
                                            new JournalLine(payable, Direction.CREDIT, funds))));
                });
        String administrator = sessionWith(RoleName.MERCHANT_ADMINISTRATOR);
        HttpResponse<String> issued =
                post("/v1/operator/merchants/" + id + "/api-keys", null, administrator, someKey());
        assertThat(issued.statusCode()).isEqualTo(201);
        return new Merchant(
                id.toString(),
                field(issued.body(), "keyId") + "." + field(issued.body(), "secret"),
                payable);
    }

    private <R> R asOperator(Function<Connection, R> work) throws Exception {
        try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope acting =
                        SecurityContext.enter(
                                new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER));
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            R result = work.apply(app);
            app.commit();
            return result;
        }
    }

    // -----------------------------------------------------------------
    // Requests
    // -----------------------------------------------------------------

    private HttpResponse<String> payout(String token, String amount, String currency, String key)
            throws Exception {
        return post(
                "/v1/merchant/payouts",
                "{\"amount\":\"" + amount + "\",\"currency\":\"" + currency + "\"}",
                token,
                key);
    }

    private HttpResponse<String> operatorPayout(String merchant, String token, String body)
            throws Exception {
        return post("/v1/operator/merchants/" + merchant + "/payouts", body, token, someKey());
    }

    private String sessionWith(RoleName role) throws Exception {
        return tokenFrom(authenticate(operatorLogin(role)).body());
    }

    private String operatorLogin(RoleName role) throws Exception {
        String login = registered();
        UUID identity;
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT id FROM identity.identity WHERE login_identifier = ?")) {
            read.setString(1, login);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                identity = row.getObject("id", UUID.class);
            }
        }
        try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(
                    app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
            app.commit();
        }
        return login;
    }

    private String registered() throws Exception {
        String login = "payout." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        HttpResponse<String> registration =
                post(
                        "/v1/registrations",
                        "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                                + "\"password\":\"" + PASSWORD + "\"}",
                        null,
                        someKey());
        assertThat(registration.statusCode()).isEqualTo(201);
        return login;
    }

    private HttpResponse<String> authenticate(String login) throws Exception {
        return post(
                "/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}",
                null,
                someKey());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build());
    }

    private HttpResponse<String> post(String path, String body, String token, String key)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (key != null) {
            request.header(IdempotencyKeyHeader.NAME, key);
        }
        return send(request.build());
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        try (HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    // -----------------------------------------------------------------
    // Reading back
    // -----------------------------------------------------------------

    private static long payoutsOf(Merchant merchant) throws SQLException {
        return count(
                "SELECT count(*) FROM merchant.merchant_payout WHERE merchant_id = ?",
                UUID.fromString(merchant.id()));
    }

    private static String errorCode(HttpResponse<String> response) {
        return field(response.body(), "code");
    }

    private static void raw(String sql, Object... arguments) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement statement = app.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            statement.executeUpdate();
        }
    }

    private static long count(String sql, Object... arguments) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                read.setObject(i + 1, arguments[i]);
            }
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private static List<String> strings(String sql, Object... arguments) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                read.setObject(i + 1, arguments[i]);
            }
            List<String> values = new ArrayList<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    values.add(row.getString(1));
                }
            }
            return values;
        }
    }

    /** The payout counter for one outcome, in the application's own registry (`P6-TSK-013`). */
    private double payoutMeter(String outcome) {
        return meterRegistry.get("finapp.merchant.payout").tag("outcome", outcome).counter().count();
    }

    /** The correlation identifier and the echoed path differ per request by design. */
    private static String normalised(String body) {
        return body.replaceAll("\"correlationId\":\"[^\"]*\"", "\"correlationId\":\"*\"")
                .replaceAll("\"instance\":\"[^\"]*\"", "\"instance\":\"*\"");
    }

    private static String someKey() {
        return UUID.randomUUID().toString();
    }

    private static String field(String body, String name) {
        Matcher matcher =
                Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("the body must carry %s: %s", name, body).isTrue();
        return matcher.group(1);
    }

    private static String tokenFrom(String body) {
        return field(body, "sessionToken");
    }
}
