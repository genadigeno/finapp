package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.CheckVerdict;
import com.finapp.accounting.CloseCheckCode;
import com.finapp.accounting.CloseCheckId;
import com.finapp.accounting.CloseCheckStatus;
import com.finapp.accounting.CloseChecks;
import com.finapp.accounting.CloseRunId;
import com.finapp.accounting.CloseRuns;
import com.finapp.accounting.ReconciliationRunCoverage;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.reconciliation.Matching;
import com.finapp.settlement.BatchAcceptance;
import com.finapp.settlement.DeliveryChannel;
import com.finapp.settlement.FileParsing;
import com.finapp.settlement.FileReception;
import com.finapp.settlement.SettlementAuditAction;
import com.finapp.settlement.SettlementSourceDescriptor;
import com.finapp.settlement.SettlementSources;
import com.finapp.settlement.TransactionRunner;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * An honestly quiet month closes (`P14-TSK-012`; the owner's decisions of 2026-11-02 and 2026-11-03; {@code RECONCILIATION_DISCLOSURE}): a month with no run is {@code FAILED}, naming every active source; each counterparty's nil report goes through the existing door - the
 * channel's authentication here, a pull - the parse, the acceptance and the run leg, its run {@code COMPLETED} at no items with nothing posted; and the check then reads the month as run. Nothing here mints a run: every one is born in an acceptance's transaction, from a stored file. The real
 * composition, against PostgreSQL. <strong>Written and owed</strong> (owner decision C5); the formats' half is {@code NilReportsAreEvidenceTest}, and runs on every build.
 *
 * <p><strong>A database of its own</strong> ({@code own-container}): a bank statement's chain starts at sequence 1 with a zero opening, and a shared database's has moved on.
 */
@Tag("database")
@Tag("own-container") // its own JVM and database: ownContainerDatabaseTest, the javadoc says why
@SpringBootTest
@DisplayName("a quiet month runs on nil reports (P14-TSK-012)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class QuietMonthDatabaseTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final YearMonth MONTH = YearMonth.of(1999, 3);
    private static final LocalDate DAY = MONTH.atDay(31);

    @Autowired private SettlementSources sources;
    @Autowired private FileReception<Connection> reception;
    @Autowired private FileParsing parsing;
    @Autowired private BatchAcceptance acceptance;
    @Autowired private Matching matching;
    @Autowired private TransactionRunner settlementTransactionRunner;
    @Autowired private ReconciliationMonthReads monthReads;
    @Autowired private CloseChecks closeChecks;

    @Test
    @DisplayName("no run: FAILED MONTH_NOT_RUN, naming every active source; nil reports through the door: COMPLETED BATCH runs, posting nothing, the month run")
    void aQuietMonthRunsOnNilReports() throws Exception {
        CheckVerdict quiet = judged();
        assertThat(quiet.status()).isEqualTo(CloseCheckStatus.FAILED);
        assertThat(quiet.failures()).containsExactly(CloseChecks.MONTH_NOT_RUN);
        sources.declared().forEach(source -> assertThat(quiet.evidence()).contains(source.code()));

        for (SettlementSourceDescriptor source : sources.declared()) {
            pull(source, nilReport(source));
        }
        for (int round = 0; round < sources.declared().size(); round++) {
            parsing.sweep();
            acceptance.sweep();
            matching.sweep();
        }

        ReconciliationRunCoverage.Coverage coverage;
        try (Connection app = DatabaseRoles.application()) {
            coverage = monthReads.ofMonth(app, MONTH);
        }
        assertThat(coverage.sources()).filteredOn(source -> source.state() == ReconciliationRunCoverage.SourceState.ACTIVE).hasSize(sources.declared().size());
        assertThat(coverage.lines()).as("one run a source, born in its acceptance").hasSize(sources.declared().size()).allSatisfy(line -> {
            assertThat(line.kind()).isEqualTo("BATCH");
            assertThat(line.status()).isEqualTo("COMPLETED");
            assertThat(line.repudiated()).isFalse();
        });

        CheckVerdict run = judged();
        assertThat(run.status()).as("run; PASSED or DISCLOSED by what else stands, never FAILED by the month").isNotEqualTo(CloseCheckStatus.FAILED);
        assertThat(run.evidence()).contains("\"sourcesNotRun\":[]");
    }

    private CheckVerdict judged() {
        Optional<CheckVerdict> verdict = closeChecks.judge(new CloseRuns.ClaimedCheck(CloseCheckId.next(IDS), CloseRunId.next(IDS), AccountingPeriodId.of(IDS.next()), MONTH,
                CloseCheckCode.RECONCILIATION_DISCLOSURE, 1, IDS.next(), 0, false, CorrelationId.generate(IDS), Actor.SYSTEM));
        return verdict.orElseThrow();
    }

    private void pull(SettlementSourceDescriptor source, String content) {
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            FileReception.Result result = settlementTransactionRunner.inTransaction(uow -> reception.receive(uow, new FileReception.Delivery(source.code(), DeliveryChannel.PULL,
                    content.getBytes(StandardCharsets.UTF_8), Optional.empty(), Actor.SYSTEM, SettlementAuditAction.SETTLEMENT_FILE_UPLOADED, CorrelationContext.current().orElseThrow())));
            assertThat(result).as(source.code()).isInstanceOf(FileReception.Result.New.class);
        }
    }

    /** A nil report in the source's own format, one of its settled two-decimal currencies, its own remittance shape. */
    private static String nilReport(SettlementSourceDescriptor source) {
        String currency = source.settledCurrencies().stream().filter(code -> code.minorUnits() == 2).map(CurrencyCode::code).sorted().findFirst().orElse("EUR");
        String remittance = source.remittanceReferencePattern().map(pattern -> pattern.substring(0, pattern.indexOf('[')) + "19990331").orElse("");
        return switch (source.format()) {
            case SIM_PSP_CSV -> "H,SIM_PSP_CSV,1,PSPB-" + DAY + "-01," + currency + "," + DAY + "\nT,0,0.00," + remittance + "\n";
            case SIM_PAYOUT_CSV -> "H,SIM_PAYOUT_CSV,1,PAYDAY-" + DAY + "-" + currency + "," + currency + "," + DAY + "\nT,0,0.00," + remittance + "\n";
            case SIM_FX_CSV -> "H,SIM_FX_CSV,1,FXB-" + DAY + "-" + currency + "," + currency + "," + DAY + "\nT,0,0.00," + remittance + "\n";
            case SIM_CORRIDOR_CSV -> "H,SIM_CORRIDOR_CSV,1,XB-" + DAY + "-" + currency + "," + currency + "," + DAY + "\nT,0,0.00," + remittance + "\n";
            case SIM_SCHEME_JSON -> "{\"format\":\"SIM_SCHEME_JSON\",\"version\":1,\"cycle\":\"" + DAY + "-C1\",\"currency\":\"" + currency + "\",\"businessDate\":\"" + DAY + "\",\"remittanceReference\":\"" + remittance
                    + "\",\"entries\":[],\"net\":\"0.00\",\"entryCount\":0}";
            case SIM_STATEMENT_TAGGED -> ":20:SB-STMT-19990331-" + currency + "\n:25:SIMBANK-" + currency + "-01\n:28C:1\n:60F:C," + DAY.minusDays(1) + "," + currency + ",0.00\n:62F:C," + DAY + "," + currency + ",0.00\n";
        };
    }
}
