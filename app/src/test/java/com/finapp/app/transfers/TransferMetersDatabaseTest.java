package com.finapp.app.transfers;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
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
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The transfer meters, proven through the WIRED beans over real HTTP (`P4-TSK-011`, the
 * `P3-TSK-020` proof shape): a per-class test of {@code TransferMetrics} would prove a
 * counter counts, which is Micrometer's property, not ours. What only this suite can prove
 * is the counting discipline at the real seam — post-commit, acting call only — because the
 * defect worth money is a converged retry or a replayed key silently inflating throughput,
 * and only driving the deployed chain can see it.
 *
 * <p>Every assertion is a <strong>delta</strong> against the registry read before the act:
 * the context's registry is shared across this class's tests, and an absolute count would
 * couple each test to its neighbours' traffic.
 *
 * <p>The eager-registration half (a freshly started instance publishes every series) is
 * deliberately not re-proven here — {@code PlannedMetersExistTest}'s pinned Phase-4 guard
 * owns it, in the context that boots with nothing (`P1-TSK-012`: a second copy of a working
 * assertion is duplication that drifts).
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@DisplayName("the transfer meters count the acting call, post-commit (P4-TSK-011)")
class TransferMetersDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final CurrencyCode USD = CurrencyCode.of("USD");

    @LocalServerPort private int port;
    @Autowired private MeterRegistry registry;
    @Autowired private Authorization authorization;

    // -----------------------------------------------------------------
    // The transfer counter and the timer
    // -----------------------------------------------------------------

    @Test
    @DisplayName("the acting judgement counts once, its replay lands replayed with the original"
            + " outcome's series unchanged, and a committed FAILED counts as failed")
    void theActingJudgementAndItsReplayCount() throws Exception {
        String sender = verifiedCustomer(someLogin());
        String source = openAccount(sender);
        String destination = openAccount(verifiedCustomer(someLogin()));
        fund(source, 10_00);

        double completed = transferCount("completed");
        double replayed = transferCount("replayed");
        double failed = transferCount("failed");
        long timed = latencyCount();

        String key = someKey();
        String body = body(source, destination, null, "3.00", "USD", null);
        assertThat(field(transfer(sender, body, key).body(), "status")).isEqualTo("COMPLETED");
        assertThat(transferCount("completed")).isEqualTo(completed + 1);
        assertThat(latencyCount()).isEqualTo(timed + 1);

        // The retried key replays its recorded judgement (INV-IDEM-01): replayed moves,
        // completed does NOT - a replay counted as throughput is the defect the acting-call
        // discipline exists to prevent, and the mutation counting it as completed fails here.
        assertThat(field(transfer(sender, body, key).body(), "status")).isEqualTo("COMPLETED");
        assertThat(transferCount("replayed")).isEqualTo(replayed + 1);
        assertThat(transferCount("completed")).isEqualTo(completed + 1);
        // The replay is a command invocation like any other: it is timed.
        assertThat(latencyCount()).isEqualTo(timed + 2);

        // A committed refusal is a judgement, not a refusal: source = destination is the
        // aggregate's own FAILED(SELF_TRANSFER), a 201 whose body says so - and it lands on
        // failed, never on refused.
        HttpResponse<String> selfTransfer =
                transfer(sender, body(source, source, null, "1.00", "USD", null), someKey());
        assertThat(selfTransfer.statusCode()).isEqualTo(201);
        assertThat(field(selfTransfer.body(), "status")).isEqualTo("FAILED");
        assertThat(transferCount("failed")).isEqualTo(failed + 1);
        assertThat(transferCount("completed")).isEqualTo(completed + 1);
    }

    @Test
    @DisplayName("an INV-IDEM-03 conflict lands on its own series, never on an outcome")
    void aConflictLandsOnItsOwnSeries() throws Exception {
        String sender = verifiedCustomer(someLogin());
        String source = openAccount(sender);
        String destination = openAccount(verifiedCustomer(someLogin()));
        fund(source, 10_00);

        String key = someKey();
        assertThat(transfer(sender, body(source, destination, null, "2.00", "USD", null), key)
                        .statusCode())
                .isEqualTo(201);

        double conflicts = conflictCount();
        double completed = transferCount("completed");
        double replayed = transferCount("replayed");
        double refused = transferCount("refused");

        // The same key with a materially different request: the distinct 409 (INV-IDEM-03),
        // counted on the security signal's own series and on no outcome - a conflict is
        // neither a judgement nor a replay, and folding it into either would hide exactly
        // the reading an alert must watch alone.
        HttpResponse<String> conflicted =
                transfer(sender, body(source, destination, null, "9.99", "USD", null), key);
        assertThat(conflicted.statusCode()).isEqualTo(409);
        assertThat(conflictCount()).isEqualTo(conflicts + 1);
        assertThat(transferCount("completed")).isEqualTo(completed);
        assertThat(transferCount("replayed")).isEqualTo(replayed);
        assertThat(transferCount("refused")).isEqualTo(refused);
    }

    @Test
    @DisplayName("a resolution refusal counts as refused, with nothing judged - and it is"
            + " still timed")
    void aRefusalCountsAndStillTimes() throws Exception {
        String sender = verifiedCustomer(someLogin());
        String source = openAccount(sender);
        fund(source, 10_00);

        double refused = transferCount("refused");
        double completed = transferCount("completed");
        double failed = transferCount("failed");
        long timed = latencyCount();

        // An unknown destination: the 422 with nothing written (P4-TSK-008). The refusal is
        // a command the platform declined to judge - refused moves, no judgement series does,
        // and the timer still records, because a timer that only times successes flatters
        // exactly the incident an operator is trying to see.
        HttpResponse<String> refusal =
                transfer(sender, body(source, UUID.randomUUID().toString(), null, "1.00",
                        "USD", null), someKey());
        assertThat(refusal.statusCode()).isEqualTo(422);
        assertThat(refusal.body()).contains("transfers.UnknownDestination");
        assertThat(transferCount("refused")).isEqualTo(refused + 1);
        assertThat(transferCount("completed")).isEqualTo(completed);
        assertThat(transferCount("failed")).isEqualTo(failed);
        assertThat(latencyCount()).isEqualTo(timed + 1);
    }

    @Test
    @DisplayName("the acting reversal counts once; the machine's 409 is a refusal and never"
            + " a second reversed")
    void theReversalCountsOnceAndItsLoserIsARefusal() throws Exception {
        String sender = verifiedCustomer(someLogin());
        String source = openAccount(sender);
        String destination = openAccount(verifiedCustomer(someLogin()));
        fund(source, 10_00);
        HttpResponse<String> created =
                transfer(sender, body(source, destination, null, "4.00", "USD", null), someKey());
        assertThat(field(created.body(), "status")).isEqualTo("COMPLETED");
        String transferId = field(created.body(), "id");
        String operator = operator(someLogin());

        double reversed = transferCount("reversed");
        double refused = transferCount("refused");

        assertThat(reverse(operator, transferId, reasonBody("operator error, documented"))
                        .statusCode())
                .isEqualTo(201);
        assertThat(transferCount("reversed")).isEqualTo(reversed + 1);

        // The second reversal is the machine's 409 (COMPLETED -> REVERSED happens at most
        // once ever, P4-TSK-009): a command refused with nothing written - refused moves,
        // reversed does not. The mutation counting every reverse invocation fails here.
        assertThat(reverse(operator, transferId, reasonBody("operator error, documented"))
                        .statusCode())
                .isEqualTo(409);
        assertThat(transferCount("reversed")).isEqualTo(reversed + 1);
        assertThat(transferCount("refused")).isEqualTo(refused + 1);
    }

    // -----------------------------------------------------------------
    // The beneficiary counter
    // -----------------------------------------------------------------

    @Test
    @DisplayName("beneficiary converges are never throughput: added and removed count the"
            + " acting call only")
    void beneficiaryConvergesAreNeverThroughput() throws Exception {
        String owner = verifiedCustomer(someLogin());
        UUID destination = UUID.fromString(openAccount(verifiedCustomer(someLogin())));

        double added = beneficiaryCount("added");
        double removed = beneficiaryCount("removed");

        HttpResponse<String> created = createBeneficiary(owner, destination);
        String beneficiaryId = field(created.body(), "id");
        assertThat(beneficiaryCount("added")).isEqualTo(added + 1);

        // The identical retry converges onto the winner's row (P4-TSK-006): one saved
        // destination however many times it is said - and one count.
        assertThat(createBeneficiary(owner, destination).statusCode()).isEqualTo(201);
        assertThat(beneficiaryCount("added")).isEqualTo(added + 1);

        assertThat(deleteBeneficiary(owner, beneficiaryId).statusCode()).isEqualTo(204);
        assertThat(beneficiaryCount("removed")).isEqualTo(removed + 1);

        // The repeated DELETE converges on 204 with nothing moved: not throughput.
        assertThat(deleteBeneficiary(owner, beneficiaryId).statusCode()).isEqualTo(204);
        assertThat(beneficiaryCount("removed")).isEqualTo(removed + 1);
    }

    // -----------------------------------------------------------------
    // Meter reads
    // -----------------------------------------------------------------

    private double transferCount(String outcome) {
        return registry.get("finapp.transfers.transfer").tag("outcome", outcome).counter()
                .count();
    }

    private double beneficiaryCount(String outcome) {
        return registry.get("finapp.transfers.beneficiary").tag("outcome", outcome).counter()
                .count();
    }

    private double conflictCount() {
        return registry.get("finapp.transfers.conflict").counter().count();
    }

    private long latencyCount() {
        return registry.get("finapp.transfers.transfer.latency").timer().count();
    }

    // -----------------------------------------------------------------
    // Fixtures (the TransferEndpointDatabaseTest idioms)
    // -----------------------------------------------------------------

    private String verifiedCustomer(String login) throws Exception {
        assertThat(register(login).statusCode()).isEqualTo(201);
        try (Connection app = DatabaseRoles.application()) {
            // GREATEST(now(), opened_at): the container's clock runs behind the JVM's that
            // wrote opened_at, and the ordering constraint is right to refuse a backwards
            // status change (P1-TSK-031, the P2-TSK-006 idiom).
            execute(
                    app,
                    "UPDATE party.customer SET status = 'ACTIVE',"
                            + " status_changed_at = GREATEST(now(), opened_at)"
                            + " WHERE party_id ="
                            + " (SELECT party_id FROM identity.identity"
                            + "   WHERE login_identifier = ?)",
                    login);
        }
        return tokenFrom(authenticate(login).body());
    }

    /**
     * Registers an identity over HTTP, grants {@code LEDGER_OPERATOR} through the real
     * {@code Authorization} write (the `P4-TSK-009` fixture), and returns its token.
     */
    private String operator(String login) throws Exception {
        assertThat(register(login).statusCode()).isEqualTo(201);
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)))) {
            app.setAutoCommit(false);
            IdentityId identity = IdentityId.of(UUID.fromString(identityIdOf(app, login)));
            authorization.assign(app, identity, RoleName.LEDGER_OPERATOR, identity,
                    "test fixture");
            app.commit();
        }
        return tokenFrom(authenticate(login).body());
    }

    private static String identityIdOf(Connection app, String login) throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT id FROM identity.identity WHERE login_identifier = ?")) {
            read.setString(1, login);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    /** Opens a USD wallet over the real endpoint; returns the product identifier. */
    private String openAccount(String token) throws Exception {
        HttpResponse<String> opened =
                post(
                        "/v1/me/accounts",
                        "{\"productType\":\"WALLET\",\"currency\":\"USD\"}",
                        token,
                        true);
        assertThat(opened.statusCode()).isEqualTo(201);
        return field(opened.body(), "id");
    }

    /** Funds the product's wallet with a real posting (the execution suite's fixture). */
    private void fund(String product, long minorUnits) throws Exception {
        JdbcLedgerAccountStore ledgerAccounts = new JdbcLedgerAccountStore();
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope scope =
                        SecurityContext.enter(
                                new Actor(IDS.next().toString(), ActorType.CUSTOMER));
                CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)))) {
            app.setAutoCommit(false);
            LedgerAccountId wallet =
                    ledgerAccounts.findAllOwned(app, UUID.fromString(product)).stream()
                            .filter(a -> a.purpose() == AccountPurpose.CUSTOMER_WALLET)
                            .findFirst()
                            .orElseThrow()
                            .id();
            LedgerAccount clearing =
                    ledgerAccounts
                            .findOperational(app, AccountPurpose.SETTLEMENT_CLEARING, USD)
                            .orElseThrow();
            LocalDate today = LocalDate.now(CLOCK);
            new PostingService(
                            new IdempotentExecutor(
                                    new JdbcIdempotencyRecordStore(),
                                    CLOCK,
                                    Duration.ofDays(1),
                                    Duration.ofMinutes(5)),
                            new JdbcJournalEntryStore(IDS),
                            new JdbcAuditWriter(),
                            new JdbcOutboxWriter(),
                            new JdbcBalanceProjection(),
                            IDS,
                            CLOCK,
                            PostingObserver.NONE)
                    .post(
                            app,
                            new PostingCommand(
                                    "fund-" + IDS.next(),
                                    today,
                                    today,
                                    "funding",
                                    List.of(
                                            new JournalLine(
                                                    clearing.id(),
                                                    Direction.DEBIT,
                                                    Money.ofMinorUnits(minorUnits, USD)),
                                            new JournalLine(
                                                    wallet,
                                                    Direction.CREDIT,
                                                    Money.ofMinorUnits(minorUnits, USD)))));
            app.commit();
        }
    }

    private HttpResponse<String> createBeneficiary(String token, UUID destination)
            throws Exception {
        HttpResponse<String> created =
                post(
                        "/v1/beneficiaries",
                        "{\"displayName\":\"Aunt Vera\",\"destinationAccountId\":\""
                                + destination + "\"}",
                        token,
                        false);
        assertThat(created.statusCode()).isEqualTo(201);
        return created;
    }

    private HttpResponse<String> deleteBeneficiary(String token, String beneficiaryId)
            throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(
                                "http://localhost:" + port + "/v1/beneficiaries/"
                                        + beneficiaryId))
                        .header("Authorization", "Bearer " + token)
                        .DELETE()
                        .build();
        return send(request);
    }

    // -----------------------------------------------------------------
    // HTTP
    // -----------------------------------------------------------------

    private static String body(
            String source,
            String destinationAccount,
            String beneficiary,
            String amount,
            String currency,
            String reference) {
        StringBuilder json = new StringBuilder("{\"sourceAccountId\":\"").append(source)
                .append("\"");
        if (destinationAccount != null) {
            json.append(",\"destinationAccountId\":\"").append(destinationAccount).append("\"");
        }
        if (beneficiary != null) {
            json.append(",\"beneficiaryId\":\"").append(beneficiary).append("\"");
        }
        json.append(",\"amount\":\"").append(amount).append("\"");
        json.append(",\"currency\":\"").append(currency).append("\"");
        if (reference != null) {
            json.append(",\"reference\":\"").append(reference).append("\"");
        }
        return json.append("}").toString();
    }

    private static String reasonBody(String reason) {
        return "{\"reason\":\"" + reason + "\"}";
    }

    private HttpResponse<String> transfer(String token, String body, String idempotencyKey)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/v1/transfers"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.ofString(body));
        if (idempotencyKey != null) {
            request.header(IdempotencyKeyHeader.NAME, idempotencyKey);
        }
        return send(request.build());
    }

    private HttpResponse<String> reverse(String token, String transferId, String body)
            throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(
                                "http://localhost:" + port + "/v1/transfers/" + transferId
                                        + "/reversal"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
        return send(request);
    }

    private HttpResponse<String> register(String login) throws Exception {
        return post(
                "/v1/registrations",
                "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                        + "\"password\":\"" + PASSWORD + "\"}",
                null,
                true);
    }

    private HttpResponse<String> authenticate(String login) throws Exception {
        return post(
                "/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}",
                null,
                false);
    }

    private HttpResponse<String> post(
            String path, String body, String token, boolean idempotencyKey) throws Exception {
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
        if (idempotencyKey) {
            request.header(IdempotencyKeyHeader.NAME, someKey());
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
    // Parsing
    // -----------------------------------------------------------------

    private static String someKey() {
        return UUID.randomUUID().toString();
    }

    private static String someLogin() {
        return "meters." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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
