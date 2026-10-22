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

import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PurposeBalances;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
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
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
 * T-ref against a real database (`P12-TSK-021`; {@code bnpl V012}; owner decisions B3 and B7, ADR-0105;
 * {@code INV-BNPL-06}, {@code -07}, {@code -09}, {@code INV-MER-07} as amended, {@code INV-HIST-04}, {@code INV-IDEM-01}):
 * {@link BnplRefunds} over bnpl's own JDBC stores, on a database clock moved by {@link BnplClockFrame} - scenarios 7 (a
 * refund racing a collection, both orders), 8 (the same refund twice on two instances; two refunds on two instances never
 * above the gross), 9 (before, during, after; a full refund closing with nothing stranded) and 16 (a repayment after a full
 * refund) - and {@code bnpl V012}'s ranks for every writer: the refund's bound and B7's, its versions and floor, the
 * adjustments' deferred Σ, the versions' identity, the purchase's terminal edge, the closure's reasons, immutability. The
 * ledger is the in-memory book of {@link BnplApplicationFakes} behind one lock (the real postings, the pair lock against a
 * wallet-paid capture and the doors are {@code app}'s {@code BnplRefundApiTest}).
 *
 * <p>Written by `P12-TSK-021` and <strong>owed a first run</strong> (the owner's no-{@code databaseTest} instruction).
 */
@Tag("database")
@DisplayName("merchant refunds against bnpl V012's ranks (P12-TSK-021)")
class BnplRefundDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = BnplTermsFixtures.EUR;
    private static final JdbcBnplOfferStore OFFERS = new JdbcBnplOfferStore();
    private static final JdbcBnplPurchaseStore PURCHASES = new JdbcBnplPurchaseStore();
    private static final JdbcBnplServicingStore SERVICING = new JdbcBnplServicingStore();
    private static final JdbcBnplRepaymentStore REPAYMENTS = new JdbcBnplRepaymentStore();
    private static final JdbcBnplRefundStore REFUNDS = new JdbcBnplRefundStore();
    private static final JdbcBnplTermsStore TERMS_STORE = new JdbcBnplTermsStore();

    @BeforeAll
    static void termsAndClock() {
        BnplClockFrame.install();
        BnplTermsAdministration terms = new BnplTermsAdministration(TERMS_STORE, new JdbcAuditWriter(),
                new JdbcOutboxWriter(), IDS, CLOCK, BnplTermsFixtures.BOUNDS);
        for (BnplTerms wanted : List.of(BnplTermsFixtures.payIn4().build(), BnplTermsFixtures.payInN("0.1999").build())) {
            if (inOneTransaction(uow -> TERMS_STORE.active(uow, wanted.product())).isEmpty()) {
                BnplTermsAdministration.Proposed proposed = inOneTransaction(uow -> terms.propose(uow, wanted,
                        "the refund suite's terms", employee(), correlation()));
                inOneTransaction(uow -> terms.approve(uow, proposed.id(), employee(), "in force", correlation()));
            }
        }
    }

    // ------------------------------------------------------------------ scenario 9: before, during, after

    @Test
    @DisplayName("scenario 9: a full refund before instalment 2 cancels 4, 3 and 2 latest first, the excess the paid"
            + " instalment 1, the fee in full - every BNPL account zero, CLOSED (REFUNDED), the purchase FULLY_REFUNDED,"
            + " schedule and agreement version 2 (INV-BNPL-09)")
    void aFullRefundClosesWithNothingStranded() throws SQLException {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        BnplRefunds.Refunded refunded = world.refundOn(bought.purchaseDate().plusDays(3), bought, "full", 600_00);
        assertThat(refunded.closure()).map(BnplClosure::reason).contains(BnplClosureReason.REFUNDED);
        assertThat(scalar("SELECT string_agg(sequence || ':' || kind || ':' || principal_cancelled_minor, ','"
                + " ORDER BY sequence DESC) FROM bnpl.bnpl_refund_adjustment WHERE refund_id = '"
                + refunded.refund().id() + "'")).isEqualTo("4:UNBILLED:15000,3:UNBILLED:15000,2:UNBILLED:15000");
        assertThat(scalar("SELECT excess_minor || '|' || excess_to || '|' || fee_returned_minor || '|'"
                + " || payable_debit_minor FROM bnpl.bnpl_refund WHERE id = '" + refunded.refund().id() + "'"))
                .isEqualTo("15000|WALLET|2400|57600");
        for (com.finapp.ledger.AccountPurpose purpose : BnplAccounts.purposes()) {
            assertThat(world.balance(bought, purpose)).as(purpose.name()).isEqualTo(Money.zero(EUR));
        }
        assertThat(scalar("SELECT status FROM bnpl.purchase WHERE id = '" + bought.purchase().value() + "'"))
                .isEqualTo("FULLY_REFUNDED");
        assertThat(scalar("SELECT status || '|' || closure_reason FROM bnpl.bnpl_obligation WHERE id = '" + bought.id()
                + "'")).isEqualTo("CLOSED|REFUNDED");
        assertThat(scalar("SELECT max(schedule_version)::text FROM bnpl.bnpl_schedule WHERE obligation_id = '"
                + bought.id() + "'")).isEqualTo("2");
        assertThat(scalar("SELECT max(version)::text FROM bnpl.bnpl_agreement WHERE obligation_id = '" + bought.id()
                + "'")).isEqualTo("2");
    }

    @Test
    @DisplayName("refund before, during and after: 180 cancels 4 and 30 of 3; with instalment 2 billed, 420 cancels the"
            + " rest of 3 and 2's billed principal (settled by the cancellation) and returns the excess; after closure"
            + " (REPAID) a refund is all excess to the wallet, the obligation CLOSED")
    void refundBeforeDuringAfter() throws SQLException {
        World world = new World();
        Purchased during = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        LocalDate second = during.purchaseDate().plusDays(14);
        BnplRefunds.Refunded before = world.refundOn(during.purchaseDate().plusDays(1), during, "before", 180_00);
        assertThat(scalar("SELECT string_agg(sequence || ':' || kind || ':' || principal_cancelled_minor, ','"
                + " ORDER BY sequence DESC) FROM bnpl.bnpl_refund_adjustment WHERE refund_id = '"
                + before.refund().id() + "'")).as("before: 4 whole, then 30.00 of 3 - latest first")
                .isEqualTo("4:UNBILLED:15000,3:UNBILLED:3000");
        assertThat(before.refund().cancelledBilled()).isEqualTo(Money.zero(EUR));
        assertThat(before.refund().excess()).isEqualTo(Money.zero(EUR));
        assertThat(before.closure()).isEmpty();
        world.serviceOn(second, during);
        BnplRefunds.Refunded rest = world.refundOn(second.plusDays(1), during, "during", 420_00);
        assertThat(rest.refund().cancelledBilled()).isEqualTo(Money.ofMinorUnits(150_00, EUR));
        assertThat(rest.refund().excess()).isEqualTo(Money.ofMinorUnits(150_00, EUR));
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_billing b CROSS JOIN (VALUES ('INTEREST'), ('PRINCIPAL')) AS"
                + " c (component) WHERE b.obligation_id = '" + during.id() + "' AND bnpl.billing_component_paid(b.id,"
                + " c.component) <> bnpl.billing_component_billed(b.id, c.component)")).as("every billed item settled")
                .isZero();
        assertThat(scalar("SELECT sum(fee_returned_minor)::text FROM bnpl.bnpl_refund WHERE purchase_id = '"
                + during.purchase().value() + "'")).isEqualTo("2400");

        Purchased after = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        LocalDate last = after.purchaseDate().plusDays(42);
        world.serviceOn(last, after);
        world.repayOn(last, after, "all", 450_00);
        BnplRefunds.Refunded late = world.refundOn(last.plusDays(2), after, "after", 240_00);
        assertThat(late.refund().cancelled()).isEqualTo(Money.zero(EUR));
        assertThat(late.refund().excess()).isEqualTo(Money.ofMinorUnits(240_00, EUR));
        assertThat(scalar("SELECT status || '|' || closure_reason FROM bnpl.bnpl_obligation WHERE id = '" + after.id()
                + "'")).isEqualTo("CLOSED|REPAID");
    }

    @Test
    @DisplayName("the excess goes to the customer's wallet; a cancellation is a full refund; a partial refund reduces the"
            + " latest instalments - version 2's principal version 1's less exactly the unbilled cancellation")
    void excessGoesToTheCustomerAndAPartialRefundReducesTheLatestInstalments() throws SQLException {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        world.refundOn(bought.purchaseDate().plusDays(1), bought, "partial", 180_00);
        assertThat(scalar("SELECT max(version)::text FROM bnpl.bnpl_agreement WHERE obligation_id = '" + bought.id()
                + "'")).as("the partial refund's agreement version").isEqualTo("2");
        assertThat(scalar("SELECT status FROM bnpl.bnpl_obligation WHERE id = '" + bought.id() + "'"))
                .as("a partial refund leaves the obligation ACTIVE").isEqualTo("ACTIVE");
        assertThat(scalar("SELECT string_agg(i.sequence || ':' || i.principal_minor, ',' ORDER BY i.sequence) FROM"
                + " bnpl.bnpl_instalment i JOIN bnpl.bnpl_schedule s ON s.id = i.schedule_id WHERE s.obligation_id = '"
                + bought.id() + "' AND s.schedule_version = 2")).isEqualTo("1:15000,2:15000,3:12000");
        assertThat(scalar("SELECT principal_minor::text FROM bnpl.bnpl_schedule WHERE obligation_id = '" + bought.id()
                + "' AND schedule_version = 2")).isEqualTo("42000");
        BnplRefunds.Refunded rest = world.refundOn(bought.purchaseDate().plusDays(2), bought, "cancel", 420_00);
        assertThat(rest.refund().excess()).as("instalment 1, paid at purchase").isEqualTo(Money.ofMinorUnits(150_00, EUR));
        assertThat(rest.refund().excessTo()).as("the excess to the customer's wallet")
                .isEqualTo(BnplRefund.ExcessTo.WALLET);
        assertThat(rest.closure()).map(BnplClosure::reason).contains(BnplClosureReason.REFUNDED);
    }

    @Test
    @DisplayName("refunds cancel latest first, and the servicing step bills version 2: instalment 2 150, instalment 3 120,"
            + " nothing for 4 - the billing's oldest-first rank judged by due date")
    void refundCancelsLatestFirst() throws SQLException {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        world.refundOn(bought.purchaseDate().plusDays(1), bought, "latest", 180_00);
        world.serviceOn(bought.purchaseDate().plusDays(42), bought);
        assertThat(scalar("SELECT string_agg(b.value_date - o.purchase_date || ':' || b.principal_minor || ':v'"
                + " || b.schedule_version, ',' ORDER BY b.value_date) FROM bnpl.bnpl_billing b JOIN bnpl.bnpl_obligation"
                + " o ON o.id = b.obligation_id WHERE b.obligation_id = '" + bought.id() + "'"))
                .isEqualTo("0:15000:v1,14:15000:v2,28:12000:v2");
    }

    @Test
    @DisplayName("a repayment after a full refund is refused 409, nothing posted (scenario 16); a refund after it is 409"
            + " PurchaseFullyRefunded")
    void aRepaymentAfterAFullRefundIsRefused() throws SQLException {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        world.refundOn(bought.purchaseDate().plusDays(1), bought, "full", 600_00);
        int keys = world.book.keys().size();
        String repaymentRows = "SELECT count(*) FROM bnpl.bnpl_repayment WHERE obligation_id = '" + bought.id() + "'";
        long repayments = count(repaymentRows);
        String outboxRows = "SELECT count(*) FROM platform.outbox_event WHERE aggregate_id IN ('" + bought.id() + "', '"
                + bought.purchase().value() + "')";
        long outbox = count(outboxRows);
        assertThatThrownBy(() -> world.repayOn(bought.purchaseDate().plusDays(2), bought, "late", 10_00))
                .isInstanceOf(BnplRepayments.ObligationClosed.class);
        assertThatThrownBy(() -> world.refundOn(bought.purchaseDate().plusDays(2), bought, "again", 1))
                .isInstanceOf(BnplRefunds.PurchaseFullyRefunded.class);
        assertThat(world.book.keys()).hasSize(keys);
        assertThat(count(repaymentRows)).as("no repayment row written").isEqualTo(repayments);
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_repayment WHERE obligation_id = '" + bought.id()
                + "' AND kind = 'CUSTOMER'")).as("no customer repayment at all").isZero();
        assertThat(count(outboxRows)).as("no event written").isEqualTo(outbox);
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_refund WHERE purchase_id = '" + bought.purchase().value()
                + "'")).as("the one full refund only").isEqualTo(1);
    }

    // ------------------------------------------------------------------ scenarios 7 and 8, and the fee

    @Test
    @DisplayName("scenario 7: a refund racing the instalment's auto-collection (P12-TSK-019, collecting terms) and a"
            + " customer's repayment of it conserves in both orders - B5 then B4 against B4: Σ cancelled + excess = R,"
            + " nothing over-allocated, every BNPL account zero, the customer whole")
    void aRefundRacingACollectionConservesInBothOrders() throws Exception {
        for (int round = 0; round < 6; round++) {
            boolean collection = round % 2 == 0;
            World world = new World();
            Purchased bought = collection ? world.collectingPurchase() : world.purchase(BnplProduct.BNPL_PAY_IN_4,
                    BnplPlan.PAY_IN_4);
            LocalDate due = bought.purchaseDate().plusDays(14);
            if (!collection) {
                world.serviceOn(due, bought);
            }
            List<Object> outcomes = world.concurrently(2, index -> {
                try {
                    if (index == 0) {
                        return world.refundOn(due, bought, "race", 600_00);
                    }
                    return collection ? world.serviceOn(due, bought) : world.repayOn(due, bought, "collect", 150_00);
                } catch (BnplRepayments.ObligationClosed closed) {
                    return closed;
                }
            });
            assertThat(outcomes).hasSize(2);
            assertThat(scalar("SELECT (cancelled_unbilled_minor + cancelled_billed_minor + excess_minor)::text FROM"
                    + " bnpl.bnpl_refund WHERE purchase_id = '" + bought.purchase().value() + "'")).isEqualTo("60000");
            for (com.finapp.ledger.AccountPurpose purpose : BnplAccounts.purposes()) {
                assertThat(world.balance(bought, purpose)).as(purpose.name()).isEqualTo(Money.zero(EUR));
            }
            if (collection) {
                assertThat(count("SELECT count(*) FROM bnpl.bnpl_collection_attempt WHERE obligation_id = '"
                        + bought.obligation().value() + "' AND collected_minor > 0")).as("collected at most once")
                        .isLessThanOrEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("scenario 8: the same refund twice - one key, two instances at once - refunds once; a lapsed claim's"
            + " retry converges on the refund born under the key (UNIQUE (merchant_id, requested_by, refund_key))")
    void theSameRefundTwiceRefundsOnce() throws Exception {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        List<BnplRefunds.Refunded> refunded = world.concurrently(2, index -> world.refundOn(
                bought.purchaseDate().plusDays(1), bought, "once", 180_00));
        assertThat(refunded).extracting(BnplRefunds.Refunded::replayed).containsExactlyInAnyOrder(true, false);
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_refund WHERE purchase_id = '" + bought.purchase().value()
                + "'")).isEqualTo(1);
        assertThat(world.book.keys()).filteredOn(key -> key.startsWith(BnplRefunds.POSTING_KEY_PREFIX)).hasSize(1);
        assertThatThrownBy(() -> world.refundOn(bought.purchaseDate().plusDays(1), bought, "once", 181_00))
                .isInstanceOf(BnplRefunds.RefundKeyReused.class);
    }

    @Test
    @DisplayName("two refunds with different keys on two instances never exceed the gross: serialised on B5, the second"
            + " sees the first; ten racing refunds of 100 on a 600 purchase admit exactly six")
    void twoRefundsOnTwoInstancesNeverExceedTheGross() throws Exception {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        List<Object> outcomes = world.concurrently(10, index -> {
            try {
                return world.refundOn(bought.purchaseDate().plusDays(1), bought, "ten-" + index, 100_00);
            } catch (BnplRefunds.RefundExceedsPurchase | BnplRefunds.PurchaseFullyRefunded refused) {
                return refused;
            }
        });
        assertThat(outcomes).filteredOn(BnplRefunds.Refunded.class::isInstance).hasSize(6);
        assertThat(scalar("SELECT sum(amount_minor)::text || '|' || sum(fee_returned_minor)::text FROM bnpl.bnpl_refund"
                + " WHERE purchase_id = '" + bought.purchase().value() + "'")).isEqualTo("60000|2400");
    }

    @Test
    @DisplayName("the fee telescopes to exactly f over split refunds - 100.00, 133.33, 366.67 return 4.00, 5.33, 14.67")
    void theFeeTelescopesToExactlyFOverSplitRefunds() throws SQLException {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        for (long minor : new long[] {100_00, 133_33, 366_67}) {
            world.refundOn(bought.purchaseDate().plusDays(1), bought, "split-" + minor, minor);
        }
        assertThat(scalar("SELECT string_agg(fee_returned_minor::text, ',' ORDER BY refunded_before_minor) FROM bnpl.bnpl_refund"
                + " WHERE purchase_id = '" + bought.purchase().value() + "'")).isEqualTo("400,533,1467");
    }

    @Test
    @DisplayName("B7: the refund trigger refuses for every writer a refund whose payable debits would exceed the financed"
            + " net, and one above the gross - the bound's second rank (INV-MER-07 as amended, INV-BNPL-07)")
    void aRefundAfterPayoutIsBoundedByThePurchasesNet() throws SQLException {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        world.refundOn(bought.purchaseDate().plusDays(1), bought, "first", 180_00);
        try (Connection migrator = BnplClockFrame.framed(DatabaseRoles.migrator(),
                world.frameOn(bought.purchaseDate().plusDays(1)))) {
            refused(migrator, rawRefund(bought, 42000, 18000, 0, 42000, 0, 42000, "NONE", 0), "P0001",
                    "take back at most its financed net and return at most its fee");
            refused(migrator, rawRefund(bought, 42001, 18000, 1680, 40321, 0, 42001, "NONE", 0), "P0001",
                    "never total more than its gross");
        }
    }

    @Test
    @DisplayName("the Phase 12 -> 13 gate's A2-2: V012's value-date floor rank, live - a raw refund valued the day before"
            + " its ACTIVE obligation's floor is refused for the migrator and the application role alike, P0001 with the"
            + " floor's own message (the rank that refused, not another)")
    void aRefundValuedBeforeTheFloorIsRefusedForEveryWriter() throws SQLException {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        String beforeTheFloor = rawRefund(bought, 18000, 0, 0, 18000, 18000, 0, "NONE", 0,
                "(SELECT bnpl.principal_value_floor(o.id)) - 1");
        for (java.util.function.Supplier<Connection> role : List.<java.util.function.Supplier<Connection>>of(
                BnplRefundDatabaseTest::migrator, BnplRefundDatabaseTest::application)) {
            try (Connection writer = BnplClockFrame.framed(role.get(), world.frameOn(bought.purchaseDate().plusDays(1)))) {
                assertThatThrownBy(() -> {
                            try (Statement insert = writer.createStatement()) {
                                insert.execute(beforeTheFloor);
                            }
                        })
                        .isInstanceOfSatisfying(SQLException.class, refused -> assertThat(refused.getSQLState())
                                .isEqualTo("P0001"))
                        .hasMessageContaining("a BNPL refund is value-dated on or after its obligation's floor");
                writer.rollback();
            }
        }
        assertThat(scalar("SELECT count(*) FROM bnpl.bnpl_refund WHERE purchase_id = '" + bought.purchase().value() + "'"))
                .isEqualTo("0");
    }

    private static Connection migrator() {
        try {
            return DatabaseRoles.migrator();
        } catch (SQLException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    private static Connection application() {
        try {
            return DatabaseRoles.application();
        } catch (SQLException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    // ------------------------------------------------------------------ pay-in-N

    @Test
    @DisplayName("a pay-in-N refund needs every elapsed date accrued, then shortens the plan by recalculateAtInstalment -"
            + " version 2 from the purchase date, its principal version 1's less the cancellation; a full one releases the"
            + " unpaid interest and closes REFUNDED with every account zero (A11)")
    void aPnRefundAccruesFirstAndAFullOneReleasesUnpaidInterest() throws SQLException {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_3_MONTHS);
        LocalDate day = bought.purchaseDate().plusDays(5);
        assertThatThrownBy(() -> world.refundOn(day, bought, "pn", 150_00)).isInstanceOf(BnplRefunds.BehindAccrual.class);
        world.accrueThrough(bought, day.minusDays(1), 10);
        world.refundOn(day, bought, "pn", 150_00);
        assertThat(scalar("SELECT start_date - (SELECT purchase_date FROM bnpl.bnpl_obligation WHERE id = '" + bought.id()
                + "') || '|' || principal_minor FROM bnpl.bnpl_schedule WHERE obligation_id = '" + bought.id()
                + "' AND schedule_version = 2")).isEqualTo("0|45000");
        BnplRefunds.Refunded rest = world.refundOn(day, bought, "pn-rest", 450_00);
        assertThat(rest.refund().interestAccruedReleased()).isEqualTo(Money.ofMinorUnits(50, EUR));
        assertThat(rest.closure()).map(BnplClosure::reason).contains(BnplClosureReason.REFUNDED);
        for (com.finapp.ledger.AccountPurpose purpose : BnplAccounts.purposes()) {
            assertThat(world.balance(bought, purpose)).as(purpose.name()).isEqualTo(Money.zero(EUR));
        }
    }

    @Test
    @DisplayName("an unpostable wallet holds the excess as credit (OQ-13); the next servicing step refunds it and closes"
            + " REFUNDED")
    void anUnpostableWalletHoldsTheExcessAsCredit() throws SQLException {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        synchronized (world.book) {
            world.fakes.book.statuses.put(world.ports.wallet, LedgerAccountStatus.POSTING_SUSPENDED);
        }
        BnplRefunds.Refunded refunded = world.refundOn(bought.purchaseDate().plusDays(1), bought, "held", 600_00);
        assertThat(refunded.refund().excessTo()).isEqualTo(BnplRefund.ExcessTo.CREDIT_BALANCE);
        assertThat(refunded.closure()).isEmpty();
        synchronized (world.book) {
            world.fakes.book.statuses.put(world.ports.wallet, LedgerAccountStatus.ACTIVE);
        }
        assertThat(world.serviceOn(bought.purchaseDate().plusDays(2), bought).closed()).isTrue();
        assertThat(scalar("SELECT reason FROM bnpl.bnpl_obligation_closure WHERE obligation_id = '" + bought.id() + "'"))
                .isEqualTo("REFUNDED");
    }

    // ------------------------------------------------------------------ the lock order and the ranks

    @Test
    @DisplayName("the refund takes the purchase first (B5): another transaction holding its row, the refund waits on"
            + " purchase, then refunds once")
    void refundTakesThePurchaseThenTheObligation() throws Exception {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = DatabaseRoles.application()) {
            holder.setAutoCommit(false);
            try (Statement lock = holder.createStatement()) {
                lock.execute("SELECT 1 FROM bnpl.purchase WHERE id = '" + bought.purchase().value() + "' FOR UPDATE");
            }
            Future<BnplRefunds.Refunded> refunding = pool.submit(() -> world.refundOn(bought.purchaseDate().plusDays(1),
                    bought, "waits", 10_00));
            assertThat(BnplAcceptanceDatabaseTest.awaitBlockedBy(pid(holder))).contains("bnpl.purchase");
            holder.rollback();
            assertThat(refunding.get(30, TimeUnit.SECONDS).replayed()).isFalse();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("V012's ranks for every writer: a refund row is immutable; the purchase moves FULLY_REFUNDED only at the"
            + " gross; adjustments that do not sum to the refund are refused at commit; a CLOSED obligation's refund"
            + " cancels nothing")
    void theRanksHoldForEveryWriter() throws SQLException {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        BnplRefunds.Refunded refunded = world.refundOn(bought.purchaseDate().plusDays(1), bought, "ranks", 180_00);
        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);
            refused(migrator, "UPDATE bnpl.bnpl_refund SET reason = 'edited' WHERE id = '" + refunded.refund().id() + "'",
                    "P0001", "a BNPL refund is immutable from insert");
            refused(migrator, "DELETE FROM bnpl.bnpl_refund_adjustment WHERE refund_id = '" + refunded.refund().id()
                    + "'", "P0001", "a BNPL refund adjustment is immutable from insert");
            refused(migrator, "UPDATE bnpl.purchase SET status = 'FULLY_REFUNDED' WHERE id = '"
                    + bought.purchase().value() + "'", "P0001", "FULLY_REFUNDED only when its refunds total its gross");
        }
        try (Connection migrator = BnplClockFrame.framed(DatabaseRoles.migrator(),
                world.frameOn(bought.purchaseDate().plusDays(1)))) {
            migrator.setAutoCommit(false);
            // A refund claiming 10.00 of unbilled principal cancelled, with no adjustment row naming it: Sigma
            // adjustments (0) + excess (0) <> R. (The row first written here routed the 10.00 to the wallet as excess - a
            // row whose adjustments sum, vacuously, to its cancellations, so the deferred trigger rightly admitted it;
            // P12-TSK-027.)
            try (Statement insert = migrator.createStatement()) {
                insert.execute(rawRefund(bought, 1000, 18000, 40, 960, 1000, 0, "NONE", 0));
            }
            assertThatThrownBy(migrator::commit).isInstanceOf(SQLException.class)
                    .hasMessageContaining("adjustments sum to its cancellations");
        }
    }

    // ------------------------------------------------------------------ plumbing

    /** A raw refund row (the migrator's) - the trigger's ranks judge it for every writer. */
    private static String rawRefund(Purchased bought, long amount, long before, long fee, long debit, long unbilled,
            long billed, String excessTo, long excess) {
        return rawRefund(bought, amount, before, fee, debit, unbilled, billed, excessTo, excess,
                "(SELECT bnpl.principal_value_floor(o.id))");
    }

    /** The raw refund row valued at {@code valueDate}, a SQL expression over the obligation {@code o}. */
    private static String rawRefund(Purchased bought, long amount, long before, long fee, long debit, long unbilled,
            long billed, String excessTo, long excess, String valueDate) {
        return "INSERT INTO bnpl.bnpl_refund (id, purchase_id, obligation_id, merchant_id, door, requested_by, refund_key,"
                + " currency, scale, amount_minor, refunded_before_minor, fee_returned_minor, payable_debit_minor,"
                + " cancelled_unbilled_minor, cancelled_billed_minor, excess_minor, excess_to,"
                + " interest_accrued_released_minor, interest_due_released_minor, allocation_engine, agreement_id,"
                + " agreement_version, schedule_version, reason, journal_entry_id, value_date, created_at, correlation_id)"
                + " SELECT gen_random_uuid(), p.id, o.id, p.merchant_id, 'MERCHANT', 'MERCHANT:' || p.merchant_id,"
                + " 'raw-' || gen_random_uuid(), 'EUR', 2, " + amount + ", " + before + ", " + fee + ", " + debit + ", "
                + unbilled + ", " + billed + ", " + excess + ", '" + excessTo + "', 0, 0, 'REFUND_ALLOCATION_ENGINE_V1',"
                + " o.agreement_id, (SELECT max(version) FROM bnpl.bnpl_agreement WHERE obligation_id = o.id),"
                + " (SELECT max(schedule_version) FROM bnpl.bnpl_schedule WHERE obligation_id = o.id), 'raw',"
                + " gen_random_uuid(), " + valueDate + ", clock_timestamp(), 'raw'"
                + " FROM bnpl.purchase p JOIN bnpl.bnpl_obligation o ON o.purchase_id = p.id WHERE p.id = '"
                + bought.purchase().value() + "'";
    }

    /** One purchased obligation - its purchase, id, party, merchant and purchase date. */
    record Purchased(BnplPurchaseId purchase, BnplObligationId obligation, UUID party, UUID merchant,
            LocalDate purchaseDate, BnplPurchaseStore.StoredSchedule schedule, BnplAgreementId agreement) {

        String id() {
            return obligation.value().toString();
        }
    }

    /** A step of a concurrent race. */
    @FunctionalInterface
    interface Racer<R> {
        R run(int index) throws Exception;
    }

    /** The suite's world: the scripted ports, the in-memory book behind one lock, T-pur, T-svc, T-rep and T-ref. */
    private static final class World {

        final BnplAcceptanceDatabaseTest.Scripted ports = new BnplAcceptanceDatabaseTest.Scripted();
        final BnplApplicationFakes fakes = new BnplApplicationFakes();
        /** The billing suite's book, inside its callers' transactions - a rolled-back attempt leaves nothing (P12-TSK-027). */
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
        final BnplRefunds refunds = new BnplRefunds(REFUNDS, SERVICING, PURCHASES, TERMS_STORE, ports, ports, book,
                closures, (uow, actor) -> BnplKeyIssuer.partyOf(actor), new JdbcOutboxWriter(), new JdbcAuditWriter(), IDS,
                CLOCK);
        private final BnplAcceptances acceptances = new BnplAcceptances(OFFERS, TERMS_STORE, PURCHASES, ports,
                (uow, party) -> true, ports, ports, ports, ports, book, new JdbcOutboxWriter(), new JdbcAuditWriter(),
                IDS, CLOCK);

        World() {
            fakes.book.external("payable", ports.payable.value());
            fakes.book.external("wallet", ports.wallet.value());
            fakes.book.funds.put(ports.wallet, Money.ofMinorUnits(1_000_000_00L, EUR));
        }

        /** A pay-in-4 purchase under terms that collect automatically (P12-TSK-019); the suite's default restored after. */
        Purchased collectingPurchase() {
            activate(BnplTermsFixtures.payIn4().autoCollection(true).build());
            try {
                return purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
            } finally {
                activate(BnplTermsFixtures.payIn4().build());
            }
        }

        private static void activate(BnplTerms wanted) {
            BnplTermsAdministration terms = new BnplTermsAdministration(TERMS_STORE, new JdbcAuditWriter(),
                    new JdbcOutboxWriter(), IDS, CLOCK, BnplTermsFixtures.BOUNDS);
            BnplTermsAdministration.Proposed proposed = inOneTransaction(uow -> terms.propose(uow, wanted,
                    "the refund suite's collecting terms", employee(), correlation()));
            inOneTransaction(uow -> terms.approve(uow, proposed.id(), employee(), "in force", correlation()));
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
            return new Purchased(accepted.purchase().id(), accepted.obligation().id(), offered.party(),
                    offered.merchant(), accepted.purchase().businessDate(), schedule, accepted.agreement().id());
        }

        Duration frameOn(LocalDate date) {
            return BnplClockFrame.frameTo(date.atTime(12, 0).toInstant(ZoneOffset.UTC));
        }

        @SuppressWarnings("try") // The SecurityContext Scope is used for its close side effect (the idiom).
        ServicingOutcome serviceOn(LocalDate date, Purchased bought) {
            try (SecurityContext.Scope system = SecurityContext.enterSystem()) {
                return BnplClockFrame.inOneTransaction(frameOn(date), uow -> servicing.service(uow, bought.obligation()));
            }
        }

        BnplRepayments.Repaid repayOn(LocalDate date, Purchased bought, String key, long minor) {
            return BnplClockFrame.inOneTransaction(frameOn(date), uow -> repayments.repay(uow, bought.agreement(),
                    bought.party(), new BnplRepayments.Request(key, Money.ofMinorUnits(minor, EUR)),
                    customer(bought.party()), correlation()));
        }

        BnplRefunds.Refunded refundOn(LocalDate date, Purchased bought, String key, long minor) {
            Actor merchant = new Actor(bought.merchant().toString(), ActorType.MERCHANT);
            return BnplClockFrame.inOneTransaction(frameOn(date), uow -> refunds.atMerchant(uow, bought.purchase(),
                    bought.merchant(), new BnplRefunds.Request(key, Money.ofMinorUnits(minor, EUR), "goods returned",
                            Optional.of("key-under-test"), BnplKeyIssuer.KEY_ISSUER), merchant, correlation()));
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

        LedgerAccount account(Purchased bought, com.finapp.ledger.AccountPurpose purpose) {
            return book.accountsOf(null, bought.obligation()).stream().filter(account -> account.purpose() == purpose)
                    .findFirst().orElseThrow();
        }

        Money balance(Purchased bought, com.finapp.ledger.AccountPurpose purpose) {
            return book.balanceOf(null, account(bought, purpose));
        }

        /** The owner-role accrual fixture: {@code perDayMinor} on every date from the purchase's through {@code last}. */
        void accrueThrough(Purchased bought, LocalDate last, long perDayMinor) throws SQLException {
            List<BnplPurchaseStore.StoredInstalment> instalments = bought.schedule().instalments();
            try (Connection migrator = BnplClockFrame.framed(DatabaseRoles.migrator(), frameOn(last.plusDays(1)));
                    Statement insert = migrator.createStatement()) {
                long cumulative = 0;
                LocalDate periodStart = bought.purchaseDate();
                int period = 0;
                for (LocalDate day = bought.purchaseDate(); !day.isAfter(last); day = day.plusDays(1)) {
                    while (!day.isBefore(instalments.get(period).dueDate())) {
                        periodStart = instalments.get(period).dueDate();
                        period++;
                    }
                    LocalDate end = instalments.get(period).dueDate();
                    cumulative = day.equals(periodStart) ? perDayMinor : cumulative + perDayMinor;
                    insert.execute("INSERT INTO bnpl.bnpl_interest_accrual (id, obligation_id, accrual_date, period_start,"
                            + " period_end, currency, scale, base_minor, cumulative_exact, posted_minor, agreement_id,"
                            + " agreement_version, accrual_engine, journal_entry_id, created_at, correlation_id) VALUES"
                            + " (gen_random_uuid(), '" + bought.id() + "', DATE '" + day + "', DATE '" + periodStart
                            + "', DATE '" + end + "', 'EUR', 2, 60000, " + cumulative + ", " + perDayMinor + ", '"
                            + bought.agreement().value() + "', 1, 'ACCRUAL_ENGINE_V1', gen_random_uuid(),"
                            + " clock_timestamp(), 'owner-role fixture')");
                    synchronized (book) {
                        fakes.servicing.accrue(bought.obligation(), day, Money.ofMinorUnits(perDayMinor, EUR));
                    }
                }
                migrator.commit();
            }
        }
    }
}
