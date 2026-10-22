package com.finapp.bnpl;

import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.count;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.pid;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.refused;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.scalar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PurposeBalances;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-acc against a real database (`P12-TSK-017`; {@code bnpl V009}; scenario 18; {@code INV-LND-02}, {@code INV-LND-10} by
 * analogy, {@code INV-IDEM-02}): {@link BnplAccruals} over bnpl's own JDBC stores, on a database clock moved by
 * {@link BnplClockFrame}, accrues each elapsed date of a pay-in-N obligation once - ten sweepers or one, a restart mid-run,
 * a ±5 s skew at midnight - and {@code bnpl V009}'s ranks hold for every writer: once per date, oldest first, never early,
 * an instalment period of the latest schedule version, the agreement in force and its engine, an entry exactly when the
 * date posts something, pay-in-4 never. The ledger is {@link BnplBillingDatabaseTest}'s in-memory book behind one lock -
 * the real postings are {@code app}'s - so the lock assertion here is that T-acc's transaction holds no lock outside
 * bnpl's own relations and that a step on the obligation waits on B4 alone; the real-ledger {@code pg_locks} half (the
 * income row never held across a wallet wait) was owed to the first T-svc step that takes a wallet, `P12-TSK-019`'s
 * collection, which wrote it over the real ledger: {@code app}'s
 * {@code BnplCollectionLedgerDatabaseTest#aCollectionHoldsNoIncomeRowWhileItWaitsOnTheWallet}.
 *
 * <p>Written by `P12-TSK-017` and <strong>owed a first run</strong> (the owner's no-{@code databaseTest} instruction).
 */
@Tag("database")
@DisplayName("pay-in-N interest accrual against bnpl V009's ranks (P12-TSK-017)")
class BnplAccrualDatabaseTest {

    private static final CurrencyCode EUR = BnplTermsFixtures.EUR;
    private static final JdbcBnplAccrualStore ACCRUALS = new JdbcBnplAccrualStore();
    private static final JdbcBnplServicingStore SERVICING = new JdbcBnplServicingStore();
    private static final JdbcBnplPurchaseStore PURCHASES = new JdbcBnplPurchaseStore();
    private static final JdbcBnplTermsStore TERMS_STORE = new JdbcBnplTermsStore();
    private static final Duration RETRY = Duration.ofMinutes(15);

    @BeforeAll
    static void termsAndClock() {
        BnplBillingDatabaseTest.termsAndClock();
    }

    // ------------------------------------------------------------------ scenario 18

    @Test
    @DisplayName("scenario 18: a restart mid-run writes nothing; ten sweepers, then a second run - one row per (obligation,"
            + " date) and one entry per posted date")
    void accruesOncePerDate() throws Exception {
        BnplBillingDatabaseTest.World world = new BnplBillingDatabaseTest.World();
        BnplBillingDatabaseTest.Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_3_MONTHS);
        BnplAccruals accruals = accruals(world.book);
        Duration frame = world.frameOn(bought.purchaseDate().plusDays(6));
        // The restart: the call ran, the process died before commit.
        assertThatThrownBy(() -> BnplClockFrame.inOneTransaction(frame, uow -> {
            accruals.accrue(uow, bought.obligation());
            throw new IllegalStateException("the instance died before commit");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(rows(bought)).isZero();

        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            List<Future<Integer>> sweepers = new ArrayList<>();
            for (int sweeper = 0; sweeper < 10; sweeper++) {
                sweepers.add(pool.submit(() -> {
                    BnplAccruals.Outcome outcome = BnplClockFrame.inOneTransaction(frame,
                            uow -> accruals.accrue(uow, bought.obligation()));
                    return outcome.posted() + outcome.zero();
                }));
            }
            int accrued = 0;
            for (Future<Integer> sweeper : sweepers) {
                accrued += sweeper.get(60, TimeUnit.SECONDS);
            }
            assertThat(accrued).isEqualTo(6);
        } finally {
            pool.shutdownNow();
        }
        BnplClockFrame.inOneTransaction(frame, uow -> accruals.accrue(uow, bought.obligation()));
        assertThat(rows(bought)).isEqualTo(6);
        assertThat(count("SELECT count(DISTINCT accrual_date) FROM bnpl.bnpl_interest_accrual WHERE obligation_id = '"
                + bought.id() + "'")).isEqualTo(6);
        assertThat(world.book.keys()).filteredOn(key -> key.startsWith(BnplAccruals.KEY_PREFIX + bought.id()))
                .doesNotHaveDuplicates()
                .hasSize((int) count("SELECT count(*) FROM bnpl.bnpl_interest_accrual WHERE obligation_id = '"
                        + bought.id() + "' AND journal_entry_id IS NOT NULL"));
        // Beneath B4 and the trigger: the UNIQUE (obligation_id, accrual_date) itself refuses a second row for a date.
        try (Connection bootstrap = DatabaseRoles.bootstrap()) {
            try (Statement replica = bootstrap.createStatement()) {
                replica.execute("SET session_replication_role = replica");
            }
            bootstrap.setAutoCommit(false);
            refused(bootstrap, "INSERT INTO bnpl.bnpl_interest_accrual SELECT gen_random_uuid(), obligation_id,"
                    + " accrual_date, period_start, period_end, currency, scale, base_minor, cumulative_exact, 0,"
                    + " agreement_id, agreement_version, accrual_engine, NULL, clock_timestamp(), correlation_id FROM"
                    + " bnpl.bnpl_interest_accrual WHERE obligation_id = '" + bought.id() + "'", "23505");
        }
    }

    @Test
    @DisplayName("the database clock at midnight, ±5 s: five seconds before nothing has elapsed and a raw writer of the"
            + " date is refused; five seconds after the date accrues once")
    void skewNeverAccruesEarlyOrTwice() throws SQLException {
        BnplBillingDatabaseTest.World world = new BnplBillingDatabaseTest.World();
        BnplBillingDatabaseTest.Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_3_MONTHS);
        BnplAccruals accruals = accruals(world.book);
        Instant midnight = bought.purchaseDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Duration before = BnplClockFrame.frameTo(midnight.minusSeconds(5));
        assertThat(BnplClockFrame.inOneTransaction(before, uow -> accruals.accrue(uow, bought.obligation())).posted())
                .isZero();
        assertThat(rows(bought)).isZero();
        try (Connection migrator = BnplClockFrame.framed(DatabaseRoles.migrator(), before)) {
            refused(migrator, rawAccrual(bought, bought.purchaseDate(), bought.purchaseDate(),
                    bought.schedule().instalments().get(0).dueDate()), "P0001",
                    "a date accrues only once it has ended on the database clock");
        }
        Duration after = BnplClockFrame.frameTo(midnight.plusSeconds(5));
        assertThat(BnplClockFrame.inOneTransaction(after, uow -> accruals.accrue(uow, bought.obligation())).posted())
                .isEqualTo(1);
        assertThat(BnplClockFrame.inOneTransaction(after, uow -> accruals.accrue(uow, bought.obligation())).posted())
                .isZero();
        assertThat(scalar("SELECT string_agg(accrual_date::text, ',') FROM bnpl.bnpl_interest_accrual WHERE"
                + " obligation_id = '" + bought.id() + "'")).isEqualTo(bought.purchaseDate().toString());
    }

    @Test
    @DisplayName("the scheduler down three days: the missed dates are caught up oldest first, born in date order, none"
            + " skipped")
    void threeMissedDaysAreCaughtUpOldestFirst() throws SQLException {
        BnplBillingDatabaseTest.World world = new BnplBillingDatabaseTest.World();
        BnplBillingDatabaseTest.Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_3_MONTHS);
        BnplAccruals accruals = accruals(world.book);
        BnplClockFrame.inOneTransaction(world.frameOn(bought.purchaseDate().plusDays(1)),
                uow -> accruals.accrue(uow, bought.obligation()));
        BnplClockFrame.inOneTransaction(world.frameOn(bought.purchaseDate().plusDays(5)),
                uow -> accruals.accrue(uow, bought.obligation()));
        assertThat(scalar("SELECT string_agg(accrual_date::text, ',' ORDER BY created_at, accrual_date) FROM"
                + " bnpl.bnpl_interest_accrual WHERE obligation_id = '" + bought.id() + "'")).isEqualTo(
                        String.join(",", bought.purchaseDate().toString(), bought.purchaseDate().plusDays(1).toString(),
                                bought.purchaseDate().plusDays(2).toString(), bought.purchaseDate().plusDays(3).toString(),
                                bought.purchaseDate().plusDays(4).toString()));
    }

    @Test
    @DisplayName("the base is derived from postings: a billing moves principal to due and the base stays the gross; the"
            + " rows never name the projection's reduced principal")
    void theBaseIsDerivedFromPostingsNotTheProjection() throws SQLException {
        BnplBillingDatabaseTest.World world = new BnplBillingDatabaseTest.World();
        BnplBillingDatabaseTest.Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_3_MONTHS);
        BnplAccruals accruals = accruals(world.book);
        LocalDate d1 = bought.schedule().instalments().get(0).dueDate();
        BnplClockFrame.inOneTransaction(world.frameOn(d1), uow -> accruals.accrue(uow, bought.obligation()));
        assertThat(world.serviceOn(d1, bought.obligation()).billed()).isEqualTo(1);
        BnplClockFrame.inOneTransaction(world.frameOn(d1.plusDays(4)), uow -> accruals.accrue(uow, bought.obligation()));
        assertThat(scalar("SELECT string_agg(DISTINCT base_minor::text, ',') FROM bnpl.bnpl_interest_accrual WHERE"
                + " obligation_id = '" + bought.id() + "'")).isEqualTo("60000");
    }

    @Test
    @DisplayName("an entry exactly when the date posts something: a zero day with an entry, or a posted day without one,"
            + " is refused (23514)")
    void zeroDaysWriteARowAndNoEntry() throws SQLException {
        BnplBillingDatabaseTest.World world = new BnplBillingDatabaseTest.World();
        BnplBillingDatabaseTest.Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_3_MONTHS);
        LocalDate first = bought.schedule().instalments().get(0).dueDate();
        try (Connection migrator = BnplClockFrame.framed(DatabaseRoles.migrator(),
                world.frameOn(bought.purchaseDate().plusDays(2)))) {
            refused(migrator, rawAccrual(bought, bought.purchaseDate(), bought.purchaseDate(), first, 0,
                    "gen_random_uuid()"), "23514");
            refused(migrator, rawAccrual(bought, bought.purchaseDate(), bought.purchaseDate(), first, 33, "NULL"),
                    "23514");
        }
    }

    @Test
    @DisplayName("a pay-in-4 obligation never accrues: T-acc answers NOT_ACCRUING and a raw row is refused for every"
            + " writer")
    void aP4ObligationNeverAccrues() throws SQLException {
        BnplBillingDatabaseTest.World world = new BnplBillingDatabaseTest.World();
        BnplBillingDatabaseTest.Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        BnplAccruals accruals = accruals(world.book);
        Duration frame = world.frameOn(bought.purchaseDate().plusDays(5));
        assertThat(BnplClockFrame.inOneTransaction(frame, uow -> accruals.accrue(uow, bought.obligation())).kind())
                .isEqualTo(BnplAccruals.Outcome.Kind.NOT_ACCRUING);
        assertThat(rows(bought)).isZero();
        try (Connection migrator = BnplClockFrame.framed(DatabaseRoles.migrator(), frame)) {
            refused(migrator, rawAccrual(bought, bought.purchaseDate(), bought.purchaseDate(),
                    bought.purchaseDate().plusDays(14)), "P0001",
                    "interest accrues only on an ACTIVE pay-in-N obligation");
        }
    }

    @Test
    @DisplayName("nothing accrues at or after the final due date, and a raw row outside the latest schedule's instalment"
            + " periods or under another engine is refused (P0001)")
    void theSpanEndsAtTheFinalDueDate() throws SQLException {
        BnplBillingDatabaseTest.World world = new BnplBillingDatabaseTest.World();
        BnplBillingDatabaseTest.Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_3_MONTHS);
        List<BnplPurchaseStore.StoredInstalment> instalments = bought.schedule().instalments();
        LocalDate last = instalments.get(instalments.size() - 1).dueDate();
        BnplAccruals accruals = accruals(world.book);
        Duration frame = world.frameOn(last.plusDays(3));
        for (int call = 0; call < 3; call++) {
            BnplClockFrame.inOneTransaction(frame, uow -> accruals.accrue(uow, bought.obligation()));
        }
        assertThat(scalar("SELECT max(accrual_date)::text FROM bnpl.bnpl_interest_accrual WHERE obligation_id = '"
                + bought.id() + "'")).isEqualTo(last.minusDays(1).toString());
        try (Connection migrator = BnplClockFrame.framed(DatabaseRoles.migrator(), frame)) {
            refused(migrator, rawAccrual(bought, last, last, last.plusMonths(1)), "P0001",
                    "never at or after the final due date");
            // Under another engine: a date inside the last instalment's period - the engine's rank, ahead of every date
            // rank, is what refuses it (the 12 -> 13 transition gate, A6-F2: the display name's second half, untested).
            LocalDate before = instalments.get(instalments.size() - 2).dueDate();
            refused(migrator, rawAccrual(bought, last.minusDays(1), before, last, 33, "gen_random_uuid()")
                    .replace("'ACCRUAL_ENGINE_V1'", "'ACCRUAL_ENGINE_V2'"), "P0001",
                    "an accrual names its obligation's agreement version in force and the accrual engine it pins");
        }
    }

    // ------------------------------------------------------------------ the acts

    @Test
    @DisplayName("an act on a behind record writes nothing and answers BEHIND_ACCRUAL; after T-acc the step bills")
    void anActOnABehindRecordRunsTheAccrualFirst() throws SQLException {
        BnplBillingDatabaseTest.World world = new BnplBillingDatabaseTest.World();
        BnplBillingDatabaseTest.Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_3_MONTHS);
        BnplAccruals accruals = accruals(world.book);
        LocalDate d1 = bought.schedule().instalments().get(0).dueDate();
        assertThat(world.serviceOn(d1, bought.obligation()).kind()).isEqualTo(ServicingOutcome.Kind.BEHIND_ACCRUAL);
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_billing WHERE obligation_id = '" + bought.id() + "'")).isZero();
        assertThat(rows(bought)).as("T-svc never accrues").isZero();
        BnplClockFrame.inOneTransaction(world.frameOn(d1), uow -> accruals.accrue(uow, bought.obligation()));
        assertThat(world.serviceOn(d1, bought.obligation()).billed()).isEqualTo(1);
    }

    @Test
    @DisplayName("T-acc holds no lock outside bnpl's own relations - no ledger row, no wallet, no payable - and a step on"
            + " the obligation waits on B4 (bnpl_obligation) alone, then finds the record caught up")
    void theAccrualHoldsNoWalletAndNoIncomeRowIsHeldAcrossAWalletWait() throws Exception {
        BnplBillingDatabaseTest.World world = new BnplBillingDatabaseTest.World();
        BnplBillingDatabaseTest.Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_3_MONTHS);
        CountDownLatch posted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger accrualPid = new AtomicInteger();
        PausingBook pausing = new PausingBook(world.book, uow -> {
            try {
                accrualPid.set(pid(uow));
                posted.countDown();
                release.await(30, TimeUnit.SECONDS);
            } catch (SQLException | InterruptedException failure) {
                throw new IllegalStateException(failure);
            }
        });
        BnplAccruals accruals = accruals(pausing);
        LocalDate d1 = bought.schedule().instalments().get(0).dueDate();
        Duration frame = world.frameOn(d1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<BnplAccruals.Outcome> accruing = pool.submit(() -> BnplClockFrame.inOneTransaction(frame,
                    uow -> accruals.accrue(uow, bought.obligation())));
            assertThat(posted.await(30, TimeUnit.SECONDS)).isTrue();
            try (Connection observer = DatabaseRoles.application(); Statement statement = observer.createStatement();
                    ResultSet namespaces = statement.executeQuery("SELECT DISTINCT n.nspname FROM pg_locks l"
                            + " JOIN pg_class c ON c.oid = l.relation JOIN pg_namespace n ON n.oid = c.relnamespace"
                            + " WHERE l.pid = " + accrualPid.get())) {
                List<String> held = new ArrayList<>();
                while (namespaces.next()) {
                    held.add(namespaces.getString(1));
                }
                assertThat(held).contains("bnpl").doesNotContain("ledger", "accounts", "merchant", "payments");
            }
            Future<ServicingOutcome> stepping = pool.submit(() -> world.serviceAt(frame, bought.obligation()));
            assertThat(BnplAcceptanceDatabaseTest.awaitBlockedBy(accrualPid.get())).contains("bnpl_obligation");
            release.countDown();
            assertThat(accruing.get(30, TimeUnit.SECONDS).caughtUp()).isTrue();
            assertThat(stepping.get(30, TimeUnit.SECONDS).billed()).isEqualTo(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a failing obligation does not hold the page: its claim re-stamps its permit to the retry cadence, and"
            + " the next obligation on the page accrues")
    void aFailingObligationDoesNotHoldThePage() throws SQLException {
        BnplBillingDatabaseTest.World world = new BnplBillingDatabaseTest.World();
        BnplBillingDatabaseTest.Purchased failing = world.purchase(BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_3_MONTHS);
        BnplBillingDatabaseTest.Purchased healthy = world.purchase(BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_3_MONTHS);
        BnplAccruals accruals = accruals(world.book);
        Duration frame = world.frameOn(failing.purchaseDate().plusDays(3));
        List<BnplObligationId> page = BnplClockFrame.inOneTransaction(frame,
                uow -> world.servicing.claim(uow, 500, RETRY));
        assertThat(page).contains(failing.obligation(), healthy.obligation());
        for (BnplObligationId id : page) {
            try {
                if (id.equals(failing.obligation())) {
                    BnplClockFrame.inOneTransaction(frame, uow -> {
                        accruals.accrue(uow, id);
                        throw new IllegalStateException("the accrual failed");
                    });
                } else if (id.equals(healthy.obligation())) {
                    BnplClockFrame.inOneTransaction(frame, uow -> accruals.accrue(uow, id));
                }
            } catch (IllegalStateException failed) {
                // the schedule's loop: counted failed, the class name logged, the page continues
            }
        }
        assertThat(rows(failing)).isZero();
        assertThat(rows(healthy)).isEqualTo(3);
        assertThat(scalar("SELECT (next_servicing_at > clock_timestamp())::text FROM bnpl.bnpl_obligation WHERE id = '"
                + failing.id() + "'")).isEqualTo("true");
    }

    @Test
    @DisplayName("the accrual lag: an obligation behind reads the time since the end of its oldest unaccrued date")
    void theLagIsTheOldestUnaccruedDate() throws SQLException {
        BnplBillingDatabaseTest.World world = new BnplBillingDatabaseTest.World();
        BnplBillingDatabaseTest.Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_3_MONTHS);
        Optional<Duration> lag = BnplClockFrame.inOneTransaction(world.frameOn(bought.purchaseDate().plusDays(2)),
                ACCRUALS::oldestLag);
        assertThat(lag).isPresent();
        assertThat(lag.get()).isGreaterThanOrEqualTo(Duration.ofHours(36));
    }

    // ------------------------------------------------------------------ plumbing

    private static BnplAccruals accruals(BnplBook book) {
        return new BnplAccruals(SERVICING, ACCRUALS, PURCHASES, TERMS_STORE, book,
                new com.finapp.sharedkernel.id.IdGenerator(java.time.Clock.systemUTC(), new java.security.SecureRandom()));
    }

    private static long rows(BnplBillingDatabaseTest.Purchased bought) throws SQLException {
        return count("SELECT count(*) FROM bnpl.bnpl_interest_accrual WHERE obligation_id = '" + bought.id() + "'");
    }

    /** A raw accrual row of {@code date} in {@code [start, end)}, posting 33 minor units under a fresh entry. */
    private static String rawAccrual(BnplBillingDatabaseTest.Purchased bought, LocalDate date, LocalDate start,
            LocalDate end) {
        return rawAccrual(bought, date, start, end, 33, "gen_random_uuid()");
    }

    /** A raw accrual row posting {@code postedMinor} under {@code entry} (an SQL expression: a fresh id, or NULL). */
    private static String rawAccrual(BnplBillingDatabaseTest.Purchased bought, LocalDate date, LocalDate start,
            LocalDate end, long postedMinor, String entry) {
        return "INSERT INTO bnpl.bnpl_interest_accrual (id, obligation_id, accrual_date, period_start, period_end,"
                + " currency, scale, base_minor, cumulative_exact, posted_minor, agreement_id, agreement_version,"
                + " accrual_engine, journal_entry_id, created_at, correlation_id) VALUES (gen_random_uuid(), '"
                + bought.id() + "', DATE '" + date + "', DATE '" + start + "', DATE '" + end + "', 'EUR', 2, 60000,"
                + " 0.33, " + postedMinor + ", '" + bought.agreement().value() + "', 1, 'ACCRUAL_ENGINE_V1', " + entry
                + ", clock_timestamp(), 'raw')";
    }

    /** The suite's book, pausing T-acc at its first posting - the posting done, the transaction open. */
    private static final class PausingBook implements BnplBook {

        private final BnplBook delegate;
        private final java.util.function.Consumer<Connection> pause;
        private boolean paused;

        PausingBook(BnplBook delegate, java.util.function.Consumer<Connection> pause) {
            this.delegate = delegate;
            this.pause = pause;
        }

        @Override
        public BnplLedgerAccounts openAccounts(Connection unitOfWork, BnplObligationId obligation, CurrencyCode currency) {
            return delegate.openAccounts(unitOfWork, obligation, currency);
        }

        @Override
        public List<LedgerAccount> accountsOf(Connection unitOfWork, BnplObligationId obligation) {
            return delegate.accountsOf(unitOfWork, obligation);
        }

        @Override
        public LedgerAccountId operational(Connection unitOfWork, com.finapp.ledger.AccountPurpose purpose,
                CurrencyCode currency) {
            return delegate.operational(unitOfWork, purpose, currency);
        }

        @Override
        public Optional<LedgerAccountStatus> lockForUpdate(Connection unitOfWork, LedgerAccountId account) {
            return delegate.lockForUpdate(unitOfWork, account);
        }

        @Override
        public Money availableUnderLock(Connection unitOfWork, LedgerAccountId account) {
            return delegate.availableUnderLock(unitOfWork, account);
        }

        @Override
        public void lockBalancesInOrder(Connection unitOfWork, Collection<LedgerAccountId> accounts) {
            delegate.lockBalancesInOrder(unitOfWork, accounts);
        }

        @Override
        public JournalEntryId post(Connection unitOfWork, PostingCommand command) {
            JournalEntryId entry = delegate.post(unitOfWork, command);
            if (!paused && command.idempotencyKey().startsWith(BnplAccruals.KEY_PREFIX)) {
                paused = true;
                pause.accept(unitOfWork);
            }
            return entry;
        }

        @Override
        public Money balanceOf(Connection unitOfWork, LedgerAccount account) {
            return delegate.balanceOf(unitOfWork, account);
        }

        @Override
        public List<PurposeBalances.DatedLine> datedLinesOn(Connection unitOfWork, Set<LedgerAccountId> accounts,
                LocalDate valueDateThrough) {
            return delegate.datedLinesOn(unitOfWork, accounts, valueDateThrough);
        }

        @Override
        public com.finapp.ledger.JournalEntryId reverse(Connection unitOfWork,
                com.finapp.ledger.ReversalCommand command) {
            return delegate.reverse(unitOfWork, command);
        }

        @Override
        public List<com.finapp.ledger.JournalLine> entryLines(Connection unitOfWork,
                com.finapp.ledger.JournalEntryId entry) {
            return delegate.entryLines(unitOfWork, entry);
        }
    }
}
