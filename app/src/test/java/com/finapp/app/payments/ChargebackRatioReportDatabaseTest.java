package com.finapp.app.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.payments.DisputeStore;
import com.finapp.payments.SimulatedCardPspAdapter;
import com.finapp.payments.SimulatedInstantSchemeAdapter;
import com.finapp.payments.TransactionRunner;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
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
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The chargeback-ratio report over the real schema (`P7-TSK-015`, {@code PHASE_7_PLAN.md} §15,
 * ADR-0018): every merchant's card sales and chargebacks for a calendar month, attributed through
 * the ledger, ranked worst first, bounded, permissioned and on the record.
 *
 * <p><strong>The months are 2001's, deliberately</strong>: the report counts every merchant in the
 * database, and this suite shares one with every other — so its facts are seeded raw into months no
 * flow ever touches (a capture's {@code CAPTURED} history row, a dispute's opening or its move into
 * {@code CHARGED_BACK}, each stamped exactly), and each assertion reads a month that holds only this
 * suite's rows. The dates also pin the window's two edges to the second.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the chargeback-ratio report (P7-TSK-015)")
class ChargebackRatioReportDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode GBP = CurrencyCode.of("GBP");
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final String REPORT = "/v1/operator/reports/chargeback-ratio";

    private static String merchantAdministrator;
    private static String disputeOperator;

    @LocalServerPort private int port;
    @Autowired private LedgerAccountStore<Connection> ledgerAccountStore;
    @Autowired private DisputeStore<Connection> disputeStore;
    @Autowired private Authorization authorization;

    @Autowired
    @Qualifier("paymentTransactionRunner")
    private TransactionRunner transactions;

    @Test
    @DisplayName("each merchant's month: card sales and chargebacks counted inside the window to the"
            + " second, summed across its payables, a wallet never a merchant, an inquiry never a"
            + " chargeback - ranked chargebacks-without-sales first, then by ratio, BigDecimal to"
            + " four places")
    void theMonthIsCountedAndRanked() throws Exception {
        // Merchant A: four sales in February across TWO payables (EUR and GBP) - the last at the
        // window's closing second - and two chargebacks dated in February: one opened charged
        // back, one escalated from a January inquiry on a January sale. Outside the window: a
        // sale on its opening instant of March, one at the last second of January, a chargeback
        // opened in March, and an inquiry never escalated.
        UUID a = IDS.next();
        LedgerAccountId aEur = payable(a, EUR);
        LedgerAccountId aGbp = payable(a, GBP);
        UUID aSale = sale(aEur, at("2001-02-05T10:00:00Z"));
        sale(aEur, at("2001-02-10T10:00:00Z"));
        UUID aLastSale = sale(aEur, at("2001-02-28T23:59:59Z"));
        sale(aGbp, at("2001-02-15T10:00:00Z"));
        sale(aEur, at("2001-03-01T00:00:00Z"));
        UUID aJanuarySale = sale(aEur, at("2001-01-31T23:59:59Z"));
        chargeback(aSale, at("2001-02-20T09:00:00Z"), null);
        chargeback(aJanuarySale, at("2001-01-25T09:00:00Z"), at("2001-02-02T09:00:00Z"));
        chargeback(aLastSale, at("2001-03-02T09:00:00Z"), null);
        inquiry(sale(aEur, at("2001-01-10T10:00:00Z")), at("2001-02-12T09:00:00Z"));

        // Merchant B: three sales, no chargeback - 0.0000.
        UUID b = IDS.next();
        LedgerAccountId bEur = payable(b, EUR);
        for (int i = 1; i <= 3; i++) {
            sale(bEur, at("2001-02-0" + i + "T12:00:00Z"));
        }

        // Merchant C: a chargeback in February on a January sale, and no February sale.
        UUID c = IDS.next();
        chargeback(sale(payable(c, EUR), at("2001-01-20T12:00:00Z")), at("2001-02-14T12:00:00Z"),
                null);

        // Merchant D: two sales, both charged back - 1.0000.
        UUID d = IDS.next();
        LedgerAccountId dEur = payable(d, EUR);
        chargeback(sale(dEur, at("2001-02-03T12:00:00Z")), at("2001-02-04T12:00:00Z"), null);
        chargeback(sale(dEur, at("2001-02-05T12:00:00Z")), at("2001-02-06T12:00:00Z"), null);

        // A customer's wallet topped up by card and charged back: the operator's desk, never a
        // merchant's ratio.
        LedgerAccountId wallet = account(IDS.next(), AccountPurpose.CUSTOMER_WALLET, EUR);
        chargeback(sale(wallet, at("2001-02-07T12:00:00Z")), at("2001-02-08T12:00:00Z"), null);

        long audited = auditsFor("2001-02");
        HttpResponse<String> answered = get(REPORT + "?month=2001-02", merchantAdministrator());
        assertThat(answered.statusCode()).as(answered.body()).isEqualTo(200);
        JsonNode report = JSON.readTree(answered.body());

        assertThat(report.path("month").stringValue()).isEqualTo("2001-02");
        assertThat(report.path("windowStart").stringValue()).isEqualTo("2001-02-01T00:00:00Z");
        assertThat(report.path("windowEnd").stringValue()).isEqualTo("2001-03-01T00:00:00Z");
        assertThat(report.path("truncated").booleanValue()).isFalse();
        List<String> rows = new ArrayList<>();
        for (JsonNode row : report.path("merchants")) {
            rows.add(row.path("merchantId").stringValue() + "|" + row.path("sales").longValue()
                    + "|" + row.path("chargebacks").longValue() + "|"
                    + (row.path("ratio").isNull() ? "null" : row.path("ratio").stringValue()));
        }
        assertThat(rows)
                .as("C (chargebacks against no sales) first, then D 1.0000, A 0.5000, B 0.0000 -"
                        + " and nothing else: the wallet is no merchant")
                .containsExactly(
                        c + "|0|1|null",
                        d + "|2|2|1.0000",
                        a + "|4|2|0.5000",
                        b + "|3|0|0.0000");

        // On the record, once, naming the period - never a merchant or an amount.
        assertThat(auditsFor("2001-02")).isEqualTo(audited + 1);
        assertThat(oneString("SELECT change_summary FROM platform.audit_record"
                        + " WHERE operation = 'payments.ChargebackRatioRead' AND target_id = '2001-02'"
                        + " ORDER BY audit_id DESC LIMIT 1"))
                .contains("period=2001-02")
                .doesNotContain(a.toString());
    }

    @Test
    @DisplayName("the report is bounded: the worst hundred, truncated says there were more, and the"
            + " order is total - ties broken by chargebacks, then the merchant id")
    void theReportIsBounded() throws Exception {
        List<String> merchants = new ArrayList<>();
        for (int i = 0; i < DisputeOperations.RATIO_REPORT_BOUND + 1; i++) {
            UUID merchant = IDS.next();
            merchants.add(merchant.toString());
            chargeback(sale(payable(merchant, EUR), at("2001-04-10T12:00:00Z")),
                    at("2001-04-11T12:00:00Z"), null);
        }
        HttpResponse<String> answered = get(REPORT + "?month=2001-04", merchantAdministrator());
        assertThat(answered.statusCode()).as(answered.body()).isEqualTo(200);
        JsonNode report = JSON.readTree(answered.body());

        assertThat(report.path("truncated").booleanValue()).isTrue();
        List<String> shown = new ArrayList<>();
        report.path("merchants").forEach(row -> shown.add(row.path("merchantId").stringValue()));
        merchants.sort(String::compareTo);
        assertThat(shown)
                .as("equal ratios and counts: the merchant id decides, so the bound cuts the same"
                        + " merchants every time")
                .containsExactlyElementsOf(merchants.subList(0, DisputeOperations.RATIO_REPORT_BOUND));
    }

    @Test
    @DisplayName("least privilege and the boundary: the dispute desk (DISPUTE_ADMINISTER, no"
            + " MERCHANT_ADMINISTER) is the one 403, no session the 401, a period that is not a"
            + " calendar month the one 422 - and none of them writes a record")
    void theReportIsGuarded() throws Exception {
        long audited = count("SELECT count(*) FROM platform.audit_record"
                + " WHERE operation = 'payments.ChargebackRatioRead'");

        assertThat(get(REPORT + "?month=2001-02", operator()).statusCode()).isEqualTo(403);
        assertThat(get(REPORT + "?month=2001-02", null).statusCode()).isEqualTo(401);
        String aFutureMonth = YearMonth.now(CLOCK.withZone(ZoneOffset.UTC)).plusMonths(2).toString();
        // The shape refused, a meaningless month, and the two ends of the reportable range: a
        // month before any record and one that has not happened (9999-12's window would end in
        // year 10000, refused here and never left for the database driver to discover).
        for (String month :
                List.of("2001-13", "2001-2", "Feb-2001", "2001-02-01", "", "1999-12",
                        aFutureMonth, "9999-12")) {
            HttpResponse<String> refused = get(REPORT + "?month=" + month, merchantAdministrator());
            assertThat(refused.statusCode()).as("month=%s: %s", month, refused.body()).isEqualTo(422);
            assertThat(refused.body()).contains("api.ValidationFailed");
        }

        assertThat(count("SELECT count(*) FROM platform.audit_record"
                        + " WHERE operation = 'payments.ChargebackRatioRead'"))
                .as("a refused read is no read")
                .isEqualTo(audited);
    }

    @Test
    @DisplayName("without a month the report is the clock's current UTC month")
    void theDefaultPeriodIsTheCurrentMonth() throws Exception {
        String before = YearMonth.now(CLOCK.withZone(ZoneOffset.UTC)).toString();
        HttpResponse<String> answered = get(REPORT, merchantAdministrator());
        String after = YearMonth.now(CLOCK.withZone(ZoneOffset.UTC)).toString();
        assertThat(answered.statusCode()).as(answered.body()).isEqualTo(200);
        assertThat(JSON.readTree(answered.body()).path("month").stringValue()).isIn(before, after);
    }

    @Test
    @DisplayName("the counts read the attempt's STORED rail against the caller's rails - data, never"
            + " a name (INV-RAIL-01): asked about a rail without chargebacks, the card month is"
            + " empty")
    void theRailFilterIsTheCallersData() throws Exception {
        UUID merchant = IDS.next();
        chargeback(sale(payable(merchant, EUR), at("2001-06-10T12:00:00Z")),
                at("2001-06-11T12:00:00Z"), null);
        Instant from = at("2001-06-01T00:00:00Z");
        Instant to = at("2001-07-01T00:00:00Z");

        List<DisputeStore.CreditedCounts> card =
                transactions.inTransaction(
                        uow -> disputeStore.chargebackCountsByCreditAccount(
                                uow, Set.of(SimulatedCardPspAdapter.RAIL.id()), from, to));
        assertThat(card)
                .extracting(DisputeStore.CreditedCounts::sales, DisputeStore.CreditedCounts::chargebacks)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1L, 1L));
        List<DisputeStore.CreditedCounts> instant =
                transactions.inTransaction(
                        uow -> disputeStore.chargebackCountsByCreditAccount(
                                uow, Set.of(SimulatedInstantSchemeAdapter.RAIL.id()), from, to));
        assertThat(instant).isEmpty();
    }

    // -----------------------------------------------------------------
    // Fixtures: raw rows, each stamped exactly
    // -----------------------------------------------------------------

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static Instant at(String instant) {
        return Instant.parse(instant);
    }

    private LedgerAccountId payable(UUID merchant, CurrencyCode currency) throws Exception {
        return account(merchant, AccountPurpose.MERCHANT_PAYABLE, currency);
    }

    private LedgerAccountId account(UUID owner, AccountPurpose purpose, CurrencyCode currency)
            throws Exception {
        return asPlatform(
                uow -> ledgerAccountStore
                        .createOrConverge(
                                uow,
                                LedgerAccount.owned(
                                        IDS, CLOCK, AccountType.LIABILITY, purpose, currency, owner))
                        .account()
                        .id());
    }

    /** A captured card sale crediting {@code credit}, its CAPTURED history row at {@code capturedAt}. */
    private UUID sale(LedgerAccountId credit, Instant capturedAt) throws SQLException {
        UUID intent = IDS.next();
        UUID attempt = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app,
                    "INSERT INTO payments.payment_intent (id, party_id, customer_id,"
                            + " payment_method_id, credit_account_id, amount_minor, currency,"
                            + " scale, status, created_at, capture_mode)"
                            + " VALUES (?, ?, ?, ?, ?, 1000, 'EUR', 2, 'SUCCEEDED', ?, 'AUTOMATIC')",
                    intent, IDS.next(), IDS.next(), IDS.next(), credit.value(),
                    Timestamp.from(capturedAt));
            execute(app,
                    "INSERT INTO payments.payment_attempt (id, intent_id, auth_reference,"
                            + " capture_reference, auth_provider_reference,"
                            + " capture_provider_reference, authorized_amount_minor,"
                            + " authorized_currency, authorized_scale, captured_amount_minor,"
                            + " captured_currency, captured_scale, status, created_at, rail,"
                            + " interaction_model)"
                            + " VALUES (?, ?, ?, ?, ?, ?, 1000, 'EUR', 2, 1000, 'EUR', 2,"
                            + " 'CAPTURED', ?, 'card', 'TWO_STEP')",
                    attempt, intent, "auth-" + IDS.next(), "cap-" + IDS.next(),
                    "psp-auth-" + IDS.next(), "psp-cap-" + IDS.next(), Timestamp.from(capturedAt));
            execute(app,
                    "INSERT INTO payments.payment_attempt_event (attempt_id, from_status,"
                            + " to_status, actor_id, actor_type, occurred_at)"
                            + " VALUES (?, 'CAPTURE_DISPATCHED', 'CAPTURED', 'platform',"
                            + " 'PLATFORM', ?)",
                    attempt, Timestamp.from(capturedAt));
        }
        return attempt;
    }

    /**
     * A 10.00 chargeback on {@code attempt}: opened charged back at {@code openedAt}, or - with
     * {@code escalatedAt} - opened as an inquiry then moved into CHARGED_BACK at that instant.
     */
    private void chargeback(UUID attempt, Instant openedAt, Instant escalatedAt)
            throws SQLException {
        UUID dispute = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app,
                    "INSERT INTO payments.dispute (id, provider, provider_dispute_reference,"
                            + " attempt_id, reason, stage, chargeback_amount_minor,"
                            + " chargeback_currency, chargeback_scale,"
                            + " counterparty_share_amount_minor, counterparty_share_currency,"
                            + " counterparty_share_scale, parked_share_amount_minor,"
                            + " parked_share_currency, parked_share_scale, opened_at)"
                            + " VALUES (?, 'simulated-card', ?, ?, 'FRAUD', 'CHARGED_BACK', 1000,"
                            + " 'EUR', 2, 0, 'EUR', 2, 0, 'EUR', 2, ?)",
                    dispute, "dp_" + UUID.randomUUID().toString().replace("-", ""), attempt,
                    Timestamp.from(openedAt));
            if (escalatedAt != null) {
                execute(app,
                        "INSERT INTO payments.dispute_event (dispute_id, from_stage, to_stage,"
                                + " actor_id, actor_type, occurred_at)"
                                + " VALUES (?, 'INQUIRY', 'CHARGED_BACK', 'platform', 'SYSTEM', ?)",
                        dispute, Timestamp.from(escalatedAt));
            }
        }
    }

    /** An inquiry on {@code attempt} that never became a chargeback: no funds taken. */
    private void inquiry(UUID attempt, Instant openedAt) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            execute(app,
                    "INSERT INTO payments.dispute (id, provider, provider_dispute_reference,"
                            + " attempt_id, reason, stage, opened_at)"
                            + " VALUES (?, 'simulated-card', ?, ?, 'FRAUD', 'INQUIRY', ?)",
                    IDS.next(), "dp_" + UUID.randomUUID().toString().replace("-", ""), attempt,
                    Timestamp.from(openedAt));
        }
    }

    private <R> R asPlatform(Function<Connection, R> work) {
        try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope platform = SecurityContext.enterSystem()) {
            return transactions.inTransaction(work);
        }
    }

    private long auditsFor(String period) throws SQLException {
        return count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'payments.ChargebackRatioRead' AND target_id = ?", period);
    }

    /** A MERCHANT_ADMINISTRATOR's session - the standing desk the report serves. */
    private String merchantAdministrator() throws Exception {
        if (merchantAdministrator == null) {
            merchantAdministrator = session(RoleName.MERCHANT_ADMINISTRATOR);
        }
        return merchantAdministrator;
    }

    /** A LEDGER_OPERATOR's session - the dispute desk: DISPUTE_ADMINISTER, no MERCHANT_ADMINISTER. */
    private String operator() throws Exception {
        if (disputeOperator == null) {
            disputeOperator = session(RoleName.LEDGER_OPERATOR);
        }
        return disputeOperator;
    }

    private String session(RoleName role) throws Exception {
        String login = "ratio." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        HttpResponse<String> registration =
                post("/v1/registrations",
                        "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                                + "\"password\":\"" + PASSWORD + "\"}");
        assertThat(registration.statusCode()).as(registration.body()).isEqualTo(201);
        UUID identity =
                UUID.fromString(oneString(
                        "SELECT id FROM identity.identity WHERE login_identifier = ?", login));
        try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity),
                    "test fixture");
            app.commit();
        }
        HttpResponse<String> authenticated =
                post("/v1/authentications",
                        "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}");
        return JSON.readTree(authenticated.body()).path("sessionToken").stringValue();
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return send(request.build());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .header(IdempotencyKeyHeader.NAME, UUID.randomUUID().toString())
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build());
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        try (HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static String oneString(String sql, Object... arguments) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                read.setObject(i + 1, arguments[i]);
            }
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).as(sql).isTrue();
                return row.getString(1);
            }
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

    private static void execute(Connection connection, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            statement.executeUpdate();
        }
    }
}
