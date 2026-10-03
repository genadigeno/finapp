package com.finapp.app.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounts.AccountOpening;
import com.finapp.accounts.JdbcCustomerAccountStore;
import com.finapp.accounts.ProductType;
import com.finapp.app.payments.ChargebackAccountingFixture;
import com.finapp.app.payments.JdbcPaymentParticipants;
import com.finapp.app.accounts.VerifiedAccountHolder;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcSessionStore;
import com.finapp.identity.RoleName;
import com.finapp.identity.Session;
import com.finapp.identity.SessionPolicy;
import com.finapp.identity.SessionStore;
import com.finapp.identity.SessionToken;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingService;
import com.finapp.party.JdbcPartyStore;
import com.finapp.paymentmethods.JdbcPaymentMethodStore;
import com.finapp.payments.BookRail;
import com.finapp.payments.JdbcPaymentAttemptStore;
import com.finapp.payments.JdbcPaymentIntentStore;
import com.finapp.payments.PaymentAttempt;
import com.finapp.payments.PaymentAttemptId;
import com.finapp.payments.PaymentAttemptStatus;
import com.finapp.payments.PaymentCreation;
import com.finapp.payments.PaymentIntentId;
import com.finapp.payments.PaymentIntentStatus;
import com.finapp.payments.PaymentOutcomes;
import com.finapp.payments.PaymentRails;
import com.finapp.payments.ProviderAnswer;
import com.finapp.payments.ProviderIdempotencyReference;
import com.finapp.payments.ProviderReference;
import com.finapp.payments.Refund;
import com.finapp.payments.RefundStatus;
import com.finapp.payments.SettlementExpectations;
import com.finapp.payments.SimulatedCardPspAdapter;
import com.finapp.payments.TransactionRunner;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.reconciliation.ExpectationDirection;
import com.finapp.reconciliation.ExpectationKind;
import com.finapp.reconciliation.KeyKind;
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
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The opening position and the proofs (`P8-TSK-007`, ADR-0067 §8–§9): completions driven
 * with a quiet double — the pre-Phase-8 world, where the port did not exist — then the
 * keyed, reasoned backfill over real HTTP, every adopted expectation proven against the
 * LEDGER with the same helper the live openers are proven with; the verdicts flip on a
 * planted gap and a raw-SQL line; ten backfills race a live burst and add nothing twice.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the opening position, the position proof and the completeness verifier"
        + " (P8-TSK-007)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class ReconciliationOpeningDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final Money AMOUNT = Money.ofMinorUnits(12_00, EUR);

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private PositionProof positionProof;

    // The opening position's own collaborators, named as its bean method names them, for the
    // crash-injected instance (SEC-08); the bean itself retries what the crash interrupted.
    @Autowired private OpeningPosition openingPosition;
    @Autowired private com.finapp.payments.PaymentAttemptStore<Connection> paymentAttemptStore;
    @Autowired private com.finapp.payments.PaymentIntentStore<Connection> paymentIntentStore;
    @Autowired private com.finapp.payments.RefundStore<Connection> refundStore;
    @Autowired private com.finapp.payments.WithdrawalStore<Connection> withdrawalStore;
    @Autowired private com.finapp.payments.DisputeStore<Connection> disputeStore;

    @Autowired
    private com.finapp.payments.UnmatchedConfirmationStore<Connection> unmatchedConfirmationStore;

    @Autowired private com.finapp.merchant.MerchantPayoutStore<Connection> merchantPayoutStore;
    @Autowired private PaymentRails paymentRails;
    @Autowired private com.finapp.ledger.LedgerAccountStore<Connection> ledgerAccountStore;
    @Autowired private com.finapp.ledger.JournalEntryStore<Connection> journalEntryStore;
    @Autowired private ReconciliationExpectationRecorder settlementExpectations;
    @Autowired private IdempotentExecutor idempotentExecutor;
    @Autowired private com.finapp.platform.audit.AuditWriter<Connection> auditWriter;
    @Autowired private IdGenerator idGenerator;
    @Autowired private Clock clock;

    @Autowired
    private org.springframework.transaction.support.TransactionTemplate reconciliationTransactions;

    @Autowired private javax.sql.DataSource dataSource;
    @Autowired private com.finapp.settlement.SettlementSources settlementSources;

    @Autowired
    private com.finapp.settlement.SettlementBatchStore<Connection> settlementBatchStore;

    @Autowired private com.finapp.app.settlement.ReconciliationIntake acceptedBatchIntake;
    @Autowired private com.finapp.merchant.PayoutReturnStore<Connection> payoutReturnStore;

    @Autowired
    private com.finapp.payments.SchemeExecutionClaimStore<Connection> schemeExecutionClaimStore;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SessionStore<Connection> sessions = new JdbcSessionStore();
    private final Runner runner = new Runner();
    private final JdbcPaymentIntentStore intents = new JdbcPaymentIntentStore();
    private final JdbcPaymentAttemptStore attempts = new JdbcPaymentAttemptStore();
    private final JdbcLedgerAccountStore ledgerAccounts = new JdbcLedgerAccountStore();
    private final JdbcPaymentParticipants participants =
            new JdbcPaymentParticipants(
                    new JdbcPartyStore(),
                    new JdbcCustomerAccountStore(),
                    ledgerAccounts,
                    new JdbcPaymentMethodStore());

    // -----------------------------------------------------------------
    // The adoption: history's completions opened exactly as the live opener would.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("the backfill adopts a pre-Phase-8 capture and refund as their clearing"
            + " lines' copies - proven against the ledger - a same-key replay answers the"
            + " recorded counts, and another controller's run adds nothing")
    void theBackfillAdoptsHistory() throws Exception {
        // THE PRE-PHASE-8 WORLD: completions through the quiet double, so no expectation
        // exists - exactly the history ADR-0067 section 8 adopts.
        Holder holder = holder();
        PaymentAttemptId attemptId = captureDispatched(holder);
        String pspRef = "psp-open-" + UUID.randomUUID();
        applyCapture(holder, attemptId, pspRef);
        Refund refund = dispatchedRefund(holder, attemptId);
        String pspRefundRef = "psp-open-rfd-" + UUID.randomUUID();
        applyRefund(holder, refund, pspRefundRef);
        // A second, UNREFUNDED capture, so the position's identity is judged on a NON-ZERO
        // balance: a capture-and-refund world nets to zero, and zero is sign-blind — the
        // probe run that inverted the fold's sign SURVIVED against it (the recorded find),
        // which is exactly what this asymmetry closes.
        Holder unrefunded = holder();
        PaymentAttemptId standing = captureDispatched(unrefunded);
        applyCapture(unrefunded, standing, "psp-open-standing-" + UUID.randomUUID());
        assertThat(ClearingLineCopies.expectationsOf(
                        ExpectationKind.CARD_CAPTURE, attemptId.value().toString()))
                .as("the quiet double really was the pre-Phase-8 world")
                .isZero();

        // THE BACKFILL, over real HTTP, as the CONTROLLER - reasoned and keyed.
        Session controller = controllerSession();
        String key = "open-" + IDS.next();
        HttpResponse<String> recorded = backfill(controller.token(), key, "adopting history");
        assertThat(recorded.statusCode()).isEqualTo(200);

        // EVERY ADOPTED ROW IS WHAT THE LIVE OPENER WOULD HAVE WRITTEN: the same
        // ledger-backed proof the live openers are proven with, kind by kind.
        ClearingLineCopies.Opened capture =
                ClearingLineCopies.assertOpensItsClearingLinesCopy(
                        ExpectationKind.CARD_CAPTURE,
                        attemptId.value().toString(),
                        "payment-capture:" + attemptId.value(),
                        ExpectationDirection.INBOUND);
        ClearingLineCopies.assertKeyed(capture, KeyKind.PSP_CAPTURE_REF, pspRef);
        ClearingLineCopies.assertKeyed(
                capture, KeyKind.CARD_ATTEMPT, attemptId.value().toString());
        ClearingLineCopies.Opened refunded =
                ClearingLineCopies.assertOpensItsClearingLinesCopy(
                        ExpectationKind.CARD_REFUND,
                        refund.id().value().toString(),
                        "payment-refund:" + refund.id().value(),
                        ExpectationDirection.OUTBOUND);
        ClearingLineCopies.assertKeyed(refunded, KeyKind.PSP_REFUND_REF, pspRefundRef);

        // The record is audited with the reason and counts only.
        try (Connection app = DatabaseRoles.application()) {
            assertThat(count(app,
                            "SELECT count(*) FROM platform.audit_record WHERE operation ="
                                    + " 'reconciliation.OpeningPositionRecorded' AND reason ="
                                    + " 'adopting history'"))
                    .isEqualTo(1);
        }

        // THE REPLAY: the same principal's key answers the recorded counts byte for byte.
        HttpResponse<String> replay = backfill(controller.token(), key, "adopting history");
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(recorded.body());

        // ANOTHER CONTROLLER, ANOTHER KEY: the walk re-runs and the uniques converge.
        long before = expectationCount();
        Session second = controllerSession();
        assertThat(backfill(second.token(), "open-" + IDS.next(), "re-run converges")
                        .statusCode())
                .isEqualTo(200);
        assertThat(expectationCount()).as("a re-run adds nothing").isEqualTo(before);

        // THE VERDICTS, on the real schema: every clearing position explained - and the
        // card position judged on a NON-ZERO balance, so the identity's sign is load-bearing
        // here, not vacuously satisfied by zero.
        PositionProof.Report report = sweep();
        for (PositionProof.PositionVerdict verdict : report.verdicts()) {
            assertThat(verdict.explained())
                    .as("%s %s: DR-CR %s = open remainders %s (INV-REC-06)",
                            verdict.purpose(), verdict.currency(),
                            verdict.ledgerBalance(), verdict.openRemainders())
                    .isTrue();
        }
        PositionProof.PositionVerdict card =
                report.verdicts().stream()
                        .filter(
                                verdict ->
                                        verdict.purpose()
                                                        == AccountPurpose.SETTLEMENT_CLEARING
                                                && verdict.currency().equals(EUR))
                        .findFirst()
                        .orElseThrow();
        assertThat(card.ledgerBalance().minorUnits())
                .as("the standing capture keeps the position non-zero: the sign is judged")
                .isPositive();
        // The identity's own form since P8-TSK-009 carries the items term - in the
        // shared container another suite's waiting item and its balancing expectation
        // (P8-TSK-011's) may stand, so the special case remainders = balance is not
        // this suite's to assert.
        assertThat(card.openRemainders().minus(card.openItems()))
                .isEqualTo(card.ledgerBalance());
        assertThat(report.unattributedByPurpose().get(AccountPurpose.SETTLEMENT_CLEARING))
                .as("every clearing line is known")
                .isZero();
    }

    @Test
    @DisplayName("ten backfills race a burst of LIVE completions: every capture ends with"
            + " exactly one expectation - the uniques arbitrate, no lock and no leader")
    void tenBackfillsRaceALiveBurst() throws Exception {
        // History: three quiet-double captures the backfills will adopt.
        List<PaymentAttemptId> history = new ArrayList<>();
        List<Holder> holders = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Holder holder = holder();
            PaymentAttemptId attempt = captureDispatched(holder);
            applyCapture(holder, attempt, "psp-hist-" + UUID.randomUUID());
            history.add(attempt);
            holders.add(holder);
        }
        // Live: three more, prepared now, completed DURING the race with the REAL recorder.
        List<Holder> liveHolders = new ArrayList<>();
        List<PaymentAttemptId> live = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Holder holder = holder();
            live.add(captureDispatched(holder));
            liveHolders.add(holder);
        }
        List<Session> controllers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            controllers.add(controllerSession());
        }

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(13);
        List<Future<?>> racers = new ArrayList<>();
        try {
            for (Session controller : controllers) {
                racers.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return backfill(
                                            controller.token(),
                                            "race-" + UUID.randomUUID(),
                                            "racing backfills");
                                }));
            }
            for (int i = 0; i < live.size(); i++) {
                Holder holder = liveHolders.get(i);
                PaymentAttemptId attempt = live.get(i);
                racers.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    applyCaptureLive(holder, attempt,
                                            "psp-live-" + UUID.randomUUID());
                                    return null;
                                }));
            }
            start.countDown();
            for (Future<?> racer : racers) {
                racer.get();
            }
        } finally {
            pool.shutdownNow();
        }

        List<PaymentAttemptId> all = new ArrayList<>(history);
        all.addAll(live);
        for (PaymentAttemptId attempt : all) {
            assertThat(ClearingLineCopies.expectationsOf(
                            ExpectationKind.CARD_CAPTURE, attempt.value().toString()))
                    .as("one expectation for %s under ten backfills and a live burst",
                            attempt)
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a walk that crashes between its transactions leaves its adopted prefix AND the"
            + " record of who began it and why - the start commits before the first page"
            + " (SEC-08); the same-key retry re-walks, converges and records its counts")
    void aCrashMidWalkStillLeavesItsStartOnTheRecord() throws Exception {
        // History the walk adopts BEFORE it crashes, so the prefix it leaves is real.
        Holder holder = holder();
        PaymentAttemptId attemptId = captureDispatched(holder);
        applyCapture(holder, attemptId, "psp-crash-" + UUID.randomUUID());

        // THE CRASH: the alias leg's page dies after every capture was adopted - the process
        // lost between the walk's transactions, never reaching the counts' record.
        com.finapp.payments.ClearingRecordStore<Connection> real =
                new com.finapp.payments.JdbcClearingRecordStore();
        com.finapp.payments.ClearingRecordStore<Connection> crashing =
                new com.finapp.payments.ClearingRecordStore<>() {
                    @Override
                    public boolean insert(
                            Connection unitOfWork, com.finapp.payments.ClearingRecord fresh) {
                        return real.insert(unitOfWork, fresh);
                    }

                    @Override
                    public Optional<com.finapp.payments.ClearingRecord> findForAttempt(
                            Connection unitOfWork, PaymentAttemptId attempt) {
                        return real.findForAttempt(unitOfWork, attempt);
                    }

                    @Override
                    public List<com.finapp.payments.ClearingRecord> page(
                            Connection unitOfWork, UUID after, int limit) {
                        throw new SimulatedCrash();
                    }
                };
        OpeningPosition crashingWalk =
                new OpeningPosition(
                        paymentAttemptStore,
                        paymentIntentStore,
                        refundStore,
                        withdrawalStore,
                        disputeStore,
                        unmatchedConfirmationStore,
                        crashing,
                        merchantPayoutStore,
                        paymentRails,
                        new ChartOfAccounts<>(ledgerAccountStore),
                        journalEntryStore,
                        settlementExpectations,
                        idempotentExecutor,
                        auditWriter,
                        idGenerator,
                        clock,
                        reconciliationTransactions,
                        dataSource,
                        settlementSources,
                        settlementBatchStore,
                        acceptedBatchIntake,
                        payoutReturnStore,
                        schemeExecutionClaimStore);

        Actor controller = new Actor(IDS.next().toString(), ActorType.CUSTOMER);
        String key = "crash-" + IDS.next();
        String reason = "adopting history, interrupted";
        Correlation crashed = flow();
        try (SecurityContext.Scope actor = SecurityContext.enter(controller);
                CorrelationContext.Scope flow = CorrelationContext.enter(crashed)) {
            assertThatThrownBy(() -> crashingWalk.record(key, reason))
                    .isInstanceOf(SimulatedCrash.class);
        }

        assertThat(ClearingLineCopies.expectationsOf(
                        ExpectationKind.CARD_CAPTURE, attemptId.value().toString()))
                .as("the walk's prefix committed before the crash")
                .isEqualTo(1);
        try (Connection app = DatabaseRoles.application()) {
            assertThat(count(app,
                            "SELECT count(*) FROM platform.audit_record WHERE operation ="
                                    + " 'reconciliation.OpeningPositionStarted' AND"
                                    + " correlation_id = ? AND actor_id = ? AND reason = ? AND"
                                    + " outcome = 'SUCCEEDED'",
                            crashed.correlationId().value(), controller.id(), reason))
                    .as("who began the walk, and why, is on the record though it never ended")
                    .isEqualTo(1);
            assertThat(count(app,
                            "SELECT count(*) FROM platform.audit_record WHERE operation ="
                                    + " 'reconciliation.OpeningPositionRecorded' AND"
                                    + " correlation_id = ?",
                            crashed.correlationId().value()))
                    .as("no counts: the run was never recorded")
                    .isZero();
            assertThat(count(app,
                            "SELECT count(*) FROM platform.idempotency_record WHERE"
                                    + " idempotency_key = ?",
                            key))
                    .as("nor claimed")
                    .isZero();
        }

        // THE RETRY, same principal and key: it re-walks, converges on the uniques, records.
        Correlation retried = flow();
        try (SecurityContext.Scope actor = SecurityContext.enter(controller);
                CorrelationContext.Scope flow = CorrelationContext.enter(retried)) {
            openingPosition.record(key, reason);
        }
        assertThat(ClearingLineCopies.expectationsOf(
                        ExpectationKind.CARD_CAPTURE, attemptId.value().toString()))
                .as("the retry adds nothing twice")
                .isEqualTo(1);
        try (Connection app = DatabaseRoles.application()) {
            assertThat(count(app,
                            "SELECT count(*) FROM platform.audit_record WHERE operation ="
                                    + " 'reconciliation.OpeningPositionRecorded' AND"
                                    + " correlation_id = ? AND actor_id = ?",
                            retried.correlationId().value(), controller.id()))
                    .isEqualTo(1);
            assertThat(count(app,
                            "SELECT count(*) FROM platform.audit_record WHERE operation ="
                                    + " 'reconciliation.OpeningPositionStarted' AND"
                                    + " actor_id = ?",
                            controller.id()))
                    .as("one start per walk: the crashed one and the retry")
                    .isEqualTo(2);
        }
    }

    /** The injected crash: nothing the walk could catch or answer. */
    private static final class SimulatedCrash extends RuntimeException {
        private static final long serialVersionUID = 1L;

        SimulatedCrash() {
            super("simulated crash between the walk's transactions");
        }
    }

    // -----------------------------------------------------------------
    // The verdicts flip - report, never repair.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a planted missing expectation flips the proof and the completeness count;"
            + " a raw-SQL clearing line flips completeness alone - detection, never repair")
    void theVerdictsFlip() throws Exception {
        // BASELINE: adopt whatever the shared container's earlier suites left behind (the
        // hand-built ones drive completions through quiet doubles by design), so the
        // verdicts below start from an explained world whatever the class ordering.
        assertThat(backfill(controllerSession().token(), "base-" + IDS.next(),
                        "baseline adoption for the flip probes")
                        .statusCode())
                .isEqualTo(200);
        Holder holder = holder();
        PaymentAttemptId attemptId = captureDispatched(holder);
        applyCaptureLive(holder, attemptId, "psp-flip-" + UUID.randomUUID());
        ClearingLineCopies.Opened opened =
                ClearingLineCopies.assertOpensItsClearingLinesCopy(
                        ExpectationKind.CARD_CAPTURE,
                        attemptId.value().toString(),
                        "payment-capture:" + attemptId.value(),
                        ExpectationDirection.INBOUND);
        PositionProof.Report before = sweep();
        long unattributedBefore =
                before.unattributedByPurpose().get(AccountPurpose.SETTLEMENT_CLEARING);
        assertThat(before.currenciesFailing(AccountPurpose.SETTLEMENT_CLEARING)).isZero();

        // THE MISSING EXPECTATION, planted the only way one can exist - history's shape,
        // with the append-only triggers disabled as the platform's own root.
        try (Connection root = DatabaseRoles.bootstrap()) {
            root.setAutoCommit(false);
            execute(root, "ALTER TABLE reconciliation.expectation_key DISABLE TRIGGER"
                    + " expectation_key_is_append_only");
            execute(root, "ALTER TABLE reconciliation.expectation_event DISABLE TRIGGER"
                    + " expectation_event_is_append_only");
            execute(root, "ALTER TABLE reconciliation.expectation DISABLE TRIGGER"
                    + " expectation_is_never_deleted");
            try {
                execute(root, "DELETE FROM reconciliation.expectation_key WHERE"
                        + " expectation_id = ?", opened.id());
                execute(root, "DELETE FROM reconciliation.expectation_event WHERE"
                        + " expectation_id = ?", opened.id());
                execute(root, "DELETE FROM reconciliation.expectation WHERE id = ?",
                        opened.id());
            } finally {
                execute(root, "ALTER TABLE reconciliation.expectation ENABLE TRIGGER"
                        + " expectation_is_never_deleted");
                execute(root, "ALTER TABLE reconciliation.expectation_event ENABLE TRIGGER"
                        + " expectation_event_is_append_only");
                execute(root, "ALTER TABLE reconciliation.expectation_key ENABLE TRIGGER"
                        + " expectation_key_is_append_only");
            }
            root.commit();
        }
        PositionProof.Report missing = sweep();
        assertThat(missing.currenciesFailing(AccountPurpose.SETTLEMENT_CLEARING))
                .as("the proof flips: a clearing currency's identity fails")
                .isPositive();
        assertThat(missing.unattributedByPurpose().get(AccountPurpose.SETTLEMENT_CLEARING))
                .as("the capture's line is no longer explained")
                .isEqualTo(unattributedBefore + 1);

        // REPAIR IS THE BACKFILL'S, REASONED - never the verifier's: the re-adoption
        // restores both verdicts.
        assertThat(backfill(controllerSession().token(), "flip-" + IDS.next(),
                        "re-adopting the planted gap")
                        .statusCode())
                .isEqualTo(200);
        PositionProof.Report repaired = sweep();
        assertThat(repaired.currenciesFailing(AccountPurpose.SETTLEMENT_CLEARING)).isZero();
        assertThat(repaired.unattributedByPurpose().get(AccountPurpose.SETTLEMENT_CLEARING))
                .isEqualTo(unattributedBefore);

        // THE RAW-SQL LINE: a writer outside every port - completeness names it at once
        // (ADR-0067's every-writer detection), and the proof fails with it. Observed in the
        // writer's own uncommitted transaction and rolled back, so the shared container's
        // later suites never inherit the plant.
        UUID rawEntry = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try {
                execute(app,
                        "INSERT INTO ledger.journal_entry (id, posting_date, value_date,"
                                + " entry_type, reference, actor_id, correlation_id,"
                                + " causation_id, idempotency_scope, created_at)"
                                + " VALUES (?, current_date, current_date, 'POSTING',"
                                + " 'raw-line-probe', 'raw-writer', 'raw-corr', 'raw-cause',"
                                + " ?, now())",
                        rawEntry, "raw:" + rawEntry);
                UUID clearing = clearingAccountId(app, "EUR");
                UUID fee = feeAccountId(app, "EUR");
                execute(app,
                        "INSERT INTO ledger.journal_line (id, entry_id, ledger_account_id,"
                                + " direction, amount_minor, currency, scale, seq)"
                                + " VALUES (?, ?, ?, 'DEBIT', 700, 'EUR', 2, 0),"
                                + " (?, ?, ?, 'CREDIT', 700, 'EUR', 2, 1)",
                        IDS.next(), rawEntry, clearing, IDS.next(), rawEntry, fee);
                PositionProof.Report rawLine = positionProof.sweep(app);
                assertThat(rawLine.unattributedByPurpose()
                                .get(AccountPurpose.SETTLEMENT_CLEARING))
                        .as("the raw line is counted at once")
                        .isEqualTo(unattributedBefore + 1);
                assertThat(rawLine.currenciesFailing(AccountPurpose.SETTLEMENT_CLEARING))
                        .as("and the identity fails with it")
                        .isPositive();
            } finally {
                app.rollback();
            }
        }
    }

    // -----------------------------------------------------------------
    // The doors: negatives three ways, the report audited and refused across desks.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("the backfill and the report hold their permissions: anonymous 401, a"
            + " role-less session 403, and each desk refused at the other's door")
    void theSecurityNegativesHold() throws Exception {
        Session controller = controllerSession();
        Session operator = operatorSession();
        Session roleless = rolelessSession();

        // The backfill: only the CONTROLLER.
        assertThat(backfill(null, "neg-" + IDS.next(), "anonymous").statusCode())
                .isEqualTo(401);
        assertThat(backfill(roleless.token(), "neg-" + IDS.next(), "role-less").statusCode())
                .isEqualTo(403);
        assertThat(backfill(operator.token(), "neg-" + IDS.next(), "the operator desk")
                        .statusCode())
                .as("the desk the controller oversees cannot adopt history")
                .isEqualTo(403);
        // A reason-less ask is the boundary's 400/422, and nothing is recorded.
        assertThat(backfillBody(controller.token(), "neg-" + IDS.next(), "{}").statusCode())
                .isBetween(400, 422);

        // The report: only the INVESTIGATOR - and every serving is on the record.
        assertThat(positionsReport(null).statusCode()).isEqualTo(401);
        assertThat(positionsReport(roleless.token()).statusCode()).isEqualTo(403);
        assertThat(positionsReport(controller.token()).statusCode())
                .as("the controller adopts; the investigator reads")
                .isEqualTo(403);
        try (Connection app = DatabaseRoles.application()) {
            long served =
                    count(app,
                            "SELECT count(*) FROM platform.audit_record WHERE operation ="
                                    + " 'reconciliation.ReportRead'");
            HttpResponse<String> report = positionsReport(operator.token());
            assertThat(report.statusCode()).isEqualTo(200);
            assertThat(report.body()).contains("\"positions\"").contains("ledgerBalance");
            assertThat(count(app,
                            "SELECT count(*) FROM platform.audit_record WHERE operation ="
                                    + " 'reconciliation.ReportRead'"))
                    .as("amounts served, so the serving is on the record (ADR-0072)")
                    .isEqualTo(served + 1);
        }
    }

    // -----------------------------------------------------------------
    // Fixtures: the card world, driven with the QUIET double (pre-Phase-8) or LIVE.
    // -----------------------------------------------------------------

    private record Holder(UUID party, PaymentIntentId intent, LedgerAccountId wallet) {}

    /** The quiet double: the pre-Phase-8 world, where no port existed to open anything. */
    private static final SettlementExpectations QUIET =
            new SettlementExpectations() {
                @Override
                public void open(Connection unitOfWork, Opening opening) {}

                @Override
                public void alias(Connection unitOfWork, AliasRegistration registration) {}

                @Override
                public void parked(java.sql.Connection uow, ParkedValue parked) {}
            };

    @Autowired private SettlementExpectations liveRecorder;

    private Holder holder() throws Exception {
        UUID party = IDS.next();
        UUID customer = IDS.next();
        UUID method = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Opening Holder',"
                            + " now() - interval '2 hour')",
                    party);
            execute(app,
                    "INSERT INTO party.customer (id, party_id, status, opened_at,"
                            + " status_changed_at) VALUES (?, ?, 'ACTIVE',"
                            + " now() - interval '1 hour', now() - interval '1 hour')",
                    customer, party);
            execute(app,
                    "INSERT INTO paymentmethods.payment_method (id, party_id, kind,"
                            + " token_reference, brand, display_suffix, expiry_month,"
                            + " expiry_year, status, created_at) VALUES (?, ?, 'CARD_TOKEN',"
                            + " ?, 'Visa', '4242', 12, 2030, 'ACTIVE', now())",
                    method, party, "tok-p8t7-" + UUID.randomUUID());
        }
        try (SecurityContext.Scope actor =
                        SecurityContext.enter(new Actor(party.toString(), ActorType.CUSTOMER));
                CorrelationContext.Scope ignored = CorrelationContext.enter(flow())) {
            runner.inTransaction(
                    uow ->
                            new AccountOpening(
                                            new JdbcCustomerAccountStore(),
                                            ledgerAccounts,
                                            new VerifiedAccountHolder(new JdbcPartyStore()),
                                            new JdbcAuditWriter(),
                                            new JdbcOutboxWriter(),
                                            IDS,
                                            CLOCK)
                                    .open(uow, party, ProductType.WALLET, EUR));
            PaymentCreation.CreationResult created =
                    runner.inTransaction(
                            uow ->
                                    new PaymentCreation(
                                                    executor(),
                                                    participants,
                                                    intents,
                                                    new JdbcAuditWriter(),
                                                    new JdbcOutboxWriter(),
                                                    IDS,
                                                    CLOCK,
                                                    PaymentCreation.IDEMPOTENCY_SCOPE)
                                            .create(
                                                    uow,
                                                    new PaymentCreation.CreatePaymentCommand(
                                                            party,
                                                            method,
                                                            AMOUNT,
                                                            "p8t7-" + UUID.randomUUID())));
            LedgerAccountId wallet =
                    runner.inTransaction(
                            uow ->
                                    intents.findById(uow, created.intent())
                                            .orElseThrow()
                                            .creditAccount());
            return new Holder(party, created.intent(), wallet);
        }
    }

    private PaymentAttemptId captureDispatched(Holder holder) {
        try (SecurityContext.Scope scope = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = CorrelationContext.enter(flow())) {
            return runner.inTransaction(
                    uow -> {
                        intents.transition(
                                uow,
                                holder.intent(),
                                PaymentIntentStatus.REQUIRES_CONFIRMATION,
                                PaymentIntentStatus.PROCESSING);
                        PaymentAttempt attempt =
                                PaymentAttempt.create(
                                        IDS,
                                        CLOCK,
                                        holder.intent(),
                                        SimulatedCardPspAdapter.RAIL.id(),
                                        new ProviderIdempotencyReference("p8t7a-" + IDS.next()));
                        attempts.insert(uow, attempt);
                        outcomes(QUIET)
                                .applyAuthorization(
                                        uow,
                                        holder.intent(),
                                        attempt.id(),
                                        PaymentAttemptStatus.AUTH_DISPATCHED,
                                        ProviderAnswer.Verdict.APPROVED,
                                        Optional.of(
                                                new ProviderReference(
                                                        "psp-auth-" + UUID.randomUUID())),
                                        AMOUNT,
                                        flow());
                        attempts.dispatchCapture(
                                uow,
                                attempt.id(),
                                new ProviderIdempotencyReference("p8t7c-" + IDS.next()));
                        return attempt.id();
                    });
        }
    }

    private void applyCapture(Holder holder, PaymentAttemptId attemptId, String pspRef) {
        applyCapture(outcomes(QUIET), holder, attemptId, pspRef);
    }

    private void applyCaptureLive(Holder holder, PaymentAttemptId attemptId, String pspRef) {
        applyCapture(outcomes(liveRecorder), holder, attemptId, pspRef);
    }

    private void applyCapture(
            PaymentOutcomes outcomes, Holder holder, PaymentAttemptId attemptId, String pspRef) {
        try (SecurityContext.Scope scope = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = CorrelationContext.enter(flow())) {
            runner.inTransaction(
                    uow -> {
                        outcomes.applyCapture(
                                uow,
                                holder.intent(),
                                attemptId,
                                PaymentAttemptStatus.CAPTURE_DISPATCHED,
                                ProviderAnswer.Verdict.APPROVED,
                                Optional.of(new ProviderReference(pspRef)),
                                holder.wallet(),
                                AMOUNT,
                                flow());
                        return null;
                    });
        }
    }

    private Refund dispatchedRefund(Holder holder, PaymentAttemptId attemptId) {
        try (SecurityContext.Scope scope = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = CorrelationContext.enter(flow())) {
            return runner.inTransaction(
                    uow -> {
                        PaymentAttempt captured =
                                attempts.findById(uow, attemptId).orElseThrow();
                        com.finapp.ledger.Hold hold =
                                new com.finapp.ledger.HoldService(
                                                ledgerAccounts,
                                                new com.finapp.ledger.JdbcBalanceDerivation(),
                                                new com.finapp.ledger.JdbcHoldStore(),
                                                new JdbcBalanceProjection(),
                                                new JdbcAuditWriter(),
                                                new JdbcOutboxWriter(),
                                                IDS,
                                                CLOCK)
                                        .place(uow, holder.wallet(), AMOUNT);
                        Refund fresh =
                                Refund.create(
                                        IDS,
                                        CLOCK,
                                        captured,
                                        AMOUNT,
                                        Money.ofMinorUnits(0, EUR),
                                        "opening suite refund",
                                        hold.id(),
                                        new ProviderIdempotencyReference("rfd-" + IDS.next()));
                        new com.finapp.payments.JdbcRefundStore()
                                .insert(uow, fresh, "p8t7-rfd-" + UUID.randomUUID());
                        return fresh;
                    });
        }
    }

    private void applyRefund(Holder holder, Refund refund, String pspRefundRef) {
        try (SecurityContext.Scope scope = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = CorrelationContext.enter(flow())) {
            runner.inTransaction(
                    uow -> {
                        outcomes(QUIET)
                                .applyRefund(
                                        uow,
                                        holder.intent(),
                                        refund,
                                        RefundStatus.DISPATCHED,
                                        ProviderAnswer.Verdict.APPROVED,
                                        Optional.of(new ProviderReference(pspRefundRef)),
                                        holder.wallet(),
                                        flow());
                        return null;
                    });
        }
    }

    private PaymentOutcomes outcomes(SettlementExpectations expectations) {
        PostingService postings =
                new PostingService(
                        executor(),
                        new JdbcJournalEntryStore(IDS),
                        new JdbcAuditWriter(),
                        new JdbcOutboxWriter(),
                        new JdbcBalanceProjection(),
                        IDS,
                        CLOCK,
                        PostingObserver.NONE);
        return new PaymentOutcomes(
                intents,
                attempts,
                new com.finapp.payments.JdbcRefundStore(),
                new com.finapp.ledger.HoldService(
                        ledgerAccounts,
                        new com.finapp.ledger.JdbcBalanceDerivation(),
                        new com.finapp.ledger.JdbcHoldStore(),
                        new JdbcBalanceProjection(),
                        new JdbcAuditWriter(),
                        new JdbcOutboxWriter(),
                        IDS,
                        CLOCK),
                postings,
                new ChartOfAccounts<>(ledgerAccounts),
                new com.finapp.app.merchant.MerchantBoundCaptureComposition(
                        new com.finapp.merchant.MerchantSettlement(
                                new com.finapp.merchant.JdbcPaymentFeePinStore(),
                                new com.finapp.merchant.JdbcFeeScheduleStore(),
                                ledgerAccounts,
                                new ChartOfAccounts<>(ledgerAccounts),
                                new JdbcOutboxWriter(),
                                IDS),
                        new com.finapp.payments.WalletTopUpComposition(),
                        landed -> {},
                        new com.finapp.app.telemetry.MerchantMeters(
                                new io.micrometer.core.instrument.simple
                                        .SimpleMeterRegistry())),
                new com.finapp.app.merchant.MerchantBoundRefundComposition(
                        new com.finapp.merchant.MerchantSettlement(
                                new com.finapp.merchant.JdbcPaymentFeePinStore(),
                                new com.finapp.merchant.JdbcFeeScheduleStore(),
                                ledgerAccounts,
                                new ChartOfAccounts<>(ledgerAccounts),
                                new JdbcOutboxWriter(),
                                IDS),
                        new com.finapp.payments.WalletRefundComposition()),
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(),
                IDS,
                CLOCK,
                PaymentRails.of(List.of(SimulatedCardPspAdapter.RAIL, BookRail.RAIL)),
                new com.finapp.payments.JdbcUnmatchedConfirmationStore(),
                ledgerAccounts,
                ChargebackAccountingFixture.over(
                        postings,
                        PaymentRails.of(List.of(SimulatedCardPspAdapter.RAIL, BookRail.RAIL)),
                        IDS,
                        CLOCK),
                com.finapp.payments.RailOutcomeObserver.NONE,
                new com.finapp.payments.JdbcSchemeExecutionClaimStore(),
                expectations);
    }

    private static IdempotentExecutor executor() {
        return new IdempotentExecutor(
                new JdbcIdempotencyRecordStore(), CLOCK, Duration.ofDays(1),
                Duration.ofMinutes(5));
    }

    private static Correlation flow() {
        return Correlation.startingWith(CorrelationId.of("p8t7-" + UUID.randomUUID()))
                .causing(CausationId.of("p8t7-cause"));
    }

    // ----------------------------------------------------------------- the doors

    private record Session(IdentityId identity, String token) {}

    private Session controllerSession() throws SQLException {
        return sessionWith(RoleName.RECONCILIATION_CONTROLLER);
    }

    private Session operatorSession() throws SQLException {
        return sessionWith(RoleName.RECONCILIATION_OPERATOR);
    }

    private Session rolelessSession() throws SQLException {
        IdentityId identity = givenAnIdentity();
        return new Session(identity, givenASessionFor(identity));
    }

    private Session sessionWith(RoleName role) throws SQLException {
        IdentityId identity = givenAnIdentity();
        try (CorrelationContext.Scope correlation = CorrelationContext.enter(flow());
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, identity, role, identity, "test fixture");
            app.commit();
        }
        return new Session(identity, givenASessionFor(identity));
    }

    private static IdentityId givenAnIdentity() throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Reconciliation Person', now())",
                    party);
            execute(app,
                    "INSERT INTO identity.identity (id, party_id, login_identifier, status,"
                            + " created_at, status_changed_at)"
                            + " VALUES (?, ?, ?, 'ACTIVE', now(), now())",
                    identity,
                    party,
                    "rc" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        }
        return IdentityId.of(identity);
    }

    private String givenASessionFor(IdentityId identity) throws SQLException {
        byte[] bytes = new byte[32];
        RANDOMNESS.nextBytes(bytes);
        String plaintext =
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        com.finapp.identity.Session session =
                com.finapp.identity.Session.issue(
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

    private HttpResponse<String> backfill(String token, String key, String reason)
            throws Exception {
        return backfillBody(token, key, "{\"reason\":\"" + reason + "\"}");
    }

    private HttpResponse<String> backfillBody(String token, String key, String body)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://localhost:" + port
                                                + "/v1/operator/reconciliation/opening-position"))
                        .header("Content-Type", "application/json")
                        .header("Idempotency-Key", key)
                        .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> positionsReport(String token) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://localhost:" + port
                                                + "/v1/operator/reports/reconciliation"
                                                + "/positions"))
                        .GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    // ----------------------------------------------------------------- readers

    private PositionProof.Report sweep() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            app.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                PositionProof.Report report = positionProof.sweep(app);
                app.commit();
                return report;
            } catch (RuntimeException failure) {
                app.rollback();
                throw failure;
            }
        }
    }

    private static long expectationCount() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            return count(app, "SELECT count(*) FROM reconciliation.expectation");
        }
    }

    private static UUID clearingAccountId(Connection app, String currency)
            throws SQLException {
        return accountIdOf(app, "SETTLEMENT_CLEARING", currency);
    }

    private static UUID feeAccountId(Connection app, String currency) throws SQLException {
        return accountIdOf(app, "FEE_REVENUE", currency);
    }

    private static UUID accountIdOf(Connection app, String purpose, String currency)
            throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT id FROM ledger.ledger_account WHERE purpose = ? AND"
                                + " currency = ?")) {
            read.setString(1, purpose);
            read.setString(2, currency);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getObject(1, UUID.class);
            }
        }
    }

    private static long count(Connection app, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement read = app.prepareStatement(sql)) {
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

    /** One transaction per act, committed — the appliers run on the caller's connection. */
    private static final class Runner implements TransactionRunner {
        @Override
        public <R> R inTransaction(java.util.function.Function<Connection, R> work) {
            try (Connection connection = DatabaseRoles.application()) {
                connection.setAutoCommit(false);
                try {
                    R result = work.apply(connection);
                    connection.commit();
                    return result;
                } catch (RuntimeException failure) {
                    connection.rollback();
                    throw failure;
                }
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
        }
    }
}
