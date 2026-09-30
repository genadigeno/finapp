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
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingService;
import com.finapp.ledger.TrialBalance;
import com.finapp.merchant.MerchantId;
import com.finapp.merchant.MerchantPayoutStatus;
import com.finapp.merchant.MerchantPayouts;
import com.finapp.merchant.SimulatedPayoutProvider;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.reconciliation.InternalClassification;
import com.finapp.reconciliation.InternalReferenceLookup;
import com.finapp.reconciliation.InternalSubject;
import com.finapp.reconciliation.KeyKind;
import com.finapp.reconciliation.Matching;
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
import java.math.BigDecimal;
import java.math.RoundingMode;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The payout provider's report over the REAL composition (`P8-TSK-018`, ADR-0065): payouts made
 * through the real merchant flow against the simulated provider — each posting DR payable / CR
 * {@code PAYOUT_CLEARING} and opening its {@code MERCHANT_PAYOUT} expectation through the port —
 * the provider's report rendered from {@code merchant.merchant_payout}, accepted (its fees
 * recognised DR {@code PROCESSING_COSTS} / CR {@code PAYOUT_CLEARING}, its net expected as an
 * OUTBOUND {@code REMITTANCE}), matched, and discharged by the bank's DEBIT — each payout reading
 * {@code CASH_CONFIRMED} over the wire, the payout itself still {@code COMPLETED}. A returned
 * payout waits with no break, never meeting its own payout's expectation, and after grace parks
 * as {@code RETURN_NOT_APPLICABLE} through the REAL lookup, a four-eyes transfer crediting the
 * payable back.
 *
 * <p><strong>The position proof on a credit-normal position</strong> — the debt `P8-TSK-015`
 * recorded — is asserted absolutely: an open payout expectation leaves {@code PAYOUT_CLEARING}
 * explained, its balance read DR−CR like the remainders.
 *
 * <p><strong>Runs in its own container</strong>: the bank's statement chain is one sequence per
 * currency for the whole database (the `P8-TSK-017` precedent), and the proof assertions here
 * are absolute for {@code PAYOUT_CLEARING}.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("the payout provider's report: payouts allocated, cash confirmed (P8-TSK-018)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class PayoutSettlementCashDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode GBP = CurrencyCode.of("GBP");
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final String PAYOUT_SOURCE = "simulated-payout.settlement";
    private static final String BANK_SOURCE = "simulated-bank.statement";
    private static final String PATH = SimulatedPayoutProvider.PAYOUTS_PATH;
    /**
     * The provider mints its own reference per payout — letters only, so no digit run of card
     * length can ever meet the format's reference class by chance.
     */
    private static final String PAID =
            "{\"status\":\"paid\",\"reference\":\"po_{{randomValue length=12"
                    + " type='ALPHABETIC'}}\"}";

    private static SimulatedProvider provider;

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private MerchantPayouts payouts;
    @Autowired private FileReception<Connection> reception;
    @Autowired private FileParsing parsing;
    @Autowired private BatchAcceptance acceptance;
    @Autowired private Matching matching;
    @Autowired private PositionProof proof;
    @Autowired private PostingService postingService;
    @Autowired private LedgerAccountStore<Connection> ledgerAccountStore;
    @Autowired private SettlementFileStore<Connection> settlementFileStore;
    @Autowired private TransactionRunner settlementTransactionRunner;
    @Autowired private InternalReferenceLookup internalReferenceLookup;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SessionStore<Connection> sessions = new JdbcSessionStore();

    @BeforeAll
    static void startProvider() {
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
    }

    @AfterAll
    static void stopProvider() {
        provider.close();
    }

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) {
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
        registry.add("finapp.merchant.payout.provider.url", () -> provider.baseUrl());
    }

    @BeforeEach
    void pays() {
        provider.reset();
        provider.succeedsWith(PATH, 200, PAID);
    }

    // ----------------------------------------------------------------- the chain to cash

    @Test
    @Order(1)
    @DisplayName("an open payout leaves PAYOUT_CLEARING explained, its balance read DR−CR; the"
            + " report's fees post against the declared position, each payout is allocated by its"
            + " provider reference, and the bank's DEBIT discharges the remittance - each payout"
            + " CASH_CONFIRMED over the wire, still COMPLETED")
    void aDayOfPayoutsEndsCashConfirmed() throws Exception {
        Funded merchant = funded(EUR, "100.00");
        UUID first = paid(merchant, "40.00");
        UUID second = paid(merchant, "12.50");

        PositionProof.PositionVerdict open = verdict(sweep(), EUR);
        assertThat(open.ledgerBalance().minorUnits())
                .as("CR 52.50 read DR−CR - the remainders' own sign")
                .isEqualTo(-52_50);
        assertThat(open.openRemainders().minorUnits()).isEqualTo(-52_50);
        assertThat(open.explained())
                .as("an in-flight payout is explained, never a false alarm (the paid debt)")
                .isTrue();

        String remittanceRef = "PAY-REM-18" + digits();
        UUID report =
                acceptedBatch(PAYOUT_SOURCE,
                        new SimulatedPayoutReports("PAYDAY-" + marker(), "EUR", "2026-09-26",
                                remittanceRef)
                                .with(settledLine(first, "0.25"))
                                .with(settledLine(second, "0.25"))
                                .render());
        assertThat(entryLines(batchEntry(report)))
                .as("hop 1 posts only the provider's fees - the liability grows")
                .containsExactlyInAnyOrder("PROCESSING_COSTS:DEBIT:50", "PAYOUT_CLEARING:CREDIT:50");
        assertThat(one("SELECT direction || ':' || amount_minor::text || ':' || position_purpose"
                        + " FROM reconciliation.expectation WHERE kind = 'REMITTANCE' AND"
                        + " operation_ref = ?", report.toString()))
                .isEqualTo("OUTBOUND:5300:PAYOUT_CLEARING");

        matchUntilQuiet();
        for (UUID payout : List.of(first, second)) {
            assertThat(one("SELECT status FROM reconciliation.expectation WHERE kind ="
                            + " 'MERCHANT_PAYOUT' AND operation_ref = ?", payout.toString()))
                    .isEqualTo("SETTLED");
            assertThat(one("SELECT d.matched_key_kind FROM reconciliation.match_decision d JOIN"
                            + " reconciliation.allocation a ON a.decision_id = d.id JOIN"
                            + " reconciliation.expectation e ON e.id = a.expectation_id WHERE"
                            + " e.operation_ref = ?", payout.toString()))
                    .isEqualTo("PAYOUT_PROVIDER_REF");
        }
        assertThat(count("SELECT count(*) FROM reconciliation.external_item i JOIN"
                        + " reconciliation.reconciliation_batch r ON r.id = i.run_id WHERE"
                        + " r.batch_id = ? AND i.line_type = 'PAYOUT_FEE' AND i.status ="
                        + " 'CHECKED'", report))
                .as("each fee judged against the pinned 0.25 through its payout")
                .isEqualTo(2);
        assertThat(count("SELECT count(*) FROM reconciliation.break b JOIN"
                        + " reconciliation.external_item i ON i.id = b.external_item_id JOIN"
                        + " reconciliation.reconciliation_batch r ON r.id = i.run_id WHERE"
                        + " r.batch_id = ?", report))
                .as("exactly the terms: no break")
                .isZero();

        UUID statement =
                acceptedBatch(BANK_SOURCE,
                        statement("SB-EUR-1-" + marker(), "EUR", 1, "C,2026-09-27,EUR,0.00",
                                "D,2026-09-28,EUR,53.00",
                                ":61:2026-09-28,D,53.00," + remittanceRef)
                                .getBytes(StandardCharsets.UTF_8));
        assertThat(entryLines(batchEntry(statement)))
                .as("the bank's debit discharges the provider's clearing")
                .containsExactlyInAnyOrder("CASH_AT_BANK:CREDIT:5300", "PAYOUT_CLEARING:DEBIT:5300");
        matchUntilQuiet();
        assertThat(one("SELECT status FROM reconciliation.expectation WHERE kind = 'REMITTANCE'"
                        + " AND operation_ref = ?", report.toString()))
                .isEqualTo("SETTLED");

        Session operator = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        for (UUID payout : List.of(first, second)) {
            HttpResponse<String> status =
                    get(operator.token(), "/v1/operator/reconciliation/settlement-status?kind="
                            + "MERCHANT_PAYOUT&operationRef=" + payout);
            assertThat(status.statusCode()).isEqualTo(200);
            assertThat(status.body()).contains("\"status\":\"CASH_CONFIRMED\"");
            assertThat(payoutStatus(payout))
                    .as("settlement moves no payout (INV-LIFE-04)")
                    .isEqualTo(MerchantPayoutStatus.COMPLETED.name());
        }

        PositionProof.Report settled = sweep();
        PositionProof.PositionVerdict cleared = verdict(settled, EUR);
        assertThat(cleared.explained()).isTrue();
        assertThat(cleared.ledgerBalance().isZero())
                .as("settled payouts leave PAYOUT_CLEARING explained to zero after cash")
                .isTrue();
        assertThat(settled.cashOf(EUR).orElseThrow().explained()).isTrue();
        assertThat(settled.unattributedByPurpose().getOrDefault(AccountPurpose.PAYOUT_CLEARING, 0L))
                .isZero();
        assertTrialBalance();
    }

    // ----------------------------------------------------------------- a returned payout

    @Test
    @Order(2)
    @DisplayName("a returned payout waits UNMATCHED with no break - never a candidate of its own"
            + " payout's expectation - its cash arriving on the statement; after grace it parks as"
            + " RETURN_NOT_APPLICABLE through the REAL lookup, and a four-eyes transfer credits the"
            + " merchant's payable back - the payout still COMPLETED")
    void aReturnedPayoutFallsBackToTheFourEyesTransfer() throws Exception {
        Funded merchant = funded(GBP, "100.00");
        UUID returned = paid(merchant, "20.00");
        UUID kept = paid(merchant, "10.00");

        String firstRemittance = "PAY-REM-18" + digits();
        UUID firstReport =
                acceptedBatch(PAYOUT_SOURCE,
                        new SimulatedPayoutReports("PAYDAY-" + marker(), "GBP", "2026-09-26",
                                firstRemittance)
                                .with(settledLine(returned, "0.25"))
                                .with(settledLine(kept, "0.25"))
                                .render());
        matchUntilQuiet();
        acceptedBatch(BANK_SOURCE,
                statement("SB-GBP-1-" + marker(), "GBP", 1, "C,2026-09-27,GBP,0.00",
                        "D,2026-09-28,GBP,30.50", ":61:2026-09-28,D,30.50," + firstRemittance)
                        .getBytes(StandardCharsets.UTF_8));
        matchUntilQuiet();
        assertThat(one("SELECT status FROM reconciliation.expectation WHERE kind = 'REMITTANCE'"
                        + " AND operation_ref = ?", firstReport.toString()))
                .isEqualTo("SETTLED");

        String secondRemittance = "PAY-REM-18" + digits();
        UUID secondReport =
                acceptedBatch(PAYOUT_SOURCE,
                        new SimulatedPayoutReports("PAYDAY-" + marker(), "GBP", "2026-09-27",
                                secondRemittance)
                                .with(returnedLine(returned))
                                .render());
        assertThat(batchEntryOrNull(secondReport))
                .as("no fee, no recognition - honestly omitted at zero")
                .isNull();
        assertThat(one("SELECT direction || ':' || amount_minor::text FROM"
                        + " reconciliation.expectation WHERE kind = 'REMITTANCE' AND operation_ref"
                        + " = ?", secondReport.toString()))
                .as("the return comes back: the provider owes the platform")
                .isEqualTo("INBOUND:2000");
        matchUntilQuiet();
        UUID item = (UUID) one("SELECT i.id FROM reconciliation.external_item i JOIN"
                + " reconciliation.reconciliation_batch r ON r.id = i.run_id WHERE r.batch_id = ?"
                + " AND i.line_type = 'PAYOUT_RETURNED'", secondReport);
        assertThat(one("SELECT status FROM reconciliation.external_item WHERE id = ?", item))
                .as("no PAYOUT_RETURN opened yet: the return waits")
                .isEqualTo("UNMATCHED");
        assertThat(count("SELECT count(*) FROM reconciliation.break WHERE external_item_id = ?",
                item))
                .as("no break raised by the matcher")
                .isZero();
        assertThat(count("SELECT count(*) FROM reconciliation.match_candidate c JOIN"
                        + " reconciliation.match_decision d ON d.id = c.decision_id JOIN"
                        + " reconciliation.expectation e ON e.id = c.expectation_id WHERE"
                        + " d.external_item_id = ? AND e.kind = 'MERCHANT_PAYOUT'", item))
                .as("an INBOUND return never meets its OUTBOUND payout's expectation")
                .isZero();

        acceptedBatch(BANK_SOURCE,
                statement("SB-GBP-2-" + marker(), "GBP", 2, "D,2026-09-28,GBP,30.50",
                        "D,2026-09-29,GBP,10.50", ":61:2026-09-29,C,20.00," + secondRemittance)
                        .getBytes(StandardCharsets.UTF_8));
        matchUntilQuiet();
        assertThat(one("SELECT status FROM reconciliation.expectation WHERE kind = 'REMITTANCE'"
                        + " AND operation_ref = ?", secondReport.toString()))
                .isEqualTo("SETTLED");
        assertThat(verdict(sweep(), GBP).explained())
                .as("the waiting return's item explains the credit it left")
                .isTrue();

        expireGrace(item);
        matchUntilQuiet();
        assertThat(one("SELECT status FROM reconciliation.external_item WHERE id = ?", item))
                .isEqualTo("PARKED");
        Object[] raised = row("SELECT id, type, cause, value_at_issue_minor,"
                + " internal_classification, internal_operation_ref FROM reconciliation.break"
                + " WHERE external_item_id = ?", item);
        assertThat(new Object[] {raised[1], raised[2], raised[3], raised[4], raised[5]})
                .as("the REAL lookup knows the payout: the return is not the matcher's to apply")
                .containsExactly("REVERSAL_MISMATCH", "RETURN_NOT_APPLICABLE", 20_00L, "COMPLETED",
                        returned.toString());

        Session proposer = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session approver = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        HttpResponse<String> proposed =
                post(proposer.token(), "/breaks/" + raised[0] + "/resolutions",
                        "pay-" + UUID.randomUUID(),
                        "{\"kind\":\"TRANSFER_TO_ACCOUNT\",\"reasonCode\":\"FUNDS_ATTRIBUTED\","
                                + "\"narrative\":\"the payout came back to the merchant\","
                                + "\"targetAccountId\":\"" + merchant.payable().value() + "\"}");
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        HttpResponse<String> approved =
                post(approver.token(), "/resolutions/" + field(proposed.body(), "resolutionId")
                        + "/approval", null, null);
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(entryLines(UUID.fromString(field(approved.body(), "journalEntryId"))))
                .as("the parked value credited back to the merchant, four eyes on it")
                .containsExactlyInAnyOrder(
                        "SUSPENSE_UNMATCHED:DEBIT:2000", "MERCHANT_PAYABLE:CREDIT:2000");
        assertThat(payoutStatus(returned)).isEqualTo(MerchantPayoutStatus.COMPLETED.name());

        PositionProof.Report after = sweep();
        assertThat(verdict(after, GBP).explained()).isTrue();
        assertThat(verdict(after, GBP).ledgerBalance().isZero()).isTrue();
        assertTrialBalance();
    }

    // ----------------------------------------------------------------- a short bank debit

    @Test
    @Order(3)
    @DisplayName("a bank debit short of the remittance is SETTLEMENT_MISMATCH (REMITTANCE_DIFFERS) on"
            + " the remittance, one beyond it parks its excess on the bank line - neither"
            + " difference absorbed, the position explained after each")
    void shortAndExcessDebitsAreRemittanceDifferences() throws Exception {
        Funded merchant = funded(USD, "100.00");
        UUID payout = paid(merchant, "30.00");
        String remittanceRef = "PAY-REM-18" + digits();
        UUID report =
                acceptedBatch(PAYOUT_SOURCE,
                        new SimulatedPayoutReports("PAYDAY-" + marker(), "USD", "2026-09-26",
                                remittanceRef)
                                .with(settledLine(payout, "0.25"))
                                .render());
        matchUntilQuiet();
        acceptedBatch(BANK_SOURCE,
                statement("SB-USD-1-" + marker(), "USD", 1, "C,2026-09-27,USD,0.00",
                        "D,2026-09-28,USD,30.00", ":61:2026-09-28,D,30.00," + remittanceRef)
                        .getBytes(StandardCharsets.UTF_8));
        matchUntilQuiet();
        assertThat(one("SELECT b.type || ':' || b.cause || ':' || b.value_at_issue_minor::text"
                        + " FROM reconciliation.break b JOIN reconciliation.expectation e ON"
                        + " e.id = b.expectation_id WHERE e.kind = 'REMITTANCE' AND"
                        + " e.operation_ref = ?", report.toString()))
                .isEqualTo("SETTLEMENT_MISMATCH:REMITTANCE_DIFFERS:25");
        PositionProof.PositionVerdict usd = verdict(sweep(), USD);
        assertThat(usd.explained()).isTrue();
        assertThat(usd.ledgerBalance().minorUnits())
                .as("the unpaid 0.25 stands on the remittance, visible")
                .isEqualTo(-25);

        // An excess: the bank debits 0.25 more than the day's remittance promised.
        UUID next = paid(merchant, "10.00");
        String excessRef = "PAY-REM-18" + digits();
        UUID excessReport =
                acceptedBatch(PAYOUT_SOURCE,
                        new SimulatedPayoutReports("PAYDAY-" + marker(), "USD", "2026-09-27",
                                excessRef)
                                .with(settledLine(next, "0.25"))
                                .render());
        matchUntilQuiet();
        UUID excessStatement =
                acceptedBatch(BANK_SOURCE,
                        statement("SB-USD-2-" + marker(), "USD", 2, "D,2026-09-28,USD,30.00",
                                "D,2026-09-29,USD,40.50", ":61:2026-09-29,D,10.50," + excessRef)
                                .getBytes(StandardCharsets.UTF_8));
        matchUntilQuiet();
        assertThat(one("SELECT status FROM reconciliation.expectation WHERE kind = 'REMITTANCE'"
                        + " AND operation_ref = ?", excessReport.toString()))
                .as("the remittance takes what it promised")
                .isEqualTo("SETTLED");
        UUID bankItem = (UUID) one("SELECT i.id FROM reconciliation.external_item i JOIN"
                + " reconciliation.reconciliation_batch r ON r.id = i.run_id WHERE r.batch_id = ?",
                excessStatement);
        assertThat(one("SELECT b.type || ':' || b.cause || ':' || b.value_at_issue_minor::text"
                        + " FROM reconciliation.break b WHERE b.external_item_id = ?", bankItem))
                .isEqualTo("SETTLEMENT_MISMATCH:REMITTANCE_DIFFERS:25");
        assertThat(one("SELECT origin || ':' || (amount_minor - released_minor)::text FROM"
                        + " reconciliation.suspense_item WHERE external_item_id = ?", bankItem))
                .as("the excess parks owned, never absorbed into the position")
                .isEqualTo("RECON_PARK:25");
        PositionProof.PositionVerdict after = verdict(sweep(), USD);
        assertThat(after.explained()).isTrue();
        assertThat(after.ledgerBalance().minorUnits())
                .as("only the first day's unpaid 0.25 still stands on the position")
                .isEqualTo(-25);
        assertTrialBalance();
    }

    // ----------------------------------------------------------------- the lookup

    @Test
    @Order(4)
    @DisplayName("the REAL lookup answers a completed payout by its provider reference and by our"
            + " reference, and nothing for a reference no payout holds")
    void theLookupKnowsARealPayout() throws Exception {
        Funded merchant = funded(EUR, "20.00");
        UUID payout = paid(merchant, "5.00");
        Object[] references = row("SELECT provider_reference, provider_idempotency_reference FROM"
                + " merchant.merchant_payout WHERE id = ?", payout);
        UUID source;
        try (Connection app = DatabaseRoles.application()) {
            source = settlementFileStore.sourceByCode(app, PAYOUT_SOURCE).orElseThrow().id();
        }
        for (Map.Entry<KeyKind, String> reference :
                Map.of(KeyKind.PAYOUT_PROVIDER_REF, (String) references[0],
                        KeyKind.OUR_REF, (String) references[1]).entrySet()) {
            try (Connection app = DatabaseRoles.application()) {
                InternalReferenceLookup.InternalReference known =
                        internalReferenceLookup.classify(
                                app,
                                new InternalReferenceLookup.LookupSubject(
                                        Optional.empty(),
                                        Map.of(reference.getKey(), reference.getValue()),
                                        Optional.of(source)));
                assertThat(known.classification()).isEqualTo(InternalClassification.COMPLETED);
                assertThat(known.subject()).contains(InternalSubject.PAYOUT);
                assertThat(known.operationRef()).contains(payout.toString());
            }
        }
        try (Connection app = DatabaseRoles.application()) {
            assertThat(internalReferenceLookup.classify(
                                    app,
                                    new InternalReferenceLookup.LookupSubject(
                                            Optional.empty(),
                                            Map.of(KeyKind.PAYOUT_PROVIDER_REF, "po_nobody"),
                                            Optional.of(source)))
                            .classification())
                    .isEqualTo(InternalClassification.UNKNOWN);
        }
    }

    // ----------------------------------------------------------------- ten deliveries

    @Test
    @Order(5)
    @DisplayName("the same payout report delivered ten ways at once: one file, one batch, one run,"
            + " one recognition entry")
    void tenDeliveriesOfOneReport() throws Exception {
        String marker = marker();
        byte[] bytes =
                new SimulatedPayoutReports("PAYDAY-TEN-" + marker, "EUR", "2026-09-26",
                        "PAY-REM-18" + digits())
                        .with(SimulatedPayoutReports.Entry.settled("1.00", "0.25",
                                "po_ten" + marker.toLowerCase(), "pyo-" + IDS.next()))
                        .render();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<FileReception.Result>> deliveries = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            deliveries.add(pool.submit(() -> {
                start.await();
                return receive(PAYOUT_SOURCE, bytes);
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

    // ----------------------------------------------------------------- payouts

    private record Funded(MerchantId id, LedgerAccountId payable, CurrencyCode currency) {}

    /**
     * A merchant with an effective destination and a funded payable — the funding a
     * capture-shaped entry (DR settlement clearing / CR payable), as the payout suite funds its
     * merchants.
     */
    private Funded funded(CurrencyCode currency, String amount) throws Exception {
        MerchantId merchant = MerchantId.next(IDS);
        OffsetDateTime created =
                OffsetDateTime.ofInstant(
                        Instant.now(CLOCK).truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
        execute("INSERT INTO merchant.merchant (id, party_ref, legal_name, display_name,"
                        + " settlement_currency, status, created_at, status_changed_at) VALUES"
                        + " (?, ?, 'Acme GmbH', 'Acme', ?, 'ACTIVE', ?, ?)",
                merchant.value(), UUID.randomUUID(), currency.code(), created, created);
        execute("INSERT INTO merchant.payout_destination (id, merchant_id, destination_reference,"
                        + " display_suffix, status, proposed_by, proposed_at, proposal_reason,"
                        + " approved_by, approved_at, cooling_off_until, effective_at) VALUES"
                        + " (?, ?, ?, '3000', 'EFFECTIVE', 'fixture-a', now() - interval '4 days',"
                        + " 'fixture', 'fixture-b', now() - interval '4 days', now() - interval"
                        + " '1 day', now() - interval '1 hour')",
                IDS.next(), merchant.value(),
                "pdr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        LedgerAccountId payable =
                asOperator(uow -> ledgerAccountStore
                        .createOrConverge(
                                uow,
                                LedgerAccount.owned(IDS, CLOCK, AccountType.LIABILITY,
                                        AccountPurpose.MERCHANT_PAYABLE, currency,
                                        merchant.value()))
                        .account()
                        .id());
        Money funding = money(amount, currency);
        asOperator(uow -> {
            LedgerAccount clearing =
                    new ChartOfAccounts<>(ledgerAccountStore)
                            .resolve(uow, AccountPurpose.SETTLEMENT_CLEARING, currency);
            LocalDate today = LocalDate.now(CLOCK.withZone(ZoneOffset.UTC));
            UUID reference = UUID.randomUUID();
            return postingService.post(
                    uow,
                    new PostingCommand(
                            "payout-settlement-fixture:" + reference, today, today,
                            reference.toString(),
                            List.of(new JournalLine(clearing.id(), Direction.DEBIT, funding),
                                    new JournalLine(payable, Direction.CREDIT, funding))));
        });
        return new Funded(merchant, payable, currency);
    }

    /** A payout through the REAL flow, paid by the simulated provider: COMPLETED, expected. */
    private UUID paid(Funded merchant, String amount) {
        MerchantPayouts.Initiated initiated =
                asMerchant(merchant, () -> payouts.initiate(
                        new MerchantPayouts.InitiateCommand(
                                merchant.id(), money(amount, merchant.currency()),
                                "pay-" + UUID.randomUUID(), Optional.empty(), Optional.empty())));
        assertThat(initiated.status()).isEqualTo(MerchantPayoutStatus.COMPLETED);
        return initiated.payout().value();
    }

    /** The report's line for a payout, read off {@code merchant.merchant_payout}. */
    private static SimulatedPayoutReports.Entry settledLine(UUID payout, String fee)
            throws SQLException {
        Object[] stored = row("SELECT provider_reference, provider_idempotency_reference,"
                + " amount_minor, scale FROM merchant.merchant_payout WHERE id = ?", payout);
        return SimulatedPayoutReports.Entry.settled(
                decimal(stored), fee, (String) stored[0], (String) stored[1]);
    }

    private static SimulatedPayoutReports.Entry returnedLine(UUID payout) throws SQLException {
        Object[] stored = row("SELECT provider_reference, provider_idempotency_reference,"
                + " amount_minor, scale FROM merchant.merchant_payout WHERE id = ?", payout);
        return SimulatedPayoutReports.Entry.returned(
                decimal(stored), (String) stored[0], (String) stored[1]);
    }

    private static String decimal(Object[] stored) {
        return BigDecimal.valueOf(((Number) stored[2]).longValue(), ((Number) stored[3]).intValue())
                .setScale(((Number) stored[3]).intValue(), RoundingMode.UNNECESSARY)
                .toPlainString();
    }

    private static String payoutStatus(UUID payout) throws SQLException {
        return (String) one("SELECT status FROM merchant.merchant_payout WHERE id = ?", payout);
    }

    private static Money money(String amount, CurrencyCode currency) {
        return Money.of(new BigDecimal(amount), currency);
    }

    // ----------------------------------------------------------------- evidence

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

    private static UUID batchEntry(UUID batch) throws SQLException {
        UUID entry = batchEntryOrNull(batch);
        assertThat(entry).as("the batch's recognition entry").isNotNull();
        return entry;
    }

    private static UUID batchEntryOrNull(UUID batch) throws SQLException {
        return (UUID) one("SELECT journal_entry_id FROM settlement.batch WHERE id = ?", batch);
    }

    private void matchUntilQuiet() {
        for (int i = 0; i < 4; i++) {
            matching.sweep();
        }
    }

    /** The stored window moved, never the clock: expiry stays a database-clock fact. */
    private static void expireGrace(UUID item) throws SQLException {
        execute("UPDATE reconciliation.external_item SET grace_until = now() - interval '1 hour'"
                + " WHERE id = ?", item);
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

    // ----------------------------------------------------------------- the proofs

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

    private static PositionProof.PositionVerdict verdict(
            PositionProof.Report report, CurrencyCode currency) {
        return report.verdicts().stream()
                .filter(v -> v.purpose() == AccountPurpose.PAYOUT_CLEARING
                        && v.currency().equals(currency))
                .findFirst()
                .orElseThrow();
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

    // ----------------------------------------------------------------- the doors

    private HttpResponse<String> get(String token, String path) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String token, String path, String key, String body)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port
                                + "/v1/operator/reconciliation" + path))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String field(String json, String name) {
        Matcher matcher = Pattern.compile("\"" + name + "\":\"([^\"]+)\"").matcher(json);
        assertThat(matcher.find()).as(name + " in " + json).isTrue();
        return matcher.group(1);
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
        execute("INSERT INTO party.party (id, kind, display_name, registered_at)"
                        + " VALUES (?, 'PERSON', 'Payout Desk Person', now())",
                party);
        execute("INSERT INTO identity.identity (id, party_id, login_identifier, status,"
                        + " created_at, status_changed_at) VALUES (?, ?, ?, 'ACTIVE',"
                        + " now(), now())",
                identity, party,
                "po" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        return IdentityId.of(identity);
    }

    private String givenASessionFor(IdentityId identity) throws SQLException {
        byte[] bytes = new byte[32];
        RANDOMNESS.nextBytes(bytes);
        String plaintext = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        com.finapp.identity.Session.Draft session =
                com.finapp.identity.Session.issue(
                        IDS, CLOCK, identity, SessionToken.of(plaintext),
                        AssuranceLevel.PASSWORD, SessionPolicy.current());
        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, session);
        }
        return plaintext;
    }

    // ----------------------------------------------------------------- plumbing

    private <R> R asMerchant(Funded merchant, Supplier<R> work) {
        try (CorrelationContext.Scope scope = CorrelationContext.enter(flow());
                SecurityContext.Scope acting =
                        SecurityContext.enter(
                                new Actor(merchant.id().value().toString(), ActorType.MERCHANT))) {
            return work.get();
        }
    }

    private <R> R asOperator(Function<Connection, R> work) throws Exception {
        try (CorrelationContext.Scope scope = CorrelationContext.enter(flow());
                SecurityContext.Scope acting =
                        SecurityContext.enter(
                                new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER));
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            R result = work.apply(app);
            app.commit();
            return result;
        }
    }

    private static Correlation flow() {
        return Correlation.startingWith(CorrelationId.of("p8t18-" + UUID.randomUUID()))
                .causing(CausationId.of("p8t18-cause"));
    }

    /** Letters and digits, a letter first: never a digit run of card length. */
    private static String marker() {
        return "M" + UUID.randomUUID().toString().replace("-", "").substring(0, 9).toUpperCase();
    }

    private static String digits() {
        return String.valueOf(10_000_000 + RANDOMNESS.nextInt(89_999_999));
    }

    private static void execute(String sql, Object... args) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement statement = app.prepareStatement(sql)) {
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

    private static Object[] row(String sql, Object... args) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement statement = app.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).as("a row for " + sql).isTrue();
                Object[] values = new Object[result.getMetaData().getColumnCount()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = result.getObject(i + 1);
                }
                return values;
            }
        }
    }

    private static long count(String sql, Object... args) throws SQLException {
        return ((Number) one(sql, args)).longValue();
    }
}
