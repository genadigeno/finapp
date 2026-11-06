package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.AsOf;
import com.finapp.ledger.BalanceDerivation;
import com.finapp.ledger.Direction;
import com.finapp.ledger.HoldService;
import com.finapp.ledger.JdbcBalanceDerivation;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcHoldStore;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JdbcProjectionRebuildRows;
import com.finapp.ledger.JdbcProjectionRebuildStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingService;
import com.finapp.ledger.ProjectionFigures;
import com.finapp.ledger.ProjectionRebuildId;
import com.finapp.ledger.ProjectionRebuildRows;
import com.finapp.ledger.ProjectionRebuildStatus;
import com.finapp.ledger.ProjectionRebuilds;
import com.finapp.ledger.ProjectionVerification;
import com.finapp.ledger.RebuildNotOpenException;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The four-eyes projection rebuild against a live PostgreSQL (`P14-TSK-026`, ADR-0119 decision 1; {@code INV-BAL-01},
 * {@code INV-BAL-02}, {@code INV-LED-03}, {@code INV-AUD-04}) - owed to the Phase 14 database pass (C5).
 *
 * <p><strong>The races really interleave</strong>, each with its holder committed only once the other side is observed
 * waiting on a lock ({@code pg_blocking_pids}): (2) a posting holding the account's projection row while the approval waits
 * for it - <em>would fail</em> if the rebuild summed before taking the row (its sum would miss the posting, and the row end
 * 300 short of the journal); (3) the approval holding the row, paused after its derivation, while a posting waits for it -
 * <em>would fail</em> if the rebuild wrote anything but its own derivation or the posting's increment were lost (the row
 * would not equal the journal); (4) a placer holding the account {@code FOR UPDATE} first, the approval observed blocked
 * behind it before the hold updates the row - <em>would deadlock</em> ({@code 40P01}) if the rebuild took the row before the
 * key share; (5) two approvers of one rebuild serialised on its row.
 *
 * <p><strong>State across cases</strong>, in the order {@link Order} fixes: every case makes its own wallet, posts its own
 * entries against the one shared {@code CHARGEBACK_RECOVERABLE} USD account (whose projection only grows, consistently) and
 * rebuilds only its wallet; every idempotency key carries a fresh id, so a re-run on the shared container meets no key of
 * an earlier run; every rebuild record names its own wallet, so the one-standing index never meets another case's row. (7)
 * writes nothing it does not roll back.
 */
@Tag("database")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("the four-eyes projection rebuild under postings, holds, rebuilds and the schema (P14-TSK-026)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class ProjectionRebuildDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final LocalDate DATE = LocalDate.of(2026, 9, 16);
    private static final Actor PROPOSER = new Actor("rebuild-proposer", ActorType.CUSTOMER);
    private static final Actor APPROVER = new Actor("rebuild-approver", ActorType.CUSTOMER);
    private static final Actor THIRD = new Actor("rebuild-third", ActorType.EMPLOYEE);

    private final LedgerAccountStore<Connection> accounts = new JdbcLedgerAccountStore();
    private final BalanceDerivation<Connection> derivation = new JdbcBalanceDerivation();
    private final ProjectionVerification verification = new ProjectionVerification(new JdbcBalanceDerivation());
    private final JdbcProjectionRebuildRows real = new JdbcProjectionRebuildRows(new JdbcBalanceDerivation());

    @Test
    @Order(1)
    @DisplayName("a drifting row is rebuilt from the journal by a second person: the verification then reads it clean, and"
            + " not one journal line moved")
    void aDriftIsRebuiltAndTheJournalDoesNotMove() throws Exception {
        LedgerAccount wallet = postedWallet(5_000, 1_200);
        drift(wallet.id(), 777);
        long linesBefore = linesOf(wallet.id());
        String journalBefore = journalOf(wallet.id());
        try (Connection app = DatabaseRoles.application(); CorrelationContext.Scope flow = flow()) {
            assertThat(verification.verdictOf(app, wallet.id())).isEqualTo(ProjectionVerification.Verdict.DRIFTING);
        }
        ProjectionRebuildId id = committedAs(PROPOSER, uow -> rebuilds(real).propose(uow, wallet.id(), "a raw writer", key()))
                .rebuild().id();
        ProjectionRebuilds.Outcome applied = committedAs(APPROVER, uow -> rebuilds(real).approve(uow, id, key()));

        assertThat(applied.rebuild().status()).isEqualTo(ProjectionRebuildStatus.APPLIED);
        assertProjectionIsTheJournal(wallet.id());
        try (Connection app = DatabaseRoles.application(); CorrelationContext.Scope flow = flow()) {
            assertThat(verification.verdictOf(app, wallet.id())).isEqualTo(ProjectionVerification.Verdict.CLEAN);
        }
        assertThat(linesOf(wallet.id())).isEqualTo(linesBefore);
        assertThat(journalOf(wallet.id())).as("every line byte for byte").isEqualTo(journalBefore);
        assertThat(applied.rebuild().approval().orElseThrow().before().orElseThrow().postedMinor())
                .isEqualTo(applied.rebuild().approval().orElseThrow().after().postedMinor() - 777);
    }

    @Test
    @Order(2)
    @DisplayName("interleaving 1, a posting first: the approval waits for the row a posting holds, and its sum includes that"
            + " posting once it commits")
    void aPostingHoldingTheRowIsInTheSum() throws Exception {
        LedgerAccount wallet = postedWallet(5_000, 1_200);
        drift(wallet.id(), 777);
        ProjectionRebuildId id = proposed(wallet.id());
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try (Connection holder = DatabaseRoles.application();
                SecurityContext.Scope who = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = flow()) {
            holder.setAutoCommit(false);
            service().post(holder, entry(clearing(holder), Direction.DEBIT, wallet, Direction.CREDIT, 300));
            Future<ProjectionRebuilds.Outcome> approval =
                    pool.submit(() -> committedAs(APPROVER, uow -> rebuilds(real).approve(uow, id, key())));
            awaitWaiters(pid(holder), approval);
            holder.commit();
            ProjectionRebuilds.Outcome outcome = approval.get(30, TimeUnit.SECONDS);
            assertThat(outcome.rebuild().status()).isEqualTo(ProjectionRebuildStatus.APPLIED);
            assertThat(outcome.rebuild().approval().orElseThrow().after().appliedEntries()).isEqualTo(3);
        } finally {
            pool.shutdownNow();
        }
        assertProjectionIsTheJournal(wallet.id());
    }

    @Test
    @Order(3)
    @DisplayName("interleaving 2, the rebuild first: a posting waits for the row the paused approval holds and, once it"
            + " commits, adds its increment to the written row - no lost update")
    void aPostingBehindTheRebuildComposes() throws Exception {
        LedgerAccount wallet = postedWallet(5_000, 1_200);
        drift(wallet.id(), 777);
        ProjectionRebuildId id = proposed(wallet.id());
        Paused paused = new Paused(real);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ProjectionRebuilds.Outcome> approval =
                    pool.submit(() -> committedAs(APPROVER, uow -> rebuilds(paused).approve(uow, id, key())));
            assertThat(paused.derived.await(30, TimeUnit.SECONDS)).as("the approval holds the row and derived").isTrue();
            Future<Void> posting = pool.submit(() -> {
                committedAs(Actor.SYSTEM, uow -> service().post(uow,
                        entry(clearing(uow), Direction.DEBIT, wallet, Direction.CREDIT, 400)));
                return null;
            });
            awaitWaiters(paused.pid, posting);
            paused.release.countDown();
            ProjectionFigures written = approval.get(30, TimeUnit.SECONDS).rebuild().approval().orElseThrow().after();
            posting.get(30, TimeUnit.SECONDS);
            assertThat(written.appliedEntries()).as("the approval's own reading: before the posting").isEqualTo(2);
        } finally {
            paused.release.countDown();
            pool.shutdownNow();
        }
        assertProjectionIsTheJournal(wallet.id());
        assertThat(entriesOf(wallet.id())).isEqualTo(3);
    }

    /**
     * The lock-order case, built so that an inversion deadlocks (the fix round's M-1 - the case it replaces paused the
     * approval after both its locks were held, which no order of them could fail). The placer takes the account
     * {@code FOR UPDATE} first, as {@code HoldService.place} does, and holds it; the approval starts and is observed blocked
     * behind the placer's session; only then does the placer place the hold, which updates the projection row, and commit.
     *
     * <p>Walked by hand. <strong>The posting's order</strong> (the account {@code FOR KEY SHARE}, then the row): the
     * approval waits at the key share holding no row lock, so the placer's {@code UPDATE} of the row is granted, it commits,
     * and the approval then locks the row and derives the hold among the {@code ACTIVE} holds - both complete, the row
     * {@code CLEAN}. <strong>The inverted order</strong> (the row, then the key share): the approval holds the row
     * {@code FOR UPDATE} and waits behind the placer for the key share; the placer's {@code UPDATE} of the row then waits
     * behind the approval - a cycle PostgreSQL breaks after {@code deadlock_timeout} with {@code 40P01} in one of the two,
     * and this case fails whichever it is.
     */
    @Test
    @Order(4)
    @DisplayName("a placer holding the account FOR UPDATE: the approval waits behind it holding no row lock, so the hold's"
            + " update of the row is granted and both complete - the rows' holds the ACTIVE holds; the row taken before the"
            + " key share would deadlock here (40P01)")
    void aHoldHeldFirstCannotDeadlockTheRebuild() throws Exception {
        LedgerAccount wallet = postedWallet(5_000, 1_200);
        drift(wallet.id(), 777);
        ProjectionRebuildId id = proposed(wallet.id());
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try (Connection placer = DatabaseRoles.application();
                SecurityContext.Scope who = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = flow()) {
            placer.setAutoCommit(false);
            // The hold's first lock, taken and held: HoldService.place takes it again in this transaction, re-entrantly.
            assertThat(accounts.lockForUpdate(placer, wallet.id())).isPresent();
            Future<ProjectionRebuilds.Outcome> approval =
                    pool.submit(() -> committedAs(APPROVER, uow -> rebuilds(real).approve(uow, id, key())));
            awaitWaiters(pid(placer), approval);
            holdService().place(placer, wallet.id(), Money.ofMinorUnits(250, USD));
            placer.commit();
            ProjectionRebuilds.Outcome outcome = approval.get(30, TimeUnit.SECONDS);
            assertThat(outcome.rebuild().status()).isEqualTo(ProjectionRebuildStatus.APPLIED);
            assertThat(outcome.rebuild().approval().orElseThrow().after().holdsMinor())
                    .as("the approval derived after the placer committed: the hold is among the ACTIVE holds").isEqualTo(250);
        } finally {
            pool.shutdownNow();
        }
        assertProjectionIsTheJournal(wallet.id());
        assertThat(rowOf(wallet.id()).orElseThrow().holdsMinor()).isEqualTo(250);
    }

    @Test
    @Order(5)
    @DisplayName("two approvers of one rebuild serialise on its row: one applies it, the other is refused RebuildNotOpen and"
            + " writes nothing")
    void twoApproversSerialise() throws Exception {
        LedgerAccount wallet = postedWallet(5_000, 1_200);
        drift(wallet.id(), 777);
        ProjectionRebuildId id = proposed(wallet.id());
        Paused paused = new Paused(real);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ProjectionRebuilds.Outcome> first =
                    pool.submit(() -> committedAs(APPROVER, uow -> rebuilds(paused).approve(uow, id, key())));
            assertThat(paused.derived.await(30, TimeUnit.SECONDS)).isTrue();
            Future<ProjectionRebuilds.Outcome> second =
                    pool.submit(() -> committedAs(THIRD, uow -> rebuilds(real).approve(uow, id, key())));
            awaitWaiters(paused.pid, second);
            paused.release.countDown();
            assertThat(first.get(30, TimeUnit.SECONDS).rebuild().status()).isEqualTo(ProjectionRebuildStatus.APPLIED);
            assertThatThrownBy(() -> second.get(30, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(RebuildNotOpenException.class);
        } finally {
            paused.release.countDown();
            pool.shutdownNow();
        }
        assertProjectionIsTheJournal(wallet.id());
    }

    @Test
    @Order(6)
    @DisplayName("a row a bypass removed is inserted from the journal, the record saying none was found")
    void aMissingRowIsInserted() throws Exception {
        LedgerAccount wallet = postedWallet(5_000, 1_200);
        try (Connection migrator = DatabaseRoles.migrator();
                PreparedStatement delete = migrator.prepareStatement(
                        "DELETE FROM ledger.account_balance WHERE ledger_account_id = ?")) {
            delete.setObject(1, wallet.id().value());
            assertThat(delete.executeUpdate()).isEqualTo(1);
        }
        ProjectionRebuildId id = proposed(wallet.id());
        ProjectionRebuilds.Outcome applied = committedAs(APPROVER, uow -> rebuilds(real).approve(uow, id, key()));
        assertThat(applied.rebuild().observedRow()).isEmpty();
        assertThat(applied.rebuild().approval().orElseThrow().before()).isEmpty();
        assertProjectionIsTheJournal(wallet.id());
    }

    @Test
    @Order(7)
    @DisplayName("the schema: four eyes and one standing rebuild for every writer; for the application role the payload,"
            + " a delete and a journal line privilege-refused (42501); for the migrator, which holds every privilege, the"
            + " payload frozen, a delete and a truncate refused by the triggers (23514)")
    void theSchemaHoldsForEveryWriter() throws Exception {
        LedgerAccount wallet = postedWallet(1_000, 100);
        UUID first = UUID.randomUUID();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            insertRaw(app, first, wallet.id());
            assertThat(sqlState(() -> {
                insertRaw(app, UUID.randomUUID(), wallet.id());
                return null;
            })).as("one standing per account")
                    .isEqualTo("23505");
            app.rollback();
        }
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            insertRaw(app, first, wallet.id());
            try (PreparedStatement self = app.prepareStatement("UPDATE ledger.projection_rebuild SET status = 'APPLIED',"
                    + " decided_by = proposed_by, decided_at = proposed_at, before_row = false, after_posted_minor = 1,"
                    + " after_holds_minor = 0, after_seq = 1 WHERE id = ?")) {
                self.setObject(1, first);
                assertThat(sqlState(self::executeUpdate)).as("four eyes for every writer").isEqualTo("23514");
            }
            app.rollback();
        }
        for (String denied : List.of("UPDATE ledger.projection_rebuild SET reason = 'x' WHERE id = ?",
                "DELETE FROM ledger.projection_rebuild WHERE id = ?",
                "UPDATE ledger.journal_line SET amount_minor = amount_minor WHERE entry_id = ?")) {
            try (Connection app = DatabaseRoles.application(); PreparedStatement statement = app.prepareStatement(denied)) {
                statement.setObject(1, UUID.randomUUID());
                assertThat(sqlState(statement::executeUpdate)).as(denied).isEqualTo("42501");
            }
        }
        // The triggers themselves, reached only by a writer the grants do not stop (the fix round's M-5): the migrator,
        // on a row it inserted, inside a transaction it rolls back. Each refusal names its trigger, so a CHECK's 23514
        // cannot stand in for it.
        for (String frozen : List.of("UPDATE ledger.projection_rebuild SET reason = 'rewritten' WHERE id = ?",
                "UPDATE ledger.projection_rebuild SET observed_derived_posted_minor = 2 WHERE id = ?",
                "DELETE FROM ledger.projection_rebuild WHERE id = ?")) {
            try (Connection migrator = DatabaseRoles.migrator()) {
                migrator.setAutoCommit(false);
                UUID row = UUID.randomUUID();
                insertRaw(migrator, row, wallet.id());
                try (PreparedStatement statement = migrator.prepareStatement(frozen)) {
                    statement.setObject(1, row);
                    assertThat(refusal(statement::executeUpdate)).as(frozen).startsWith("23514 ")
                            .contains(frozen.startsWith("DELETE") ? "ledger_projection_rebuild_is_kept"
                                    : "ledger_projection_rebuild_is_frozen");
                }
                migrator.rollback();
            }
        }
        try (Connection migrator = DatabaseRoles.migrator();
                PreparedStatement truncate = migrator.prepareStatement("TRUNCATE ledger.projection_rebuild")) {
            migrator.setAutoCommit(false);
            assertThat(refusal(truncate::executeUpdate)).startsWith("23514 ").contains("ledger_projection_rebuild_is_kept");
            migrator.rollback();
        }
    }

    @Test
    @Order(8)
    @DisplayName("a refused self-approval leaves the rebuild PROPOSED and nothing of the act, and its FAILED record - written"
            + " in a transaction of its own after the rollback, as the desk does - is in platform.audit_record once (the"
            + " fix round's I-1)")
    void aRefusedSelfApprovalIsRecordedOnce() throws Exception {
        LedgerAccount wallet = postedWallet(5_000, 1_200);
        drift(wallet.id(), 777);
        ProjectionRebuildId id = proposed(wallet.id());
        com.finapp.ledger.RebuildSelfApprovalRefusedException refusal = org.assertj.core.api.Assertions.catchThrowableOfType(
                com.finapp.ledger.RebuildSelfApprovalRefusedException.class,
                () -> committedAs(PROPOSER, uow -> rebuilds(real).approve(uow, id, key())));
        assertThat(refusal).isNotNull();
        committedAs(PROPOSER, uow -> {
            rebuilds(real).recordRefusal(uow, refusal);
            return null;
        });
        String target = id.value().toString();
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'ledger.SelfApprovalRefused' AND"
                + " outcome = 'FAILED' AND actor_id = 'rebuild-proposer' AND target_type = 'projection_rebuild' AND"
                + " target_id = ?", target)).as("recorded FAILED, once").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'ledger.ProjectionRebuilt' AND"
                + " target_id = ?", target)).as("the refused act's own record rolled back with it").isZero();
        assertThat(count("SELECT count(*) FROM ledger.projection_rebuild WHERE status = 'PROPOSED' AND id = ?::uuid", target))
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------ fixtures

    /** The real rows, paused after the approval's derivation until released - its row and key share held. */
    private static final class Paused implements ProjectionRebuildRows<Connection> {
        private final ProjectionRebuildRows<Connection> real;
        final CountDownLatch derived = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile int pid;

        Paused(ProjectionRebuildRows<Connection> real) {
            this.real = real;
        }

        @Override
        public Observation observe(Connection unitOfWork, LedgerAccountId account) {
            return real.observe(unitOfWork, account);
        }

        @Override
        public boolean shareAccount(Connection unitOfWork, LedgerAccountId account) {
            return real.shareAccount(unitOfWork, account);
        }

        @Override
        public Optional<Row> lockRow(Connection unitOfWork, LedgerAccountId account) {
            Optional<Row> row = real.lockRow(unitOfWork, account);
            try {
                pid = pid(unitOfWork);
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
            return row;
        }

        @Override
        public Derived derive(Connection unitOfWork, LedgerAccountId account) {
            Derived reading = real.derive(unitOfWork, account);
            derived.countDown();
            try {
                if (!release.await(60, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("never released");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return reading;
        }

        @Override
        public boolean insertRow(Connection unitOfWork, LedgerAccountId account, Derived derived, Instant at) {
            return real.insertRow(unitOfWork, account, derived, at);
        }

        @Override
        public void writeRow(Connection unitOfWork, LedgerAccountId account, Derived derived, Instant at) {
            real.writeRow(unitOfWork, account, derived, at);
        }
    }

    @FunctionalInterface
    private interface Work<T> {
        T in(Connection unitOfWork) throws Exception;
    }

    @FunctionalInterface
    private interface Statement {
        Object run() throws SQLException;
    }

    private <T> T committedAs(Actor actor, Work<T> work) throws Exception {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope who = actor.type() == ActorType.SYSTEM ? SecurityContext.enterSystem()
                        : SecurityContext.enter(actor);
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            try {
                T result = work.in(app);
                app.commit();
                return result;
            } catch (Exception failed) {
                app.rollback();
                throw failed;
            }
        }
    }

    private ProjectionRebuildId proposed(LedgerAccountId wallet) throws Exception {
        return committedAs(PROPOSER, uow -> rebuilds(real).propose(uow, wallet, "the verification found it drifting", key()))
                .rebuild().id();
    }

    private ProjectionRebuilds rebuilds(ProjectionRebuildRows<Connection> rows) {
        return new ProjectionRebuilds(new IdempotentExecutor(new JdbcIdempotencyRecordStore(), CLOCK, Duration.ofDays(1),
                Duration.ofMinutes(5)), new JdbcProjectionRebuildStore(), rows, new JdbcAuditWriter(), IDS, CLOCK);
    }

    private static String key() {
        return "rebuild-" + IDS.next();
    }

    /** A fresh wallet with two committed credits against the shared operational account. */
    private LedgerAccount postedWallet(long first, long second) throws Exception {
        return committedAs(Actor.SYSTEM, uow -> {
            LedgerAccount wallet = accounts.createOrConverge(uow, LedgerAccount.owned(IDS, CLOCK, AccountType.LIABILITY,
                    AccountPurpose.CUSTOMER_WALLET, USD, IDS.next())).account();
            service().post(uow, entry(clearing(uow), Direction.DEBIT, wallet, Direction.CREDIT, first));
            service().post(uow, entry(clearing(uow), Direction.DEBIT, wallet, Direction.CREDIT, second));
            return wallet;
        });
    }

    /** A raw writer's drift: the row's posted figure moved with no posting - the application role's V006 grant. */
    private static void drift(LedgerAccountId wallet, long minor) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement update = app.prepareStatement(
                        "UPDATE ledger.account_balance SET posted_minor = posted_minor - ? WHERE ledger_account_id = ?")) {
            update.setLong(1, minor);
            update.setObject(2, wallet.value());
            assertThat(update.executeUpdate()).isEqualTo(1);
        }
    }

    private LedgerAccount clearing(Connection uow) {
        return accounts.findOperational(uow, AccountPurpose.CHARGEBACK_RECOVERABLE, USD).orElseThrow();
    }

    private static PostingCommand entry(LedgerAccount first, Direction firstSide, LedgerAccount second, Direction secondSide,
            long minorUnits) {
        return new PostingCommand("rebuild-" + IDS.next(), DATE, DATE, "rebuild-probe", List.of(
                new JournalLine(first.id(), firstSide, Money.ofMinorUnits(minorUnits, USD)),
                new JournalLine(second.id(), secondSide, Money.ofMinorUnits(minorUnits, USD))));
    }

    private PostingService service() {
        return new PostingService(new IdempotentExecutor(new JdbcIdempotencyRecordStore(), CLOCK, Duration.ofDays(1),
                Duration.ofMinutes(5)), new JdbcJournalEntryStore(IDS), new JdbcAuditWriter(), new JdbcOutboxWriter(),
                new JdbcBalanceProjection(), IDS, CLOCK, PostingObserver.NONE);
    }

    private HoldService holdService() {
        return new HoldService(accounts, new JdbcBalanceDerivation(), new JdbcHoldStore(), new JdbcBalanceProjection(),
                new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, CLOCK);
    }

    /** The row, the derivation, the applied-entry count and the ACTIVE holds agree - the verification's whole definition. */
    private void assertProjectionIsTheJournal(LedgerAccountId wallet) throws SQLException {
        try (Connection app = DatabaseRoles.application(); CorrelationContext.Scope flow = flow()) {
            ProjectionFigures row = rowOf(wallet).orElseThrow();
            assertThat(row.postedMinor()).as("posted is the journal's")
                    .isEqualTo(derivation.derive(app, wallet, AsOf.latest()).settled().minorUnits());
            assertThat(row.appliedEntries()).as("the watermark is the applied-entry count").isEqualTo(entriesOf(wallet));
            assertThat(verification.verdictOf(app, wallet)).isEqualTo(ProjectionVerification.Verdict.CLEAN);
        }
    }

    private static Optional<ProjectionFigures> rowOf(LedgerAccountId wallet) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement("SELECT posted_minor, holds_minor, last_entry_seq FROM"
                        + " ledger.account_balance WHERE ledger_account_id = ?")) {
            select.setObject(1, wallet.value());
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(new ProjectionFigures(row.getLong(1), row.getLong(2), row.getLong(3)))
                        : Optional.empty();
            }
        }
    }

    private static long entriesOf(LedgerAccountId wallet) throws SQLException {
        return single("SELECT COUNT(DISTINCT entry_id) FROM ledger.journal_line WHERE ledger_account_id = ?", wallet);
    }

    private static long linesOf(LedgerAccountId wallet) throws SQLException {
        return single("SELECT COUNT(*) FROM ledger.journal_line WHERE ledger_account_id = ?", wallet);
    }

    /** Every line of the account as text, in a stable order - what "not one line moved" compares. */
    private static String journalOf(LedgerAccountId wallet) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement("SELECT string_agg(id::text || ':' || entry_id::text || ':'"
                        + " || direction || ':' || amount_minor || ':' || currency || ':' || scale, ',' ORDER BY id)"
                        + " FROM ledger.journal_line WHERE ledger_account_id = ?")) {
            select.setObject(1, wallet.value());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getString(1);
            }
        }
    }

    private static long single(String sql, LedgerAccountId wallet) throws SQLException {
        try (Connection app = DatabaseRoles.application(); PreparedStatement select = app.prepareStatement(sql)) {
            select.setObject(1, wallet.value());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static void insertRaw(Connection app, UUID id, LedgerAccountId wallet) throws SQLException {
        try (PreparedStatement insert = app.prepareStatement("INSERT INTO ledger.projection_rebuild (id, ledger_account_id,"
                + " currency, scale, status, reason, proposed_by, proposed_at, observed_row, observed_derived_posted_minor,"
                + " observed_derived_holds_minor, observed_derived_seq) VALUES (?, ?, 'USD', 2, 'PROPOSED', 'raw', 'raw-writer',"
                + " now(), false, 1, 0, 1)")) {
            insert.setObject(1, id);
            insert.setObject(2, wallet.value());
            insert.executeUpdate();
        }
    }

    private static String sqlState(Statement statement) {
        try {
            statement.run();
            return "none";
        } catch (SQLException refused) {
            return refused.getSQLState();
        }
    }

    /** The refusal's state and message - so a trigger's 23514 is told from a CHECK's. */
    private static String refusal(Statement statement) {
        try {
            statement.run();
            return "none";
        } catch (SQLException refused) {
            return refused.getSQLState() + " " + refused.getMessage();
        }
    }

    private static long count(String sql, String parameter) throws SQLException {
        try (Connection app = DatabaseRoles.application(); PreparedStatement select = app.prepareStatement(sql)) {
            select.setString(1, parameter);
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    /**
     * A session waiting on a lock behind {@code blocker} - awaited 30 s, as the application role (the
     * {@code BnplCollectionLedgerDatabaseTest} reasoning: only it sees its own sessions' wait events).
     */
    private static void awaitWaiters(int blocker, Future<?> racer) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (racer.isDone()) {
                throw new AssertionError("the racer finished while the holder held its lock: it never waited - "
                        + racer.get());
            }
            try (Connection observer = DatabaseRoles.application();
                    PreparedStatement select = observer.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE ?"
                            + " = ANY (pg_blocking_pids(pid)) AND state = 'active' AND wait_event_type = 'Lock'")) {
                select.setInt(1, blocker);
                try (ResultSet row = select.executeQuery()) {
                    row.next();
                    if (row.getLong(1) >= 1) {
                        return;
                    }
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no session waiting on a lock behind session " + blocker + " within 30 s");
    }

    private static int pid(Connection connection) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT pg_backend_pid()");
                ResultSet row = select.executeQuery()) {
            row.next();
            return row.getInt(1);
        }
    }

    private static CorrelationContext.Scope flow() {
        return CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
    }
}
