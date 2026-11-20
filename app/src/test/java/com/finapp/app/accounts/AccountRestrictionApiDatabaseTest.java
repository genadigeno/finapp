package com.finapp.app.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounts.AccountsErrorCode;
import com.finapp.accounts.JdbcCustomerAccountStore;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcSessionStore;
import com.finapp.identity.RoleName;
import com.finapp.identity.SessionPolicy;
import com.finapp.identity.SessionStore;
import com.finapp.identity.SessionToken;
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
import com.finapp.party.JdbcPartyStore;
import com.finapp.platform.api.ApiException;
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
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * An agreement's restriction by four eyes over HTTP (`P17-TSK-028`, ADR-0153): the analyst proposes, the officer decides;
 * a suspended agreement refuses new customer-initiated outflows at the command's own check and admits credits; a transfer
 * racing the suspension commits wholly before it or is refused after it, never half.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("an agreement's restriction by four eyes over HTTP (P17-TSK-028)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class AccountRestrictionApiDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final String PROPOSAL = "{\"reasonCode\":\"FRAUD_SUSPECTED\",\"reason\":\"card testing from a new device\"}";
    private static final String REINSTATING = "{\"reasonCode\":\"INVESTIGATION_CLEARED\",\"reason\":\"the device was the customer's\"}";
    private static final String AGREED = "{\"reason\":\"the evidence supports it\"}";

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    private final SessionStore<Connection> sessions = new JdbcSessionStore();
    private final HttpClient http = HttpClient.newHttpClient();

    private record Person(UUID id, String token) {}

    /** A customer with a funded wallet agreement, and another customer's agreement to move money to and from. */
    private record Subject(String token, String account, String customerId, String otherToken, String other) {}

    @Test
    @DisplayName("each door asks its permission and a second factor: a customer, a password session and the other role are"
            + " refused, and nothing is written")
    void everyDoorAsksItsPermissionAndAFactor() throws Exception {
        Subject subject = subject(10_00);
        Person analyst = person(RoleName.RISK_ANALYST, AssuranceLevel.MULTI_FACTOR);
        Person officer = person(RoleName.RISK_OFFICER, AssuranceLevel.MULTI_FACTOR);
        Person passwordOnly = person(RoleName.RISK_ANALYST, AssuranceLevel.PASSWORD);

        assertThat(post("/v1/operator/accounts/" + subject.account() + "/suspension", PROPOSAL, subject.token(), key())
                .statusCode()).as("a customer").isEqualTo(403);
        assertThat(post("/v1/operator/accounts/" + subject.account() + "/suspension", PROPOSAL, officer.token(), key())
                .statusCode()).as("the officer proposes nothing").isEqualTo(403);
        assertThat(post("/v1/operator/accounts/" + subject.account() + "/suspension", PROPOSAL, passwordOnly.token(), key())
                .statusCode()).as("a password session").isIn(401, 403);
        HttpResponse<String> proposed = post("/v1/operator/accounts/" + subject.account() + "/suspension", PROPOSAL,
                analyst.token(), key());
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        String restriction = field(proposed.body(), "restrictionId");
        assertThat(post("/v1/operator/account-restrictions/" + restriction + "/approval", AGREED, analyst.token(), key())
                .statusCode()).as("the analyst decides nothing").isEqualTo(403);
        assertThat(post("/v1/operator/account-restrictions/" + restriction + "/approval", AGREED, subject.token(), key())
                .statusCode()).as("a customer").isEqualTo(403);
        assertThat(post("/v1/operator/accounts/" + subject.account() + "/suspension", PROPOSAL, analyst.token(), null)
                .statusCode()).as("no key: the platform's 422 for a missing Idempotency-Key").isEqualTo(422);
        assertThat(accountStatus(subject.account())).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("proposed by the analyst, approved by the officer: the agreement is SUSPENDED, a transfer out FAILS"
            + " SOURCE_NOT_POSTABLE, the outflow gate refuses, money in still lands; reinstated the same way, it sends again")
    void aSuspensionRefusesNewOutflowsAndAdmitsCredits() throws Exception {
        Subject subject = subject(10_00);
        Person analyst = person(RoleName.RISK_ANALYST, AssuranceLevel.MULTI_FACTOR);
        Person officer = person(RoleName.RISK_OFFICER, AssuranceLevel.MULTI_FACTOR);

        HttpResponse<String> proposed = post("/v1/operator/accounts/" + subject.account() + "/suspension", PROPOSAL,
                analyst.token(), key());
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        assertThat(field(proposed.body(), "status")).isEqualTo("PROPOSED");
        assertThat(transferStatus(subject.token(), subject.account(), subject.other(), "1.00"))
                .as("a proposal moves nothing").isEqualTo("COMPLETED");

        String restriction = field(proposed.body(), "restrictionId");
        HttpResponse<String> approved = post("/v1/operator/account-restrictions/" + restriction + "/approval", AGREED,
                officer.token(), key());
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(field(approved.body(), "status")).isEqualTo("APPROVED");
        assertThat(field(approved.body(), "accountStatus")).isEqualTo("SUSPENDED");
        assertThat(accountStatus(subject.account())).isEqualTo("SUSPENDED");

        assertThat(transferStatus(subject.token(), subject.account(), subject.other(), "1.00"))
                .as("a new outflow, refused at the transfer's own check").isEqualTo("FAILED:SOURCE_NOT_POSTABLE");
        assertThatThrownBy(() -> gate(subject.customerId())).as("the gate withdrawals, wallet payments and FX share")
                .isInstanceOfSatisfying(ApiException.class,
                        refused -> assertThat(refused.errorCode()).isEqualTo(AccountsErrorCode.OUTFLOW_SUSPENDED));
        assertThat(transferStatus(subject.otherToken(), subject.other(), subject.account(), "2.00"))
                .as("a credit into the suspended agreement lands").isEqualTo("COMPLETED");
        assertThat(walletBalanceMinor(subject.account())).as("10.00 - 1.00 + 2.00: the restriction moved no money")
                .isEqualTo(11_00);
        assertThat(ledgerAccountStatuses(subject.account())).as("the ledger accounts stay ACTIVE (ADR-0153 decision 2)")
                .containsOnly("ACTIVE");

        assertThat(recordedBy(analyst)).contains("accounts.RestrictionProposed customer_account " + subject.account());
        assertThat(recordedBy(officer)).contains("accounts.AccountSuspended customer_account " + subject.account());

        HttpResponse<String> reinstating = post("/v1/operator/accounts/" + subject.account() + "/reinstatement", REINSTATING,
                analyst.token(), key());
        assertThat(reinstating.statusCode()).as(reinstating.body()).isEqualTo(201);
        HttpResponse<String> reinstated = post("/v1/operator/account-restrictions/" + field(reinstating.body(), "restrictionId")
                + "/approval", AGREED, officer.token(), key());
        assertThat(reinstated.statusCode()).as(reinstated.body()).isEqualTo(200);
        assertThat(accountStatus(subject.account())).isEqualTo("ACTIVE");
        assertThat(transferStatus(subject.token(), subject.account(), subject.other(), "1.00")).isEqualTo("COMPLETED");
        assertThat(recordedBy(officer)).contains("accounts.AccountReinstated customer_account " + subject.account());
    }

    @Test
    @DisplayName("the machine refuses what does not apply: reinstating an ACTIVE agreement, a second open proposal, a decided"
            + " one decided again, a reason code of the other kind, an unknown agreement; a rejection moves nothing")
    void theMachineRefusesWhatDoesNotApply() throws Exception {
        Subject subject = subject(1_00);
        Person analyst = person(RoleName.RISK_ANALYST, AssuranceLevel.MULTI_FACTOR);
        Person officer = person(RoleName.RISK_OFFICER, AssuranceLevel.MULTI_FACTOR);
        String suspension = "/v1/operator/accounts/" + subject.account() + "/suspension";

        assertCode(post("/v1/operator/accounts/" + subject.account() + "/reinstatement", REINSTATING, analyst.token(), key()),
                409, "accounts.RestrictionNotApplicable");
        assertCode(post(suspension, REINSTATING, analyst.token(), key()), 422, "accounts.RestrictionReasonInvalid");
        assertCode(post(suspension, "{\"reasonCode\":\"BORED\",\"reason\":\"x\"}", analyst.token(), key()), 422,
                "accounts.RestrictionReasonInvalid");
        assertThat(post("/v1/operator/accounts/" + IDS.next() + "/suspension", PROPOSAL, analyst.token(), key()).statusCode())
                .isEqualTo(404);

        HttpResponse<String> proposed = post(suspension, PROPOSAL, analyst.token(), key());
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        assertCode(post(suspension, PROPOSAL, analyst.token(), key()), 409, "accounts.RestrictionAlreadyProposed");

        String restriction = field(proposed.body(), "restrictionId");
        HttpResponse<String> rejected = post("/v1/operator/account-restrictions/" + restriction + "/rejection", AGREED,
                officer.token(), key());
        assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(200);
        assertThat(field(rejected.body(), "status")).isEqualTo("REJECTED");
        assertThat(accountStatus(subject.account())).as("a rejection moves nothing").isEqualTo("ACTIVE");
        assertCode(post("/v1/operator/account-restrictions/" + restriction + "/approval", AGREED, officer.token(), key()),
                409, "accounts.RestrictionAlreadyDecided");
        assertThat(post(suspension, PROPOSAL, analyst.token(), key()).statusCode())
                .as("a decided proposal frees the agreement for the next").isEqualTo(201);
    }

    @Test
    @DisplayName("four eyes for every writer: V003's CHECK refuses a decision by the proposer, whoever writes it")
    void fourEyesIsTheSchemas() throws Exception {
        Subject subject = subject(1_00);
        Person analyst = person(RoleName.RISK_ANALYST, AssuranceLevel.MULTI_FACTOR);
        HttpResponse<String> proposed = post("/v1/operator/accounts/" + subject.account() + "/suspension", PROPOSAL,
                analyst.token(), key());
        String restriction = field(proposed.body(), "restrictionId");
        try (Connection app = DatabaseRoles.application()) {
            assertThatThrownBy(() -> execute(app, "UPDATE accounts.account_restriction SET status = 'APPROVED',"
                    + " decided_by = proposed_by, decided_by_type = 'EMPLOYEE', decided_at = proposed_at + interval '1 second',"
                    + " decision_reason = 'mine',"
                    + " decision_correlation_id = 'c' WHERE id = ?", UUID.fromString(restriction)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("account_restriction_four_eyes");
        }
    }

    @Test
    @DisplayName("keyed: a retry with the same key replays the first receipt, one proposal written; another body under the"
            + " key is 409")
    void aRetryReplays() throws Exception {
        Subject subject = subject(1_00);
        Person analyst = person(RoleName.RISK_ANALYST, AssuranceLevel.MULTI_FACTOR);
        String key = key();
        String path = "/v1/operator/accounts/" + subject.account() + "/suspension";
        HttpResponse<String> first = post(path, PROPOSAL, analyst.token(), key);
        HttpResponse<String> again = post(path, PROPOSAL, analyst.token(), key);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        assertThat(field(again.body(), "restrictionId")).isEqualTo(field(first.body(), "restrictionId"));
        assertThat(restrictionsOf(subject.account())).isEqualTo(1);
        assertThat(post(path, "{\"reasonCode\":\"LEGAL_ORDER\",\"reason\":\"a court order\"}", analyst.token(), key)
                .statusCode()).isEqualTo(409);
    }

    @Test
    @DisplayName("the race: ten transfers out while the suspension is approved - each COMPLETED before it or FAILED"
            + " SOURCE_NOT_POSTABLE after it, never half: the balance is the funding less exactly the completed ones")
    void aTransferRacingTheSuspensionIsWhollyBeforeOrRefusedAfter() throws Exception {
        Subject subject = subject(10_00);
        Person analyst = person(RoleName.RISK_ANALYST, AssuranceLevel.MULTI_FACTOR);
        Person officer = person(RoleName.RISK_OFFICER, AssuranceLevel.MULTI_FACTOR);
        HttpResponse<String> proposed = post("/v1/operator/accounts/" + subject.account() + "/suspension", PROPOSAL,
                analyst.token(), key());
        String restriction = field(proposed.body(), "restrictionId");

        int racers = 10;
        ExecutorService pool = Executors.newFixedThreadPool(racers + 1);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> transfers = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                transfers.add(pool.submit(raced(start,
                        () -> transferStatus(subject.token(), subject.account(), subject.other(), "0.50"))));
            }
            Future<Integer> approval = pool.submit(raced(start, () -> postRetryingShed("/v1/operator/account-restrictions/"
                    + restriction + "/approval", AGREED, officer.token(), key()).statusCode()));
            start.countDown();
            assertThat(approval.get()).isEqualTo(200);
            int completed = 0;
            for (Future<String> transfer : transfers) {
                String outcome = transfer.get();
                assertThat(outcome).as("each wholly before or refused after").isIn("COMPLETED", "FAILED:SOURCE_NOT_POSTABLE");
                completed += outcome.equals("COMPLETED") ? 1 : 0;
            }
            assertThat(walletBalanceMinor(subject.account())).as("the funding less exactly the completed transfers")
                    .isEqualTo(10_00 - completed * 50L);
            assertThat(completedTransfersOutOf(subject.account())).isEqualTo(completed);
        } finally {
            pool.shutdownNow();
        }
        assertThat(transferStatus(subject.token(), subject.account(), subject.other(), "0.50"))
                .as("after the race, the suspension holds").isEqualTo("FAILED:SOURCE_NOT_POSTABLE");
    }

    // ------------------------------------------------------------------ fixtures

    private static <T> Callable<T> raced(CountDownLatch start, Callable<T> work) {
        return () -> {
            start.await();
            return work.call();
        };
    }

    private Subject subject(long fundingMinor) throws Exception {
        String token = verifiedCustomer("ar" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        String account = openAccount(token);
        fund(account, fundingMinor);
        String otherToken = verifiedCustomer("ar" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        String other = openAccount(otherToken);
        fund(other, 10_00);
        return new Subject(token, account, customerOf(account), otherToken, other);
    }

    /** The transfer's outcome: COMPLETED, or FAILED:<reason>. */
    private String transferStatus(String token, String from, String to, String amount) throws Exception {
        HttpResponse<String> created = postRetryingShed("/v1/transfers", "{\"sourceAccountId\":\"" + from
                + "\",\"destinationAccountId\":\"" + to + "\",\"amount\":\"" + amount + "\",\"currency\":\"USD\","
                + "\"reference\":\"race\"}", token, key());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String status = field(created.body(), "status");
        return status.equals("FAILED") ? status + ":" + field(created.body(), "failureReason") : status;
    }

    private void gate(String customerId) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try {
                new OutflowGate(new JdbcPartyStore(), new JdbcCustomerAccountStore()).requireForCustomer(app,
                        UUID.fromString(customerId));
            } finally {
                app.rollback();
            }
        }
    }

    private String verifiedCustomer(String login) throws Exception {
        HttpResponse<String> registered = post("/v1/registrations", "{\"loginIdentifier\":\"" + login
                + "\",\"displayName\":\"Ada Lovelace\",\"password\":\"" + PASSWORD + "\"}", null, key());
        assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
        try (Connection app = DatabaseRoles.application()) {
            execute(app, "UPDATE party.customer SET status = 'ACTIVE', status_changed_at = GREATEST(now(), opened_at)"
                    + " WHERE party_id = (SELECT party_id FROM identity.identity WHERE login_identifier = ?)", login);
        }
        HttpResponse<String> authenticated = post("/v1/authentications", "{\"loginIdentifier\":\"" + login
                + "\",\"password\":\"" + PASSWORD + "\"}", null, null);
        return field(authenticated.body(), "sessionToken");
    }

    private String openAccount(String token) throws Exception {
        HttpResponse<String> opened = post("/v1/me/accounts", "{\"productType\":\"WALLET\",\"currency\":\"USD\"}", token, key());
        assertThat(opened.statusCode()).as(opened.body()).isEqualTo(201);
        return field(opened.body(), "id");
    }

    /** Funds the product's wallet with a real posting (the transfer suites' fixture). */
    private void fund(String product, long minorUnits) throws Exception {
        JdbcLedgerAccountStore ledgerAccounts = new JdbcLedgerAccountStore();
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope scope = SecurityContext.enter(new Actor(IDS.next().toString(), ActorType.CUSTOMER));
                CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            app.setAutoCommit(false);
            LedgerAccountId wallet = ledgerAccounts.findAllOwned(app, UUID.fromString(product)).stream()
                    .filter(a -> a.purpose() == AccountPurpose.CUSTOMER_WALLET).findFirst().orElseThrow().id();
            LedgerAccount operational = ledgerAccounts.findOperational(app, AccountPurpose.FEE_REVENUE, USD).orElseThrow();
            LocalDate today = LocalDate.now(CLOCK);
            new PostingService(new IdempotentExecutor(new JdbcIdempotencyRecordStore(), CLOCK, Duration.ofDays(1),
                    Duration.ofMinutes(5)), new JdbcJournalEntryStore(IDS), new JdbcAuditWriter(), new JdbcOutboxWriter(),
                    new JdbcBalanceProjection(), IDS, CLOCK, PostingObserver.NONE)
                    .post(app, new PostingCommand("fund-" + IDS.next(), today, today, "funding", List.of(
                            new JournalLine(operational.id(), Direction.DEBIT, Money.ofMinorUnits(minorUnits, USD)),
                            new JournalLine(wallet, Direction.CREDIT, Money.ofMinorUnits(minorUnits, USD)))));
            app.commit();
        }
    }

    private static String customerOf(String account) throws SQLException {
        return single("SELECT customer_id::text FROM accounts.customer_account WHERE id = ?", account);
    }

    private static String accountStatus(String account) throws SQLException {
        return single("SELECT status FROM accounts.customer_account WHERE id = ?", account);
    }

    private static List<String> ledgerAccountStatuses(String account) throws SQLException {
        List<String> statuses = new ArrayList<>();
        try (Connection root = DatabaseRoles.bootstrap();
                PreparedStatement read = root.prepareStatement("SELECT status FROM ledger.ledger_account WHERE owner_ref = ?")) {
            read.setObject(1, UUID.fromString(account));
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    statuses.add(rows.getString(1));
                }
            }
        }
        return statuses;
    }

    /** The wallet's settled balance in minor units, from the journal itself: credits less debits. */
    private static long walletBalanceMinor(String account) throws SQLException {
        return Long.parseLong(single("SELECT coalesce(sum(CASE line.direction WHEN 'CREDIT' THEN line.amount_minor"
                + " ELSE -line.amount_minor END), 0)::text FROM ledger.journal_line line JOIN ledger.ledger_account wallet"
                + " ON wallet.id = line.ledger_account_id WHERE wallet.owner_ref = ? AND wallet.purpose = 'CUSTOMER_WALLET'",
                account));
    }

    private static int completedTransfersOutOf(String account) throws SQLException {
        return Integer.parseInt(single("SELECT count(*)::text FROM transfers.transfer t JOIN ledger.ledger_account source"
                + " ON source.id = t.source_account_id WHERE source.owner_ref = ? AND t.status = 'COMPLETED'"
                + " AND t.amount_minor = 50", account));
    }

    private static int restrictionsOf(String account) throws SQLException {
        return Integer.parseInt(single("SELECT count(*)::text FROM accounts.account_restriction WHERE account_id = ?", account));
    }

    private static String single(String sql, String uuid) throws SQLException {
        try (Connection root = DatabaseRoles.bootstrap(); PreparedStatement read = root.prepareStatement(sql)) {
            read.setObject(1, UUID.fromString(uuid));
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getString(1);
            }
        }
    }

    private static void assertCode(HttpResponse<String> answer, int status, String code) {
        assertThat(answer.statusCode()).as(answer.body()).isEqualTo(status);
        assertThat(answer.body()).contains("\"code\":\"" + code + "\"");
    }

    /**
     * {@link #post}, retried with the same key while the platform sheds it ({@code 503 api.Overloaded}: "nothing was done;
     * retry the same request") - ten racers on one test machine meet the admission limit, and a shed request is no outcome.
     */
    private HttpResponse<String> postRetryingShed(String path, String body, String token, String idempotencyKey)
            throws Exception {
        HttpResponse<String> answer = post(path, body, token, idempotencyKey);
        for (int attempt = 0; attempt < 50 && answer.statusCode() == 503; attempt++) {
            Thread.sleep(100L + RANDOMNESS.nextInt(200));
            answer = post(path, body, token, idempotencyKey);
        }
        return answer;
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private HttpResponse<String> post(String path, String body, String token, String idempotencyKey) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (idempotencyKey != null) {
            request.header(IdempotencyKeyHeader.NAME, idempotencyKey);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String field(String body, String name) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("the body must carry %s: %s", name, body).isTrue();
        return matcher.group(1);
    }

    /** Every audit record this person wrote, as text: operation, target type, target, summary. */
    private static String recordedBy(Person actor) throws SQLException {
        try (Connection root = DatabaseRoles.bootstrap();
                PreparedStatement select = root.prepareStatement("SELECT string_agg(operation || ' ' || target_type || ' '"
                        + " || target_id || ' ' || coalesce(change_summary, ''), E'\\n') FROM platform.audit_record"
                        + " WHERE actor_id = ?")) {
            select.setString(1, actor.id().toString());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return String.valueOf(rows.getString(1));
            }
        }
    }

    /** A role holder with a session at {@code assurance}; no factor enrolled. */
    private Person person(RoleName role, AssuranceLevel assurance) throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        String login = "rs" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        try (Connection app = DatabaseRoles.application()) {
            execute(app, "INSERT INTO party.party (id, kind, display_name, registered_at) VALUES (?, 'PERSON', 'Risk Person',"
                    + " now())", party);
            execute(app, "INSERT INTO identity.identity (id, party_id, login_identifier, status, created_at, status_changed_at)"
                    + " VALUES (?, ?, ?, 'ACTIVE', now(), now())", identity, party, login);
        }
        try (CorrelationContext.Scope correlation = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of("p17-028-" + UUID.randomUUID())));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
            app.commit();
        }
        byte[] bytes = new byte[32];
        RANDOMNESS.nextBytes(bytes);
        String plaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, com.finapp.identity.Session.issue(IDS, CLOCK, IdentityId.of(identity), SessionToken.of(plaintext),
                    assurance, SessionPolicy.current()));
        }
        return new Person(identity, plaintext);
    }

    private static void execute(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.executeUpdate();
        }
    }
}
