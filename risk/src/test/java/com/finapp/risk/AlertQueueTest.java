package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CausationId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The alert queue over the in-memory store (`P13-TSK-018`; {@code PHASE_13_PLAN.md} sections 9, 11; {@code INV-RSK-08};
 * {@code DESIGN-018} D3-D7): oldest first by {@code (raised_at, id)}, of one status ({@code OPEN} unless named) and
 * optionally one kind, keyset-paged after the last alert served; every alert of the reader's own party - or whose party
 * cannot be placed - withheld, the scan reading on past them; the cursor never a withheld alert's; the page recorded
 * {@code risk.AlertRead} with the ids served and never a subject; the refusals. The database's half - the statements,
 * the indexes and a page under concurrent inserts - is {@code QueueDatabaseTest}'s (written and owed, C5).
 */
@DisplayName("the alert queue: oldest first, keyset-paged, self-dealing withheld, every page recorded (P13-TSK-018)")
class AlertQueueTest {

    private final AlertWorld world = new AlertWorld();
    private final AlertQueue queue = new AlertQueue(world.store,
            (unitOfWork, actor) -> Optional.ofNullable(world.partyOfActor.get(actor.id())),
            (unitOfWork, subject) -> Optional.ofNullable(world.partyOfSubject.get(subject)),
            (unitOfWork, record) -> world.audit.add(record), EvaluationWorld.IDS, EvaluationWorld.CLOCK);

    @Test
    @DisplayName("oldest first, paged after the last alert served: every alert once, in order, the last page without"
            + " a cursor; each page recorded risk.AlertRead naming the ids it served")
    void oldestFirstKeysetPaged() {
        List<RiskAlert> raised = raisedInOrder(5);
        Actor reader = world.analyst();
        List<RiskAlertId> served = new ArrayList<>();
        Optional<RiskAlertId> after = Optional.empty();
        int pages = 0;
        do {
            AlertQueue.Page page = queue.page(AlertWorld.UOW, AlertQueue.Filter.named(Optional.empty(),
                    Optional.empty()), after, 2, reader);
            queue.record(AlertWorld.UOW, page, reader, AlertWorld.correlation());
            page.alerts().forEach(alert -> served.add(alert.id()));
            assertThat(page.alerts()).hasSizeLessThanOrEqualTo(2);
            after = page.next();
            pages++;
        } while (after.isPresent());
        assertThat(served).containsExactlyElementsOf(raised.stream().map(RiskAlert::id).toList());
        assertThat(pages).isEqualTo(3);

        List<AuditRecord> recorded = world.audited("risk.AlertRead");
        assertThat(recorded).hasSize(3).allSatisfy(record -> {
            assertThat(record.actor()).isEqualTo(reader);
            assertThat(record.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
            assertThat(record.targetType()).isEqualTo(AlertQueue.TARGET_TYPE);
            assertThat(record.targetId()).isEqualTo("OPEN");
            for (RiskAlert alert : raised) {
                assertThat(record.changeSummary().orElseThrow()).doesNotContain(alert.subject().ref());
            }
        });
        assertThat(recorded.get(0).changeSummary().orElseThrow())
                .contains("page: 2 alert(s), status=OPEN, kind=ANY, after=none")
                .contains(raised.get(0).id().value().toString()).contains(raised.get(1).id().value().toString())
                .contains("withheld=0, more=true");
        assertThat(recorded.get(2).changeSummary().orElseThrow()).contains("page: 1 alert(s)").contains("more=false");
    }

    @Test
    @DisplayName("the filters: OPEN unless named, a named status and kind; a name outside the vocabulary, an unknown"
            + " cursor and a page below one are refused QueueRequestInvalid; a page above the bound is the bound")
    void theFilters() {
        RiskAlert ato = world.openAlert();
        world.store.now = world.store.now.plusSeconds(1);
        RiskAlert aml = world.born(AlertWorld.structuring(AlertWorld.freshParty(), AlertWorld.day(),
                List.of(EvaluationWorld.IDS.next())));
        world.store.now = world.store.now.plusSeconds(1);
        RiskAlert attached = world.openAlert();
        world.store.attach(AlertWorld.UOW, attached.id(), RiskCaseId.of(EvaluationWorld.IDS.next()));
        Actor reader = world.analyst();

        assertThat(page(Optional.empty(), Optional.empty(), Optional.empty(), 20, reader).alerts())
                .extracting(RiskAlert::id).containsExactly(ato.id(), aml.id());
        assertThat(page(Optional.of("OPEN"), Optional.of("AML"), Optional.empty(), 20, reader).alerts())
                .extracting(RiskAlert::id).containsExactly(aml.id());
        assertThat(page(Optional.of("ATTACHED"), Optional.empty(), Optional.empty(), 20, reader).alerts())
                .extracting(RiskAlert::id).containsExactly(attached.id());
        assertThat(page(Optional.empty(), Optional.empty(), Optional.of(attached.id()), 20, reader).alerts())
                .as("a cursor that is no longer of the filter still keys the page: its key never changes").isEmpty();

        assertThatThrownBy(() -> AlertQueue.Filter.named(Optional.of("open"), Optional.empty()))
                .isInstanceOf(Queues.QueueRequestInvalid.class);
        assertThatThrownBy(() -> AlertQueue.Filter.named(Optional.empty(), Optional.of("COLLECTIONS")))
                .isInstanceOf(Queues.QueueRequestInvalid.class);
        assertThatThrownBy(() -> page(Optional.empty(), Optional.empty(),
                Optional.of(RiskAlertId.of(EvaluationWorld.IDS.next())), 20, reader))
                .isInstanceOf(Queues.QueueRequestInvalid.class).hasMessageContaining("the cursor names no alert");
        assertThatThrownBy(() -> Queues.size(Optional.of(0))).isInstanceOf(Queues.QueueRequestInvalid.class);
        assertThat(Queues.size(Optional.of(500))).isEqualTo(Queues.PAGE_BOUND);
        assertThat(Queues.size(Optional.empty())).isEqualTo(Queues.DEFAULT_PAGE);
        assertThat(world.audited("risk.AlertRead")).as("a refused read records nothing served").isEmpty();
    }

    @Test
    @DisplayName("self-dealing withheld: the reader's own party's alert, a merchant's of the reader's organisation and"
            + " one whose party cannot be placed are never served - the scan reads on past them and the cursor is the"
            + " last alert served")
    void selfDealingIsWithheld() {
        UUID own = UUID.randomUUID();
        Actor reader = world.analystOf(own);
        SignalSubject merchant = SignalSubject.of(SubjectKind.MERCHANT, UUID.randomUUID());
        world.partyOfSubject.put(merchant, own);
        RiskAlert ownParty = world.born(AlertWorld.ato(AlertWorld.party(own), AlertWorld.day()));
        world.store.now = world.store.now.plusSeconds(1);
        RiskAlert ownMerchant = world.born(merchantAlert(merchant));
        world.store.now = world.store.now.plusSeconds(1);
        RiskAlert unplaceable = world.born(AlertWorld.structuring(SignalSubject.of(SubjectKind.ACCOUNT,
                UUID.randomUUID()), AlertWorld.day(), List.of(EvaluationWorld.IDS.next())));
        world.store.now = world.store.now.plusSeconds(1);
        RiskAlert first = world.openAlert();
        world.store.now = world.store.now.plusSeconds(1);
        RiskAlert second = world.openAlert();

        AlertQueue.Page page = page(Optional.empty(), Optional.empty(), Optional.empty(), 1, reader);
        assertThat(page.alerts()).extracting(RiskAlert::id).containsExactly(first.id());
        assertThat(page.next()).contains(first.id());
        assertThat(page.withheld()).as("the merchant's and the unplaceable; the own party's left out by the statement")
                .isEqualTo(2);
        AlertQueue.Page rest = page(Optional.empty(), Optional.empty(), page.next(), 1, reader);
        assertThat(rest.alerts()).extracting(RiskAlert::id).containsExactly(second.id());
        assertThat(rest.next()).isEmpty();

        List<RiskAlertId> everServed = new ArrayList<>();
        everServed.addAll(page.alerts().stream().map(RiskAlert::id).toList());
        everServed.addAll(rest.alerts().stream().map(RiskAlert::id).toList());
        assertThat(everServed).doesNotContain(ownParty.id(), ownMerchant.id(), unplaceable.id());
        assertThat(world.store.log).contains("queue OPEN");
    }

    @Test
    @DisplayName("the scan's bound: a wall of more than SCAN_BOUND withheld alerts ends the page bounded - nothing of"
            + " the wall served, no cursor naming it")
    void theScanIsBounded() {
        Actor reader = world.analyst();
        for (int i = 0; i < Queues.SCAN_BOUND + 50; i++) {
            world.store.now = world.store.now.plusMillis(1);
            world.born(AlertWorld.structuring(SignalSubject.of(SubjectKind.ACCOUNT, UUID.randomUUID()),
                    AlertWorld.day(), List.of(EvaluationWorld.IDS.next())));
        }
        world.store.now = world.store.now.plusSeconds(1);
        world.openAlert();
        AlertQueue.Page page = page(Optional.empty(), Optional.empty(), Optional.empty(), 20, reader);
        assertThat(page.alerts()).isEmpty();
        assertThat(page.next()).isEmpty();
        assertThat(page.bounded()).isTrue();
        assertThat(page.withheld()).isGreaterThanOrEqualTo(Queues.SCAN_BOUND);
        queue.record(AlertWorld.UOW, page, reader, AlertWorld.correlation());
        assertThat(world.audited("risk.AlertRead").get(0).changeSummary().orElseThrow()).contains("bounded=true");
    }

    @Test
    @DisplayName("alerts born between pages: none of the first ones skipped or repeated, the new ones on later pages")
    void insertsBetweenPagesNeitherSkipNorRepeat() {
        List<RiskAlert> before = raisedInOrder(4);
        Actor reader = world.analyst();
        AlertQueue.Page first = page(Optional.empty(), Optional.empty(), Optional.empty(), 3, reader);
        List<RiskAlert> after = raisedInOrder(3);
        List<RiskAlertId> served = new ArrayList<>(first.alerts().stream().map(RiskAlert::id).toList());
        Optional<RiskAlertId> cursor = first.next();
        while (cursor.isPresent()) {
            AlertQueue.Page next = page(Optional.empty(), Optional.empty(), cursor, 3, reader);
            next.alerts().forEach(alert -> served.add(alert.id()));
            cursor = next.next();
        }
        assertThat(served).doesNotHaveDuplicates()
                .containsSubsequence(before.stream().map(RiskAlert::id).toList())
                .containsAll(after.stream().map(RiskAlert::id).toList()).hasSize(7);
    }

    @Test
    @DisplayName("the reader: placed in a party or refused UNPLACEABLE (recorded FAILED against the queue, never the"
            + " party); never the platform")
    void theReader() {
        world.openAlert();
        Actor unplaced = new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER);
        assertThatThrownBy(() -> page(Optional.empty(), Optional.empty(), Optional.empty(), 20, unplaced))
                .isInstanceOf(RiskAssessments.SelfDealingRefused.class)
                .satisfies(refused -> {
                    RiskAssessments.SelfDealingRefused self = (RiskAssessments.SelfDealingRefused) refused;
                    assertThat(self.refusal()).isEqualTo(RiskAssessments.SelfDealingRefused.Cause.UNPLACEABLE);
                    queue.recordRefusal(AlertWorld.UOW, AlertQueue.Filter.named(Optional.empty(), Optional.empty()),
                            self, unplaced, AlertWorld.correlation());
                });
        AuditRecord refused = world.audited("risk.SelfDealingRefused").get(0);
        assertThat(refused.outcome()).isEqualTo(AuditOutcome.FAILED);
        assertThat(refused.targetType()).isEqualTo(AlertQueue.TARGET_TYPE);
        assertThat(refused.changeSummary().orElseThrow()).contains("act=alert-queue-read, cause=UNPLACEABLE");

        assertThatThrownBy(() -> page(Optional.empty(), Optional.empty(), Optional.empty(), 20, Actor.SYSTEM))
                .isInstanceOf(IllegalArgumentException.class);
        Actor customerTyped = new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER);
        world.partyOfActor.put(customerTyped.id(), UUID.randomUUID());
        assertThat(page(Optional.empty(), Optional.empty(), Optional.empty(), 20, customerTyped).alerts())
                .as("an operator as the session establishes every identity reads the queue").hasSize(1);
    }

    // ------------------------------------------------------------------ fixtures

    private AlertQueue.Page page(Optional<String> status, Optional<String> kind, Optional<RiskAlertId> after,
            int limit, Actor reader) {
        return queue.page(AlertWorld.UOW, AlertQueue.Filter.named(status, kind), after, Queues.size(Optional.of(limit)),
                reader);
    }

    /** {@code count} open alerts of fresh placed parties, each a second after the last on the database clock. */
    private List<RiskAlert> raisedInOrder(int count) {
        List<RiskAlert> raised = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            world.store.now = world.store.now.plus(Duration.ofSeconds(1));
            SignalSubject subject = AlertWorld.freshParty();
            raised.add(world.born(AlertWorld.ato(subject, AlertWorld.day())));
        }
        return raised;
    }

    private static NewAlert merchantAlert(SignalSubject merchant) {
        return NewAlert.ofRule(AlertKind.MERCHANT_ABUSE, AlertSeverity.HIGH, merchant, RuleKind.REFUND_RATIO,
                AlertWorld.day(), AlertWorld.policy(),
                AlertEvidence.assessment(RiskAssessmentId.of(EvaluationWorld.IDS.next())), AlertWorld.correlation(),
                CausationId.of("queue-suite"));
    }
}
