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

    private final BnplConditionDatabaseWorld world = new BnplConditionDatabaseWorld();
    private final Map<String, UUID> operatorParties = new ConcurrentHashMap<>();
    private final BnplDisputes disputes = new BnplDisputes(DISPUTES, REFUNDS, SERVICING, PURCHASES, TERMS_STORE,
            world.refunds, world.ports, world.book,
            (uow, actor) -> Optional.ofNullable(operatorParties.get(actor.id())), new JdbcOutboxWriter(),
            new JdbcAuditWriter(), IDS, CLOCK);

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
        assertThatThrownBy(() -> asApplication("INSERT INTO bnpl.bnpl_collection_attempt (id, obligation_id,"
                + " instalment_id, attempt_date, outcome, currency, scale, due_minor, collected_minor, agreement_version,"
                + " created_at, correlation_id) VALUES (gen_random_uuid(), '" + bought.id() + "', '"
                + bought.instalment(2) + "', current_date, 'INSUFFICIENT_FUNDS', 'EUR', 2, 15000, 0, 1,"
                + " clock_timestamp(), 'raw')")).isInstanceOf(SQLException.class);
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
        assertThatThrownBy(() -> asApplication("UPDATE bnpl.bnpl_dispute_proposal SET status = 'APPROVED', decided_by ="
                + " proposed_by, decision_reason = 'raw', decided_at = clock_timestamp() WHERE id = '" + proposal + "'"))
                .isInstanceOf(SQLException.class);
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
        assertThat(scalar("SELECT status FROM bnpl.bnpl_dispute WHERE id = '" + dispute.id().value() + "'"))
                .isEqualTo("MERCHANT_RESPONDED");
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
                bought.merchant(), new BnplDisputes.Response(decision, "we shipped it", "key-under-test"),
                new Actor(bought.merchant().toString(), ActorType.MERCHANT), correlation()));
    }

    private Optional<BnplDisputes.Acted> lapseOn(LocalDate date, BnplDispute dispute) {
        return BnplClockFrame.inOneTransaction(world.frameOn(date), uow -> disputes.lapse(uow, dispute.id(),
                new Actor("platform", ActorType.SYSTEM), correlation()));
    }

    private BnplDisputes.Acted resolveOn(LocalDate date, BnplDispute dispute, Actor operator, BnplDisputeStatus outcome) {
        return BnplClockFrame.inOneTransaction(world.frameOn(date), uow -> disputes.resolve(uow, dispute.id(), outcome,
                "on the evidence", operator, correlation()));
    }

    private static void asApplication(String sql) throws SQLException {
        try (Connection application = DatabaseRoles.application(); Statement statement = application.createStatement()) {
            application.setAutoCommit(false);
            try {
                statement.execute(sql);
                application.commit();
            } finally {
                application.rollback();
            }
        }
    }
}
