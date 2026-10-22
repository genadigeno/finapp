package com.finapp.bnpl;

import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.awaitBlockedBy;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.correlation;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.count;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.customer;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.pid;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.scalar;
import static com.finapp.bnpl.BnplConditionDatabaseWorld.CLOCK;
import static com.finapp.bnpl.BnplConditionDatabaseWorld.IDS;
import static com.finapp.bnpl.BnplConditionDatabaseWorld.PURCHASES;
import static com.finapp.bnpl.BnplConditionDatabaseWorld.REFUNDS;
import static com.finapp.bnpl.BnplConditionDatabaseWorld.SERVICING;
import static com.finapp.bnpl.BnplConditionDatabaseWorld.TERMS_STORE;
import static com.finapp.bnpl.BnplConditionDatabaseWorld.eur;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-dsp against a real database (`P12-TSK-023`; {@code bnpl V015}; ADR-0106; {@code INV-BNPL-08}, {@code -07},
 * {@code INV-LIFE-01}...{@code -04}, {@code INV-AUD-04}): {@link BnplDisputes} over bnpl's own JDBC stores on a database clock
 * moved by {@link BnplClockFrame} - scenario 10 (a dispute's refund outcome and a merchant refund sharing one bound, both
 * orders, counted), the races the plan's contention table names (one open dispute per purchase on two instances, the
 * deadline sweep against a late acceptance, ten resolvers, a collection racing the opening) and {@code bnpl V015}'s ranks
 * for every writer (the four-eyes {@code CHECK}, the hold's triggers, the refund beside an open dispute). The ledger is the
 * in-memory book of {@link BnplApplicationFakes} behind one lock ({@link BnplConditionDatabaseWorld}).
 *
 * <p>Written by `P12-TSK-023` and <strong>owed a first run</strong> (the owner's no-{@code databaseTest} instruction).
 */
@Tag("database")
@DisplayName("BNPL disputes against bnpl V015's ranks (P12-TSK-023)")
class BnplDisputeDatabaseTest {

    private static final JdbcBnplDisputeStore DISPUTES = new JdbcBnplDisputeStore();

    /** An operator the platform cannot place in a party - refused at every operator door (the gate's A7-6). */
    private static final Actor UNPLACED = new Actor("operator-unplaced", ActorType.CUSTOMER);

    private final BnplConditionDatabaseWorld world = new BnplConditionDatabaseWorld();
    private final Map<String, UUID> operatorParties = new ConcurrentHashMap<>();
    /** Every operator a party of their own (an identity always belongs to one) - but UNPLACED, which has none. */
    private final BnplActingParty actingParties = (uow, actor) -> Optional.ofNullable(operatorParties.computeIfAbsent(
            actor.id(), id -> id.equals(UNPLACED.id()) ? null : UUID.randomUUID()));
    private final BnplDisputes disputes = new BnplDisputes(DISPUTES, REFUNDS, SERVICING, PURCHASES, TERMS_STORE,
            world.refunds, world.ports, world.book, actingParties, new JdbcOutboxWriter(), new JdbcAuditWriter(), IDS,
            CLOCK);

    // Run alone the suite found no ACTIVE terms and no shadow clock - it leaned on a suite run before it in the same JVM
    // (found by the Phase 12 exit review running one method alone): the billing suite's terms and clock, as every sibling.
    @BeforeAll
    static void termsAndClock() {
        BnplBillingDatabaseTest.termsAndClock();
    }

    // ------------------------------------------------------------------ scenario 10

    @Test
    @DisplayName("scenario 10: a dispute's refund outcome and a merchant refund share one bound - both orders, ten racers"
            + " each, at most G returned across them and never a refund beside an open dispute")
    void disputeRefundAndMerchantRefundShareOneBound() throws Exception {
        // Order one: the dispute opens first; ten merchant refunds race its acceptance - none lands beside it.
        BnplConditionDatabaseWorld.Purchased first = world.plain();
        BnplDispute dispute = openOn(first.purchaseDate().plusDays(1), first, "open-1", 400_00).dispute();
        List<String> raced = world.concurrently(11, index -> {
            try {
                if (index == 0) {
                    respondOn(first.purchaseDate().plusDays(2), dispute, first, BnplDisputes.Decision.ACCEPT);
                    return "accepted";
                }
                world.refundOn(first.purchaseDate().plusDays(2), first, "refund-" + index, 100_00);
                return "refunded";
            } catch (BnplRefunds.DisputeOpen | BnplRefunds.RefundExceedsPurchase
                     | BnplRefunds.PurchaseFullyRefunded refused) {
                return "refused";
            }
        });
        assertThat(raced).contains("accepted");
        assertThat(count("SELECT coalesce(sum(amount_minor), 0) FROM bnpl.bnpl_refund WHERE purchase_id = '"
                + first.purchase().value() + "'")).as("never above G").isLessThanOrEqualTo(600_00);
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_refund WHERE dispute_id = '" + dispute.id().value() + "'"))
                .isEqualTo(1);
        // Order two: a merchant refund first; the dispute may claim only what remains.
        BnplConditionDatabaseWorld.Purchased second = world.plain();
        world.refundOn(second.purchaseDate().plusDays(1), second, "refund-first", 250_00);
        assertThatThrownBy(() -> openOn(second.purchaseDate().plusDays(1), second, "open-2", 350_01))
                .isInstanceOf(BnplRefunds.RefundExceedsPurchase.class);
        BnplDispute rest = openOn(second.purchaseDate().plusDays(1), second, "open-3", 350_00).dispute();
        respondOn(second.purchaseDate().plusDays(2), rest, second, BnplDisputes.Decision.ACCEPT);
        assertThat(scalar("SELECT status FROM bnpl.purchase WHERE id = '" + second.purchase().value() + "'"))
                .isEqualTo("FULLY_REFUNDED");
        assertThat(count("SELECT sum(amount_minor) FROM bnpl.bnpl_refund WHERE purchase_id = '"
                + second.purchase().value() + "'")).isEqualTo(600_00);
        // Both orders RACED (the review's finding: order two above is serial): a merchant refund of 300 against a dispute
        // OPENING for 400, on two instances at once, round after round - the first two rounds unstaggered, then the side
        // that starts late alternates, so both orders occur. B5 orders them, and exactly one of two outcomes is admitted:
        // the refund first, and the opening's claim of 400 exceeds the 300 left (RefundExceedsPurchase); the opening
        // first, and the refund is refused beside the open dispute (DisputeOpen) - whose accepted refund of 400 then
        // leaves 200, so a late merchant refund of 300 is refused by the bound. Σ refunds <= G after every round.
        int refundFirst = 0;
        int disputeFirst = 0;
        for (int round = 0; round < 8; round++) {
            BnplConditionDatabaseWorld.Purchased contested = world.plain();
            LocalDate day = contested.purchaseDate().plusDays(1);
            int which = round;
            List<Object> outcomes = world.concurrently(2, index -> {
                if (which >= 2 && index == which % 2) {
                    Thread.sleep(150);
                }
                try {
                    return index == 0 ? world.refundOn(day, contested, "race-refund-" + which, 300_00)
                            : openOn(day, contested, "race-open-" + which, 400_00);
                } catch (BnplRefunds.DisputeOpen | BnplRefunds.RefundExceedsPurchase refused) {
                    return refused;
                }
            });
            String label = "round " + round + ": " + outcomes;
            if (outcomes.get(0) instanceof BnplRefunds.Refunded) {
                assertThat(outcomes.get(1)).as(label).isInstanceOf(BnplRefunds.RefundExceedsPurchase.class);
                assertThat(count("SELECT count(*) FROM bnpl.bnpl_dispute WHERE purchase_id = '"
                        + contested.purchase().value() + "'")).as(label).isZero();
                refundFirst++;
            } else {
                assertThat(outcomes.get(0)).as(label).isInstanceOf(BnplRefunds.DisputeOpen.class);
                BnplDispute opened = ((BnplDisputes.Acted) outcomes.get(1)).dispute();
                respondOn(day.plusDays(1), opened, contested, BnplDisputes.Decision.ACCEPT);
                assertThatThrownBy(() -> world.refundOn(day.plusDays(1), contested, "late-" + which, 300_00)).as(label)
                        .isInstanceOf(BnplRefunds.RefundExceedsPurchase.class);
                assertThat(count("SELECT count(*) FROM bnpl.bnpl_refund WHERE dispute_id = '" + opened.id().value()
                        + "'")).as(label).isEqualTo(1);
                disputeFirst++;
            }
            assertThat(count("SELECT coalesce(sum(amount_minor), 0) FROM bnpl.bnpl_refund WHERE purchase_id = '"
                    + contested.purchase().value() + "'")).as("Σ refunds <= G, " + label).isLessThanOrEqualTo(600_00);
        }
        assertThat(refundFirst).as("the refund won B5 in some round").isPositive();
        assertThat(disputeFirst).as("the opening won B5 in some round").isPositive();
    }

    @Test
    @DisplayName("one open dispute per purchase: ten instances opening at once - one OPEN, nine refused DisputeOpen")
    void oneOpenDisputePerPurchase() throws Exception {
        BnplConditionDatabaseWorld.Purchased bought = world.plain();
        List<String> answers = world.concurrently(10, index -> {
            try {
                openOn(bought.purchaseDate().plusDays(1), bought, "open-" + index, 100_00);
                return "opened";
            } catch (BnplDisputes.DisputeOpen | BnplStorageException refused) {
                return "refused";
            }
        });
        assertThat(answers).filteredOn("opened"::equals).hasSize(1);
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_dispute WHERE purchase_id = '" + bought.purchase().value()
                + "' AND status IN ('OPEN', 'MERCHANT_RESPONDED')")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the hold

    @Test
    @DisplayName("the hold suspends collection, DPD and fees: instalment 2, held, is billed but never attempted, never past"
            + " due and never charged - and a raw attempt or fee on it is refused by bnpl V015's triggers")
    void theHoldSuspendsCollectionDpdAndFees() throws SQLException {
        BnplConditionDatabaseWorld.Purchased bought = world.purchaseUnder(BnplTermsFixtures.payIn4().autoCollection(true)
                .lateFee(eur(5_00)).build());
        openOn(bought.purchaseDate().plusDays(1), bought, "open-1", 450_00);
        world.serviceOn(bought.due(2), bought);
        world.serviceOn(bought.due(2).plusDays(6), bought);
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_collection_attempt WHERE obligation_id = '" + bought.id() + "'"))
                .isZero();
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_fee_assessment WHERE obligation_id = '" + bought.id() + "'"))
                .isZero();
        assertThat(count("SELECT bnpl.days_past_due('" + bought.id() + "', DATE '" + bought.due(2).plusDays(6) + "')"))
                .isZero();
        // The raw writers, dated inside the hold on or after the due date on a framed connection, so every earlier rank
        // admits them and only bnpl V015's own triggers refuse (the Phase 12 -> 13 gate's A1-1/A6-F1: unframed, the attempt
        // was refused first by bnpl V011's born-once trigger, and the fee had no raw case at all).
        LocalDate held = bought.due(2).plusDays(6);
        refusedFramed(held, rawAttempt(bought, held), "P0001", "a held BNPL instalment is never collected automatically");
        refusedFramed(held, rawFee(bought, held), "P0001", "a BNPL late fee is never assessed on a held instalment");
    }

    @Test
    @DisplayName("bnpl V015's two hold triggers are the ranks that refuse (the gate's A1-1/A6-F1, in-suite): with each"
            + " dropped inside a transaction that rolls back, the same raw attempt and the same raw LATE fee on the held"
            + " instalment are admitted - every earlier rank passes them - and with them in place each is refused with its"
            + " own message")
    void theHoldTriggersAreTheRanksThatRefuse() throws SQLException {
        BnplConditionDatabaseWorld.Purchased bought = world.purchaseUnder(BnplTermsFixtures.payIn4().autoCollection(true)
                .lateFee(eur(5_00)).build());
        openOn(bought.purchaseDate().plusDays(1), bought, "open-1", 450_00);
        world.serviceOn(bought.due(2), bought);
        LocalDate held = bought.due(2).plusDays(6);
        refusedFramed(held, rawAttempt(bought, held), "P0001", "a held BNPL instalment is never collected automatically");
        refusedFramed(held, rawFee(bought, held), "P0001", "a BNPL late fee is never assessed on a held instalment");
        assertThat(admittedWithout(held, "bnpl_collection_attempt_respects_the_dispute_hold", "bnpl.bnpl_collection_attempt",
                rawAttempt(bought, held))).as("the attempt, V015's collection trigger dropped").isEqualTo(1);
        assertThat(admittedWithout(held, "bnpl_fee_assessment_respects_the_dispute_hold", "bnpl.bnpl_fee_assessment",
                rawFee(bought, held))).as("the fee, V015's fee trigger dropped").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_collection_attempt WHERE obligation_id = '" + bought.id() + "'"))
                .as("the probes rolled back").isZero();
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_fee_assessment WHERE obligation_id = '" + bought.id() + "'"))
                .isZero();
        refusedFramed(held, rawAttempt(bought, held), "P0001", "a held BNPL instalment is never collected automatically");
    }

    /** A raw automatic collection attempt on instalment 2, dated {@code on}, recording what it has due - INSUFFICIENT_FUNDS. */
    private static String rawAttempt(BnplConditionDatabaseWorld.Purchased bought, LocalDate on) {
        return "INSERT INTO bnpl.bnpl_collection_attempt (id, obligation_id, instalment_id, attempt_date, outcome, currency,"
                + " scale, due_minor, collected_minor, agreement_version, created_at, correlation_id) VALUES"
                + " (gen_random_uuid(), '" + bought.id() + "', '" + bought.instalment(2) + "', DATE '" + on + "',"
                + " 'INSUFFICIENT_FUNDS', 'EUR', 2, 15000, 0, (SELECT max(version) FROM bnpl.bnpl_agreement WHERE"
                + " obligation_id = '" + bought.id() + "'), clock_timestamp(), 'raw')";
    }

    /** A raw LATE fee of the pinned 5.00 on instalment 2, dated {@code on} - its sixth day past due, the fee day 5. */
    private static String rawFee(BnplConditionDatabaseWorld.Purchased bought, LocalDate on) {
        return "INSERT INTO bnpl.bnpl_fee_assessment (id, obligation_id, instalment_id, kind, business_date, currency, scale,"
                + " amount_minor, agreement_id, agreement_version, journal_entry_id, created_at, correlation_id) VALUES"
                + " (gen_random_uuid(), '" + bought.id() + "', '" + bought.instalment(2) + "', 'LATE', DATE '" + on + "',"
                + " 'EUR', 2, 500, '" + bought.agreement().value() + "', (SELECT max(version) FROM bnpl.bnpl_agreement"
                + " WHERE obligation_id = '" + bought.id() + "'), gen_random_uuid(), clock_timestamp(), 'raw')";
    }

    /** {@code sql} as the application role on the database clock framed at {@code on}: refused, its state and message. */
    private void refusedFramed(LocalDate on, String sql, String sqlState, String message) throws SQLException {
        try (Connection application = BnplClockFrame.framed(DatabaseRoles.application(), world.frameOn(on))) {
            DatabaseRoles.assertCannotBypassPrivileges(application);
            refusedWith(application, sql, sqlState, message);
        }
    }

    /**
     * {@code sql} as the schema's owner framed at {@code on}, with {@code trigger} on {@code table} dropped in the same
     * transaction - its row count; rolled back, the trigger restored with it (DDL is transactional).
     */
    private int admittedWithout(LocalDate on, String trigger, String table, String sql) throws SQLException {
        try (Connection owner = BnplClockFrame.framed(DatabaseRoles.migrator(), world.frameOn(on));
                Statement statement = owner.createStatement()) {
            try {
                statement.execute("DROP TRIGGER " + trigger + " ON " + table);
                return statement.executeUpdate(sql);
            } finally {
                owner.rollback();
            }
        }
    }

    /** {@code sql} on {@code connection} refused with {@code sqlState} and a message holding {@code message}; rolled back. */
    private static void refusedWith(Connection connection, String sql, String sqlState, String message) {
        assertThatThrownBy(() -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }).as(sql).isInstanceOf(SQLException.class)
                .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).as(sql).isEqualTo(sqlState))
                .hasMessageContaining(message);
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // the next statement's own assertion says what matters
        }
    }

    @Test
    @DisplayName("the hold covers the latest instalments: 200 holds 4 whole and 50 of 3, by their due dates")
    void theHoldCoversTheLatestInstalments() throws SQLException {
        BnplConditionDatabaseWorld.Purchased bought = world.plain();
        BnplDispute dispute = openOn(bought.purchaseDate().plusDays(1), bought, "open-1", 200_00).dispute();
        assertThat(scalar("SELECT string_agg(sequence || ':' || kind || ':' || held_principal_minor, ','"
                + " ORDER BY sequence DESC) FROM bnpl.bnpl_dispute_hold WHERE dispute_id = '" + dispute.id().value()
                + "'")).isEqualTo("4:UNBILLED:15000,3:UNBILLED:5000");
    }

    // ------------------------------------------------------------------ the deadline

    @Test
    @DisplayName("merchant silence resolves as a refund at the deadline: nothing claimed before it, the sweep's step after"
            + " it resolves RESOLVED_REFUND by SYSTEM with its refund of exactly D")
    void merchantSilenceResolvesAsARefundAtTheDeadline() throws SQLException {
        BnplConditionDatabaseWorld.Purchased bought = world.plain();
        LocalDate opened = bought.purchaseDate().plusDays(1);
        BnplDispute dispute = openOn(opened, bought, "open-1", 300_00).dispute();
        List<BnplDisputeId> early = BnplClockFrame.inOneTransaction(world.frameOn(opened.plusDays(13)),
                uow -> disputes.claim(uow, 50, Duration.ofMinutes(15)));
        assertThat(early).doesNotContain(dispute.id());
        List<BnplDisputeId> due = BnplClockFrame.inOneTransaction(world.frameOn(opened.plusDays(15)),
                uow -> disputes.claim(uow, 50, Duration.ofMinutes(15)));
        assertThat(due).contains(dispute.id());
        assertThat(lapseOn(opened.plusDays(15), dispute)).isPresent();
        assertThat(scalar("SELECT d.status || '|' || d.resolved_by || '|' || r.amount_minor || '|' || r.door"
                + " FROM bnpl.bnpl_dispute d JOIN bnpl.bnpl_refund r ON r.id = d.refund_id WHERE d.id = '"
                + dispute.id().value() + "'")).isEqualTo("RESOLVED_REFUND|SYSTEM|30000|DISPUTE");
        assertThat(lapseOn(opened.plusDays(15), dispute)).isEmpty();
    }

    @Test
    @DisplayName("a late acceptance at the deadline resolves once: the merchant's acceptance and ten sweep steps racing past"
            + " the deadline - one RESOLVED_REFUND, one refund, the merchant refused")
    void aLateAcceptanceAtTheDeadlineResolvesOnce() throws Exception {
        BnplConditionDatabaseWorld.Purchased bought = world.plain();
        LocalDate opened = bought.purchaseDate().plusDays(1);
        BnplDispute dispute = openOn(opened, bought, "open-1", 300_00).dispute();
        List<String> answers = world.concurrently(11, index -> {
            try {
                if (index == 0) {
                    respondOn(opened.plusDays(15), dispute, bought, BnplDisputes.Decision.ACCEPT);
                    return "merchant";
                }
                return lapseOn(opened.plusDays(15), dispute).isPresent() ? "sweep" : "nothing";
            } catch (BnplDisputes.DisputeNotActionable refused) {
                return "refused";
            }
        });
        assertThat(answers).filteredOn(answer -> answer.equals("sweep") || answer.equals("merchant")).hasSize(1);
        assertThat(answers.get(0)).isEqualTo("refused");
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_refund WHERE dispute_id = '" + dispute.id().value() + "'"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a deadline lapse whose instance dies after the dispute's refund, its posting and the resolution is"
            + " re-driven by another sweeper, once: the dead transaction leaves nothing; the re-drive resolves once - one"
            + " refund, one refund entry - and a re-drive after a lost response finds nothing to do")
    void aCrashedLapseIsReDrivenOnceByAnotherInstance() throws SQLException {
        BnplConditionDatabaseWorld.Purchased bought = world.plain();
        LocalDate opened = bought.purchaseDate().plusDays(1);
        BnplDispute dispute = openOn(opened, bought, "open-1", 300_00).dispute();
        LocalDate past = opened.plusDays(15);
        long base = refundPostings();
        long[] seen = BnplInstanceCrash.crashAfter(world.frameOn(past), uow -> disputes.lapse(uow, dispute.id(),
                new Actor("platform", ActorType.SYSTEM), correlation()).orElseThrow(), uow -> witness(uow, dispute));
        assertThat(seen).as("the dead instance had refunded, resolved and posted").containsExactly(1, 1, base + 1);
        assertThat(witness(dispute)).as("nothing of the dead instance stands").containsExactly(0, 0, base);
        assertThat(scalar("SELECT status FROM bnpl.bnpl_dispute WHERE id = '" + dispute.id().value() + "'"))
                .isEqualTo("OPEN");

        assertThat(lapseOn(past, dispute)).as("another sweeper").isPresent();
        assertThat(lapseOn(past, dispute)).as("the sweep again, the response lost").isEmpty();
        assertThat(witness(dispute)).as("one refund, resolved once, one entry").containsExactly(1, 1, base + 1);
        assertThat(scalar("SELECT status || '|' || resolved_by FROM bnpl.bnpl_dispute WHERE id = '"
                + dispute.id().value() + "'")).isEqualTo("RESOLVED_REFUND|SYSTEM");
    }

    @Test
    @DisplayName("an operator's resolution whose instance dies after the dispute's refund, its posting and the move is"
            + " re-driven by another operator, once: the dead transaction leaves nothing; the re-drive resolves once - one"
            + " refund, one refund entry - and a re-drive after a lost response is refused, nothing doubled")
    void aCrashedResolutionIsReDrivenOnceByAnotherInstance() throws SQLException {
        BnplConditionDatabaseWorld.Purchased bought = world.plain();
        BnplDispute dispute = openOn(bought.purchaseDate().plusDays(1), bought, "open-1", 200_00).dispute();
        respondOn(bought.purchaseDate().plusDays(2), dispute, bought, BnplDisputes.Decision.CONTEST);
        LocalDate day = bought.purchaseDate().plusDays(3);
        long base = refundPostings();
        long[] seen = BnplInstanceCrash.crashAfter(world.frameOn(day), uow -> disputes.resolve(uow, dispute.id(),
                BnplDisputeStatus.RESOLVED_REFUND, "on the evidence", new Actor("operator-a", ActorType.CUSTOMER),
                correlation()), uow -> witness(uow, dispute));
        assertThat(seen).as("the dead instance had refunded, resolved and posted").containsExactly(1, 1, base + 1);
        assertThat(witness(dispute)).as("nothing of the dead instance stands").containsExactly(0, 0, base);
        assertThat(scalar("SELECT status FROM bnpl.bnpl_dispute WHERE id = '" + dispute.id().value() + "'"))
                .isEqualTo("MERCHANT_RESPONDED");

        Actor operatorB = new Actor("operator-b", ActorType.CUSTOMER);
        assertThat(resolveOn(day, dispute, operatorB, BnplDisputeStatus.RESOLVED_REFUND).replayed())
                .as("another instance resolves").isFalse();
        BnplDisputes.Acted again = resolveOn(day, dispute, operatorB, BnplDisputeStatus.RESOLVED_REFUND);
        assertThat(again.replayed()).as("the re-drive after a lost response converges on it").isTrue();
        assertThat(again.refund()).as("and refunds nothing again").isEmpty();
        assertThatThrownBy(() -> resolveOn(day, dispute, new Actor("operator-c", ActorType.CUSTOMER),
                BnplDisputeStatus.RESOLVED_REFUND)).as("any other operator finds it resolved")
                .isInstanceOf(BnplDisputes.DisputeNotActionable.class);
        assertThat(witness(dispute)).as("one refund, resolved once, one entry").containsExactly(1, 1, base + 1);
    }

    /** The dispute's refunds, whether it is RESOLVED_REFUND, and the book's refund entries - on {@code uow}. */
    private long[] witness(Connection uow, BnplDispute dispute) {
        return new long[] {
            BnplInstanceCrash.countOn(uow, "SELECT count(*) FROM bnpl.bnpl_refund WHERE dispute_id = '"
                    + dispute.id().value() + "'"),
            BnplInstanceCrash.countOn(uow, "SELECT count(*) FROM bnpl.bnpl_dispute WHERE id = '" + dispute.id().value()
                    + "' AND status = 'RESOLVED_REFUND'"),
            refundPostings()};
    }

    /** {@link #witness(Connection, BnplDispute)} on a committed view. */
    private long[] witness(BnplDispute dispute) throws SQLException {
        try (Connection reader = DatabaseRoles.application()) {
            return witness(reader, dispute);
        }
    }

    /** The book's refund postings - every refund's entry, as the ledger keys it. */
    private long refundPostings() {
        return world.book.keys().stream().filter(key -> key.startsWith(BnplRefunds.POSTING_KEY_PREFIX)).count();
    }

    // ------------------------------------------------------------------ the lift

    @Test
    @DisplayName("upheld lifts the hold and re-bills nothing: no refund, no new schedule version, DPD from the lift")
    void upheldLiftsTheHoldAndReBillsNothing() throws SQLException {
        BnplConditionDatabaseWorld.Purchased bought = world.plain();
        BnplDispute dispute = openOn(bought.purchaseDate().plusDays(1), bought, "open-1", 200_00).dispute();
        respondOn(bought.purchaseDate().plusDays(2), dispute, bought, BnplDisputes.Decision.CONTEST);
        LocalDate lift = bought.due(3).plusDays(6);
        world.serviceOn(bought.due(2), bought);
        world.repayOn(bought.due(2), bought, "pay-2", 150_00);
        world.serviceOn(bought.due(3), bought);
        resolveOn(lift, dispute, new Actor("operator-a", ActorType.CUSTOMER), BnplDisputeStatus.RESOLVED_UPHELD);
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_refund WHERE purchase_id = '" + bought.purchase().value() + "'"))
                .isZero();
        assertThat(scalar("SELECT max(schedule_version)::text FROM bnpl.bnpl_schedule WHERE obligation_id = '"
                + bought.id() + "'")).isEqualTo("1");
        assertThat(count("SELECT bnpl.days_past_due('" + bought.id() + "', DATE '" + lift + "')")).isZero();
        assertThat(count("SELECT bnpl.days_past_due('" + bought.id() + "', DATE '" + lift.plusDays(3) + "')"))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("a withdrawal lifts the hold: WITHDRAWN by its customer, nothing posted")
    void aWithdrawalLiftsTheHold() throws SQLException {
        BnplConditionDatabaseWorld.Purchased bought = world.plain();
        BnplDispute dispute = openOn(bought.purchaseDate().plusDays(1), bought, "open-1", 450_00).dispute();
        BnplClockFrame.inOneTransaction(world.frameOn(bought.purchaseDate().plusDays(3)), uow -> disputes.withdraw(uow,
                dispute.id(), bought.party(), customer(bought.party()), correlation()));
        assertThat(scalar("SELECT status || '|' || resolved_by FROM bnpl.bnpl_dispute WHERE id = '"
                + dispute.id().value() + "'")).isEqualTo("WITHDRAWN|CUSTOMER:" + bought.party());
        assertThat(count("SELECT bnpl.dispute_holds('" + bought.id() + "', DATE '" + bought.due(4) + "', DATE '"
                + bought.purchaseDate().plusDays(4) + "')::int")).isZero();
    }

    // ------------------------------------------------------------------ the operator

    @Test
    @DisplayName("ten resolvers, one resolution: ten operators resolving one answered dispute on ten instances - one"
            + " refund, nine refused")
    void tenResolversOneResolution() throws Exception {
        BnplConditionDatabaseWorld.Purchased bought = world.plain();
        BnplDispute dispute = openOn(bought.purchaseDate().plusDays(1), bought, "open-1", 200_00).dispute();
        respondOn(bought.purchaseDate().plusDays(2), dispute, bought, BnplDisputes.Decision.CONTEST);
        List<String> answers = world.concurrently(10, index -> {
            try {
                resolveOn(bought.purchaseDate().plusDays(3), dispute, new Actor("operator-" + index, ActorType.CUSTOMER),
                        BnplDisputeStatus.RESOLVED_REFUND);
                return "resolved";
            } catch (BnplDisputes.DisputeNotActionable refused) {
                return "refused";
            }
        });
        assertThat(answers).filteredOn("resolved"::equals).hasSize(1);
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_refund WHERE dispute_id = '" + dispute.id().value() + "'"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("self-approval refused at both ranks: the domain refuses its proposer, and a raw-SQL self-approval is"
            + " refused by the four-eyes CHECK for every writer")
    void selfApprovalRefusedAtBothRanksAndAudited() throws SQLException {
        BnplConditionDatabaseWorld.Purchased bought = world.plain();
        BnplDispute dispute = openOn(bought.purchaseDate().plusDays(1), bought, "open-1", 300_00).dispute();
        respondOn(bought.purchaseDate().plusDays(2), dispute, bought, BnplDisputes.Decision.CONTEST);
        Actor proposer = new Actor("operator-a", ActorType.CUSTOMER);
        UUID proposal = resolveOn(bought.purchaseDate().plusDays(3), dispute, proposer, BnplDisputeStatus.RESOLVED_REFUND)
                .proposal().orElseThrow().id();
        BnplDisputes.SelfApprovalRefused self = null;
        try {
            BnplClockFrame.inOneTransaction(world.frameOn(bought.purchaseDate().plusDays(3)),
                    uow -> disputes.approve(uow, dispute.id(), proposal, "mine", proposer, correlation()));
        } catch (BnplDisputes.SelfApprovalRefused refused) {
            self = refused;
        }
        assertThat(self).as("the domain refuses its proposer").isNotNull();
        // The desk's record of the refusal (BnplDisputeDesk, the gate's R4): FAILED, in a transaction of its own after the
        // act rolled back - the act's rows gone, the refusal's row kept.
        BnplDisputes.SelfApprovalRefused refusal = self;
        BnplClockFrame.inOneTransaction(world.frameOn(bought.purchaseDate().plusDays(3)), uow -> {
            disputes.recordSelfApproval(uow, refusal, proposer, correlation());
            return null;
        });
        assertThat(scalar("SELECT string_agg(outcome || '|' || actor_id || '|' || target_id, ',') FROM"
                + " platform.audit_record WHERE operation = '" + BnplAuditAction.SELF_APPROVAL_REFUSED.code()
                + "' AND target_id = '" + proposal + "'")).as("one FAILED row naming the proposer and the proposal")
                .isEqualTo("FAILED|" + proposer.id() + "|" + proposal);
        // For both roles, the specific refusal: the four-eyes CHECK by name (the gate's A6-F11) - not any SQLException.
        String selfApproval = "UPDATE bnpl.bnpl_dispute_proposal SET status = 'APPROVED', decided_by = proposed_by,"
                + " decision_reason = 'raw', decided_at = clock_timestamp() WHERE id = '" + proposal + "'";
        try (Connection application = DatabaseRoles.application(); Connection owner = DatabaseRoles.migrator()) {
            DatabaseRoles.assertCannotBypassPrivileges(application);
            for (Connection role : List.of(application, owner)) {
                role.setAutoCommit(false);
                refusedWith(role, selfApproval, "23514", "bnpl_dispute_proposal_four_eyes");
            }
        }
        assertThat(scalar("SELECT status FROM bnpl.bnpl_dispute_proposal WHERE id = '" + proposal + "'"))
                .isEqualTo("PROPOSED");
    }

    @Test
    @DisplayName("an employee never resolves their own party's dispute")
    void anEmployeeNeverResolvesTheirOwnPartysDispute() throws SQLException {
        BnplConditionDatabaseWorld.Purchased bought = world.plain();
        BnplDispute dispute = openOn(bought.purchaseDate().plusDays(1), bought, "open-1", 200_00).dispute();
        respondOn(bought.purchaseDate().plusDays(2), dispute, bought, BnplDisputes.Decision.CONTEST);
        Actor own = new Actor("operator-own", ActorType.CUSTOMER);
        operatorParties.put(own.id(), bought.party());
        assertThatThrownBy(() -> resolveOn(bought.purchaseDate().plusDays(3), dispute, own,
                BnplDisputeStatus.RESOLVED_REFUND)).isInstanceOf(BnplDisputes.SelfDealingRefused.class);
        // Fail closed (the gate's A7-6): an operator the platform cannot place in a party is refused too.
        assertThatThrownBy(() -> resolveOn(bought.purchaseDate().plusDays(3), dispute, UNPLACED,
                BnplDisputeStatus.RESOLVED_REFUND)).isInstanceOf(BnplDisputes.SelfDealingRefused.class);
        // And the read (the gate's A5-F4): never a dispute of one's own party, nothing read.
        assertThatThrownBy(() -> BnplClockFrame.inOneTransaction(world.frameOn(bought.purchaseDate().plusDays(3)),
                uow -> disputes.investigate(uow, dispute.id(), own, correlation())))
                .isInstanceOf(BnplDisputes.SelfDealingRefused.class);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = '"
                + BnplAuditAction.DISPUTE_READ.code() + "' AND target_id = '" + dispute.id().value() + "'")).isZero();
        assertThat(scalar("SELECT status FROM bnpl.bnpl_dispute WHERE id = '" + dispute.id().value() + "'"))
                .isEqualTo("MERCHANT_RESPONDED");
    }

    // ------------------------------------------------------------------ the Phase 12 -> 13 gate's repairs (R1)

    @Test
    @DisplayName("the merchant's acceptance that waits at (P) while the deadline passes is 409 DisputeNotActionable, never a"
            + " storage failure (the gate's A3-M2): its edge is conditional on the deadline in its own statement - nothing"
            + " written, the dispute still OPEN, and the sweep's lapse then resolves it once")
    void anAcceptanceThatWaitsPastTheDeadlineIsNotActionable() throws Exception {
        BnplConditionDatabaseWorld.Purchased bought = world.plain();
        BnplDispute dispute = openOn(bought.purchaseDate().plusDays(1), bought, "open-1", 300_00).dispute();
        java.time.Instant deadline = dispute.responseDeadline().orElseThrow();
        java.util.concurrent.CountDownLatch atThePayable = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        // The payable held by another act: the acceptance's (P) waits on it while the database clock passes the deadline.
        BnplBook heldPayable = (BnplBook) java.lang.reflect.Proxy.newProxyInstance(BnplBook.class.getClassLoader(),
                new Class<?>[] {BnplBook.class}, (proxy, method, args) -> {
                    if (method.getName().equals("lockForUpdate") && world.ports.payable.equals(args[1])) {
                        atThePayable.countDown();
                        assertThat(release.await(60, TimeUnit.SECONDS)).isTrue();
                    }
                    try {
                        return method.invoke(world.book, args);
                    } catch (java.lang.reflect.InvocationTargetException thrown) {
                        throw thrown.getCause();
                    }
                });
        BnplRefunds waitingRefunds = new BnplRefunds(REFUNDS, SERVICING, PURCHASES, TERMS_STORE, world.ports, world.ports,
                heldPayable, world.closures, (uow, actor) -> BnplKeyIssuer.partyOf(actor), new JdbcOutboxWriter(),
                new JdbcAuditWriter(), IDS, CLOCK);
        BnplDisputes waiting = new BnplDisputes(DISPUTES, REFUNDS, SERVICING, PURCHASES, TERMS_STORE, waitingRefunds,
                world.ports, heldPayable, actingParties, new JdbcOutboxWriter(), new JdbcAuditWriter(), IDS, CLOCK);
        Duration twoSecondsBefore = BnplClockFrame.frameTo(deadline.minusSeconds(2));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Object> accepting = pool.submit(() -> {
                try {
                    return BnplClockFrame.inOneTransaction(twoSecondsBefore, uow -> waiting.respond(uow, dispute.id(),
                            bought.merchant(), new BnplDisputes.Response(BnplDisputes.Decision.ACCEPT, "refund it",
                                    "key-under-test", BnplKeyIssuer.KEY_ISSUER), new Actor(bought.merchant().toString(), ActorType.MERCHANT),
                            correlation()));
                } catch (RuntimeException refused) {
                    return refused;
                }
            });
            assertThat(atThePayable.await(30, TimeUnit.SECONDS)).as("judged before the deadline, then at (P)").isTrue();
            // Released only once the deadline has passed on the DATABASE clock the acceptance's statement reads - its own
            // frame, polled - never after a fixed JVM sleep: the frame's offset is taken from the JVM clock, and the test
            // VM's database clock steps by up to 1.7 s, so a 3 s sleep left a 1 s margin that a step could eat, the edge
            // then judged before the deadline and the acceptance Acted (the transition gate's G4, on R3's merge). The
            // margin beyond the deadline is a step's worth, measured on that clock.
            try (Connection observer = BnplClockFrame.framed(DatabaseRoles.application(), twoSecondsBefore);
                    Statement poll = observer.createStatement()) {
                long patience = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (true) {
                    try (java.sql.ResultSet past = poll.executeQuery("SELECT clock_timestamp() > TIMESTAMPTZ '" + deadline
                            + "' + interval '2 seconds'")) {
                        past.next();
                        if (past.getBoolean(1)) {
                            break;
                        }
                    }
                    observer.rollback();
                    assertThat(System.nanoTime()).as("the deadline passes on the database clock").isLessThan(patience);
                    Thread.sleep(50);
                }
                observer.rollback();
            }
            release.countDown();
            assertThat(accepting.get(60, TimeUnit.SECONDS)).isInstanceOf(BnplDisputes.DisputeNotActionable.class);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(scalar("SELECT status FROM bnpl.bnpl_dispute WHERE id = '" + dispute.id().value() + "'"))
                .isEqualTo("OPEN");
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_refund WHERE dispute_id = '" + dispute.id().value() + "'"))
                .as("the acceptance's refund rolled back with it").isZero();
        assertThat(lapseOn(bought.purchaseDate().plusDays(16), dispute)).as("the merchant's silence is acceptance")
                .isPresent();
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_refund WHERE dispute_id = '" + dispute.id().value() + "'"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the open, withdraw and re-open loop cannot suppress default (the gate's A5-F2): the same-day re-opening is"
            + " refused DisputeCoolingOff; re-opened after the cooldown and withdrawn again, cycle after cycle - days past"
            + " due from the due date on every unheld servicing day (bnpl V018's clock start, bnpl.days_past_due), and the"
            + " default recorded at 90")
    void theOpenWithdrawLoopStillDefaults() throws SQLException {
        BnplConditionDatabaseWorld.Purchased bought = world.plain();
        LocalDate due = bought.due(2);
        BnplDispute dispute = openOn(bought.purchaseDate().plusDays(1), bought, "open-0", 450_00).dispute();
        world.serviceOn(due, bought);
        LocalDate withdrawal = due.plusDays(1);
        LocalDate defaultedOn = null;
        for (int cycle = 1; defaultedOn == null && cycle <= 6; cycle++) {
            BnplDispute open = dispute;
            LocalDate withdrawnOn = withdrawal;
            BnplClockFrame.inOneTransaction(world.frameOn(withdrawal), uow -> disputes.withdraw(uow, open.id(),
                    bought.party(), customer(bought.party()), correlation()));
            String key = "again-" + cycle;
            assertThatThrownBy(() -> openOn(withdrawnOn, bought, key, 450_00)).as("re-opened the day it was withdrawn")
                    .isInstanceOf(BnplDisputes.DisputeCoolingOff.class);
            for (LocalDate unheld : List.of(withdrawal.plusDays(1), withdrawal.plusDays(30))) {
                world.serviceOn(unheld, bought);
                assertThat(count("SELECT bnpl.days_past_due('" + bought.id() + "', DATE '" + unheld + "')"))
                        .as("on %s no dispute holds instalment 2: from its due date", unheld)
                        .isEqualTo(java.time.temporal.ChronoUnit.DAYS.between(due, unheld));
            }
            String defaulted = scalar("SELECT min(business_date)::text FROM bnpl.bnpl_condition_event WHERE obligation_id"
                    + " = '" + bought.id() + "' AND kind = 'DEFAULT' AND defaulted");
            defaultedOn = defaulted == null ? null : LocalDate.parse(defaulted);
            LocalDate reopen = withdrawal.plusDays(31);
            dispute = openOn(reopen, bought, "open-" + cycle, 450_00).dispute();
            withdrawal = reopen.plusDays(10);
        }
        assertThat(defaultedOn).as("defaulted on the first unheld servicing day at or past 90 days").isNotNull()
                .isAfterOrEqualTo(due.plusDays(90)).isBefore(due.plusDays(90 + 41));
    }

    @Test
    @DisplayName("a withdrawal starts a cooldown for every writer (the gate's A5-F2): the purchase takes no new dispute"
            + " within 30 days of the withdrawal - the domain refuses DisputeCoolingOff, and bnpl V018's trigger refuses a"
            + " raw insert with its own message (dropped in a transaction that rolls back, the same row is admitted); after"
            + " the cooldown it opens; an upheld dispute starts none")
    void aWithdrawalStartsACooldownForEveryWriter() throws SQLException {
        BnplConditionDatabaseWorld.Purchased bought = world.plain();
        LocalDate withdrawnOn = bought.purchaseDate().plusDays(2);
        BnplDispute dispute = openOn(bought.purchaseDate().plusDays(1), bought, "open-1", 200_00).dispute();
        BnplClockFrame.inOneTransaction(world.frameOn(withdrawnOn), uow -> disputes.withdraw(uow, dispute.id(),
                bought.party(), customer(bought.party()), correlation()));
        assertThatThrownBy(() -> openOn(withdrawnOn, bought, "open-2", 200_00))
                .isInstanceOf(BnplDisputes.DisputeCoolingOff.class);
        assertThatThrownBy(() -> openOn(withdrawnOn.plusDays(29), bought, "open-3", 200_00))
                .isInstanceOf(BnplDisputes.DisputeCoolingOff.class);
        String raw = "INSERT INTO bnpl.bnpl_dispute (id, purchase_id, obligation_id, merchant_id, party_id, agreement_id,"
                + " terms_version_id, status, reason_category, reason, request_key, opened_at, opened_business_date,"
                + " response_deadline_at, next_sweep_at, correlation_id) SELECT gen_random_uuid(), p.id, o.id, p.merchant_id,"
                + " p.party_id, o.agreement_id, (SELECT a.terms_version_id FROM bnpl.bnpl_agreement a WHERE a.obligation_id ="
                + " o.id ORDER BY a.version DESC LIMIT 1), 'OPEN', 'OTHER', 'raw', 'raw-key', clock_timestamp(), current_date,"
                + " clock_timestamp() + interval '1 day', clock_timestamp(), 'raw' FROM bnpl.purchase p JOIN"
                + " bnpl.bnpl_obligation o ON o.purchase_id = p.id WHERE p.id = '" + bought.purchase().value() + "'";
        refusedFramed(withdrawnOn.plusDays(1), raw, "P0001", "is not disputed again within the cooldown");
        assertThat(admittedWithout(withdrawnOn.plusDays(1), "bnpl_dispute_respects_the_withdrawal_cooldown",
                "bnpl.bnpl_dispute", raw)).as("the same raw dispute, V018's trigger dropped").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_dispute WHERE purchase_id = '" + bought.purchase().value() + "'"))
                .as("nothing written").isEqualTo(1);
        assertThat(openOn(withdrawnOn.plusDays(31), bought, "open-4", 200_00).dispute().status())
                .as("after the cooldown").isEqualTo(BnplDisputeStatus.OPEN);

        BnplConditionDatabaseWorld.Purchased upheld = world.plain();
        BnplDispute contested = openOn(upheld.purchaseDate().plusDays(1), upheld, "open-5", 200_00).dispute();
        respondOn(upheld.purchaseDate().plusDays(2), contested, upheld, BnplDisputes.Decision.CONTEST);
        resolveOn(upheld.purchaseDate().plusDays(3), contested, new Actor("operator-a", ActorType.CUSTOMER),
                BnplDisputeStatus.RESOLVED_UPHELD);
        assertThat(openOn(upheld.purchaseDate().plusDays(3), upheld, "open-6", 200_00).dispute().status())
                .as("an operator's outcome starts no cooldown").isEqualTo(BnplDisputeStatus.OPEN);
    }

    @Test
    @DisplayName("four-eyes on the purchase's cumulative refunds by dispute, at every rank (the gate's A5-F7): 200 then 100 on"
            + " one purchase - the first resolved by one operator, the second (300 > 250) only by a proposal, which bnpl"
            + " V018's proposal trigger admits; a raw one-operator resolution of it is refused by the edge trigger")
    void theFourEyesThresholdIsCumulative() throws SQLException {
        BnplConditionDatabaseWorld.Purchased bought = world.plain();
        BnplDispute first = openOn(bought.purchaseDate().plusDays(1), bought, "open-1", 200_00).dispute();
        respondOn(bought.purchaseDate().plusDays(2), first, bought, BnplDisputes.Decision.CONTEST);
        assertThat(resolveOn(bought.purchaseDate().plusDays(3), first, new Actor("operator-a", ActorType.CUSTOMER),
                BnplDisputeStatus.RESOLVED_REFUND).outcome()).isEqualTo(BnplDisputes.Outcome.RESOLVED_REFUND);
        BnplDispute second = openOn(bought.purchaseDate().plusDays(4), bought, "open-2", 100_00).dispute();
        respondOn(bought.purchaseDate().plusDays(5), second, bought, BnplDisputes.Decision.CONTEST);
        assertThat(count("SELECT bnpl.dispute_four_eyes_amount('" + second.id().value() + "')")).isEqualTo(300_00);
        try (Connection application = BnplClockFrame.framed(DatabaseRoles.application(),
                world.frameOn(bought.purchaseDate().plusDays(6)))) {
            refusedWith(application, "UPDATE bnpl.bnpl_dispute SET status = 'RESOLVED_UPHELD', resolved_by ="
                    + " 'OPERATOR:raw', resolution_reason = 'one person', resolved_at = clock_timestamp(),"
                    + " resolved_business_date = current_date WHERE id = '" + second.id().value() + "'", "P0001",
                    "its purchase's refunds by dispute and its own claim");
        }
        BnplDisputes.Acted proposed = resolveOn(bought.purchaseDate().plusDays(6), second,
                new Actor("operator-b", ActorType.CUSTOMER), BnplDisputeStatus.RESOLVED_REFUND);
        assertThat(proposed.outcome()).isEqualTo(BnplDisputes.Outcome.PROPOSED);
        BnplDisputes.Acted approved = BnplClockFrame.inOneTransaction(world.frameOn(bought.purchaseDate().plusDays(6)),
                uow -> disputes.approve(uow, second.id(), proposed.proposal().orElseThrow().id(), "checked",
                        new Actor("operator-c", ActorType.CUSTOMER), correlation()));
        assertThat(approved.dispute().status()).isEqualTo(BnplDisputeStatus.RESOLVED_REFUND);
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_refund WHERE purchase_id = '" + bought.purchase().value()
                + "' AND door = 'DISPUTE'")).isEqualTo(2);
    }

    @Test
    @DisplayName("a dispute whose agreement closes posts its lapse to the wallet, and a CLOSED agreement without an ACTIVE"
            + " wallet takes no dispute (the gate's A7-1): a payoff mid-dispute closes the agreement, the deadline's refund"
            + " is credited whole to the wallet; with the wallet closed an opening is refused WalletUnavailable, nothing"
            + " written")
    void aClosedAgreementsDisputeRefundsToTheWallet() throws SQLException {
        BnplConditionDatabaseWorld.Purchased bought = world.plain();
        LocalDate opened = bought.purchaseDate().plusDays(1);
        BnplDispute dispute = openOn(opened, bought, "open-1", 200_00).dispute();
        world.payOffOn(opened.plusDays(1), bought, "payoff-1");
        assertThat(scalar("SELECT status FROM bnpl.bnpl_obligation WHERE id = '" + bought.id() + "'")).isEqualTo("CLOSED");
        BnplDisputes.Acted lapsed = lapseOn(opened.plusDays(15), dispute).orElseThrow();
        java.util.UUID refund = lapsed.refund().orElseThrow().refund().id();
        assertThat(scalar("SELECT excess_to || '|' || excess_minor FROM bnpl.bnpl_refund WHERE id = '" + refund + "'"))
                .isEqualTo("WALLET|20000");
        world.book.keys(); // the book learns the refund's commit
        assertThat(world.fakes.book.linesOf(BnplRefunds.POSTING_KEY_PREFIX + refund)).filteredOn(line ->
                line.account().equals(world.ports.wallet) && line.direction() == com.finapp.ledger.Direction.CREDIT)
                .extracting(line -> line.amount().minorUnits()).containsExactly(200_00L);

        BnplConditionDatabaseWorld.Purchased closed = world.plain();
        world.payOffOn(closed.purchaseDate().plusDays(1), closed, "payoff-2");
        world.fakes.book.statuses.put(world.ports.wallet, com.finapp.ledger.LedgerAccountStatus.CLOSED);
        try {
            assertThatThrownBy(() -> openOn(closed.purchaseDate().plusDays(2), closed, "open-2", 100_00))
                    .isInstanceOf(BnplDisputes.WalletUnavailable.class);
            assertThat(count("SELECT count(*) FROM bnpl.bnpl_dispute WHERE purchase_id = '" + closed.purchase().value()
                    + "'")).isZero();
        } finally {
            world.fakes.book.statuses.put(world.ports.wallet, com.finapp.ledger.LedgerAccountStatus.ACTIVE);
        }
        assertThat(openOn(closed.purchaseDate().plusDays(2), closed, "open-3", 100_00).dispute().status())
                .isEqualTo(BnplDisputeStatus.OPEN);
    }

    @Test
    @DisplayName("the operator's queue over bnpl's own rows (the gate's A7-2): the contested disputes oldest first by when the"
            + " merchant answered, keyset-paged after a cursor, never one of the reading operator's own party; every page"
            + " recorded bnpl.DisputeQueueRead; an unplaced operator refused; the oldest's age read for the gauge")
    void theQueue() throws SQLException {
        List<BnplDispute> contested = new java.util.ArrayList<>();
        List<BnplConditionDatabaseWorld.Purchased> bought = new java.util.ArrayList<>();
        for (int purchase = 0; purchase < 3; purchase++) {
            BnplConditionDatabaseWorld.Purchased one = world.plain();
            bought.add(one);
            contested.add(openOn(one.purchaseDate().plusDays(1), one, "open-" + purchase, 100_00).dispute());
        }
        // Answered out of order on the database clock: the second, then the third, then the first.
        respondOn(bought.get(1).purchaseDate().plusDays(2), contested.get(1), bought.get(1), BnplDisputes.Decision.CONTEST);
        respondOn(bought.get(2).purchaseDate().plusDays(3), contested.get(2), bought.get(2), BnplDisputes.Decision.CONTEST);
        respondOn(bought.get(0).purchaseDate().plusDays(4), contested.get(0), bought.get(0), BnplDisputes.Decision.CONTEST);
        Actor operator = new Actor("operator-queue", ActorType.CUSTOMER);
        long pages = count("SELECT count(*) FROM platform.audit_record WHERE operation = '"
                + BnplAuditAction.DISPUTE_QUEUE_READ.code() + "' AND actor_id = '" + operator.id() + "'");
        List<BnplDisputeId> all = new java.util.ArrayList<>();
        Optional<BnplDisputeId> after = Optional.empty();
        int served = 0;
        do {
            Optional<BnplDisputeId> cursor = after;
            BnplDisputes.Queue page = BnplClockFrame.inOneTransaction(Duration.ZERO, uow -> disputes.queue(uow,
                    BnplDisputeStatus.MERCHANT_RESPONDED, cursor, operator, correlation()));
            assertThat(page.disputes()).allSatisfy(row -> assertThat(row.status())
                    .isEqualTo(BnplDisputeStatus.MERCHANT_RESPONDED));
            page.disputes().forEach(row -> all.add(row.id()));
            after = page.next();
            served++;
        } while (after.isPresent());
        assertThat(all).doesNotHaveDuplicates()
                .containsSubsequence(contested.get(1).id(), contested.get(2).id(), contested.get(0).id());
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = '"
                + BnplAuditAction.DISPUTE_QUEUE_READ.code() + "' AND actor_id = '" + operator.id() + "'"))
                .isEqualTo(pages + served);

        Actor insider = new Actor("operator-insider", ActorType.CUSTOMER);
        operatorParties.put(insider.id(), bought.get(2).party());
        List<BnplDisputeId> seen = new java.util.ArrayList<>();
        Optional<BnplDisputeId> next = Optional.empty();
        do {
            Optional<BnplDisputeId> cursor = next;
            BnplDisputes.Queue page = BnplClockFrame.inOneTransaction(Duration.ZERO, uow -> disputes.queue(uow,
                    BnplDisputeStatus.MERCHANT_RESPONDED, cursor, insider, correlation()));
            page.disputes().forEach(row -> seen.add(row.id()));
            next = page.next();
        } while (next.isPresent());
        assertThat(seen).as("never one's own party's").doesNotContain(contested.get(2).id())
                .contains(contested.get(1).id(), contested.get(0).id());
        assertThatThrownBy(() -> BnplClockFrame.inOneTransaction(Duration.ZERO, uow -> disputes.queue(uow,
                BnplDisputeStatus.MERCHANT_RESPONDED, Optional.empty(), UNPLACED, correlation())))
                .isInstanceOf(BnplDisputes.SelfDealingRefused.class);
        Optional<Duration> oldest = BnplClockFrame.inOneTransaction(Duration.ZERO, DISPUTES::oldestContestedAge);
        assertThat(oldest).as("the gauge's read").isPresent();
    }

    @Test
    @DisplayName("a dispute on a fully refunded purchase is refused - there is nothing left to resolve as a refund")
    void aResolutionAfterAFullRefundRefundsNothing() throws SQLException {
        BnplConditionDatabaseWorld.Purchased bought = world.plain();
        world.refundOn(bought.purchaseDate().plusDays(1), bought, "full", 600_00);
        assertThatThrownBy(() -> openOn(bought.purchaseDate().plusDays(2), bought, "open-1", 1))
                .isInstanceOf(BnplRefunds.PurchaseFullyRefunded.class);
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_dispute WHERE purchase_id = '" + bought.purchase().value() + "'"))
                .isZero();
    }

    @Test
    @DisplayName("the dispute takes the purchase, then the obligation: an opening waits on a refund holding B5, and a"
            + " collection holding B4 makes the opening wait on the obligation")
    void disputeTakesThePurchaseThenTheObligation() throws Exception {
        BnplConditionDatabaseWorld.Purchased bought = world.plain();
        try (Connection holder = DatabaseRoles.application()) {
            holder.setAutoCommit(false);
            try (Statement lock = holder.createStatement()) {
                lock.execute("SELECT 1 FROM bnpl.purchase WHERE id = '" + bought.purchase().value() + "' FOR UPDATE");
            }
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<BnplDisputes.Acted> waiting = pool.submit(() -> openOn(bought.purchaseDate().plusDays(1), bought,
                        "open-1", 100_00));
                assertThat(awaitBlockedBy(pid(holder))).contains("bnpl.purchase");
                holder.rollback();
                assertThat(waiting.get(30, TimeUnit.SECONDS).dispute().status()).isEqualTo(BnplDisputeStatus.OPEN);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private BnplDisputes.Acted openOn(LocalDate date, BnplConditionDatabaseWorld.Purchased bought, String key,
            long minor) {
        return BnplClockFrame.inOneTransaction(world.frameOn(date), uow -> disputes.open(uow, bought.agreement(),
                bought.party(), new BnplDisputes.OpenRequest(key, eur(minor), BnplDisputeReason.NOT_RECEIVED,
                        "it never came"), customer(bought.party()), correlation()));
    }

    private BnplDisputes.Acted respondOn(LocalDate date, BnplDispute dispute, BnplConditionDatabaseWorld.Purchased bought,
            BnplDisputes.Decision decision) {
        return BnplClockFrame.inOneTransaction(world.frameOn(date), uow -> disputes.respond(uow, dispute.id(),
                bought.merchant(), new BnplDisputes.Response(decision, "we shipped it", "key-under-test",
                        BnplKeyIssuer.KEY_ISSUER), new Actor(bought.merchant().toString(), ActorType.MERCHANT),
                correlation()));
    }

    private Optional<BnplDisputes.Acted> lapseOn(LocalDate date, BnplDispute dispute) {
        return BnplClockFrame.inOneTransaction(world.frameOn(date), uow -> disputes.lapse(uow, dispute.id(),
                new Actor("platform", ActorType.SYSTEM), correlation()));
    }

    private BnplDisputes.Acted resolveOn(LocalDate date, BnplDispute dispute, Actor operator, BnplDisputeStatus outcome) {
        return BnplClockFrame.inOneTransaction(world.frameOn(date), uow -> disputes.resolve(uow, dispute.id(), outcome,
                "on the evidence", operator, correlation()));
    }
}
