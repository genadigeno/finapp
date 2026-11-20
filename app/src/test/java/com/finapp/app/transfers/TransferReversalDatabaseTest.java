package com.finapp.app.transfers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.finapp.transfers.Transfer;
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
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

        // P17-TSK-031 (ADR-0154): always four eyes - the operator proposes, a controller who did not propose approves, and
        // the approval performs the same reversal, one step later.
        String operator = operator(someLogin());
        String controllerLogin = someLogin();
        String controller = controller(controllerLogin);
        String reason = "the recipient disputed the movement; case CS-4411";
        HttpResponse<String> proposed = reverse(operator, transferId, reasonBody(reason));
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        assertThat(field(proposed.body(), "status")).isEqualTo("PROPOSED");
        assertThat(field(proposed.body(), "transferStatus")).as("a proposal moves nothing").isEqualTo("COMPLETED");
        try (Connection app = DatabaseRoles.application()) {
            assertThat(reversalEntriesFor(app, entryId)).as("nothing posted at the proposal").isZero();
        }
        String approval = "agreed: the dispute is upheld";
        HttpResponse<String> reversed = approve(controller, field(proposed.body(), "proposalId"), reasonBody(approval));
        assertThat(reversed.statusCode()).as(reversed.body()).isEqualTo(200);
        assertThat(field(reversed.body(), "status")).isEqualTo("APPROVED");
        assertThat(field(reversed.body(), "transferStatus")).isEqualTo("REVERSED");
        String view = get("/v1/transfers/" + transferId, sender).body();
        assertThat(field(view, "journalEntryId")).isEqualTo(entryId.toString());
        String reversalEntryId = field(view, "reversalEntryId");
        assertThat(reversalEntryId).isNotEqualTo(entryId.toString());
        assertThat(field(view, "reversedAt")).isNotBlank();
        // The operator's identity is the audit trail's fact, never the customer view's.
        assertThat(view).doesNotContain("reversedBy");

        try (Connection app = DatabaseRoles.application()) {
            // The original, byte-identical after the reversal (INV-REV-01).
            assertThat(rowOf(app, entryId))
                    .as("the original entry must be byte-identical after its reversal")
                    .isEqualTo(entryBefore);
            assertThat(lineRowsOf(app, entryId)).isEqualTo(linesBefore);

            // The reversal entry references the original - the chain walkable in both
            // directions (plan section 12), counted in the table.
            assertThat(reversalEntriesFor(app, entryId)).isEqualTo(1);

            // The trail: one record, the APPROVER's identity - who performed it - and the approval's reason verbatim
            // (requiresReason - the justification enters at the moment it is written); the proposal is its own record.
            assertThat(reversalAuditCount(app, transferId)).isEqualTo(1);
            try (PreparedStatement read =
                    app.prepareStatement(
                            "SELECT actor_id, reason FROM platform.audit_record"
                                    + " WHERE operation = 'transfers.TransferReversed'"
                                    + " AND target_id = ?")) {
                read.setString(1, transferId);
                try (ResultSet row = read.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString("actor_id")).isEqualTo(identityIdOf(app, controllerLogin));
                    assertThat(row.getString("reason")).isEqualTo(approval);
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

        // A second proposal on the reversed transfer is the one 409 (a lost answer's retry under its own key replays
        // instead - TransferReversalFourEyesDatabaseTest); nothing more is written.
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
    @DisplayName("ten concurrent proposals over HTTP leave one open proposal - one 201, nine 409s; then approvals racing"
            + " rejections decide it once - one 200, the rest 409 - and the tables hold exactly that one outcome")
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
        String controller = controller(someLogin());

        int racers = 10;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<HttpResponse<String>>> proposals = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                String reason = "concurrent reversal " + i;
                String racerKey = someKey();
                proposals.add(pool.submit(() -> {
                    start.await();
                    return postRetryingShed("/v1/transfers/" + transferId + "/reversal", operator, reasonBody(reason),
                            racerKey);
                }));
            }
            start.countDown();
            List<String> open = new ArrayList<>();
            int refusedCount = 0;
            for (Future<HttpResponse<String>> outcome : proposals) {
                HttpResponse<String> answer = outcome.get(60, TimeUnit.SECONDS);
                if (answer.statusCode() == 201) {
                    open.add(field(answer.body(), "proposalId"));
                } else if (answer.statusCode() == 409) {
                    refusedCount++;
                }
            }
            assertThat(open).as("exactly one racer proposes").hasSize(1);
            assertThat(refusedCount).as("every loser gets the open proposal's 409").isEqualTo(9);

            CountDownLatch decide = new CountDownLatch(1);
            List<Future<HttpResponse<String>>> decisions = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                boolean approving = i % 2 == 0;
                String racerKey = someKey();
                decisions.add(pool.submit(() -> {
                    decide.await();
                    return approving
                            ? postRetryingShed("/v1/operator/transfer-reversals/" + open.get(0) + "/approval", controller,
                                    reasonBody("approved in the race"), racerKey)
                            : postRetryingShed("/v1/operator/transfer-reversals/" + open.get(0) + "/rejection", controller,
                                    reasonBody("rejected in the race"), racerKey);
                }));
            }
            decide.countDown();
            int won = 0;
            int lost = 0;
            boolean approvalWon = false;
            for (Future<HttpResponse<String>> outcome : decisions) {
                HttpResponse<String> answer = outcome.get(60, TimeUnit.SECONDS);
                if (answer.statusCode() == 200) {
                    won++;
                    approvalWon = field(answer.body(), "status").equals("APPROVED");
                } else if (answer.statusCode() == 409) {
                    lost++;
                }
            }
            assertThat(won).as("exactly one decision").isEqualTo(1);
            assertThat(lost).as("every other decider meets the decided proposal's 409").isEqualTo(9);

            // Counted in the tables, never inferred from the answers (INV-CON-02's discipline): the one outcome, whole.
            try (Connection app = DatabaseRoles.application()) {
                assertThat(reversalEntriesFor(app, entryId)).isEqualTo(approvalWon ? 1 : 0);
                assertThat(historyRows(app, transferId, "REVERSED")).isEqualTo(approvalWon ? 1 : 0);
                assertThat(reversalAuditCount(app, transferId)).isEqualTo(approvalWon ? 1 : 0);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("four eyes for every writer (P17-TSK-031): one identity holding both roles is refused approving its own"
            + " proposal - 409, nothing posted - and V005's CHECK refuses the proposer's decision written by hand")
    void theProposerNeverDecides() throws Exception {
        String sender = verifiedCustomer(someLogin());
        String source = openAccount(sender);
        fund(source, 10_00);
        HttpResponse<String> created =
                transfer(sender, transferBody(source, openAccount(verifiedCustomer(someLogin())), "3.00"), someKey());
        String transferId = field(created.body(), "id");
        UUID entryId = UUID.fromString(field(created.body(), "journalEntryId"));
        String login = someLogin();
        String both = operator(login);
        grant(login, RoleName.FINANCIAL_CONTROLLER);

        HttpResponse<String> proposed = reverse(both, transferId, reasonBody("my own proposal"));
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        String proposal = field(proposed.body(), "proposalId");
        HttpResponse<String> mine = approve(both, proposal, reasonBody("and my own approval"));
        assertThat(mine.statusCode()).as(mine.body()).isEqualTo(409);
        assertThat(mine.body()).contains("transfers.ReversalSelfDecision");
        try (Connection app = DatabaseRoles.application()) {
            assertThat(reversalEntriesFor(app, entryId)).isZero();
            try (PreparedStatement forged = app.prepareStatement("UPDATE transfers.transfer_reversal_proposal"
                    + " SET status = 'APPROVED', decided_by = proposed_by, decided_by_type = 'EMPLOYEE',"
                    + " decided_at = proposed_at + interval '1 second', decision_reason = 'mine',"
                    + " decision_correlation_id = 'c' WHERE id = ?")) {
                forged.setObject(1, UUID.fromString(proposal));
                assertThatThrownBy(forged::executeUpdate).isInstanceOf(SQLException.class)
                        .hasMessageContaining("transfer_reversal_proposal_four_eyes");
            }
        }
    }

    @Test
    @DisplayName("keyed (P17-TSK-031): an approval whose answer was lost, retried under its key, replays the first answer and"
            + " the reversal posts once; another key meets the decided proposal's 409; a rejection moves nothing")
    void aLostApprovalReplaysAndARejectionMovesNothing() throws Exception {
        String sender = verifiedCustomer(someLogin());
        String source = openAccount(sender);
        fund(source, 10_00);
        HttpResponse<String> first =
                transfer(sender, transferBody(source, openAccount(verifiedCustomer(someLogin())), "1.00"), someKey());
        HttpResponse<String> second =
                transfer(sender, transferBody(source, openAccount(verifiedCustomer(someLogin())), "1.00"), someKey());
        String operator = operator(someLogin());
        String controller = controller(someLogin());

        String proposal = field(reverse(operator, field(first.body(), "id"), reasonBody("returned in error")).body(),
                "proposalId");
        String key = someKey();
        String path = "/v1/operator/transfer-reversals/" + proposal + "/approval";
        HttpResponse<String> approved = keyedPost(path, controller, reasonBody("agreed"), key);
        HttpResponse<String> replayed = keyedPost(path, controller, reasonBody("agreed"), key);
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(replayed.statusCode()).isEqualTo(200);
        assertThat(replayed.body()).isEqualTo(approved.body());
        try (Connection app = DatabaseRoles.application()) {
            assertThat(reversalEntriesFor(app, UUID.fromString(field(first.body(), "journalEntryId")))).isEqualTo(1);
        }
        HttpResponse<String> again = approve(controller, proposal, reasonBody("agreed again"));
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(again.body()).contains("transfers.ReversalAlreadyDecided");

        String other = field(reverse(operator, field(second.body(), "id"), reasonBody("returned in error")).body(),
                "proposalId");
        HttpResponse<String> rejected = reject(controller, other, reasonBody("the customer confirmed it"));
        assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(200);
        assertThat(field(rejected.body(), "status")).isEqualTo("REJECTED");
        assertThat(field(rejected.body(), "transferStatus")).isEqualTo("COMPLETED");
        try (Connection app = DatabaseRoles.application()) {
            assertThat(reversalEntriesFor(app, UUID.fromString(field(second.body(), "journalEntryId")))).isZero();
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

        // The transfer's own customer holds no TRANSFER_REVERSE: refused, audited by the
        // interceptor, and NOTHING of the reversal written (INV-AUD-03) - the acceptance
        // test's operator success is the positive control, so this refusal is not blanket.
        // The reversal is MULTI_FACTOR since P17-TSK-102 and the step-up is judged before the
        // permission, so the customer's password session meets the step-up's refusal first.
        HttpResponse<String> forbidden = reverse(sender, transferId, reasonBody("mine"));
        assertThat(forbidden.statusCode()).isEqualTo(403);
        assertThat(forbidden.body()).contains("identity.AssuranceRequired");

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
    // The clock
    // -----------------------------------------------------------------

    /**
     * The reversal is served by whichever instance the operator reaches, and its clock may read
     * behind the one that executed the transfer. {@code Transfer.reverse} mints the stamp and
     * {@code markReversed} persists it verbatim, so the clamp lives in the aggregate; this is the
     * command composed on such a clock, landing where an unclamped stamp would die on V002's
     * CHECK - and take the reversal's ledger posting down with it.
     */
    @Test
    @DisplayName("a clock behind initiation cannot fail a legal reversal: reversed_at lands at"
            + " initiated_at (the P1-TSK-031 drift; ADR-0014)")
    void aClockBehindInitiationCannotFailALegalReversal() throws Exception {
        String sender = verifiedCustomer(someLogin());
        String source = openAccount(sender);
        String destination = openAccount(verifiedCustomer(someLogin()));
        fund(source, 10_00);
        TransferId clamped = completedTransfer(sender, source, destination);
        TransferId own = completedTransfer(sender, source, destination);

        Instant born = stampsOf(clamped).initiatedAt();
        assertThat(reverseOn(Clock.fixed(born.minusMillis(250), ZoneOffset.UTC), clamped))
                .isPresent();
        assertThat(stampsOf(clamped).reversedAt()).isEqualTo(born);

        // A floor, not a pin: a clock past initiation stamps its own read.
        Instant later = stampsOf(own).initiatedAt().plusSeconds(5);
        assertThat(reverseOn(Clock.fixed(later, ZoneOffset.UTC), own)).isPresent();
        assertThat(stampsOf(own).reversedAt()).isEqualTo(later);
    }

    @Test
    @DisplayName("an illegal reversal under a behind clock is still the machine's refusal, with"
            + " nothing written - never V002's CHECK")
    void anIllegalReversalUnderABehindClockIsStillTheMachinesRefusal() throws Exception {
        String sender = verifiedCustomer(someLogin());
        String source = openAccount(sender);
        String destination = openAccount(verifiedCustomer(someLogin()));
        fund(source, 10_00);
        HttpResponse<String> refused =
                transfer(sender, transferBody(source, destination, "100.00"), someKey());
        assertThat(field(refused.body(), "status")).isEqualTo("FAILED");
        TransferId failed = TransferId.of(UUID.fromString(field(refused.body(), "id")));
        TransferId alreadyReversed = completedTransfer(sender, source, destination);
        assertThat(reverseOn(CLOCK, alreadyReversed)).isPresent();
        Stamps reversed = stampsOf(alreadyReversed);

        // The machine judges the locked row before any ledger work or stamp: FAILED moved no
        // money, and COMPLETED -> REVERSED happens at most once (INV-LIFE-04).
        for (TransferId refusedTransfer : List.of(failed, alreadyReversed)) {
            Clock behind =
                    Clock.fixed(
                            stampsOf(refusedTransfer).initiatedAt().minusSeconds(1),
                            ZoneOffset.UTC);
            assertThatThrownBy(() -> reverseOn(behind, refusedTransfer))
                    .isInstanceOf(IllegalTransferTransitionException.class);
        }
        assertThat(stampsOf(failed).reversedAt()).isNull();
        assertThat(stampsOf(alreadyReversed)).isEqualTo(reversed);
        try (Connection app = DatabaseRoles.application()) {
            assertThat(historyRows(app, failed.value().toString(), "REVERSED")).isZero();
            assertThat(historyRows(app, alreadyReversed.value().toString(), "REVERSED"))
                    .isEqualTo(1);
        }
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
     * constraint and the per-request resolution are all on the path), and returns its stepped-up token.
     */
    private String operator(String login) throws Exception {
        return staff(login, RoleName.LEDGER_OPERATOR);
    }

    /** A {@code FINANCIAL_CONTROLLER}, stepped up: the reversal's second person (P17-TSK-031). */
    private String controller(String login) throws Exception {
        return staff(login, RoleName.FINANCIAL_CONTROLLER);
    }

    /** A second role for an identity already registered (P17-TSK-031: the grants are disjoint, the holders need not be). */
    private void grant(String login, RoleName role) throws Exception {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow =
                        CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            app.setAutoCommit(false);
            IdentityId identity = IdentityId.of(UUID.fromString(identityIdOf(app, login)));
            authorization.assign(app, identity, role, identity, "test fixture");
            app.commit();
        }
    }

    private String staff(String login, RoleName role) throws Exception {
        assertThat(register(login).statusCode()).isEqualTo(201);
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)))) {
            app.setAutoCommit(false);
            IdentityId identity = IdentityId.of(UUID.fromString(identityIdOf(app, login)));
            authorization.assign(app, identity, role, identity, "test fixture");
            app.commit();
        }
        // The reversal is an operator act, MULTI_FACTOR (P17-TSK-102): the operator steps up.
        return com.finapp.app.session.OperatorStepUp.elevated(tokenFrom(authenticate(login).body()),
                (path, body, bearer, key) -> post(path, body, bearer, key != null));
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
            // FEE_REVENUE, not SETTLEMENT_CLEARING (the P8-TSK-006 move): clearing is a reconciled
            // position whose every line answers to an expectation (INV-REC-06), and a funding
            // entry is no operation a backfill could adopt - it stood unexplained in the shared
            // container. This fixture only ever wanted an operational account facing the wallet.
            LedgerAccount operational =
                    ledgerAccounts
                            .findOperational(app, AccountPurpose.FEE_REVENUE, USD)
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
                                                    operational.id(),
                                                    Direction.DEBIT,
                                                    Money.ofMinorUnits(minorUnits, USD)),
                                            new JournalLine(
                                                    wallet,
                                                    Direction.CREDIT,
                                                    Money.ofMinorUnits(minorUnits, USD)))));
            app.commit();
        }
    }

    /** A {@code COMPLETED} transfer of 2.00, executed over HTTP on the application's clock. */
    private TransferId completedTransfer(String sender, String source, String destination)
            throws Exception {
        HttpResponse<String> created =
                transfer(sender, transferBody(source, destination, "2.00"), someKey());
        assertThat(field(created.body(), "status")).isEqualTo("COMPLETED");
        return TransferId.of(UUID.fromString(field(created.body(), "id")));
    }

    /**
     * The command composed on {@code clock} and run as one operator's committed flow - an
     * instance whose one injected Clock reads what {@code clock} reads.
     */
    private static Optional<Transfer> reverseOn(Clock clock, TransferId transfer)
            throws SQLException {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enter(operatorActor());
                CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)))) {
            app.setAutoCommit(false);
            Optional<Transfer> reversed =
                    composedReversal(clock).reverse(app, transfer, "a clock-skew probe");
            app.commit();
            return reversed;
        }
    }

    /**
     * The command composed as the interleaving needs it - directly, on the caller's
     * connection, the {@code TransferExecutionDatabaseTest} stance. The composition root's
     * copy is exercised by every HTTP test in this suite.
     */
    private static TransferReversal composedReversal() {
        return composedReversal(CLOCK);
    }

    /** {@link #composedReversal()} on {@code clock}, the one Clock an instance injects. */
    private static TransferReversal composedReversal(Clock clock) {
        return new TransferReversal(
                new JdbcTransferStore(),
                new ReversalService(
                        new IdempotentExecutor(
                                new JdbcIdempotencyRecordStore(),
                                clock,
                                Duration.ofDays(1),
                                Duration.ofMinutes(5)),
                        new JdbcJournalEntryStore(IDS),
                        new JdbcAuditWriter(),
                        new JdbcOutboxWriter(),
                        new JdbcBalanceProjection(),
                        IDS,
                        clock,
                        PostingObserver.NONE),
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(),
                IDS,
                clock);
    }

    /** The transfer's two instants as stored, at the column's own microsecond resolution. */
    private record Stamps(Instant initiatedAt, Instant reversedAt) {}

    private static Stamps stampsOf(TransferId transfer) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT initiated_at, reversed_at FROM transfers.transfer"
                                        + " WHERE id = ?")) {
            read.setObject(1, transfer.value());
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                Timestamp reversedAt = row.getTimestamp(2);
                return new Stamps(
                        row.getTimestamp(1).toInstant(),
                        reversedAt == null ? null : reversedAt.toInstant());
            }
        }
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

    /** Proposes the transfer's reversal (P17-TSK-031): keyed, as every operator money act is. */
    private HttpResponse<String> reverse(String token, String transferId, String body)
            throws Exception {
        return keyedPost("/v1/transfers/" + transferId + "/reversal", token, body);
    }

    /** A controller's approval of a reversal proposal (P17-TSK-031): the reversal itself. */
    private HttpResponse<String> approve(String token, String proposalId, String body) throws Exception {
        return keyedPost("/v1/operator/transfer-reversals/" + proposalId + "/approval", token, body);
    }

    /** A controller's rejection of a reversal proposal (P17-TSK-031): nothing moves. */
    private HttpResponse<String> reject(String token, String proposalId, String body) throws Exception {
        return keyedPost("/v1/operator/transfer-reversals/" + proposalId + "/rejection", token, body);
    }

    private HttpResponse<String> keyedPost(String path, String token, String body) throws Exception {
        return keyedPost(path, token, body, someKey());
    }

    /**
     * {@link #keyedPost}, retried with the same key while the platform sheds it ({@code 503 api.Overloaded}: "nothing was
     * done; retry the same request") - ten racers on one test machine meet the admission limit, and a shed request is no
     * outcome (`P17-TSK-028`'s discipline).
     */
    private HttpResponse<String> postRetryingShed(String path, String token, String body, String key) throws Exception {
        HttpResponse<String> answer = keyedPost(path, token, body, key);
        for (int attempt = 0; attempt < 50 && answer.statusCode() == 503; attempt++) {
            Thread.sleep(100L + java.util.concurrent.ThreadLocalRandom.current().nextInt(200));
            answer = keyedPost(path, token, body, key);
        }
        return answer;
    }

    private HttpResponse<String> keyedPost(String path, String token, String body, String key) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + token)
                        .header(IdempotencyKeyHeader.NAME, key)
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
