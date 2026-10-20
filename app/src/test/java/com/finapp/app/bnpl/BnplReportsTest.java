package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.bnpl.BnplAuditAction;
import com.finapp.bnpl.BnplObligationId;
import com.finapp.bnpl.BnplObligationStatus;
import com.finapp.bnpl.BnplProduct;
import com.finapp.bnpl.BnplProofs;
import com.finapp.bnpl.BnplReplayProof;
import com.finapp.bnpl.BnplSubledgerProof;
import com.finapp.bnpl.BnplThreeWayProof;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BNPL's operations reports, their assembly proven without a database (`P12-TSK-026`): the date refused before any read,
 * the unknown date answered empty and still recorded, every sum the reads' lines and never a row's figure where journal
 * lines are the truth, every tie judged - the book against the products and the trial balance, the exposure against the
 * payables' lines, each refund against its entry - the proofs carried with the agreements named and bounded, and exactly
 * one {@code bnpl.ReportRead} per serving, committed after the snapshot and never carrying a figure ({@code INV-AUD-01},
 * {@code INV-AUD-02}). The statements are {@code BnplReportsDatabaseTest}'s.
 */
@DisplayName("BNPL's operations reports: dates, sums, ties, proofs and the audit, without a database (P12-TSK-026)")
class BnplReportsTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final String DAY = "2026-10-15";

    private final Reads reads = new Reads();
    private final Units units = new Units();
    private final List<AuditRecord> audited = new ArrayList<>();
    private BnplProofs.Reading reading = new BnplProofs.Reading(List.of(), List.of());

    private final BnplReports reports = new BnplReports(units, reads, snapshot -> reading,
            (AuditWriter<Connection>) (unitOfWork, record) -> {
                units.calls.add("audit");
                audited.add(record);
            }, IDS, Clock.systemUTC());

    @Test
    @DisplayName("a malformed or missing date is 422 api.ValidationFailed before any read, and nothing is recorded")
    void aMalformedDateIsRefusedBeforeAnyRead() {
        for (String date : new String[] {null, "", "2026-13-01", "2026-02-30", "15-10-2026", "2026-10-15T00:00"}) {
            assertThatThrownBy(() -> served(() -> reports.portfolio(date))).as(String.valueOf(date))
                    .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.errorCode())
                            .isEqualTo(PlatformErrorCode.VALIDATION_FAILED));
        }
        assertThat(units.calls).isEmpty();
        assertThat(audited).isEmpty();
    }

    @Test
    @DisplayName("a date the platform has not had answers empty - never a figure - and is still recorded once")
    void anUnknownDateIsEmptyAndRecorded() {
        reads.reportable = false;
        plantBook();
        BnplReports.BnplPortfolioReport portfolio = served(() -> reports.portfolio(DAY));
        assertThat(portfolio.known()).isFalse();
        assertThat(portfolio.rows()).isEmpty();
        assertThat(portfolio.currencies()).isEmpty();
        assertThat(portfolio.proofs()).isNull();
        assertThat(served(() -> reports.merchantFinancing(DAY)).rows()).isEmpty();
        assertThat(served(() -> reports.refunds(DAY)).rows()).isEmpty();
        assertThat(served(() -> reports.delinquency(DAY)).known()).isFalse();
        // Before the platform's first business date nothing is even asked of the database.
        reads.asked.clear();
        assertThat(served(() -> reports.portfolio("2025-12-31")).known()).isFalse();
        assertThat(reads.asked).isEmpty();
        assertThat(audited).hasSize(5).allSatisfy(record -> assertThat(record.changeSummary().orElseThrow())
                .contains("known=false", "rows=0"));
    }

    @Test
    @DisplayName("the portfolio: each product's balances and outstanding from its lines, the book tied to the products and"
            + " the trial balance, the three-way verdict and its failing arms beside it")
    void thePortfolioSumsAndTies() {
        plantBook();
        reading = new BnplProofs.Reading(List.of(), List.of(new BnplThreeWayProof.CurrencyVerdict(EUR,
                List.of(new BnplThreeWayProof.MerchantLeg(IDS.next(), true, false, 0, 0, true)), List.of(), List.of(),
                true, true, true)));
        BnplReports.BnplPortfolioReport report = served(() -> reports.portfolio(DAY));
        assertThat(report.known()).isTrue();
        assertThat(report.rows()).hasSize(2);
        BnplReports.BnplPortfolioRow payIn4 = report.rows().get(0);
        assertThat(payIn4.product()).isEqualTo("bnpl_pay_in_4");
        assertThat(payIn4.active()).isEqualTo(3);
        assertThat(payIn4.closed()).isEqualTo(1);
        assertThat(payIn4.principalMinor()).isEqualTo(450_00);
        assertThat(payIn4.principalDueMinor()).isEqualTo(150_00);
        assertThat(payIn4.feesDueMinor()).isEqualTo(7_00);
        assertThat(payIn4.creditBalanceMinor()).as("credit-normal, read positive").isEqualTo(12_00);
        assertThat(payIn4.outstandingMinor()).as("the five receivables, never the credit balance").isEqualTo(607_00);
        BnplReports.BnplPortfolioRow payInN = report.rows().get(1);
        assertThat(payInN.interestAccruedMinor()).isEqualTo(3_21);
        assertThat(payInN.interestDueMinor()).isEqualTo(1_10);
        assertThat(payInN.outstandingMinor()).isEqualTo(1_000_00 + 3_21 + 1_10);
        assertThat(report.currencies()).singleElement().satisfies(tie -> {
            assertThat(tie.outstandingMinor()).isEqualTo(607_00 + 1_004_31);
            assertThat(tie.tiesToProducts()).isTrue();
            assertThat(tie.trialBalanceZero()).isTrue();
            assertThat(tie.threeWayHolds()).isFalse();
            assertThat(tie.threeWayFailures()).containsExactly("MERCHANT_LEG");
        });

        // A line on an obligation account bnpl's book does not name: the book no longer ties, and the journal is off.
        reads.book.add(new BnplReportReads.Balance("", "EUR", 2, "BNPL_PRINCIPAL", new BigDecimal(1)));
        reads.trial.put("EUR", false);
        BnplReports.BnplBookTie broken = served(() -> reports.portfolio(DAY)).currencies().get(0);
        assertThat(broken.tiesToProducts()).isFalse();
        assertThat(broken.trialBalanceZero()).isFalse();
    }

    @Test
    @DisplayName("the portfolio carries the subledger and replay counts and names the failing and diverged agreements -"
            + " bounded, the truncation said")
    void thePortfolioNamesTheAgreementsTheProofsFail() {
        plantBook();
        List<BnplProofs.Proven> proven = new ArrayList<>();
        for (int i = 0; i < BnplReports.BOUND + 2; i++) {
            proven.add(proven(false, BnplReplayProof.Verdict.IDENTICAL));
        }
        proven.add(proven(true, BnplReplayProof.Verdict.DIVERGED));
        proven.add(proven(true, BnplReplayProof.Verdict.IDENTICAL));
        reading = new BnplProofs.Reading(proven, List.of());
        BnplReports.BnplProofCounts counts = served(() -> reports.portfolio(DAY)).proofs();
        assertThat(counts.holding()).isEqualTo(2);
        assertThat(counts.failing()).isEqualTo(BnplReports.BOUND + 2);
        assertThat(counts.identical()).isEqualTo(BnplReports.BOUND + 3);
        assertThat(counts.diverged()).isEqualTo(1);
        assertThat(counts.failingAgreements()).hasSize(BnplReports.BOUND)
                .allSatisfy(agreement -> assertThat(reads.agreements.containsValue(UUID.fromString(agreement))).isTrue());
        UUID divergedObligation = proven.get(BnplReports.BOUND + 2).subledger().obligation().value();
        assertThat(counts.divergedAgreements()).containsExactly(reads.agreements.get(divergedObligation).toString());
        assertThat(counts.truncated()).isTrue();
    }

    @Test
    @DisplayName("the merchant financing: the exposure is the net financed less the payable debited, tied to the payables'"
            + " lines of those entries, the failing three-way merchant legs counted")
    void theMerchantFinancingTiesToThePayables() {
        reads.financing.add(new BnplReportReads.Financing("EUR", 2, 3, new BigDecimal(1_200_00), new BigDecimal(36_00),
                new BigDecimal(1_164_00), 1, new BigDecimal(100_00), new BigDecimal(3_00), new BigDecimal(97_00)));
        reads.payables.add(new BnplReportReads.Balance("", "EUR", 2, "MERCHANT_PAYABLE", new BigDecimal(1_067_00)));
        reading = new BnplProofs.Reading(List.of(), List.of(new BnplThreeWayProof.CurrencyVerdict(EUR,
                List.of(new BnplThreeWayProof.MerchantLeg(IDS.next(), true, true, 0, 0, true),
                        new BnplThreeWayProof.MerchantLeg(IDS.next(), true, true, 1, 0, true)),
                List.of(), List.of(), true, true, true)));
        BnplReports.BnplMerchantFinancingRow row = served(() -> reports.merchantFinancing(DAY)).rows().get(0);
        assertThat(row.purchases()).isEqualTo(3);
        assertThat(row.netFinancedMinor()).isEqualTo(1_164_00);
        assertThat(row.exposureMinor()).isEqualTo(1_164_00 - 97_00);
        assertThat(row.ledgerPayableMinor()).isEqualTo(1_067_00);
        assertThat(row.tiesToLedger()).isTrue();
        assertThat(row.merchantLegsFailing()).isEqualTo(1);

        reads.payables.set(0, new BnplReportReads.Balance("", "EUR", 2, "MERCHANT_PAYABLE", new BigDecimal(1_066_99)));
        assertThat(served(() -> reports.merchantFinancing(DAY)).rows().get(0).tiesToLedger()).isFalse();
        reads.payables.set(0, new BnplReportReads.Balance("", "EUR", 3, "MERCHANT_PAYABLE", new BigDecimal(1_067_000)));
        assertThatThrownBy(() -> served(() -> reports.merchantFinancing(DAY)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("two scales");
    }

    @Test
    @DisplayName("the refunds: the impact on outstanding is what their entries left on the receivables - late fees"
            + " released included - and a row whose figures its entry does not carry does not tie")
    void theRefundsTieToTheirEntries() {
        reads.refunds.add(new BnplReportReads.RefundSum("BNPL_PAY_IN_N", "EUR", 2, "PARTIAL", "MERCHANT", 2,
                new BigDecimal(300_00), new BigDecimal(290_00), new BigDecimal(4_00), new BigDecimal(6_00),
                BigDecimal.ZERO, new BigDecimal(9_00), new BigDecimal(291_00), new BigDecimal(294_00),
                new BigDecimal(5_00)));
        reads.refunds.add(new BnplReportReads.RefundSum("BNPL_PAY_IN_4", "EUR", 2, "FULL", "DISPUTE", 1,
                new BigDecimal(100_00), new BigDecimal(75_00), BigDecimal.ZERO, new BigDecimal(25_00), BigDecimal.ZERO,
                new BigDecimal(3_00), new BigDecimal(97_00), new BigDecimal(74_00), BigDecimal.ZERO));
        List<BnplReports.BnplRefundRow> rows = served(() -> reports.refunds(DAY)).rows();
        assertThat(rows.get(0).product()).isEqualTo("bnpl_pay_in_n");
        assertThat(rows.get(0).kind()).isEqualTo("partial");
        assertThat(rows.get(0).door()).isEqualTo("merchant");
        assertThat(rows.get(0).principalCancelledMinor()).isEqualTo(290_00);
        assertThat(rows.get(0).interestReleasedMinor()).isEqualTo(4_00);
        assertThat(rows.get(0).lateFeesReleasedMinor()).isEqualTo(5_00);
        assertThat(rows.get(0).outstandingReducedMinor()).isEqualTo(299_00);
        assertThat(rows.get(0).tiesToLedger()).isTrue();
        assertThat(rows.get(1).door()).isEqualTo("dispute");
        assertThat(rows.get(1).tiesToLedger()).as("75.00 cancelled by the row, 74.00 by its entry").isFalse();
    }

    @Test
    @DisplayName("the delinquency: each bucket's obligations and defaulted, and its due sums from the ledger's due lines")
    void theDelinquencySumsItsDues() {
        reads.delinquency.add(new BnplReportReads.DelinquencyCount("BNPL_PAY_IN_4", "EUR", "CURRENT", 5, 0));
        reads.delinquency.add(new BnplReportReads.DelinquencyCount("BNPL_PAY_IN_4", "EUR", "B2_30_59", 2, 1));
        reads.dues.add(new BnplReportReads.Balance("BNPL_PAY_IN_4|B2_30_59", "EUR", 2, "BNPL_PRINCIPAL_DUE",
                new BigDecimal(200_00)));
        reads.dues.add(new BnplReportReads.Balance("BNPL_PAY_IN_4|B2_30_59", "EUR", 2, "BNPL_FEES_DUE",
                new BigDecimal(14_00)));
        reads.dues.add(new BnplReportReads.Balance("BNPL_PAY_IN_N|B2_30_59", "EUR", 2, "BNPL_PRINCIPAL_DUE",
                new BigDecimal(999_00)));
        List<BnplReports.BnplDelinquencyRow> rows = served(() -> reports.delinquency(DAY)).rows();
        assertThat(rows.get(0).bucket()).isEqualTo("current");
        assertThat(rows.get(0).dueMinor()).isZero();
        assertThat(rows.get(1).bucket()).isEqualTo("b2_30_59");
        assertThat(rows.get(1).obligations()).isEqualTo(2);
        assertThat(rows.get(1).defaulted()).isEqualTo(1);
        assertThat(rows.get(1).principalDueMinor()).isEqualTo(200_00);
        assertThat(rows.get(1).feesDueMinor()).isEqualTo(14_00);
        assertThat(rows.get(1).dueMinor()).as("its own product's dues only").isEqualTo(214_00);
    }

    @Test
    @DisplayName("every serving is recorded once - bnpl.ReportRead, after the snapshot, in a transaction of its own - its"
            + " summary naming the report, the date and the row count, never a figure; a read that fails records nothing")
    void everyServingIsRecordedOnceAndCarriesNoFigure() {
        plantBook();
        served(() -> reports.portfolio(DAY));
        assertThat(units.calls).containsExactly("snapshot", "transaction", "audit");
        assertThat(audited).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(BnplAuditAction.REPORT_READ);
            assertThat(record.operation().code()).isEqualTo("bnpl.ReportRead");
            assertThat(record.targetType()).isEqualTo("bnpl_report");
            assertThat(record.targetId()).isEqualTo("portfolio");
            assertThat(record.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
            assertThat(record.actor().id()).isEqualTo("investigator-1");
            assertThat(record.correlationId().value()).isEqualTo("report-correlation-1");
            assertThat(record.changeSummary()).contains("report=portfolio, date=2026-10-15, known=true, rows=2");
            assertThat(record.changeSummary().orElseThrow()).doesNotContain("45000", "450.00", "607", "EUR");
        });

        units.calls.clear();
        audited.clear();
        reads.failAt = "obligations";
        assertThatThrownBy(() -> served(() -> reports.portfolio(DAY))).isInstanceOf(IllegalStateException.class);
        assertThat(units.calls).containsExactly("snapshot");
        assertThat(audited).isEmpty();
    }

    @Test
    @DisplayName("ten instances at one instant: every read a report makes is inside its ONE snapshot - none before, none"
            + " beside - so a report is a function of one state, and two instances over the same state serve one report")
    void everyReadIsInsideOneSnapshotAndInstancesAgree() {
        plantBook();
        reads.financing.add(new BnplReportReads.Financing("EUR", 2, 1, new BigDecimal(600_00), new BigDecimal(18_00),
                new BigDecimal(582_00), 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        reads.payables.add(new BnplReportReads.Balance("", "EUR", 2, "MERCHANT_PAYABLE", new BigDecimal(582_00)));
        reads.inside = units.inside;
        List<BnplReports> instances = new ArrayList<>();
        for (int instance = 0; instance < 10; instance++) {
            instances.add(new BnplReports(units, reads, snapshot -> reading,
                    (AuditWriter<Connection>) (unitOfWork, record) -> audited.add(record), IDS, Clock.systemUTC()));
        }
        List<Object> served = new ArrayList<>();
        for (BnplReports instance : instances) {
            units.calls.clear();
            served.add(served(() -> instance.portfolio(DAY)));
            served.add(served(() -> instance.merchantFinancing(DAY)));
            served.add(served(() -> instance.refunds(DAY)));
            served.add(served(() -> instance.delinquency(DAY)));
            assertThat(units.calls).as("one snapshot and one audit transaction per report")
                    .containsExactly("snapshot", "transaction", "snapshot", "transaction", "snapshot", "transaction",
                            "snapshot", "transaction");
        }
        assertThat(reads.outside).as("no read outside the report's snapshot").isEmpty();
        for (int instance = 1; instance < 10; instance++) {
            assertThat(served.subList(instance * 4, instance * 4 + 4)).as("instance %d serves instance 0's reports",
                    instance).isEqualTo(served.subList(0, 4));
        }
        assertThat(audited).hasSize(40);
    }

    // ------------------------------------------------------------------ the world

    /** Runs {@code report} as an investigator inside a request's correlation. */
    @SuppressWarnings("try")
    private static <R> R served(Supplier<R> report) {
        try (SecurityContext.Scope actor = SecurityContext.enter(new Actor("investigator-1", ActorType.EMPLOYEE));
                CorrelationContext.Scope correlation = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of("report-correlation-1")))) {
            return report.get();
        }
    }

    /** Two products' books in EUR: their counts, their lines, the same lines from the ledger's side, the journal at zero. */
    private void plantBook() {
        reads.obligations.add(new BnplReportReads.ObligationCount("BNPL_PAY_IN_4", "EUR", "ACTIVE", 3));
        reads.obligations.add(new BnplReportReads.ObligationCount("BNPL_PAY_IN_4", "EUR", "CLOSED", 1));
        reads.obligations.add(new BnplReportReads.ObligationCount("BNPL_PAY_IN_N", "EUR", "ACTIVE", 1));
        Object[][] lines = {
            {"BNPL_PAY_IN_4", "BNPL_PRINCIPAL", 450_00}, {"BNPL_PAY_IN_4", "BNPL_PRINCIPAL_DUE", 150_00},
            {"BNPL_PAY_IN_4", "BNPL_FEES_DUE", 7_00}, {"BNPL_PAY_IN_4", "BNPL_CREDIT_BALANCE", -12_00},
            {"BNPL_PAY_IN_N", "BNPL_PRINCIPAL", 1_000_00}, {"BNPL_PAY_IN_N", "BNPL_INTEREST_ACCRUED", 3_21},
            {"BNPL_PAY_IN_N", "BNPL_INTEREST_DUE", 1_10}};
        for (Object[] line : lines) {
            BigDecimal signed = new BigDecimal((Integer) line[2]);
            reads.balances.add(new BnplReportReads.Balance((String) line[0], "EUR", 2, (String) line[1], signed));
            reads.book.add(new BnplReportReads.Balance("", "EUR", 2, (String) line[1], signed));
        }
        reads.trial.put("EUR", true);
    }

    private BnplProofs.Proven proven(boolean holds, BnplReplayProof.Verdict verdict) {
        BnplObligationId obligation = BnplObligationId.of(IDS.next());
        reads.agreements.put(obligation.value(), IDS.next());
        BnplSubledgerProof.Verdict subledger = new BnplSubledgerProof.Verdict(obligation, BnplProduct.BNPL_PAY_IN_4,
                BnplObligationStatus.ACTIVE, List.of(), true, holds ? 0 : 1, true, true, true);
        BnplReplayProof.Replay replay = new BnplReplayProof.Replay(obligation, verdict,
                verdict == BnplReplayProof.Verdict.IDENTICAL ? List.of()
                        : List.of(new BnplReplayProof.Finding(BnplReplayProof.Check.REFUND, IDS.next())), 5);
        return new BnplProofs.Proven(subledger, replay);
    }

    /** The two units, recording their order - the connection is never touched by these reads. */
    private static final class Units implements BnplReports.Units {
        final List<String> calls = new ArrayList<>();
        /** Whether a snapshot is open now. */
        final boolean[] inside = new boolean[1];

        @Override
        public <R> R inSnapshot(Function<Connection, R> work) {
            calls.add("snapshot");
            inside[0] = true;
            try {
                return work.apply(null);
            } finally {
                inside[0] = false;
            }
        }

        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            calls.add("transaction");
            return work.apply(null);
        }
    }

    /** Planted rows; each read named as it is asked; {@code failAt} fails the named read. */
    private static final class Reads implements BnplReportReads {
        boolean reportable = true;
        String failAt = "";
        final List<String> asked = new ArrayList<>();
        final List<ObligationCount> obligations = new ArrayList<>();
        final List<Balance> balances = new ArrayList<>();
        final List<Balance> book = new ArrayList<>();
        final Map<String, Boolean> trial = new HashMap<>();
        final List<Financing> financing = new ArrayList<>();
        final List<Balance> payables = new ArrayList<>();
        final List<RefundSum> refunds = new ArrayList<>();
        final List<DelinquencyCount> delinquency = new ArrayList<>();
        final List<Balance> dues = new ArrayList<>();
        final Map<UUID, UUID> agreements = new HashMap<>();
        /** When set, the snapshot flag every read is judged against; the reads made outside one. */
        boolean[] inside;
        final List<String> outside = new ArrayList<>();

        private <T> T ask(String read, T answer) {
            asked.add(read);
            if (inside != null && !inside[0]) {
                outside.add(read);
            }
            if (read.equals(failAt)) {
                throw new IllegalStateException("a BNPL report could not be read: 08006");
            }
            return answer;
        }

        @Override
        public boolean reportable(Connection snapshot, LocalDate day) {
            return ask("reportable", reportable);
        }

        @Override
        public List<ObligationCount> obligations(Connection snapshot, LocalDate day) {
            return ask("obligations", obligations);
        }

        @Override
        public List<Balance> obligationBalances(Connection snapshot, LocalDate day) {
            return ask("obligationBalances", balances);
        }

        @Override
        public List<Balance> bookBalances(Connection snapshot, LocalDate day) {
            return ask("bookBalances", book);
        }

        @Override
        public Map<String, Boolean> trialBalance(Connection snapshot, LocalDate day) {
            return ask("trialBalance", trial);
        }

        @Override
        public List<Financing> financing(Connection snapshot, LocalDate day) {
            return ask("financing", financing);
        }

        @Override
        public List<Balance> payableLines(Connection snapshot, LocalDate day) {
            return ask("payableLines", payables);
        }

        @Override
        public List<RefundSum> refunds(Connection snapshot, LocalDate day) {
            return ask("refunds", refunds);
        }

        @Override
        public List<DelinquencyCount> delinquency(Connection snapshot, LocalDate day) {
            return ask("delinquency", delinquency);
        }

        @Override
        public List<Balance> dues(Connection snapshot, LocalDate day) {
            return ask("dues", dues);
        }

        @Override
        public Map<UUID, UUID> agreementsOf(Connection snapshot, Collection<UUID> obligations) {
            Map<UUID, UUID> found = new HashMap<>();
            obligations.forEach(obligation -> found.put(obligation, agreements.get(obligation)));
            return ask("agreementsOf", found);
        }
    }
}
