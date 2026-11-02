package com.finapp.app.accounting;

import static com.finapp.accounting.testing.ReportBodies.minor;
import static com.finapp.accounting.testing.ReportBodies.row;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.RunBasis;
import com.finapp.accounting.TrialBalanceReads;
import com.finapp.accounting.reporting.CanonicalJson;
import com.finapp.accounting.reporting.PurposeReportDefinition;
import com.finapp.accounting.reporting.ReportInputs;
import com.finapp.accounting.reporting.RunParameters;
import com.finapp.accounting.testing.InMemoryGlMappingStore;
import com.finapp.sharedkernel.id.IdGenerator;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <strong>The entry's probe, on a control that can go red without a database</strong> (`P14-TSK-017`; {@code INV-BAL-05}): {@code
 * LOAN_PORTFOLIO} and {@code BNPL_EXPOSURE} are the ledger's balances by purpose, never lending's or bnpl's live report rows. The real
 * adapter runs its real statements against a stand-in that answers a statement over {@code ledger.} tables with the ledger's rows and one
 * naming a product's schema with the live report's - which disagree, as live rows and a restatement as of a period's end do. Source the
 * portfolio from the product and the figures move: this goes red. The equality against a real journal is {@code
 * OperationalReportsDatabaseTest#loanPortfolioEqualsTheLedger}'s (owed, C5).
 */
@DisplayName("the portfolio and the exposure are the ledger's, never the products' live rows (P14-TSK-017)")
class LoanPortfolioIsTheLedgersTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final YearMonth JUNE = YearMonth.of(2097, 6);
    private static final Instant LOCK = Instant.parse("2097-07-05T10:00:00Z");

    private final AccountingPeriodId periodId = AccountingPeriodId.next(IDS);
    private final TrialBalanceReads reads = new LedgerTrialBalanceReads(new InMemoryGlMappingStore());

    /** The ledger's rows for the journal's statements; the live report's for anything naming a product's schema. */
    private final Function<String, List<Map<String, Object>>> books = sql -> {
        if (sql.contains("lending.") || sql.contains("bnpl.")) {
            return List.of(aggregate("LOAN_PRINCIPAL", "LOAN", 999_999), aggregate("BNPL_PRINCIPAL", "BNPL_OBLIGATION", 999_999));
        }
        if (sql.equals(LedgerTrialBalanceReads.PERIOD)) {
            return List.of(ScriptedStatements.row("period_start", Date.valueOf(JUNE.atDay(1)), "period_end",
                    Date.valueOf(JUNE.atEndOfMonth()), "status", "LOCKED", "pinned_mapping_version_id", null, "previous_pin", null));
        }
        return List.of(aggregate("LOAN_PRINCIPAL", "LOAN", 1_500), aggregate("BNPL_PRINCIPAL", "BNPL_OBLIGATION", 2_500));
    };

    @Test
    @DisplayName("LOAN_PORTFOLIO's closing is the ledger's lines, not the live report's")
    void loanPortfolioIsTheLedgers() {
        CanonicalJson.Obj body = PurposeReportDefinition.loanPortfolio(reads)
                .generate(new ScriptedStatements(books).connection(), inputs());
        assertThat(minor(row(body, "LOAN_PRINCIPAL", "EUR").orElseThrow(), "opening")).isEqualTo(BigInteger.valueOf(1_500));
        assertThat(minor(row(body, "LOAN_PRINCIPAL", "EUR").orElseThrow(), "debits")).isEqualTo(BigInteger.valueOf(1_500));
        assertThat(minor(row(body, "LOAN_PRINCIPAL", "EUR").orElseThrow(), "closing")).isEqualTo(BigInteger.valueOf(3_000));
    }

    @Test
    @DisplayName("BNPL_EXPOSURE's closing is the ledger's lines, not the live report's")
    void bnplExposureIsTheLedgers() {
        CanonicalJson.Obj body = PurposeReportDefinition.bnplExposure(reads)
                .generate(new ScriptedStatements(books).connection(), inputs());
        assertThat(minor(row(body, "BNPL_PRINCIPAL", "EUR").orElseThrow(), "closing")).isEqualTo(BigInteger.valueOf(5_000));
    }

    @Test
    @DisplayName("every statement they make reads the ledger's or accounting's tables and names no product's schema")
    void noStatementNamesAProduct() {
        ScriptedStatements script = new ScriptedStatements(books);
        PurposeReportDefinition.loanPortfolio(reads).generate(script.connection(), inputs());
        PurposeReportDefinition.bnplExposure(reads).generate(script.connection(), inputs());
        assertThat(script.prepared).isNotEmpty().allSatisfy(sql -> assertThat(sql)
                .doesNotContain("lending.").doesNotContain("bnpl.").containsAnyOf("ledger.", "accounting."));
    }

    private ReportInputs inputs() {
        return new ReportInputs(periodId, JUNE, 1, RunBasis.FINAL, Optional.of(LOCK), Optional.empty(), Optional.empty(), LOCK,
                RunParameters.none());
    }

    private static Map<String, Object> aggregate(String purpose, String ownerKind, long minor) {
        return ScriptedStatements.row("purpose", purpose, "owner_kind", ownerKind, "currency", "EUR", "scale", 2, "direction", "DEBIT",
                "total", new BigDecimal(minor), "lines", 1L);
    }
}
