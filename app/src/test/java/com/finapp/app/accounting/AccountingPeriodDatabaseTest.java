package com.finapp.app.accounting;

import static com.finapp.app.accounting.AccountingPeriodFixture.FEBRUARY;
import static com.finapp.app.accounting.AccountingPeriodFixture.IDS;
import static com.finapp.app.accounting.AccountingPeriodFixture.JANUARY;
import static com.finapp.app.accounting.AccountingPeriodFixture.closedThrough;
import static com.finapp.app.accounting.AccountingPeriodFixture.correlation;
import static com.finapp.app.accounting.AccountingPeriodFixture.id;
import static com.finapp.app.accounting.AccountingPeriodFixture.inScope;
import static com.finapp.app.accounting.AccountingPeriodFixture.on;
import static com.finapp.app.accounting.AccountingPeriodFixture.periods;
import static com.finapp.app.accounting.AccountingPeriodFixture.person;
import static com.finapp.app.accounting.AccountingPeriodFixture.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriods;
import com.finapp.accounting.PeriodStatus;
import com.finapp.ledger.PostingCutoffs;
import com.finapp.ledger.PostingService;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The accounting periods against a live PostgreSQL (`P14-TSK-005`; {@code accounting V003}, ADR-0114 decision 3;
 * {@code PHASE_14_PLAN.md} section 8.3 row 2; {@code INV-ACC-03}, {@code INV-ACC-08}, {@code INV-AUD-04}) - the backlog
 * entry's named cases: ten lockers leave one edge, a lock before the grace is refused, periods lock in order, an unlock
 * moves the ledger's cutoff back, a lock stops postings dated in the period (the entry's probe target), and the four-eyes
 * {@code CHECK} for every writer. Every count read from the tables. Only January and February 1999 are used and each
 * case returns them to {@code OPEN} with the cutoff unset ({@link AccountingPeriodFixture}). <strong>Written and
 * owed</strong> (owner decision C5: the database tiers are skipped per task).
 */
@Tag("database")
@DisplayName("accounting periods: open, lock, unlock against the database (P14-TSK-005)")
class AccountingPeriodDatabaseTest {

    private static final int LOCKERS = 10;

    @BeforeEach
    void openAndRestore() {
        AccountingPeriodFixture.restore();
    }

    @AfterEach
    void leaveOpen() {
        AccountingPeriodFixture.restore();
    }

    @Test
    @DisplayName("contention row 2: ten lockers on ten connections - one edge, one history row, one event, one audit"
            + " record, one cutoff move; the other nine converge on LOCKED")
    void twoLocksOneEdge() throws Exception {
        AccountingPeriodId january = id(JANUARY);
        CorrelationId shared = correlation();
        long eventsBefore = count("SELECT count(*) FROM platform.outbox_event WHERE event_type = '"
                + AccountingPeriods.LOCKED_EVENT + "' AND aggregate_id = '" + january.value() + "'");
        long auditBefore = count("SELECT count(*) FROM platform.audit_record WHERE operation = 'accounting.PeriodLocked'"
                + " AND target_id = '" + january.value() + "'");
        ExecutorService pool = Executors.newFixedThreadPool(LOCKERS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<AccountingPeriods.Acted>> racing = new ArrayList<>();
            for (int i = 0; i < LOCKERS; i++) {
                racing.add(pool.submit(() -> {
                    start.await();
                    Actor locker = person();
                    return inScope(locker, shared,
                            uow -> periods().lock(uow, january, locker, "racing", shared));
                }));
            }
            start.countDown();
            List<AccountingPeriods.Acted> outcomes = new ArrayList<>();
            for (Future<AccountingPeriods.Acted> outcome : racing) {
                outcomes.add(outcome.get(2, TimeUnit.MINUTES));
            }
            assertThat(outcomes).extracting(AccountingPeriods.Acted::status).containsOnly(PeriodStatus.LOCKED);
            assertThat(outcomes).filteredOn(acted -> !acted.replayed()).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("SELECT count(*) FROM accounting.period_event WHERE period_id = '" + january.value()
                + "' AND act = 'LOCKED' AND correlation_id = '" + shared.value() + "'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = '"
                + AccountingPeriods.LOCKED_EVENT + "' AND aggregate_id = '" + january.value() + "'"))
                .isEqualTo(eventsBefore + 1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'accounting.PeriodLocked'"
                + " AND target_id = '" + january.value() + "'")).isEqualTo(auditBefore + 1);
        assertThat(count("SELECT count(*) FROM ledger.posting_cutoff_event WHERE correlation_id = '" + shared.value()
                + "'")).as("one cutoff move").isEqualTo(1);
        assertThat(closedThrough()).contains(LocalDate.of(1999, 1, 31));
    }

    @Test
    @DisplayName("a lock before the grace is refused and changes nothing - the period OPEN, the cutoff unset; on the"
            + " grace's last business day it locks")
    void aLockBeforeTheGraceIsRefused() {
        AccountingPeriodId january = id(JANUARY);
        // January 1999 ends on a Sunday: Monday 1 and Tuesday 2 February are the grace.
        Actor accountant = person();
        assertThatThrownBy(() -> inScope(accountant, uow -> periods(on("1999-02-01")).lock(uow, january, accountant,
                null, correlation()))).isInstanceOf(AccountingPeriods.PeriodNotLockable.class)
                .hasMessageContaining("1999-02-02");
        assertThat(row(JANUARY).status()).isEqualTo(PeriodStatus.OPEN);
        assertThat(closedThrough()).isEmpty();
        assertThat(inScope(accountant, uow -> periods(on("1999-02-02")).lock(uow, january, accountant, null,
                correlation())).status()).isEqualTo(PeriodStatus.LOCKED);
    }

    @Test
    @DisplayName("periods lock in order - not while the previous is OPEN or LOCKED - and the edge trigger refuses the"
            + " out-of-order lock and a gap for the owner itself")
    void locksInOrder() throws Exception {
        AccountingPeriodId january = id(JANUARY);
        AccountingPeriodId february = id(FEBRUARY);
        Actor accountant = person();
        assertThatThrownBy(() -> inScope(accountant, uow -> periods().lock(uow, february, accountant, null,
                correlation()))).isInstanceOf(AccountingPeriods.PeriodNotLockable.class)
                .hasMessageContaining("1999-01 is OPEN");
        inScope(accountant, uow -> periods().lock(uow, january, accountant, null, correlation()));
        assertThatThrownBy(() -> inScope(accountant, uow -> periods().lock(uow, february, accountant, null,
                correlation()))).isInstanceOf(AccountingPeriods.PeriodNotLockable.class)
                .hasMessageContaining("1999-01 is LOCKED");
        try (Connection owner = DatabaseRoles.migrator()) {
            refusedBy(owner, "UPDATE accounting.accounting_period SET status = 'LOCKED', locked_by = 'raw',"
                    + " locked_by_type = 'CUSTOMER', locked_at = clock_timestamp() WHERE id = '" + february.value() + "'",
                    "P0001", "lock in order");
            refusedBy(owner, "INSERT INTO accounting.accounting_period (id, period_start, period_end, status, revision,"
                    + " opened_at, correlation_id) VALUES ('" + UUID.randomUUID() + "', DATE '1998-11-01',"
                    + " DATE '1998-11-30', 'OPEN', 1, clock_timestamp(), 'raw')", "P0001", "contiguous");
            refused(owner, "DELETE FROM accounting.accounting_period WHERE id = '" + february.value() + "'", "P0001");
        }
        assertThat(row(FEBRUARY).status()).isEqualTo(PeriodStatus.OPEN);
    }

    @Test
    @DisplayName("an unlock moves the cutoff back: proposed by one person, approved by another - the period OPEN, the"
            + " pin released, the cutoff unset again, the move in the ledger's history")
    void anUnlockMovesTheCutoffBack() throws Exception {
        AccountingPeriodId january = id(JANUARY);
        Actor accountant = person();
        Actor controller = person();
        inScope(accountant, uow -> periods().lock(uow, january, accountant, null, correlation()));
        assertThat(closedThrough()).contains(LocalDate.of(1999, 1, 31));
        inScope(accountant, uow -> periods().proposeUnlock(uow, january, accountant, "a missed accrual",
                correlation()));
        assertThat(closedThrough()).as("a proposal moves nothing").contains(LocalDate.of(1999, 1, 31));
        CorrelationId approval = correlation();
        AccountingPeriods.Acted unlocked = inScope(controller, approval,
                uow -> periods().approveUnlock(uow, january, controller, "reviewed", approval));
        assertThat(unlocked.status()).isEqualTo(PeriodStatus.OPEN);
        assertThat(row(JANUARY).pinned()).isEmpty();
        assertThat(closedThrough()).isEmpty();
        assertThat(count("SELECT count(*) FROM ledger.posting_cutoff_event WHERE correlation_id = '" + approval.value()
                + "' AND from_closed_through = DATE '1999-01-31' AND to_closed_through IS NULL")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM accounting.period_event WHERE period_id = '" + january.value()
                + "' AND act = 'UNLOCKED' AND correlation_id = '" + approval.value() + "' AND proposed_by = '"
                + accountant.id() + "'")).isEqualTo(1);
    }

    @Test
    @DisplayName("INV-ACC-08 from the lock: an entry dated in the locked period is refused at insert (ledger.PeriodClosed's"
            + " SQLSTATE), the day after is not - the entry's named probe target")
    void aLockStopsPostingsDatedInThePeriod() throws Exception {
        AccountingPeriodId january = id(JANUARY);
        Actor accountant = person();
        inScope(accountant, uow -> periods().lock(uow, january, accountant, null, correlation()));
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            assertThatThrownBy(() -> rawEntry(app, LocalDate.of(1999, 1, 20)))
                    .isInstanceOf(SQLException.class)
                    .extracting(failure -> ((SQLException) failure).getSQLState())
                    .isEqualTo(PostingCutoffs.PERIOD_CLOSED_SQLSTATE);
            app.rollback();
            rawEntry(app, LocalDate.of(1999, 2, 1)); // open: not refused at insert (its missing lines fail at commit)
            app.rollback();
        }
    }

    @Test
    @DisplayName("the proposer cannot approve: SelfApprovalRefused and nothing moves; a raw self-approval is refused by"
            + " accounting_period_unlock_four_eyes and a raw self-approved history row by period_event_four_eyes")
    void theProposerCannotApprove() throws Exception {
        AccountingPeriodId january = id(JANUARY);
        Actor accountant = person();
        inScope(accountant, uow -> periods().lock(uow, january, accountant, null, correlation()));
        inScope(accountant, uow -> periods().proposeUnlock(uow, january, accountant, "mine", correlation()));
        assertThatThrownBy(() -> inScope(accountant, uow -> periods().approveUnlock(uow, january,
                SecurityContext.require(), "mine", correlation())))
                .isInstanceOf(AccountingPeriods.SelfApprovalRefused.class);
        assertThat(row(JANUARY).unlockPending()).isTrue();
        assertThat(closedThrough()).contains(LocalDate.of(1999, 1, 31));
        try (Connection owner = DatabaseRoles.migrator()) {
            refusedBy(owner, "UPDATE accounting.accounting_period SET status = 'OPEN', locked_by = NULL,"
                    + " locked_by_type = NULL, locked_at = NULL, unlock_approved_by = unlock_proposed_by,"
                    + " unlock_approved_by_type = 'CUSTOMER', unlock_approved_at = clock_timestamp(),"
                    + " unlock_approval_reason = 'raw' WHERE id = '" + january.value() + "'",
                    "23514", "accounting_period_unlock_four_eyes");
            refusedBy(owner, "INSERT INTO accounting.period_event (id, period_id, act, from_status, to_status, revision,"
                    + " actor, actor_type, proposed_by, reason, occurred_at, correlation_id) VALUES ('"
                    + UUID.randomUUID() + "', '" + january.value() + "', 'UNLOCKED', 'LOCKED', 'OPEN', 1, '"
                    + accountant.id() + "', 'CUSTOMER', '" + accountant.id() + "', 'raw', clock_timestamp(), 'raw')",
                    "23514", "period_event_four_eyes");
        }
    }

    // -----------------------------------------------------------------

    private static void rawEntry(Connection app, LocalDate postingDate) throws SQLException {
        try (PreparedStatement insert = app.prepareStatement("INSERT INTO ledger.journal_entry (id, posting_date,"
                + " value_date, entry_type, reference, actor_id, correlation_id, causation_id, idempotency_scope,"
                + " created_at) VALUES (?, ?, ?, 'POSTING', 'raw-sql', 'raw', ?, ?, ?, ?)")) {
            String correlation = UUID.randomUUID().toString();
            insert.setObject(1, IDS.next());
            insert.setObject(2, postingDate);
            insert.setObject(3, postingDate);
            insert.setString(4, correlation);
            insert.setString(5, correlation);
            insert.setString(6, PostingService.IDEMPOTENCY_SCOPE + ":raw-" + IDS.next());
            insert.setTimestamp(7, Timestamp.from(AccountingPeriodFixture.NOW.instant()));
            insert.executeUpdate();
        }
    }

    static long count(String sql) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }

    /** Refused with {@code sqlState}, its message naming {@code rank} - not another rank raising the same state. */
    private static void refusedBy(Connection connection, String sql, String sqlState, String rank) {
        assertThatThrownBy(() -> {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(sql);
                    }
                })
                .as(sql)
                .isInstanceOf(SQLException.class)
                .satisfies(failure -> {
                    assertThat(((SQLException) failure).getSQLState()).as(sql).isEqualTo(sqlState);
                    assertThat(failure.getMessage()).as("refused by %s itself", rank).contains(rank);
                });
    }

    private static void refused(Connection connection, String sql, String sqlState) {
        assertThatThrownBy(() -> {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(sql);
                    }
                })
                .as(sql)
                .isInstanceOf(SQLException.class)
                .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).as(sql).isEqualTo(sqlState));
    }
}
