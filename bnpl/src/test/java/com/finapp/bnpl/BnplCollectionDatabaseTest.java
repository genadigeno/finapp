package com.finapp.bnpl;

import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.count;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.correlation;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.customer;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.employee;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.inOneTransaction;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.refused;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.scalar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.SecurityContext;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Auto-collection against a real database (`P12-TSK-019`; {@code bnpl V011}; scenario 4; {@code INV-IDEM-02},
 * {@code INV-LND-04} by analogy, {@code INV-BAL-04}, {@code INV-BNPL-01}): {@link BnplServicing}'s collection slot over
 * bnpl's own JDBC stores, on a database clock moved by {@link BnplClockFrame} - ten sweepers and a customer's repayment on
 * one due date collect once (scenario 4), the due date's attempt once whatever races, partial and all-or-nothing terms, a
 * short wallet retried on the next business date, the unpostable wallet, never more than due, the closure in the
 * collecting step - and {@code bnpl V011}'s ranks for every writer: {@code UNIQUE (instalment_id, attempt_date)}, the
 * pinned authority, the billed instalment due by the date, never back-filled, the conservation beside every payer, the
 * automatic repayment's attempt at commit, INSERT-only. The ledger is {@link BnplBillingDatabaseTest}'s in-memory book
 * behind one lock - so the wallet's row lock, the two obligations of one customer serialising on it, the payment rail never
 * touched and the deadlock probe are {@code app}'s {@code BnplCollectionLedgerDatabaseTest}'s, over the real ledger.
 *
 * <p>Written by `P12-TSK-019` and <strong>owed a first run</strong> (the owner's no-{@code databaseTest} instruction).
 */
@Tag("database")
@DisplayName("auto-collection from the wallet against bnpl V011's ranks (P12-TSK-019)")
class BnplCollectionDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = BnplTermsFixtures.EUR;
    private static final JdbcBnplOfferStore OFFERS = new JdbcBnplOfferStore();
    private static final JdbcBnplPurchaseStore PURCHASES = new JdbcBnplPurchaseStore();
    private static final JdbcBnplServicingStore SERVICING = new JdbcBnplServicingStore();
    private static final JdbcBnplRepaymentStore REPAYMENTS = new JdbcBnplRepaymentStore();
    private static final JdbcBnplTermsStore TERMS_STORE = new JdbcBnplTermsStore();

    @BeforeAll
    static void termsAndClock() {
        BnplBillingDatabaseTest.termsAndClock();
    }

    // ------------------------------------------------------------------ scenario 4

    @Test
    @DisplayName("scenario 4: ten sweepers and the customer's repayment on one due date - serialised on B4 - collect once:"
            + " one attempt for (instalment, date), at most one AUTO_COLLECTION repayment, the instalment paid exactly"
            + " once, no component over-paid; and beneath B4 UNIQUE (instalment_id, attempt_date) refuses a second row")
    void tenSweepersCollectOnce() throws Exception {
        World world = new World();
        Purchased bought = world.collectingPurchase(true);
        LocalDate due = bought.purchaseDate().plusDays(14);
        UUID instalment = bought.schedule().instalments().get(1).id();
        List<Object> outcomes = world.concurrently(11, index -> index == 10
                ? world.repayOn(due, bought, "racing", eur(150_00))
                : world.serviceOn(due, bought));
        assertThat(outcomes).hasSize(11);
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_collection_attempt WHERE instalment_id = '" + instalment
                + "' AND attempt_date = DATE '" + due + "'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_repayment WHERE obligation_id = '" + bought.id()
                + "' AND kind = 'AUTO_COLLECTION'")).isLessThanOrEqualTo(1);
        assertThat(scalar("SELECT (bnpl.billing_component_billed(b.id, 'PRINCIPAL') - bnpl.billing_component_paid(b.id,"
                + " 'PRINCIPAL'))::text FROM bnpl.bnpl_billing b WHERE b.instalment_id = '" + instalment + "'"))
                .as("paid exactly once, whichever B4 admitted first").isEqualTo("0");
        assertNoComponentOverPaid(bought);
        // Conserved in either order: the instalment billed 150, the customer paid 150, so what is held is exactly what
        // the collection took (150 when it ran first, nothing when the repayment came first and the billing applied it).
        long collected = count("SELECT coalesce(sum(amount_minor), 0) FROM bnpl.bnpl_repayment WHERE obligation_id = '"
                + bought.id() + "' AND kind = 'AUTO_COLLECTION'");
        assertThat(world.balance(bought, com.finapp.ledger.AccountPurpose.BNPL_CREDIT_BALANCE)).isEqualTo(eur(collected));
        // Beneath B4 and the trigger: the UNIQUE itself refuses a second attempt for the instalment and date.
        try (Connection bootstrap = DatabaseRoles.bootstrap()) {
            try (Statement replica = bootstrap.createStatement()) {
                replica.execute("SET session_replication_role = replica");
            }
            bootstrap.setAutoCommit(false);
            refused(bootstrap, "INSERT INTO bnpl.bnpl_collection_attempt SELECT gen_random_uuid(), obligation_id,"
                    + " instalment_id, attempt_date, 'NOTHING_DUE', currency, scale, 0, 0, agreement_version, NULL,"
                    + " clock_timestamp(), correlation_id FROM bnpl.bnpl_collection_attempt WHERE instalment_id = '"
                    + instalment + "'", "23505");
        }
    }

    @Test
    @DisplayName("the due date collects once whatever races: steps before and after the collection that day, and the next"
            + " day once paid, attempt nothing more")
    void theDueDateCollectsOnceWhateverRaces() throws Exception {
        World world = new World();
        Purchased bought = world.collectingPurchase(true);
        LocalDate due = bought.purchaseDate().plusDays(14);
        world.serviceOn(due, bought);
        world.serviceAt(BnplClockFrame.frameTo(due.atTime(23, 0).toInstant(ZoneOffset.UTC)), bought);
        world.serviceOn(due.plusDays(1), bought);
        assertThat(attempts(bought)).containsExactly(due + ":COLLECTED:15000:15000");
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_repayment WHERE obligation_id = '" + bought.id()
                + "' AND kind = 'AUTO_COLLECTION'")).isEqualTo(1);
        assertThat(scalar("SELECT r.request_key = c.instalment_id || ':' || c.attempt_date AND r.value_date = c.attempt_date"
                + " FROM bnpl.bnpl_repayment r JOIN bnpl.bnpl_collection_attempt c ON c.repayment_id = r.id WHERE"
                + " r.obligation_id = '" + bought.id() + "'")).as("the attempt's key, value-dated its date").isEqualTo("t");
    }

    @Test
    @SuppressWarnings("try") // The SecurityContext Scope is used for its close side effect (the idiom).
    @DisplayName("a collection whose instance dies after the attempt and its posting is re-driven by another, once: the"
            + " dead transaction leaves nothing; the re-drive collects once - one attempt, one AUTO_COLLECTION repayment,"
            + " one posting - and a re-drive after a lost response collects nothing more")
    void aCrashedCollectionIsReDrivenOnceByAnotherInstance() throws SQLException {
        World world = new World();
        Purchased bought = world.collectingPurchase(true);
        LocalDate due = bought.purchaseDate().plusDays(14);
        UUID instalment = bought.schedule().instalments().get(1).id();
        String attempted = "SELECT count(*) FROM bnpl.bnpl_collection_attempt WHERE instalment_id = '" + instalment
                + "' AND attempt_date = DATE '" + due + "'";
        String collected = "SELECT count(*) FROM bnpl.bnpl_repayment WHERE obligation_id = '" + bought.id()
                + "' AND kind = 'AUTO_COLLECTION'";
        long base = world.collectionPostings(); // instalment 1's AT_PURCHASE repayment
        long[] seen;
        try (SecurityContext.Scope system = SecurityContext.enterSystem()) {
            seen = BnplInstanceCrash.crashAfter(world.frameOn(due), uow -> world.servicing.service(uow,
                    bought.obligation()), uow -> new long[] {BnplInstanceCrash.countOn(uow, attempted),
                            BnplInstanceCrash.countOn(uow, collected), world.collectionPostings()});
        }
        assertThat(seen).as("the dead instance had attempted, repaid and posted").containsExactly(1, 1, base + 1);
        assertThat(count(attempted)).as("nothing of the dead instance stands").isZero();
        assertThat(count(collected)).isZero();
        assertThat(world.collectionPostings()).isEqualTo(base);

        world.serviceOn(due, bought); // another instance
        world.serviceOn(due, bought); // and the sweep again: the response lost
        assertThat(attempts(bought)).containsExactly(due + ":COLLECTED:15000:15000");
        assertThat(count(collected)).isEqualTo(1);
        assertThat(world.collectionPostings()).as("one entry").isEqualTo(base + 1);
        assertNoComponentOverPaid(bought);
    }

    // ------------------------------------------------------------------ the amount

    @Test
    @DisplayName("short of the instalment under partial terms: PARTIAL - what was available collected, the rest due")
    void partialCollectionWhenAllowed() throws SQLException {
        World world = new World();
        Purchased bought = world.collectingPurchase(true);
        LocalDate due = bought.purchaseDate().plusDays(14);
        world.availableTo(100_00);
        world.serviceOn(due, bought);
        assertThat(attempts(bought)).containsExactly(due + ":PARTIAL:15000:10000");
        assertThat(world.balance(bought, com.finapp.ledger.AccountPurpose.BNPL_PRINCIPAL_DUE)).isEqualTo(eur(50_00));
    }

    @Test
    @DisplayName("short of the instalment where the terms allow no partial collection: INSUFFICIENT_FUNDS, nothing moved,"
            + " bnpl.BnplCollectionFailed in the outbox")
    void allOrNothingWhenNot() throws SQLException {
        World world = new World();
        Purchased bought = world.collectingPurchase(false);
        LocalDate due = bought.purchaseDate().plusDays(14);
        world.availableTo(100_00);
        world.serviceOn(due, bought);
        assertThat(attempts(bought)).containsExactly(due + ":INSUFFICIENT_FUNDS:15000:0");
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_repayment WHERE obligation_id = '" + bought.id()
                + "' AND kind = 'AUTO_COLLECTION'")).isZero();
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = '" + bought.id()
                + "' AND event_type = '" + BnplCollections.FAILED_EVENT + "'")).isEqualTo(1);
    }

    @Test
    @DisplayName("an insufficient wallet is recorded and retried the next business date - a new row, the money collected"
            + " then; a missed date is not back-filled")
    void insufficientFundsRecordsAndRetriesTomorrow() throws SQLException {
        World world = new World();
        Purchased bought = world.collectingPurchase(false);
        LocalDate due = bought.purchaseDate().plusDays(14);
        world.availableTo(0);
        world.serviceOn(due, bought);
        world.availableTo(1_000_00);
        // The sweeper down a day: the next step is two days on, dated that day.
        world.serviceOn(due.plusDays(2), bought);
        assertThat(attempts(bought)).containsExactly(due + ":INSUFFICIENT_FUNDS:15000:0",
                due.plusDays(2) + ":COLLECTED:15000:15000");
    }

    @Test
    @DisplayName("a wallet that cannot be debited now is its own outcome - WALLET_NOT_POSTABLE, nothing moved")
    void aWalletNotPostableIsItsOwnOutcome() throws SQLException {
        World world = new World();
        Purchased bought = world.collectingPurchase(true);
        LocalDate due = bought.purchaseDate().plusDays(14);
        synchronized (world.book) {
            world.fakes.book.statuses.put(world.ports.wallet, LedgerAccountStatus.POSTING_SUSPENDED);
        }
        world.serviceOn(due, bought);
        assertThat(attempts(bought)).containsExactly(due + ":WALLET_NOT_POSTABLE:15000:0");
    }

    @Test
    @DisplayName("never more than due: the repayment's every line on the attempted instalment, never the credit balance;"
            + " and a raw attempt whose figures disagree with what the instalment still has due is refused")
    void neverMoreThanDue() throws SQLException {
        World world = new World();
        Purchased bought = world.collectingPurchase(true);
        LocalDate due = bought.purchaseDate().plusDays(14);
        UUID instalment = bought.schedule().instalments().get(1).id();
        world.serviceOn(due, bought);
        assertThat(scalar("SELECT string_agg(l.target || ':' || l.instalment_id || ':' || l.amount_minor, ',')"
                + " FROM bnpl.bnpl_repayment_allocation l JOIN bnpl.bnpl_repayment r ON r.id = l.repayment_id"
                + " WHERE r.obligation_id = '" + bought.id() + "' AND r.kind = 'AUTO_COLLECTION'"))
                .isEqualTo("DUE_ITEM:" + instalment + ":15000");
        assertNoComponentOverPaid(bought);
        try (Connection app = BnplClockFrame.framed(DatabaseRoles.application(), world.frameOn(due.plusDays(1)))) {
            refused(app, "INSERT INTO bnpl.bnpl_collection_attempt (id, obligation_id, instalment_id, attempt_date,"
                    + " outcome, currency, scale, due_minor, collected_minor, agreement_version, repayment_id, created_at,"
                    + " correlation_id) VALUES (gen_random_uuid(), '" + bought.id() + "', '" + instalment + "', DATE '"
                    + due.plusDays(1) + "', 'INSUFFICIENT_FUNDS', 'EUR', 2, 100, 0, 1, NULL, clock_timestamp(), 'x')",
                    "P0001", "records what its instalment had due and what it collected");
        }
    }

    @Test
    @DisplayName("a collection that settles the obligation closes it in its step: the closure names the collection's"
            + " repayment, the obligation CLOSED")
    void aCollectionThatSettlesClosesInItsStep() throws SQLException {
        World world = new World();
        Purchased bought = world.collectingPurchase(true);
        LocalDate start = bought.purchaseDate();
        world.serviceOn(start.plusDays(14), bought);
        world.serviceOn(start.plusDays(28), bought);
        ServicingOutcome last = world.serviceOn(start.plusDays(42), bought);
        assertThat(last.closed()).isTrue();
        assertThat(scalar("SELECT status || '|' || closure_reason FROM bnpl.bnpl_obligation WHERE id = '" + bought.id()
                + "'")).isEqualTo("CLOSED|REPAID");
        assertThat(scalar("SELECT (c.repayment_id = a.repayment_id)::text FROM bnpl.bnpl_obligation_closure c JOIN"
                + " bnpl.bnpl_collection_attempt a ON a.obligation_id = c.obligation_id WHERE c.obligation_id = '"
                + bought.id() + "' AND a.attempt_date = DATE '" + start.plusDays(42) + "'")).isEqualTo("true");
    }

    // ------------------------------------------------------------------ bnpl V011's ranks for every writer

    @Test
    @DisplayName("the ranks hold for every writer: a back-filled date, an unbilled instalment, PARTIAL under"
            + " all-or-nothing terms, an AUTO_COLLECTION repayment without its attempt at commit - each refused;"
            + " INSERT-only and never truncated for every role")
    void theRanksHoldForEveryWriter() throws SQLException {
        World world = new World();
        Purchased bought = world.collectingPurchase(false);
        LocalDate due = bought.purchaseDate().plusDays(14);
        UUID second = bought.schedule().instalments().get(1).id();
        UUID fourth = bought.schedule().instalments().get(3).id();
        world.availableTo(0);
        world.serviceOn(due.plusDays(1), bought); // instalment 2 billed and attempted on the day after its due date
        String insert = "INSERT INTO bnpl.bnpl_collection_attempt (id, obligation_id, instalment_id, attempt_date,"
                + " outcome, currency, scale, due_minor, collected_minor, agreement_version, repayment_id, created_at,"
                + " correlation_id) VALUES (gen_random_uuid(), '" + bought.id() + "', '%s', DATE '%s', '%s', 'EUR', 2,"
                + " %d, %d, 1, NULL, clock_timestamp(), 'raw writer')";
        try (Connection app = BnplClockFrame.framed(DatabaseRoles.application(), world.frameOn(due))) {
            // Back-filled: an attempt dated before the instalment's later one.
            refused(app, String.format(insert, second, due, "INSUFFICIENT_FUNDS", 150_00, 0), "P0001",
                    "never dated before an earlier attempt of its instalment");
            // Not billed: instalment 4.
            refused(app, String.format(insert, fourth, due, "INSUFFICIENT_FUNDS", 150_00, 0), "P0001",
                    "attempted on a billed instalment of its own obligation");
        }
        try (Connection app = BnplClockFrame.framed(DatabaseRoles.application(), world.frameOn(due.plusDays(2)))) {
            // PARTIAL under terms that allow none - the trigger's terms rank, before the shape's CHECK and the FK.
            refused(app, String.format(insert.replace("NULL, clock_timestamp()", "gen_random_uuid(), clock_timestamp()"),
                    second, due.plusDays(2), "PARTIAL", 150_00, 1_00), "P0001",
                    "a partial BNPL collection only where the pinned terms allow it");
        }
        // An AUTO_COLLECTION repayment no attempt names - whole and paying a billed instalment, so only the deferred
        // "every automatic repayment has its attempt" can refuse it, at commit.
        try (Connection app = BnplClockFrame.framed(DatabaseRoles.application(), world.frameOn(due.plusDays(2)));
                Statement statement = app.createStatement()) {
            UUID orphan = IDS.next();
            statement.execute("INSERT INTO bnpl.bnpl_repayment (id, obligation_id, kind, request_key, currency, scale,"
                    + " amount_minor, agreement_id, agreement_version, allocation_engine, journal_entry_id, value_date,"
                    + " received_at) SELECT '" + orphan + "', obligation_id, 'AUTO_COLLECTION', '" + second + ":"
                    + due.plusDays(2) + "', currency, scale, 100, agreement_id, agreement_version, allocation_engine,"
                    + " gen_random_uuid(), DATE '" + due.plusDays(2) + "', clock_timestamp() FROM bnpl.bnpl_repayment"
                    + " WHERE obligation_id = '" + bought.id() + "' AND kind = 'AT_PURCHASE'");
            statement.execute("INSERT INTO bnpl.bnpl_repayment_allocation (id, repayment_id, sequence, target,"
                    + " instalment_id, component, currency, scale, amount_minor) VALUES (gen_random_uuid(), '" + orphan
                    + "', 1, 'DUE_ITEM', '" + second + "', 'PRINCIPAL', 'EUR', 2, 100)");
            assertThatThrownBy(app::commit).isInstanceOf(SQLException.class)
                    .hasMessageContaining("named by its collection attempt");
        }
        try (Connection app = DatabaseRoles.application(); Connection migrator = DatabaseRoles.migrator()) {
            app.setAutoCommit(false);
            migrator.setAutoCommit(false);
            refused(app, "UPDATE bnpl.bnpl_collection_attempt SET outcome = 'NOTHING_DUE' WHERE obligation_id = '"
                    + bought.id() + "'", "42501");
            refused(app, "DELETE FROM bnpl.bnpl_collection_attempt", "42501");
            refused(migrator, "UPDATE bnpl.bnpl_collection_attempt SET collected_minor = 1 WHERE obligation_id = '"
                    + bought.id() + "'", "P0001", "a BNPL collection attempt is immutable from insert");
            refused(migrator, "DELETE FROM bnpl.bnpl_collection_attempt WHERE obligation_id = '" + bought.id() + "'",
                    "P0001", "a BNPL collection attempt is immutable from insert");
            refused(migrator, "TRUNCATE bnpl.bnpl_collection_attempt", "P0001", "are never truncated");
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static List<String> attempts(Purchased bought) throws SQLException {
        String rows = scalar("SELECT string_agg(attempt_date || ':' || outcome || ':' || due_minor || ':' || collected_minor,"
                + " ',' ORDER BY attempt_date, created_at) FROM bnpl.bnpl_collection_attempt WHERE obligation_id = '"
                + bought.id() + "'");
        return rows == null ? List.of() : List.of(rows.split(","));
    }

    private static void assertNoComponentOverPaid(Purchased bought) throws SQLException {
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_billing b CROSS JOIN (VALUES ('FEES'), ('INTEREST'),"
                + " ('PRINCIPAL')) AS c (component) WHERE b.obligation_id = '" + bought.id() + "' AND"
                + " bnpl.billing_component_paid(b.id, c.component) > bnpl.billing_component_billed(b.id, c.component)"))
                .isZero();
    }

    private static Money eur(long minor) {
        return Money.ofMinorUnits(minor, EUR);
    }

    /** One purchased obligation - its id, party, purchase date, schedule version 1 and agreement. */
    record Purchased(BnplObligationId obligation, UUID party, LocalDate purchaseDate,
            BnplPurchaseStore.StoredSchedule schedule, BnplAgreementId agreement) {

        String id() {
            return obligation.value().toString();
        }
    }

    /** A step of a concurrent race. */
    @FunctionalInterface
    interface Racer<R> {
        R run(int index) throws Exception;
    }

    /** The suite's world: the scripted ports, the in-memory book behind one lock, T-pur, T-svc and T-rep over JDBC. */
    private static final class World {

        final BnplAcceptanceDatabaseTest.Scripted ports = new BnplAcceptanceDatabaseTest.Scripted();
        final BnplApplicationFakes fakes = new BnplApplicationFakes();
        final BnplBillingDatabaseTest.SharedBook book = new BnplBillingDatabaseTest.SharedBook(fakes.book);
        final BnplClosures closures = new BnplClosures(REPAYMENTS, SERVICING, PURCHASES, ports, book,
                new JdbcOutboxWriter(), IDS, CLOCK);
        final BnplRepayments repayments = new BnplRepayments(REPAYMENTS, SERVICING, PURCHASES, TERMS_STORE, ports, book,
                closures, new JdbcOutboxWriter(), IDS, CLOCK);
        final BnplServicing servicing = new BnplServicing(SERVICING, PURCHASES, TERMS_STORE, book, closures,
                new BnplCollections(new JdbcBnplCollectionStore(), repayments, PURCHASES, ports, book,
                        new JdbcOutboxWriter(), IDS, CLOCK),
                new BnplConditions(new JdbcBnplConditionStore(), PURCHASES, TERMS_STORE, book, new JdbcOutboxWriter(),
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

        /**
         * A pay-in-4 purchase under terms that collect automatically ({@code partial} or all-or-nothing) - the suite's
         * default terms (no auto-collection, {@link BnplTermsFixtures}) restored at once: each obligation keeps the
         * version its offer pinned, and every other suite services as before.
         */
        Purchased collectingPurchase(boolean partial) {
            activate(BnplTermsFixtures.payIn4().autoCollection(true).partialCollection(partial).build());
            try {
                return purchase();
            } finally {
                activate(BnplTermsFixtures.payIn4().build());
            }
        }

        private Purchased purchase() {
            BnplAcceptanceDatabaseTest.Offered offered = BnplAcceptanceDatabaseTest.offered(BnplProduct.BNPL_PAY_IN_4,
                    BnplPlan.PAY_IN_4);
            ports.session = offered.session();
            ports.merchant = offered.merchant();
            String hash = inOneTransaction(uow -> OFFERS.terms(uow, offered.offer()).orElseThrow().termsSha256Hex());
            BnplAcceptances.Accepted accepted = inOneTransaction(uow -> acceptances.accept(uow, offered.offer(),
                    offered.party(), new BnplAcceptances.Acceptance(hash, new BnplAcceptanceEvidence(UUID.randomUUID(),
                            UUID.randomUUID(), "MULTI_FACTOR", Optional.empty(), Optional.empty())),
                    customer(offered.party()), Correlation.startingWith(correlation())));
            BnplPurchaseStore.StoredSchedule schedule = inOneTransaction(uow -> PURCHASES.schedule(uow,
                    accepted.obligation().id(), 1)).orElseThrow();
            return new Purchased(accepted.obligation().id(), offered.party(), accepted.purchase().businessDate(),
                    schedule, accepted.agreement().id());
        }

        private static void activate(BnplTerms wanted) {
            BnplTermsAdministration terms = new BnplTermsAdministration(TERMS_STORE, new JdbcAuditWriter(),
                    new JdbcOutboxWriter(), IDS, CLOCK, BnplTermsFixtures.BOUNDS);
            BnplTermsAdministration.Proposed proposed = inOneTransaction(uow -> terms.propose(uow, wanted,
                    "the collection suite's terms", employee(), correlation()));
            inOneTransaction(uow -> terms.approve(uow, proposed.id(), employee(), "in force", correlation()));
        }

        /** The book's repayment postings (an AUTO_COLLECTION's among them) - the entries, as the ledger keys them. */
        long collectionPostings() {
            return book.keys().stream().filter(key -> key.startsWith(BnplRepayments.POSTING_KEY_PREFIX)).count();
        }

        /** The wallet's available balance set to exactly {@code minor} - the fixture standing in for pay-ins. */
        void availableTo(long minor) {
            synchronized (book) {
                Money now = fakes.book.availableUnderLock(null, ports.wallet);
                fakes.book.funds.put(ports.wallet, fakes.book.funds.get(ports.wallet).plus(eur(minor).minus(now)));
            }
        }

        Duration frameOn(LocalDate date) {
            return BnplClockFrame.frameTo(date.atTime(12, 0).toInstant(ZoneOffset.UTC));
        }

        ServicingOutcome serviceOn(LocalDate date, Purchased bought) {
            return serviceAt(frameOn(date), bought);
        }

        @SuppressWarnings("try") // The SecurityContext Scope is used for its close side effect (the idiom).
        ServicingOutcome serviceAt(Duration frame, Purchased bought) {
            try (SecurityContext.Scope system = SecurityContext.enterSystem()) {
                return BnplClockFrame.inOneTransaction(frame, uow -> servicing.service(uow, bought.obligation()));
            }
        }

        BnplRepayments.Repaid repayOn(LocalDate date, Purchased bought, String key, Money amount) {
            return BnplClockFrame.inOneTransaction(frameOn(date), uow -> repayments.repay(uow, bought.agreement(),
                    bought.party(), new BnplRepayments.Request(key, amount), customer(bought.party()), correlation()));
        }

        Money balance(Purchased bought, com.finapp.ledger.AccountPurpose purpose) {
            return book.balanceOf(null, book.accountsOf(null, bought.obligation()).stream()
                    .filter(account -> account.purpose() == purpose).findFirst().orElseThrow());
        }

        <R> List<R> concurrently(int racers, Racer<R> racer) throws Exception {
            ExecutorService pool = Executors.newFixedThreadPool(racers);
            CountDownLatch start = new CountDownLatch(1);
            try {
                List<Future<R>> running = new ArrayList<>();
                for (int index = 0; index < racers; index++) {
                    int which = index;
                    running.add(pool.submit((Callable<R>) () -> {
                        start.await();
                        return racer.run(which);
                    }));
                }
                start.countDown();
                List<R> results = new ArrayList<>();
                for (Future<R> result : running) {
                    results.add(result.get(60, TimeUnit.SECONDS));
                }
                return results;
            } finally {
                pool.shutdownNow();
            }
        }
    }
}
