package com.finapp.bnpl;

import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.count;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.correlation;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.customer;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.employee;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.inOneTransaction;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.pid;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.refused;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.scalar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.ledger.PostingCommand;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-svc's rows against a real database (`P12-TSK-015`; {@code bnpl V008}; {@code INV-BNPL-05}, {@code INV-LND-02} and
 * {@code -04} by analogy, {@code INV-IDEM-02}): {@link BnplServicing} over bnpl's own JDBC stores, on a database clock moved
 * to each due date by {@link BnplClockFrame}, bills each instalment once at its due date - ten sweepers or one - and
 * {@code bnpl V008}'s ranks hold for every writer: dated its due date and never early, oldest first, the interest exactly
 * the period's accruals (the owner-role accrual fixture), the principal rule and the remainder, the application's floor and
 * its next number, the permit-only edge, the step waiting on the obligation's lock (B4). The ledger is the in-memory book
 * of {@link BnplApplicationFakes} - the real postings, keys and balances are {@code app}'s; credit, checkout, merchant,
 * capital and the wallet scripted as {@link BnplAcceptanceDatabaseTest}'s.
 *
 * <p>Written by `P12-TSK-015` and <strong>owed a first run</strong> (the owner's no-{@code databaseTest} instruction).
 */
@Tag("database")
@DisplayName("instalment billing and the credit-balance application against bnpl V008's ranks (P12-TSK-015)")
class BnplBillingDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = BnplTermsFixtures.EUR;
    private static final JdbcBnplOfferStore OFFERS = new JdbcBnplOfferStore();
    private static final JdbcBnplPurchaseStore PURCHASES = new JdbcBnplPurchaseStore();
    private static final JdbcBnplServicingStore SERVICING = new JdbcBnplServicingStore();
    private static final JdbcBnplTermsStore TERMS_STORE = new JdbcBnplTermsStore();
    private static final Duration RETRY = Duration.ofMinutes(15);

    @BeforeAll
    static void termsAndClock() {
        BnplClockFrame.install();
        BnplTermsAdministration terms = new BnplTermsAdministration(TERMS_STORE, new JdbcAuditWriter(),
                new JdbcOutboxWriter(), IDS, CLOCK, BnplTermsFixtures.BOUNDS);
        for (BnplTerms wanted : List.of(BnplTermsFixtures.payIn4().build(), BnplTermsFixtures.payInN("0.1999").build())) {
            if (inOneTransaction(uow -> TERMS_STORE.active(uow, wanted.product())).isEmpty()) {
                BnplTermsAdministration.Proposed proposed = inOneTransaction(uow -> terms.propose(uow, wanted,
                        "the billing suite's terms", employee(), correlation()));
                inOneTransaction(uow -> terms.approve(uow, proposed.id(), employee(), "in force", correlation()));
            }
        }
    }

    // ------------------------------------------------------------------ billing

    @Test
    @DisplayName("on its due date an instalment is billed once: dated its due date, the business date stamped, a second"
            + " step that day bills nothing")
    void billedOnTheDueDateOnce() throws SQLException {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        LocalDate due = bought.purchaseDate().plusDays(14);
        assertThat(world.serviceOn(due, bought.obligation()).billed()).isEqualTo(1);
        assertThat(world.serviceOn(due, bought.obligation()).billed()).isZero();
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_billing WHERE obligation_id = '" + bought.id() + "'"))
                .isEqualTo(2);
        assertThat(scalar("SELECT value_date || '|' || business_date || '|' || principal_minor || '|' || schedule_version"
                + " FROM bnpl.bnpl_billing b JOIN bnpl.bnpl_instalment i ON i.id = b.instalment_id WHERE i.sequence = 2"
                + " AND b.obligation_id = '" + bought.id() + "'")).isEqualTo(due + "|" + due + "|15000|1");
    }

    @Test
    @DisplayName("ten sweepers - five claiming, five handed the id - bill each instalment once (B4, UNIQUE (instalment_id),"
            + " UNIQUE (obligation_id, value_date))")
    void tenSweepersBillOnce() throws Exception {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        Duration frame = world.frameOn(bought.purchaseDate().plusDays(43));
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            List<Future<Integer>> sweepers = new ArrayList<>();
            for (int sweeper = 0; sweeper < 10; sweeper++) {
                boolean claims = sweeper < 5;
                sweepers.add(pool.submit(() -> {
                    List<BnplObligationId> page = claims
                            ? BnplClockFrame.inOneTransaction(frame, uow -> world.servicing.claim(uow, 50, RETRY))
                            : List.of(bought.obligation());
                    int billed = 0;
                    for (BnplObligationId claimed : page) {
                        if (claimed.equals(bought.obligation())) {
                            billed += BnplClockFrame.inOneTransaction(frame,
                                    uow -> world.servicing.service(uow, claimed)).billed();
                        }
                    }
                    return billed;
                }));
            }
            int billed = 0;
            for (Future<Integer> sweeper : sweepers) {
                billed += sweeper.get(60, TimeUnit.SECONDS);
            }
            assertThat(billed).isEqualTo(3);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_billing WHERE obligation_id = '" + bought.id() + "'"))
                .isEqualTo(4);
        assertThat(world.book.keys()).filteredOn(key -> key.startsWith(BnplServicing.BILLING_KEY_PREFIX))
                .doesNotHaveDuplicates().hasSize(4);
    }

    @Test
    @DisplayName("pay-in-4 bills on D1 + 14, + 28 and + 42 - nothing the day before each")
    void p4BillsEveryFourteenDays() throws SQLException {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        for (int days : List.of(14, 28, 42)) {
            assertThat(world.serviceOn(bought.purchaseDate().plusDays(days - 1), bought.obligation()).billed())
                    .as("the day before D1 + " + days).isZero();
            assertThat(world.serviceOn(bought.purchaseDate().plusDays(days), bought.obligation()).billed())
                    .as("D1 + " + days).isEqualTo(1);
        }
        assertThat(scalar("SELECT string_agg(value_date::text, ',' ORDER BY value_date) FROM bnpl.bnpl_billing"
                + " WHERE obligation_id = '" + bought.id() + "'")).isEqualTo(bought.purchaseDate() + ","
                        + bought.purchaseDate().plusDays(14) + "," + bought.purchaseDate().plusDays(28) + ","
                        + bought.purchaseDate().plusDays(42));
    }

    @Test
    @DisplayName("instalment 1, billed in T-pur, is never billed again: the step on the purchase's day bills nothing, and a"
            + " raw second billing of it is refused (23505)")
    void instalmentOneIsNeverBilledTwice() throws SQLException {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        assertThat(world.serviceOn(bought.purchaseDate(), bought.obligation()).billed()).isZero();
        try (Connection bootstrap = DatabaseRoles.bootstrap()) {
            try (Statement replica = bootstrap.createStatement()) {
                replica.execute("SET session_replication_role = replica");
            }
            bootstrap.setAutoCommit(false);
            refused(bootstrap, "INSERT INTO bnpl.bnpl_billing (id, instalment_id, obligation_id, value_date, currency,"
                    + " scale, principal_minor, interest_minor, journal_entry_id, billed_at) SELECT gen_random_uuid(),"
                    + " instalment_id, obligation_id, value_date + 1, currency, scale, principal_minor, interest_minor,"
                    + " gen_random_uuid(), billed_at FROM bnpl.bnpl_billing WHERE obligation_id = '" + bought.id() + "'",
                    "23505");
        }
    }

    @Test
    @DisplayName("pay-in-N bills the interest the period actually accrued - Σ the owner-role accrual fixture, never the"
            + " projection - and a raw billing of the projection is refused")
    void pnBillsTheAccruedInterestNotTheProjection() throws SQLException {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_3_MONTHS);
        BnplPurchaseStore.StoredInstalment first = bought.schedule().instalments().get(0);
        world.accrueThrough(bought, first.dueDate().minusDays(1), 30);
        long accrued = 30 * (first.dueDate().toEpochDay() - bought.purchaseDate().toEpochDay());
        assertThat(accrued).isNotEqualTo(first.interest().minorUnits());
        assertThat(world.serviceOn(first.dueDate(), bought.obligation()).billed()).isEqualTo(1);
        assertThat(scalar("SELECT interest_minor || '|' || principal_minor FROM bnpl.bnpl_billing WHERE instalment_id = '"
                + first.id() + "'")).isEqualTo(accrued + "|" + (first.amount().minorUnits() - accrued));
    }

    @Test
    @DisplayName("billing waits for every accrual of the period: the eve unaccrued - BEHIND_ACCRUAL, nothing written; a"
            + " raw writer is refused")
    void billingWaitsForEveryAccrualOfThePeriod() throws SQLException {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_3_MONTHS);
        BnplPurchaseStore.StoredInstalment first = bought.schedule().instalments().get(0);
        world.accrueThrough(bought, first.dueDate().minusDays(2), 30);
        assertThat(world.serviceOn(first.dueDate(), bought.obligation()).kind())
                .isEqualTo(ServicingOutcome.Kind.BEHIND_ACCRUAL);
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_billing WHERE obligation_id = '" + bought.id() + "'")).isZero();
        BnplBilling raw = world.billingOf(bought, first, Money.ofMinorUnits(30 * 30, EUR));
        assertThatThrownBy(() -> BnplClockFrame.inOneTransaction(world.frameOn(first.dueDate()), uow -> {
            PURCHASES.insertBilling(uow, raw);
            return null;
        })).hasMessageContaining("P0001");
    }

    @Test
    @DisplayName("the last instalment bills the remainder: Σ billed principal = the gross; a raw last billing of anything"
            + " else is refused (INV-BNPL-05)")
    void theLastInstalmentBillsTheRemainder() throws SQLException {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        assertThat(world.serviceOn(bought.purchaseDate().plusDays(28), bought.obligation()).billed()).isEqualTo(2);
        BnplPurchaseStore.StoredInstalment last = bought.schedule().instalments().get(3);
        BnplBilling wrong = world.billingOf(bought, last, Money.zero(EUR), last.principal().plus(Money.ofMinorUnits(1, EUR)));
        assertThatThrownBy(() -> BnplClockFrame.inOneTransaction(world.frameOn(last.dueDate()), uow -> {
            PURCHASES.insertBilling(uow, wrong);
            return null;
        })).hasMessageContaining("P0001");
        world.serviceOn(last.dueDate(), bought.obligation());
        assertThat(scalar("SELECT sum(principal_minor)::text FROM bnpl.bnpl_billing WHERE obligation_id = '" + bought.id()
                + "'")).isEqualTo("60000");
    }

    @Test
    @DisplayName("the scheduler down three due dates: each instalment billed at its own due date, on one business date")
    void threeMissedDueDatesBillAtTheirDueDates() throws SQLException {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        LocalDate today = bought.purchaseDate().plusDays(43);
        assertThat(world.serviceOn(today, bought.obligation()).billed()).isEqualTo(3);
        assertThat(scalar("SELECT string_agg(value_date || '@' || business_date, ',' ORDER BY value_date) FROM"
                + " bnpl.bnpl_billing WHERE obligation_id = '" + bought.id() + "' AND business_date IS NOT NULL"
                + " AND value_date > '" + bought.purchaseDate() + "'")).isEqualTo(
                        bought.purchaseDate().plusDays(14) + "@" + today + "," + bought.purchaseDate().plusDays(28) + "@"
                                + today + "," + bought.purchaseDate().plusDays(42) + "@" + today);
    }

    @Test
    @DisplayName("the database clock decides at midnight: five seconds before the due date bills nothing, five after bills")
    void skewNeverBillsEarly() throws SQLException {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        Instant midnight = bought.purchaseDate().plusDays(14).atStartOfDay(ZoneOffset.UTC).toInstant();
        assertThat(world.serviceAt(BnplClockFrame.frameTo(midnight.minusSeconds(5)), bought.obligation()).billed())
                .isZero();
        assertThat(world.serviceAt(BnplClockFrame.frameTo(midnight.plusSeconds(5)), bought.obligation()).billed())
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------ money held

    @Test
    @DisplayName("a credit balance pays the bill at its birth - one application per billing, its rows summing to it")
    void aCreditBalancePaysTheBill() throws SQLException {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        world.credit(bought, Money.ofMinorUnits(200_00, EUR));
        assertThat(world.serviceOn(bought.purchaseDate().plusDays(14), bought.obligation()).appliedAtBilling())
                .isEqualTo(1);
        assertThat(scalar("SELECT a.amount_minor || '|' || (a.billing_id IS NOT NULL) || '|' || sum(l.amount_minor)"
                + " FROM bnpl.bnpl_credit_balance_application a JOIN bnpl.bnpl_credit_balance_allocation l"
                + " ON l.application_id = a.id WHERE a.obligation_id = '" + bought.id() + "' GROUP BY a.id"))
                .isEqualTo("15000|true|15000");
    }

    @Test
    @DisplayName("held credit settles what is already past due: the next step's catch-up application, numbered 1 - never"
            + " left beside arrears (R2)")
    void heldCreditSettlesWhatIsAlreadyPastDue() throws SQLException {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        world.serviceOn(bought.purchaseDate().plusDays(14), bought.obligation());
        world.credit(bought, Money.ofMinorUnits(100_00, EUR));
        assertThat(world.serviceOn(bought.purchaseDate().plusDays(15), bought.obligation()).caughtUp()).isTrue();
        assertThat(scalar("SELECT catch_up_sequence || '|' || amount_minor FROM bnpl.bnpl_credit_balance_application"
                + " WHERE obligation_id = '" + bought.id() + "'")).isEqualTo("1|10000");
    }

    @Test
    @DisplayName("no application below the floor (R1): a clock stepped back across midnight values it at the latest"
            + " billing's date, and a raw writer below the floor is refused")
    void noApplicationBelowTheFloor() throws SQLException {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        LocalDate due = bought.purchaseDate().plusDays(14);
        world.serviceAt(BnplClockFrame.frameTo(due.atStartOfDay(ZoneOffset.UTC).toInstant().plusMillis(500)),
                bought.obligation());
        world.credit(bought, Money.ofMinorUnits(40_00, EUR));
        Duration justAfter = BnplClockFrame.frameTo(due.atStartOfDay(ZoneOffset.UTC).toInstant().plusMillis(500));
        BnplClockFrame.inOneTransaction(justAfter, uow -> {
            BnplClockFrame.stepBack(uow, Duration.ofSeconds(2));
            return world.servicing.service(uow, bought.obligation());
        });
        assertThat(scalar("SELECT value_date::text FROM bnpl.bnpl_credit_balance_application WHERE obligation_id = '"
                + bought.id() + "'")).isEqualTo(due.toString());
        try (Connection migrator = BnplClockFrame.framed(DatabaseRoles.migrator(), world.frameOn(due))) {
            refused(migrator, "INSERT INTO bnpl.bnpl_credit_balance_application (id, obligation_id, catch_up_sequence,"
                    + " currency, scale, amount_minor, agreement_id, agreement_version, allocation_engine, value_date,"
                    + " journal_entry_id, created_at, correlation_id) SELECT gen_random_uuid(), o.id, 2, 'EUR', 2, 1,"
                    + " o.agreement_id, 1, 'ALLOCATION_ENGINE_V1', DATE '" + due.minusDays(1) + "', gen_random_uuid(),"
                    + " clock_timestamp(), 'raw' FROM bnpl.bnpl_obligation o WHERE o.id = '" + bought.id() + "'",
                    "P0001");
        }
    }

    // ------------------------------------------------------------------ the sweep

    @Test
    @DisplayName("a failing obligation does not hold the page: its claim re-stamps its permit to the retry cadence, and"
            + " the next obligation on the page is serviced")
    void aFailingObligationDoesNotHoldThePage() throws SQLException {
        World world = new World();
        Purchased failing = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        Purchased healthy = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        Duration frame = world.frameOn(failing.purchaseDate().plusDays(14));
        List<BnplObligationId> page = BnplClockFrame.inOneTransaction(frame, uow -> world.servicing.claim(uow, 500, RETRY));
        assertThat(page).contains(failing.obligation(), healthy.obligation());
        int serviced = 0;
        for (BnplObligationId id : page) {
            try {
                if (id.equals(failing.obligation())) {
                    BnplClockFrame.inOneTransaction(frame, uow -> {
                        world.servicing.service(uow, id);
                        throw new IllegalStateException("the step failed");
                    });
                }
                if (id.equals(healthy.obligation())) {
                    serviced += BnplClockFrame.inOneTransaction(frame, uow -> world.servicing.service(uow, id)).billed();
                }
            } catch (IllegalStateException failed) {
                // the schedule's loop: the class name logged, the page continues
            }
        }
        assertThat(serviced).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_billing WHERE obligation_id = '" + failing.id() + "'"))
                .isEqualTo(1);
        assertThat(scalar("SELECT (next_servicing_at > clock_timestamp())::text FROM bnpl.bnpl_obligation WHERE id = '"
                + failing.id() + "'")).isEqualTo("true");
    }

    @Test
    @DisplayName("servicing takes the obligation's lock (B4): another transaction holding the row, the step waits on"
            + " bnpl_obligation and then bills once")
    void servicingTakesTheObligationLock() throws Exception {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        Duration frame = world.frameOn(bought.purchaseDate().plusDays(14));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = DatabaseRoles.application()) {
            holder.setAutoCommit(false);
            try (Statement lock = holder.createStatement()) {
                lock.execute("SELECT 1 FROM bnpl.bnpl_obligation WHERE id = '" + bought.id() + "' FOR UPDATE");
            }
            Future<ServicingOutcome> stepping = pool.submit(() -> BnplClockFrame.inOneTransaction(frame,
                    uow -> world.servicing.service(uow, bought.obligation())));
            assertThat(BnplAcceptanceDatabaseTest.awaitBlockedBy(pid(holder))).contains("bnpl_obligation");
            holder.rollback();
            assertThat(stepping.get(30, TimeUnit.SECONDS).billed()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("the obligation moves only its permit: the application role may not touch another column (42501), and a"
            + " status move is refused for every writer (P0001)")
    void theObligationMovesOnlyItsPermit() throws SQLException {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        String where = " WHERE id = '" + bought.id() + "'";
        try (Connection application = DatabaseRoles.application(); Connection migrator = DatabaseRoles.migrator()) {
            application.setAutoCommit(false);
            migrator.setAutoCommit(false);
            refused(application, "UPDATE bnpl.bnpl_obligation SET plan = plan" + where, "42501");
            refused(migrator, "UPDATE bnpl.bnpl_obligation SET status = 'CLOSED', closed_at = clock_timestamp(),"
                    + " closure_reason = 'REPAID'" + where, "P0001");
            refused(application, "INSERT INTO bnpl.bnpl_interest_accrual (id) VALUES (gen_random_uuid())", "42501");
        }
    }

    // ------------------------------------------------------------------ plumbing

    /** One purchased obligation - its id, purchase date and schedule version 1. */
    record Purchased(BnplObligationId obligation, LocalDate purchaseDate, BnplPurchaseStore.StoredSchedule schedule,
            BnplAgreementId agreement) {

        String id() {
            return obligation.value().toString();
        }
    }

    /** The suite's world: the scripted ports, the in-memory book behind one lock, T-pur and T-svc over the JDBC stores. */
    private static final class World {

        final BnplAcceptanceDatabaseTest.Scripted ports = new BnplAcceptanceDatabaseTest.Scripted();
        final BnplApplicationFakes fakes = new BnplApplicationFakes();
        final SharedBook book = new SharedBook(fakes.book);
        final BnplServicing servicing = new BnplServicing(SERVICING, PURCHASES, TERMS_STORE, book,
                new BnplClosures(new JdbcBnplRepaymentStore(), SERVICING, PURCHASES, ports, book, new JdbcOutboxWriter(),
                        IDS, CLOCK),
                new JdbcOutboxWriter(), IDS, CLOCK);
        private final BnplAcceptances acceptances = new BnplAcceptances(OFFERS, TERMS_STORE, PURCHASES, ports,
                (uow, party) -> true, ports, ports, ports, ports, book, new JdbcOutboxWriter(), new JdbcAuditWriter(),
                IDS, CLOCK);

        World() {
            fakes.book.external("payable", ports.payable.value());
            fakes.book.external("wallet", ports.wallet.value());
            fakes.book.funds.put(ports.wallet, Money.ofMinorUnits(1_000_000_00L, EUR));
        }

        Purchased purchase(BnplProduct product, BnplPlan plan) {
            BnplAcceptanceDatabaseTest.Offered offered = BnplAcceptanceDatabaseTest.offered(product, plan);
            ports.session = offered.session();
            ports.merchant = offered.merchant();
            String hash = inOneTransaction(uow -> OFFERS.terms(uow, offered.offer()).orElseThrow().termsSha256Hex());
            BnplAcceptances.Accepted accepted = inOneTransaction(uow -> acceptances.accept(uow, offered.offer(),
                    offered.party(), new BnplAcceptances.Acceptance(hash, new BnplAcceptanceEvidence(UUID.randomUUID(),
                            UUID.randomUUID(), "MULTI_FACTOR", Optional.empty(), Optional.empty())),
                    customer(offered.party()), Correlation.startingWith(correlation())));
            BnplPurchaseStore.StoredSchedule schedule = inOneTransaction(uow -> PURCHASES.schedule(uow,
                    accepted.obligation().id(), 1)).orElseThrow();
            return new Purchased(accepted.obligation().id(), accepted.purchase().businessDate(), schedule,
                    accepted.agreement().id());
        }

        /** The frame placing a transaction begun now at noon UTC of {@code date}. */
        Duration frameOn(LocalDate date) {
            return BnplClockFrame.frameTo(date.atTime(12, 0).toInstant(ZoneOffset.UTC));
        }

        ServicingOutcome serviceOn(LocalDate date, BnplObligationId id) {
            return serviceAt(frameOn(date), id);
        }

        ServicingOutcome serviceAt(Duration frame, BnplObligationId id) {
            return BnplClockFrame.inOneTransaction(frame, uow -> servicing.service(uow, id));
        }

        /** The owner-role accrual fixture: {@code perDayMinor} on every date from the purchase's through {@code last}. */
        void accrueThrough(Purchased bought, LocalDate last, long perDayMinor) throws SQLException {
            BnplPurchaseStore.StoredInstalment first = bought.schedule().instalments().get(0);
            try (Connection migrator = BnplClockFrame.framed(DatabaseRoles.migrator(), frameOn(last.plusDays(1)));
                    Statement insert = migrator.createStatement()) {
                long cumulative = 0;
                for (LocalDate day = bought.purchaseDate(); !day.isAfter(last); day = day.plusDays(1)) {
                    LocalDate start = day.isBefore(first.dueDate()) ? bought.purchaseDate() : first.dueDate();
                    LocalDate end = day.isBefore(first.dueDate()) ? first.dueDate()
                            : bought.schedule().instalments().get(1).dueDate();
                    cumulative = day.equals(start) ? perDayMinor : cumulative + perDayMinor;
                    insert.execute("INSERT INTO bnpl.bnpl_interest_accrual (id, obligation_id, accrual_date, period_start,"
                            + " period_end, currency, scale, base_minor, cumulative_exact, posted_minor, agreement_id,"
                            + " agreement_version, accrual_engine, journal_entry_id, created_at, correlation_id) VALUES"
                            + " (gen_random_uuid(), '" + bought.id() + "', DATE '" + day + "', DATE '" + start + "', DATE '"
                            + end + "', 'EUR', 2, 60000, " + cumulative + ", " + perDayMinor + ", '"
                            + bought.agreement().value() + "', 1, 'ACCRUAL_ENGINE_V1', gen_random_uuid(),"
                            + " clock_timestamp(), 'owner-role fixture')");
                    fakes.servicing.accrue(bought.obligation(), day, Money.ofMinorUnits(perDayMinor, EUR));
                }
                migrator.commit();
            }
        }

        /** The owner-role credit fixture: {@code amount} held for the customer on the book. */
        void credit(Purchased bought, Money amount) {
            LedgerAccount credit = book.accountsOf(null, bought.obligation()).stream()
                    .filter(account -> account.purpose() == com.finapp.ledger.AccountPurpose.BNPL_CREDIT_BALANCE)
                    .findFirst().orElseThrow();
            synchronized (book) {
                LocalDate today = LocalDate.now(ZoneOffset.UTC);
                fakes.book.lines.add(new BnplApplicationFakes.Book.Line("fixture:credit", ports.wallet, Direction.DEBIT,
                        amount, today, today));
                fakes.book.lines.add(new BnplApplicationFakes.Book.Line("fixture:credit", credit.id(), Direction.CREDIT,
                        amount, today, today));
            }
        }

        /** A raw billing of {@code instalment} - its versions the obligation's, its figures the case's. */
        BnplBilling billingOf(Purchased bought, BnplPurchaseStore.StoredInstalment instalment, Money interest) {
            return billingOf(bought, instalment, interest, instalment.amount().minus(interest));
        }

        BnplBilling billingOf(Purchased bought, BnplPurchaseStore.StoredInstalment instalment, Money interest,
                Money principal) {
            BnplPurchaseStore.StoredSchedule schedule = bought.schedule();
            return new BnplBilling(IDS.next(), instalment.id(), instalment.sequence(), bought.obligation(), schedule.id(),
                    1, schedule.engine(), bought.agreement(), 1,
                    schedule.engine().equals("SCHEDULE_ENGINE_V1") ? Optional.of("ACCRUAL_ENGINE_V1") : Optional.empty(),
                    BnplServicing.periodStart(schedule, instalment), instalment.dueDate(), principal, interest,
                    Optional.of(JournalEntryId.of(IDS.next())), correlation());
        }
    }

    /** The in-memory book behind one lock - the suite's sweepers run on threads of their own. */
    private static final class SharedBook implements BnplBook {

        private final BnplApplicationFakes.Book delegate;

        SharedBook(BnplApplicationFakes.Book delegate) {
            this.delegate = delegate;
        }

        synchronized List<String> keys() {
            return List.copyOf(delegate.keys);
        }

        @Override
        public synchronized BnplLedgerAccounts openAccounts(Connection unitOfWork, BnplObligationId obligation,
                CurrencyCode currency) {
            return delegate.openAccounts(unitOfWork, obligation, currency);
        }

        @Override
        public synchronized List<LedgerAccount> accountsOf(Connection unitOfWork, BnplObligationId obligation) {
            return delegate.accountsOf(unitOfWork, obligation);
        }

        @Override
        public synchronized LedgerAccountId operational(Connection unitOfWork, com.finapp.ledger.AccountPurpose purpose,
                CurrencyCode currency) {
            return delegate.operational(unitOfWork, purpose, currency);
        }

        @Override
        public synchronized Optional<LedgerAccountStatus> lockForUpdate(Connection unitOfWork, LedgerAccountId account) {
            return delegate.lockForUpdate(unitOfWork, account);
        }

        @Override
        public synchronized Money availableUnderLock(Connection unitOfWork, LedgerAccountId account) {
            return delegate.availableUnderLock(unitOfWork, account);
        }

        @Override
        public synchronized void lockBalancesInOrder(Connection unitOfWork, Collection<LedgerAccountId> accounts) {
            delegate.lockBalancesInOrder(unitOfWork, accounts);
        }

        @Override
        public synchronized JournalEntryId post(Connection unitOfWork, PostingCommand command) {
            return delegate.post(unitOfWork, command);
        }

        @Override
        public synchronized Money balanceOf(Connection unitOfWork, LedgerAccount account) {
            return delegate.balanceOf(unitOfWork, account);
        }

        @Override
        public synchronized List<com.finapp.ledger.PurposeBalances.DatedLine> datedLinesOn(Connection unitOfWork,
                java.util.Set<LedgerAccountId> accounts, LocalDate valueDateThrough) {
            return delegate.datedLinesOn(unitOfWork, accounts, valueDateThrough);
        }
    }
}
