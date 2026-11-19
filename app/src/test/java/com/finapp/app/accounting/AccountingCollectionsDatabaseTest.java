package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.AccountingCollections;
import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.GlMappingStore;
import com.finapp.accounting.GlMappingVersionStatus;
import com.finapp.accounting.JdbcAccountingCollections;
import com.finapp.accounting.ReportRunStore;
import com.finapp.accounting.RunStatus;
import com.finapp.accounting.ValuationRateSetStatus;
import com.finapp.accounting.ValuationRateSetStore;
import com.finapp.accounting.reporting.RunParameters;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The accounting lists against PostgreSQL (`P17-TSK-023`): each a keyset walk that serves every row once, in its declared order -
 * a tie on the instant broken by the id, and split by a page boundary - and each filter narrowing it. The rows are planted as the
 * owner with the triggers off (the {@code StatementsDatabaseTest} idiom: a run is born {@code GENERATING} and a set
 * {@code PROPOSED} by their doors, and stamped with the database's clock, so a walk's order and its ties are planted only this
 * way), in 2091, in one transaction rolled back whole - the {@code CHECK}s still hold every row to its table's shape. The requests,
 * the permissions and the records are {@code AccountingListsApiDatabaseTest}'s.
 */
@Tag("database")
@DisplayName("the accounting lists against PostgreSQL (P17-TSK-023)")
class AccountingCollectionsDatabaseTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final AccountingCollections COLLECTIONS = new JdbcAccountingCollections();
    private static final Instant T1 = Instant.parse("2091-02-01T10:00:00.000001Z");
    private static final Instant T2 = Instant.parse("2091-02-01T10:00:00.000002Z");
    private static final Instant T3 = Instant.parse("2091-02-02T08:30:15.123456Z");
    private static final Instant T4 = Instant.parse("2091-02-03T09:00:00.654321Z");
    private static final Instant T5 = Instant.parse("2091-02-04T12:00:00Z");

    @Test
    @DisplayName("report runs walk once, newest claim first, a tie split by a page boundary; a period, a status and the FINAL"
            + " statements' definitions narrow them")
    void theRunsWalkOnceNewestFirst() throws SQLException {
        try (Connection root = DatabaseRoles.bootstrap()) {
            root.setAutoCommit(false);
            try (Statement statement = root.createStatement()) {
                statement.execute("SET LOCAL session_replication_role = replica");
                UUID january = period(root, "2091-01-01");
                UUID february = period(root, "2091-02-01");
                UUID balanceSheet = run(root, january, "BALANCE_SHEET", 2, "FINAL", T5);
                UUID incomeStatement = run(root, january, "INCOME_STATEMENT", 1, "FINAL", T4);
                UUID trialBalance = run(root, january, "TRIAL_BALANCE", 1, "FINAL", T4);
                UUID superseded = run(root, january, "BALANCE_SHEET", 1, "SUPERSEDED", T2);
                UUID failed = run(root, january, "INCOME_STATEMENT", 2, "FAILED", T1);
                UUID provisional = run(root, february, "BALANCE_SHEET", 1, "PROVISIONAL", T3);
                AccountingCollections.RunFilter ofJanuary = new AccountingCollections.RunFilter(
                        Optional.of(AccountingPeriodId.of(january)), Optional.empty(), Set.of());

                List<ReportRunStore.RunRow> walked = walkRuns(root, ofJanuary, 2);

                // PostgreSQL orders uuid by its bytes, the lowercase hex text's order - not UUID.compareTo's signed longs.
                List<UUID> tie = (incomeStatement.toString().compareTo(trialBalance.toString()) > 0)
                        ? List.of(incomeStatement, trialBalance) : List.of(trialBalance, incomeStatement);
                assertThat(walked.stream().map(row -> row.id().value()).toList())
                        .as("T5, the T4 tie by id descending - split between the first page and the second - T2, T1")
                        .containsExactly(balanceSheet, tie.get(0), tie.get(1), superseded, failed);
                assertThat(walked).isSortedAccordingTo(Comparator.comparing(ReportRunStore.RunRow::claimedAt).reversed());
                assertThat(walked.get(0).claimedAt()).as("the cursor's instant is exact to the microsecond").isEqualTo(T5);
                assertThat(walked.get(0).period().toString()).isEqualTo("2091-01");

                assertThat(walkRuns(root, new AccountingCollections.RunFilter(Optional.of(AccountingPeriodId.of(january)),
                        Optional.of(RunStatus.FINAL), FinalStatementDesk.STATEMENTS), 1))
                        .as("the FINAL statements: no trial balance, no superseded, no failed run")
                        .extracting(row -> row.id().value()).containsExactly(balanceSheet, incomeStatement);
                assertThat(walkRuns(root, new AccountingCollections.RunFilter(Optional.of(AccountingPeriodId.of(february)),
                        Optional.of(RunStatus.PROVISIONAL), Set.of()), 50))
                        .extracting(row -> row.id().value()).containsExactly(provisional);
                assertThat(walkRuns(root, new AccountingCollections.RunFilter(Optional.of(AccountingPeriodId.of(february)),
                        Optional.of(RunStatus.FINAL), Set.of()), 50)).isEmpty();
            } finally {
                root.rollback();
            }
        }
    }

    @Test
    @DisplayName("mapping versions walk once, newest version first; a status narrows them to the approver's queue")
    void theVersionsWalkNewestFirst() throws SQLException {
        try (Connection root = DatabaseRoles.bootstrap()) {
            root.setAutoCommit(false);
            try (Statement statement = root.createStatement()) {
                statement.execute("SET LOCAL session_replication_role = replica");
                int top = maxVersion(root);
                UUID first = version(root, top + 1, "PROPOSED", "accountant-a");
                UUID rejected = version(root, top + 2, "REJECTED", "accountant-b");
                UUID second = version(root, top + 3, "PROPOSED", "accountant-b");
                UUID third = version(root, top + 4, "PROPOSED", "accountant-a");

                List<GlMappingStore.VersionRow> all = walkVersions(root, Optional.empty(), 3);
                assertThat(all.stream().limit(4).map(row -> row.id().value()).toList())
                        .as("the newest four are this test's, newest first").containsExactly(third, second, rejected, first);
                assertThat(new HashSet<>(all.stream().map(GlMappingStore.VersionRow::version).toList())).hasSize(all.size());
                assertThat(all).isSortedAccordingTo(Comparator.comparingInt(GlMappingStore.VersionRow::version).reversed());

                List<GlMappingStore.VersionRow> queue = walkVersions(root, Optional.of(GlMappingVersionStatus.PROPOSED), 1);
                assertThat(queue).allSatisfy(row -> assertThat(row.status()).isEqualTo(GlMappingVersionStatus.PROPOSED));
                assertThat(queue.stream().map(row -> row.id().value()).toList()).containsSubsequence(third, second, first)
                        .doesNotContain(rejected);
                assertThat(queue.get(0).proposedBy()).isEqualTo("accountant-a");
            } finally {
                root.rollback();
            }
        }
    }

    @Test
    @DisplayName("rate sets walk once, newest capture first, a tie split by a page boundary; a period, a status and the capturing"
            + " person narrow them")
    void theSetsWalkOnceNewestFirst() throws SQLException {
        try (Connection root = DatabaseRoles.bootstrap()) {
            root.setAutoCommit(false);
            try (Statement statement = root.createStatement()) {
                statement.execute("SET LOCAL session_replication_role = replica");
                UUID march = period(root, "2091-03-01");
                String accountant = "accountant-" + UUID.randomUUID();
                UUID proposed = set(root, march, "PROPOSED", accountant, T3);
                UUID another = set(root, march, "PROPOSED", "accountant-other", T3);
                UUID approved = set(root, march, "APPROVED", accountant, T2);
                UUID rejected = set(root, march, "REJECTED", accountant, T1);
                AccountingCollections.SetFilter ofMarch = new AccountingCollections.SetFilter(
                        Optional.of(AccountingPeriodId.of(march)), Optional.empty(), Optional.empty());

                List<ValuationRateSetStore.SetRow> walked = walkSets(root, ofMarch, 1);

                List<UUID> tie = (proposed.toString().compareTo(another.toString()) > 0)
                        ? List.of(proposed, another) : List.of(another, proposed);
                assertThat(walked.stream().map(row -> row.id().value()).toList())
                        .containsExactly(tie.get(0), tie.get(1), approved, rejected);
                assertThat(walked.get(2).decidedBy()).contains("controller-1");
                assertThat(walked.get(0).month().toString()).isEqualTo("2091-03");

                assertThat(walkSets(root, new AccountingCollections.SetFilter(Optional.empty(), Optional.empty(),
                        Optional.of(accountant)), 2)).as("the proposer's own, every period's")
                        .extracting(row -> row.id().value()).containsExactly(proposed, approved, rejected);
                assertThat(walkSets(root, new AccountingCollections.SetFilter(Optional.of(AccountingPeriodId.of(march)),
                        Optional.of(ValuationRateSetStatus.PROPOSED), Optional.empty()), 50))
                        .extracting(row -> row.id().value()).containsExactlyInAnyOrder(proposed, another);
                assertThat(walkSets(root, new AccountingCollections.SetFilter(Optional.of(AccountingPeriodId.of(march)),
                        Optional.of(ValuationRateSetStatus.APPROVED), Optional.of("accountant-other")), 50)).isEmpty();
            } finally {
                root.rollback();
            }
        }
    }

    // ------------------------------------------------------------------------------------------- the walks

    private static List<ReportRunStore.RunRow> walkRuns(Connection root, AccountingCollections.RunFilter filter, int size) {
        return AccountingCollectionsDatabaseTest.<ReportRunStore.RunRow, AccountingCollections.RunCursor>walk(
                cursor -> COLLECTIONS.reportRuns(root, filter, cursor, size),
                row -> new AccountingCollections.RunCursor(row.claimedAt(), row.id().value()));
    }

    private static List<GlMappingStore.VersionRow> walkVersions(Connection root, Optional<GlMappingVersionStatus> status,
            int size) {
        return AccountingCollectionsDatabaseTest.<GlMappingStore.VersionRow, Integer>walk(
                cursor -> COLLECTIONS.mappingVersions(root, status, cursor, size), GlMappingStore.VersionRow::version);
    }

    private static List<ValuationRateSetStore.SetRow> walkSets(Connection root, AccountingCollections.SetFilter filter,
            int size) {
        return AccountingCollectionsDatabaseTest.<ValuationRateSetStore.SetRow, AccountingCollections.SetCursor>walk(
                cursor -> COLLECTIONS.rateSets(root, filter, cursor, size),
                row -> new AccountingCollections.SetCursor(row.capturedAt(), row.id().value()));
    }

    /** Pages until one comes back empty, each resuming after the last row served - the desk's walk, without its extra row. */
    private static <R, C> List<R> walk(Function<Optional<C>, List<R>> page, Function<R, C> key) {
        List<R> walked = new ArrayList<>();
        Optional<C> cursor = Optional.empty();
        for (int pages = 0; pages < 10_000; pages++) {
            List<R> rows = page.apply(cursor);
            walked.addAll(rows);
            if (rows.isEmpty()) {
                return walked;
            }
            cursor = Optional.of(key.apply(rows.get(rows.size() - 1)));
        }
        throw new AssertionError("the walk did not end");
    }

    // ------------------------------------------------------------------------------------------- planting

    /** An OPEN month of 2091 - the CHECKs' shape: a calendar month, no lock, no pin. */
    private static UUID period(Connection root, String start) throws SQLException {
        UUID id = IDS.next();
        execute(root, "INSERT INTO accounting.accounting_period (id, period_start, period_end, status, revision, opened_at,"
                + " correlation_id) VALUES (?, ?::date, (?::date + interval '1 month')::date - 1, 'OPEN', 1, now(), 'p17-023')",
                id, start, start);
        return id;
    }

    /** A run in {@code status}, claimed at {@code claimedAt}: content, completion, failure and supersession as its status holds. */
    private static UUID run(Connection root, UUID period, String definition, int revision, String status, Instant claimedAt)
            throws SQLException {
        UUID id = IDS.next();
        boolean provisional = "PROVISIONAL".equals(status);
        boolean content = Set.of("FINAL", "PROVISIONAL", "SUPERSEDED").contains(status);
        Timestamp claimed = Timestamp.from(claimedAt);
        execute(root, "INSERT INTO accounting.report_run (id, definition, definition_version, period_id, revision, parameters,"
                + " parameters_hash, period_revision, basis, period_locked_at, cut_at, requested_by, requested_by_type,"
                + " claimed_at, status, attempt, lease_until, content, content_sha256, completed_at, failure_code, failed_at,"
                + " superseded_at, superseded_correlation_id, correlation_id) VALUES (?, ?, 1, ?, ?, ?,"
                + " encode(sha256(convert_to(?, 'UTF8')), 'hex'), 1, ?, ?, ?, 'accountant-1', 'EMPLOYEE', ?, ?, 1,"
                + " ?::timestamptz + interval '10 minutes', ?, ?, ?, ?, ?, ?, ?, 'p17-023')",
                id, definition, period, revision, RunParameters.none().canonical(), RunParameters.none().canonical(),
                provisional ? "PROVISIONAL" : "FINAL", provisional ? null : Timestamp.from(Instant.parse("2091-02-01T00:00:00Z")),
                claimed, claimed, status, claimed, content ? "{}" : null, content ? "a".repeat(64) : null,
                content ? claimed : null, "FAILED".equals(status) ? "DEFINITION_FAILED" : null,
                "FAILED".equals(status) ? claimed : null, "SUPERSEDED".equals(status) ? claimed : null,
                "SUPERSEDED".equals(status) ? "p17-023-reopen" : null);
        return id;
    }

    private static int maxVersion(Connection root) throws SQLException {
        try (Statement statement = root.createStatement();
                ResultSet row = statement.executeQuery("SELECT coalesce(max(version), 0) FROM accounting.gl_mapping_version")) {
            row.next();
            return row.getInt(1);
        }
    }

    /** A version proposed by {@code proposer}; a REJECTED one decided by another, with its reason. */
    private static UUID version(Connection root, int version, String status, String proposer) throws SQLException {
        UUID id = IDS.next();
        boolean decided = !"PROPOSED".equals(status);
        execute(root, "INSERT INTO accounting.gl_mapping_version (id, version, status, effective_from_period, proposed_by,"
                + " proposed_by_type, proposed_at, proposal_reason, decided_by, decided_by_type, decided_at, decision_reason,"
                + " correlation_id) VALUES (?, ?, ?, '2091-04-01', ?, 'EMPLOYEE', clock_timestamp(), 'a planted proposal', ?, ?,"
                + " ?, ?, 'p17-023')",
                id, version, status, proposer, decided ? "controller-1" : null, decided ? "EMPLOYEE" : null,
                decided ? Timestamp.from(T5) : null, decided ? "a planted decision" : null);
        return id;
    }

    /** A set captured by {@code capturer} at {@code capturedAt}; a decided one decided by another, with its reason. */
    private static UUID set(Connection root, UUID period, String status, String capturer, Instant capturedAt)
            throws SQLException {
        UUID id = IDS.next();
        boolean decided = !"PROPOSED".equals(status);
        execute(root, "INSERT INTO accounting.valuation_rate_set (id, period_id, functional_currency, as_of, max_age_hours,"
                + " rate_count, status, captured_by, captured_by_type, captured_at, decided_by, decided_by_type, decided_at,"
                + " decision_reason, correlation_id) VALUES (?, ?, 'EUR', '2091-04-01T00:00:00Z', 72, 3, ?, ?, 'EMPLOYEE', ?, ?,"
                + " ?, ?, ?, 'p17-023')",
                id, period, status, capturer, Timestamp.from(capturedAt), decided ? "controller-1" : null,
                decided ? "EMPLOYEE" : null, decided ? Timestamp.from(T5) : null, decided ? "a planted decision" : null);
        return id;
    }

    private static void execute(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.executeUpdate();
        }
    }
}
