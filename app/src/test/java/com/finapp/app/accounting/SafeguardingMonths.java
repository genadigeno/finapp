package com.finapp.app.accounting;

import static com.finapp.app.accounting.AccountingPeriodFixture.IDS;
import static com.finapp.app.accounting.AccountingPeriodFixture.NOW;
import static com.finapp.app.accounting.AccountingPeriodFixture.correlation;
import static com.finapp.app.accounting.AccountingPeriodFixture.id;
import static com.finapp.app.accounting.AccountingPeriodFixture.person;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.JdbcGlMappingStore;
import com.finapp.accounting.JdbcReportRunStore;
import com.finapp.accounting.ReportRunId;
import com.finapp.accounting.ReportRunStore;
import com.finapp.accounting.ReportRuns;
import com.finapp.accounting.TrialBalanceReads;
import com.finapp.accounting.reporting.ReportDefinitions;
import com.finapp.accounting.reporting.ReportGeneration;
import com.finapp.accounting.reporting.RunParameters;
import com.finapp.accounting.reporting.SafeguardingPositionDefinition;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.testing.database.DatabaseRoles;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.YearMonth;
import java.util.function.Function;

/**
 * A month's real close and a FINAL of the real definition, for a suite in another package holding real loans (`P14-TSK-019`'s review, M-4): {@code SafeguardingCommittedDatabaseTest} lives beside lending's client, and the period fixtures are this package's. No state of its own. Test code: it ships in no jar.
 */
public final class SafeguardingMonths {

    /** The month the fixtures open first. */
    public static final YearMonth JANUARY = AccountingPeriodFixture.JANUARY;

    private static final TrialBalanceReads READS = new LedgerTrialBalanceReads(new JdbcGlMappingStore());
    private static final ReportDefinitions DEFINITIONS = ReportDefinitions.of(new SafeguardingPositionDefinition(READS, new LedgerCounterpartyPositionReads(), new LendingFundedAsOfCutoffReads()));
    private static final JdbcReportSnapshots SNAPSHOTS = new JdbcReportSnapshots(DatabaseRoles::application, ReportRuns.Leases.STANDARD.generationTimeout());
    /** Report generation's snapshots, as production opens them: the reporting role ({@code P15-TSK-021}). */
    private static final JdbcReportSnapshots GENERATION_SNAPSHOTS = new JdbcReportSnapshots(DatabaseRoles::reporting,
            ReportRuns.Leases.STANDARD.generationTimeout());
    private static final ReportRuns RUNS = new ReportRuns(new JdbcReportRunStore(), AccountingPeriodFixture.STORE, new JdbcGlMappingStore(), DEFINITIONS, ReportRuns.Leases.STANDARD, new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, NOW);

    private static final AccountingTransactions TRANSACTIONS = new AccountingTransactions() {
        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            try (Connection connection = DatabaseRoles.application()) {
                connection.setAutoCommit(false);
                try {
                    R result = work.apply(connection);
                    connection.commit();
                    return result;
                } catch (RuntimeException failure) {
                    connection.rollback();
                    throw failure;
                }
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
        }
    };

    private SafeguardingMonths() {}

    /** A FINAL run: its id and its content as issued. */
    public record Issued(ReportRunId run, String content) {}

    /** January and February 1999 exist, a GL mapping in force from January. */
    public static void chartInForce() {
        AccountingPeriodFixture.openJanuaryAndFebruary();
        PeriodCloseFixture.mappingInForce();
    }

    /** {@code month} closed by the machine's own four-eyes doors. */
    public static void closed(YearMonth month) {
        PeriodCloseFixture.closed(month);
    }

    /** The month's FINAL of {@code SAFEGUARDING_POSITION}, through the machine's own claim and generation. */
    public static Issued issued(YearMonth month) {
        AccountingPeriodId period = id(month);
        Actor accountant = person();
        ReportRuns.Claimed claimed = TRANSACTIONS.inTransaction(uow -> RUNS.claim(uow, SafeguardingPositionDefinition.CODE, period, RunParameters.none(), accountant, correlation()));
        ReportRunStore.RunRow done = generator().generateAndComplete(claimed.run(), accountant);
        String content = TRANSACTIONS.inTransaction(uow -> RUNS.content(uow, done.id())).orElseThrow().text();
        return new Issued(done.id(), content);
    }

    /** The machine's own comparison of a run regenerated with its stored bytes. */
    public static boolean regeneratesIdentical(ReportRunId run) {
        return generator().regenerate(run).identical();
    }

    private static ReportRunGenerator generator() {
        return new ReportRunGenerator(RUNS, new ReportGeneration(ReportGeneration.FROM_THE_SNAPSHOT, ReportRuns.Leases.STANDARD.generationTimeout(), NOW), GENERATION_SNAPSHOTS, TRANSACTIONS,
                new ReportRunMetrics(new SimpleMeterRegistry(), DEFINITIONS));
    }
}
