package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcSessionStore;
import com.finapp.identity.Session;
import com.finapp.identity.SessionPolicy;
import com.finapp.identity.SessionStore;
import com.finapp.identity.SessionToken;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.AdjustmentCommand;
import com.finapp.ledger.AdjustmentService;
import com.finapp.ledger.JdbcAdjustmentProposalStore;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingService;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The statement endpoint, over real HTTP (`P3-TSK-018`, {@code INV-ACC-02}'s drill-down shape
 * three phases early).
 *
 * <p>The acceptance: a period statement derived from postings whose opening and closing
 * reconcile to the lines between them — with the closing held to an <strong>independent
 * {@code BigDecimal} recomputation over raw SQL rows</strong>, because the derivation must
 * not be certified with itself (`P3-TSK-008`'s discipline). {@code DOD-SEC}'s negatives: the
 * one-404 equality between not-yours, unknown and malformed; a 401; and the disclosure
 * exclusions — no counterparty account identifier and no adjustment {@code reason} in any
 * statement body.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the statement endpoint (P3-TSK-018)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class StatementEndpointDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final CurrencyCode USD = CurrencyCode.of("USD");

    private static final LocalDate BEFORE = LocalDate.of(2026, 9, 10);
    private static final LocalDate FROM = LocalDate.of(2026, 9, 12);
    private static final LocalDate TO = LocalDate.of(2026, 9, 14);
    private static final LocalDate AFTER = LocalDate.of(2026, 9, 15);

    private static final String OPEN_USD = "{\"productType\":\"WALLET\",\"currency\":\"USD\"}";

    @LocalServerPort private int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SessionStore<Connection> sessions = new JdbcSessionStore();
    private final LedgerAccountStore<Connection> ledgerAccounts = new JdbcLedgerAccountStore();

    @Test
    @DisplayName("the acceptance: opening and closing reconcile to the lines between them,"
            + " and the closing survives an independent recomputation")
    void theStatementReconcilesToItsLines() throws Exception {
        Person person = givenAPerson();
        String accountId = field(post("/me/accounts", person.session(), OPEN_USD, key()).body(), "id");
        UUID account = UUID.fromString(accountId);

        // The history: 10.00 in before the period, 2.50 in on the period's FIRST day and
        // 1.00 out on its LAST (both boundaries inclusive), 5.00 in after it.
        postCredit(account, BEFORE, 1000);
        postCredit(account, FROM, 250);
        postDebit(account, TO, 100);
        postCredit(account, AFTER, 500);

        HttpResponse<String> answered = statement(accountId, person.session(), FROM, TO);
        assertThat(answered.statusCode()).isEqualTo(200);
        String body = answered.body();

        // The statement says what it is and for when.
        assertThat(body)
                .contains("\"kind\":\"DERIVED\"")
                .contains("\"from\":\"2026-09-12\"")
                .contains("\"to\":\"2026-09-14\"")
                .contains("\"currency\":\"USD\"");

        // Opening is the pre-period history; the two period lines appear in posting-date
        // order with their sides named; the boundary days are both inside; the day after is
        // not; and closing = opening + lines (the wallet is a LIABILITY: credits grow it).
        assertThat(body)
                .contains("\"opening\":\"10.00\"")
                .contains("\"closing\":\"11.50\"")
                .contains("\"postingDate\":\"2026-09-12\"")
                .contains("\"postingDate\":\"2026-09-14\"")
                .contains("\"direction\":\"CREDIT\"")
                .contains("\"direction\":\"DEBIT\"")
                .contains("\"amount\":\"2.50\"")
                .contains("\"amount\":\"1.00\"")
                .contains("\"entryType\":\"POSTING\"")
                .doesNotContain("\"amount\":\"5.00\"")
                .doesNotContain("2026-09-15");
        assertThat(body.indexOf("2026-09-12"))
                .as("lines are ordered by posting date")
                .isLessThan(body.indexOf("2026-09-14"));

        // The counterparty is never disclosed: an entry's other lines touch accounts that
        // are not the caller's, and a statement line carries no account identifier at all.
        assertThat(body).doesNotContain(clearingAccountId().toString());

        // The closing, recomputed independently: BigDecimal over the raw rows through TO,
        // signed by direction against the wallet's CREDIT normal balance - never through
        // Money, because the sweep must not certify the kernel with the kernel.
        assertThat(new BigDecimal("11.50"))
                .isEqualByComparingTo(independentlyRecomputedThrough(account, TO));

        // A period before any posting: opening equals closing at the account's own scale,
        // and the lines are exactly none - the empty statement still says what it carries.
        HttpResponse<String> empty =
                statement(accountId, person.session(), LocalDate.of(2026, 9, 1),
                        LocalDate.of(2026, 9, 5));
        assertThat(empty.statusCode()).isEqualTo(200);
        assertThat(empty.body())
                .contains("\"opening\":\"0.00\"")
                .contains("\"closing\":\"0.00\"")
                .contains("\"lines\":[]");
    }

    @Test
    @DisplayName("not-yours, unknown and malformed are one 404, asserted as an equality")
    void ownershipIsOne404() throws Exception {
        Person a = givenAPerson();
        Person b = givenAPerson();
        String bAccount = field(post("/me/accounts", b.session(), OPEN_USD, key()).body(), "id");

        HttpResponse<String> notYours = statement(bAccount, a.session(), FROM, TO);
        HttpResponse<String> unknown = statement(IDS.next().toString(), a.session(), FROM, TO);
        HttpResponse<String> malformed = statement("not-an-identifier", a.session(), FROM, TO);
        assertThat(notYours.statusCode()).isEqualTo(404);
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(malformed.statusCode()).isEqualTo(404);
        assertThat(withoutCorrelation(notYours.body()))
                .isEqualTo(withoutCorrelation(unknown.body()))
                .isEqualTo(withoutCorrelation(malformed.body()));
    }

    @Test
    @DisplayName("the period parameters are the caller's own: specific 422s, never our 500;"
            + " and no session is a 401")
    void thePeriodParametersAreTheCallers() throws Exception {
        Person person = givenAPerson();
        String accountId = field(post("/me/accounts", person.session(), OPEN_USD, key()).body(), "id");

        // Inverted period: refused before any read, naming the mistake.
        HttpResponse<String> inverted = statement(accountId, person.session(), TO, FROM);
        assertThat(inverted.statusCode()).isEqualTo(422);
        assertThat(inverted.body()).contains("api.ValidationFailed");

        // A malformed date names the parameter and never echoes the value.
        HttpResponse<String> malformed =
                get("/me/accounts/" + accountId + "/statement?from=12-09-2026&to=2026-09-14",
                        person.session());
        assertThat(malformed.statusCode()).isEqualTo(422);
        assertThat(malformed.body()).contains("api.ValidationFailed").contains("'from'")
                .doesNotContain("12-09-2026");

        // A missing parameter is the framework's own 4xx - the caller's fault, never ours.
        HttpResponse<String> missing =
                get("/me/accounts/" + accountId + "/statement?from=2026-09-12", person.session());
        assertThat(missing.statusCode()).isGreaterThanOrEqualTo(400).isLessThan(500);

        assertThat(statement(accountId, null, FROM, TO).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("an adjustment appears as its line - and its reason never does")
    void anAdjustmentsReasonStaysOutOfTheStatement() throws Exception {
        Person person = givenAPerson();
        String accountId = field(post("/me/accounts", person.session(), OPEN_USD, key()).body(), "id");
        UUID account = UUID.fromString(accountId);

        String reason = "Correcting reconciliation break CASE-77 per operations review";
        AdjustmentService adjustments = adjustmentService();
        AdjustmentService.ProposalResult proposal;
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope initiator =
                        SecurityContext.enter(
                                new Actor("stmt-initiator", ActorType.EMPLOYEE));
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            proposal =
                    adjustments.propose(
                            app,
                            new AdjustmentCommand(
                                    UUID.randomUUID().toString(),
                                    FROM,
                                    FROM,
                                    "statement-adjustment-" + IDS.next(),
                                    reason,
                                    List.of(
                                            new JournalLine(
                                                    clearing().id(),
                                                    Direction.DEBIT,
                                                    Money.ofMinorUnits(300, USD)),
                                            new JournalLine(
                                                    wallet(account).id(),
                                                    Direction.CREDIT,
                                                    Money.ofMinorUnits(300, USD)))));
            app.commit();
        }
        // Four-eyes (P3-TSK-021): the entry posts at a SECOND person's approval.
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope approver =
                        SecurityContext.enter(new Actor("stmt-approver", ActorType.EMPLOYEE));
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            adjustments.approve(app, proposal.proposalId());
            app.commit();
        }

        String body = statement(accountId, person.session(), FROM, TO).body();
        assertThat(body)
                .contains("\"entryType\":\"ADJUSTMENT\"")
                .contains("\"amount\":\"3.00\"")
                // The justification is audit material, RESTRICTED-PII free text written by
                // a person - never statement material (INV-AUD-02's reasoning at this
                // surface). The audit record carries it; the statement must not.
                .doesNotContain("CASE-77")
                .doesNotContain("reason");
    }

    // -----------------------------------------------------------------
    // Fixtures

    private record Person(UUID party, UUID customer, IdentityId identity, String session) {}

    /** A person with an {@code ACTIVE} customer, an active identity and a live session. */
    private Person givenAPerson() throws SQLException {
        UUID party = IDS.next();
        UUID customer = IDS.next();
        UUID identity = IDS.next();
        String login = "s" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Statement Endpoint Person',"
                            + " now() - interval '2 hour')",
                    party);
            execute(
                    app,
                    "INSERT INTO party.customer (id, party_id, status, opened_at,"
                            + " status_changed_at) VALUES (?, ?, 'ACTIVE',"
                            + " now() - interval '1 hour', now() - interval '1 hour')",
                    customer,
                    party);
            execute(
                    app,
                    "INSERT INTO identity.identity (id, party_id, login_identifier, status,"
                            + " created_at, status_changed_at)"
                            + " VALUES (?, ?, ?, 'ACTIVE', now() - interval '1 hour',"
                            + " now() - interval '1 hour')",
                    identity,
                    party,
                    login);
        }
        return new Person(
                party, customer, IdentityId.of(identity), givenASessionFor(IdentityId.of(identity)));
    }

    private String givenASessionFor(IdentityId identity) throws SQLException {
        byte[] bytes = new byte[32];
        RANDOMNESS.nextBytes(bytes);
        String plaintext = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Session session =
                Session.issue(
                        IDS,
                        CLOCK,
                        identity,
                        SessionToken.of(plaintext),
                        AssuranceLevel.PASSWORD,
                        SessionPolicy.current());
        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, session);
        }
        return plaintext;
    }

    private static IdempotentExecutor executor() {
        return new IdempotentExecutor(
                new JdbcIdempotencyRecordStore(), CLOCK, Duration.ofDays(1), Duration.ofMinutes(5));
    }

    private static PostingService postingService() {
        return new PostingService(
                executor(),
                new JdbcJournalEntryStore(IDS),
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(),
                new JdbcBalanceProjection(),
                IDS,
                CLOCK, PostingObserver.NONE);
    }

    private static AdjustmentService adjustmentService() {
        return new AdjustmentService(
                executor(),
                new JdbcJournalEntryStore(IDS),
                new JdbcAdjustmentProposalStore(),
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(),
                new JdbcBalanceProjection(),
                IDS,
                CLOCK, PostingObserver.NONE);
    }

    private void postCredit(UUID account, LocalDate postingDate, long minorUnits)
            throws Exception {
        postBalanced(account, postingDate, minorUnits, true);
    }

    private void postDebit(UUID account, LocalDate postingDate, long minorUnits)
            throws Exception {
        postBalanced(account, postingDate, minorUnits, false);
    }

    /** A balanced clearing-vs-wallet posting on {@code postingDate}. */
    private void postBalanced(
            UUID account, LocalDate postingDate, long minorUnits, boolean creditTheWallet)
            throws Exception {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            LedgerAccount wallet = wallet(account);
            LedgerAccount clearing = clearing();
            postingService()
                    .post(
                            app,
                            new PostingCommand(
                                    "statement-" + IDS.next(),
                                    postingDate,
                                    postingDate,
                                    "statement-fixture",
                                    List.of(
                                            new JournalLine(
                                                    clearing.id(),
                                                    creditTheWallet
                                                            ? Direction.DEBIT
                                                            : Direction.CREDIT,
                                                    Money.ofMinorUnits(minorUnits, USD)),
                                            new JournalLine(
                                                    wallet.id(),
                                                    creditTheWallet
                                                            ? Direction.CREDIT
                                                            : Direction.DEBIT,
                                                    Money.ofMinorUnits(minorUnits, USD)))));
            app.commit();
        }
    }

    private LedgerAccount wallet(UUID account) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            return ledgerAccounts
                    .findOwned(app, account, AccountPurpose.CUSTOMER_WALLET, USD)
                    .orElseThrow();
        }
    }

    private LedgerAccount clearing() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            return ledgerAccounts
                    .findOperational(app, AccountPurpose.SETTLEMENT_CLEARING, USD)
                    .orElseThrow();
        }
    }

    private UUID clearingAccountId() throws SQLException {
        return clearing().id().value();
    }

    /**
     * The independent check: signed {@code BigDecimal} sum over the raw rows with
     * {@code posting_date <= through}, credits positive against the wallet's {@code CREDIT}
     * normal balance — never through {@code Money}.
     */
    private BigDecimal independentlyRecomputedThrough(UUID account, LocalDate through)
            throws SQLException {
        UUID walletLedgerAccount = wallet(account).id().value();
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT line.direction, line.amount_minor, line.scale"
                                        + " FROM ledger.journal_line line"
                                        + " JOIN ledger.journal_entry entry"
                                        + " ON entry.id = line.entry_id"
                                        + " WHERE line.ledger_account_id = ?"
                                        + " AND entry.posting_date <= ?")) {
            read.setObject(1, walletLedgerAccount);
            read.setObject(2, through);
            try (ResultSet rows = read.executeQuery()) {
                BigDecimal total = BigDecimal.ZERO;
                while (rows.next()) {
                    BigDecimal amount =
                            BigDecimal.valueOf(rows.getLong("amount_minor"), rows.getInt("scale"));
                    total =
                            "CREDIT".equals(rows.getString("direction"))
                                    ? total.add(amount)
                                    : total.subtract(amount);
                }
                return total;
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

    // -----------------------------------------------------------------
    // HTTP

    private HttpResponse<String> statement(
            String accountId, String token, LocalDate from, LocalDate to) throws Exception {
        return get(
                "/me/accounts/" + accountId + "/statement?from=" + from + "&to=" + to, token);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1" + path));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String token, String body, String idempotencyKey)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1" + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    /** The value of a top-level string member of a small JSON body. */
    private static String field(String body, String name) {
        String marker = "\"" + name + "\":\"";
        int start = body.indexOf(marker);
        assertThat(start).as("member %s in %s", name, body).isNotNegative();
        int from = start + marker.length();
        return body.substring(from, body.indexOf('"', from));
    }

    /** Correlation identifiers differ per request by design; everything else must not. */
    private static String withoutCorrelation(String body) {
        return body.replaceAll("\"correlationId\":\"[^\"]*\"", "\"correlationId\":\"-\"")
                .replaceAll("\"instance\":\"[^\"]*\"", "\"instance\":\"-\"");
    }

    private static CorrelationContext.Scope flow() {
        return CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
    }
}
