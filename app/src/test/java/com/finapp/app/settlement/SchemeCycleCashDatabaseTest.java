package com.finapp.app.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.reconciliation.PositionProof;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcSessionStore;
import com.finapp.identity.RoleName;
import com.finapp.identity.SessionPolicy;
import com.finapp.identity.SessionStore;
import com.finapp.identity.SessionToken;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingService;
import com.finapp.ledger.TrialBalance;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.reconciliation.ExpectationDirection;
import com.finapp.reconciliation.ExpectationKind;
import com.finapp.reconciliation.ExpectationRegister;
import com.finapp.reconciliation.InternalClassification;
import com.finapp.reconciliation.InternalReferenceLookup;
import com.finapp.reconciliation.InternalSubject;
import com.finapp.reconciliation.KeyKind;
import com.finapp.reconciliation.Matching;
import com.finapp.reconciliation.NewExpectation;
import com.finapp.reconciliation.RuleSets;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * The instant scheme's cycle report over the REAL composition (`P8-TSK-017`, ADR-0065): pay-ins,
 * withdrawals and returns seeded WITH their postings and expectations, the scheme's cycle report
 * accepted (its fees recognised against the rail's declared position, its net expected as a
 * {@code REMITTANCE}), matched per cycle — the return LEARNING its cycle, a shifted pay-in a
 * zero-value timing — and discharged to cash by the bank's statement, each operation reading
 * {@code CASH_CONFIRMED} over the wire; a net-receivable cycle debits cash and a net-payable one
 * credits it.
 *
 * <p><strong>Runs in its own container</strong>: the bank's statement chain is one sequence per
 * currency for the whole database, and `P8-TSK-016`'s cash suite owns each currency's first
 * statement in the proof container. The identity assertions are still baseline-relative — the
 * flow moves {@code INSTANT_CLEARING}'s identity by exactly nothing.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("the instant scheme's cycle report: allocated per cycle, cash confirmed (P8-TSK-017)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class SchemeCycleCashDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode GBP = CurrencyCode.of("GBP");
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final String SCHEME_SOURCE = "simulated-scheme.cycle-report";
    private static final String BANK_SOURCE = "simulated-bank.statement";
    private static final LocalDate EXECUTED_ON = LocalDate.parse("2026-09-24");

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private FileReception<Connection> reception;
    @Autowired private FileParsing parsing;
    @Autowired private BatchAcceptance acceptance;
    @Autowired private Matching matching;
    @Autowired private PositionProof proof;
    @Autowired private PostingService postingService;
    @Autowired private LedgerAccountStore<Connection> ledgerAccountStore;
    @Autowired private ExpectationRegister expectationRegister;
    @Autowired private RuleSets ruleSets;
    @Autowired private SettlementFileStore<Connection> settlementFileStore;
    @Autowired private TransactionRunner settlementTransactionRunner;
    @Autowired private InternalReferenceLookup internalReferenceLookup;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SessionStore<Connection> sessions = new JdbcSessionStore();

    // ----------------------------------------------------------------- a receivable cycle

    @Test
    @Order(1)
    @DisplayName("a net-receivable cycle: a pay-in, a withdrawal and a return allocated per cycle,"
            + " the scheme's fees recognised against its declared position, the return LEARNING its"
            + " cycle, the net discharged by a bank CREDIT - each operation CASH_CONFIRMED")
    void aReceivableCycleEndsCashConfirmed() throws Exception {
        String marker = marker();
        // The simulated scheme's shape, a letter before the random marker: the format's classes
        // join digits across single dashes (a card number written with dashes), so a date running
        // straight into a digit-led marker would read as a card-length run - refused, and flaky.
        String cycle = "CYC-2026-09-25-C" + marker;
        Money baseline = unexplained(sweep(), AccountPurpose.INSTANT_CLEARING, EUR);
        LedgerAccount wallet = wallet(EUR);

        String payIn = "op-pi-" + UUID.randomUUID();
        String withdrawal = "op-wd-" + UUID.randomUUID();
        String returned = "op-rt-" + UUID.randomUUID();
        seedCompletion(ExpectationKind.PUSH_PAY_IN, payIn, wallet, 25_00, EUR, Optional.of(cycle),
                List.of(key(KeyKind.SCHEME_REF, "SCH-PI-" + marker),
                        key(KeyKind.END_TO_END_REF, "E2E-PI-" + marker)));
        seedCompletion(ExpectationKind.PUSH_WITHDRAWAL, withdrawal, wallet, 10_00, EUR,
                Optional.of(cycle),
                List.of(key(KeyKind.SCHEME_REF, "SCH-WD-" + marker),
                        key(KeyKind.END_TO_END_REF, "E2E-WD-" + marker)));
        seedCompletion(ExpectationKind.PUSH_RETURN, returned, wallet, 5_00, EUR, Optional.empty(),
                List.of(key(KeyKind.SCHEME_REF, "SCH-RT-" + marker),
                        key(KeyKind.OUR_REF, "RTN-" + marker)));

        String remittanceRef = "SCH-REM-17" + digits();
        UUID report =
                acceptedBatch(SCHEME_SOURCE,
                        new SimulatedSchemeReports(cycle, "EUR", "2026-09-25", remittanceRef)
                                .with(SimulatedSchemeReports.Entry.payIn(
                                        "25.00", "0.10", "SCH-PI-" + marker, "E2E-PI-" + marker))
                                .with(SimulatedSchemeReports.Entry.withdrawal(
                                        "10.00", "0.10", "SCH-WD-" + marker, "E2E-WD-" + marker))
                                .with(SimulatedSchemeReports.Entry.returned(
                                        "5.00", "0.10", "SCH-RT-" + marker, "RTN-" + marker))
                                .render());
        assertThat(entryLines((UUID) one("SELECT journal_entry_id FROM settlement.batch WHERE"
                        + " id = ?", report)))
                .as("hop 1 posts only the scheme's fees, against the rail's declared position")
                .containsExactlyInAnyOrder(
                        "PROCESSING_COSTS:DEBIT:30", "INSTANT_CLEARING:CREDIT:30");
        assertThat(one("SELECT r.settlement_cycle FROM reconciliation.reconciliation_batch r"
                        + " WHERE r.batch_id = ?", report))
                .as("the cycle rides to the run, frozen")
                .isEqualTo(cycle);
        assertThat(one("SELECT direction || ':' || amount_minor::text || ':' || position_purpose"
                        + " FROM reconciliation.expectation WHERE kind = 'REMITTANCE' AND"
                        + " operation_ref = ?", report.toString()))
                .isEqualTo("INBOUND:970:INSTANT_CLEARING");

        matchUntilQuiet();
        for (String operation : List.of(payIn, withdrawal, returned)) {
            assertThat(one("SELECT status FROM reconciliation.expectation WHERE operation_ref = ?",
                            operation))
                    .as("%s allocated per cycle", operation)
                    .isEqualTo("SETTLED");
        }
        assertThat(one("SELECT i.learned_cycle FROM reconciliation.external_item i JOIN"
                        + " reconciliation.allocation a ON a.external_item_id = i.id JOIN"
                        + " reconciliation.expectation e ON e.id = a.expectation_id WHERE"
                        + " e.operation_ref = ?", returned))
                .as("the return announced no cycle: it learned the report's (ADR-0062)")
                .isEqualTo(cycle);
        assertThat(one("SELECT i.learned_cycle IS NULL FROM reconciliation.external_item i JOIN"
                        + " reconciliation.allocation a ON a.external_item_id = i.id JOIN"
                        + " reconciliation.expectation e ON e.id = a.expectation_id WHERE"
                        + " e.operation_ref = ?", payIn))
                .as("a pay-in announced its cycle: nothing to learn")
                .isEqualTo(true);
        assertThat(count("SELECT count(*) FROM reconciliation.external_item i JOIN"
                        + " reconciliation.reconciliation_batch r ON r.id = i.run_id WHERE"
                        + " r.batch_id = ? AND i.line_type = 'SCHEME_FEE' AND i.status ="
                        + " 'CHECKED'", report))
                .as("each fee checked against the pinned 0.10, reached through its original")
                .isEqualTo(3);
        assertThat(count("SELECT count(*) FROM reconciliation.break b JOIN"
                        + " reconciliation.match_decision d ON d.id = b.decision_id JOIN"
                        + " reconciliation.reconciliation_batch r ON r.id = d.run_id WHERE"
                        + " r.batch_id = ? AND b.type = 'TIMING_DIFFERENCE'", report))
                .as("every line settled in the cycle its completion announced")
                .isZero();

        UUID statement =
                acceptedBatch(BANK_SOURCE,
                        statement("SB-EUR-1-" + marker, "EUR", 1, "C,2026-09-26,EUR,0.00",
                                "C,2026-09-27,EUR,9.70",
                                ":61:2026-09-27,C,9.70," + remittanceRef).getBytes(
                                StandardCharsets.UTF_8));
        assertThat(entryLines((UUID) one("SELECT journal_entry_id FROM settlement.batch WHERE"
                        + " id = ?", statement)))
                .as("a net-receivable cycle DEBITS cash against the scheme's clearing")
                .containsExactlyInAnyOrder("CASH_AT_BANK:DEBIT:970", "INSTANT_CLEARING:CREDIT:970");
        matchUntilQuiet();
        assertThat(one("SELECT status FROM reconciliation.expectation WHERE kind = 'REMITTANCE'"
                        + " AND operation_ref = ?", report.toString()))
                .isEqualTo("SETTLED");

        Session operator = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        for (Map.Entry<String, String> operation :
                Map.of("PUSH_PAY_IN", payIn, "PUSH_WITHDRAWAL", withdrawal,
                        "PUSH_RETURN", returned).entrySet()) {
            HttpResponse<String> status =
                    get(operator.token(), "/v1/operator/reconciliation/settlement-status?kind="
                            + operation.getKey() + "&operationRef=" + operation.getValue());
            assertThat(status.statusCode()).isEqualTo(200);
            assertThat(status.body())
                    .as("%s over the wire", operation.getKey())
                    .contains("\"status\":\"CASH_CONFIRMED\"");
        }

        PositionProof.Report proven = sweep();
        assertThat(unexplained(proven, AccountPurpose.INSTANT_CLEARING, EUR))
                .as("the scheme's clearing identity untouched by the whole cycle")
                .isEqualTo(baseline);
        assertThat(proven.cashOf(EUR).orElseThrow().explained()).isTrue();
        assertThat(proven.unattributedByPurpose().getOrDefault(AccountPurpose.INSTANT_CLEARING, 0L))
                .isZero();
        assertThat(proven.unattributedByPurpose().getOrDefault(AccountPurpose.PROCESSING_COSTS, 0L))
                .isZero();
        assertTrialBalance();
    }

    // ----------------------------------------------------------------- a payable, shifted cycle

    @Test
    @Order(2)
    @DisplayName("a net-payable cycle credits cash, and a pay-in the report settles in a cycle"
            + " other than the one it announced still allocates - with a zero-value"
            + " TIMING_DIFFERENCE (CYCLE_MISMATCH)")
    void aPayableShiftedCycle() throws Exception {
        String marker = marker();
        String announced = "CYC-2026-09-24-C" + marker;
        String settled = "CYC-2026-09-25-C" + marker;
        Money baseline = unexplained(sweep(), AccountPurpose.INSTANT_CLEARING, GBP);
        LedgerAccount wallet = wallet(GBP);
        String payIn = "op-pi-" + UUID.randomUUID();
        String withdrawal = "op-wd-" + UUID.randomUUID();
        seedCompletion(ExpectationKind.PUSH_PAY_IN, payIn, wallet, 1_00, GBP,
                Optional.of(announced),
                List.of(key(KeyKind.SCHEME_REF, "SCH-PI-" + marker),
                        key(KeyKind.END_TO_END_REF, "E2E-PI-" + marker)));
        seedCompletion(ExpectationKind.PUSH_WITHDRAWAL, withdrawal, wallet, 1_00, GBP,
                Optional.of(settled),
                List.of(key(KeyKind.SCHEME_REF, "SCH-WD-" + marker),
                        key(KeyKind.END_TO_END_REF, "E2E-WD-" + marker)));

        String remittanceRef = "SCH-REM-17" + digits();
        UUID report =
                acceptedBatch(SCHEME_SOURCE,
                        new SimulatedSchemeReports(settled, "GBP", "2026-09-25", remittanceRef)
                                .with(SimulatedSchemeReports.Entry.payIn(
                                        "1.00", "0.10", "SCH-PI-" + marker, "E2E-PI-" + marker))
                                .with(SimulatedSchemeReports.Entry.withdrawal(
                                        "1.00", "0.10", "SCH-WD-" + marker, "E2E-WD-" + marker))
                                .render());
        assertThat(one("SELECT direction || ':' || amount_minor::text FROM"
                        + " reconciliation.expectation WHERE kind = 'REMITTANCE' AND operation_ref"
                        + " = ?", report.toString()))
                .as("the fees make the cycle net-payable: the platform owes the scheme")
                .isEqualTo("OUTBOUND:20");

        matchUntilQuiet();
        assertThat(one("SELECT status FROM reconciliation.expectation WHERE operation_ref = ?",
                        payIn))
                .isEqualTo("SETTLED");
        assertThat(one("SELECT b.cause || ':' || b.value_at_issue_minor::text FROM"
                        + " reconciliation.break b JOIN reconciliation.match_decision d ON d.id ="
                        + " b.decision_id JOIN reconciliation.allocation a ON a.decision_id = d.id"
                        + " JOIN reconciliation.expectation e ON e.id = a.expectation_id WHERE"
                        + " e.operation_ref = ? AND b.type = 'TIMING_DIFFERENCE'", payIn))
                .as("the money matched; only the cycle differs")
                .isEqualTo("CYCLE_MISMATCH:0");

        UUID statement =
                acceptedBatch(BANK_SOURCE,
                        statement("SB-GBP-1-" + marker, "GBP", 1, "C,2026-09-26,GBP,0.00",
                                "D,2026-09-27,GBP,0.20",
                                ":61:2026-09-27,D,0.20," + remittanceRef).getBytes(
                                StandardCharsets.UTF_8));
        assertThat(entryLines((UUID) one("SELECT journal_entry_id FROM settlement.batch WHERE"
                        + " id = ?", statement)))
                .as("a net-payable cycle CREDITS cash, debiting the scheme's clearing")
                .containsExactlyInAnyOrder("CASH_AT_BANK:CREDIT:20", "INSTANT_CLEARING:DEBIT:20");
        matchUntilQuiet();
        assertThat(one("SELECT status FROM reconciliation.expectation WHERE kind = 'REMITTANCE'"
                        + " AND operation_ref = ?", report.toString()))
                .isEqualTo("SETTLED");

        PositionProof.Report proven = sweep();
        assertThat(unexplained(proven, AccountPurpose.INSTANT_CLEARING, GBP)).isEqualTo(baseline);
        PositionProof.CashVerdict cash = proven.cashOf(GBP).orElseThrow();
        assertThat(cash.explained()).isTrue();
        assertThat(cash.chainClosing().minorUnits()).isEqualTo(-20);
        assertTrialBalance();
    }

    // ----------------------------------------------------------------- the lookup's rail

    @Test
    @Order(3)
    @DisplayName("a scheme reference resolves through the REAL claim store under the rail the"
            + " source register composes - reconciliation names no rail, and a source with no"
            + " rail resolves nothing")
    void theLookupReadsTheRailOffTheRegister() throws Exception {
        String schemeRef = "SCH-CLAIM-" + marker();
        UUID schemeSource;
        UUID bankSource;
        try (Connection app = DatabaseRoles.application()) {
            schemeSource = settlementFileStore.sourceByCode(app, SCHEME_SOURCE).orElseThrow().id();
            bankSource = settlementFileStore.sourceByCode(app, BANK_SOURCE).orElseThrow().id();
            execute(app,
                    "INSERT INTO payments.scheme_execution_claim (rail, scheme_reference,"
                            + " subject_kind, subject_id, claimed_at) VALUES (?, ?, 'PAY_IN', ?,"
                            + " now())",
                    com.finapp.payments.SimulatedInstantSchemeAdapter.RAIL.id().value(),
                    schemeRef, IDS.next());
        }
        try (Connection app = DatabaseRoles.application()) {
            InternalReferenceLookup.InternalReference claimed =
                    internalReferenceLookup.classify(
                            app,
                            new InternalReferenceLookup.LookupSubject(
                                    Optional.empty(), Map.of(KeyKind.SCHEME_REF, schemeRef),
                                    Optional.of(schemeSource)));
            assertThat(claimed.classification())
                    .as("a claimed execution is COMPLETED - after grace, MISSING_INTERNAL, never"
                            + " UNKNOWN_EXTERNAL")
                    .isEqualTo(InternalClassification.COMPLETED);
            assertThat(claimed.subject()).contains(InternalSubject.PAYMENT_ATTEMPT);

            assertThat(internalReferenceLookup.classify(
                                    app,
                                    new InternalReferenceLookup.LookupSubject(
                                            Optional.empty(),
                                            Map.of(KeyKind.SCHEME_REF, schemeRef),
                                            Optional.of(bankSource)))
                            .classification())
                    .as("the bank settles no rail: nothing to claim under")
                    .isEqualTo(InternalClassification.UNKNOWN);
        }
    }

    // ----------------------------------------------------------------- ten uploads

    @Test
    @Order(4)
    @DisplayName("the same cycle report delivered ten ways at once: one file, one batch, one run,"
            + " one recognition entry")
    void tenDeliveriesOfOneReport() throws Exception {
        String marker = marker();
        byte[] bytes =
                new SimulatedSchemeReports("CYC-2026-09-25-C" + marker, "USD", "2026-09-25",
                        "SCH-REM-17" + digits())
                        .with(SimulatedSchemeReports.Entry.payIn(
                                "1.00", "0.10", "SCH-TEN-" + marker, "E2E-TEN-" + marker))
                        .render();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<FileReception.Result>> deliveries = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            deliveries.add(pool.submit(() -> {
                start.await();
                return receive(SCHEME_SOURCE, bytes);
            }));
        }
        start.countDown();
        List<FileReception.Result> results = new ArrayList<>();
        for (Future<FileReception.Result> delivery : deliveries) {
            results.add(delivery.get(2, TimeUnit.MINUTES));
        }
        assertThat(results.stream().filter(FileReception.Result.New.class::isInstance).count())
                .as("the content address: one file")
                .isEqualTo(1);
        UUID fileId = results.stream()
                .filter(FileReception.Result.New.class::isInstance)
                .map(result -> ((FileReception.Result.New) result).fileId())
                .findFirst()
                .orElseThrow();

        List<Future<?>> racers = new ArrayList<>();
        CountDownLatch go = new CountDownLatch(1);
        for (int i = 0; i < 10; i++) {
            racers.add(pool.submit(() -> {
                go.await();
                parsing.sweep();
                acceptance.sweep();
                return null;
            }));
        }
        go.countDown();
        for (Future<?> racer : racers) {
            racer.get(3, TimeUnit.MINUTES);
        }
        pool.shutdown();
        assertThat(one("SELECT status FROM settlement.file WHERE id = ?", fileId))
                .isEqualTo("ACCEPTED");
        assertThat(count("SELECT count(*) FROM settlement.batch WHERE file_id = ?", fileId))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.reconciliation_batch r JOIN"
                        + " settlement.batch b ON b.id = r.batch_id WHERE b.file_id = ?", fileId))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry e JOIN settlement.batch b ON"
                        + " e.id = b.journal_entry_id WHERE b.file_id = ?", fileId))
                .isEqualTo(1);
    }

    // ----------------------------------------------------------------- seeding

    /**
     * A push completion seeded WITH its posting (DR the rail's clearing for money in, CR it for
     * money out, the wallet the other side) and its expectation naming that line - the clearing
     * identity holds at every step.
     */
    private void seedCompletion(
            ExpectationKind kind,
            String operationRef,
            LedgerAccount wallet,
            long minor,
            CurrencyCode currency,
            Optional<String> announcedCycle,
            List<NewExpectation.ExpectationKey> keys) throws Exception {
        boolean inbound = kind == ExpectationKind.PUSH_PAY_IN;
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(flow());
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            LedgerAccount clearing =
                    ledgerAccountStore
                            .findOperational(app, AccountPurpose.INSTANT_CLEARING, currency)
                            .orElseThrow();
            Money amount = Money.ofPersisted(minor, currency, 2);
            UUID entry =
                    postingService
                            .post(
                                    app,
                                    new PostingCommand(
                                            "push-completion:" + operationRef,
                                            EXECUTED_ON,
                                            EXECUTED_ON,
                                            operationRef,
                                            inbound
                                                    ? List.of(
                                                            new JournalLine(clearing.id(),
                                                                    Direction.DEBIT, amount),
                                                            new JournalLine(wallet.id(),
                                                                    Direction.CREDIT, amount))
                                                    : List.of(
                                                            new JournalLine(wallet.id(),
                                                                    Direction.DEBIT, amount),
                                                            new JournalLine(clearing.id(),
                                                                    Direction.CREDIT, amount))))
                            .entryId()
                            .value();
            UUID source = settlementFileStore.sourceByCode(app, SCHEME_SOURCE).orElseThrow().id();
            expectationRegister.open(
                    app,
                    new NewExpectation(
                            kind, operationRef, "push-completion:" + operationRef, source,
                            AccountPurpose.INSTANT_CLEARING, clearing.id().value(),
                            inbound ? ExpectationDirection.INBOUND : ExpectationDirection.OUTBOUND,
                            amount, Optional.of(entry), EXECUTED_ON, announcedCycle,
                            EXECUTED_ON.plusDays(1), ruleSets.activeFor(app, source).id(), keys,
                            Actor.SYSTEM, Instant.now(CLOCK), CorrelationId.generate(IDS)));
            app.commit();
        }
    }

    private LedgerAccount wallet(CurrencyCode currency) throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            LedgerAccount wallet =
                    ledgerAccountStore
                            .createOrConverge(
                                    app,
                                    LedgerAccount.owned(
                                            IDS, CLOCK, AccountType.LIABILITY,
                                            AccountPurpose.CUSTOMER_WALLET, currency, IDS.next()))
                            .account();
            app.commit();
            return wallet;
        }
    }

    private static NewExpectation.ExpectationKey key(KeyKind kind, String value) {
        return new NewExpectation.ExpectationKey(kind, value);
    }

    private static String statement(
            String reference, String currency, long sequence, String opening, String closing,
            String... body) {
        StringBuilder text =
                new StringBuilder()
                        .append(":20:").append(reference).append('\n')
                        .append(":25:SIMBANK-").append(currency).append("-01\n")
                        .append(":28C:").append(sequence).append('\n')
                        .append(":60F:").append(opening).append('\n');
        for (String record : body) {
            text.append(record).append('\n');
        }
        return text.append(":62F:").append(closing).append('\n').toString();
    }

    private FileReception.Result receive(String source, byte[] bytes) {
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(flow())) {
            return settlementTransactionRunner.inTransaction(
                    uow ->
                            reception.receive(
                                    uow,
                                    new FileReception.Delivery(
                                            source,
                                            DeliveryChannel.PULL,
                                            bytes,
                                            Optional.empty(),
                                            Actor.SYSTEM,
                                            SettlementAuditAction.SETTLEMENT_FILE_UPLOADED,
                                            CorrelationContext.current().orElseThrow())));
        }
    }

    private UUID acceptedBatch(String source, byte[] bytes) throws SQLException {
        FileReception.Result result = receive(source, bytes);
        assertThat(result).isInstanceOf(FileReception.Result.New.class);
        UUID fileId = ((FileReception.Result.New) result).fileId();
        parsing.sweep();
        acceptance.sweep();
        assertThat(one("SELECT status || ':' || coalesce(rejection_code, '-') FROM"
                        + " settlement.file WHERE id = ?", fileId))
                .isEqualTo("ACCEPTED:-");
        return (UUID) one("SELECT id FROM settlement.batch WHERE file_id = ?", fileId);
    }

    private void matchUntilQuiet() {
        for (int i = 0; i < 4; i++) {
            matching.sweep();
        }
    }

    private static List<String> entryLines(UUID entryId) throws SQLException {
        List<String> lines = new ArrayList<>();
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT a.purpose, l.direction, l.amount_minor FROM"
                                        + " ledger.journal_line l JOIN ledger.ledger_account a"
                                        + " ON a.id = l.ledger_account_id WHERE"
                                        + " l.entry_id = ?")) {
            read.setObject(1, entryId);
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    lines.add(rows.getString(1) + ":" + rows.getString(2) + ":"
                            + rows.getLong(3));
                }
            }
        }
        return lines;
    }

    private PositionProof.Report sweep() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            app.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                return proof.sweep(app);
            } finally {
                app.rollback();
            }
        }
    }

    private static void assertTrialBalance() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            app.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            assertThat(new TrialBalance().sweep(app).outOfBalance())
                    .as("the trial balance holds (INV-ACC-01)")
                    .isEmpty();
            app.rollback();
        }
    }

    /** The identity's difference: balance − (open remainders − open items). */
    private static Money unexplained(
            PositionProof.Report report, AccountPurpose purpose, CurrencyCode currency) {
        PositionProof.PositionVerdict verdict =
                report.verdicts().stream()
                        .filter(v -> v.purpose() == purpose && v.currency().equals(currency))
                        .findFirst()
                        .orElseThrow();
        return verdict.ledgerBalance()
                .minus(verdict.openRemainders().minus(verdict.openItems()));
    }

    // ----------------------------------------------------------------- the doors

    private HttpResponse<String> get(String token, String path) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private record Session(IdentityId identity, String token) {}

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
                            + " VALUES (?, 'PERSON', 'Scheme Desk Person', now())",
                    party);
            execute(app,
                    "INSERT INTO identity.identity (id, party_id, login_identifier, status,"
                            + " created_at, status_changed_at) VALUES (?, ?, ?, 'ACTIVE',"
                            + " now(), now())",
                    identity, party,
                    "sc" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        }
        return IdentityId.of(identity);
    }

    private String givenASessionFor(IdentityId identity) throws SQLException {
        byte[] bytes = new byte[32];
        RANDOMNESS.nextBytes(bytes);
        String plaintext = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        com.finapp.identity.Session session =
                com.finapp.identity.Session.issue(
                        IDS, CLOCK, identity, SessionToken.of(plaintext),
                        AssuranceLevel.PASSWORD, SessionPolicy.current());
        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, session);
        }
        return plaintext;
    }

    // ----------------------------------------------------------------- plumbing

    private static Correlation flow() {
        return Correlation.startingWith(CorrelationId.of("p8t17-" + UUID.randomUUID()))
                .causing(CausationId.of("p8t17-cause"));
    }

    private static String marker() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    private static String digits() {
        return String.valueOf(10_000_000 + RANDOMNESS.nextInt(89_999_999));
    }

    private static void execute(Connection connection, String sql, Object... args)
            throws SQLException {
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
