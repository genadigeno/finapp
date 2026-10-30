package com.finapp.accounting.testing;

import com.finapp.accounting.GlAccount;
import com.finapp.accounting.GlClassification;
import com.finapp.accounting.GlMappingVersion;
import com.finapp.accounting.LedgerChart;
import com.finapp.accounting.MappingRule;
import com.finapp.accounting.StatementSection;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The reference GL mapping version (`P14-TSK-003`; owner decision C2, recommended default A) - a GL chart and one rule
 * for each of the ledger's forty purposes, keyed by purpose and owner kind. <strong>TEST-ONLY, and reference values for
 * qualified review</strong>: the codes, names and statement sections are illustrative, not a statutory chart, and no
 * migration activates them - production starts with no version, and two persons propose and activate the first after
 * deployment (ADR-0113, Consequences). What is not illustrative is what the suites prove of it: it is total over the
 * ledger's purposes and classification-compatible with each purpose's ledger type ({@code LedgerPurposeChartTest} in
 * {@code app}, against the ledger's own enum and seed migrations), and each section admits its account's classification.
 *
 * <p>Strings only: {@code accounting} has no ledger edge, so the purposes and owner kinds are the ledger's names, held
 * equal to its enum by {@code app}'s test.
 */
public final class ReferenceGlMapping {

    /** The month the reference takes effect from in the suites. */
    public static final YearMonth EFFECTIVE_FROM = YearMonth.of(2026, 11);

    private ReferenceGlMapping() {}

    /** A purpose, its owner kind and the account it maps to. */
    private record Row(String purpose, String ownerKind, String code) {}

    private static final List<GlAccount> ACCOUNTS = List.of(
            new GlAccount("1000", "Cash at bank", GlClassification.ASSET, StatementSection.CASH_AND_BANK),
            new GlAccount("1100", "Card settlement clearing", GlClassification.ASSET,
                    StatementSection.CLEARING_AND_SETTLEMENT_RECEIVABLES),
            new GlAccount("1110", "Instant payment clearing", GlClassification.ASSET,
                    StatementSection.CLEARING_AND_SETTLEMENT_RECEIVABLES),
            new GlAccount("1120", "FX provider clearing", GlClassification.ASSET,
                    StatementSection.CLEARING_AND_SETTLEMENT_RECEIVABLES),
            new GlAccount("1130", "Chargebacks recoverable", GlClassification.ASSET, StatementSection.OTHER_ASSETS),
            new GlAccount("1140", "FX position", GlClassification.ASSET, StatementSection.OTHER_ASSETS),
            new GlAccount("1300", "Loans - principal", GlClassification.ASSET, StatementSection.LOANS_AND_BNPL_RECEIVABLES),
            new GlAccount("1310", "Loans - interest receivable", GlClassification.ASSET,
                    StatementSection.LOANS_AND_BNPL_RECEIVABLES),
            new GlAccount("1320", "Loans - fees receivable", GlClassification.ASSET,
                    StatementSection.LOANS_AND_BNPL_RECEIVABLES),
            new GlAccount("1400", "BNPL - principal", GlClassification.ASSET, StatementSection.LOANS_AND_BNPL_RECEIVABLES),
            new GlAccount("1410", "BNPL - interest receivable", GlClassification.ASSET,
                    StatementSection.LOANS_AND_BNPL_RECEIVABLES),
            new GlAccount("1420", "BNPL - fees receivable", GlClassification.ASSET,
                    StatementSection.LOANS_AND_BNPL_RECEIVABLES),
            new GlAccount("2000", "Customer funds", GlClassification.LIABILITY, StatementSection.CUSTOMER_FUNDS),
            new GlAccount("2100", "Merchant payables", GlClassification.LIABILITY, StatementSection.MERCHANT_PAYABLES),
            new GlAccount("2200", "Payout clearing", GlClassification.LIABILITY,
                    StatementSection.CLEARING_AND_SETTLEMENT_PAYABLES),
            new GlAccount("2210", "Cross-border corridor clearing", GlClassification.LIABILITY,
                    StatementSection.CLEARING_AND_SETTLEMENT_PAYABLES),
            new GlAccount("2300", "Borrower credit balances", GlClassification.LIABILITY,
                    StatementSection.OTHER_LIABILITIES),
            new GlAccount("2900", "Suspense - unmatched", GlClassification.LIABILITY, StatementSection.SUSPENSE),
            new GlAccount("3000", "Lending capital", GlClassification.EQUITY, StatementSection.CAPITAL),
            new GlAccount("4000", "Fee income", GlClassification.REVENUE, StatementSection.FEE_AND_COMMISSION_INCOME),
            new GlAccount("4010", "BNPL merchant fee income", GlClassification.REVENUE,
                    StatementSection.FEE_AND_COMMISSION_INCOME),
            new GlAccount("4020", "Loan fee income", GlClassification.REVENUE, StatementSection.FEE_AND_COMMISSION_INCOME),
            new GlAccount("4030", "BNPL fee income", GlClassification.REVENUE, StatementSection.FEE_AND_COMMISSION_INCOME),
            new GlAccount("4100", "Loan interest income", GlClassification.REVENUE, StatementSection.INTEREST_INCOME),
            new GlAccount("4110", "BNPL interest income", GlClassification.REVENUE, StatementSection.INTEREST_INCOME),
            new GlAccount("4200", "FX spread revenue", GlClassification.REVENUE, StatementSection.FX_RESULT),
            new GlAccount("4210", "FX realised gains", GlClassification.REVENUE, StatementSection.FX_RESULT),
            new GlAccount("4300", "Reconciliation gains", GlClassification.REVENUE, StatementSection.OTHER_INCOME),
            new GlAccount("5000", "Processing costs", GlClassification.EXPENSE,
                    StatementSection.PROCESSING_AND_DISPUTE_COSTS),
            new GlAccount("5010", "Dispute costs", GlClassification.EXPENSE, StatementSection.PROCESSING_AND_DISPUTE_COSTS),
            new GlAccount("5100", "Loan write-offs", GlClassification.EXPENSE, StatementSection.CREDIT_LOSSES),
            new GlAccount("5110", "BNPL write-offs", GlClassification.EXPENSE, StatementSection.CREDIT_LOSSES),
            new GlAccount("5200", "FX realised losses", GlClassification.EXPENSE, StatementSection.FX_RESULT),
            new GlAccount("5210", "Rounding residual", GlClassification.EXPENSE, StatementSection.FX_RESULT),
            new GlAccount("5300", "Reconciliation losses", GlClassification.EXPENSE, StatementSection.OTHER_EXPENSES));

    private static final List<Row> ROWS = List.of(
            new Row("CUSTOMER_WALLET", "CUSTOMER", "2000"),
            new Row("MERCHANT_PAYABLE", "MERCHANT", "2100"),
            new Row("LOAN_PRINCIPAL", "LOAN", "1300"),
            new Row("LOAN_PRINCIPAL_DUE", "LOAN", "1300"),
            new Row("LOAN_INTEREST_ACCRUED", "LOAN", "1310"),
            new Row("LOAN_INTEREST_DUE", "LOAN", "1310"),
            new Row("LOAN_FEES_DUE", "LOAN", "1320"),
            new Row("LOAN_CREDIT_BALANCE", "LOAN", "2300"),
            new Row("BNPL_PRINCIPAL", "BNPL_OBLIGATION", "1400"),
            new Row("BNPL_PRINCIPAL_DUE", "BNPL_OBLIGATION", "1400"),
            new Row("BNPL_INTEREST_ACCRUED", "BNPL_OBLIGATION", "1410"),
            new Row("BNPL_INTEREST_DUE", "BNPL_OBLIGATION", "1410"),
            new Row("BNPL_FEES_DUE", "BNPL_OBLIGATION", "1420"),
            new Row("BNPL_CREDIT_BALANCE", "BNPL_OBLIGATION", "2300"),
            new Row("SETTLEMENT_CLEARING", "OPERATIONAL", "1100"),
            new Row("PAYOUT_CLEARING", "OPERATIONAL", "2200"),
            new Row("INSTANT_CLEARING", "OPERATIONAL", "1110"),
            new Row("CASH_AT_BANK", "OPERATIONAL", "1000"),
            new Row("CHARGEBACK_RECOVERABLE", "OPERATIONAL", "1130"),
            new Row("DISPUTE_COSTS", "OPERATIONAL", "5010"),
            new Row("PROCESSING_COSTS", "OPERATIONAL", "5000"),
            new Row("RECONCILIATION_LOSSES", "OPERATIONAL", "5300"),
            new Row("FEE_REVENUE", "OPERATIONAL", "4000"),
            new Row("RECONCILIATION_GAINS", "OPERATIONAL", "4300"),
            new Row("FX_POSITION", "OPERATIONAL", "1140"),
            new Row("ROUNDING_RESIDUAL", "OPERATIONAL", "5210"),
            new Row("FX_SPREAD_REVENUE", "OPERATIONAL", "4200"),
            new Row("FX_REALISED_GAINS", "OPERATIONAL", "4210"),
            new Row("FX_REALISED_LOSSES", "OPERATIONAL", "5200"),
            new Row("FX_PROVIDER_CLEARING", "COUNTERPARTY", "1120"),
            new Row("CORRIDOR_CLEARING", "COUNTERPARTY", "2210"),
            new Row("LOAN_INTEREST_INCOME", "OPERATIONAL", "4100"),
            new Row("LOAN_FEE_INCOME", "OPERATIONAL", "4020"),
            new Row("LOAN_WRITE_OFF_EXPENSE", "OPERATIONAL", "5100"),
            new Row("LENDING_CAPITAL", "OPERATIONAL", "3000"),
            new Row("BNPL_MERCHANT_FEE_INCOME", "OPERATIONAL", "4010"),
            new Row("BNPL_INTEREST_INCOME", "OPERATIONAL", "4110"),
            new Row("BNPL_FEE_INCOME", "OPERATIONAL", "4030"),
            new Row("BNPL_WRITE_OFF_EXPENSE", "OPERATIONAL", "5110"),
            new Row("SUSPENSE_UNMATCHED", "SUSPENSE", "2900"));

    /** The reference version, effective from {@link #EFFECTIVE_FROM}. */
    public static GlMappingVersion version() {
        return version(EFFECTIVE_FROM);
    }

    /** The reference version, effective from {@code month}. */
    public static GlMappingVersion version(YearMonth month) {
        return new GlMappingVersion(month, ACCOUNTS, ROWS.stream().map(row -> new MappingRule(row.purpose(),
                row.ownerKind(), row.code())).toList());
    }

    /** The reference, every rule but those of {@code purposes} - a version that is not total. */
    public static GlMappingVersion without(YearMonth month, Set<String> purposes) {
        return new GlMappingVersion(month, ACCOUNTS, ROWS.stream().filter(row -> !purposes.contains(row.purpose()))
                .map(row -> new MappingRule(row.purpose(), row.ownerKind(), row.code())).toList());
    }

    /**
     * The reference with {@code purpose} mapped to {@code code} instead - with {@code extra} added to the chart when it
     * is not there - e.g. an asset purpose mapped to a liability account: a version that is not compatible.
     */
    public static GlMappingVersion remapped(YearMonth month, String purpose, GlAccount extra) {
        List<GlAccount> accounts = new ArrayList<>(ACCOUNTS);
        if (accounts.stream().noneMatch(account -> account.code().equals(extra.code()))) {
            accounts.add(extra);
        }
        return new GlMappingVersion(month, accounts, ROWS.stream().map(row -> new MappingRule(row.purpose(),
                row.ownerKind(), row.purpose().equals(purpose) ? extra.code() : row.code())).toList());
    }

    /** Every purpose the reference maps, with its owner kind - in the order of the ledger's enum. */
    public static Map<String, String> ownerKinds() {
        Map<String, String> kinds = new LinkedHashMap<>();
        ROWS.forEach(row -> kinds.put(row.purpose(), row.ownerKind()));
        return kinds;
    }

    /**
     * A chart that agrees with the reference - each purpose of the given type - for accounting's own suites, which have
     * no ledger: {@code app}'s {@code LedgerPurposeChartTest} proves the reference against the ledger's real chart.
     */
    public static LedgerChart.Chart agreeingChart(Predicate<String> keep) {
        GlMappingVersion version = version();
        List<LedgerChart.ChartPurpose> purposes = new ArrayList<>();
        for (Row row : ROWS) {
            if (keep.test(row.purpose())) {
                purposes.add(new LedgerChart.ChartPurpose(row.purpose(), row.ownerKind(),
                        Set.of(version.account(row.code()).orElseThrow().classification().name())));
            }
        }
        return new LedgerChart.Chart(purposes, List.of(new LedgerChart.ChartCounterparty("fx-sim-a", "FX_PROVIDER")));
    }
}
