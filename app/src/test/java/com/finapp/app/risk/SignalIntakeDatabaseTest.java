package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.inbox.InboxConsumer;
import com.finapp.platform.inbox.InboxKey;
import com.finapp.platform.inbox.JdbcInboxRecordStore;
import com.finapp.platform.inbox.ReceivedEvent;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.database.SimulatedInstance;
import com.finapp.risk.JdbcRiskSignalStore;
import com.finapp.risk.MonitoringHints;
import com.finapp.risk.RiskSignal;
import com.finapp.risk.RiskSignalStore;
import com.finapp.risk.RiskSignals;
import com.finapp.risk.SignalKind;
import com.finapp.risk.SignalReadings;
import com.finapp.risk.SignalSubject;
import com.finapp.risk.SubjectKind;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-sig on PostgreSQL, across instances (`P13-TSK-005`; {@code PHASE_13_PLAN.md} section 7.4 "Duplicate / delayed /
 * replayed events"; {@code INV-RSK-06}, {@code INV-IDEM-04}, {@code INV-EVT-04}): the same event three times - a
 * second delivery the inbox absorbs, a third past the dedupe the unique absorbs - is one signal and one announcement;
 * ten instances delivering one event at once leave one row; reordered events each keep their own instant; an
 * attribute outside its bound is refused with nothing written, the dedupe record included; and every reading is one
 * range scan on the subject index. The consumer is wired exactly as {@code RiskBeans} wires it, driven through the
 * platform's own {@code InboxConsumer} so the dedupe row and the signal share the transaction they share in
 * production.
 *
 * <p>Written and owed a first run under the standing criterion-7 deviation (C5).
 */
@Tag("database")
@DisplayName("signal intake, across instances (P13-TSK-005)")
@SuppressWarnings("try") // correlation Scopes are used for their close side effect
class SignalIntakeDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Duration RETENTION = Duration.ofDays(14);

    private final RiskSignalStore store = new JdbcRiskSignalStore();
    private final RiskSignals signals = new RiskSignals(store, new JdbcOutboxWriter(), IDS, CLOCK);
    private final InboxConsumer<Connection> inbox = new InboxConsumer<>(new JdbcInboxRecordStore(), CLOCK, RETENTION);

    @Test
    @DisplayName("the same event twice records once - and a third time, past the dedupe, still once")
    void theSameEventTwiceRecordsOnce() throws SQLException {
        UUID account = UUID.randomUUID();
        ReceivedEvent event = accountOpened(EventId.next(IDS), account);
        RiskSignalIntake intake = intake(RiskSignalIntake.Source.ACCOUNT_OPENED);

        try (SimulatedInstance a = SimulatedInstance.inAgreementWithTheServer();
                SimulatedInstance b = SimulatedInstance.inAgreementWithTheServer()) {
            assertThat(deliver(a, intake, event)).isEqualTo(InboxConsumer.Outcome.PROCESSED);
            assertThat(deliver(b, intake, event))
                    .as("the inbox absorbs the redelivery on another instance")
                    .isEqualTo(InboxConsumer.Outcome.SKIPPED_DUPLICATE);
        }
        // The third delivery, after the dedupe record is gone (its retention lapsed, or it was lost): the handler
        // runs again and the unique on source_event_id absorbs it - no second row, no second announcement.
        forgetDedupe(event);
        try (SimulatedInstance c = SimulatedInstance.inAgreementWithTheServer()) {
            assertThat(deliver(c, intake, event)).isEqualTo(InboxConsumer.Outcome.PROCESSED);
        }

        assertThat(signalCount(event.eventId())).isEqualTo(1);
        assertThat(announcementCount(account)).as("one RiskSignalRecorded, born once").isEqualTo(1);
    }

    @Test
    @DisplayName("ten instances delivering one event at once leave one signal and one announcement")
    void tenInstancesRecordOnce() throws Exception {
        UUID account = UUID.randomUUID();
        ReceivedEvent event = accountOpened(EventId.next(IDS), account);
        RiskSignalIntake intake = intake(RiskSignalIntake.Source.ACCOUNT_OPENED);
        int instances = 10;
        ExecutorService pool = Executors.newFixedThreadPool(instances);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<InboxConsumer.Outcome>> outcomes = new ArrayList<>();
        List<InboxConsumer.Outcome> seen = new ArrayList<>();
        try {
            for (int i = 0; i < instances; i++) {
                outcomes.add(pool.submit(() -> {
                    try (SimulatedInstance instance = SimulatedInstance.inAgreementWithTheServer()) {
                        go.await();
                        return deliver(instance, intake, event);
                    }
                }));
            }
            go.countDown();
            for (Future<InboxConsumer.Outcome> outcome : outcomes) {
                seen.add(outcome.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        // One instance handled it; every other was told SKIPPED_DUPLICATE (it lost after the winner committed) or
        // CONTENDED (it lost while the winner was still in flight) in any mix - the inbox PK is the arbiter, and a
        // loser told CONTENDED is simply redelivered in production.
        assertThat(seen).hasSize(instances).containsOnlyOnce(InboxConsumer.Outcome.PROCESSED);
        assertThat(seen.stream().filter(outcome -> outcome != InboxConsumer.Outcome.PROCESSED))
                .allMatch(outcome -> outcome == InboxConsumer.Outcome.SKIPPED_DUPLICATE
                        || outcome == InboxConsumer.Outcome.CONTENDED);
        assertThat(signalCount(event.eventId())).isEqualTo(1);
        assertThat(announcementCount(account)).isEqualTo(1);
    }

    @Test
    @DisplayName("reordered events each record with their own observed instant, and the latest is the latest observed")
    void reorderedEventsRecordEach() throws SQLException {
        UUID identity = UUID.randomUUID();
        Instant earlier = Instant.now(CLOCK).minus(Duration.ofDays(3));
        Instant later = Instant.now(CLOCK).minus(Duration.ofHours(1));
        ReceivedEvent first = credentialChanged(EventId.next(IDS), identity, earlier);
        ReceivedEvent second = credentialChanged(EventId.next(IDS), identity, later);
        RiskSignalIntake intake = intake(RiskSignalIntake.Source.CREDENTIAL_CHANGED);

        try (SimulatedInstance a = SimulatedInstance.inAgreementWithTheServer()) {
            // Delivered later-first: the order of delivery is not the order of the facts.
            assertThat(deliver(a, intake, second)).isEqualTo(InboxConsumer.Outcome.PROCESSED);
            assertThat(deliver(a, intake, first)).isEqualTo(InboxConsumer.Outcome.PROCESSED);
        }

        SignalSubject subject = SignalSubject.of(SubjectKind.IDENTITY, identity);
        try (Connection app = DatabaseRoles.application()) {
            SignalReadings readings = new SignalReadings(store);
            assertThat(readings.countWithin(app, subject, SignalKind.CREDENTIAL_CHANGED, Instant.now(CLOCK),
                    Duration.ofDays(7))).isEqualTo(2);
            assertThat(readings.countWithin(app, subject, SignalKind.CREDENTIAL_CHANGED, Instant.now(CLOCK),
                    Duration.ofDays(1))).isEqualTo(1);
            assertThat(readings.latest(app, subject, SignalKind.CREDENTIAL_CHANGED))
                    .map(RiskSignal::source).map(RiskSignal.SourceEvent::id)
                    .as("the latest observed, not the latest recorded")
                    .contains(second.eventId());
            assertThat(readings.age(app, subject, SignalKind.CREDENTIAL_CHANGED, Instant.now(CLOCK)))
                    .hasValueSatisfying(age -> assertThat(age).isGreaterThan(Duration.ofDays(2)));
            assertThat(readings.isNovel(app, subject)).isFalse();
            assertThat(readings.isNovel(app, SignalSubject.of(SubjectKind.IDENTITY, UUID.randomUUID()))).isTrue();
        }
    }

    @Test
    @DisplayName("an attribute outside its bound is refused with nothing written - no signal and no dedupe record - so the redelivery retries")
    void anAttributeOutsideItsBoundIsRefused() throws SQLException {
        UUID loan = UUID.randomUUID();
        ReceivedEvent event = new ReceivedEvent(EventId.next(IDS), "lending.LoanDefaulted", 1, 1, loan, "loan",
                Instant.now(CLOCK), "lending", CorrelationId.of(UUID.randomUUID().toString()), CausationId.of("c"),
                "application/json",
                EventPayload.of().with("loanId", loan.toString()).with("product", "PERSONAL_LOAN")
                        .with("businessDate", "2026-10-22").with("daysPastDue", "99999").with("bucket", "B4_90_PLUS")
                        .with("defaulted", "true").with("agreementVersion", "1").toBytes());
        RiskSignalIntake intake = intake(RiskSignalIntake.Source.LOAN_DEFAULTED);

        try (SimulatedInstance a = SimulatedInstance.inAgreementWithTheServer()) {
            assertThatThrownBy(() -> deliverWithoutCommit(a, intake, event))
                    .isInstanceOf(IllegalArgumentException.class);
            a.rollback();
        }
        assertThat(signalCount(event.eventId())).isZero();
        assertThat(dedupeCount(event.eventId())).as("rolled back with the effect, so the redelivery is handled").isZero();
    }

    @Test
    @DisplayName("every reading is one range scan on risk_signal_by_subject")
    void theReadingsIndexAnswersInOneScan() throws SQLException {
        UUID identity = UUID.randomUUID();
        try (SimulatedInstance a = SimulatedInstance.inAgreementWithTheServer()) {
            deliver(a, intake(RiskSignalIntake.Source.CREDENTIAL_CHANGED),
                    credentialChanged(EventId.next(IDS), identity, Instant.now(CLOCK)));
        }
        try (Connection app = DatabaseRoles.application(); Statement explain = app.createStatement()) {
            // A tiny table lets the planner prefer a sequential scan; what is asserted is that the index answers
            // each predicate shape, which is what matters at a size where the planner would choose it.
            explain.execute("SET enable_seqscan = off");
            for (String query : List.of(
                    "SELECT count(*) FROM risk.risk_signal WHERE subject_kind = 'IDENTITY' AND subject_ref = '" + identity
                            + "' AND kind = 'CREDENTIAL_CHANGED' AND observed_at >= now() - interval '7 days'",
                    "SELECT id FROM risk.risk_signal WHERE subject_kind = 'IDENTITY' AND subject_ref = '" + identity
                            + "' AND kind = 'CREDENTIAL_CHANGED' ORDER BY observed_at DESC, recorded_at DESC LIMIT 1",
                    "SELECT min(observed_at) FROM risk.risk_signal WHERE subject_kind = 'IDENTITY' AND subject_ref = '"
                            + identity + "' AND kind = 'CREDENTIAL_CHANGED'",
                    "SELECT EXISTS (SELECT 1 FROM risk.risk_signal WHERE subject_kind = 'IDENTITY' AND subject_ref = '"
                            + identity + "')")) {
                StringBuilder plan = new StringBuilder();
                try (ResultSet rows = explain.executeQuery("EXPLAIN " + query)) {
                    while (rows.next()) {
                        plan.append(rows.getString(1)).append('\n');
                    }
                }
                assertThat(plan.toString()).as(query).contains("risk_signal_by_subject");
            }
        }
    }

    // -----------------------------------------------------------------

    private RiskSignalIntake intake(RiskSignalIntake.Source source) {
        return new RiskSignalIntake(source, signals, MonitoringHints.NONE, IDS, RiskFlowScope.NONE);
    }

    /** One delivery, exactly as the shell drives it: the dedupe record and the handler in one transaction, committed. */
    private InboxConsumer.Outcome deliver(SimulatedInstance instance, RiskSignalIntake intake, ReceivedEvent event)
            throws SQLException {
        InboxConsumer.Outcome outcome = deliverWithoutCommit(instance, intake, event);
        if (outcome == InboxConsumer.Outcome.CONTENDED) {
            instance.rollback();
        } else {
            instance.commit();
        }
        return outcome;
    }

    private InboxConsumer.Outcome deliverWithoutCommit(SimulatedInstance instance, RiskSignalIntake intake,
            ReceivedEvent event) {
        try (CorrelationContext.Scope scope = CorrelationContext.enter(
                new Correlation(event.correlationId(), CausationId.of(event.eventId().value().toString())))) {
            return inbox.consume(instance.connection(), new InboxKey(intake.consumerName(),
                    event.eventId().value().toString()), event.eventType(),
                    connection -> intake.handle(connection, event));
        }
    }

    private static ReceivedEvent accountOpened(EventId eventId, UUID account) {
        return new ReceivedEvent(eventId, "accounts.AccountOpened", 1, 1, account, "customer_account",
                Instant.now(CLOCK), "accounts", CorrelationId.of(UUID.randomUUID().toString()),
                CausationId.of("p13-tsk-005"), "application/json",
                EventPayload.of().with("productType", "WALLET").toBytes());
    }

    private static ReceivedEvent credentialChanged(EventId eventId, UUID identity, Instant occurredAt) {
        return new ReceivedEvent(eventId, "identity.CredentialChanged", 1, 1, identity, "Identity", occurredAt,
                "identity", CorrelationId.of(UUID.randomUUID().toString()), CausationId.of("p13-tsk-005"),
                "application/json", EventPayload.of().with("identityId", identity.toString()).toBytes());
    }

    private static void forgetDedupe(ReceivedEvent event) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator();
                PreparedStatement forget = migrator.prepareStatement(
                        "DELETE FROM platform.inbox_message WHERE consumer = ? AND dedupe_key = ?")) {
            forget.setString(1, RiskSignalIntake.CONSUMER);
            forget.setString(2, event.eventId().value().toString());
            assertThat(forget.executeUpdate()).as("the dedupe record existed").isEqualTo(1);
        }
    }

    private static long signalCount(EventId sourceEvent) throws SQLException {
        return countOf("SELECT count(*) FROM risk.risk_signal WHERE source_event_id = ?", sourceEvent.value());
    }

    private static long dedupeCount(EventId sourceEvent) throws SQLException {
        return countOf("SELECT count(*) FROM platform.inbox_message WHERE consumer = '" + RiskSignalIntake.CONSUMER
                + "' AND dedupe_key = ?", sourceEvent.value().toString());
    }

    /** Announcements are keyed by the signal, so the account is reached through the signal's subject reference. */
    private static long announcementCount(UUID account) throws SQLException {
        return countOf("SELECT count(*) FROM platform.outbox_event e WHERE e.event_type = 'risk.RiskSignalRecorded'"
                + " AND EXISTS (SELECT 1 FROM risk.risk_signal s WHERE s.id = e.aggregate_id AND s.subject_ref = ?)",
                account.toString());
    }

    private static long countOf(String sql, Object argument) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement count = migrator.prepareStatement(sql)) {
            count.setObject(1, argument);
            try (ResultSet row = count.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
