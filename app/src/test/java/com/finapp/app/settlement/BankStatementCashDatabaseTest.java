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
import com.finapp.reconciliation.KeyKind;
import com.finapp.reconciliation.Matching;
import com.finapp.reconciliation.NewExpectation;
import com.finapp.reconciliation.RuleSets;
import com.finapp.settlement.BatchAcceptance;
import com.finapp.settlement.DeliveryChannel;
import com.finapp.settlement.FileParsing;
import com.finapp.settlement.FileReception;
import com.finapp.settlement.RefusalReason;
import com.finapp.settlement.SettlementAuditAction;
import com.finapp.settlement.SettlementFileStore;
import com.finapp.settlement.TransactionRunner;
import com.finapp.sharedkernel.correlation.CausationId;
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
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Hop 2 over the REAL composition (`P8-TSK-016`, ADR-0065 §3, {@code INV-SET-06}): a capture's
 * clearing line, the PSP's report that owes it, and the bank's own statement that pays it — the
 * one way cash moves on the books — ending {@code CASH_CONFIRMED} over the wire with the cash
 * proof holding; then the chain's failure modes (a gap filled {@code EVIDENCED}, a non-zero first
 * opening, unattributed cash owned from its transaction), ten racing acceptors of competing
 * statements, and the IBAN needle refused at the door leaving metadata only.
 *
 * <p>Chain discipline: bank statements are this suite's alone in the shared container, and each
 * currency's chain is used by exactly one ordered step — EUR by the confirmed flow and the storm,
 * GBP by the gap, USD by the opening balance (which leaves USD's cash proof failing, loudly and
 * truthfully, for the rest of the run). The capture is seeded WITH its posting, so the clearing
 * identity holds at every step.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.junit.jupiter.api.extension.ExtendWith(OutputCaptureExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("the bank statement: cash recognised, remittances matched (P8-TSK-016)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class BankStatementCashDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode GBP = CurrencyCode.of("GBP");
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final String PSP_SOURCE = "simulated-psp.settlement";
    private static final String BANK_SOURCE = "simulated-bank.statement";
    private static final LocalDate CAPTURED_ON = LocalDate.parse("2026-09-24");

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
    @Autowired private MeterRegistry meterRegistry;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SessionStore<Connection> sessions = new JdbcSessionStore();

    // ----------------------------------------------------------------- the confirmed flow

    @Test
    @Order(1)
    @DisplayName("capture -> PSP report -> bank statement -> matcher: the remittance settles by"
            + " REMITTANCE_REF, the operation reads CASH_CONFIRMED over the wire, and the cash"
            + " proof holds - CASH_AT_BANK is the statement's closing")
    void aCaptureEndsCashConfirmed() throws Exception {
        String marker = marker();
        String captureRef = "PSP-CAP-" + marker;
        String remittanceRef = "PSP-REM-16" + digits();
        String operationRef = "op-p8t16-" + UUID.randomUUID();
        // The clearing identity's standing difference BEFORE this flow: a shared container may
        // carry another suite's residue, so what this flow proves is that it moves the
        // difference by exactly nothing (the P8-TSK-015 precedent).
        Money baseline = unexplained(sweep(), AccountPurpose.SETTLEMENT_CLEARING, EUR);
        seedCapture(operationRef, captureRef, 100_00);

        // Hop 1: the PSP says it owes 98.25 (100.00 less its 1.75, the pinned schedule's own).
        UUID report =
                acceptedBatch(
                        PSP_SOURCE,
                        "H,SIM_PSP_CSV,1,PSPB-P8T16-" + marker + ",EUR,2026-09-25\n"
                                + "D,1,SALE,100.00,1.75,EUR,2026-09-25,,," + captureRef
                                + ",,,ORD-" + marker + ",Sale\n"
                                + "T,1,98.25," + remittanceRef + "\n");
        matchUntilQuiet();
        UUID remittance =
                (UUID) one("SELECT id FROM reconciliation.expectation WHERE kind = 'REMITTANCE'"
                        + " AND operation_ref = ?", report.toString());
        assertThat(one("SELECT status FROM reconciliation.expectation WHERE kind ="
                        + " 'CARD_CAPTURE' AND operation_ref = ?", operationRef))
                .as("the report's capture line settled the capture")
                .isEqualTo("SETTLED");
        assertThat(one("SELECT status FROM reconciliation.expectation WHERE id = ?", remittance))
                .isEqualTo("OPEN");
        Session operator = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        HttpResponse<String> reported =
                get(operator.token(), "/v1/operator/reconciliation/settlement-status?kind="
                        + "CARD_CAPTURE&operationRef=" + operationRef);
        assertThat(reported.statusCode()).isEqualTo(200);
        assertThat(reported.body())
                .as("INV-SET-01: the report alone settles the capture but confirms no cash -"
                        + " with its remittance still OPEN the operation reads REPORTED, never"
                        + " CASH_CONFIRMED")
                .contains("\"status\":\"REPORTED\"")
                .doesNotContain("CASH_CONFIRMED")
                .contains(remittance.toString());

        // Hop 2: the bank credits 98.25 by the remittance reference and charges its 0.50.
        UUID statement =
                acceptedBatch(
                        BANK_SOURCE,
                        statement("SB-EUR-1-" + marker, "EUR", 1, "C,2026-09-26,EUR,0.00",
                                "C,2026-09-27,EUR,97.75",
                                ":61:2026-09-27,C,98.25," + remittanceRef,
                                ":86:Remittance for 2026-09-25",
                                ":61:2026-09-27,F,0.50"));
        assertThat(one("SELECT statement_sequence::text || ':' || opening_minor::text || ':'"
                        + " || closing_minor::text || ':' || (remittance_reference IS NULL)::text"
                        + " FROM settlement.batch WHERE id = ?", statement))
                .isEqualTo("1:0:9775:true");
        UUID entry =
                (UUID) one("SELECT journal_entry_id FROM settlement.batch WHERE id = ?", statement);
        assertThat(entryLines(entry))
                .as("the recognition: DR cash net / CR the attributed clearing / DR the bank fee")
                .containsExactlyInAnyOrder(
                        "CASH_AT_BANK:DEBIT:9775",
                        "SETTLEMENT_CLEARING:CREDIT:9825",
                        "PROCESSING_COSTS:DEBIT:50");
        UUID bankItem =
                (UUID) one("SELECT i.id FROM reconciliation.external_item i"
                        + " JOIN reconciliation.reconciliation_batch r ON r.id = i.run_id"
                        + " WHERE r.batch_id = ? AND i.line_type = 'BANK_CREDIT'", statement);
        assertThat(one("SELECT (attributed_source_id = (SELECT id FROM settlement.source WHERE"
                        + " code = ?))::text || ':' || position_purpose FROM"
                        + " reconciliation.external_item WHERE id = ?", PSP_SOURCE, bankItem))
                .as("attributed to the PSP by its remittance pattern, in its position")
                .isEqualTo("true:SETTLEMENT_CLEARING");

        matchUntilQuiet();
        assertThat(one("SELECT status FROM reconciliation.expectation WHERE id = ?", remittance))
                .as("the bank line discharged the remittance")
                .isEqualTo("SETTLED");
        assertThat(one("SELECT d.strategy || ':' || c.key_kind FROM"
                        + " reconciliation.match_decision d JOIN reconciliation.match_candidate c"
                        + " ON c.decision_id = d.id WHERE d.external_item_id = ?", bankItem))
                .isEqualTo("ONE_TO_ONE:REMITTANCE_REF");
        assertThat(one("SELECT status FROM reconciliation.external_item WHERE line_type ="
                        + " 'BANK_FEE' AND run_id = (SELECT id FROM"
                        + " reconciliation.reconciliation_batch WHERE batch_id = ?)", statement))
                .as("the bank's fee is checked against its pinned 0.50, never allocated")
                .isEqualTo("CHECKED");

        HttpResponse<String> status =
                get(operator.token(), "/v1/operator/reconciliation/settlement-status?kind="
                        + "CARD_CAPTURE&operationRef=" + operationRef);
        assertThat(status.statusCode()).isEqualTo(200);
        assertThat(status.body())
                .contains("\"status\":\"CASH_CONFIRMED\"")
                .contains(remittance.toString())
                .contains(bankItem.toString());

        PositionProof.Report proven = sweep();
        PositionProof.CashVerdict cash = proven.cashOf(EUR).orElseThrow();
        assertThat(cash.explained())
                .as("CASH_AT_BANK %s = the unbroken chain's closing %s", cash.ledgerBalance(),
                        cash.chainClosing())
                .isTrue();
        assertThat(cash.chainClosing().minorUnits()).isEqualTo(97_75);
        assertThat(cash.latestSequence()).isEqualTo(1);
        PositionProof.PositionVerdict clearing =
                verdictOf(proven, AccountPurpose.SETTLEMENT_CLEARING, EUR);
        assertThat(unexplained(proven, AccountPurpose.SETTLEMENT_CLEARING, EUR))
                .as("the clearing identity is untouched by both hops: balance %s, remainders %s,"
                                + " items %s, standing difference before the flow %s",
                        clearing.ledgerBalance(), clearing.openRemainders(),
                        clearing.openItems(), baseline)
                .isEqualTo(baseline);
        assertThat(proven.unattributedByPurpose().getOrDefault(AccountPurpose.CASH_AT_BANK, 0L))
                .as("every cash line is a recognition's (ADR-0067 section 9)")
                .isZero();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            app.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            assertThat(new TrialBalance().sweep(app).outOfBalance())
                    .as("the trial balance holds with cash on the books (INV-ACC-01)")
                    .isEmpty();
            app.rollback();
        }

        HttpResponse<String> positions =
                get(operator.token(), "/v1/operator/reports/reconciliation/positions");
        assertThat(positions.statusCode()).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                        + " 'reconciliation.ReportRead' AND actor_id = ?",
                        operator.identity().value().toString()))
                .as("the report's serving is audited - its shape, never its figures")
                .isEqualTo(1);
        assertThat(positions.body())
                .contains("\"cash\":[")
                .contains("{\"currency\":\"EUR\",\"ledgerBalance\":\"97.75\","
                        + "\"chainClosing\":\"97.75\",\"difference\":\"0.00\","
                        + "\"latestSequence\":1,\"unbroken\":true,\"explained\":true}");
    }

    // ----------------------------------------------------------------- the chain

    @Test
    @Order(2)
    @DisplayName("a gap fails the cash proof until the missing statement stitches it - then the"
            + " STATEMENT_GAP closes EVIDENCED naming it; unattributed cash is owned from its"
            + " transaction, a debit CRITICAL")
    void aGapIsFilledAndUnattributedCashIsOwned() throws Exception {
        String marker = marker();
        UUID first =
                acceptedBatch(BANK_SOURCE,
                        statement("SB-GBP-1-" + marker, "GBP", 1, "C,2026-09-25,GBP,0.00",
                                "C,2026-09-26,GBP,20.00", ":61:2026-09-26,C,20.00"));
        UUID creditItem = itemOf(first, "BANK_CREDIT");
        assertThat(one("SELECT status || ':' || parked_minor::text || ':' ||"
                        + " (position_purpose IS NULL)::text FROM reconciliation.external_item"
                        + " WHERE id = ?", creditItem))
                .as("born PARKED with its whole value, standing in no position")
                .isEqualTo("PARKED:2000:true");
        assertThat(one("SELECT b.type || ':' || b.cause || ':' || b.severity || ':' || s.origin"
                        + " || ':' || s.side || ':' || s.amount_minor::text || ':'"
                        + " || (s.entry_id = (SELECT journal_entry_id FROM settlement.batch"
                        + " WHERE id = ?))::text FROM reconciliation.suspense_item s JOIN"
                        + " reconciliation.break b ON b.id = s.break_id WHERE"
                        + " s.external_item_id = ?", first, creditItem))
                .as("owned by its break, carrying the recognition's entry (INV-REC-09)")
                .isEqualTo("UNKNOWN_EXTERNAL:BANK_LINE_UNATTRIBUTED:HIGH:BANK_UNATTRIBUTED:"
                        + "CREDIT:2000:true");

        UUID third =
                acceptedBatch(BANK_SOURCE,
                        statement("SB-GBP-3-" + marker, "GBP", 3, "C,2026-09-27,GBP,15.00",
                                "C,2026-09-28,GBP,10.00", ":61:2026-09-28,D,5.00,ZZZ-REF-7"));
        assertThat(one("SELECT b.cause || ':' || b.severity || ':' || b.status || ':'"
                        + " || b.value_at_issue_minor::text FROM reconciliation.break b JOIN"
                        + " reconciliation.reconciliation_batch r ON r.id = b.run_id WHERE"
                        + " r.batch_id = ? AND b.type = 'SETTLEMENT_MISMATCH'", third))
                .isEqualTo("STATEMENT_GAP:CRITICAL:OPEN:1500");
        assertThat(one("SELECT b.severity FROM reconciliation.break b WHERE"
                        + " b.external_item_id = ?", itemOf(third, "BANK_DEBIT")))
                .as("cash that LEFT with no counterparty is CRITICAL")
                .isEqualTo("CRITICAL");
        PositionProof.CashVerdict broken = sweep().cashOf(GBP).orElseThrow();
        assertThat(broken.unbroken()).isFalse();
        assertThat(broken.explained())
                .as("the platform does not know its cash across a gap")
                .isFalse();

        UUID second =
                acceptedBatch(BANK_SOURCE,
                        statement("SB-GBP-2-" + marker, "GBP", 2, "C,2026-09-26,GBP,20.00",
                                "C,2026-09-27,GBP,15.00", ":61:2026-09-27,D,5.00"));
        assertThat(one("SELECT b.status || ':' || r.kind || ':' || r.reason_code || ':'"
                        + " || (r.decision_id IS NULL)::text || ':' || r.narrative FROM"
                        + " reconciliation.break b JOIN reconciliation.reconciliation_batch rb"
                        + " ON rb.id = b.run_id JOIN reconciliation.resolution r ON"
                        + " r.break_id = b.id WHERE rb.batch_id = ? AND b.cause ="
                        + " 'STATEMENT_GAP'", third))
                .as("the filling statement is the evidence, named - no decision exists")
                .isEqualTo("RESOLVED:EVIDENCED:EVIDENCE_RECEIVED:true:statement=" + second);

        PositionProof.Report proven = sweep();
        PositionProof.CashVerdict cash = proven.cashOf(GBP).orElseThrow();
        assertThat(cash.explained())
                .as("stitched: %s = %s", cash.ledgerBalance(), cash.chainClosing())
                .isTrue();
        assertThat(cash.chainClosing().minorUnits()).isEqualTo(10_00);
        assertThat(cash.latestSequence()).isEqualTo(3);
        assertThat(proven.suspenseVerdicts().stream()
                        .filter(verdict -> verdict.currency().equals(GBP))
                        .findFirst()
                        .orElseThrow()
                        .explained())
                .as("the suspense identity holds over the unattributed credit and debits")
                .isTrue();
        assertThat(proven.suspenseUnowned())
                .as("every unattributed unit is owned by an open break (INV-REC-09)")
                .isZero();
    }

    @Test
    @Order(3)
    @DisplayName("a non-zero first opening is OPENING_BALANCE, CRITICAL, and nothing is posted to"
            + " make it fit - the cash proof fails loudly")
    void aNonZeroFirstOpeningIsNeverPostedAround() throws Exception {
        String marker = marker();
        UUID first =
                acceptedBatch(BANK_SOURCE,
                        statement("SB-USD-1-" + marker, "USD", 1, "C,2026-09-25,USD,5.00",
                                "C,2026-09-26,USD,4.50", ":61:2026-09-26,F,0.50"));
        assertThat(one("SELECT b.cause || ':' || b.severity || ':' || b.value_at_issue_minor::text"
                        + " FROM reconciliation.break b JOIN reconciliation.reconciliation_batch"
                        + " r ON r.id = b.run_id WHERE r.batch_id = ?", first))
                .isEqualTo("OPENING_BALANCE:CRITICAL:500");
        UUID entry =
                (UUID) one("SELECT journal_entry_id FROM settlement.batch WHERE id = ?", first);
        assertThat(entryLines(entry))
                .as("the statement's own movement only - the opening is never booked")
                .containsExactlyInAnyOrder("CASH_AT_BANK:CREDIT:50", "PROCESSING_COSTS:DEBIT:50");
        PositionProof.Report proven = sweep();
        assertThat(proven.cashOf(USD).orElseThrow().explained()).isFalse();
        assertThat(proven.cashCurrenciesFailing()).isGreaterThanOrEqualTo(1);
    }

    // ----------------------------------------------------------------- ten acceptors

    @Test
    @Order(4)
    @DisplayName("ten instances parse and accept competing statements at once: one statement per"
            + " sequence, one recognition per batch, and the chain stitched whatever the order")
    void tenAcceptorsOfCompetingStatements() throws Exception {
        String marker = marker();
        UUID two = pulled(BANK_SOURCE,
                statement("SB-EUR-2A-" + marker, "EUR", 2, "C,2026-09-27,EUR,97.75",
                        "C,2026-09-28,EUR,97.25", ":61:2026-09-28,F,0.50"));
        UUID twoAgain = pulled(BANK_SOURCE,
                statement("SB-EUR-2B-" + marker, "EUR", 2, "C,2026-09-27,EUR,97.75",
                        "C,2026-09-28,EUR,97.25", ":61:2026-09-28,F,0.50"));
        UUID three = pulled(BANK_SOURCE,
                statement("SB-EUR-3-" + marker, "EUR", 3, "C,2026-09-28,EUR,97.25",
                        "C,2026-09-29,EUR,96.75", ":61:2026-09-29,F,0.50"));

        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> racers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            racers.add(pool.submit(() -> {
                start.await();
                for (int round = 0; round < 3; round++) {
                    parsing.sweep();
                    acceptance.sweep();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> racer : racers) {
            racer.get(3, TimeUnit.MINUTES);
        }
        pool.shutdown();

        List<String> verdicts = new ArrayList<>();
        for (UUID file : List.of(two, twoAgain)) {
            verdicts.add(one("SELECT status || ':' || coalesce(rejection_code, '-') FROM"
                    + " settlement.file WHERE id = ?", file).toString());
        }
        assertThat(verdicts)
                .as("one statement per sequence: the loser retained, CONFLICTING_BATCH")
                .containsExactlyInAnyOrder("ACCEPTED:-", "REJECTED:CONFLICTING_BATCH");
        assertThat(one("SELECT status FROM settlement.file WHERE id = ?", three))
                .isEqualTo("ACCEPTED");
        assertThat(count("SELECT count(*) FROM ledger.journal_entry e JOIN settlement.batch b"
                        + " ON e.id = b.journal_entry_id WHERE b.file_id IN (?, ?, ?)",
                        two, twoAgain, three))
                .as("one recognition per accepted batch - ten racers, no second entry")
                .isEqualTo(2);
        assertThat(count("SELECT count(*) FROM reconciliation.break b JOIN"
                        + " reconciliation.reconciliation_batch r ON r.id = b.run_id JOIN"
                        + " settlement.batch sb ON sb.id = r.batch_id WHERE sb.file_id IN"
                        + " (?, ?, ?) AND b.cause = 'STATEMENT_GAP' AND b.status <> 'RESOLVED'",
                        two, twoAgain, three))
                .as("an out-of-order acceptance's gap is closed by its filler")
                .isZero();
        PositionProof.CashVerdict cash = sweep().cashOf(EUR).orElseThrow();
        assertThat(cash.explained())
                .as("the chain is whole: %s = %s", cash.ledgerBalance(), cash.chainClosing())
                .isTrue();
        assertThat(cash.chainClosing().minorUnits()).isEqualTo(96_75);
        assertThat(cash.latestSequence()).isEqualTo(3);
    }

    // ----------------------------------------------------------------- the needle

    @Test
    @Order(5)
    @DisplayName("a statement whose free text carries an IBAN is refused at the door: metadata"
            + " only, the needle in no log, audit record, event or table (INV-RAIL-03)")
    void theIbanNeedleReachesNoSink(CapturedOutput output) throws Exception {
        // An IBAN shape whose digit run stays short of card length, so the door refuses it as
        // an account identifier, never as a card number.
        String needle = "NL" + (10 + RANDOMNESS.nextInt(89)) + "ABNA"
                + (1_000_000_000L + (long) RANDOMNESS.nextInt(999_999_999));
        String content =
                statement("SB-EUR-N-" + marker(), "EUR", 9, "C,2026-09-29,EUR,0.00",
                        "C,2026-09-30,EUR,1.00", ":61:2026-09-30,C,1.00",
                        ":86:Pay to " + needle);
        FileReception.Result result;
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(flow())) {
            result =
                    settlementTransactionRunner.inTransaction(
                            uow ->
                                    reception.receive(
                                            uow,
                                            new FileReception.Delivery(
                                                    BANK_SOURCE,
                                                    DeliveryChannel.PULL,
                                                    content.getBytes(StandardCharsets.UTF_8),
                                                    Optional.empty(),
                                                    Actor.SYSTEM,
                                                    SettlementAuditAction
                                                            .SETTLEMENT_FILE_UPLOADED,
                                                    CorrelationContext.current()
                                                            .orElseThrow())));
        }
        assertThat(result).isInstanceOf(FileReception.Result.Refused.class);
        FileReception.Result.Refused refused = (FileReception.Result.Refused) result;
        assertThat(refused.reason()).isEqualTo(RefusalReason.ACCOUNT_IDENTIFIER);
        assertThat(refused.fieldName()).contains("narrative");

        assertThat(output.getAll()).doesNotContain(needle);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE"
                        + " coalesce(change_summary, '') || coalesce(reason, '') LIKE ?",
                        "%" + needle + "%"))
                .isZero();
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE"
                        + " convert_from(payload, 'UTF8') LIKE ?", "%" + needle + "%"))
                .isZero();
        assertThat(count("SELECT count(*) FROM settlement.refused_delivery WHERE"
                        + " coalesce(field_name, '') LIKE ?", "%" + needle + "%"))
                .isZero();
        assertThat(count("SELECT count(*) FROM settlement.line_reference WHERE value LIKE ?",
                        "%" + needle + "%"))
                .isZero();
        assertThat(count("SELECT count(*) FROM settlement.refused_delivery WHERE reason ="
                        + " 'ACCOUNT_IDENTIFIER' AND field_name = 'narrative'"))
                .as("the refusal is recorded - as metadata")
                .isGreaterThanOrEqualTo(1);
        assertThat(meterRegistry.getMeters().stream()
                        .flatMap(meter -> meter.getId().getTags().stream())
                        .noneMatch(tag -> tag.getValue().contains(needle)))
                .as("no metric carries the needle - tags are closed vocabularies")
                .isTrue();
    }

    // ----------------------------------------------------------------- seeding

    /**
     * A captured card sale seeded WITH its posting (the production key's shape: DR the card
     * clearing / CR the counterparty) and its CARD_CAPTURE expectation naming that line, keyed
     * by the PSP's capture reference under the PSP source — the clearing identity holds.
     */
    private void seedCapture(String operationRef, String captureRef, long minor) throws Exception {
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(flow());
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            LedgerAccount wallet =
                    ledgerAccountStore
                            .createOrConverge(
                                    app,
                                    LedgerAccount.owned(
                                            IDS, CLOCK, AccountType.LIABILITY,
                                            AccountPurpose.CUSTOMER_WALLET, EUR, IDS.next()))
                            .account();
            LedgerAccount clearing =
                    ledgerAccountStore
                            .findOperational(app, AccountPurpose.SETTLEMENT_CLEARING, EUR)
                            .orElseThrow();
            Money amount = Money.ofPersisted(minor, EUR, 2);
            UUID entry =
                    postingService
                            .post(
                                    app,
                                    new PostingCommand(
                                            "payment-capture:" + operationRef,
                                            CAPTURED_ON,
                                            CAPTURED_ON,
                                            operationRef,
                                            List.of(
                                                    new JournalLine(clearing.id(),
                                                            Direction.DEBIT, amount),
                                                    new JournalLine(wallet.id(),
                                                            Direction.CREDIT, amount))))
                            .entryId()
                            .value();
            UUID pspSource =
                    settlementFileStore.sourceByCode(app, PSP_SOURCE).orElseThrow().id();
            expectationRegister.open(
                    app,
                    new NewExpectation(
                            ExpectationKind.CARD_CAPTURE, operationRef,
                            "payment-capture:" + operationRef, pspSource,
                            AccountPurpose.SETTLEMENT_CLEARING, clearing.id().value(),
                            ExpectationDirection.INBOUND, amount, Optional.of(entry),
                            CAPTURED_ON, Optional.empty(), CAPTURED_ON.plusDays(3),
                            ruleSets.activeFor(app, pspSource).id(),
                            List.of(new NewExpectation.ExpectationKey(
                                    KeyKind.PSP_CAPTURE_REF, captureRef)),
                            Actor.SYSTEM, Instant.now(CLOCK), CorrelationId.generate(IDS)));
            app.commit();
        }
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

    private UUID pulled(String source, String content) {
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(flow())) {
            FileReception.Result result =
                    settlementTransactionRunner.inTransaction(
                            uow ->
                                    reception.receive(
                                            uow,
                                            new FileReception.Delivery(
                                                    source,
                                                    DeliveryChannel.PULL,
                                                    content.getBytes(StandardCharsets.UTF_8),
                                                    Optional.empty(),
                                                    Actor.SYSTEM,
                                                    SettlementAuditAction
                                                            .SETTLEMENT_FILE_UPLOADED,
                                                    CorrelationContext.current()
                                                            .orElseThrow())));
            assertThat(result).isInstanceOf(FileReception.Result.New.class);
            return ((FileReception.Result.New) result).fileId();
        }
    }

    private UUID acceptedBatch(String source, String content) throws SQLException {
        UUID fileId = pulled(source, content);
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

    private static UUID itemOf(UUID batchId, String lineType) throws SQLException {
        return (UUID) one("SELECT i.id FROM reconciliation.external_item i JOIN"
                + " reconciliation.reconciliation_batch r ON r.id = i.run_id WHERE r.batch_id = ?"
                + " AND i.line_type = ?", batchId, lineType);
    }

    /** The entry's lines as purpose:direction:minor, read against the chart. */
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

    /** The identity's difference: balance − (open remainders − open items). */
    private static Money unexplained(
            PositionProof.Report report, AccountPurpose purpose, CurrencyCode currency) {
        PositionProof.PositionVerdict verdict = verdictOf(report, purpose, currency);
        return verdict.ledgerBalance()
                .minus(verdict.openRemainders().minus(verdict.openItems()));
    }

    private static PositionProof.PositionVerdict verdictOf(
            PositionProof.Report report, AccountPurpose purpose, CurrencyCode currency) {
        return report.verdicts().stream()
                .filter(verdict -> verdict.purpose() == purpose
                        && verdict.currency().equals(currency))
                .findFirst()
                .orElseThrow();
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
                            + " VALUES (?, 'PERSON', 'Cash Desk Person', now())",
                    party);
            execute(app,
                    "INSERT INTO identity.identity (id, party_id, login_identifier, status,"
                            + " created_at, status_changed_at) VALUES (?, ?, ?, 'ACTIVE',"
                            + " now(), now())",
                    identity, party,
                    "cb" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
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
        return Correlation.startingWith(CorrelationId.of("p8t16-" + UUID.randomUUID()))
                .causing(CausationId.of("p8t16-cause"));
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
