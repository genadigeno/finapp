package com.finapp.app.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.merchant.IllegalPayoutDestinationTransitionException;
import com.finapp.merchant.MerchantId;
import com.finapp.merchant.PayoutDestination;
import com.finapp.merchant.PayoutDestinationChangePendingException;
import com.finapp.merchant.PayoutDestinationEffectuation;
import com.finapp.merchant.PayoutDestinationId;
import com.finapp.merchant.PayoutDestinationReference;
import com.finapp.merchant.PayoutDestinationStatus;
import com.finapp.merchant.PayoutDestinationStore;
import com.finapp.merchant.PayoutDestinationTokenisation;
import com.finapp.merchant.PayoutDestinations;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The payout destination flow against the real schema (`P6-TSK-011`, ADR-0056,
 * {@code INV-AUD-04}): the self-approval refused <em>and audited</em>, the cooling-off holding
 * the prior destination in place for a dispatch, the three races counted to one effective row,
 * the withdrawal that gives the cooling-off its teeth, and `V006` refusing — for raw SQL — what
 * the domain refuses. The HTTP surface is {@code PayoutDestinationEndpointDatabaseTest}'s.
 *
 * <p>The cooling-off is the context's default (72 hours) and the sweeps run on offset clocks, so
 * "during" and "after" are exact rather than waited for.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest
@DisplayName("the payout destination flow against the real schema (P6-TSK-011)")
class PayoutDestinationDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final int RACERS = 10;

    @Autowired private PayoutDestinations destinations;
    @Autowired private PayoutDestinationStore<Connection> store;
    @Autowired private AuditWriter<Connection> auditWriter;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataSource dataSource;
    @Autowired private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    // -----------------------------------------------------------------
    // Four-eyes
    // -----------------------------------------------------------------

    @Test
    @DisplayName("approve-by-proposer is refused, the refusal is audited, and nothing else moves")
    void approveByProposerIsRefusedAndAudited() throws Exception {
        MerchantId merchant = merchant();
        Actor proposer = person();
        PayoutDestinationId proposed = propose(merchant, proposer, "pdr_self1");

        PayoutDestinations.Approval outcome =
                as(proposer, uow -> destinations.approve(uow, merchant, proposed, "mine to approve"));

        assertThat(outcome).isInstanceOf(PayoutDestinations.SelfApprovalRefused.class);
        assertThat(statusOf(proposed)).isEqualTo("PROPOSED");
        assertThat(count(
                        "SELECT count(*) FROM platform.audit_record WHERE operation ="
                                + " 'merchant.PayoutDestinationApprovalRefused' AND target_id = ?"
                                + " AND outcome = 'DENIED' AND actor_id = ? AND reason = ?",
                        proposed.value().toString(),
                        proposer.id(),
                        "mine to approve"))
                .as("the refusal committed its own evidence (INV-AUD-03)")
                .isEqualTo(1);
        assertThat(count(
                        "SELECT count(*) FROM merchant.payout_destination_event WHERE"
                                + " payout_destination_id = ?",
                        proposed.value()))
                .as("no transition was recorded")
                .isZero();

        // The schema refuses the same thing for a writer that never ran the domain code. The
        // approval is stamped from the test's clock, the one that stamped the proposal: a
        // database now() trailing it also broke approved_at >= proposed_at, a second 23514
        // under which this refusal passed whatever the distinctness CHECK did (X-TSK-005).
        OffsetDateTime approvedAt = testClockNow();
        assertThatThrownBy(
                        () ->
                                raw(
                                        "UPDATE merchant.payout_destination SET status ="
                                                + " 'APPROVED', approved_by = proposed_by,"
                                                + " approved_at = ?, cooling_off_until = ?"
                                                + " WHERE id = ?",
                                        approvedAt,
                                        approvedAt.plusHours(72),
                                        proposed.value()))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23514"));
        assertThat(statusOf(proposed)).isEqualTo("PROPOSED");
    }

    @Test
    @DisplayName("a second operator's approval starts the cooling-off; their retry converges")
    void aSecondOperatorApproves() throws Exception {
        MerchantId merchant = merchant();
        PayoutDestinationId proposed = propose(merchant, person(), "pdr_second1");
        Actor approver = person();

        PayoutDestinations.Approval first =
                as(approver, uow -> destinations.approve(uow, merchant, proposed, "verified"));
        PayoutDestinations.Approval retry =
                as(approver, uow -> destinations.approve(uow, merchant, proposed, "verified"));

        assertThat(first).isInstanceOfSatisfying(
                PayoutDestinations.Approved.class, a -> assertThat(a.replayed()).isFalse());
        assertThat(retry).isInstanceOfSatisfying(
                PayoutDestinations.Approved.class, a -> assertThat(a.replayed()).isTrue());
        PayoutDestination approved = ((PayoutDestinations.Approved) first).destination();
        assertThat(approved.coolingOffUntil())
                .contains(approved.approvedAt().orElseThrow().plus(Duration.ofHours(72)));
        assertThat(count(
                        "SELECT count(*) FROM platform.audit_record WHERE operation ="
                                + " 'merchant.PayoutDestinationApproved' AND target_id = ?",
                        proposed.value().toString()))
                .as("one act, however many times it was asked for")
                .isEqualTo(1);
    }

    // -----------------------------------------------------------------
    // The cooling-off
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a dispatch during the cooling-off reads the prior destination; after it, the new one")
    void aDispatchDuringTheCoolingOffUsesThePriorDestination() throws Exception {
        MerchantId merchant = merchant();
        PayoutDestinationId first = effectiveDestination(merchant, "pdr_prior1");

        PayoutDestinationId second = propose(merchant, person(), "pdr_next1");
        approve(merchant, second, person());

        // The dispatch's read, in its own transaction - during the cooling-off.
        assertThat(effectiveIdOf(merchant)).contains(first);
        // A sweep one hour into the cooling-off changes nothing.
        sweepAt(Duration.ofHours(1));
        assertThat(effectiveIdOf(merchant)).contains(first);
        assertThat(statusOf(second)).isEqualTo("APPROVED");

        // Past the deadline the platform effects the new one and supersedes the old, together.
        sweepAt(Duration.ofHours(73));
        assertThat(effectiveIdOf(merchant)).contains(second);
        assertThat(statusOf(first)).isEqualTo("SUPERSEDED");
        assertThat(statusOf(second)).isEqualTo("EFFECTIVE");
        assertThat(count(
                        "SELECT count(*) FROM platform.audit_record WHERE operation ="
                                + " 'merchant.PayoutDestinationEffective' AND target_id = ?"
                                + " AND change_summary LIKE ?",
                        second.value().toString(),
                        "%superseded=" + first + "%"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a withdrawal during the cooling-off stops the change: the sweep never effects it")
    void aWithdrawalDuringTheCoolingOffStopsTheChange() throws Exception {
        MerchantId merchant = merchant();
        PayoutDestinationId first = effectiveDestination(merchant, "pdr_keep1");
        PayoutDestinationId second = propose(merchant, person(), "pdr_stop1");
        approve(merchant, second, person());

        PayoutDestination withdrawn =
                as(person(), uow -> destinations.withdraw(uow, merchant, second, "not the merchant's"));
        assertThat(withdrawn.status()).isEqualTo(PayoutDestinationStatus.WITHDRAWN);

        sweepAt(Duration.ofHours(73));
        assertThat(statusOf(second)).isEqualTo("WITHDRAWN");
        assertThat(effectiveIdOf(merchant)).contains(first);
        // And the merchant is free to propose again: the one-open slot was released.
        assertThat(propose(merchant, person(), "pdr_again1")).isNotNull();
    }

    @Test
    @DisplayName("the schema refuses an effect before the cooling-off, for every writer")
    void theSchemaRefusesAnEarlyEffect() throws Exception {
        MerchantId merchant = merchant();
        PayoutDestinationId proposed = propose(merchant, person(), "pdr_early1");
        approve(merchant, proposed, person());
        assertThatThrownBy(
                        () ->
                                raw(
                                        "UPDATE merchant.payout_destination SET status ="
                                                + " 'EFFECTIVE', effective_at = now() WHERE id = ?",
                                        proposed.value()))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23514"));
        assertThat(statusOf(proposed)).isEqualTo("APPROVED");
    }

    // -----------------------------------------------------------------
    // The races, counted
    // -----------------------------------------------------------------

    @Test
    @DisplayName("ten concurrent proposals leave exactly one open change")
    void concurrentProposalsLeaveOneOpenChange() throws Exception {
        MerchantId merchant = merchant();
        Actor proposer = person();
        List<Callable<Object>> racers = new ArrayList<>();
        for (int i = 0; i < RACERS; i++) {
            String reference = "pdr_race" + i;
            racers.add(() -> proposeOrRefusal(merchant, proposer, reference));
        }
        List<Object> outcomes = race(racers);

        assertThat(outcomes.stream().filter(PayoutDestinationId.class::isInstance)).hasSize(1);
        assertThat(outcomes.stream().filter(PayoutDestinationChangePendingException.class::isInstance))
                .hasSize(RACERS - 1);
        assertThat(count(
                        "SELECT count(*) FROM merchant.payout_destination WHERE merchant_id = ?",
                        merchant.value()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("ten distinct approvers racing leave exactly one approval")
    void concurrentApprovalsLeaveOneApproval() throws Exception {
        MerchantId merchant = merchant();
        PayoutDestinationId proposed = propose(merchant, person(), "pdr_approvals1");
        List<Callable<Object>> racers = new ArrayList<>();
        for (int i = 0; i < RACERS; i++) {
            Actor approver = person();
            racers.add(
                    () -> {
                        try {
                            return as(
                                    approver,
                                    uow -> destinations.approve(uow, merchant, proposed, "racing"));
                        } catch (IllegalPayoutDestinationTransitionException lost) {
                            return lost;
                        }
                    });
        }
        List<Object> outcomes = race(racers);

        assertThat(outcomes.stream().filter(PayoutDestinations.Approved.class::isInstance))
                .hasSize(1);
        assertThat(outcomes.stream()
                        .filter(IllegalPayoutDestinationTransitionException.class::isInstance))
                .hasSize(RACERS - 1);
        assertThat(count(
                        "SELECT count(*) FROM platform.audit_record WHERE operation ="
                                + " 'merchant.PayoutDestinationApproved' AND target_id = ?",
                        proposed.value().toString()))
                .isEqualTo(1);
        assertThat(count(
                        "SELECT count(*) FROM merchant.payout_destination_event WHERE"
                                + " payout_destination_id = ? AND to_status = 'APPROVED'",
                        proposed.value()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("ten concurrent sweeps leave exactly one effective row and one supersession")
    void concurrentSweepsLeaveOneEffectiveRow() throws Exception {
        MerchantId merchant = merchant();
        PayoutDestinationId first = effectiveDestination(merchant, "pdr_sweeps1");
        PayoutDestinationId second = propose(merchant, person(), "pdr_sweeps2");
        approve(merchant, second, person());

        PayoutDestinationEffectuation due = effectuationAt(Duration.ofHours(73));
        List<Callable<Object>> racers = new ArrayList<>();
        for (int i = 0; i < RACERS; i++) {
            racers.add(due::sweep);
        }
        race(racers);

        assertThat(count(
                        "SELECT count(*) FROM merchant.payout_destination WHERE merchant_id = ?"
                                + " AND status = 'EFFECTIVE'",
                        merchant.value()))
                .isEqualTo(1);
        assertThat(effectiveIdOf(merchant)).contains(second);
        assertThat(statusOf(first)).isEqualTo("SUPERSEDED");
        assertThat(count(
                        "SELECT count(*) FROM platform.audit_record WHERE operation ="
                                + " 'merchant.PayoutDestinationEffective' AND target_id = ?",
                        second.value().toString()))
                .as("one effect, however many sweepers raced for it")
                .isEqualTo(1);
        assertThat(count(
                        "SELECT count(*) FROM merchant.payout_destination_event WHERE"
                                + " payout_destination_id = ? AND to_status = 'SUPERSEDED'",
                        first.value()))
                .isEqualTo(1);
    }

    // -----------------------------------------------------------------
    // The schema, for writers that never ran the domain code
    // -----------------------------------------------------------------

    @Test
    @DisplayName("V006 refuses a second open change, a second EFFECTIVE, bank details, edits and deletes")
    void theSchemaRefusesWhatTheDomainRefuses() throws Exception {
        MerchantId merchant = merchant();
        PayoutDestinationId effective = effectiveDestination(merchant, "pdr_schema1");
        PayoutDestinationId open = propose(merchant, person(), "pdr_schema2");

        // A second open change.
        assertSqlState("23505", () -> insertRaw(merchant, "pdr_schema3", "PROPOSED"));
        // A second EFFECTIVE destination, every fact coherent: only the index refuses it.
        assertSqlState(
                "23505",
                () ->
                        raw(
                                "INSERT INTO merchant.payout_destination (id, merchant_id,"
                                        + " destination_reference, display_suffix, status,"
                                        + " proposed_by, proposed_at, proposal_reason, approved_by,"
                                        + " approved_at, cooling_off_until, effective_at) VALUES"
                                        + " (?, ?, 'pdr_schema4', '3000', 'EFFECTIVE', 'raw-a',"
                                        + " now() - interval '4 days', 'raw', 'raw-b',"
                                        + " now() - interval '4 days', now() - interval '1 day',"
                                        + " now())",
                                IDS.next(),
                                merchant.value()));
        // An account number where a reference belongs - international and domestic.
        assertSqlState("23514", () -> insertRaw(merchant, "DE89370400440532013000", "REJECTED"));
        assertSqlState("23514", () -> insertRaw(merchant, "12345678", "REJECTED"));
        // Moving an edge the machine does not have (PROPOSED -> EFFECTIVE).
        assertThatThrownBy(
                        () ->
                                raw(
                                        "UPDATE merchant.payout_destination SET status ="
                                                + " 'SUPERSEDED' WHERE id = ?",
                                        open.value()))
                .isInstanceOf(SQLException.class);
        // The proposal is frozen: the reference column is not even updatable by the app role.
        assertSqlState(
                "42501",
                () ->
                        raw(
                                "UPDATE merchant.payout_destination SET destination_reference ="
                                        + " 'pdr_swapped' WHERE id = ?",
                                effective.value()));
        // No destination ever disappears.
        assertSqlState(
                "42501",
                () -> raw("DELETE FROM merchant.payout_destination WHERE id = ?", open.value()));
        assertThat(statusOf(effective)).isEqualTo("EFFECTIVE");
        assertThat(statusOf(open)).isEqualTo("PROPOSED");
    }

    @Test
    @DisplayName("the pending gauge's subject counts open changes: proposed and cooling off")
    void theGaugeSubjectCountsOpenChanges() throws Exception {
        long before = openCount();
        MerchantId merchant = merchant();
        PayoutDestinationId proposed = propose(merchant, person(), "pdr_gauge1");
        assertThat(openCount()).isEqualTo(before + 1);
        approve(merchant, proposed, person());
        assertThat(openCount()).as("cooling off is still open").isEqualTo(before + 1);
        sweepAt(Duration.ofHours(73));
        assertThat(openCount()).as("effective is finished").isLessThanOrEqualTo(before);
    }

    @Test
    @DisplayName("the gauge is registered in a running context and reads the table, never NaN when readable")
    void theGaugeIsRegisteredAndReadsTheTable() {
        // Found missing by the completion gate: the store count had a test, the published series
        // did not - and a plan-named meter that is not registered is the P1-TSK-029 defect.
        io.micrometer.core.instrument.Gauge gauge =
                meterRegistry.get("finapp.merchant.destination.pending").gauge();
        assertThat(gauge.value()).isNotNaN().isGreaterThanOrEqualTo(0.0);
    }

    @Test
    @DisplayName("a withdrawal racing the deadline leaves one coherent outcome: withdrawn, or effective")
    void aWithdrawalRacingTheDeadlineLeavesOneCoherentOutcome() throws Exception {
        // Found missing by the completion gate: the cooling-off's teeth are the withdrawal, and the
        // moment it matters most is the deadline itself, when the sweep and the operator arrive
        // together. The row lock serializes them; this counts that it does.
        MerchantId merchant = merchant();
        PayoutDestinationId first = effectiveDestination(merchant, "pdr_deadline1");
        PayoutDestinationId second = propose(merchant, person(), "pdr_deadline2");
        approve(merchant, second, person());

        PayoutDestinationEffectuation due = effectuationAt(Duration.ofHours(73));
        Actor withdrawer = person();
        List<Callable<Object>> racers = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            racers.add(due::sweep);
        }
        racers.add(
                () -> {
                    try {
                        return as(
                                withdrawer,
                                uow -> destinations.withdraw(uow, merchant, second, "at the deadline"));
                    } catch (IllegalPayoutDestinationTransitionException tooLate) {
                        return tooLate;
                    }
                });
        List<Object> outcomes = race(racers);

        boolean withdrew = outcomes.stream().anyMatch(PayoutDestination.class::isInstance);
        assertThat(count(
                        "SELECT count(*) FROM merchant.payout_destination WHERE merchant_id = ?"
                                + " AND status = 'EFFECTIVE'",
                        merchant.value()))
                .isEqualTo(1);
        if (withdrew) {
            assertThat(statusOf(second)).isEqualTo("WITHDRAWN");
            assertThat(statusOf(first)).as("nothing replaced it").isEqualTo("EFFECTIVE");
            assertThat(auditCount("merchant.PayoutDestinationEffective", second)).isZero();
        } else {
            assertThat(outcomes.stream()
                            .filter(IllegalPayoutDestinationTransitionException.class::isInstance))
                    .as("the withdrawal lost to the effect and was refused, not applied")
                    .hasSize(1);
            assertThat(statusOf(second)).isEqualTo("EFFECTIVE");
            assertThat(statusOf(first)).isEqualTo("SUPERSEDED");
            assertThat(auditCount("merchant.PayoutDestinationWithdrawn", second)).isZero();
        }
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private static long auditCount(String operation, PayoutDestinationId target)
            throws SQLException {
        return count(
                "SELECT count(*) FROM platform.audit_record WHERE operation = ? AND target_id = ?",
                operation,
                target.value().toString());
    }

    private PayoutDestinationId effectiveDestination(MerchantId merchant, String reference)
            throws Exception {
        PayoutDestinationId proposed = propose(merchant, person(), reference);
        approve(merchant, proposed, person());
        sweepAt(Duration.ofHours(73));
        assertThat(statusOf(proposed)).isEqualTo("EFFECTIVE");
        return proposed;
    }

    private PayoutDestinationId propose(MerchantId merchant, Actor proposer, String reference)
            throws Exception {
        Object outcome = proposeOrRefusal(merchant, proposer, reference);
        assertThat(outcome).isInstanceOf(PayoutDestinationId.class);
        return (PayoutDestinationId) outcome;
    }

    private Object proposeOrRefusal(MerchantId merchant, Actor proposer, String reference)
            throws Exception {
        try {
            return as(
                    proposer,
                    uow ->
                            destinations
                                    .propose(
                                            uow,
                                            new PayoutDestinations.ProposeCommand(
                                                    UUID.randomUUID().toString(),
                                                    merchant,
                                                    new PayoutDestinationTokenisation
                                                            .TokenisedDestination(
                                                            PayoutDestinationReference.of(reference),
                                                            "3000"),
                                                    "the merchant's new account"))
                                    .destinationId());
        } catch (PayoutDestinationChangePendingException pending) {
            return pending;
        }
    }

    private void approve(MerchantId merchant, PayoutDestinationId id, Actor approver)
            throws Exception {
        PayoutDestinations.Approval approval =
                as(approver, uow -> destinations.approve(uow, merchant, id, "verified"));
        assertThat(approval).isInstanceOf(PayoutDestinations.Approved.class);
    }

    private Optional<PayoutDestinationId> effectiveIdOf(MerchantId merchant) throws Exception {
        return as(person(), uow -> destinations.effectiveFor(uow, merchant))
                .map(PayoutDestination::id);
    }

    private void sweepAt(Duration offset) {
        effectuationAt(offset).sweep();
    }

    /** The real sweep over the real store, on a clock moved forward by {@code offset}. */
    private PayoutDestinationEffectuation effectuationAt(Duration offset) {
        // A wide batch: rows other tests left approved are due too, and must not crowd ours out.
        return new PayoutDestinationEffectuation(
                new MerchantTransactions(new TransactionTemplate(transactionManager), dataSource),
                store,
                auditWriter,
                IDS,
                Clock.offset(Clock.system(ZoneOffset.UTC), offset),
                1000);
    }

    /** Runs {@code work} as {@code actor} in one committed transaction on the app role. */
    private <R> R as(Actor actor, Function<Connection, R> work) throws Exception {
        try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope acting = SecurityContext.enter(actor);
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try {
                R result = work.apply(app);
                app.commit();
                return result;
            } catch (RuntimeException refused) {
                app.rollback();
                throw refused;
            }
        }
    }

    private static List<Object> race(List<Callable<Object>> racers) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(racers.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> racer : racers) {
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return racer.call();
                                }));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private static MerchantId merchant() throws SQLException {
        MerchantId id = MerchantId.next(IDS);
        raw(
                "INSERT INTO merchant.merchant (id, party_ref, legal_name, display_name,"
                        + " settlement_currency, status, created_at, status_changed_at) VALUES"
                        + " (?, ?, 'Acme GmbH', 'Acme', 'EUR', 'ACTIVE', now(), now())",
                id.value(),
                UUID.randomUUID());
        return id;
    }

    private static Actor person() {
        return new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER);
    }

    private static void insertRaw(MerchantId merchant, String reference, String status)
            throws SQLException {
        boolean ended = status.equals("REJECTED");
        // One clock for the row. proposed_at was the database's now() and ended_at the JVM's,
        // so ended_at >= proposed_at failed whenever the database ran ahead - a 23514 of its
        // own, under which the bank-detail refusals passed whatever the reference CHECK did
        // (X-TSK-005).
        OffsetDateTime at = testClockNow();
        raw(
                "INSERT INTO merchant.payout_destination (id, merchant_id, destination_reference,"
                        + " display_suffix, status, proposed_by, proposed_at, proposal_reason,"
                        + " ended_by, ended_at) VALUES (?, ?, ?, '3000', ?, 'raw-writer', ?,"
                        + " 'raw', ?, ?)",
                IDS.next(),
                merchant.value(),
                reference,
                status,
                at,
                ended ? "raw-writer" : null,
                ended ? at : null);
    }

    /**
     * The test's clock at the columns' microsecond resolution: the clock the domain stamps with
     * ({@code Clock.systemUTC()}), never the database's {@code now()} (X-TSK-005).
     */
    private static OffsetDateTime testClockNow() {
        return OffsetDateTime.ofInstant(
                Instant.now(CLOCK).truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    private static void assertSqlState(String state, ThrowingRunnable statement) {
        assertThatThrownBy(statement::run)
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(state));
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static String statusOf(PayoutDestinationId id) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT status FROM merchant.payout_destination WHERE id = ?")) {
            read.setObject(1, id.value());
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    private long openCount() throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            return store.countOpen(app);
        }
    }

    private static long count(String sql, Object... arguments) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                read.setObject(i + 1, arguments[i]);
            }
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private static void raw(String sql, Object... arguments) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement statement = app.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            statement.executeUpdate();
        }
    }
}
