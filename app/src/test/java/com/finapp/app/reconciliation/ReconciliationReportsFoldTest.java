package com.finapp.app.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.reconciliation.BreakStatus;
import com.finapp.reconciliation.BreakType;
import com.finapp.reconciliation.ExpectationDirection;
import com.finapp.reconciliation.ExpectationKind;
import com.finapp.reconciliation.ExternalLineType;
import com.finapp.reconciliation.ItemStatus;
import com.finapp.reconciliation.ReportReadings;
import com.finapp.reconciliation.ResolutionKind;
import com.finapp.reconciliation.Severity;
import com.finapp.reconciliation.SuspenseOrigin;
import com.finapp.reconciliation.SuspenseSide;
import com.finapp.settlement.LineDirection;
import com.finapp.settlement.SettlementBatchStore;
import com.finapp.settlement.SettlementLineType;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reports' folds (`P8-TSK-024`, ADR-0072 §2–§3, §6), hermetic on purpose: the bound's
 * truncation over 101 rows, suspense CREDIT and DEBIT gross beside the proof's verdict,
 * unmatched value split by direction with the expectations' window and overdue figures, the
 * summary's four-place match rate (half up) and its ageing bands, and the provider-costs fold's
 * four things over a PSP report's fee, a bank statement's bank fee, a batch accepted and
 * repudiated in the month, a reversal of an earlier month's batch and a disagreeing evidence
 * total. Bank statements live here because the shared container holds none: every suite that
 * writes one runs in a database of its own ({@code own-container}, `X-TSK-016`). The live reads,
 * the audit and the doors
 * ride {@code ReconciliationReportsDatabaseTest}.
 */
@DisplayName("the reconciliation reports' folds (P8-TSK-024)")
class ReconciliationReportsFoldTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode GBP = CurrencyCode.of("GBP");
    private static final CurrencyCode CHF = CurrencyCode.of("CHF");
    private static final LocalDate TODAY = LocalDate.parse("2026-10-01");
    private static final String PSP = "simulated-psp.settlement";
    private static final String BANK = "simulated-bank.statement";

    // ----------------------------------------------------------------- suspense

    @Test
    @DisplayName("101 open items serve the oldest 100, say truncated, and fold all 101")
    void theSuspenseBoundTruncatesButTheTotalsFoldEveryItem() {
        ReconciliationReports.SuspenseFold fold = new ReconciliationReports.SuspenseFold(TODAY);
        for (int i = 0; i < 101; i++) {
            fold.accept(suspense(SuspenseSide.CREDIT, 100, EUR, TODAY.minusDays(200 - i)));
        }

        ReconciliationReports.SuspenseReport report = fold.report(List.of());

        assertThat(report.items()).hasSize(ReconciliationReports.BOUND);
        assertThat(report.truncated()).as("the 101st item is announced, never silent").isTrue();
        assertThat(report.items().get(0).ageDays()).isEqualTo(200);
        assertThat(report.currencies())
                .containsExactly(new ReconciliationReports.SuspenseReportCurrency(
                        "EUR", null, "101.00", 101, "0.00", 0, null, null));
    }

    @Test
    @DisplayName("per currency: the proof's balance and verdict beside CREDIT and DEBIT gross -"
            + " never netted - and a currency with no suspense account shown without a verdict")
    void creditAndDebitAreNeverNettedAndTheVerdictIsTheProofs() {
        ReconciliationReports.SuspenseFold fold = new ReconciliationReports.SuspenseFold(TODAY);
        fold.accept(suspense(SuspenseSide.CREDIT, 10_00, EUR, TODAY.minusDays(3)));
        fold.accept(suspense(SuspenseSide.DEBIT, 4_00, EUR, TODAY.minusDays(2)));
        fold.accept(suspense(SuspenseSide.CREDIT, 2_50, EUR, TODAY));
        fold.accept(suspense(SuspenseSide.DEBIT, 7_00, CHF, TODAY));

        ReconciliationReports.SuspenseReport report =
                fold.report(
                        List.of(
                                new PositionProof.SuspenseVerdict(
                                        EUR, eur(8_50), eur(12_50), eur(4_00), eur(0), 3, true),
                                new PositionProof.SuspenseVerdict(
                                        GBP, Money.ofPersisted(0, GBP, 2),
                                        Money.ofPersisted(0, GBP, 2),
                                        Money.ofPersisted(0, GBP, 2),
                                        Money.ofPersisted(0, GBP, 2), 0, true)));

        assertThat(report.truncated()).isFalse();
        assertThat(report.items()).hasSize(4);
        assertThat(report.items().get(0).ageDays()).isEqualTo(3);
        assertThat(report.items().get(0).breakType()).isEqualTo("UNKNOWN_EXTERNAL");
        assertThat(report.items().get(0).breakSeverity()).isEqualTo("HIGH");
        assertThat(report.items().get(0).unreleased()).isEqualTo("10.00");
        assertThat(report.currencies())
                .as("12.50 CREDIT and 4.00 DEBIT in EUR, never 8.50; GBP's empty proof; CHF's"
                        + " items with nothing to judge them against")
                .containsExactly(
                        new ReconciliationReports.SuspenseReportCurrency(
                                "CHF", null, "0.00", 0, "7.00", 1, null, null),
                        new ReconciliationReports.SuspenseReportCurrency(
                                "EUR", "8.50", "12.50", 2, "4.00", 1, "0.00", true),
                        new ReconciliationReports.SuspenseReportCurrency(
                                "GBP", "0.00", "0.00", 0, "0.00", 0, "0.00", true));
    }

    // ----------------------------------------------------------------- unmatched

    @Test
    @DisplayName("unmatched value per source, currency and DIRECTION: items unmatched and"
            + " parked, expectations in window and overdue, each with count, value and oldest"
            + " age; the open breaks' value at issue CRITICAL first")
    void unmatchedKeepsDirectionsApart() {
        UUID psp = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        ReconciliationReports.UnmatchedFold fold =
                new ReconciliationReports.UnmatchedFold(Map.of(psp, PSP), TODAY);
        UUID breakId = UUID.randomUUID();
        fold.item(unexplained(psp, ExpectationDirection.INBOUND, ItemStatus.UNMATCHED, 5_00,
                TODAY.minusDays(4), Optional.empty()));
        fold.item(unexplained(psp, ExpectationDirection.OUTBOUND, ItemStatus.UNMATCHED, 3_00,
                TODAY.minusDays(1), Optional.empty()));
        fold.item(unexplained(psp, ExpectationDirection.INBOUND, ItemStatus.UNMATCHED, 1_25,
                TODAY.minusDays(1), Optional.empty()));
        fold.item(unexplained(psp, ExpectationDirection.INBOUND, ItemStatus.PARKED, 9_00,
                TODAY.minusDays(9),
                Optional.of(new ReportReadings.OpenBreak(
                        breakId, BreakType.UNKNOWN_EXTERNAL, Severity.MEDIUM))));
        fold.item(unexplained(stranger, ExpectationDirection.INBOUND, ItemStatus.UNMATCHED,
                1_00, TODAY, Optional.empty()));
        fold.expectation(expectation(psp, ExpectationDirection.INBOUND, 20_00,
                TODAY.minusDays(2), Optional.empty()));
        fold.expectation(expectation(psp, ExpectationDirection.INBOUND, 30_00,
                TODAY.minusDays(12), Optional.of(Instant.parse("2026-09-25T00:00:00Z"))));
        fold.openBreak(breakLine(psp, BreakType.UNKNOWN_EXTERNAL, Severity.MEDIUM, 9_00,
                BreakStatus.OPEN, TODAY.minusDays(9), Optional.empty()));
        fold.openBreak(breakLine(psp, BreakType.MISSING_EXTERNAL, Severity.CRITICAL, 30_00,
                BreakStatus.OPEN, TODAY.minusDays(1), Optional.empty()));
        fold.openBreak(breakLine(psp, BreakType.UNKNOWN_EXTERNAL, Severity.MEDIUM, 1_00,
                BreakStatus.INVESTIGATING, TODAY, Optional.empty()));

        ReconciliationReports.UnmatchedReport report = fold.report();

        assertThat(report.truncated()).isFalse();
        assertThat(report.items()).hasSize(5);
        assertThat(report.items().get(3).breakId()).isEqualTo(breakId.toString());
        assertThat(report.items().get(3).breakSeverity()).isEqualTo("MEDIUM");
        assertThat(report.items().get(3).ageDays()).isEqualTo(9);
        assertThat(report.items().get(0).graceUntil()).isEqualTo("2026-10-03T00:00:00Z");
        assertThat(report.groups())
                .as("by source (an undeclared one as its id, which sorts before any code"
                        + " beginning with a letter past 'f'), currency and direction")
                .containsExactly(
                        new ReconciliationReports.UnmatchedReportGroup(
                                stranger.toString(), "EUR", "INBOUND",
                                figure(1, "1.00", 0L),
                                figure(0, "0.00", null),
                                figure(0, "0.00", null),
                                figure(0, "0.00", null)),
                        new ReconciliationReports.UnmatchedReportGroup(
                                PSP, "EUR", "INBOUND",
                                figure(2, "6.25", 4L),
                                figure(1, "9.00", 9L),
                                figure(1, "20.00", 2L),
                                figure(1, "30.00", 12L)),
                        new ReconciliationReports.UnmatchedReportGroup(
                                PSP, "EUR", "OUTBOUND",
                                figure(1, "3.00", 1L),
                                figure(0, "0.00", null),
                                figure(0, "0.00", null),
                                figure(0, "0.00", null)));
        assertThat(report.openBreaks())
                .as("worst first: CRITICAL before MEDIUM")
                .containsExactly(
                        new ReconciliationReports.UnmatchedReportBreaks(
                                "MISSING_EXTERNAL", "CRITICAL", "EUR", 1, "30.00"),
                        new ReconciliationReports.UnmatchedReportBreaks(
                                "UNKNOWN_EXTERNAL", "MEDIUM", "EUR", 2, "10.00"));
    }

    // ----------------------------------------------------------------- summary

    @Test
    @DisplayName("the match rate is a ratio of counts to four places, rounded half up, never a"
            + " double - and absent without a base")
    void theMatchRateIsFourPlacesHalfUp() {
        assertThat(ReconciliationReports.ratioOf(2, 3)).isEqualTo("0.6667");
        assertThat(ReconciliationReports.ratioOf(1, 3)).isEqualTo("0.3333");
        assertThat(ReconciliationReports.ratioOf(1, 8)).isEqualTo("0.1250");
        assertThat(ReconciliationReports.ratioOf(1, 20_000))
                .as("0.00005 rounds HALF_UP to 0.0001, where half-even would give 0.0000")
                .isEqualTo("0.0001");
        assertThat(ReconciliationReports.ratioOf(3, 3)).isEqualTo("1.0000");
        assertThat(ReconciliationReports.ratioOf(0, 0)).isNull();
    }

    @Test
    @DisplayName("the ageing bands are 0-2, 3-7, 8-30 and over 30 days, boundaries inclusive")
    void theAgeingBandsHaveTheirBoundaries() {
        assertThat(ReconciliationReports.SummaryFold.bandOf(0)).isZero();
        assertThat(ReconciliationReports.SummaryFold.bandOf(2)).isZero();
        assertThat(ReconciliationReports.SummaryFold.bandOf(3)).isEqualTo(1);
        assertThat(ReconciliationReports.SummaryFold.bandOf(7)).isEqualTo(1);
        assertThat(ReconciliationReports.SummaryFold.bandOf(8)).isEqualTo(2);
        assertThat(ReconciliationReports.SummaryFold.bandOf(30)).isEqualTo(2);
        assertThat(ReconciliationReports.SummaryFold.bandOf(31)).isEqualTo(3);
    }

    @Test
    @DisplayName("the summary folds per declared source: batches, items by disposition with"
            + " value, the match rate without repudiated items, breaks raised and resolved with"
            + " value at issue, resolutions approved with the value they posted, and the breaks"
            + " open at the day's end by band - exact as of the date")
    void theSummaryFoldsPerSource() {
        UUID psp = UUID.randomUUID();
        UUID bank = UUID.randomUUID();
        Instant from = TODAY.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant until = TODAY.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        ReconciliationReports.SummaryFold fold =
                new ReconciliationReports.SummaryFold(
                        Map.of(psp, PSP, bank, BANK), TODAY, from, until);
        fold.batches(psp, 2L);
        fold.item(day(psp, ItemStatus.MATCHED, 10_00));
        fold.item(day(psp, ItemStatus.MATCHED, 2_00));
        fold.item(day(psp, ItemStatus.CHECKED, 1_75));
        fold.item(day(psp, ItemStatus.UNMATCHED, 6_00));
        fold.item(day(psp, ItemStatus.PARKED, 4_00));
        fold.item(day(psp, ItemStatus.REPUDIATED, 99_00));
        // Raised today, still open: band 0-2.
        fold.breakLine(breakLine(psp, BreakType.UNKNOWN_EXTERNAL, Severity.HIGH, 6_00,
                BreakStatus.OPEN, TODAY, Optional.empty()));
        // Raised five days ago, resolved today: resolved, not open at the end.
        fold.breakLine(breakLine(psp, BreakType.AMOUNT_MISMATCH, Severity.MEDIUM, 50,
                BreakStatus.RESOLVED, TODAY.minusDays(5), Optional.of(from.plusSeconds(60))));
        // Raised ten days ago, resolved tomorrow: open at today's end, band 8-30.
        fold.breakLine(breakLine(psp, BreakType.MISSING_EXTERNAL, Severity.LOW, 3_00,
                BreakStatus.RESOLVED, TODAY.minusDays(10), Optional.of(until.plusSeconds(1))));
        // Raised forty days ago, open: band >30.
        fold.breakLine(breakLine(psp, BreakType.MISSING_EXTERNAL, Severity.CRITICAL, 7_00,
                BreakStatus.OPEN, TODAY.minusDays(40), Optional.empty()));
        fold.approved(new ReportReadings.ApprovedResolutionLine(
                Optional.of(psp), ResolutionKind.WRITE_OFF, eur(50), true));
        fold.approved(new ReportReadings.ApprovedResolutionLine(
                Optional.of(psp), ResolutionKind.WRITE_OFF, eur(25), true));
        fold.approved(new ReportReadings.ApprovedResolutionLine(
                Optional.of(psp), ResolutionKind.ACKNOWLEDGE, eur(1_00), false));
        fold.approved(new ReportReadings.ApprovedResolutionLine(
                Optional.empty(), ResolutionKind.ACKNOWLEDGE, eur(0), false));

        ReconciliationReports.DailySummaryReport report = fold.report();

        assertThat(report.date()).isEqualTo("2026-10-01");
        assertThat(report.truncated()).isFalse();
        assertThat(report.sources())
                .extracting(ReconciliationReports.DailySummarySource::source)
                .as("every declared source answers, active or not; an unattributable"
                        + " resolution is never dropped")
                .containsExactly(BANK, PSP, "unknown");
        ReconciliationReports.DailySummarySource pspDay = report.sources().get(1);
        assertThat(pspDay.batchesAccepted()).isEqualTo(2);
        assertThat(pspDay.items())
                .containsExactly(
                        new ReconciliationReports.SummaryDisposition("MATCHED", "EUR", 2,
                                "12.00"),
                        new ReconciliationReports.SummaryDisposition("CHECKED", "EUR", 1,
                                "1.75"),
                        new ReconciliationReports.SummaryDisposition("UNMATCHED", "EUR", 1,
                                "6.00"),
                        new ReconciliationReports.SummaryDisposition("PARKED", "EUR", 1,
                                "4.00"),
                        new ReconciliationReports.SummaryDisposition("REPUDIATED", "EUR", 1,
                                "99.00"));
        assertThat(pspDay.itemsMatched()).isEqualTo(3);
        assertThat(pspDay.itemsCounted())
                .as("the repudiated item's evidence is withdrawn: not counted")
                .isEqualTo(5);
        assertThat(pspDay.matchRate()).isEqualTo("0.6000");
        assertThat(pspDay.breaksRaised())
                .containsExactly(new ReconciliationReports.SummaryBreaks(
                        "UNKNOWN_EXTERNAL", "HIGH", "EUR", 1, "6.00"));
        assertThat(pspDay.breaksResolved())
                .containsExactly(new ReconciliationReports.SummaryBreaks(
                        "AMOUNT_MISMATCH", "MEDIUM", "EUR", 1, "0.50"));
        assertThat(pspDay.resolutionsApproved())
                .containsExactly(
                        new ReconciliationReports.SummaryResolutions(
                                "ACKNOWLEDGE", "EUR", 1, "0.00"),
                        new ReconciliationReports.SummaryResolutions(
                                "WRITE_OFF", "EUR", 2, "0.75"));
        assertThat(pspDay.openBreaksByAge())
                .containsExactly(
                        new ReconciliationReports.SummaryAgeingBand("0-2", 1),
                        new ReconciliationReports.SummaryAgeingBand("3-7", 0),
                        new ReconciliationReports.SummaryAgeingBand("8-30", 1),
                        new ReconciliationReports.SummaryAgeingBand(">30", 1));
        ReconciliationReports.DailySummarySource bankDay = report.sources().get(0);
        assertThat(bankDay.batchesAccepted()).isZero();
        assertThat(bankDay.matchRate()).isNull();
    }

    // ----------------------------------------------------------------- provider costs

    @Test
    @DisplayName("provider costs, four things per source and currency: charged from the"
            + " PROCESSING_COSTS lines less the month's reversals, the evidence by fee type and"
            + " its agreement, the expected fees, and the month's fee mismatches")
    void providerCostsFoldTheFourThings() {
        LedgerAccountId eurCosts =
                LedgerAccountId.of(new com.finapp.sharedkernel.id.IdGenerator(
                        java.time.Clock.systemUTC(), new java.security.SecureRandom()).next());
        LedgerAccountId clearing =
                LedgerAccountId.of(new com.finapp.sharedkernel.id.IdGenerator(
                        java.time.Clock.systemUTC(), new java.security.SecureRandom()).next());
        LedgerAccountId cash =
                LedgerAccountId.of(new com.finapp.sharedkernel.id.IdGenerator(
                        java.time.Clock.systemUTC(), new java.security.SecureRandom()).next());
        ReconciliationReports.ProviderCostFold fold =
                new ReconciliationReports.ProviderCostFold(
                        currency ->
                                currency.equals(EUR) ? Optional.of(eurCosts) : Optional.empty());

        // A PSP report: 1.75 charged, evidenced and expected (the pinned schedule's own).
        fold.recognised(PSP, EUR,
                List.of(line(eurCosts, Direction.DEBIT, 1_75),
                        line(clearing, Direction.CREDIT, 1_75)),
                List.of(total(SettlementLineType.CAPTURE, LineDirection.INBOUND, 100_00),
                        total(SettlementLineType.PROCESSING_FEE, LineDirection.OUTBOUND, 1_75)),
                List.of(expected(ExpectationDirection.OUTBOUND, 1_75)));
        // A PSP report accepted AND repudiated this month: recognition and reversal net out.
        List<JournalLine> repudiated =
                List.of(line(eurCosts, Direction.DEBIT, 2_00),
                        line(clearing, Direction.CREDIT, 2_00));
        List<SettlementBatchStore.TotalRow> repudiatedTotals =
                List.of(total(SettlementLineType.PROCESSING_FEE, LineDirection.OUTBOUND, 2_00));
        List<ReportReadings.ExpectedFeeLine> repudiatedExpected =
                List.of(expected(ExpectationDirection.OUTBOUND, 2_00));
        fold.recognised(PSP, EUR, repudiated, repudiatedTotals, repudiatedExpected);
        fold.reversed(PSP, EUR,
                List.of(line(clearing, Direction.DEBIT, 2_00),
                        line(eurCosts, Direction.CREDIT, 2_00)),
                repudiatedTotals, repudiatedExpected);
        // A reversal this month of LAST month's report: a negative cost here.
        fold.reversed(PSP, EUR,
                List.of(line(clearing, Direction.DEBIT, 40),
                        line(eurCosts, Direction.CREDIT, 40)),
                List.of(total(SettlementLineType.PROCESSING_FEE, LineDirection.OUTBOUND, 40)),
                List.of(expected(ExpectationDirection.OUTBOUND, 40)));
        // A bank statement's own fee: DR PROCESSING_COSTS against CASH_AT_BANK.
        fold.recognised(BANK, EUR,
                List.of(line(cash, Direction.DEBIT, 97_75),
                        line(clearing, Direction.CREDIT, 98_25),
                        line(eurCosts, Direction.DEBIT, 50)),
                List.of(total(SettlementLineType.BANK_CREDIT, LineDirection.INBOUND, 98_25),
                        total(SettlementLineType.BANK_FEE, LineDirection.OUTBOUND, 50)),
                List.of(expected(ExpectationDirection.OUTBOUND, 50)));
        // A GBP report whose ledger figure disagrees with its evidence (no cost account read).
        fold.recognised(PSP, GBP, List.of(),
                List.of(new SettlementBatchStore.TotalRow(
                        SettlementLineType.PROCESSING_FEE, LineDirection.OUTBOUND, 1, 30, 2)),
                List.of());
        fold.feeMismatch(PSP, breakLine(UUID.randomUUID(), BreakType.FEE_MISMATCH,
                Severity.LOW, 25, BreakStatus.OPEN, TODAY, Optional.empty()));
        fold.feeMismatch(PSP, breakLine(UUID.randomUUID(), BreakType.FEE_MISMATCH,
                Severity.LOW, 10, BreakStatus.RESOLVED, TODAY, Optional.of(Instant.now())));

        List<ReconciliationReports.ProviderCostRow> rows = fold.rows();

        assertThat(rows)
                .containsExactly(
                        new ReconciliationReports.ProviderCostRow(
                                BANK, "EUR", 1, 0, "0.50",
                                List.of(new ReconciliationReports.ProviderFeeEvidence(
                                        "BANK_FEE", "0.50")),
                                "0.50", true, "0.50", 0, "0.00", 0),
                        new ReconciliationReports.ProviderCostRow(
                                PSP, "EUR", 2, 2, "1.35",
                                List.of(new ReconciliationReports.ProviderFeeEvidence(
                                        "PROCESSING_FEE", "1.35")),
                                "1.35", true, "1.35", 2, "0.35", 1),
                        new ReconciliationReports.ProviderCostRow(
                                PSP, "GBP", 1, 0, "0.00",
                                List.of(new ReconciliationReports.ProviderFeeEvidence(
                                        "PROCESSING_FEE", "0.30")),
                                "0.30", false, "0.00", 0, "0.00", 0));
    }

    // ----------------------------------------------------------------- fixtures

    private static Money eur(long minor) {
        return Money.ofPersisted(minor, EUR, 2);
    }

    private static ReportReadings.OpenSuspenseLine suspense(
            SuspenseSide side, long minor, CurrencyCode currency, LocalDate openedOn) {
        UUID owner = UUID.randomUUID();
        return new ReportReadings.OpenSuspenseLine(
                UUID.randomUUID(),
                SuspenseOrigin.RECON_PARK,
                side,
                Money.ofPersisted(minor, currency, 2),
                openedOn,
                owner,
                Optional.of(
                        new ReportReadings.OpenBreak(
                                owner, BreakType.UNKNOWN_EXTERNAL, Severity.HIGH)));
    }

    private static ReportReadings.UnexplainedItemLine unexplained(
            UUID source,
            ExpectationDirection direction,
            ItemStatus status,
            long minor,
            LocalDate businessDate,
            Optional<ReportReadings.OpenBreak> openBreak) {
        return new ReportReadings.UnexplainedItemLine(
                UUID.randomUUID(),
                source,
                ExternalLineType.CAPTURE,
                direction,
                status,
                eur(minor),
                businessDate,
                Optional.of(Instant.parse("2026-10-03T00:00:00Z")),
                openBreak);
    }

    private static ReportReadings.OpenExpectationLine expectation(
            UUID source,
            ExpectationDirection direction,
            long minor,
            LocalDate postingDate,
            Optional<Instant> overdueSince) {
        return new ReportReadings.OpenExpectationLine(
                UUID.randomUUID(),
                source,
                ExpectationKind.CARD_CAPTURE,
                direction,
                eur(minor),
                postingDate,
                postingDate.plusDays(3),
                overdueSince);
    }

    private static ReportReadings.BreakLine breakLine(
            UUID source,
            BreakType type,
            Severity severity,
            long minor,
            BreakStatus status,
            LocalDate raisedOn,
            Optional<Instant> resolvedAt) {
        return new ReportReadings.BreakLine(
                UUID.randomUUID(),
                source,
                type,
                severity,
                status,
                eur(minor),
                raisedOn.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(3_600),
                resolvedAt);
    }

    private static ReconciliationReports.UnmatchedFigure figure(
            long count, String value, Long oldestAgeDays) {
        return new ReconciliationReports.UnmatchedFigure(count, value, oldestAgeDays);
    }

    private static ReportReadings.DayItemLine day(UUID source, ItemStatus status, long amount) {
        return new ReportReadings.DayItemLine(
                source, status, eur(amount), eur(0), eur(0), eur(0));
    }

    private static JournalLine line(LedgerAccountId account, Direction direction, long minor) {
        return new JournalLine(account, direction, eur(minor));
    }

    private static SettlementBatchStore.TotalRow total(
            SettlementLineType type, LineDirection direction, long minor) {
        return new SettlementBatchStore.TotalRow(type, direction, 1, minor, 2);
    }

    private static ReportReadings.ExpectedFeeLine expected(
            ExpectationDirection direction, long minor) {
        return new ReportReadings.ExpectedFeeLine(UUID.randomUUID(), direction, eur(minor));
    }
}
