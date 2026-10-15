package com.finapp.app.lending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingService;
import com.finapp.lending.LendingCapitalHeadroom;
import com.finapp.lending.LendingCapitalProof;
import com.finapp.lending.LoanAccounts;
import com.finapp.lending.LoanId;
import com.finapp.lending.LoanLedgerAccounts;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.reconciliation.BreakCause;
import com.finapp.reconciliation.BreakRegister;
import com.finapp.reconciliation.BreakType;
import com.finapp.reconciliation.ExpectationDirection;
import com.finapp.reconciliation.ExpectationKind;
import com.finapp.reconciliation.ExpectationRegister;
import com.finapp.reconciliation.KeyKind;
import com.finapp.reconciliation.Matching;
import com.finapp.reconciliation.NewExpectation;
import com.finapp.reconciliation.ReconciliationSweep;
import com.finapp.reconciliation.ResolutionKind;
import com.finapp.reconciliation.ResolutionMachine;
import com.finapp.reconciliation.ResolutionReasonCode;
import com.finapp.reconciliation.RuleSets;
import com.finapp.reconciliation.RunReplays;
import com.finapp.settlement.BatchAcceptance;
import com.finapp.settlement.DeliveryChannel;
import com.finapp.settlement.FileParsing;
import com.finapp.settlement.FileReception;
import com.finapp.settlement.SettlementAuditAction;
import com.finapp.settlement.SettlementFileStore;
import com.finapp.settlement.TransactionRunner;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Lending capital over the REAL composition (`P11-TSK-004`; ADR-0096 sections 4-6, PHASE_11_PLAN.md sections 7 and
 * 12.9; {@code INV-LND-13}, {@code INV-SET-06}, {@code INV-REC-06}, {@code INV-LED-01}, {@code INV-AUD-04},
 * {@code INV-IDEM-01}): two treasury officers contribute over HTTP; the approval opens an expectation and creates NO
 * capital; the settlement bank's statement carrying the transfer is accepted and bank recognition - the one poster of
 * {@code CASH_AT_BANK} - posts rule 0, {@code DR CASH_AT_BANK / CR LENDING_CAPITAL}, matching the contribution in the
 * same transaction; the headroom reads the ledger under the capital row lock; and the capital proof holds at rest and
 * fails on a foreign posting.
 *
 * <p>Chain discipline: the suite runs in a database of its own ({@code own-container}, `X-TSK-016`), because the
 * bank's EUR statement chain must start at sequence 1 and every step here extends it in order - the methods are
 * ordered, and the chain's position is this class's state.
 */
@Tag("database")
@Tag("own-container") // its own JVM and database: ownContainerDatabaseTest (X-TSK-016)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("lending capital: contributed, recognised from the statement, headroom under the lock, proven (P11-TSK-004)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class LendingCapitalDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final String BANK_SOURCE = "simulated-bank.statement";
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final String CONTRIBUTIONS = "/v1/operator/lending/capital-contributions";
    private static final String CAPITAL = "/v1/operator/lending/capital";

    /** The EUR chain's place - the next sequence and the last closing (minor units). */
    private static long nextSequence = 1;
    private static long closingMinor = 0;

    private static final ExecutorService POOL = Executors.newFixedThreadPool(12);

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private FileReception<Connection> reception;
    @Autowired private FileParsing parsing;
    @Autowired private BatchAcceptance acceptance;
    @Autowired private Matching matching;
    @Autowired private RunReplays runReplays;
    @Autowired private ReconciliationSweep reconciliationSweep;
    @Autowired private TransactionRunner settlementTransactionRunner;
    @Autowired private SettlementFileStore<Connection> settlementFileStore;
    @Autowired private ExpectationRegister expectationRegister;
    @Autowired private RuleSets ruleSets;
    @Autowired private LendingCapitalHeadroom headroom;
    @Autowired private LendingCapitalProof proof;
    @Autowired private LendingCapitalMetrics metrics;
    @Autowired private PostingService postingService;
    @Autowired private LedgerAccountStore<Connection> ledgerAccountStore;
    @Autowired private BreakRegister breakRegister;
    @Autowired private ResolutionMachine resolutionMachine;

    @AfterAll
    static void stopThePool() {
        POOL.shutdownNow();
    }

    // ----------------------------------------------------------------- recognition

    @Test
    @Order(1)
    @DisplayName("capital is recognised only from a statement line: the approval opens an expectation and posts"
            + " nothing; the bank's statement posts DR CASH_AT_BANK / CR LENDING_CAPITAL and matches the contribution in"
            + " the same acceptance; the proof holds, the run replays, the report reads RECOGNISED")
    void capitalIsRecognisedOnlyFromAStatementLine() throws Exception {
        String proposer = sessionWith(RoleName.LENDING_TREASURY_OFFICER);
        String approver = sessionWith(RoleName.LENDING_TREASURY_OFFICER);
        String officer = sessionWith(RoleName.LENDING_OFFICER);
        String reference = reference();
        Money baseCapital = headroomNow().capital();

        String proposalKey = key();
        HttpResponse<String> proposed = post(CONTRIBUTIONS, proposal("EUR", 1_000_00, reference, "seed capital"),
                proposer, proposalKey);
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        assertThat(field(proposed.body(), "status")).isEqualTo("PROPOSED");
        String contribution = field(proposed.body(), "id");
        HttpResponse<String> retried = post(CONTRIBUTIONS, proposal("EUR", 1_000_00, reference, "seed capital"),
                proposer, proposalKey);
        assertThat(retried.body())
                .as("INV-IDEM-01: a lost response's retry under the same key replays the receipt - one proposal, never"
                        + " a ProposalPending refusal of its own echo")
                .isEqualTo(proposed.body());
        assertThat(count("SELECT count(*) FROM lending.capital_contribution WHERE reference = ?", reference)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.expectation WHERE operation_ref = ?", contribution))
                .as("a proposal opens nothing").isZero();

        String approvalKey = key();
        HttpResponse<String> approved = post(CONTRIBUTIONS + "/" + contribution + "/approval",
                decision("board minute 11"), approver, approvalKey);
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(field(approved.body(), "status")).isEqualTo("APPROVED");
        assertThat(field(approved.body(), "expectationRef")).isEqualTo(contribution);
        assertThat(post(CONTRIBUTIONS + "/" + contribution + "/approval", decision("board minute 11"), approver,
                        approvalKey).body())
                .as("the same key replays the receipt").isEqualTo(approved.body());
        assertThat(one("SELECT kind || ':' || status || ':' || direction || ':' || amount_minor || ':'"
                        + " || position_purpose || ':' || (journal_entry_id IS NULL)"
                        + " FROM reconciliation.expectation WHERE operation_ref = ?", contribution))
                .isEqualTo("LENDING_CAPITAL_CONTRIBUTION:OPEN:INBOUND:100000:LENDING_CAPITAL:true");
        assertThat(headroomNow().capital())
                .as("an approval is not capital: the ledger holds nothing new").isEqualTo(baseCapital);

        UUID statement = acceptedStatement(capitalLine(1_000_00, reference));
        UUID entry = (UUID) one("SELECT journal_entry_id FROM settlement.batch WHERE id = ?", statement);
        assertThat(entryLines(entry))
                .as("rule 0, inside the statement's one recognition entry")
                .containsExactlyInAnyOrder("CASH_AT_BANK:DEBIT:100000", "LENDING_CAPITAL:CREDIT:100000");
        assertThat(one("SELECT e.status || ':' || i.status || ':' || d.origin || ':' || d.verdict"
                        + " FROM reconciliation.capital_recognition r"
                        + " JOIN reconciliation.expectation e ON e.id = r.expectation_id"
                        + " JOIN reconciliation.external_item i ON i.id = r.external_item_id"
                        + " JOIN reconciliation.match_decision d ON d.id = r.decision_id"
                        + " WHERE e.operation_ref = ? AND r.journal_entry_id = ?", contribution, entry))
                .isEqualTo("SETTLED:MATCHED:RECOGNITION:CAPITAL_RECOGNISED");
        assertThat(count("SELECT count(*) FROM reconciliation.suspense_item s JOIN reconciliation.external_item i"
                        + " ON i.id = s.external_item_id JOIN reconciliation.reconciliation_batch r ON r.id = i.run_id"
                        + " WHERE r.batch_id = ?", statement))
                .as("the contribution explains the cash: no suspense").isZero();
        assertThat(headroomNow().capital()).isEqualTo(baseCapital.plus(eur(1_000_00)));
        assertThat(proofNow().holds()).as("the capital proof holds at rest").isTrue();
        assertThat(metrics.readNow().failing()).isZero();

        matchUntilQuiet();
        UUID run = (UUID) one("SELECT id FROM reconciliation.reconciliation_batch WHERE batch_id = ?", statement);
        assertThat(one("SELECT status FROM reconciliation.reconciliation_batch WHERE id = ?", run)).isEqualTo("COMPLETED");
        RunReplays.Replay replay;
        try (CorrelationContext.Scope scope = CorrelationContext.enter(flow())) {
            replay = runReplays.replay(run, Actor.SYSTEM, CorrelationContext.current().orElseThrow().correlationId());
        }
        assertThat(replay.divergences()).as("the recognition replays exactly from its snapshot").isZero();

        HttpResponse<String> report = get(CAPITAL, officer);
        assertThat(report.statusCode()).as(report.body()).isEqualTo(200);
        assertThat(report.body()).contains("\"recognition\":\"RECOGNISED\"").contains(contribution);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'lending.ReportRead'"))
                .as("the report is audited per serving").isPositive();
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE target_id = ? AND operation IN"
                        + " ('lending.CapitalContributionProposed', 'lending.CapitalContributionApproved')"
                        + " AND reason IS NOT NULL", contribution))
                .isEqualTo(2);
    }

    // ----------------------------------------------------------------- four eyes and races

    @Test
    @Order(2)
    @DisplayName("ten approvers of one contribution: one approval, nine 409 lending.ProposalNotPending - one decision"
            + " row, one expectation")
    void tenApproversOneApproval() throws Exception {
        String proposer = sessionWith(RoleName.LENDING_TREASURY_OFFICER);
        String contribution = field(post(CONTRIBUTIONS, proposal("EUR", 500_00, reference(), "second tranche"),
                proposer, key()).body(), "id");
        List<String> approvers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            approvers.add(sessionWith(RoleName.LENDING_TREASURY_OFFICER));
        }
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> racing = new ArrayList<>();
        for (String approver : approvers) {
            racing.add(POOL.submit(() -> {
                start.await();
                return post(CONTRIBUTIONS + "/" + contribution + "/approval", decision("approved"), approver, key());
            }));
        }
        start.countDown();
        int approvedCount = 0;
        int notPending = 0;
        for (Future<HttpResponse<String>> answer : racing) {
            HttpResponse<String> response = answer.get(60, TimeUnit.SECONDS);
            if (response.statusCode() == 200) {
                approvedCount++;
            } else {
                assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
                assertThat(response.body()).contains("lending.ProposalNotPending");
                notPending++;
            }
        }
        assertThat(approvedCount).isEqualTo(1);
        assertThat(notPending).isEqualTo(9);
        assertThat(count("SELECT count(*) FROM lending.capital_contribution_event WHERE contribution_id = ?::uuid"
                + " AND to_status <> 'PROPOSED'", contribution)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.expectation WHERE operation_ref = ?", contribution))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE target_id = ?"
                + " AND operation = 'lending.CapitalContributionApproved'", contribution))
                .as("losers record nothing").isEqualTo(1);
    }

    @Test
    @Order(3)
    @DisplayName("self-approval refused at both ranks - 403 at the door, the four-eyes CHECK and the pinned proposer"
            + " beneath for every writer; the proposer may withdraw; a caller without the permission is refused")
    void selfApprovalRefusedAtBothRanks() throws Exception {
        String proposer = sessionWith(RoleName.LENDING_TREASURY_OFFICER);
        String officer = sessionWith(RoleName.LENDING_OFFICER);
        HttpResponse<String> forbidden = post(CONTRIBUTIONS, proposal("GBP", 100_00, reference(), "no permit"),
                officer, key());
        assertThat(forbidden.statusCode()).as("LENDING_OFFICER lacks LENDING_CAPITAL_ADMINISTER").isEqualTo(403);
        assertThat(get(CAPITAL, proposer).statusCode())
                .as("LENDING_TREASURY_OFFICER lacks LENDING_INVESTIGATE").isEqualTo(403);

        String contribution = field(post(CONTRIBUTIONS, proposal("GBP", 100_00, reference(), "gbp capital"),
                proposer, key()).body(), "id");
        HttpResponse<String> self = post(CONTRIBUTIONS + "/" + contribution + "/approval", decision("mine"), proposer,
                key());
        assertThat(self.statusCode()).as(self.body()).isEqualTo(403);
        assertThat(self.body()).contains("lending.SelfApprovalRefused");

        String proposedBy = (String) one("SELECT proposed_by FROM lending.capital_contribution WHERE id = ?::uuid",
                contribution);
        try (Connection app = DatabaseRoles.application()) {
            assertThatExceptionOfType(SQLException.class)
                    .as("the four-eyes CHECK, for a writer that bypassed the domain")
                    .isThrownBy(() -> execute(app,
                            "INSERT INTO lending.capital_contribution_event (id, contribution_id, from_status, to_status,"
                                    + " actor, actor_type, proposed_by, reason, expectation_ref, occurred_at,"
                                    + " correlation_id) VALUES (?, ?::uuid, 'PROPOSED', 'APPROVED', ?, 'EMPLOYEE', ?,"
                                    + " 'raw', 'forged', now(), 'raw')",
                            IDS.next(), contribution, proposedBy, proposedBy))
                    .matches(failure -> "23514".equals(failure.getSQLState()));
            assertThatExceptionOfType(SQLException.class)
                    .as("a forged proposer copy fails the pinned composite key")
                    .isThrownBy(() -> execute(app,
                            "INSERT INTO lending.capital_contribution_event (id, contribution_id, from_status, to_status,"
                                    + " actor, actor_type, proposed_by, reason, expectation_ref, occurred_at,"
                                    + " correlation_id) VALUES (?, ?::uuid, 'PROPOSED', 'APPROVED', ?, 'EMPLOYEE',"
                                    + " 'someone-else', 'raw', 'forged', now(), 'raw')",
                            IDS.next(), contribution, proposedBy))
                    .matches(failure -> "23503".equals(failure.getSQLState()));
            assertThatExceptionOfType(SQLException.class)
                    .as("no role but the owner edits a contribution")
                    .isThrownBy(() -> execute(app,
                            "UPDATE lending.capital_contribution SET amount_minor = 1 WHERE id = ?::uuid", contribution));
        }
        HttpResponse<String> withdrawn = post(CONTRIBUTIONS + "/" + contribution + "/rejection",
                decision("withdrawn"), proposer, key());
        assertThat(withdrawn.statusCode()).as(withdrawn.body()).isEqualTo(200);
        assertThat(field(withdrawn.body(), "status")).isEqualTo("REJECTED");
        assertThat(count("SELECT count(*) FROM reconciliation.expectation WHERE operation_ref = ?", contribution))
                .as("a rejection opens nothing").isZero();
    }

    // ----------------------------------------------------------------- the headroom reads the ledger

    @Test
    @Order(4)
    @DisplayName("headroom reads the LENDING_CAPITAL ledger balance, never a contribution's status: an approved"
            + " contribution whose money has not arrived counts for nothing, and reads AWAITING_FUNDS")
    void headroomReadsTheLedgerNotTheContributionStatus() throws Exception {
        String officer = sessionWith(RoleName.LENDING_OFFICER);
        LendingCapitalHeadroom.Headroom before = headroomNow();
        String pending = (String) one("SELECT c.id::text FROM lending.capital_contribution c"
                + " JOIN lending.capital_contribution_event d ON d.contribution_id = c.id AND d.to_status = 'APPROVED'"
                + " WHERE c.amount_minor = 50000 AND c.currency = 'EUR'");
        assertThat(pending).as("order 2's approved, unfunded contribution").isNotNull();
        assertThat(before.capital()).isEqualTo(eur(1_000_00));
        assertThat(before.headroom()).isEqualTo(eur(1_000_00));
        assertThat(get(CAPITAL, officer).body())
                .contains("\"id\":\"" + pending + "\"")
                .contains("\"recognition\":\"AWAITING_FUNDS\"");
    }

    @Test
    @Order(5)
    @DisplayName("a duplicate line recognises once: the same reference twice in one statement and again in the next"
            + " - one recognition, the copies parked in suspense as cash nobody's pattern explains")
    void aDuplicateLineRecognisesOnce() throws Exception {
        String reference = (String) one("SELECT c.reference FROM lending.capital_contribution c"
                + " JOIN lending.capital_contribution_event d ON d.contribution_id = c.id AND d.to_status = 'APPROVED'"
                + " WHERE c.amount_minor = 50000 AND c.currency = 'EUR'");
        UUID first = acceptedStatement(capitalLine(500_00, reference), capitalLine(500_00, reference));
        UUID second = acceptedStatement(capitalLine(500_00, reference));
        assertThat(count("SELECT count(*) FROM reconciliation.capital_recognition r JOIN reconciliation.expectation e"
                + " ON e.id = r.expectation_id WHERE e.operation_ref = (SELECT id::text FROM"
                + " lending.capital_contribution WHERE reference = ?)", reference)).isEqualTo(1);
        assertThat(headroomNow().capital()).as("capital counted once").isEqualTo(eur(1_500_00));
        assertThat(suspenseOf(first)).as("the second copy in the same statement parks").isEqualTo(1);
        assertThat(suspenseOf(second)).as("a later copy parks").isEqualTo(1);
        assertThat(proofNow().holds()).isTrue();
    }

    @Test
    @Order(6)
    @DisplayName("an unmatched contribution yields no capital: a wrong amount, a rejected contribution's reference and"
            + " a lapsed contribution's line all park; the ageing sweep never ages a contribution")
    void anUnmatchedContributionYieldsNoCapital() throws Exception {
        String proposer = sessionWith(RoleName.LENDING_TREASURY_OFFICER);
        String approver = sessionWith(RoleName.LENDING_TREASURY_OFFICER);
        String wrongAmountRef = reference();
        String approved = field(post(CONTRIBUTIONS, proposal("EUR", 300_00, wrongAmountRef, "third"), proposer, key())
                .body(), "id");
        assertThat(post(CONTRIBUTIONS + "/" + approved + "/approval", decision("ok"), approver, key()).statusCode())
                .isEqualTo(200);
        String rejectedRef = (String) one("SELECT reference FROM lending.capital_contribution WHERE currency = 'GBP'"
                + " ORDER BY proposed_at DESC LIMIT 1");
        String lapsedRef = reference();
        openLapsedExpectation(lapsedRef, 70_00);

        UUID statement = acceptedStatement(
                capitalLine(299_99, wrongAmountRef), capitalLine(100_00, rejectedRef), capitalLine(70_00, lapsedRef));
        assertThat(entryLines((UUID) one("SELECT journal_entry_id FROM settlement.batch WHERE id = ?", statement)))
                .noneMatch(line -> line.startsWith("LENDING_CAPITAL"));
        assertThat(suspenseOf(statement)).isEqualTo(3);
        assertThat(headroomNow().capital()).isEqualTo(eur(1_500_00));
        assertThat(one("SELECT status FROM reconciliation.expectation WHERE operation_ref = ?", approved))
                .as("the contribution waits for its exact transfer").isEqualTo("OPEN");

        reconciliationSweep.sweep();
        assertThat(count("SELECT count(*) FROM reconciliation.expectation WHERE kind = 'LENDING_CAPITAL_CONTRIBUTION'"
                + " AND overdue_since IS NOT NULL")).as("never aged").isZero();
        assertThat(count("SELECT count(*) FROM reconciliation.break b JOIN reconciliation.expectation e"
                + " ON e.id = b.expectation_id WHERE e.kind = 'LENDING_CAPITAL_CONTRIBUTION'"))
                .as("no break a person could resolve into capital").isZero();
        assertThat(proofNow().holds()).isTrue();
    }

    // ----------------------------------------------------------------- the capital lock

    @Test
    @Order(7)
    @DisplayName("headroom is derived under the capital lock: bank recognition posting a contribution waits on a"
            + " transaction holding L6 - a one-way wait on the ledger - and lands once it commits")
    void headroomIsDerivedUnderTheCapitalLock() throws Exception {
        String proposer = sessionWith(RoleName.LENDING_TREASURY_OFFICER);
        String approver = sessionWith(RoleName.LENDING_TREASURY_OFFICER);
        String reference = reference();
        String contribution = field(post(CONTRIBUTIONS, proposal("EUR", 200_00, reference, "fourth"), proposer, key())
                .body(), "id");
        assertThat(post(CONTRIBUTIONS + "/" + contribution + "/approval", decision("ok"), approver, key()).statusCode())
                .isEqualTo(200);
        UUID file = pulledStatement(capitalLine(200_00, reference));
        parsing.sweep();

        try (Connection holder = DatabaseRoles.application()) {
            holder.setAutoCommit(false);
            LendingCapitalHeadroom.Headroom held = headroom.underLock(holder, EUR);
            assertThat(held.capital()).isEqualTo(eur(1_500_00));
            int holderPid = pid(holder);
            Future<BatchAcceptance.SweepResult> recognising = POOL.submit(() -> acceptance.sweep());
            String waiting = awaitWaiterBehind(holderPid);
            assertThat(waiting)
                    .as("the recognition waits on the ledger - its capital line meeting L6 - never the reverse")
                    .contains("ledger.");
            assertThat(recognising.isDone()).isFalse();
            assertThat(isWaiting(holderPid)).as("the holder waits on nothing the recognition holds").isFalse();
            holder.commit();
            assertThat(recognising.get(60, TimeUnit.SECONDS).accepted()).isEqualTo(1);
        }
        assertThat(one("SELECT status FROM settlement.file WHERE id = ?", file)).isEqualTo("ACCEPTED");
        closingMinor += 200_00;
        assertThat(headroomNow().capital()).isEqualTo(eur(1_700_00));
    }

    @Test
    @Order(8)
    @DisplayName("two headroom consumers never over-deploy: racing to reserve the last capital through the read, one"
            + " is admitted and the other, serialised on L6, finds nothing left - headroom never below zero")
    void twoHeadroomConsumersNeverOverDeploy() throws Exception {
        Money last = headroomNow().headroom();
        assertThat(last).isEqualTo(eur(1_700_00));
        LoanLedgerAccounts loan;
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            loan = new LoanAccounts(ledgerAccountStore, IDS, CLOCK).openFor(app, LoanId.next(IDS), EUR);
            app.commit();
        }
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch bothRead = new CountDownLatch(2);
        List<Future<Boolean>> consumers = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            consumers.add(POOL.submit(() -> reserve(loan, last, start, bothRead)));
        }
        start.countDown();
        int admitted = 0;
        for (Future<Boolean> consumer : consumers) {
            if (consumer.get(60, TimeUnit.SECONDS)) {
                admitted++;
            }
        }
        assertThat(admitted).as("exactly one consumer takes the last capital").isEqualTo(1);
        LendingCapitalHeadroom.Headroom after = headroomNow();
        assertThat(after.outstanding()).isEqualTo(last);
        assertThat(after.headroom().minorUnits()).as("INV-LND-13: never over-deployed").isZero();
        assertThat(proofNow().holds()).isTrue();
    }

    // ----------------------------------------------------------------- the proof

    @Test
    @Order(9)
    @DisplayName("the proof finds a foreign posting: a line on LENDING_CAPITAL no recognition explains fails the"
            + " currency; the committed books stay proven")
    void theProofFindsAForeignPosting() throws Exception {
        ChartOfAccounts<Connection> chart = new ChartOfAccounts<>(ledgerAccountStore);
        try (CorrelationContext.Scope scope = CorrelationContext.enter(flow());
                SecurityContext.Scope system = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try {
                postingService.post(app, new PostingCommand(
                        "p11t4-foreign:" + UUID.randomUUID(), LocalDate.now(ZoneOffset.UTC),
                        LocalDate.now(ZoneOffset.UTC), "a-foreign-poster",
                        List.of(new JournalLine(chart.resolve(app, AccountPurpose.PROCESSING_COSTS, EUR).id(),
                                        Direction.DEBIT, eur(1_00)),
                                new JournalLine(headroom.capitalAccount(app, EUR).id(), Direction.CREDIT, eur(1_00)))));
                LendingCapitalProof.CurrencyVerdict verdict = proof.prove(app, EUR);
                assertThat(verdict.complete()).as("a capital line no recognition explains").isFalse();
                assertThat(verdict.unexplainedEntries()).isEqualTo(1);
                assertThat(verdict.holds()).isFalse();
            } finally {
                app.rollback();
            }
        }
        assertThat(proofNow().holds()).as("the rolled-back posting leaves the books proven").isTrue();
    }

    @Test
    @Order(10)
    @DisplayName("the every-writer ranks: a contribution never aged, never allocated but by a recognition, its"
            + " recognition never edited")
    void theEveryWriterRanks() throws Exception {
        UUID recognised = (UUID) one("SELECT expectation_id FROM reconciliation.capital_recognition LIMIT 1");
        try (Connection app = DatabaseRoles.application()) {
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> execute(app,
                            "UPDATE reconciliation.expectation SET overdue_since = now() WHERE id = ?", recognised))
                    .matches(failure -> "23514".equals(failure.getSQLState()));
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> execute(app,
                            "UPDATE reconciliation.capital_recognition SET amount_minor = 1 WHERE expectation_id = ?",
                            recognised));
        }
        // A run's decision on another statement line - a parked copy no recognition took - planted in a transaction
        // that is rolled back: the allocation it would make to the contribution is refused by the trigger, for every
        // writer (no pair unique is in play: the item was never allocated to it).
        UUID item = (UUID) one("SELECT id FROM reconciliation.external_item WHERE status = 'PARKED'"
                + " AND line_type = 'BANK_CREDIT' LIMIT 1");
        assertThat(item).as("order 5's parked copy").isNotNull();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try {
                UUID decision = IDS.next();
                execute(app,
                        "INSERT INTO reconciliation.match_decision (id, external_item_id, run_id, origin, rule_set_id,"
                                + " outcome, decided_by, decided_by_type, decided_at, decided_on, correlation_id,"
                                + " verdict, judged_status, judged_minor, fingerprint_seen_earlier)"
                                + " SELECT ?, i.id, i.run_id, 'RUN', r.rule_set_id, 'MATCHED', 'raw', 'SYSTEM', now(),"
                                + " current_date, 'raw', 'ALLOCATE', 'PENDING', 1, false"
                                + " FROM reconciliation.external_item i"
                                + " JOIN reconciliation.reconciliation_batch r ON r.id = i.run_id WHERE i.id = ?",
                        decision, item);
                assertThatExceptionOfType(SQLException.class)
                        .as("a run's decision never allocates to a contribution")
                        .isThrownBy(() -> execute(app,
                                "INSERT INTO reconciliation.allocation (id, decision_id, external_item_id,"
                                        + " expectation_id, amount_minor, currency, scale, reverses_allocation_id,"
                                        + " created_at, correlation_id) VALUES (?, ?, ?, ?, 1, 'EUR', 2, NULL, now(),"
                                        + " 'raw')",
                                IDS.next(), decision, item, recognised))
                        .withMessageContaining("bank recognition");
            } finally {
                app.rollback();
            }
        }
    }

    @Test
    @Order(11)
    @DisplayName("no person's resolution creates capital: a break on a contribution's expectation - which the ageing"
            + " sweep never raises - holds nothing a write-off could credit to LENDING_CAPITAL")
    void noResolutionCreatesCapital() throws Exception {
        UUID lapsed = (UUID) one("SELECT id FROM reconciliation.expectation"
                + " WHERE posting_key = 'lending.capital-contribution:lapsed'");
        try (CorrelationContext.Scope scope = CorrelationContext.enter(flow());
                SecurityContext.Scope system = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try {
                UUID bank = settlementFileStore.sourceByCode(app, BANK_SOURCE).orElseThrow().id();
                UUID breakId = IDS.next();
                breakRegister.raise(app, new BreakRegister.NewBreak(
                        breakId, BreakType.MISSING_EXTERNAL, BreakCause.EXPECTATION_OVERDUE,
                        BreakRegister.Subject.expectation(lapsed), bank, ruleSets.activeFor(app, bank).id(),
                        eur(70_00), Optional.of(ExpectationDirection.INBOUND),
                        Optional.of(ExpectationKind.LENDING_CAPITAL_CONTRIBUTION), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Actor.SYSTEM, CLOCK.instant(),
                        CorrelationId.of("p11t4-planted-break")));
                assertThatExceptionOfType(ResolutionMachine.ResolutionKindNotAllowed.class)
                        .as("a write-off of the contribution's remainder would CREDIT LENDING_CAPITAL into being")
                        .isThrownBy(() -> resolutionMachine.propose(app, breakId,
                                new ResolutionMachine.ProposalRequest(ResolutionKind.WRITE_OFF,
                                        ResolutionReasonCode.LOSS_ACCEPTED, "the transfer never came",
                                        Optional.empty(), Optional.empty(), Optional.empty()),
                                new Actor("investigator-1", com.finapp.platform.security.ActorType.EMPLOYEE),
                                CorrelationId.of("p11t4-write-off")));
            } finally {
                app.rollback();
            }
        }
    }

    // ----------------------------------------------------------------- the doors closed (P11-DOC-001's exit review)

    @Test
    @Order(12)
    @DisplayName("every capital door is closed to no session (401); a pending contribution's approval and rejection to a"
            + " session without LENDING_CAPITAL_ADMINISTER (403), the report to one without LENDING_INVESTIGATE (403);"
            + " an unknown or malformed contribution is 404 lending.NotFound to approve and to reject - nothing written by"
            + " any refused caller, the contribution still PROPOSED")
    void theCapitalDoorsAreClosed() throws Exception {
        String proposer = sessionWith(RoleName.LENDING_TREASURY_OFFICER);
        String treasurer = sessionWith(RoleName.LENDING_TREASURY_OFFICER);
        String officer = sessionWith(RoleName.LENDING_OFFICER);
        String nobody = sessionWith(null);
        HttpResponse<String> proposed = post(CONTRIBUTIONS, proposal("EUR", 123_00, reference(), "the doors' case"),
                proposer, key());
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        String contribution = field(proposed.body(), "id");
        long events = count("SELECT count(*) FROM lending.capital_contribution_event");
        long contributions = count("SELECT count(*) FROM lending.capital_contribution");
        long audited = count("SELECT count(*) FROM platform.audit_record WHERE operation LIKE 'lending.CapitalContribution%'");

        String approval = CONTRIBUTIONS + "/" + contribution + "/approval";
        String rejection = CONTRIBUTIONS + "/" + contribution + "/rejection";
        assertThat(post(CONTRIBUTIONS, proposal("GBP", 1_00, reference(), "no session"), null, key()).statusCode())
                .as("no session proposes").isEqualTo(401);
        for (String door : List.of(approval, rejection)) {
            assertThat(post(door, decision("no session"), null, key()).statusCode()).as("no session " + door)
                    .isEqualTo(401);
            HttpResponse<String> lending = post(door, decision("not mine to decide"), officer, key());
            assertThat(lending.statusCode()).as("LENDING_OFFICER " + door + ": " + lending.body()).isEqualTo(403);
            HttpResponse<String> roleless = post(door, decision("not mine to decide"), nobody, key());
            assertThat(roleless.statusCode()).as("no role " + door + ": " + roleless.body()).isEqualTo(403);
        }
        assertThat(get(CAPITAL, null).statusCode()).as("no session reads").isEqualTo(401);
        assertThat(get(CAPITAL, nobody).statusCode()).as("no role reads").isEqualTo(403);
        assertThat(get(CAPITAL, treasurer).statusCode()).as("LENDING_TREASURY_OFFICER lacks LENDING_INVESTIGATE")
                .isEqualTo(403);
        for (String unknown : List.of(UUID.randomUUID().toString(), IDS.next().toString(), "not-a-uuid")) {
            for (String act : List.of("/approval", "/rejection")) {
                HttpResponse<String> absent = post(CONTRIBUTIONS + "/" + unknown + act, decision("unknown"), treasurer,
                        key());
                assertThat(absent.statusCode()).as(unknown + act + ": " + absent.body()).isEqualTo(404);
                assertThat(absent.body()).contains("lending.NotFound");
            }
        }

        assertThat(count("SELECT count(*) FROM lending.capital_contribution_event")).as("no decision recorded")
                .isEqualTo(events);
        assertThat(count("SELECT count(*) FROM lending.capital_contribution")).as("no contribution proposed")
                .isEqualTo(contributions);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation LIKE"
                + " 'lending.CapitalContribution%'")).as("no act audited").isEqualTo(audited);
        assertThat(count("SELECT count(*) FROM reconciliation.expectation WHERE operation_ref = ?", contribution))
                .as("no expectation opened").isZero();
        assertThat(one("SELECT to_status FROM lending.capital_contribution_event WHERE contribution_id = ?::uuid"
                + " ORDER BY occurred_at DESC LIMIT 1", contribution)).isEqualTo("PROPOSED");

        HttpResponse<String> withdrawn = post(rejection, decision("the doors' case is done"), proposer, key());
        assertThat(withdrawn.statusCode()).as(withdrawn.body()).isEqualTo(200);
    }

    @Test
    @Order(13)
    @DisplayName("every capital act under one key is one (INV-IDEM-01): ten proposals, ten approvals and ten rejections"
            + " - each raced under one key - answer one receipt (or 409 api.IdempotencyInProgress) and make one"
            + " contribution, one decision, one expectation and one audit record each")
    void everyCapitalActUnderOneKeyIsOne() throws Exception {
        String proposer = sessionWith(RoleName.LENDING_TREASURY_OFFICER);
        String approver = sessionWith(RoleName.LENDING_TREASURY_OFFICER);
        String reference = reference();
        String body = proposal("EUR", 77_00, reference, "a raced proposal");
        String proposalKey = key();
        HttpResponse<String> proposed = OneKeyRace.tenUnderOneKey(201, () -> post(CONTRIBUTIONS, body, proposer,
                proposalKey));
        String contribution = field(proposed.body(), "id");
        assertThat(count("SELECT count(*) FROM lending.capital_contribution WHERE reference = ?", reference)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE target_id = ?"
                + " AND operation = 'lending.CapitalContributionProposed'", contribution)).isEqualTo(1);

        String approvalKey = key();
        HttpResponse<String> approved = OneKeyRace.tenUnderOneKey(200, () -> post(CONTRIBUTIONS + "/" + contribution
                + "/approval", decision("raced approval"), approver, approvalKey));
        assertThat(field(approved.body(), "status")).isEqualTo("APPROVED");
        assertThat(count("SELECT count(*) FROM lending.capital_contribution_event WHERE contribution_id = ?::uuid"
                + " AND to_status <> 'PROPOSED'", contribution)).as("one decision").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.expectation WHERE operation_ref = ?", contribution))
                .as("one expectation").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE target_id = ?"
                + " AND operation = 'lending.CapitalContributionApproved'", contribution)).isEqualTo(1);

        String rejected = field(post(CONTRIBUTIONS, proposal("EUR", 66_00, reference(), "to be rejected"), proposer,
                key()).body(), "id");
        String rejectionKey = key();
        HttpResponse<String> rejection = OneKeyRace.tenUnderOneKey(200, () -> post(CONTRIBUTIONS + "/" + rejected
                + "/rejection", decision("raced rejection"), approver, rejectionKey));
        assertThat(field(rejection.body(), "status")).isEqualTo("REJECTED");
        assertThat(count("SELECT count(*) FROM lending.capital_contribution_event WHERE contribution_id = ?::uuid"
                + " AND to_status <> 'PROPOSED'", rejected)).as("one decision").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE target_id = ?"
                + " AND operation = 'lending.CapitalContributionRejected'", rejected)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.expectation WHERE operation_ref = ?", rejected)).isZero();
    }

    @Test
    @Order(14)
    @DisplayName("every capital arbiter refuses a copy alone, triggers off (P11-DOC-001): each unique of the contribution's"
            + " two tables, read from the catalogue, refuses a copy of a real row under a fresh key by itself - every"
            + " trigger suspended and every CHECK dropped in the probe's rolled-back savepoint")
    void everyCapitalArbiterRefusesACopyWithEveryTriggerOff() throws Exception {
        List<com.finapp.platform.testing.database.BornOnceArbiters.Probe> probes =
                com.finapp.platform.testing.database.BornOnceArbiters.probe("lending").stream()
                        .filter(probe -> probe.table().startsWith("capital_contribution"))
                        .toList();
        System.out.println("P11-DOC-001 CAPITAL ARBITERS: " + probes);
        assertThat(probes).as("the capital tables' born-once uniques").extracting(
                        com.finapp.platform.testing.database.BornOnceArbiters.Probe::constraint)
                .contains("capital_contribution_reference_once", "capital_contribution_born_once",
                        "capital_contribution_decided_once", "capital_contribution_event_expectation_once");
        // Every one refused alone but the proposer copy's composite foreign-key target, which binds the whole key.
        assertThat(probes).allSatisfy(probe -> assertThat(probe.verdict()).as(probe.toString()).isEqualTo(
                probe.constraint().equals("capital_contribution_proposer_pinned")
                        ? com.finapp.platform.testing.database.BornOnceArbiters.Verdict.PRIMARY_KEY_BOUND
                        : com.finapp.platform.testing.database.BornOnceArbiters.Verdict.REFUSED_ALONE));
    }

    // ----------------------------------------------------------------- fixtures

    private Boolean reserve(LoanLedgerAccounts loan, Money amount, CountDownLatch start, CountDownLatch bothRead)
            throws Exception {
        start.await();
        try (CorrelationContext.Scope scope = CorrelationContext.enter(flow());
                SecurityContext.Scope system = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            LendingCapitalHeadroom.Headroom seen = headroom.underLock(app, EUR);
            bothRead.countDown();
            // Give a second consumer every chance to read the same headroom - it can only if the lock is gone.
            bothRead.await(2, TimeUnit.SECONDS);
            if (!seen.admits(amount)) {
                app.rollback();
                return false;
            }
            postingService.post(app, new PostingCommand(
                    "p11t4-reserve:" + UUID.randomUUID(), LocalDate.now(ZoneOffset.UTC), LocalDate.now(ZoneOffset.UTC),
                    "a-headroom-consumer",
                    List.of(new JournalLine(loan.principal().id(), Direction.DEBIT, amount),
                            new JournalLine(loan.creditBalance().id(), Direction.CREDIT, amount))));
            app.commit();
            return true;
        }
    }

    private LendingCapitalHeadroom.Headroom headroomNow() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            return headroom.atRest(app, EUR);
        }
    }

    private LendingCapitalProof.Report proofNow() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            app.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                return proof.prove(app);
            } finally {
                app.rollback();
            }
        }
    }

    /** An expectation whose window has already passed - a contribution approved and never funded in time. */
    private void openLapsedExpectation(String reference, long minor) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            UUID bank = settlementFileStore.sourceByCode(app, BANK_SOURCE).orElseThrow().id();
            RuleSets.ActiveRuleSet ruleSet = ruleSets.activeFor(app, bank);
            LocalDate approvedOn = LocalDate.now(ZoneOffset.UTC).minusDays(10);
            expectationRegister.open(app, new NewExpectation(
                    ExpectationKind.LENDING_CAPITAL_CONTRIBUTION, UUID.randomUUID().toString(),
                    "lending.capital-contribution:lapsed", bank, AccountPurpose.LENDING_CAPITAL,
                    headroom.capitalAccount(app, EUR).id().value(), ExpectationDirection.INBOUND, eur(minor),
                    Optional.empty(), approvedOn, Optional.empty(), approvedOn.plusDays(ruleSet.fundingLagDays()),
                    ruleSet.id(), List.of(new NewExpectation.ExpectationKey(KeyKind.REMITTANCE_REF, reference)),
                    Actor.SYSTEM, CLOCK.instant(), CorrelationId.of("p11t4-lapsed")));
            app.commit();
        }
    }

    private UUID acceptedStatement(String... lines) throws SQLException {
        UUID file = pulledStatement(lines);
        parsing.sweep();
        acceptance.sweep();
        assertThat(one("SELECT status || ':' || coalesce(rejection_code, '-') FROM settlement.file WHERE id = ?", file))
                .isEqualTo("ACCEPTED:-");
        for (String line : lines) {
            closingMinor += Long.parseLong(line.split(",")[2].replace(".", ""));
        }
        return (UUID) one("SELECT id FROM settlement.batch WHERE file_id = ?", file);
    }

    /** Pulls the next EUR statement - its opening the chain's last closing; the closing is booked on acceptance. */
    private UUID pulledStatement(String... lines) {
        long credits = 0;
        for (String line : lines) {
            credits += Long.parseLong(line.split(",")[2].replace(".", ""));
        }
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        StringBuilder text = new StringBuilder()
                .append(":20:LCAP-STMT-").append(nextSequence).append('-').append(marker()).append('\n')
                .append(":25:SIMBANK-EUR-01\n")
                .append(":28C:").append(nextSequence).append('\n')
                .append(":60F:C,").append(today).append(",EUR,").append(decimal(closingMinor)).append('\n');
        for (String line : lines) {
            text.append(line).append('\n');
        }
        text.append(":62F:C,").append(today).append(",EUR,").append(decimal(closingMinor + credits)).append('\n');
        nextSequence++;
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(flow())) {
            FileReception.Result result = settlementTransactionRunner.inTransaction(uow -> reception.receive(uow,
                    new FileReception.Delivery(BANK_SOURCE, DeliveryChannel.PULL,
                            text.toString().getBytes(StandardCharsets.UTF_8), Optional.empty(), Actor.SYSTEM,
                            SettlementAuditAction.SETTLEMENT_FILE_UPLOADED, CorrelationContext.current().orElseThrow())));
            assertThat(result).isInstanceOf(FileReception.Result.New.class);
            return ((FileReception.Result.New) result).fileId();
        }
    }

    private static String capitalLine(long minor, String reference) {
        return ":61:" + LocalDate.now(ZoneOffset.UTC) + ",C," + decimal(minor) + "," + reference;
    }

    private static String decimal(long minor) {
        return (minor / 100) + "." + String.format("%02d", minor % 100);
    }

    private void matchUntilQuiet() {
        for (int i = 0; i < 4; i++) {
            matching.sweep();
        }
    }

    private static long suspenseOf(UUID batch) throws SQLException {
        return count("SELECT count(*) FROM reconciliation.suspense_item s JOIN reconciliation.external_item i"
                + " ON i.id = s.external_item_id JOIN reconciliation.reconciliation_batch r ON r.id = i.run_id"
                + " WHERE r.batch_id = ?", batch);
    }

    private static List<String> entryLines(UUID entry) throws SQLException {
        List<String> lines = new ArrayList<>();
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement(
                        "SELECT a.purpose, l.direction, l.amount_minor FROM ledger.journal_line l"
                                + " JOIN ledger.ledger_account a ON a.id = l.ledger_account_id WHERE l.entry_id = ?")) {
            select.setObject(1, entry);
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    lines.add(row.getString(1) + ":" + row.getString(2) + ":" + row.getLong(3));
                }
            }
        }
        return lines;
    }

    private static int pid(Connection connection) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT pg_backend_pid()");
                ResultSet row = select.executeQuery()) {
            row.next();
            return row.getInt(1);
        }
    }

    /** The query of the backend blocked behind {@code holderPid}, awaited up to twenty seconds. */
    private static String awaitWaiterBehind(int holderPid) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            Object waiting = one("SELECT query FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                    + " AND ? = ANY (pg_blocking_pids(pid)) LIMIT 1", holderPid);
            if (waiting != null) {
                return (String) waiting;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("no backend came to wait behind the capital lock");
    }

    private static boolean isWaiting(int pid) throws SQLException {
        return count("SELECT count(*) FROM pg_stat_activity WHERE pid = ? AND wait_event_type = 'Lock'", pid) > 0;
    }

    private static Money eur(long minor) {
        return Money.ofPersisted(minor, EUR, 2);
    }

    private static String reference() {
        return "LCAP-" + (10_000_000 + RANDOMNESS.nextInt(89_999_999));
    }

    private static String marker() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private static String proposal(String currency, long minor, String reference, String reason) {
        return "{\"currency\":\"" + currency + "\",\"amountMinor\":" + minor + ",\"reference\":\"" + reference
                + "\",\"reason\":\"" + reason + "\"}";
    }

    private static String decision(String reason) {
        return "{\"reason\":\"" + reason + "\"}";
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static Correlation flow() {
        return Correlation.startingWith(CorrelationId.of("p11t4-" + UUID.randomUUID()))
                .causing(CausationId.of("p11t4-cause"));
    }

    /** A signed-in session holding {@code role}, or no role at all when it is {@code null}. */
    private String sessionWith(RoleName role) throws Exception {
        String login = "lc." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(post("/v1/registrations",
                        "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Grace Hopper\","
                                + "\"password\":\"" + PASSWORD + "\"}",
                        null, key()).statusCode())
                .isEqualTo(201);
        UUID identity = (UUID) one("SELECT id FROM identity.identity WHERE login_identifier = ?", login);
        if (role != null) {
            try (CorrelationContext.Scope scope = CorrelationContext.enter(flow());
                    SecurityContext.Scope actor = SecurityContext.enterSystem();
                    Connection app = DatabaseRoles.application()) {
                app.setAutoCommit(false);
                authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
                app.commit();
            }
        }
        HttpResponse<String> session = post("/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}", null, key());
        return field(session.body(), "sessionToken");
    }

    private HttpResponse<String> post(String path, String body, String bearer, String key) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
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

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path)).GET();
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static String field(String body, String name) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("the body must carry %s: %s", name, body).isTrue();
        return matcher.group(1);
    }

    private static void execute(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.executeUpdate();
        }
    }

    private static Object one(String sql, Object... args) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement statement = app.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getObject(1) : null;
            }
        }
    }

    private static long count(String sql, Object... args) throws SQLException {
        return ((Number) one(sql, args)).longValue();
    }
}
