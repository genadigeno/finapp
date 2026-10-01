package com.finapp.app.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.settlement.SimulatedSchemeReports;
import com.finapp.app.settlement.SimulatedSettlementReports;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingService;
import com.finapp.ledger.TrialBalance;
import com.finapp.payments.SimulatedCardPspAdapter;
import com.finapp.payments.SimulatedInstantSchemeAdapter;
import com.finapp.payments.WebhookSignature;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.reconciliation.ExpectationDirection;
import com.finapp.reconciliation.ExpectationKind;
import com.finapp.reconciliation.ExpectationRegister;
import com.finapp.reconciliation.InternalReferenceLookup;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Late evidence and break typing over the REAL composition (`P8-DOC-001`'s gate claims): the
 * production parse and accept legs, the production matcher, the composed
 * {@link JdbcInternalReferenceLookup} reading payments' own rows, and - for the chargeback - the
 * signed card webhook door posting the dispute stage and opening its expectation.
 *
 * <ul>
 *   <li><strong>§14.15, late settlement</strong> - a PSP {@code CHARGEBACK} line reported ahead
 *       of its dispute stage waits under grace, is typed and parked once grace runs out, and is
 *       unparked {@code EVIDENCED} by the rematch leg when the stage posts.
 *   <li><strong>§14.14 / ADR-0069 §2, the scheme's unclaimed line</strong> - no claim holds its
 *       scheme reference, but its end-to-end reference names a push pay-in still
 *       {@code AWAITING_PAYER}: after grace, {@code MISSING_INTERNAL} with the lookup's frozen
 *       {@code IN_FLIGHT} answer - the real lookup, never a stub.
 *   <li><strong>ADR-0065 / ADR-0069 §2, the second presentment</strong> - a second, different
 *       clearing of an already-settled capture reaching the matcher as a PSP line: pinned as
 *       the code decides it - {@code DUPLICATE_EXTERNAL (EXPECTATION_EXHAUSTED)} for its whole
 *       value, parked with its break, never absorbed.
 * </ul>
 *
 * <p>The proofs are not asserted absolutely here (the suite parks values on purpose); the trial
 * balance is.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("late evidence and break typing through the real lookup (P8-DOC-001)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class LateEvidenceAndBreakTypingDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final String PSP_SOURCE = "simulated-psp.settlement";
    private static final String SCHEME_SOURCE = "simulated-scheme.cycle-report";
    private static final byte[] WEBHOOK_KEY =
            "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8);

    private static SimulatedProvider provider;

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) {
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
        registry.add("finapp.payments.provider.url", () -> provider.baseUrl());
        registry.add(
                "finapp.payments.webhook.key",
                () -> Base64.getEncoder().encodeToString(WEBHOOK_KEY));
    }

    @AfterAll
    static void stopProvider() {
        if (provider != null) {
            provider.close();
        }
    }

    @LocalServerPort private int port;
    @Autowired private FileReception<Connection> reception;
    @Autowired private FileParsing parsing;
    @Autowired private BatchAcceptance acceptance;
    @Autowired private Matching matching;
    @Autowired private PostingService postingService;
    @Autowired private LedgerAccountStore<Connection> ledgerAccountStore;
    @Autowired private ExpectationRegister expectationRegister;
    @Autowired private RuleSets ruleSets;
    @Autowired private SettlementFileStore<Connection> settlementFileStore;
    @Autowired private TransactionRunner settlementTransactionRunner;
    @Autowired private InternalReferenceLookup internalReferenceLookup;

    // ================================================================= §14.15

    @Test
    @DisplayName("§14.15: a CHARGEBACK line reported before its dispute stage waits under grace,"
            + " parks UNKNOWN_EXTERNAL when grace runs out, and is unparked EVIDENCED by the"
            + " rematch leg once the webhook posts the stage - the unpark the park's exact"
            + " inverse")
    void aChargebackAheadOfItsStageIsUnparkedEvidenced() throws Exception {
        LocalDate today = LocalDate.now(CLOCK);
        LedgerAccountId credit = wallet();
        CardPayment payment = capturedCardPayment(credit, 10_00);
        String disputeRef = "dpcb_" + letters(16);

        // The report first: the network's take, named by a dispute nobody has heard of yet.
        UUID batch =
                acceptedBatch(PSP_SOURCE,
                        new SimulatedSettlementReports("PSPB-LATE-" + letters(10), "EUR", today,
                                "PSP-REM-" + digits(10))
                                .with(SimulatedSettlementReports.Line.chargeback(disputeRef,
                                        "10.00"))
                                .render());
        matchUntilQuiet();
        UUID item = itemOf(batch, "CHARGEBACK");
        assertThat(one("SELECT status || ':' || (grace_until > now())::text FROM"
                        + " reconciliation.external_item WHERE id = ?", item))
                .as("§14.15: the line ahead of its stage WAITS under grace - no break yet")
                .isEqualTo("UNMATCHED:true");
        assertThat(count("SELECT count(*) FROM reconciliation.break WHERE external_item_id = ?",
                        item))
                .isZero();

        // Grace runs out before the stage arrives: typed through the real lookup and parked.
        expireGrace(item);
        matchUntilQuiet();
        assertThat(one("SELECT status FROM reconciliation.external_item WHERE id = ?", item))
                .isEqualTo("PARKED");
        UUID breakId =
                (UUID) one("SELECT id FROM reconciliation.break WHERE external_item_id = ?",
                        item);
        assertThat(one("SELECT type || ':' || cause || ':' || status || ':'"
                        + " || coalesce(internal_classification, '-') FROM reconciliation.break"
                        + " WHERE id = ?", breakId))
                .as("§14.15: past grace with no dispute on the platform's records - the lookup"
                        + " answers UNKNOWN, so UNKNOWN_EXTERNAL, parked, owned")
                .isEqualTo("UNKNOWN_EXTERNAL:GRACE_EXPIRED:OPEN:UNKNOWN");
        UUID suspenseItem =
                (UUID) one("SELECT id FROM reconciliation.suspense_item WHERE"
                        + " external_item_id = ? AND break_id = ?", item, breakId);
        assertThat(suspenseItem).as("the parked value is owned by its break").isNotNull();
        UUID parkEntry =
                (UUID) one("SELECT p.journal_entry_id FROM reconciliation.park p JOIN"
                        + " reconciliation.suspense_item s ON s.park_id = p.id WHERE s.id = ?",
                        suspenseItem);

        // The stage posts: the production webhook door, the dispute CHARGED_BACK, its
        // CHARGEBACK expectation opened in the stage's own transaction.
        HttpResponse<String> delivered =
                deliver(chargebackStatement(payment, disputeRef, "needs_response", 10_00));
        assertThat(delivered.statusCode()).as(delivered.body()).isEqualTo(204);
        UUID dispute =
                (UUID) one("SELECT id FROM payments.dispute WHERE provider = ? AND"
                        + " provider_dispute_reference = ?",
                        SimulatedCardPspAdapter.NAME, disputeRef);
        UUID expectation = expectationOf("CHARGEBACK", dispute.toString());
        assertThat(one("SELECT status FROM reconciliation.expectation WHERE id = ?",
                        expectation))
                .isEqualTo("OPEN");

        matchUntilQuiet();
        assertThat(one("SELECT status FROM reconciliation.external_item WHERE id = ?", item))
                .as("§14.15: the rematch leg allocates the parked line to the stage's"
                        + " expectation")
                .isEqualTo("MATCHED");
        assertThat(one("SELECT status FROM reconciliation.expectation WHERE id = ?",
                        expectation))
                .isEqualTo("SETTLED");
        assertThat(one("SELECT d.origin FROM reconciliation.match_decision d WHERE"
                        + " d.external_item_id = ? ORDER BY d.decided_at DESC, d.id DESC LIMIT 1",
                        item))
                .isEqualTo("REMATCH");
        assertThat(one("SELECT status FROM reconciliation.suspense_item WHERE id = ?",
                        suspenseItem))
                .isEqualTo("RELEASED");
        assertThat(one("SELECT cause || ':' || amount_minor::text FROM"
                        + " reconciliation.suspense_release WHERE item_id = ?", suspenseItem))
                .as("§14.15: released whole, by the unpark")
                .isEqualTo("UNPARK:1000");
        assertThat(one("SELECT kind || ':' || status || ':' || reason_code || ':'"
                        + " || proposed_by_type FROM reconciliation.resolution WHERE break_id = ?",
                        breakId))
                .as("§14.15: the break resolved EVIDENCED - the platform's own, born approved")
                .isEqualTo("EVIDENCED:APPROVED:EVIDENCE_RECEIVED:SYSTEM");
        assertThat(one("SELECT status FROM reconciliation.break WHERE id = ?", breakId))
                .isEqualTo("RESOLVED");
        UUID unparkEntry =
                (UUID) one("SELECT journal_entry_id FROM reconciliation.resolution WHERE"
                        + " break_id = ?", breakId);
        assertThat(entryLines(unparkEntry))
                .as("§14.15: the unpark is the park's exact inverse, the item's own 10.00")
                .containsExactlyInAnyOrderElementsOf(
                        mirrored(entryLines(parkEntry)).stream()
                                .map(line -> line.substring(0, line.lastIndexOf(':')) + ":1000")
                                .toList());
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                        + " 'reconciliation.BreakResolved' AND aggregate_id = ?", breakId))
                .isEqualTo(1);
        assertTrialBalance();
    }

    // ================================================================= §14.14 / ADR-0069 §2

    @Test
    @DisplayName("an unclaimed scheme line whose end-to-end reference names a push pay-in still"
            + " AWAITING_PAYER: after grace the REAL lookup answers IN_FLIGHT and the break is"
            + " MISSING_INTERNAL, its internal facts frozen, the value parked")
    void anUnclaimedSchemeLineWithAnOperationInFlightIsMissingInternal() throws Exception {
        assertThat(internalReferenceLookup)
                .as("the composed lookup is the real one, reading payments' rows")
                .isInstanceOf(JdbcInternalReferenceLookup.class);
        String marker = letters(10);
        String endToEnd = "E2E-MI-" + marker;
        String schemeRef = "SCH-MI-" + marker;
        UUID attempt = pushPayInAwaitingPayer(endToEnd);

        UUID report =
                acceptedBatch(SCHEME_SOURCE,
                        new SimulatedSchemeReports("CYC-2026-09-25-C" + marker, "EUR",
                                "2026-09-25", "SCH-REM-17" + digits(8))
                                .with(SimulatedSchemeReports.Entry.payIn(
                                        "25.00", "0.00", schemeRef, endToEnd))
                                .render());
        assertThat(count("SELECT count(*) FROM payments.scheme_execution_claim WHERE"
                        + " scheme_reference = ?", schemeRef))
                .as("no claim holds the line's scheme reference")
                .isZero();
        matchUntilQuiet();
        UUID item = itemOf(report, "CREDIT_IN");
        assertThat(one("SELECT status || ':' || (grace_until > now())::text FROM"
                        + " reconciliation.external_item WHERE id = ?", item))
                .as("an operation in flight: the line waits under grace")
                .isEqualTo("UNMATCHED:true");

        expireGrace(item);
        matchUntilQuiet();
        assertThat(one("SELECT status FROM reconciliation.external_item WHERE id = ?", item))
                .isEqualTo("PARKED");
        assertThat(one("SELECT type || ':' || cause || ':' || status || ':'"
                        + " || internal_classification || ':' || internal_operation_ref || ':'"
                        + " || internal_state FROM reconciliation.break WHERE"
                        + " external_item_id = ?", item))
                .as("ADR-0069 §2: no claim, but the end-to-end reference names a pay-in still"
                        + " AWAITING_PAYER - MISSING_INTERNAL with the real lookup's frozen"
                        + " IN_FLIGHT answer, never UNKNOWN_EXTERNAL")
                .isEqualTo("MISSING_INTERNAL:GRACE_EXPIRED:OPEN:IN_FLIGHT:" + attempt
                        + ":AWAITING_PAYER");
        assertThat(count("SELECT count(*) FROM reconciliation.suspense_item s JOIN"
                        + " reconciliation.break b ON b.id = s.break_id WHERE"
                        + " s.external_item_id = ? AND b.type = 'MISSING_INTERNAL' AND"
                        + " s.status = 'OPEN'", item))
                .as("the value parked, owned by its break")
                .isEqualTo(1);
        assertTrialBalance();
    }

    // ================================================================= second presentment

    @Test
    @DisplayName("a second, different clearing of a settled capture arrives as a PSP line: it"
            + " claims the capture's expectation a second time, finds it exhausted and parks"
            + " DUPLICATE_EXTERNAL with its break - never absorbed, never allocated")
    void aSecondPresentmentIsParkedNeverAbsorbed() throws Exception {
        LocalDate today = LocalDate.now(CLOCK);
        String marker = letters(10);
        String operationRef = "op-2p-" + UUID.randomUUID();
        String captureRef = "PSP-CAP-" + marker;
        UUID expectation = seedCapture(operationRef, captureRef, 100_00, today.minusDays(1));

        UUID first =
                acceptedBatch(PSP_SOURCE,
                        new SimulatedSettlementReports("PSPB-2P-A-" + marker, "EUR", today,
                                "PSP-REM-" + digits(10))
                                .with(SimulatedSettlementReports.Line.capture(
                                        captureRef, "", "ORD-A" + marker, "100.00", "1.75"))
                                .render());
        matchUntilQuiet();
        assertThat(itemsOf(first))
                .containsExactlyInAnyOrder("CAPTURE:MATCHED", "PROCESSING_FEE:CHECKED");
        assertThat(one("SELECT status || ':' || allocated_minor::text FROM"
                        + " reconciliation.expectation WHERE id = ?", expectation))
                .isEqualTo("SETTLED:10000");

        // The second presentment: the same capture reference, a different clearing (another
        // report, another amount).
        UUID second =
                acceptedBatch(PSP_SOURCE,
                        new SimulatedSettlementReports("PSPB-2P-B-" + marker, "EUR", today,
                                "PSP-REM-" + digits(10))
                                .with(SimulatedSettlementReports.Line.capture(
                                        captureRef, "", "ORD-B" + marker, "60.00", "1.15"))
                                .render());
        matchUntilQuiet();
        UUID item = itemOf(second, "CAPTURE");
        assertThat(one("SELECT status FROM reconciliation.external_item WHERE id = ?", item))
                .as("ADR-0065: the second presentment is parked, never absorbed")
                .isEqualTo("PARKED");
        assertThat(rows("SELECT b.type || ':' || b.cause || ':' || b.status || ':'"
                        + " || b.value_at_issue_minor::text FROM reconciliation.break b WHERE"
                        + " b.external_item_id = ?", item))
                .as("ADR-0065 / ADR-0069 §2: the second presentment claims the settled"
                        + " capture's expectation a second time and finds it exhausted -"
                        + " DUPLICATE_EXTERNAL (EXPECTATION_EXHAUSTED) for its whole 60.00, never"
                        + " an AMOUNT_MISMATCH excess against a settled expectation")
                .containsExactly("DUPLICATE_EXTERNAL:EXPECTATION_EXHAUSTED:OPEN:6000");
        assertThat(one("SELECT status || ':' || allocated_minor::text FROM"
                        + " reconciliation.expectation WHERE id = ?", expectation))
                .as("the capture's expectation is not claimed twice: still 100.00, settled")
                .isEqualTo("SETTLED:10000");
        assertThat(count("SELECT count(*) FROM reconciliation.allocation WHERE"
                        + " external_item_id = ?", item))
                .as("nothing of the second presentment is allocated")
                .isZero();
        assertThat(count("SELECT count(*) FROM reconciliation.suspense_item s WHERE"
                        + " s.external_item_id = ? AND s.status = 'OPEN' AND s.break_id IS NOT"
                        + " NULL", item))
                .as("its value rests in suspense, owned by its break")
                .isEqualTo(1);
        assertTrialBalance();
    }

    // ================================================================= fixtures

    private record CardPayment(UUID intent, UUID attempt, String operation) {}

    /** A captured card payment seeded raw WITH its capture's posting (the chargeback suite's). */
    private CardPayment capturedCardPayment(LedgerAccountId credit, long minor) throws Exception {
        UUID intent = IDS.next();
        UUID attempt = IDS.next();
        String capture = "cap-" + IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app,
                    "INSERT INTO payments.payment_intent (id, party_id, customer_id,"
                            + " payment_method_id, credit_account_id, amount_minor, currency,"
                            + " scale, status, created_at, capture_mode)"
                            + " VALUES (?, ?, ?, ?, ?, ?, 'EUR', 2, 'SUCCEEDED', now(),"
                            + " 'AUTOMATIC')",
                    intent, IDS.next(), IDS.next(), IDS.next(), credit.value(), minor);
            execute(app,
                    "INSERT INTO payments.payment_attempt (id, intent_id, auth_reference,"
                            + " capture_reference, auth_provider_reference,"
                            + " capture_provider_reference, authorized_amount_minor,"
                            + " authorized_currency, authorized_scale, captured_amount_minor,"
                            + " captured_currency, captured_scale, status, created_at, rail,"
                            + " interaction_model)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, 'EUR', 2, ?, 'EUR', 2, 'CAPTURED',"
                            + " now(), 'card', 'TWO_STEP')",
                    attempt, intent, "auth-" + IDS.next(), capture, "psp-auth-" + IDS.next(),
                    "psp-cap-" + IDS.next(), minor, minor);
        }
        try (CorrelationContext.Scope scope = CorrelationContext.enter(flow());
                SecurityContext.Scope platform = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            LedgerAccount clearing =
                    ledgerAccountStore
                            .findOperational(app, AccountPurpose.SETTLEMENT_CLEARING, EUR)
                            .orElseThrow();
            LocalDate today = LocalDate.now(CLOCK);
            Money amount = Money.ofPersisted(minor, EUR, 2);
            postingService.post(
                    app,
                    new PostingCommand(
                            "payment-capture:" + attempt, today, today, attempt.toString(),
                            List.of(new JournalLine(clearing.id(), Direction.DEBIT, amount),
                                    new JournalLine(credit, Direction.CREDIT, amount))));
            app.commit();
        }
        return new CardPayment(intent, attempt, capture);
    }

    /** A push pay-in on the instant rail, still AWAITING_PAYER: in flight, executed by nobody. */
    private static UUID pushPayInAwaitingPayer(String endToEnd) throws SQLException {
        UUID intent = IDS.next();
        UUID attempt = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app,
                    "INSERT INTO payments.payment_intent (id, party_id, customer_id,"
                            + " payment_method_id, credit_account_id, amount_minor, currency,"
                            + " scale, status, created_at, capture_mode)"
                            + " VALUES (?, ?, ?, ?, ?, 2500, 'EUR', 2, 'PROCESSING', now(),"
                            + " 'AUTOMATIC')",
                    intent, IDS.next(), IDS.next(), IDS.next(), IDS.next());
            execute(app,
                    "INSERT INTO payments.payment_attempt (id, intent_id, status, created_at,"
                            + " rail, interaction_model, end_to_end_reference,"
                            + " last_dispatched_at)"
                            + " VALUES (?, ?, 'AWAITING_PAYER', now(), ?, 'PUSH', ?, now())",
                    attempt, intent, SimulatedInstantSchemeAdapter.RAIL.id().value(), endToEnd);
        }
        return attempt;
    }

    /** A card capture seeded WITH its posting and its CARD_CAPTURE expectation. */
    private UUID seedCapture(String operationRef, String captureRef, long minor, LocalDate on)
            throws Exception {
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
                                            "payment-capture:" + operationRef, on, on,
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
            assertThat(expectationRegister
                            .open(
                                    app,
                                    new NewExpectation(
                                            ExpectationKind.CARD_CAPTURE, operationRef,
                                            "payment-capture:" + operationRef, pspSource,
                                            AccountPurpose.SETTLEMENT_CLEARING,
                                            clearing.id().value(),
                                            ExpectationDirection.INBOUND, amount,
                                            Optional.of(entry), on, Optional.empty(),
                                            on.plusDays(3),
                                            ruleSets.activeFor(app, pspSource).id(),
                                            List.of(new NewExpectation.ExpectationKey(
                                                    KeyKind.PSP_CAPTURE_REF, captureRef)),
                                            Actor.SYSTEM, Instant.now(CLOCK),
                                            CorrelationId.generate(IDS))))
                    .isEqualTo(ExpectationRegister.OpenResult.OPENED);
            app.commit();
        }
        return expectationOf("CARD_CAPTURE", operationRef);
    }

    private LedgerAccountId wallet() throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            LedgerAccountId wallet =
                    ledgerAccountStore
                            .createOrConverge(
                                    app,
                                    LedgerAccount.owned(
                                            IDS, CLOCK, AccountType.LIABILITY,
                                            AccountPurpose.CUSTOMER_WALLET, EUR, IDS.next()))
                            .account()
                            .id();
            app.commit();
            return wallet;
        }
    }

    private UUID acceptedBatch(String source, byte[] bytes) throws SQLException {
        FileReception.Result result;
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(flow())) {
            result =
                    settlementTransactionRunner.inTransaction(
                            uow ->
                                    reception.receive(
                                            uow,
                                            new FileReception.Delivery(
                                                    source,
                                                    DeliveryChannel.PULL,
                                                    bytes,
                                                    Optional.empty(),
                                                    Actor.SYSTEM,
                                                    SettlementAuditAction
                                                            .SETTLEMENT_FILE_UPLOADED,
                                                    CorrelationContext.current()
                                                            .orElseThrow())));
        }
        assertThat(result).isInstanceOf(FileReception.Result.New.class);
        UUID fileId = ((FileReception.Result.New) result).fileId();
        for (int sweep = 0; sweep < 20 && "RECEIVED".equals(fileStatus(fileId)); sweep++) {
            parsing.sweep();
        }
        for (int sweep = 0; sweep < 20 && "PARSED".equals(fileStatus(fileId)); sweep++) {
            acceptance.sweep();
        }
        assertThat(one("SELECT status || ':' || coalesce(rejection_code, '-') FROM"
                        + " settlement.file WHERE id = ?", fileId))
                .isEqualTo("ACCEPTED:-");
        return (UUID) one("SELECT id FROM settlement.batch WHERE file_id = ?", fileId);
    }

    private static String fileStatus(UUID fileId) throws SQLException {
        return (String) one("SELECT status FROM settlement.file WHERE id = ?", fileId);
    }

    private void matchUntilQuiet() {
        for (int i = 0; i < 4; i++) {
            matching.sweep();
        }
    }

    private static void expireGrace(UUID item) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            execute(app, "UPDATE reconciliation.external_item SET grace_until = now() -"
                    + " interval '1 hour' WHERE id = ? AND status = 'UNMATCHED'", item);
        }
    }

    // ----------------------------------------------------------------- the webhook

    private static String chargebackStatement(
            CardPayment payment, String dispute, String stage, long amountMinor) {
        return "{\"eventId\":\"evt_" + UUID.randomUUID() + "\",\"operation\":\""
                + payment.operation() + "\",\"status\":\"disputed\",\"dispute\":\"" + dispute
                + "\",\"stage\":\"" + stage + "\",\"reasonCode\":\"fraudulent\","
                + "\"amountMinor\":\"" + amountMinor + "\",\"currency\":\"EUR\",\"scale\":2}";
    }

    private HttpResponse<String> deliver(String body) throws Exception {
        String timestamp = Long.toString(Instant.now(CLOCK).getEpochSecond());
        try (HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(
                    HttpRequest.newBuilder(
                                    URI.create("http://localhost:" + port
                                            + "/v1/providers/payments/webhooks"))
                            .header("Content-Type", "application/json")
                            .header(WebhookSignature.TIMESTAMP_HEADER, timestamp)
                            .header(WebhookSignature.SIGNATURE_HEADER,
                                    hmacHex(timestamp + "." + body))
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    private static String hmacHex(String signedPayload) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(WEBHOOK_KEY, "HmacSHA256"));
            return HexFormat.of()
                    .formatHex(mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 is required by every JVM", impossible);
        }
    }

    // ----------------------------------------------------------------- reads

    private static UUID itemOf(UUID batchId, String lineType) throws SQLException {
        UUID item =
                (UUID) one("SELECT i.id FROM reconciliation.external_item i JOIN"
                        + " reconciliation.reconciliation_batch r ON r.id = i.run_id WHERE"
                        + " r.batch_id = ? AND i.line_type = ?", batchId, lineType);
        assertThat(item).as("batch %s's %s item", batchId, lineType).isNotNull();
        return item;
    }

    private static List<String> itemsOf(UUID batchId) throws SQLException {
        return rows("SELECT i.line_type || ':' || i.status FROM reconciliation.external_item i"
                + " JOIN reconciliation.reconciliation_batch r ON r.id = i.run_id WHERE"
                + " r.batch_id = ?", batchId);
    }

    private static UUID expectationOf(String kind, String operationRef) throws SQLException {
        UUID id =
                (UUID) one("SELECT id FROM reconciliation.expectation WHERE kind = ? AND"
                        + " operation_ref = ?", kind, operationRef);
        assertThat(id).as("%s %s", kind, operationRef).isNotNull();
        return id;
    }

    /** The entry's lines as purpose:direction:minor. */
    private static List<String> entryLines(UUID entryId) throws SQLException {
        return rows("SELECT a.purpose || ':' || l.direction || ':' || l.amount_minor::text FROM"
                + " ledger.journal_line l JOIN ledger.ledger_account a ON a.id ="
                + " l.ledger_account_id WHERE l.entry_id = ?", entryId);
    }

    private static List<String> mirrored(List<String> lines) {
        return lines.stream()
                .map(line -> line.contains(":DEBIT:")
                        ? line.replace(":DEBIT:", ":CREDIT:")
                        : line.replace(":CREDIT:", ":DEBIT:"))
                .toList();
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

    // ----------------------------------------------------------------- plumbing

    private static Correlation flow() {
        return Correlation.startingWith(CorrelationId.of("p8doc1-" + UUID.randomUUID()))
                .causing(CausationId.of("p8doc1-cause"));
    }

    private static String letters(int length) {
        StringBuilder letters = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            letters.append((char) ('A' + RANDOMNESS.nextInt(26)));
        }
        return letters.toString();
    }

    private static String digits(int length) {
        StringBuilder digits = new StringBuilder(length);
        digits.append((char) ('1' + RANDOMNESS.nextInt(9)));
        for (int i = 1; i < length; i++) {
            digits.append((char) ('0' + RANDOMNESS.nextInt(10)));
        }
        return digits.toString();
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

    private static List<String> rows(String sql, Object... args) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement statement = app.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            List<String> rows = new ArrayList<>();
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    rows.add(row.getString(1));
                }
            }
            return rows;
        }
    }

    private static long count(String sql, Object... args) throws SQLException {
        return ((Number) one(sql, args)).longValue();
    }
}
