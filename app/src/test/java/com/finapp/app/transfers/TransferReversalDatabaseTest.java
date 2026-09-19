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
import com.finapp.ledger.ReversalService;
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
import com.finapp.transfers.IllegalTransferTransitionException;
import com.finapp.transfers.JdbcTransferStore;
import com.finapp.transfers.TransferId;
import com.finapp.transfers.TransferReversal;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The reversal (`P4-TSK-009`, M4.5): {@code POST /v1/transfers/&#123;id&#125;/reversal} over
 * real HTTP, against a real PostgreSQL, with the operator granted {@code LEDGER_OPERATOR}
 * through the real {@code Authorization} write.
 *
 * <p>What only this suite proves: the original entry <strong>byte-identical</strong> after the
 * reversal (PostgreSQL's own renderings — the `P3-TSK-016` idiom); both balances restored
 * exactly; the machine's one 409 for {@code FAILED}, already-{@code REVERSED} and the loser of
 * a race; the permissionless refusal with nothing written ({@code INV-AUD-03}); ten concurrent
 * reversals producing <strong>one</strong> entry and one move, counted in the tables; and the
 * deterministic interleaving — the loser observed Lock-waiting on the transfer row's
 * {@code FOR UPDATE}, resuming to a refusal with <strong>nothing posted</strong>.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@DisplayName("the transfer reversal (P4-TSK-009)")
class TransferReversalDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final CurrencyCode USD = CurrencyCode.of("USD");

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    // -----------------------------------------------------------------

    @Test
    @DisplayName("an operator reverses a completed transfer: the original entry is"
            + " byte-identical, both balances are restored exactly, and the trail names the"
            + " operator with the reason - then a second reversal is the 409")
    void theReversalAcceptanceChainHolds() throws Exception {
        String sender = verifiedCustomer(someLogin());
        String receiver = verifiedCustomer(someLogin());
        String source = openAccount(sender);
        String destination = openAccount(receiver);
        fund(source, 10_00);

        HttpResponse<String> created =
                transfer(sender, transferBody(source, destination, "3.00"), someKey());
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(field(created.body(), "status")).isEqualTo("COMPLETED");
        String transferId = field(created.body(), "id");
        UUID entryId = UUID.fromString(field(created.body(), "journalEntryId"));

        // The byte-identity capture BEFORE the reversal: the whole row and every line, as
        // PostgreSQL itself renders them (INV-REV-01, the P3-TSK-016 idiom) - on top of the
        // standing DB-PRIVILEGE immutability, this is the claim about THIS reversal.
        String entryBefore;
        List<String> linesBefore;
        try (Connection app = DatabaseRoles.application()) {
            entryBefore = rowOf(app, entryId);
            linesBefore = lineRowsOf(app, entryId);
        }

        String operatorLogin = someLogin();
        String operator = operator(operatorLogin);
        String reason = "the recipient disputed the movement; case CS-4411";
        HttpResponse<String> reversed = reverse(operator, transferId, reasonBody(reason));
        assertThat(reversed.statusCode()).isEqualTo(201);
        assertThat(field(reversed.body(), "status")).isEqualTo("REVERSED");
        assertThat(field(reversed.body(), "journalEntryId")).isEqualTo(entryId.toString());
        String reversalEntryId = field(reversed.body(), "reversalEntryId");
        assertThat(reversalEntryId).isNotEqualTo(entryId.toString());
        assertThat(field(reversed.body(), "reversedAt")).isNotBlank();
        // The operator's identity is the audit trail's fact, never the customer view's.
        assertThat(reversed.body()).doesNotContain("reversedBy");

        try (Connection app = DatabaseRoles.application()) {
            // The original, byte-identical after the reversal (INV-REV-01).
            assertThat(rowOf(app, entryId))
                    .as("the original entry must be byte-identical after its reversal")
                    .isEqualTo(entryBefore);
            assertThat(lineRowsOf(app, entryId)).isEqualTo(linesBefore);

            // The reversal entry references the original - the chain walkable in both
            // directions (plan section 12), counted in the table.
            assertThat(reversalEntriesFor(app, entryId)).isEqualTo(1);

            // The trail: one record, the operator's identity, the reason verbatim
            // (requiresReason - the justification enters at the moment it is written).
            assertThat(reversalAuditCount(app, transferId)).isEqualTo(1);
            try (PreparedStatement read =
                    app.prepareStatement(
                            "SELECT actor_id, reason FROM platform.audit_record"
                                    + " WHERE operation = 'transfers.TransferReversed'"
                                    + " AND target_id = ?")) {
                read.setString(1, transferId);
                try (ResultSet row = read.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString("actor_id")).isEqualTo(identityIdOf(app, operatorLogin));
                    assertThat(row.getString("reason")).isEqualTo(reason);
                }
            }

            // The history row and the announced fact, in the same commit.
            assertThat(historyRows(app, transferId, "REVERSED")).isEqualTo(1);
            assertThat(reversalEvents(app, transferId)).isEqualTo(1);
        }

        // Both balances restored exactly - the customer-visible half of INV-REV-02.
        assertThat(get("/v1/me/accounts/" + source + "/balance", sender).body())
                .contains("\"settled\":\"10.00\"");
        assertThat(get("/v1/me/accounts/" + destination + "/balance", receiver).body())
                .contains("\"settled\":\"0.00\"");

        // The customer's own GET carries the reversal - the current state, by design.
        String read = get("/v1/transfers/" + transferId, sender).body();
        assertThat(field(read, "status")).isEqualTo("REVERSED");
        assertThat(field(read, "reversalEntryId")).isEqualTo(reversalEntryId);

        // A second reversal - the retry whose response was lost included - is the one 409:
        // the machine is the idempotency, and nothing more is written.
        HttpResponse<String> again = reverse(operator, transferId, reasonBody("retry"));
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(again.body()).contains("transfers.NotReversible");
        try (Connection app = DatabaseRoles.application()) {
            assertThat(reversalEntriesFor(app, entryId)).isEqualTo(1);
            assertThat(reversalAuditCount(app, transferId)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a FAILED transfer moved no money and has nothing to reverse: the one 409,"
            + " with nothing written")
    void aFailedTransferHasNothingToReverse() throws Exception {
        String sender = verifiedCustomer(someLogin());
        String receiver = verifiedCustomer(someLogin());
        String source = openAccount(sender);
        String destination = openAccount(receiver);
        fund(source, 1_00);

        HttpResponse<String> refused =
                transfer(sender, transferBody(source, destination, "100.00"), someKey());
        assertThat(field(refused.body(), "status")).isEqualTo("FAILED");
        String transferId = field(refused.body(), "id");

        HttpResponse<String> reversal =
                reverse(operator(someLogin()), transferId, reasonBody("nothing moved"));
        assertThat(reversal.statusCode()).isEqualTo(409);
        assertThat(reversal.body()).contains("transfers.NotReversible");

        // Nothing written: no audit record, no history row, and the judgement unchanged.
        try (Connection app = DatabaseRoles.application()) {
            assertThat(reversalAuditCount(app, transferId)).isZero();
            assertThat(historyRows(app, transferId, "REVERSED")).isZero();
        }
        assertThat(field(get("/v1/transfers/" + transferId, sender).body(), "status"))
                .isEqualTo("FAILED");
    }

    @Test
    @DisplayName("two concurrent reversals are serialised by the transfer row's FOR UPDATE:"
            + " the loser is observed Lock-waiting, resumes onto the winner's commit, and is"
            + " refused with nothing posted")
    void theLoserOfTheRaceIsRefusedWithNothingPosted() throws Exception {
        String sender = verifiedCustomer(someLogin());
        String receiver = verifiedCustomer(someLogin());
        String source = openAccount(sender);
        fund(source, 10_00);
        HttpResponse<String> created =
                transfer(sender, transferBody(source, openAccount(receiver), "2.00"), someKey());
        TransferId transferId = TransferId.of(UUID.fromString(field(created.body(), "id")));
        UUID entryId = UUID.fromString(field(created.body(), "journalEntryId"));

        TransferReversal reversal = composedReversal();
        try (Connection winner = DatabaseRoles.application()) {
            winner.setAutoCommit(false);
            try (SecurityContext.Scope actor = SecurityContext.enter(operatorActor());
                    CorrelationContext.Scope flow =
                            CorrelationContext.enter(
                                    Correlation.startingWith(CorrelationId.generate(IDS)))) {
                // The winner: locked, posted, moved - and NOT yet committed, so the loser's
                // interleaving is deterministic rather than scheduling luck (P0-TST-004).
                assertThat(reversal.reverse(winner, transferId, "the winner's reason"))
                        .isPresent();
            }

            AtomicReference<Throwable> losersOutcome = new AtomicReference<>();
            CountDownLatch loserDone = new CountDownLatch(1);
            Thread loser =
                    new Thread(
                            () -> {
                                try (Connection blocked = DatabaseRoles.application();
                                        SecurityContext.Scope actor =
                                                SecurityContext.enter(operatorActor());
                                        CorrelationContext.Scope flow =
                                                CorrelationContext.enter(
                                                        Correlation.startingWith(
                                                                CorrelationId.generate(IDS)))) {
                                    blocked.setAutoCommit(false);
                                    reversal.reverse(blocked, transferId, "the loser's reason");
                                    blocked.rollback();
                                } catch (Throwable outcome) {
                                    losersOutcome.set(outcome);
                                } finally {
                                    loserDone.countDown();
                                }
                            },
                            "reversal-loser");
            loser.start();

            // The P0-TST-004 idiom: the loser OBSERVED Lock-waiting on the transfer row's
            // FOR UPDATE - the assertion that catches the lock being dropped, where an
            // outcome-only assertion could pass by timing luck.
            awaitBlockedOn("FROM transfers.transfer WHERE id = $1", "FOR UPDATE");

            winner.commit();
            assertThat(loserDone.await(30, TimeUnit.SECONDS)).isTrue();
            loser.join();

            // The loser resumed onto the winner's committed row, saw REVERSED, and was
            // refused by the machine - the surface's one 409 - with NOTHING posted: one
            // reversal entry, one history row, one audit record, counted.
            assertThat(losersOutcome.get())
                    .isInstanceOf(IllegalTransferTransitionException.class);
        }
        try (Connection app = DatabaseRoles.application()) {
            assertThat(reversalEntriesFor(app, entryId)).isEqualTo(1);
            assertThat(historyRows(app, transferId.value().toString(), "REVERSED")).isEqualTo(1);
            assertThat(reversalAuditCount(app, transferId.value().toString())).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("ten concurrent reversals over HTTP produce exactly one reversal entry and"
            + " one state move, counted in the tables - one 201, nine 409s")
    void tenConcurrentReversalsProduceOneEntryAndOneMove() throws Exception {
        String sender = verifiedCustomer(someLogin());
        String receiver = verifiedCustomer(someLogin());
        String source = openAccount(sender);
        fund(source, 10_00);
        HttpResponse<String> created =
                transfer(sender, transferBody(source, openAccount(receiver), "4.00"), someKey());
        String transferId = field(created.body(), "id");
        UUID entryId = UUID.fromString(field(created.body(), "journalEntryId"));
        String operator = operator(someLogin());

        int racers = 10;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> outcomes = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            for (int i = 0; i < racers; i++) {
                String reason = "concurrent reversal " + i;
                outcomes.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return reverse(operator, transferId, reasonBody(reason))
                                            .statusCode();
                                }));
            }
            start.countDown();
            int reversedCount = 0;
            int refusedCount = 0;
            for (Future<Integer> outcome : outcomes) {
                int status = outcome.get(60, TimeUnit.SECONDS);
                if (status == 201) {
                    reversedCount++;
                } else if (status == 409) {
                    refusedCount++;
                }
            }
            assertThat(reversedCount).as("exactly one racer reverses").isEqualTo(1);
            assertThat(refusedCount).as("every loser gets the machine's 409").isEqualTo(9);
        } finally {
            pool.shutdownNow();
        }

        // Counted in the tables, never inferred from the answers (INV-CON-02's discipline).
        try (Connection app = DatabaseRoles.application()) {
            assertThat(reversalEntriesFor(app, entryId)).isEqualTo(1);
            assertThat(historyRows(app, transferId, "REVERSED")).isEqualTo(1);
            assertThat(reversalAuditCount(app, transferId)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("the controls hold: a session without the permission is refused with nothing"
            + " written, the reason is required and bounded, and unknown or malformed"
            + " identifiers are one 404")
    void theControlsHold() throws Exception {
        String sender = verifiedCustomer(someLogin());
        String receiver = verifiedCustomer(someLogin());
        String source = openAccount(sender);
        fund(source, 10_00);
        HttpResponse<String> created =
                transfer(sender, transferBody(source, openAccount(receiver), "2.00"), someKey());
        String transferId = field(created.body(), "id");
        UUID entryId = UUID.fromString(field(created.body(), "journalEntryId"));

        // The transfer's own customer holds no TRANSFER_REVERSE: refused, audited as a
        // denial by the interceptor, and NOTHING of the reversal written (INV-AUD-03) -
        // the acceptance test's operator success is the positive control, so this refusal
        // is not blanket.
        HttpResponse<String> forbidden = reverse(sender, transferId, reasonBody("mine"));
        assertThat(forbidden.statusCode()).isEqualTo(403);
        assertThat(forbidden.body()).contains("api.Forbidden");

        String operator = operator(someLogin());

        // The reason is required and bounded at the boundary - a 422 naming the field, and
        // nothing written (the P3-TSK-017 shape; AuditRecord's refusal is the layer beneath).
        HttpResponse<String> missing = reverse(operator, transferId, "{}");
        assertThat(missing.statusCode()).isEqualTo(422);
        assertThat(missing.body()).contains("reason");
        HttpResponse<String> blank = reverse(operator, transferId, reasonBody("   "));
        assertThat(blank.statusCode()).isEqualTo(422);
        HttpResponse<String> oversized =
                reverse(operator, transferId, reasonBody("x".repeat(1001)));
        assertThat(oversized.statusCode()).isEqualTo(422);

        try (Connection app = DatabaseRoles.application()) {
            assertThat(reversalEntriesFor(app, entryId)).isZero();
            assertThat(reversalAuditCount(app, transferId)).isZero();
            assertThat(historyRows(app, transferId, "REVERSED")).isZero();
        }
        assertThat(field(get("/v1/transfers/" + transferId, sender).body(), "status"))
                .isEqualTo("COMPLETED");

        // Unknown and malformed are one 404 (malformed-equals-absent, the P1-TSK-016
        // reasoning), byte-identical but for the identifiers every response varies by.
        HttpResponse<String> unknown = reverse(operator, IDS.next().toString(), reasonBody("x"));
        HttpResponse<String> malformed = reverse(operator, "not-a-uuid", reasonBody("x"));
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(malformed.statusCode()).isEqualTo(404);
        assertThat(normalized(unknown.body())).isEqualTo(normalized(malformed.body()));
    }

    // -----------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------

    /**
     * Registers over HTTP, promotes the customer to {@code ACTIVE} (this suite's subject is
     * the reversal, not onboarding), and returns a session token.
     */
    private String verifiedCustomer(String login) throws Exception {
        assertThat(register(login).statusCode()).isEqualTo(201);
        try (Connection app = DatabaseRoles.application()) {
            // GREATEST(now(), opened_at): the container's clock runs behind the JVM's that
            // wrote opened_at (P1-TSK-031, the P2-TSK-006 idiom).
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
     * {@code Authorization} write (the `P3-TSK-007` pattern - the boundary enum, the role
     * constraint and the per-request resolution are all on the path), and returns its token.
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

    /**
     * The command composed as the interleaving needs it - directly, on the caller's
     * connection, the {@code TransferExecutionDatabaseTest} stance. The composition root's
     * copy is exercised by every HTTP test in this suite.
     */
    private static TransferReversal composedReversal() {
        return new TransferReversal(
                new JdbcTransferStore(),
                new ReversalService(
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
                        PostingObserver.NONE),
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(),
                IDS,
                CLOCK);
    }

    /** An operator-shaped actor: the command parses the id as the person's identity UUID. */
    private static Actor operatorActor() {
        return new Actor(IDS.next().toString(), ActorType.CUSTOMER);
    }

    /** The {@code P0-TST-004} idiom: the losing side observed Lock-waiting, never assumed. */
    private static void awaitBlockedOn(String queryMarker, String secondMarker)
            throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        try (Connection observer = DatabaseRoles.application();
                PreparedStatement select =
                        observer.prepareStatement(
                                "SELECT count(*) FROM pg_stat_activity"
                                        + " WHERE wait_event_type = 'Lock'"
                                        + " AND query LIKE '%" + queryMarker + "%'"
                                        + " AND query LIKE '%" + secondMarker + "%'")) {
            while (System.nanoTime() < deadline) {
                try (ResultSet row = select.executeQuery()) {
                    row.next();
                    if (row.getLong(1) > 0) {
                        return;
                    }
                }
                Thread.sleep(25);
            }
        }
        throw new AssertionError(
                "the losing reversal never blocked on the transfer row's FOR UPDATE - without"
                        + " the lock the machine is judged on a snapshot that cannot see the"
                        + " winner, and the refusal becomes a ledger-level over-reversal");
    }

    // -----------------------------------------------------------------
    // Counters - in the tables, never inferred from answers
    // -----------------------------------------------------------------

    /** The whole entry row, rendered by PostgreSQL itself — the byte-identity capture. */
    private static String rowOf(Connection app, UUID entry) throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT e::text FROM ledger.journal_entry e WHERE id = ?")) {
            read.setObject(1, entry);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    private static List<String> lineRowsOf(Connection app, UUID entry) throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT l::text FROM ledger.journal_line l WHERE entry_id = ?"
                                + " ORDER BY seq")) {
            read.setObject(1, entry);
            try (ResultSet rows = read.executeQuery()) {
                List<String> renderings = new ArrayList<>();
                while (rows.next()) {
                    renderings.add(rows.getString(1));
                }
                return renderings;
            }
        }
    }

    private static long reversalEntriesFor(Connection app, UUID original) throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT count(*) FROM ledger.journal_entry"
                                + " WHERE reverses_entry_id = ?")) {
            read.setObject(1, original);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private static long reversalAuditCount(Connection app, String transferId)
            throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT count(*) FROM platform.audit_record"
                                + " WHERE operation = 'transfers.TransferReversed'"
                                + " AND target_id = ?")) {
            read.setString(1, transferId);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private static long historyRows(Connection app, String transferId, String toStatus)
            throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT count(*) FROM transfers.transfer_event"
                                + " WHERE transfer_id = ? AND to_status = ?")) {
            read.setObject(1, UUID.fromString(transferId));
            read.setString(2, toStatus);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private static long reversalEvents(Connection app, String transferId) throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT count(*) FROM platform.outbox_event"
                                + " WHERE event_type = 'transfers.TransferReversed'"
                                + " AND aggregate_id = ?")) {
            read.setObject(1, UUID.fromString(transferId));
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    // -----------------------------------------------------------------
    // HTTP
    // -----------------------------------------------------------------

    private static String transferBody(String source, String destination, String amount) {
        return "{\"sourceAccountId\":\"" + source + "\",\"destinationAccountId\":\""
                + destination + "\",\"amount\":\"" + amount + "\",\"currency\":\"USD\"}";
    }

    private static String reasonBody(String reason) {
        return "{\"reason\":\"" + reason + "\"}";
    }

    private HttpResponse<String> reverse(String token, String transferId, String body)
            throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(
                                        "http://localhost:" + port + "/v1/transfers/"
                                                + transferId + "/reversal"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
        return send(request);
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

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build();
        return send(request);
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

    private static String field(String body, String name) {
        Matcher matcher =
                Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("the body must carry %s: %s", name, body).isTrue();
        return matcher.group(1);
    }

    private static String tokenFrom(String body) {
        return field(body, "sessionToken");
    }

    /**
     * The correlation identifier differs per request by design, and {@code instance} is the
     * caller's own request path echoed back. Everything else must be byte-identical.
     */
    private static String normalized(String body) {
        return body.replaceAll("\"correlationId\":\"[^\"]*\"", "\"correlationId\":\"normalized\"")
                .replaceAll("\"instance\":\"[^\"]*\"", "\"instance\":\"normalized\"");
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

    private static String someLogin() {
        return "reverser." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
