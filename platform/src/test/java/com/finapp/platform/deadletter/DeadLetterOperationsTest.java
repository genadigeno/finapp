package com.finapp.platform.deadletter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.api.ApiException;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.PlatformAuditAction;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.InMemoryDeadLetterStore;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The dead-letter doors' decisions, over an in-memory store (`P15-TSK-028`): every act's happy path, its refusals, its audit
 * record. The window is the one given to the constructor, the store judging each event against it as the SQL does.
 */
@DisplayName("the dead-letter doors decide, audit and refuse (P15-TSK-028)")
class DeadLetterOperationsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-08T10:00:00Z"), ZoneOffset.UTC);
    private static final Duration RETENTION = Duration.ofDays(14);
    private static final Connection UOW = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                throw new UnsupportedOperationException("the store is a fake");
            });
    private static final Actor FIRST = new Actor("operator-1", ActorType.EMPLOYEE);
    private static final Actor SECOND = new Actor("operator-2", ActorType.EMPLOYEE);
    private static final CorrelationId FLOW = CorrelationId.of("flow-1");
    private static final String TYPE = "probe.SomethingHappened";
    private static final Instant FROM = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-02T00:00:00Z");
    private static final Instant INSIDE = Instant.parse("2026-11-01T06:00:00Z");

    private final InMemoryDeadLetterStore store = new InMemoryDeadLetterStore();
    private final List<AuditRecord> audited = new ArrayList<>();
    private boolean naturallyIdempotent;
    private final DeadLetterOperations operations = new DeadLetterOperations(store, (uow, record) -> audited.add(record),
            new IdGenerator(CLOCK, new SecureRandom()), CLOCK, RETENTION, type -> naturallyIdempotent);

    // ----------------------------------------------------------------- retry

    @Test
    @DisplayName("a retry returns the abandoned event to the relay and records who and why")
    void aRetryReturnsTheEventToTheRelay() {
        UUID id = UUID.randomUUID();
        store.abandoned(id);

        operations.retry(UOW, id, FIRST, "the topic exists now", FLOW);

        assertThat(store.event(id).abandoned).isFalse();
        assertThat(onlyRecord().operation()).isEqualTo(PlatformAuditAction.OUTBOX_EVENT_RETRY_AUTHORISED);
        assertThat(onlyRecord().actor()).isEqualTo(FIRST);
        assertThat(onlyRecord().reason()).contains("the topic exists now");
        assertThat(onlyRecord().targetId()).isEqualTo(id.toString());
        assertThat(onlyRecord().correlationId()).isEqualTo(FLOW);
    }

    @Test
    @DisplayName("a retry needs an abandoned event, a reason, and no standing discard")
    void aRetryIsRefused() {
        UUID unknown = UUID.randomUUID();
        assertRefused(() -> operations.retry(UOW, unknown, FIRST, "why", FLOW), DeadLetterErrorCode.NOT_FOUND);

        UUID notAbandoned = UUID.randomUUID();
        store.published(notAbandoned, TYPE, Duration.ofDays(1), INSIDE);
        assertRefused(() -> operations.retry(UOW, notAbandoned, FIRST, "why", FLOW), DeadLetterErrorCode.NOT_ABANDONED);

        UUID blank = UUID.randomUUID();
        store.abandoned(blank);
        assertRefused(() -> operations.retry(UOW, blank, FIRST, "  ", FLOW), DeadLetterErrorCode.REASON_REQUIRED);

        UUID proposed = UUID.randomUUID();
        store.abandoned(proposed);
        operations.proposeDiscard(UOW, DeadLetterStore.SubjectKind.OUTBOX_EVENT, proposed, FIRST, "unpublishable", FLOW);
        assertRefused(() -> operations.retry(UOW, proposed, SECOND, "try again", FLOW), DeadLetterErrorCode.DISCARD_STANDING);
        assertThat(store.event(proposed).abandoned).as("a standing discard stops the retry").isTrue();
    }

    @Test
    @DisplayName("a replay that was abandoned on its way out answers to the replay's rule when retried")
    void aRetriedReplayIsJudgedAgain() {
        UUID stale = UUID.randomUUID();
        InMemoryDeadLetterStore.Event event = store.abandoned(stale);
        event.replayed = true;
        event.anchorAge = RETENTION;
        assertRefused(() -> operations.retry(UOW, stale, FIRST, "why", FLOW), DeadLetterErrorCode.REPLAY_OUTSIDE_DEDUPE_WINDOW);
        assertThat(event.abandoned).isTrue();

        event.anchorAge = RETENTION.minusHours(1);
        operations.retry(UOW, stale, FIRST, "why", FLOW);
        assertThat(store.extended).as("it publishes now, so the dedupe rows are kept from now").containsExactly(List.of(stale));
    }

    @Test
    @DisplayName("an event never published has nothing to deduplicate, so a retry is not judged against the window")
    void aFirstPublicationIsNotJudged() {
        UUID id = UUID.randomUUID();
        store.abandoned(id).anchorAge = RETENTION.plusDays(100);

        operations.retry(UOW, id, FIRST, "why", FLOW);

        assertThat(store.event(id).abandoned).isFalse();
        assertThat(store.extended).isEmpty();
    }

    @Test
    @DisplayName("a discarded event cannot be retried")
    void aDiscardedEventCannotBeRetried() {
        UUID id = UUID.randomUUID();
        store.abandoned(id);
        UUID discard = operations.proposeDiscard(UOW, DeadLetterStore.SubjectKind.OUTBOX_EVENT, id, FIRST, "why", FLOW);
        operations.approveDiscard(UOW, discard, SECOND, "agreed", FLOW);

        assertRefused(() -> operations.retry(UOW, id, FIRST, "why", FLOW), DeadLetterErrorCode.NOT_ABANDONED);
    }

    // ----------------------------------------------------------------- discard

    @Test
    @DisplayName("a discard is two people, and the row stays with the decision recorded")
    void aDiscardNeedsASecondPerson() {
        UUID id = UUID.randomUUID();
        store.abandoned(id);

        UUID discard = operations.proposeDiscard(UOW, DeadLetterStore.SubjectKind.OUTBOX_EVENT, id, FIRST, "unpublishable", FLOW);

        assertThat(store.event(id).discarded).as("a proposal changes nothing").isFalse();
        assertThat(audited.getLast().operation()).isEqualTo(PlatformAuditAction.DEAD_LETTER_DISCARD_PROPOSED);

        assertRefused(() -> operations.approveDiscard(UOW, discard, FIRST, "my own", FLOW), DeadLetterErrorCode.SELF_APPROVAL_REFUSED);
        assertThat(store.event(id).discarded).isFalse();

        operations.approveDiscard(UOW, discard, SECOND, "agreed, it is unpublishable", FLOW);

        assertThat(store.event(id).discarded).as("recorded, and the row stays").isTrue();
        assertThat(store.event(id).abandoned).as("kept, so a previous release still stops at it").isTrue();
        assertThat(store.decision(discard)).containsExactly("APPROVED", "operator-2");
        assertThat(audited.getLast().operation()).isEqualTo(PlatformAuditAction.OUTBOX_EVENT_DISCARDED);
        assertThat(audited.getLast().actor()).isEqualTo(SECOND);
    }

    @Test
    @DisplayName("the second person is judged by who they are, never by the type this request gave them")
    void theSamePersonIsTheSamePersonWhateverTheirType() {
        UUID id = UUID.randomUUID();
        store.abandoned(id);
        UUID discard = operations.proposeDiscard(UOW, DeadLetterStore.SubjectKind.OUTBOX_EVENT, id, new Actor("person-9", ActorType.CUSTOMER), "why", FLOW);

        assertRefused(
                () -> operations.approveDiscard(UOW, discard, new Actor("person-9", ActorType.EMPLOYEE), "mine", FLOW),
                DeadLetterErrorCode.SELF_APPROVAL_REFUSED);
    }

    @Test
    @DisplayName("a proposal can be turned down, even by its proposer, and nothing is discarded")
    void aRejectionDiscardsNothing() {
        UUID id = UUID.randomUUID();
        store.abandoned(id);
        UUID discard = operations.proposeDiscard(UOW, DeadLetterStore.SubjectKind.OUTBOX_EVENT, id, FIRST, "why", FLOW);

        operations.rejectDiscard(UOW, discard, FIRST, "withdrawn", FLOW);

        assertThat(store.event(id).discarded).isFalse();
        assertThat(store.standingCount()).isZero();
        assertThat(audited.getLast().operation()).isEqualTo(PlatformAuditAction.DEAD_LETTER_DISCARD_REJECTED);
    }

    @Test
    @DisplayName("a proposal needs a subject that can be discarded, and only one may stand")
    void aProposalIsRefused() {
        UUID published = UUID.randomUUID();
        store.published(published, TYPE, Duration.ofDays(1), INSIDE);
        assertRefused(() -> operations.proposeDiscard(UOW, DeadLetterStore.SubjectKind.OUTBOX_EVENT, published, FIRST, "why", FLOW), DeadLetterErrorCode.NOT_ABANDONED);

        UUID id = UUID.randomUUID();
        store.abandoned(id);
        operations.proposeDiscard(UOW, DeadLetterStore.SubjectKind.OUTBOX_EVENT, id, FIRST, "why", FLOW);
        assertRefused(() -> operations.proposeDiscard(UOW, DeadLetterStore.SubjectKind.OUTBOX_EVENT, id, SECOND, "why", FLOW), DeadLetterErrorCode.DISCARD_STANDING);

        assertRefused(() -> operations.proposeDiscard(UOW, DeadLetterStore.SubjectKind.INBOX_RECORD, UUID.randomUUID(), FIRST, "why", FLOW), DeadLetterErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("a decided proposal cannot be decided again")
    void aDecidedProposalIsClosed() {
        UUID id = UUID.randomUUID();
        store.abandoned(id);
        UUID discard = operations.proposeDiscard(UOW, DeadLetterStore.SubjectKind.OUTBOX_EVENT, id, FIRST, "why", FLOW);
        operations.rejectDiscard(UOW, discard, SECOND, "no", FLOW);

        assertRefused(() -> operations.approveDiscard(UOW, discard, SECOND, "yes", FLOW), DeadLetterErrorCode.DISCARD_NOT_STANDING);
        assertRefused(() -> operations.rejectDiscard(UOW, discard, SECOND, "no", FLOW), DeadLetterErrorCode.DISCARD_NOT_STANDING);
        assertRefused(() -> operations.approveDiscard(UOW, UUID.randomUUID(), SECOND, "yes", FLOW), DeadLetterErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("a parked record is discarded by two people too, and is not deleted")
    void aParkedRecordIsDiscardedByTwoPeople() {
        UUID parkId = UUID.randomUUID();
        store.parked(parkId, "probe.consumer", UUID.randomUUID().toString());

        UUID discard = operations.proposeDiscard(UOW, DeadLetterStore.SubjectKind.INBOX_RECORD, parkId, FIRST, "cannot be applied", FLOW);
        assertRefused(() -> operations.approveDiscard(UOW, discard, FIRST, "mine", FLOW), DeadLetterErrorCode.SELF_APPROVAL_REFUSED);
        assertThat(store.parkedRow(parkId).state).isEqualTo("PARKED");

        operations.approveDiscard(UOW, discard, SECOND, "agreed", FLOW);

        assertThat(store.parkedRow(parkId).state).isEqualTo("DISCARDED");
        assertThat(audited.getLast().operation()).isEqualTo(PlatformAuditAction.INBOX_PARKED_RECORD_DISCARDED);
    }

    // ----------------------------------------------------------------- replay

    @Test
    @DisplayName("a replay inside the window requeues, and keeps the dedupe rows alive for a full retention from now")
    void aReplayRequeuesAndExtendsTheDedupe() {
        UUID id = UUID.randomUUID();
        store.published(id, TYPE, Duration.ofDays(5), INSIDE);

        DeadLetterOperations.Replay replay = operations.replay(UOW, TYPE, FROM, TO, FIRST, "a handler arrived", FLOW);

        assertThat(replay.requeued()).isEqualTo(1);
        assertThat(store.event(id).requeues).isEqualTo(1);
        assertThat(store.extended).as("the same transaction extends what the check relied on").containsExactly(List.of(id));
        assertThat(store.extendedFor).containsExactly(RETENTION);
        assertThat(onlyRecord().operation()).isEqualTo(PlatformAuditAction.OUTBOX_EVENT_REPLAY_ORDERED);
        assertThat(onlyRecord().reason()).contains("a handler arrived");
        assertThat(onlyRecord().targetId()).isEqualTo(TYPE);
    }

    @Test
    @DisplayName("a replay past the dedupe window is refused whole, and nothing is requeued")
    void aReplayPastTheWindowIsRefused() {
        UUID inside = UUID.randomUUID();
        store.published(inside, TYPE, Duration.ofDays(5), INSIDE);
        UUID past = UUID.randomUUID();
        store.published(past, TYPE, Duration.ofDays(14), INSIDE.plusSeconds(1));

        assertRefused(() -> operations.replay(UOW, TYPE, FROM, TO, FIRST, "why", FLOW), DeadLetterErrorCode.REPLAY_OUTSIDE_DEDUPE_WINDOW);

        assertThat(store.event(inside).requeues).as("all or nothing").isZero();
        assertThat(store.event(past).requeues).isZero();
        assertThat(store.extended).isEmpty();
    }

    @Test
    @DisplayName("the window is exactly the retention: an event at the limit is out, a moment under is in")
    void theWindowIsTheRetention() {
        UUID atTheLimit = UUID.randomUUID();
        store.published(atTheLimit, TYPE, RETENTION, INSIDE);
        assertRefused(() -> operations.replay(UOW, TYPE, FROM, TO, FIRST, "why", FLOW), DeadLetterErrorCode.REPLAY_OUTSIDE_DEDUPE_WINDOW);

        store.event(atTheLimit).anchorAge = RETENTION.minusMillis(1);
        assertThat(operations.replay(UOW, TYPE, FROM, TO, FIRST, "why", FLOW).requeued()).isEqualTo(1);
    }

    @Test
    @DisplayName("past the window it is allowed only when every consumer of the type is naturally idempotent")
    void naturallyIdempotentTypesMayBeReplayedPastTheWindow() {
        UUID past = UUID.randomUUID();
        store.published(past, TYPE, Duration.ofDays(100), INSIDE);
        naturallyIdempotent = true;

        assertThat(operations.replay(UOW, TYPE, FROM, TO, FIRST, "why", FLOW).requeued()).isEqualTo(1);
    }

    @Test
    @DisplayName("a replay needs a reason, one type and a real window, and stops at its size")
    void aReplayIsBounded() {
        assertRefused(() -> operations.replay(UOW, TYPE, FROM, TO, FIRST, " ", FLOW), DeadLetterErrorCode.REASON_REQUIRED);
        assertRefused(() -> operations.replay(UOW, " ", FROM, TO, FIRST, "why", FLOW), DeadLetterErrorCode.REPLAY_INVALID);
        assertRefused(() -> operations.replay(UOW, TYPE, TO, FROM, FIRST, "why", FLOW), DeadLetterErrorCode.REPLAY_INVALID);
        assertRefused(() -> operations.replay(UOW, TYPE, FROM, FROM, FIRST, "why", FLOW), DeadLetterErrorCode.REPLAY_INVALID);

        for (int i = 0; i <= DeadLetterOperations.MAX_REPLAY_EVENTS; i++) {
            store.published(UUID.randomUUID(), TYPE, Duration.ofDays(1), INSIDE);
        }
        assertRefused(() -> operations.replay(UOW, TYPE, FROM, TO, FIRST, "why", FLOW), DeadLetterErrorCode.REPLAY_TOO_LARGE);
    }

    @Test
    @DisplayName("nothing selected is still an audited order, of nothing")
    void aReplayOfNothingIsRecorded() {
        assertThat(operations.replay(UOW, TYPE, FROM, TO, FIRST, "why", FLOW).requeued()).isZero();
        assertThat(onlyRecord().changeSummary()).hasValueSatisfying(summary -> assertThat(summary).contains("requeued=0"));
    }

    // ----------------------------------------------------------------- release

    @Test
    @DisplayName("a release returns the record to its consumer and republishes its event, under the replay's rule")
    void aReleaseRepublishes() {
        UUID eventId = UUID.randomUUID();
        store.published(eventId, TYPE, Duration.ofDays(3), INSIDE);
        UUID parkId = UUID.randomUUID();
        store.parked(parkId, "probe.consumer", eventId.toString());

        operations.release(UOW, parkId, FIRST, "the handler is fixed", FLOW);

        assertThat(store.parkedRow(parkId).state).isEqualTo("FAILING");
        assertThat(store.event(eventId).requeues).isEqualTo(1);
        assertThat(store.extended).containsExactly(List.of(eventId));
        assertThat(onlyRecord().operation()).isEqualTo(PlatformAuditAction.INBOX_PARKED_RECORD_RELEASED);
    }

    @Test
    @DisplayName("a release is refused past the window, for a record with no event, and when it is not parked")
    void aReleaseIsRefused() {
        UUID oldEvent = UUID.randomUUID();
        store.published(oldEvent, TYPE, Duration.ofDays(20), INSIDE);
        UUID stale = UUID.randomUUID();
        store.parked(stale, "probe.consumer", oldEvent.toString());
        assertRefused(() -> operations.release(UOW, stale, FIRST, "why", FLOW), DeadLetterErrorCode.REPLAY_OUTSIDE_DEDUPE_WINDOW);
        assertThat(store.parkedRow(stale).state).isEqualTo("PARKED");
        assertThat(store.event(oldEvent).requeues).isZero();

        UUID malformed = UUID.randomUUID();
        store.parked(malformed, "finapp.probe", "malformed:finapp.probe:0:7");
        assertRefused(() -> operations.release(UOW, malformed, FIRST, "why", FLOW), DeadLetterErrorCode.NOT_RELEASABLE);

        UUID gone = UUID.randomUUID();
        store.parked(gone, "probe.consumer", UUID.randomUUID().toString());
        assertRefused(() -> operations.release(UOW, gone, FIRST, "why", FLOW), DeadLetterErrorCode.NOT_RELEASABLE);

        UUID released = UUID.randomUUID();
        store.parked(released, "probe.consumer", UUID.randomUUID().toString()).state = "FAILING";
        assertRefused(() -> operations.release(UOW, released, FIRST, "why", FLOW), DeadLetterErrorCode.NOT_PARKED);
    }

    // ----------------------------------------------------------------- reads and refusals

    @Test
    @DisplayName("the read serves the queues and is recorded, with no payload to serve")
    void theReadIsRecorded() {
        store.abandoned(UUID.randomUUID());
        store.parked(UUID.randomUUID(), "probe.consumer", UUID.randomUUID().toString());

        DeadLetterOperations.DeadLetters read = operations.read(UOW, FIRST, FLOW);

        assertThat(read.abandoned()).hasSize(1);
        assertThat(read.parked()).hasSize(1);
        assertThat(onlyRecord().operation()).isEqualTo(PlatformAuditAction.DEAD_LETTERS_READ);
    }

    @Test
    @DisplayName("a refusal is recorded DENIED, naming the act and the code, even when no reason was given")
    void aRefusalIsRecorded() {
        operations.recordRefusal(UOW, "retry", UUID.randomUUID().toString(), "deadletter.NotAbandoned", Optional.empty(), FIRST, FLOW);

        AuditRecord record = onlyRecord();
        assertThat(record.operation()).isEqualTo(PlatformAuditAction.DEAD_LETTER_ACT_REFUSED);
        assertThat(record.outcome()).isEqualTo(AuditOutcome.DENIED);
        assertThat(record.reason()).isEmpty();
        assertThat(record.changeSummary()).hasValueSatisfying(summary -> assertThat(summary).contains("act=retry", "refusal=deadletter.NotAbandoned"));
    }

    // -----------------------------------------------------------------

    private AuditRecord onlyRecord() {
        assertThat(audited).hasSize(1);
        return audited.getFirst();
    }

    private static void assertRefused(Runnable act, DeadLetterErrorCode code) {
        assertThatThrownBy(act::run).isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.errorCode()).isEqualTo(code));
    }
}
