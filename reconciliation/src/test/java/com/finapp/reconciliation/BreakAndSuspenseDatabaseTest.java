package com.finapp.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingService;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Breaks and suspense against the real schema and the real ledger (`P8-TSK-010`, ADR-0069,
 * ADR-0070): every type raisable with its computed severity, the raise convergent under ten
 * instances, nothing deletable by any writer, ownership structural, park and unpark exact
 * inverses asserted against {@code ledger.journal_line}, ten parks of one item producing one
 * entry and one suspense item, and the key-collision leg raising once under ten legs.
 *
 * <p>The shared connection rolls back at the end; the race fixtures commit deliberately —
 * the module's container is this invocation's own.
 */
@Tag("database")
@DisplayName("breaks and suspense as records (P8-TSK-010)")
class BreakAndSuspenseDatabaseTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-29T16:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Actor PLATFORM = new Actor("system", ActorType.SYSTEM);
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final UUID PSP_SOURCE =
            UUID.fromString("01a0e2bc-8200-7001-8000-000000000001");
    private static final UUID PSP_RULE_SET =
            UUID.fromString("01a0e2bd-8300-7001-8000-000000000001");
    private static final long HIGH_VALUE = 100_000L; // rule set v1's seeded threshold.
    private static final LocalDate DECIDED_ON = LocalDate.parse("2026-09-29");
    private static final LocalDate SETTLED_ON = LocalDate.parse("2026-09-25");
    private static final AtomicLong SEQUENCES = new AtomicLong(System.nanoTime() % 100_000);

    private static Connection application;
    private static JdbcBreakRegister register;
    private static Suspense suspense;
    private static JdbcRuleSets ruleSets;
    private static JdbcReconciliationRuns runs;
    private static JdbcExternalItems items;
    private static JdbcExpectationRegister expectations;
    private static UUID positionAccount;

    @BeforeAll
    static void connect() throws SQLException {
        application = DatabaseRoles.application();
        application.setAutoCommit(false);
        DatabaseRoles.assertCannotBypassPrivileges(application);
        register = new JdbcBreakRegister(new JdbcOutboxWriter(), new JdbcAuditWriter(), IDS);
        suspense = new Suspense(postingService(), new JdbcLedgerAccountStore(), IDS);
        ruleSets = new JdbcRuleSets();
        runs = new JdbcReconciliationRuns();
        items = new JdbcExternalItems();
        expectations = new JdbcExpectationRegister(IDS);
        positionAccount =
                new JdbcLedgerAccountStore()
                        .findOperational(
                                application, AccountPurpose.SETTLEMENT_CLEARING, EUR)
                        .orElseThrow()
                        .id()
                        .value();
    }

    @AfterAll
    static void disconnect() throws SQLException {
        if (application != null) {
            application.rollback();
            application.close();
        }
    }

    private static PostingService postingService() {
        return new PostingService(
                new IdempotentExecutor(
                        new JdbcIdempotencyRecordStore(),
                        CLOCK,
                        Duration.ofDays(1),
                        Duration.ofMinutes(5)),
                new JdbcJournalEntryStore(IDS),
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(),
                new JdbcBalanceProjection(),
                IDS,
                CLOCK,
                PostingObserver.NONE);
    }

    // ----------------------------------------------------------------- raising

    @Test
    @DisplayName("every type is raisable with its computed severity - the seeded threshold"
            + " escalating at exactly high_value_minor and not one unit below, OUTBOUND"
            + " unknowns CRITICAL")
    void everyTypeIsRaisableWithItsSeverity() throws SQLException {
        record Case(BreakType type, BreakCause cause, Severity expected) {}
        List<Case> cases =
                List.of(
                        new Case(BreakType.MISSING_EXTERNAL, BreakCause.EXPECTATION_OVERDUE,
                                Severity.MEDIUM),
                        new Case(BreakType.MISSING_INTERNAL, BreakCause.GRACE_EXPIRED,
                                Severity.HIGH),
                        new Case(BreakType.UNKNOWN_EXTERNAL, BreakCause.GRACE_EXPIRED,
                                Severity.HIGH),
                        new Case(BreakType.AMOUNT_MISMATCH, BreakCause.AMOUNT_DIFFERS,
                                Severity.HIGH),
                        new Case(BreakType.CURRENCY_MISMATCH, BreakCause.CURRENCY_DIFFERS,
                                Severity.HIGH),
                        new Case(BreakType.FEE_MISMATCH, BreakCause.FEE_BEYOND_TOLERANCE,
                                Severity.MEDIUM),
                        new Case(BreakType.DUPLICATE_EXTERNAL,
                                BreakCause.REPEATED_FINGERPRINT, Severity.HIGH),
                        new Case(BreakType.DUPLICATE_INTERNAL, BreakCause.KEY_COLLISION,
                                Severity.HIGH),
                        new Case(BreakType.AMBIGUOUS_MATCH, BreakCause.MULTIPLE_CANDIDATES,
                                Severity.MEDIUM),
                        new Case(BreakType.TIMING_DIFFERENCE, BreakCause.LATE_MATCH,
                                Severity.LOW),
                        new Case(BreakType.REVERSAL_MISMATCH,
                                BreakCause.DIRECTION_CONTRADICTED, Severity.HIGH),
                        new Case(BreakType.REFUND_MISMATCH, BreakCause.REFUND_CONTRADICTED,
                                Severity.CRITICAL),
                        new Case(BreakType.SETTLEMENT_MISMATCH,
                                BreakCause.REMITTANCE_DIFFERS, Severity.HIGH),
                        new Case(BreakType.PROCESSING_ERROR, BreakCause.ITEM_ERRORED,
                                Severity.CRITICAL));
        for (Case c : cases) {
            UUID item = seedItems(1, SETTLED_ON, ExpectationDirection.INBOUND).get(0);
            BreakRegister.Raised raised =
                    register.raise(
                            application,
                            newBreak(c.type(), c.cause(),
                                    BreakRegister.Subject.externalItem(item), 100,
                                    Optional.empty()));
            assertThat(raised.created()).as("%s raisable", c.type()).isTrue();
            assertThat(raised.severity()).as("%s base", c.type()).isEqualTo(c.expected());
            assertThat(count("SELECT count(*) FROM reconciliation.break_event WHERE"
                    + " break_id = ? AND event_type = 'RAISED'", raised.breakId()))
                    .isEqualTo(1);
        }

        // The threshold, from the SEEDED severity_threshold row of the pinned rule set.
        UUID under = seedItems(1, SETTLED_ON, ExpectationDirection.INBOUND).get(0);
        assertThat(register.raise(application,
                        newBreak(BreakType.MISSING_INTERNAL, BreakCause.GRACE_EXPIRED,
                                BreakRegister.Subject.externalItem(under), HIGH_VALUE - 1,
                                Optional.empty()))
                        .severity())
                .as("one unit below the threshold: the base stands")
                .isEqualTo(Severity.HIGH);
        UUID at = seedItems(1, SETTLED_ON, ExpectationDirection.INBOUND).get(0);
        assertThat(register.raise(application,
                        newBreak(BreakType.MISSING_INTERNAL, BreakCause.GRACE_EXPIRED,
                                BreakRegister.Subject.externalItem(at), HIGH_VALUE,
                                Optional.empty()))
                        .severity())
                .as("at the threshold: one level up (>=, owner decision O7)")
                .isEqualTo(Severity.CRITICAL);
        UUID outbound = seedItems(1, SETTLED_ON, ExpectationDirection.OUTBOUND).get(0);
        assertThat(register.raise(application,
                        newBreak(BreakType.UNKNOWN_EXTERNAL, BreakCause.GRACE_EXPIRED,
                                BreakRegister.Subject.externalItem(outbound), 100,
                                Optional.of(ExpectationDirection.OUTBOUND)))
                        .severity())
                .as("money that LEFT with no owner")
                .isEqualTo(Severity.CRITICAL);

        // The raise-time pairing binds every writer at the database too.
        assertThatThrownBy(() -> rawBreak(application, IDS.next(), "FEE_MISMATCH",
                        "KEY_COLLISION", under))
                .hasMessageContaining("raised only by its own detector");
        application.rollback();
    }

    @Test
    @DisplayName("ten raisers of one discrepancy produce one open break, one history row -"
            + " the losers record nothing")
    void tenRaisersProduceOneOpenBreak() throws Exception {
        UUID item = seedItems(1, SETTLED_ON, ExpectationDirection.INBOUND).get(0);
        application.commit();

        int racers = 10;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> outcomes = new ArrayList<>();
        try {
            for (int i = 0; i < racers; i++) {
                outcomes.add(
                        pool.submit(
                                () -> {
                                    try (Connection own = DatabaseRoles.application()) {
                                        own.setAutoCommit(false);
                                        start.await();
                                        BreakRegister.Raised raised =
                                                register.raise(
                                                        own,
                                                        newBreak(
                                                                BreakType.MISSING_INTERNAL,
                                                                BreakCause.GRACE_EXPIRED,
                                                                BreakRegister.Subject
                                                                        .externalItem(item),
                                                                4200,
                                                                Optional.empty()));
                                        own.commit();
                                        return raised.created();
                                    }
                                }));
            }
            start.countDown();
            int created = 0;
            for (Future<Boolean> outcome : outcomes) {
                if (outcome.get()) {
                    created++;
                }
            }
            assertThat(created).as("exactly one winner").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("SELECT count(*) FROM reconciliation.break WHERE type ="
                + " 'MISSING_INTERNAL' AND external_item_id = ?", item))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.break_event ev JOIN"
                + " reconciliation.break b ON b.id = ev.break_id WHERE"
                + " b.external_item_id = ? AND ev.event_type = 'RAISED'", item))
                .as("a loser writes no history")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a recurrence after resolution is a NEW break naming its predecessor -"
            + " the partial unique frees the seat, the case keeps both records")
    void aRecurrenceNamesItsPredecessor() throws SQLException {
        UUID item = seedItems(1, SETTLED_ON, ExpectationDirection.INBOUND).get(0);
        BreakRegister.Raised first =
                register.raise(
                        application,
                        newBreak(BreakType.MISSING_INTERNAL, BreakCause.GRACE_EXPIRED,
                                BreakRegister.Subject.externalItem(item), 100,
                                Optional.empty()));
        // The legal OPEN -> RESOLVED edge, raw: EVIDENCED's producer is P8-TSK-012's - the
        // machine and the grant admit the edge today, nothing produces it yet.
        execute("UPDATE reconciliation.break SET status = 'RESOLVED', resolved_at = ?,"
                + " status_changed_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now(CLOCK)),
                java.sql.Timestamp.from(Instant.now(CLOCK)), first.breakId());

        BreakRegister.NewBreak recurrence =
                new BreakRegister.NewBreak(
                        IDS.next(),
                        BreakType.MISSING_INTERNAL,
                        BreakCause.GRACE_EXPIRED,
                        BreakRegister.Subject.externalItem(item),
                        PSP_SOURCE,
                        activeRuleSet(),
                        Money.ofPersisted(100, EUR, 2),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(InternalClassification.IN_FLIGHT),
                        Optional.of("op-recur"),
                        Optional.of("DISPATCHED"),
                        Optional.of(first.breakId()),
                        PLATFORM,
                        Instant.now(CLOCK),
                        CorrelationId.generate(IDS));
        BreakRegister.Raised second = register.raise(application, recurrence);
        assertThat(second.created()).as("the seat was freed by RESOLVED").isTrue();
        assertThat(scalar("SELECT follows_break_id::text FROM reconciliation.break WHERE"
                + " id = ?", second.breakId()))
                .isEqualTo(first.breakId().toString());
        application.rollback();
    }

    @Test
    @DisplayName("the key-collision leg raises DUPLICATE_INTERNAL once under ten legs -"
            + " subject the colliding expectation, value its own amount")
    void keyCollisionRaisesOnceUnderTenLegs() throws Exception {
        String key = "COLLIDE-" + UUID.randomUUID().toString().substring(0, 8);
        UUID standing = openExpectation("stand-" + key, key);
        UUID colliding = openExpectation("collide-" + key, key);
        assertThat(count("SELECT count(*) FROM reconciliation.expectation_event WHERE"
                + " expectation_id = ? AND event_type = 'KEY_COLLISION'", colliding))
                .as("the collision was recorded at opening (P8-TSK-004)")
                .isEqualTo(1);
        application.commit();

        KeyCollisionBreaks leg = new KeyCollisionBreaks(register, ruleSets, IDS);
        int racers = 10;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> outcomes = new ArrayList<>();
        try {
            for (int i = 0; i < racers; i++) {
                outcomes.add(
                        pool.submit(
                                () -> {
                                    try (Connection own = DatabaseRoles.application()) {
                                        own.setAutoCommit(false);
                                        start.await();
                                        int created =
                                                leg.raiseFromRecordedCollisions(
                                                        own, 100, PLATFORM,
                                                        Instant.now(CLOCK),
                                                        CorrelationId.generate(IDS));
                                        own.commit();
                                        return created;
                                    }
                                }));
            }
            start.countDown();
            int created = 0;
            for (Future<Integer> outcome : outcomes) {
                created += outcome.get();
            }
            // The container may hold OTHER suites' committed, not-yet-raised collisions
            // (ExpectationRegisterDatabaseTest commits one), each raised exactly once
            // across the ten legs; THIS subject's convergence is the count below.
            assertThat(created).as("the ten legs converged").isGreaterThanOrEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("SELECT count(*) FROM reconciliation.break WHERE type ="
                + " 'DUPLICATE_INTERNAL' AND expectation_id = ?", colliding))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.break WHERE type ="
                + " 'DUPLICATE_INTERNAL' AND expectation_id = ?", standing))
                .as("the standing expectation's key holds; the question is the collider's")
                .isEqualTo(0);
    }

    // ----------------------------------------------------------------- suspense

    @Test
    @DisplayName("park and unpark are exact inverses in each direction - one entry of four"
            + " lines per (position, date), asserted against the ledger, dates from the"
            + " park row and the item, releases appended, residual versions bumped")
    void parkAndUnparkAreExactInverses() throws SQLException {
        List<UUID> parked = seedItems(2, SETTLED_ON, ExpectationDirection.INBOUND,
                ExpectationDirection.OUTBOUND);
        UUID inboundBreak = raiseFor(parked.get(0), 10_00);
        UUID outboundBreak = raiseFor(parked.get(1), 7_50);

        Suspense.ParkResult result =
                asPlatform(() -> suspense.park(
                        application,
                        new Suspense.ParkCommand(
                                PSP_SOURCE,
                                DECIDED_ON,
                                List.of(
                                        new Suspense.ParkedItem(parked.get(0), inboundBreak,
                                                Money.ofPersisted(10_00, EUR, 2),
                                                positionAccount),
                                        new Suspense.ParkedItem(parked.get(1), outboundBreak,
                                                Money.ofPersisted(7_50, EUR, 2),
                                                positionAccount)),
                                PLATFORM,
                                Instant.now(CLOCK),
                                CorrelationId.generate(IDS))));
        assertThat(result.parked()).hasSize(2);
        assertThat(result.converged()).isEmpty();
        UUID entry = result.parked().get(0).entryId();
        assertThat(result.parked().get(1).entryId())
                .as("one date, one position: ONE entry")
                .isEqualTo(entry);

        UUID suspenseAccount =
                new JdbcLedgerAccountStore()
                        .findOperational(
                                application, AccountPurpose.SUSPENSE_UNMATCHED, EUR)
                        .orElseThrow()
                        .id()
                        .value();
        assertThat(lines(entry))
                .as("DR P 10.00 / CR S 10.00 (the INBOUND park) and DR S 7.50 / CR P 7.50"
                        + " (the OUTBOUND park): four lines, at most")
                .containsExactlyInAnyOrder(
                        positionAccount + ":DEBIT:1000",
                        suspenseAccount + ":CREDIT:1000",
                        suspenseAccount + ":DEBIT:750",
                        positionAccount + ":CREDIT:750");
        assertThat(scalar("SELECT posting_date::text || '|' || value_date::text FROM"
                + " ledger.journal_entry WHERE id = ?", entry))
                .as("posting date the park row's decided_on; value date the items' own")
                .isEqualTo("2026-09-29|2026-09-25");

        // The items and their suspense rows.
        for (Suspense.ParkedOutcome outcome : result.parked()) {
            assertThat(scalar("SELECT status FROM reconciliation.external_item WHERE"
                    + " id = ?", outcome.externalItemId()))
                    .isEqualTo("PARKED");
        }
        assertThat(scalar("SELECT side || ':' || status FROM reconciliation.suspense_item"
                + " WHERE external_item_id = ?", parked.get(0)))
                .isEqualTo("CREDIT:OPEN");
        assertThat(scalar("SELECT side || ':' || status FROM reconciliation.suspense_item"
                + " WHERE external_item_id = ?", parked.get(1)))
                .isEqualTo("DEBIT:OPEN");

        // Unpark each fully: the exact inverse, the release appended, the version bumped.
        UUID creditItem = result.parked().get(0).suspenseItemId();
        UUID debitItem = result.parked().get(1).suspenseItemId();
        Suspense.Unparked creditBack =
                asPlatform(() -> suspense.unpark(application, creditItem,
                        Money.ofPersisted(10_00, EUR, 2),
                        "rematch-sim", DECIDED_ON, PLATFORM, Instant.now(CLOCK),
                        CorrelationId.generate(IDS)));
        assertThat(lines(creditBack.entryId()))
                .containsExactlyInAnyOrder(
                        suspenseAccount + ":DEBIT:1000", positionAccount + ":CREDIT:1000");
        Suspense.Unparked debitBack =
                asPlatform(() -> suspense.unpark(application, debitItem,
                        Money.ofPersisted(7_50, EUR, 2),
                        "rematch-sim", DECIDED_ON, PLATFORM, Instant.now(CLOCK),
                        CorrelationId.generate(IDS)));
        assertThat(lines(debitBack.entryId()))
                .containsExactlyInAnyOrder(
                        positionAccount + ":DEBIT:750", suspenseAccount + ":CREDIT:750");
        assertThat(scalar("SELECT value_date::text FROM ledger.journal_entry WHERE id = ?",
                creditBack.entryId()))
                .as("the unpark keeps the park's own value date - the exact inverse")
                .isEqualTo("2026-09-25");
        for (UUID item : List.of(creditItem, debitItem)) {
            assertThat(scalar("SELECT status FROM reconciliation.suspense_item WHERE"
                    + " id = ?", item))
                    .isEqualTo("RELEASED");
            assertThat(count("SELECT count(*) FROM reconciliation.suspense_release WHERE"
                    + " item_id = ? AND cause = 'UNPARK'", item))
                    .isEqualTo(1);
        }
        for (UUID breakId : List.of(inboundBreak, outboundBreak)) {
            assertThat(count("SELECT residual_version FROM reconciliation.break WHERE"
                    + " id = ?", breakId))
                    .as("every release moves the residual version")
                    .isEqualTo(1);
        }
        application.rollback();
    }

    @Test
    @DisplayName("a partial unpark leaves the item PARTIALLY_RELEASED and its break open"
            + " over the remainder; items of different dates never share an entry")
    void partialReleasesAndDateGrouping() throws SQLException {
        List<UUID> sameDay = seedItems(1, SETTLED_ON, ExpectationDirection.INBOUND);
        List<UUID> laterDay =
                seedItems(1, SETTLED_ON.plusDays(1), ExpectationDirection.INBOUND);
        UUID first = raiseFor(sameDay.get(0), 10_00);
        UUID second = raiseFor(laterDay.get(0), 5_00);

        Suspense.ParkResult result =
                asPlatform(() -> suspense.park(
                        application,
                        new Suspense.ParkCommand(
                                PSP_SOURCE,
                                DECIDED_ON,
                                List.of(
                                        new Suspense.ParkedItem(sameDay.get(0), first,
                                                Money.ofPersisted(10_00, EUR, 2),
                                                positionAccount),
                                        new Suspense.ParkedItem(laterDay.get(0), second,
                                                Money.ofPersisted(5_00, EUR, 2),
                                                positionAccount)),
                                PLATFORM,
                                Instant.now(CLOCK),
                                CorrelationId.generate(IDS))));
        assertThat(result.parked()).hasSize(2);
        assertThat(result.parked().get(0).entryId())
                .as("different settlement dates never share an entry")
                .isNotEqualTo(result.parked().get(1).entryId());

        UUID item = result.parked().get(0).suspenseItemId();
        asPlatform(() -> suspense.unpark(application, item,
                Money.ofPersisted(4_00, EUR, 2), "partial",
                DECIDED_ON, PLATFORM, Instant.now(CLOCK), CorrelationId.generate(IDS)));
        assertThat(scalar("SELECT status || ':' || released_minor::text FROM"
                + " reconciliation.suspense_item WHERE id = ?", item))
                .isEqualTo("PARTIALLY_RELEASED:400");
        asPlatform(() -> suspense.unpark(application, item,
                Money.ofPersisted(6_00, EUR, 2), "rest",
                DECIDED_ON, PLATFORM, Instant.now(CLOCK), CorrelationId.generate(IDS)));
        assertThat(scalar("SELECT status FROM reconciliation.suspense_item WHERE id = ?",
                item))
                .isEqualTo("RELEASED");
        assertThatThrownBy(
                        () ->
                                asPlatform(() -> suspense.unpark(application, item,
                                        Money.ofPersisted(1, EUR, 2), "over", DECIDED_ON,
                                        PLATFORM, Instant.now(CLOCK),
                                        CorrelationId.generate(IDS))))
                .as("a release beyond the amount is refused")
                .isInstanceOf(IllegalArgumentException.class);
        application.rollback();
    }

    @Test
    @DisplayName("ten parks of one item produce one entry and one suspense item - the"
            + " item's conditional transition is the arbiter, the uniques beneath it")
    void tenParksOfOneItemProduceOneEntryAndOneItem() throws Exception {
        UUID item = seedItems(1, SETTLED_ON, ExpectationDirection.INBOUND).get(0);
        UUID breakId = raiseFor(item, 12_34);
        application.commit();

        int racers = 10;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> outcomes = new ArrayList<>();
        try {
            for (int i = 0; i < racers; i++) {
                outcomes.add(
                        pool.submit(
                                () -> {
                                    try (Connection own = DatabaseRoles.application()) {
                                        own.setAutoCommit(false);
                                        start.await();
                                        Suspense.ParkResult result =
                                                asPlatform(() -> suspense.park(
                                                        own,
                                                        new Suspense.ParkCommand(
                                                                PSP_SOURCE,
                                                                DECIDED_ON,
                                                                List.of(
                                                                        new Suspense
                                                                                .ParkedItem(
                                                                                item,
                                                                                breakId,
                                                                                Money
                                                                                        .ofPersisted(
                                                                                                12_34,
                                                                                                EUR,
                                                                                                2),
                                                                                positionAccount)),
                                                                PLATFORM,
                                                                Instant.now(CLOCK),
                                                                CorrelationId.generate(
                                                                        IDS))));
                                        own.commit();
                                        return result.parked().size();
                                    }
                                }));
            }
            start.countDown();
            int wins = 0;
            for (Future<Integer> outcome : outcomes) {
                wins += outcome.get();
            }
            assertThat(wins).as("one winner, nine converged").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("SELECT count(*) FROM reconciliation.suspense_item WHERE"
                + " external_item_id = ?", item))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.park p JOIN"
                + " reconciliation.suspense_item i ON i.park_id = p.id WHERE"
                + " i.external_item_id = ?", item))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a park without its owning break is refused at the domain (INV-REC-09 by"
            + " the API's shape): missing, resolved, and a type that never parks")
    void aParkWithoutItsBreakIsRefused() throws SQLException {
        UUID item = seedItems(1, SETTLED_ON, ExpectationDirection.INBOUND).get(0);

        assertThatThrownBy(() -> park(item, IDS.next(), 100))
                .as("a break that does not exist")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INV-REC-09");

        UUID resolved = raiseFor(item, 100);
        execute("UPDATE reconciliation.break SET status = 'RESOLVED', resolved_at = ?,"
                + " status_changed_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now(CLOCK)),
                java.sql.Timestamp.from(Instant.now(CLOCK)), resolved);
        assertThatThrownBy(() -> park(item, resolved, 100))
                .as("a resolved break cannot take new value")
                .isInstanceOf(IllegalStateException.class);

        UUID feeItem = seedItems(1, SETTLED_ON, ExpectationDirection.INBOUND).get(0);
        BreakRegister.Raised fee =
                register.raise(
                        application,
                        newBreak(BreakType.FEE_MISMATCH, BreakCause.FEE_BEYOND_TOLERANCE,
                                BreakRegister.Subject.externalItem(feeItem), 100,
                                Optional.empty()));
        assertThatThrownBy(() -> park(feeItem, fee.breakId(), 100))
                .as("a FEE_MISMATCH break never owns suspense")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("never owns suspense");
        application.rollback();
    }

    // ----------------------------------------------------------------- immutability

    @Test
    @DisplayName("nothing is deletable on any of the seven tables - the application role by"
            + " the absent grant, the MIGRATOR by the refusing trigger")
    void nothingIsDeletableByAnyWriter() throws SQLException {
        UUID item = seedItems(1, SETTLED_ON, ExpectationDirection.INBOUND).get(0);
        UUID breakId = raiseFor(item, 100);
        asPlatform(() -> suspense.park(
                application,
                new Suspense.ParkCommand(
                        PSP_SOURCE, DECIDED_ON,
                        List.of(new Suspense.ParkedItem(item, breakId,
                                Money.ofPersisted(100, EUR, 2), positionAccount)),
                        PLATFORM, Instant.now(CLOCK), CorrelationId.generate(IDS))));
        execute("INSERT INTO reconciliation.break_note (id, break_id, body, author,"
                + " author_type, added_at, correlation_id) VALUES (?, ?, 'a clean note',"
                + " 'op-1', 'EMPLOYEE', ?, 'corr-1')",
                IDS.next(), breakId, java.sql.Timestamp.from(Instant.now(CLOCK)));
        execute("INSERT INTO reconciliation.break_evidence_link (id, break_id, target_kind,"
                + " target_ref, added_by, added_by_type, added_at, correlation_id) VALUES"
                + " (?, ?, 'JOURNAL_ENTRY', ?, 'op-1', 'EMPLOYEE', ?, 'corr-1')",
                IDS.next(), breakId, IDS.next().toString(),
                java.sql.Timestamp.from(Instant.now(CLOCK)));
        UUID parkedItem = (UUID) queryOne("SELECT id FROM"
                + " reconciliation.suspense_item WHERE external_item_id = ?", item);
        asPlatform(() -> suspense.unpark(application, parkedItem,
                Money.ofPersisted(100, EUR, 2), "for-history", DECIDED_ON, PLATFORM,
                Instant.now(CLOCK), CorrelationId.generate(IDS)));
        application.commit();

        List<String> tables =
                List.of("break", "break_event", "break_note", "break_evidence_link",
                        "park", "suspense_item", "suspense_release");
        for (String table : tables) {
            assertThat(refusal("DELETE FROM reconciliation." + table))
                    .as("the application role holds no DELETE on %s", table)
                    .contains("permission denied");
        }
        // The MIGRATOR, whom no grant binds: each DELETE targets a planted CHILDLESS row,
        // so nothing but the refusing trigger could stop it - a whole-table DELETE would
        // hit child foreign keys and pass this assert with the trigger dropped (the
        // gate's find: the probe run demands a deterministic catcher).
        UUID bareBreak = IDS.next();
        UUID ownerBreak = IDS.next();
        UUID barePark = IDS.next();
        UUID childlessItem = IDS.next();
        List<UUID> decisionItems = seedItems(2, SETTLED_ON, ExpectationDirection.INBOUND);
        rawDecisionBreak(application, bareBreak, decisionItems.get(0));
        rawDecisionBreak(application, ownerBreak, decisionItems.get(1));
        execute("INSERT INTO reconciliation.park (id, source_id, kind,"
                + " position_account_id, currency, decided_on, value_date,"
                + " journal_entry_id, actor, actor_type, created_at, correlation_id)"
                + " VALUES (?, ?, 'PARK', ?, 'EUR', now(), now(), ?, 'system', 'SYSTEM',"
                + " now(), 'corr')",
                barePark, PSP_SOURCE, positionAccount, IDS.next());
        execute("INSERT INTO reconciliation.suspense_item (id, break_id, origin,"
                + " origin_ref, side, amount_minor, currency, scale, released_minor,"
                + " status, opened_on, entry_id, status_changed_at, correlation_id)"
                + " VALUES (?, ?, 'BANK_UNATTRIBUTED', ?, 'CREDIT', 100, 'EUR', 2, 0,"
                + " 'OPEN', now(), ?, now(), 'corr')",
                childlessItem, ownerBreak, "childless-" + childlessItem, IDS.next());
        execute("INSERT INTO reconciliation.break_event (break_id, event_type, actor,"
                + " actor_type, occurred_at, correlation_id) VALUES (?, 'RAISED',"
                + " 'system', 'SYSTEM', now(), 'corr')", ownerBreak);
        application.commit();
        Map<String, String> childless =
                Map.of(
                        "break", "id = '" + bareBreak + "'",
                        "break_event", "break_id = '" + ownerBreak + "'",
                        "break_note", "break_id = '" + breakId + "'",
                        "break_evidence_link", "break_id = '" + breakId + "'",
                        "park", "id = '" + barePark + "'",
                        "suspense_item", "id = '" + childlessItem + "'",
                        "suspense_release", "item_id = '" + parkedItem + "'");
        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);
            for (String table : tables) {
                try (Statement raw = migrator.createStatement()) {
                    assertThatThrownBy(
                                    () ->
                                            raw.execute(
                                                    "DELETE FROM reconciliation." + table
                                                            + " WHERE "
                                                            + childless.get(table)))
                            .as("the trigger refuses the migrator too on %s - the target"
                                    + " row is childless, so nothing else could", table)
                            .isInstanceOf(SQLException.class)
                            .hasMessageNotContaining("foreign key");
                    migrator.rollback();
                }
            }
        }
    }

    /**
     * A childless break past every domain guard: its one subject is a bare decision id.
     * Since `V005` the decision column carries a foreign key, so each call plants its own
     * real {@code match_decision} (origin {@code MANUAL}: no run required) over the item.
     */
    private static void rawDecisionBreak(Connection connection, UUID id, UUID itemId)
            throws SQLException {
        UUID decisionId = IDS.next();
        try (PreparedStatement insert =
                connection.prepareStatement(
                        "INSERT INTO reconciliation.match_decision (id,"
                                + " external_item_id, origin, rule_set_id, outcome,"
                                + " decided_by, decided_by_type, decided_at, decided_on,"
                                + " correlation_id) VALUES (?, ?, 'MANUAL', ?, 'PARKED',"
                                + " 'system', 'SYSTEM', now(), now(), 'corr')")) {
            insert.setObject(1, decisionId);
            insert.setObject(2, itemId);
            insert.setObject(3, PSP_RULE_SET);
            insert.executeUpdate();
        }
        try (PreparedStatement insert =
                connection.prepareStatement(
                        "INSERT INTO reconciliation.break (id, type, cause, status,"
                                + " severity, source_id, rule_set_id, decision_id,"
                                + " value_at_issue_minor, currency, scale,"
                                + " residual_version, raised_at, status_changed_at,"
                                + " correlation_id) VALUES (?, 'UNKNOWN_EXTERNAL',"
                                + " 'GRACE_EXPIRED', 'OPEN', 'HIGH', ?, ?, ?, 1, 'EUR', 2,"
                                + " 0, now(), now(), 'corr')")) {
            insert.setObject(1, id);
            insert.setObject(2, PSP_SOURCE);
            insert.setObject(3, PSP_RULE_SET);
            insert.setObject(4, decisionId);
            insert.executeUpdate();
        }
    }

    @Test
    @DisplayName("ownership is structural: a NULL break refused by NOT NULL, a never-parking"
            + " or resolved owner refused by the trigger, for every writer")
    void theOwnershipIsStructural() throws SQLException {
        // Past every domain guard, as the MIGRATOR: the database is the arbiter.
        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);
            assertThatThrownBy(() -> rawSuspenseItem(migrator, null))
                    .as("break_id NOT NULL is the structural half of INV-REC-09")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("INV-REC-09");
            migrator.rollback();
        }
        UUID item = seedItems(1, SETTLED_ON, ExpectationDirection.INBOUND).get(0);
        BreakRegister.Raised fee =
                register.raise(
                        application,
                        newBreak(BreakType.FEE_MISMATCH, BreakCause.FEE_BEYOND_TOLERANCE,
                                BreakRegister.Subject.externalItem(item), 100,
                                Optional.empty()));
        assertThatThrownBy(() -> rawSuspenseItem(application, fee.breakId()))
                .as("the owner-type trigger refuses a never-parking owner for any writer")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("never owns suspense");
        application.rollback();
    }

    @Test
    @DisplayName("a note holding a Luhn-valid 13-19-digit run or an IBAN shape is refused"
            + " at the database with nothing stored; bounds hold")
    void theNoteScreensHold() throws SQLException {
        UUID item = seedItems(1, SETTLED_ON, ExpectationDirection.INBOUND).get(0);
        UUID breakId = raiseFor(item, 100);
        assertThat(refusal("INSERT INTO reconciliation.break_note (id, break_id, body,"
                + " author, author_type, added_at, correlation_id) VALUES (?, ?,"
                + " 'the customer read the card 4111111111111111 aloud', 'op-1',"
                + " 'EMPLOYEE', now(), 'corr')", IDS.next(), breakId))
                .contains("break_note_no_card_number");
        assertThat(refusal("INSERT INTO reconciliation.break_note (id, break_id, body,"
                + " author, author_type, added_at, correlation_id) VALUES (?, ?,"
                + " 'refund to DE89370400440532013000 please', 'op-1', 'EMPLOYEE', now(),"
                + " 'corr')", IDS.next(), breakId))
                .contains("break_note_no_account_shape");
        assertThat(refusal("INSERT INTO reconciliation.break_note (id, break_id, body,"
                + " author, author_type, added_at, correlation_id) VALUES (?, ?, '"
                + "x".repeat(4001) + "', 'op-1', 'EMPLOYEE', now(), 'corr')",
                IDS.next(), breakId))
                .contains("break_note_body_bounded");
        execute("INSERT INTO reconciliation.break_note (id, break_id, body, author,"
                + " author_type, added_at, correlation_id) VALUES (?, ?,"
                + " 'the counterparty confirmed by phone; awaiting their correction file',"
                + " 'op-1', 'EMPLOYEE', now(), 'corr')", IDS.next(), breakId);
        application.rollback();
    }

    // ----------------------------------------------------------------- fixtures

    private BreakRegister.NewBreak newBreak(
            BreakType type,
            BreakCause cause,
            BreakRegister.Subject subject,
            long valueMinor,
            Optional<ExpectationDirection> direction) throws SQLException {
        return new BreakRegister.NewBreak(
                IDS.next(),
                type,
                cause,
                subject,
                PSP_SOURCE,
                activeRuleSet(),
                Money.ofPersisted(valueMinor, EUR, 2),
                direction,
                Optional.empty(),
                Optional.of(InternalClassification.UNKNOWN),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                PLATFORM,
                Instant.now(CLOCK),
                CorrelationId.generate(IDS));
    }

    private UUID raiseFor(UUID externalItemId, long valueMinor) throws SQLException {
        return register.raise(
                        application,
                        newBreak(BreakType.UNKNOWN_EXTERNAL, BreakCause.GRACE_EXPIRED,
                                BreakRegister.Subject.externalItem(externalItemId),
                                valueMinor, Optional.empty()))
                .breakId();
    }

    private void park(UUID itemId, UUID breakId, long minor) {
        asPlatform(() -> suspense.park(
                application,
                new Suspense.ParkCommand(
                        PSP_SOURCE, DECIDED_ON,
                        List.of(new Suspense.ParkedItem(itemId, breakId,
                                Money.ofPersisted(minor, EUR, 2), positionAccount)),
                        PLATFORM, Instant.now(CLOCK), CorrelationId.generate(IDS))));
    }

    /** The posting path audits and correlates, so it runs as the platform inside a
     * correlation scope (INV-AUD-01, INV-LED-05) — the established test idiom. */
    @SuppressWarnings("try")
    private static <T> T asPlatform(java.util.function.Supplier<T> work) {
        try (com.finapp.platform.security.SecurityContext.Scope platform =
                        com.finapp.platform.security.SecurityContext.enterSystem();
                com.finapp.platform.correlation.CorrelationContext.Scope scope =
                        com.finapp.platform.correlation.CorrelationContext.enter(
                                com.finapp.sharedkernel.correlation.Correlation
                                        .startingWith(CorrelationId.generate(IDS)))) {
            return work.get();
        }
    }

    private UUID activeRuleSet() throws SQLException {
        return ruleSets.activeFor(application, PSP_SOURCE).id();
    }

    /** A run and {@code n} PENDING items, one per direction given (cycled). */
    private List<UUID> seedItems(
            int n, LocalDate settlementDate, ExpectationDirection... directions)
            throws SQLException {
        UUID runId = IDS.next();
        runs.birth(
                application,
                new ReconciliationRuns.NewRun(
                        runId,
                        PSP_SOURCE,
                        Optional.of(IDS.next()),
                        RunKind.BATCH,
                        activeRuleSet(),
                        SETTLED_ON,
                        Optional.of(SEQUENCES.incrementAndGet()),
                        n,
                        Optional.empty(),
                        Optional.empty(),
                        PLATFORM,
                        Instant.now(CLOCK),
                        CorrelationId.generate(IDS)));
        List<ExternalItems.NewItem> newItems = new ArrayList<>();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            UUID id = IDS.next();
            ids.add(id);
            byte[] fingerprint = new byte[32];
            new SecureRandom().nextBytes(fingerprint);
            newItems.add(
                    new ExternalItems.NewItem(
                            id,
                            runId,
                            PSP_SOURCE,
                            IDS.next(),
                            i + 1,
                            ExternalLineType.CAPTURE,
                            directions[i % directions.length],
                            Money.ofPersisted(1_000_00, EUR, 2),
                            AccountPurpose.SETTLEMENT_CLEARING,
                            SETTLED_ON,
                            Optional.of(settlementDate),
                            Optional.of(settlementDate),
                            fingerprint,
                            Map.of(ItemKeyKind.PSP_CAPTURE_REF,
                                    "CAP-" + id.toString().substring(0, 13)),
                            Instant.now(CLOCK),
                            CorrelationId.generate(IDS)));
        }
        items.birthAll(application, PLATFORM, newItems);
        return ids;
    }

    private UUID openExpectation(String operationRef, String keyValue) throws SQLException {
        expectations.open(
                application,
                new NewExpectation(
                        ExpectationKind.CARD_CAPTURE,
                        operationRef,
                        "payment-capture:" + operationRef,
                        PSP_SOURCE,
                        AccountPurpose.SETTLEMENT_CLEARING,
                        positionAccount,
                        ExpectationDirection.INBOUND,
                        Money.ofPersisted(55_00, EUR, 2),
                        Optional.of(IDS.next()),
                        SETTLED_ON,
                        Optional.empty(),
                        SETTLED_ON.plusDays(3),
                        activeRuleSet(),
                        List.of(new NewExpectation.ExpectationKey(
                                KeyKind.PSP_CAPTURE_REF, keyValue)),
                        PLATFORM,
                        Instant.now(CLOCK),
                        CorrelationId.generate(IDS)));
        return (UUID) queryOne(
                "SELECT id FROM reconciliation.expectation WHERE operation_ref = ?",
                operationRef);
    }

    private static void rawBreak(
            Connection connection, UUID id, String type, String cause, UUID itemId)
            throws SQLException {
        try (PreparedStatement insert =
                connection.prepareStatement(
                        "INSERT INTO reconciliation.break (id, type, cause, status,"
                                + " severity, source_id, rule_set_id, external_item_id,"
                                + " value_at_issue_minor, currency, scale, residual_version,"
                                + " raised_at, status_changed_at, correlation_id) VALUES"
                                + " (?, ?, ?, 'OPEN', 'HIGH', ?, ?, ?, 1, 'EUR', 2, 0,"
                                + " now(), now(), 'corr')")) {
            insert.setObject(1, id);
            insert.setString(2, type);
            insert.setString(3, cause);
            insert.setObject(4, PSP_SOURCE);
            insert.setObject(5, PSP_RULE_SET);
            insert.setObject(6, itemId);
            insert.executeUpdate();
        }
    }

    private static void rawSuspenseItem(Connection connection, UUID breakId)
            throws SQLException {
        try (PreparedStatement insert =
                connection.prepareStatement(
                        "INSERT INTO reconciliation.suspense_item (id, break_id, origin,"
                                + " origin_ref, side, amount_minor, currency, scale,"
                                + " released_minor, status, opened_on, entry_id,"
                                + " status_changed_at, correlation_id) VALUES (?, ?,"
                                + " 'BANK_UNATTRIBUTED', ?, 'CREDIT', 100, 'EUR', 2, 0,"
                                + " 'OPEN', now(), ?, now(), 'corr')")) {
            insert.setObject(1, IDS.next());
            insert.setObject(2, breakId);
            insert.setString(3, "raw-" + UUID.randomUUID());
            insert.setObject(4, IDS.next());
            insert.executeUpdate();
        }
    }

    private List<String> lines(UUID entryId) throws SQLException {
        try (PreparedStatement read =
                application.prepareStatement(
                        "SELECT ledger_account_id::text || ':' || direction || ':' ||"
                                + " amount_minor::text AS line FROM ledger.journal_line"
                                + " WHERE entry_id = ?")) {
            read.setObject(1, entryId);
            try (ResultSet rows = read.executeQuery()) {
                List<String> lines = new ArrayList<>();
                while (rows.next()) {
                    lines.add(rows.getString("line"));
                }
                return lines;
            }
        }
    }

    private Object queryOne(String sql, Object... args) throws SQLException {
        try (PreparedStatement read = application.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                read.setObject(i + 1, args[i]);
            }
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).as("one row for: %s", sql).isTrue();
                return row.getObject(1);
            }
        }
    }

    private long count(String sql, Object... args) throws SQLException {
        return ((Number) queryOne(sql, args)).longValue();
    }

    private String scalar(String sql, Object... args) throws SQLException {
        return String.valueOf(queryOne(sql, args));
    }

    private void execute(String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = application.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.executeUpdate();
        }
    }

    /** The refusal's message, with the failed statement rolled back to a clean savepoint. */
    private String refusal(String sql, Object... args) throws SQLException {
        java.sql.Savepoint savepoint = application.setSavepoint();
        try (PreparedStatement statement = application.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.executeUpdate();
            application.rollback(savepoint);
            return "";
        } catch (SQLException refused) {
            application.rollback(savepoint);
            return String.valueOf(refused.getMessage());
        }
    }
}
