package com.finapp.app.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.settlement.SimulatedBankStatements;
import com.finapp.app.settlement.SimulatedSchemeReports;
import com.finapp.app.settlement.SimulatedSettlementReports;
import com.finapp.identity.Authorization;
import com.finapp.identity.RoleName;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingService;
import com.finapp.ledger.TrialBalance;
import com.finapp.merchant.MerchantPayouts;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.reconciliation.ExpectationDirection;
import com.finapp.reconciliation.ExpectationKind;
import com.finapp.reconciliation.ExpectationRegister;
import com.finapp.reconciliation.ExternalLineType;
import com.finapp.reconciliation.KeyKind;
import com.finapp.reconciliation.Matching;
import com.finapp.reconciliation.NewExpectation;
import com.finapp.reconciliation.RuleSetAdministration;
import com.finapp.reconciliation.RuleSetProposal;
import com.finapp.reconciliation.RuleSetStatus;
import com.finapp.reconciliation.RuleSetStore;
import com.finapp.reconciliation.RuleSets;
import com.finapp.reconciliation.RunReplays;
import com.finapp.settlement.BatchAcceptance;
import com.finapp.settlement.DeliveryChannel;
import com.finapp.settlement.FileParsing;
import com.finapp.settlement.FileReception;
import com.finapp.settlement.SettlementAuditAction;
import com.finapp.settlement.SettlementFileStore;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
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
 * <strong>JPY and BHD become postable</strong> (`P9-TSK-003`, ADR-0074 D27, owner decision O6):
 * a zero- and a three-minor-unit currency carried end to end over the REAL composition - the
 * rule-set v2 successors activated through the four-eyes door ({@link JpyAndBhdRuleSets}), then
 * money moved through the application's own doors and reconciled to the minor unit.
 *
 * <ol>
 *   <li><strong>Before v2 - the recorded behaviour the runbook orders around.</strong> A JPY card
 *       sale captures, its expectation opens under v1 and the PSP's capture line allocates to it;
 *       but v1 prices no JPY fee, so the {@code PROCESSING_FEE} line is priced at ZERO
 *       ({@code FeeCheck}'s conservative default) against a zero bound, and the whole reported
 *       fee stands at issue as a {@code FEE_MISMATCH}. That is why the activation precedes the
 *       first JPY or BHD report - and the decision it leaves is the replay case's subject.
 *   <li><strong>The successors, through the door.</strong> Each seeded source's v2 restates v1
 *       whole and adds O6's rows exactly; v1 retires in the activation's transaction; the
 *       proposer is not the approver; a second activation writes nothing; and a BHD fee row at
 *       scale 2 is refused by the proposal door with nothing stored.
 *   <li><strong>A JPY card pay-in, end to end (case a).</strong> A merchant settling in JPY, a
 *       checkout captured in JPY (the merchant fee in yen, 0 minor units, four lines exact), the
 *       PSP's {@code SIM_PSP_CSV} report accepted - the capture allocated, its
 *       {@code PROCESSING_FEE} CHECKED against the JPY schedule (round_half_up(1.5% x gross) +
 *       40, no break) - and the bank's {@code SIM_STATEMENT_TAGGED} statement on
 *       {@code SIMBANK-JPY-01} recognising the cash, its own fee CHECKED at 75 yen: the
 *       operation reads {@code CASH_CONFIRMED}, the cash proof and the trial balance hold.
 *   <li><strong>A BHD scheme fee and a BHD bank fee (case b)</strong>, each CHECKED against its
 *       BHD row - 0 + 40 fils, 0 + 200 fils - the cycle discharged to cash on
 *       {@code SIMBANK-BHD-01}.
 *   <li><strong>A BHD merchant fee in fils (case c)</strong>: a BHD capture whose fee lands at 3
 *       minor units, the books exact, and its PSP fee CHECKED against the BHD row (+ 100 fils).
 *   <li><strong>History replays under its pinned version (case d)</strong>: the decisions made
 *       under v1 before the activation replay {@code IDENTICAL} after it - the zero-priced JPY
 *       fee included, which the v2 schedule would price otherwise - and the decisions made after
 *       it pin v2 and replay {@code IDENTICAL} too.
 * </ol>
 *
 * <p><strong>Its own container.</strong> Run alone ({@code :app:databaseTest --tests
 * "*JpyAndBhdPostableDatabaseTest"}): the first case needs the seeded v1s ACTIVE, the activation
 * re-pins every seeded source for the rest of the container's life (a suite asserting a v1
 * identifier must not follow it), and the JPY and BHD bank chains - one statement sequence per
 * currency for the whole database - are this suite's alone. The proofs are read relative to a
 * baseline wherever a shared reading could carry another suite's residue.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("JPY and BHD become postable: v2 through four eyes, reconciled end to end (P9-TSK-003)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class JpyAndBhdPostableDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final CurrencyCode JPY = JpyAndBhdRuleSets.JPY;
    private static final CurrencyCode BHD = JpyAndBhdRuleSets.BHD;

    private static final String PSP = "simulated-psp.settlement";
    private static final String SCHEME = "simulated-scheme.cycle-report";
    private static final String BANK = "simulated-bank.statement";

    /** The PSP's published rate in every v1 and v2 row: 1.5%, HALF_UP (reconciliation V002). */
    private static final BigDecimal PSP_RATE = new BigDecimal("0.015000");

    /** StormTraffic's merchant pricing: 2.9% plus 30 minor units, HALF_EVEN. */
    private static final BigDecimal MERCHANT_RATE = new BigDecimal("0.029");

    private static final long MERCHANT_FIXED = 30L;

    private static SimulatedProvider provider;

    /** The world the ordered cases share (one Spring context, one container). */
    private static StormTraffic traffic;

    private static StormTraffic.Customer customer;
    private static StormTraffic.Staff administrator;
    private static StormTraffic.Staff operator;
    private static UUID preActivationRun;
    private static UUID postActivationRun;
    private static Map<UUID, UUID> successors;

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) {
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
        registry.add("finapp.paymentmethods.tokenisation.url", () -> provider.baseUrl());
        registry.add("finapp.paymentmethods.tokenisation.timeout", () -> "PT10S");
        registry.add("finapp.payments.provider.url", () -> provider.baseUrl());
        registry.add("finapp.payments.provider.timeout", () -> "PT10S");
        registry.add("finapp.payments.webhook.key",
                () -> Base64.getEncoder().encodeToString(StormTraffic.CARD_WEBHOOK_KEY));
        registry.add("finapp.payments.instant.url", () -> provider.baseUrl());
        registry.add("finapp.payments.instant.timeout", () -> "PT10S");
        registry.add("finapp.payments.instant.webhook.key",
                () -> Base64.getEncoder().encodeToString(StormTraffic.INSTANT_WEBHOOK_KEY));
        registry.add("finapp.merchant.payout.provider.url", () -> provider.baseUrl());
    }

    @AfterAll
    static void stopTheWorld() {
        if (traffic != null) {
            traffic.close();
        }
        if (provider != null) {
            provider.close();
        }
    }

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private MerchantPayouts merchantPayouts;
    @Autowired private RuleSetAdministration ruleSetAdministration;
    @Autowired private RuleSets ruleSets;
    @Autowired private RunReplays runReplays;
    @Autowired private FileReception<Connection> reception;
    @Autowired private FileParsing parsing;
    @Autowired private BatchAcceptance acceptance;
    @Autowired private Matching matching;
    @Autowired private PositionProof proof;
    @Autowired private PostingService postingService;
    @Autowired private LedgerAccountStore<Connection> ledgerAccountStore;
    @Autowired private ExpectationRegister expectationRegister;
    @Autowired private SettlementFileStore<Connection> settlementFileStore;
    @Autowired private com.finapp.settlement.TransactionRunner settlementTransactionRunner;

    @BeforeEach
    void theWorld() throws Exception {
        if (traffic == null) {
            traffic = new StormTraffic(port, provider, authorization, merchantPayouts);
            traffic.stubTheRails("CYC-UNUSED-" + StormTraffic.letters(6));
            administrator = traffic.staff(RoleName.MERCHANT_ADMINISTRATOR);
            operator = traffic.staff(RoleName.RECONCILIATION_OPERATOR);
            customer = traffic.customer();
        }
    }

    // ----------------------------------------------------------------- before v2

    @Test
    @Order(1)
    @DisplayName("before v2 (the recorded behaviour): a JPY capture allocates under v1, but its"
            + " PROCESSING_FEE meets no JPY schedule - priced at zero against a zero bound, the"
            + " whole fee a FEE_MISMATCH")
    void beforeTheSuccessorsAJpyFeeMeetsNoSchedule() throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            for (UUID source : JpyAndBhdRuleSets.SEEDED_SOURCES) {
                assertThat(ruleSets.activeFor(app, source).id())
                        .as("this suite runs in its own container: the seeded v1 is ACTIVE")
                        .isEqualTo(JpyAndBhdRuleSets.SEEDED_V1.get(source));
            }
        }
        LocalDate today = today();
        Sale sale = sale(JPY, 10_000);
        UUID v1 = JpyAndBhdRuleSets.SEEDED_V1.get(JpyAndBhdRuleSets.PSP_SOURCE);
        assertThat(one("SELECT rule_set_id FROM reconciliation.expectation WHERE id = ?",
                        sale.expectation()))
                .as("the capture's expectation pins the ACTIVE v1")
                .isEqualTo(v1);
        long fee = pspFee(JPY, sale.gross());
        assertThat(fee).as("what the PSP charges: round_half_up(1.5%% x 10000) + 40").isEqualTo(190L);

        UUID batch = accepted(PSP, pspReport(JPY, today, sale, fee, pspRemittance()));
        matchUntilQuiet();
        preActivationRun = runOf(batch);
        assertThat(one("SELECT status FROM reconciliation.expectation WHERE id = ?",
                        sale.expectation()))
                .as("the capture line allocates - matching rules are currency-blind")
                .isEqualTo("SETTLED");
        assertThat(one("SELECT status FROM reconciliation.external_item WHERE run_id = ? AND"
                        + " line_type = 'PROCESSING_FEE'", preActivationRun))
                .isEqualTo("CHECKED");
        assertThat(feeDecision(preActivationRun, ExternalLineType.PROCESSING_FEE))
                .as("v1 has no JPY schedule and no JPY bound: expected 0, tolerance 0")
                .isEqualTo("0:190:0:" + v1);
        assertThat(rows("SELECT b.type || ':' || b.cause || ':' || b.value_at_issue_minor::text"
                        + " || ':' || b.currency || ':' || b.scale::text FROM reconciliation.break"
                        + " b JOIN reconciliation.external_item i ON i.id = b.external_item_id"
                        + " WHERE i.run_id = ?", preActivationRun))
                .as("the whole reported fee at issue, in yen")
                .containsExactly("FEE_MISMATCH:FEE_BEYOND_TOLERANCE:190:JPY:0");
    }

    // ----------------------------------------------------------------- the successors

    @Test
    @Order(2)
    @DisplayName("the v2 successors through the four-eyes door: each restates v1 whole and adds"
            + " O6's rows exactly, v1 retires, the proposer is not the approver, a second"
            + " activation writes nothing, and a BHD fee row at scale 2 is refused")
    void theSuccessorsAreActivatedThroughTheFourEyesDoor() throws Exception {
        Actor proposer = actorOf(traffic.staff(RoleName.RECONCILIATION_CONTROLLER));
        Actor approver = actorOf(traffic.staff(RoleName.RECONCILIATION_CONTROLLER));
        List<JpyAndBhdRuleSets.Activation> activated =
                JpyAndBhdRuleSets.activate(ruleSetAdministration, proposer, approver);
        assertThat(activated).hasSize(4)
                .allSatisfy(activation -> {
                    assertThat(activation.alreadyActive()).isFalse();
                    assertThat(activation.version()).isEqualTo(2);
                });
        successors = new LinkedHashMap<>();
        for (JpyAndBhdRuleSets.Activation activation : activated) {
            successors.put(activation.sourceId(), activation.ruleSetId());
        }

        try (Connection app = DatabaseRoles.application()) {
            for (UUID source : JpyAndBhdRuleSets.SEEDED_SOURCES) {
                List<RuleSetStore.VersionView> versions =
                        ruleSetAdministration.versions(app, source, 10);
                assertThat(versions).as("v2 and v1, nothing else").hasSize(2);
                RuleSetStore.VersionView v2 = versions.get(0);
                RuleSetStore.VersionView v1 = versions.get(1);
                assertThat(v1.row().id()).isEqualTo(JpyAndBhdRuleSets.SEEDED_V1.get(source));
                assertThat(v1.row().status()).as("retired in the activation's transaction")
                        .isEqualTo(RuleSetStatus.RETIRED);
                assertThat(v2.row().status()).isEqualTo(RuleSetStatus.ACTIVE);
                assertThat(ruleSets.activeFor(app, source).id()).isEqualTo(v2.row().id());
                assertThat(v2.row().proposedBy()).isEqualTo(proposer.id());
                assertThat(v2.row().decidedBy()).contains(approver.id());

                // Restated whole: every v1 member unchanged.
                assertThat(v2.row().fundingLagDays()).isEqualTo(v1.row().fundingLagDays());
                assertThat(v2.row().gainMinAgeDays()).isEqualTo(v1.row().gainMinAgeDays());
                assertThat(v2.lagDays()).isEqualTo(v1.lagDays());
                assertThat(v2.rules()).isEqualTo(v1.rules());
                assertThat(v2.tolerances()).containsAll(v1.tolerances());
                assertThat(v2.feeSchedules()).containsAll(v1.feeSchedules());
                assertThat(v2.severityThresholds()).containsAllEntriesOf(v1.severityThresholds());

                // And O6's rows, exactly - nothing more.
                Map<CurrencyCode, Long> thresholds = new LinkedHashMap<>(v1.severityThresholds());
                thresholds.put(JPY, 150_000L);
                thresholds.put(BHD, 400_000L);
                assertThat(v2.severityThresholds()).isEqualTo(thresholds);
                List<RuleSetProposal.FeeTerms> fees = new ArrayList<>(v1.feeSchedules());
                fees.addAll(o6FeeRows(source));
                assertThat(v2.feeSchedules()).containsExactlyInAnyOrderElementsOf(fees);
                List<RuleSetProposal.Tolerance> tolerances = new ArrayList<>(v1.tolerances());
                if (source.equals(JpyAndBhdRuleSets.PSP_SOURCE)) {
                    tolerances.add(bound("PROCESSING_FEE_PER_LINE", JPY, 3));
                    tolerances.add(bound("PROCESSING_FEE_PER_LINE", BHD, 10));
                    tolerances.add(bound("PROCESSING_FEE_PER_BATCH", JPY, 75));
                    tolerances.add(bound("PROCESSING_FEE_PER_BATCH", BHD, 200));
                }
                assertThat(v2.tolerances()).containsExactlyInAnyOrderElementsOf(tolerances);
            }
        }
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                        + " 'reconciliation.RuleSetActivated' AND actor_id = ?", approver.id()))
                .as("each activation reasoned and audited, the approver its actor")
                .isEqualTo(4);

        // Converges: a second call (another suite in a shared container) writes nothing.
        List<JpyAndBhdRuleSets.Activation> again =
                JpyAndBhdRuleSets.activate(ruleSetAdministration, proposer, approver);
        assertThat(again).allSatisfy(activation -> {
            assertThat(activation.alreadyActive()).isTrue();
            assertThat(activation.ruleSetId()).isEqualTo(successors.get(activation.sourceId()));
        });
        assertThat(count("SELECT count(*) FROM reconciliation.rule_set WHERE source_id IN (?, ?,"
                        + " ?, ?)", JpyAndBhdRuleSets.SEEDED_SOURCES.toArray()))
                .as("no third version")
                .isEqualTo(8);

        // The door: a BHD fee schedule entered at scale 2 would read its fixed part ten times
        // too small - refused before anything is stored (INV-MON-05).
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            RuleSetStore.VersionView bank =
                    JpyAndBhdRuleSets.activeVersion(
                            ruleSetAdministration, app, JpyAndBhdRuleSets.BANK_SOURCE);
            List<RuleSetProposal.FeeTerms> misScaled =
                    bank.feeSchedules().stream()
                            .map(terms -> terms.currency().equals(BHD)
                                    ? new RuleSetProposal.FeeTerms(terms.lineType(), BHD,
                                            terms.rate(), terms.fixedMinor(), 2,
                                            terms.roundingPolicy())
                                    : terms)
                            .toList();
            RuleSetProposal proposal =
                    new RuleSetProposal(
                            bank.row().sourceId(), bank.row().fundingLagDays(),
                            bank.row().gainMinAgeDays(), bank.lagDays(), bank.rules(),
                            bank.tolerances(), misScaled, bank.severityThresholds(),
                            "a BHD bank fee at the wrong scale");
            assertThatThrownBy(() -> ruleSetAdministration.propose(
                            app, proposal, proposer, Instant.now(CLOCK),
                            CorrelationId.generate(IDS)))
                    .isInstanceOf(RuleSetAdministration.RuleSetInvalid.class)
                    .hasMessageContaining("is priced at BHD's 3 minor units, not at scale 2");
            app.rollback();
        }
        assertThat(count("SELECT count(*) FROM reconciliation.rule_set WHERE source_id = ?",
                        JpyAndBhdRuleSets.BANK_SOURCE))
                .as("the refused proposal stored nothing")
                .isEqualTo(2);
    }

    // ----------------------------------------------------------------- case a

    @Test
    @Order(3)
    @DisplayName("case a: a JPY card pay-in end to end - the merchant fee in yen, the PSP's fee"
            + " CHECKED against the JPY schedule with no break, the cash recognised on"
            + " SIMBANK-JPY-01 and CASH_CONFIRMED, the cash proof and the trial balance holding")
    void aJpyCardPayInEndsCashConfirmed() throws Exception {
        UUID pspV2 = successor(JpyAndBhdRuleSets.PSP_SOURCE);
        UUID bankV2 = successor(JpyAndBhdRuleSets.BANK_SOURCE);
        LocalDate today = today();
        Money baseline = unexplained(sweep(), AccountPurpose.SETTLEMENT_CLEARING, JPY);

        Sale sale = sale(JPY, 12_345);
        long merchantFee = merchantFee(sale.gross());
        assertThat(merchantFee).as("round_half_even(2.9%% x 12345 = 358.005) + 30").isEqualTo(388L);
        UUID capture = captureEntry(sale);
        assertThat(entryLines(capture))
                .as("ONE entry, FOUR lines, every one in yen at 0 minor units")
                .containsExactlyInAnyOrder(
                        "SETTLEMENT_CLEARING:DEBIT:12345:JPY:0",
                        "MERCHANT_PAYABLE:CREDIT:12345:JPY:0",
                        "MERCHANT_PAYABLE:DEBIT:388:JPY:0",
                        "FEE_REVENUE:CREDIT:388:JPY:0");
        assertThat(position(sale.payable())).as("the payable is the net").isEqualTo(12_345L - 388L);
        assertThat(one("SELECT amount_minor::text || ':' || currency || ':' || scale::text || ':'"
                        + " || rule_set_id::text FROM reconciliation.expectation WHERE id = ?",
                        sale.expectation()))
                .as("the capture expects its gross in yen, under v2")
                .isEqualTo("12345:JPY:0:" + pspV2);

        long pspFee = pspFee(JPY, sale.gross());
        assertThat(pspFee).as("round_half_up(1.5%% x 12345 = 185.175) + 40").isEqualTo(225L);
        String remittanceRef = pspRemittance();
        UUID report = accepted(PSP, pspReport(JPY, today, sale, pspFee, remittanceRef));
        matchUntilQuiet();
        postActivationRun = runOf(report);
        assertThat(one("SELECT status FROM reconciliation.expectation WHERE id = ?",
                        sale.expectation()))
                .as("the capture line allocated to its expectation")
                .isEqualTo("SETTLED");
        assertThat(one("SELECT status FROM reconciliation.external_item WHERE run_id = ? AND"
                        + " line_type = 'PROCESSING_FEE'", postActivationRun))
                .isEqualTo("CHECKED");
        assertThat(feeDecision(postActivationRun, ExternalLineType.PROCESSING_FEE))
                .as("priced by the JPY schedule, within the JPY bound of 3, under v2")
                .isEqualTo("225:225:3:" + pspV2);
        assertThat(breaksOf(postActivationRun)).as("no FEE_MISMATCH - no break at all").isEmpty();
        UUID remittance =
                (UUID) one("SELECT id FROM reconciliation.expectation WHERE kind = 'REMITTANCE'"
                        + " AND operation_ref = ?", report.toString());
        assertThat(one("SELECT direction || ':' || amount_minor::text || ':' || currency || ':'"
                        + " || scale::text FROM reconciliation.expectation WHERE id = ?",
                        remittance))
                .isEqualTo("INBOUND:12120:JPY:0");

        SimulatedBankStatements statement =
                new SimulatedBankStatements("SB-JPY-1-" + StormTraffic.letters(8), "JPY", 1,
                                today, 0)
                        .credit(today, 12_120, Optional.of(remittanceRef))
                        .fee(today, 75);
        UUID bank = accepted(BANK, statement.render(today));
        assertThat(one("SELECT statement_sequence::text || ':' || opening_minor::text || ':'"
                        + " || closing_minor::text FROM settlement.batch WHERE id = ?", bank))
                .isEqualTo("1:0:12045");
        assertThat(entryLines(journalEntryOf(bank)))
                .as("the recognition in yen: DR cash net / CR the clearing / DR the bank fee")
                .containsExactlyInAnyOrder(
                        "CASH_AT_BANK:DEBIT:12045:JPY:0",
                        "SETTLEMENT_CLEARING:CREDIT:12120:JPY:0",
                        "PROCESSING_COSTS:DEBIT:75:JPY:0");
        matchUntilQuiet();
        UUID bankRun = runOf(bank);
        assertThat(one("SELECT status FROM reconciliation.expectation WHERE id = ?", remittance))
                .as("the bank line discharged the remittance")
                .isEqualTo("SETTLED");
        assertThat(feeDecision(bankRun, ExternalLineType.BANK_FEE))
                .as("the bank's fee CHECKED against its JPY 75, under v2")
                .isEqualTo("75:75:0:" + bankV2);
        assertThat(breaksOf(bankRun)).isEmpty();

        String operation =
                (String) one("SELECT operation_ref FROM reconciliation.expectation WHERE id = ?",
                        sale.expectation());
        HttpResponse<String> status =
                traffic.get("/v1/operator/reconciliation/settlement-status?kind=CARD_CAPTURE"
                        + "&operationRef=" + operation, operator.token());
        assertThat(status.statusCode()).as(status.body()).isEqualTo(200);
        assertThat(status.body()).contains("\"status\":\"CASH_CONFIRMED\"");

        PositionProof.Report proven = sweep();
        PositionProof.CashVerdict cash = proven.cashOf(JPY).orElseThrow();
        assertThat(cash.explained())
                .as("CASH_AT_BANK %s = the chain's closing %s", cash.ledgerBalance(),
                        cash.chainClosing())
                .isTrue();
        assertThat(cash.chainClosing().minorUnits()).isEqualTo(12_045L);
        assertThat(cash.latestSequence()).isEqualTo(1);
        assertThat(unexplained(proven, AccountPurpose.SETTLEMENT_CLEARING, JPY))
                .as("the clearing identity untouched by the whole pay-in")
                .isEqualTo(baseline);
        assertTrialBalance();
    }

    // ----------------------------------------------------------------- case b

    @Test
    @Order(4)
    @DisplayName("case b: a BHD scheme fee and a BHD bank fee, each CHECKED against its BHD row -"
            + " 0 + 40 fils and 0 + 200 fils - the cycle discharged to cash on SIMBANK-BHD-01")
    void aBhdSchemeFeeAndABhdBankFeeAreChecked() throws Exception {
        UUID schemeV2 = successor(JpyAndBhdRuleSets.SCHEME_SOURCE);
        UUID bankV2 = successor(JpyAndBhdRuleSets.BANK_SOURCE);
        LocalDate today = today();
        String marker = StormTraffic.letters(10);
        String cycle = "CYC-" + today + "-C" + marker;
        Money baseline = unexplained(sweep(), AccountPurpose.INSTANT_CLEARING, BHD);
        String payIn = "op-pi-" + UUID.randomUUID();
        seedPayIn(payIn, wallet(BHD), 25_000, BHD, today, cycle,
                List.of(new NewExpectation.ExpectationKey(KeyKind.SCHEME_REF, "SCH-PI-" + marker),
                        new NewExpectation.ExpectationKey(
                                KeyKind.END_TO_END_REF, "E2E-PI-" + marker)));

        String remittanceRef = "SCH-REM-93" + digits();
        UUID report =
                accepted(SCHEME,
                        new SimulatedSchemeReports(cycle, "BHD", today.toString(), remittanceRef)
                                .with(SimulatedSchemeReports.Entry.payIn(
                                        "25.000", "0.040", "SCH-PI-" + marker, "E2E-PI-" + marker))
                                .render());
        assertThat(entryLines(journalEntryOf(report)))
                .as("the scheme's fee recognised in fils against the rail's position")
                .containsExactlyInAnyOrder(
                        "PROCESSING_COSTS:DEBIT:40:BHD:3", "INSTANT_CLEARING:CREDIT:40:BHD:3");
        assertThat(one("SELECT direction || ':' || amount_minor::text || ':' || currency || ':'"
                        + " || scale::text FROM reconciliation.expectation WHERE kind ="
                        + " 'REMITTANCE' AND operation_ref = ?", report.toString()))
                .isEqualTo("INBOUND:24960:BHD:3");
        matchUntilQuiet();
        UUID schemeRun = runOf(report);
        assertThat(one("SELECT status FROM reconciliation.expectation WHERE operation_ref = ?",
                        payIn))
                .isEqualTo("SETTLED");
        assertThat(one("SELECT status FROM reconciliation.external_item WHERE run_id = ? AND"
                        + " line_type = 'SCHEME_FEE'", schemeRun))
                .isEqualTo("CHECKED");
        assertThat(feeDecision(schemeRun, ExternalLineType.SCHEME_FEE))
                .as("0 x gross + 40 fils, under the scheme's v2")
                .isEqualTo("40:40:0:" + schemeV2);
        assertThat(breaksOf(schemeRun)).isEmpty();

        SimulatedBankStatements statement =
                new SimulatedBankStatements("SB-BHD-1-" + marker, "BHD", 1, today, 0)
                        .credit(today, 24_960, Optional.of(remittanceRef))
                        .fee(today, 200);
        UUID bank = accepted(BANK, statement.render(today));
        assertThat(entryLines(journalEntryOf(bank)))
                .containsExactlyInAnyOrder(
                        "CASH_AT_BANK:DEBIT:24760:BHD:3",
                        "INSTANT_CLEARING:CREDIT:24960:BHD:3",
                        "PROCESSING_COSTS:DEBIT:200:BHD:3");
        matchUntilQuiet();
        UUID bankRun = runOf(bank);
        assertThat(one("SELECT status FROM reconciliation.expectation WHERE kind = 'REMITTANCE'"
                        + " AND operation_ref = ?", report.toString()))
                .isEqualTo("SETTLED");
        assertThat(feeDecision(bankRun, ExternalLineType.BANK_FEE))
                .as("0 x gross + 200 fils, under the bank's v2")
                .isEqualTo("200:200:0:" + bankV2);
        assertThat(breaksOf(bankRun)).isEmpty();

        PositionProof.Report proven = sweep();
        PositionProof.CashVerdict cash = proven.cashOf(BHD).orElseThrow();
        assertThat(cash.explained()).isTrue();
        assertThat(cash.chainClosing().minorUnits()).isEqualTo(24_760L);
        assertThat(cash.chainClosing().scale()).isEqualTo(3);
        assertThat(unexplained(proven, AccountPurpose.INSTANT_CLEARING, BHD)).isEqualTo(baseline);
        assertTrialBalance();
    }

    // ----------------------------------------------------------------- case c

    @Test
    @Order(5)
    @DisplayName("case c: a BHD capture whose merchant fee lands in fils, the books exact, and its"
            + " PSP fee CHECKED against the BHD row")
    void aBhdMerchantFeeLandsInFils() throws Exception {
        UUID pspV2 = successor(JpyAndBhdRuleSets.PSP_SOURCE);
        LocalDate today = today();
        Sale sale = sale(BHD, 7_250);
        long merchantFee = merchantFee(sale.gross());
        assertThat(merchantFee).as("round_half_even(2.9%% x 7250 = 210.25) + 30 fils").isEqualTo(240L);
        assertThat(entryLines(captureEntry(sale)))
                .as("7.250 BHD captured, 0.240 BHD the fee - every line at 3 minor units")
                .containsExactlyInAnyOrder(
                        "SETTLEMENT_CLEARING:DEBIT:7250:BHD:3",
                        "MERCHANT_PAYABLE:CREDIT:7250:BHD:3",
                        "MERCHANT_PAYABLE:DEBIT:240:BHD:3",
                        "FEE_REVENUE:CREDIT:240:BHD:3");
        assertThat(position(sale.payable()))
                .as("INV-MER-04: fee + net = gross, to the fils")
                .isEqualTo(7_250L - 240L);
        assertThat(one("SELECT amount_minor::text || ':' || currency || ':' || scale::text FROM"
                        + " reconciliation.expectation WHERE id = ?", sale.expectation()))
                .isEqualTo("7250:BHD:3");

        long pspFee = pspFee(BHD, sale.gross());
        assertThat(pspFee).as("round_half_up(1.5%% x 7250 = 108.75) + 100 fils").isEqualTo(209L);
        UUID report = accepted(PSP, pspReport(BHD, today, sale, pspFee, pspRemittance()));
        matchUntilQuiet();
        UUID run = runOf(report);
        assertThat(one("SELECT status FROM reconciliation.expectation WHERE id = ?",
                        sale.expectation()))
                .isEqualTo("SETTLED");
        assertThat(feeDecision(run, ExternalLineType.PROCESSING_FEE))
                .as("priced by the BHD schedule, within the BHD bound of 10 fils")
                .isEqualTo("209:209:10:" + pspV2);
        assertThat(breaksOf(run)).isEmpty();
        assertTrialBalance();
    }

    // ----------------------------------------------------------------- case d

    @Test
    @Order(6)
    @DisplayName("case d: the decisions made under v1 before the activation replay IDENTICAL"
            + " after it - the zero-priced JPY fee included - and the decisions made after it pin"
            + " v2 and replay IDENTICAL too")
    void earlierDecisionsReplayUnderTheirPinnedVersion() throws Exception {
        assertThat(preActivationRun).as("case 1 ran").isNotNull();
        assertThat(postActivationRun).as("case a ran").isNotNull();
        UUID v1 = JpyAndBhdRuleSets.SEEDED_V1.get(JpyAndBhdRuleSets.PSP_SOURCE);
        UUID v2 = successor(JpyAndBhdRuleSets.PSP_SOURCE);
        assertThat(rows("SELECT DISTINCT d.rule_set_id::text FROM reconciliation.match_decision d"
                        + " JOIN reconciliation.external_item i ON i.id = d.external_item_id"
                        + " WHERE i.run_id = ?", preActivationRun))
                .as("the earlier decisions pin v1")
                .containsExactly(v1.toString());
        assertThat(rows("SELECT DISTINCT d.rule_set_id::text FROM reconciliation.match_decision d"
                        + " JOIN reconciliation.external_item i ON i.id = d.external_item_id"
                        + " WHERE i.run_id = ?", postActivationRun))
                .as("the later decisions pin v2")
                .containsExactly(v2.toString());

        Actor investigator = actorOf(traffic.staff(RoleName.RECONCILIATION_OPERATOR));
        RunReplays.Replay before =
                runReplays.replay(preActivationRun, investigator, CorrelationId.generate(IDS));
        assertThat(before.verdict())
                .as("the v1 decisions re-run under v1: the JPY fee priced at zero again, as"
                        + " decided - %s", before)
                .isEqualTo("IDENTICAL");
        assertThat(before.divergences()).isZero();
        assertThat(before.replayed()).as("the capture's and the fee's decisions")
                .isGreaterThanOrEqualTo(2);
        RunReplays.Replay after =
                runReplays.replay(postActivationRun, investigator, CorrelationId.generate(IDS));
        assertThat(after.verdict()).as("%s", after).isEqualTo("IDENTICAL");
        assertThat(after.replayed()).isGreaterThanOrEqualTo(2);
        assertThat(feeDecision(preActivationRun, ExternalLineType.PROCESSING_FEE))
                .as("history is untouched by the activation")
                .isEqualTo("0:190:0:" + v1);
    }

    // ----------------------------------------------------------------- the world

    /** A card sale as the PSP saw it, and where the platform recorded it. */
    private record Sale(String ref, long gross, UUID payable, UUID expectation) {}

    /** A checkout in {@code currency} through the real doors, read back off the tables. */
    private Sale sale(CurrencyCode currency, long gross) throws Exception {
        StormTraffic.Merchant merchant = traffic.merchant(administrator, currency);
        traffic.cardSale(merchant, customer, gross, currency);
        StormTraffic.Merchant trading = traffic.withPayable(merchant, currency);
        String[] captured =
                ((String) one("SELECT a.capture_provider_reference || '|'"
                        + " || a.captured_amount_minor::text FROM payments.payment_attempt a JOIN"
                        + " payments.payment_intent i ON i.id = a.intent_id WHERE a.status ="
                        + " 'CAPTURED' AND i.credit_account_id = ?", trading.payable()))
                        .split("\\|");
        assertThat(Long.parseLong(captured[1])).isEqualTo(gross);
        UUID expectation =
                (UUID) one("SELECT expectation_id FROM reconciliation.expectation_key WHERE"
                        + " key_kind = 'PSP_CAPTURE_REF' AND key_value = ?", captured[0]);
        assertThat(expectation).as("the capture opened its CARD_CAPTURE expectation").isNotNull();
        return new Sale(captured[0], gross, trading.payable(), expectation);
    }

    private static UUID captureEntry(Sale sale) throws SQLException {
        return (UUID) one("SELECT journal_entry_id FROM reconciliation.expectation WHERE id = ?",
                sale.expectation());
    }

    /** O6's JPY and BHD fee rows for the source's priced line types. */
    private static List<RuleSetProposal.FeeTerms> o6FeeRows(UUID source) {
        ExternalLineType line;
        BigDecimal rate = new BigDecimal("0.000000");
        if (source.equals(JpyAndBhdRuleSets.PSP_SOURCE)) {
            line = ExternalLineType.PROCESSING_FEE;
            rate = PSP_RATE;
        } else if (source.equals(JpyAndBhdRuleSets.SCHEME_SOURCE)) {
            line = ExternalLineType.SCHEME_FEE;
        } else if (source.equals(JpyAndBhdRuleSets.PAYOUT_SOURCE)) {
            line = ExternalLineType.PAYOUT_FEE;
        } else {
            line = ExternalLineType.BANK_FEE;
        }
        long[] fixed =
                switch (line) {
                    case PROCESSING_FEE, PAYOUT_FEE -> new long[] {40, 100};
                    case SCHEME_FEE -> new long[] {15, 40};
                    default -> new long[] {75, 200};
                };
        return List.of(
                new RuleSetProposal.FeeTerms(line, JPY, rate, fixed[0], 0, "HALF_UP"),
                new RuleSetProposal.FeeTerms(line, BHD, rate, fixed[1], 3, "HALF_UP"));
    }

    private static RuleSetProposal.Tolerance bound(
            String comparison, CurrencyCode currency, long minor) {
        return new RuleSetProposal.Tolerance(
                comparison, Optional.of(currency), Optional.of(minor), Optional.empty());
    }

    private static UUID successor(UUID source) {
        assertThat(successors).as("the successors were activated").isNotNull();
        return successors.get(source);
    }

    private static Actor actorOf(StormTraffic.Staff staff) {
        return new Actor(staff.actorId(), ActorType.EMPLOYEE);
    }

    /** The PSP's charge: round_half_up(1.5% x gross) + O6's fixed part, in minor units. */
    private static long pspFee(CurrencyCode currency, long grossMinor) {
        return PSP_RATE.multiply(BigDecimal.valueOf(grossMinor))
                        .setScale(0, RoundingMode.HALF_UP)
                        .longValueExact()
                + JpyAndBhdRuleSets.FIXED_PARTS.get(ExternalLineType.PROCESSING_FEE).get(currency);
    }

    /** The merchant's price: round_half_even(2.9% x gross) + 30, in minor units. */
    private static long merchantFee(long grossMinor) {
        return MERCHANT_RATE.multiply(BigDecimal.valueOf(grossMinor))
                        .setScale(0, RoundingMode.HALF_EVEN)
                        .longValueExact()
                + MERCHANT_FIXED;
    }

    private static byte[] pspReport(
            CurrencyCode currency, LocalDate day, Sale sale, long feeMinor, String remittance) {
        return new SimulatedSettlementReports("PSPB-P9T3-" + StormTraffic.letters(8),
                        currency.code(), day, remittance)
                .with(SimulatedSettlementReports.Line.capture(
                        sale.ref(), "", "", decimal(sale.gross(), currency),
                        decimal(feeMinor, currency)))
                .render();
    }

    /** Minor units as the reports write them: the currency's own scale. */
    private static String decimal(long minor, CurrencyCode currency) {
        return BigDecimal.valueOf(minor, currency.minorUnits()).toPlainString();
    }

    /**
     * A push pay-in seeded WITH its posting (DR the rail's clearing, CR the wallet) and its
     * expectation naming that line, at the currency's own minor units - the clearing identity
     * holds at every step ({@code SchemeCycleCashDatabaseTest}'s seeding, scale-aware).
     */
    private void seedPayIn(
            String operationRef, LedgerAccount wallet, long minor, CurrencyCode currency,
            LocalDate executedOn, String cycle, List<NewExpectation.ExpectationKey> keys)
            throws Exception {
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(flow());
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            LedgerAccount clearing =
                    ledgerAccountStore
                            .findOperational(app, AccountPurpose.INSTANT_CLEARING, currency)
                            .orElseThrow();
            Money amount = Money.ofPersisted(minor, currency, currency.minorUnits());
            UUID entry =
                    postingService
                            .post(
                                    app,
                                    new PostingCommand(
                                            "push-completion:" + operationRef,
                                            executedOn,
                                            executedOn,
                                            operationRef,
                                            List.of(
                                                    new JournalLine(clearing.id(),
                                                            Direction.DEBIT, amount),
                                                    new JournalLine(wallet.id(),
                                                            Direction.CREDIT, amount))))
                            .entryId()
                            .value();
            UUID source = settlementFileStore.sourceByCode(app, SCHEME).orElseThrow().id();
            expectationRegister.open(
                    app,
                    new NewExpectation(
                            ExpectationKind.PUSH_PAY_IN, operationRef,
                            "push-completion:" + operationRef, source,
                            AccountPurpose.INSTANT_CLEARING, clearing.id().value(),
                            ExpectationDirection.INBOUND, amount, Optional.of(entry), executedOn,
                            Optional.of(cycle), executedOn.plusDays(1),
                            ruleSets.activeFor(app, source).id(), keys, Actor.SYSTEM,
                            Instant.now(CLOCK), CorrelationId.generate(IDS)));
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

    // ----------------------------------------------------------------- the legs

    private UUID accepted(String source, byte[] bytes) throws SQLException {
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

    private static UUID runOf(UUID batch) throws SQLException {
        UUID run =
                (UUID) one("SELECT id FROM reconciliation.reconciliation_batch WHERE batch_id = ?",
                        batch);
        assertThat(run).as("batch %s has its run", batch).isNotNull();
        return run;
    }

    private static UUID journalEntryOf(UUID batch) throws SQLException {
        return (UUID) one("SELECT journal_entry_id FROM settlement.batch WHERE id = ?", batch);
    }

    /** The fee line's CHECK decision as expected:reported:tolerance:pinned version. */
    private static String feeDecision(UUID run, ExternalLineType line) throws SQLException {
        List<String> decisions =
                rows("SELECT d.fee_expected_minor::text || ':' || d.fee_reported_minor::text"
                        + " || ':' || d.fee_tolerance_minor::text || ':' || d.rule_set_id::text"
                        + " FROM reconciliation.match_decision d JOIN reconciliation.external_item"
                        + " i ON i.id = d.external_item_id WHERE i.run_id = ? AND i.line_type = ?",
                        run, line.name());
        assertThat(decisions).as("one %s decision in run %s", line, run).hasSize(1);
        return decisions.get(0);
    }

    /** Every break the run's items or the run itself carry, as type:cause:value. */
    private static List<String> breaksOf(UUID run) throws SQLException {
        return rows("SELECT b.type || ':' || b.cause || ':' || b.value_at_issue_minor::text FROM"
                + " reconciliation.break b WHERE b.run_id = ? OR b.external_item_id IN (SELECT"
                + " i.id FROM reconciliation.external_item i WHERE i.run_id = ?)", run, run);
    }

    /** The entry's lines as purpose:direction:minor:currency:scale, read against the chart. */
    private static List<String> entryLines(UUID entryId) throws SQLException {
        assertThat(entryId).as("the entry exists").isNotNull();
        return rows("SELECT a.purpose || ':' || l.direction || ':' || l.amount_minor::text || ':'"
                + " || l.currency || ':' || l.scale::text FROM ledger.journal_line l JOIN"
                + " ledger.ledger_account a ON a.id = l.ledger_account_id WHERE l.entry_id = ?",
                entryId);
    }

    /** An account's position, derived from its postings - credits less debits. */
    private static long position(UUID account) throws SQLException {
        return ((Number) one("SELECT coalesce(sum(CASE direction WHEN 'CREDIT' THEN amount_minor"
                + " ELSE -amount_minor END), 0) FROM ledger.journal_line WHERE ledger_account_id"
                + " = ?", account)).longValue();
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
            TrialBalance.Report trial = new TrialBalance().sweep(app);
            assertThat(trial.outOfBalance())
                    .as("the trial balance is zero per currency (INV-ACC-01)")
                    .isEmpty();
            app.rollback();
        }
    }

    /** The identity's difference: balance - (open remainders - open items). */
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

    // ----------------------------------------------------------------- plumbing

    private static LocalDate today() throws SQLException {
        return ((java.sql.Date) one("SELECT (now() AT TIME ZONE 'UTC')::date")).toLocalDate();
    }

    private static Correlation flow() {
        return Correlation.startingWith(CorrelationId.of("p9t3-" + UUID.randomUUID()))
                .causing(CausationId.of("p9t3-cause"));
    }

    private static String pspRemittance() {
        return "PSP-REM-93" + digits();
    }

    private static String digits() {
        return String.valueOf(10_000_000 + RANDOMNESS.nextInt(89_999_999));
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
}
