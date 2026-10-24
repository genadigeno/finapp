package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.sharedkernel.event.EventEnvelope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The raise, over an in-memory store (`P13-TSK-015`; {@code PHASE_13_PLAN.md} sections 7.2, 10, 13.2 scenarios 1, 2, 8;
 * {@code INV-RSK-06}, {@code INV-RSK-08}): born once per (subject, rule, window start) - a replay, a reprocessing and a
 * duplicate in one call are the same row; the birth one insert, a conflict a read and nothing written;
 * {@code risk.AlertRaised} only when born, carrying ids and codes only; a transaction's alerts born in key order, every
 * birth before any fold; an operational alert raised and never folded; the meter counting births only. The concurrent
 * halves are {@code AlertDatabaseTest}'s (written and owed, C5).
 */
@DisplayName("alerts are born once per (subject, rule, window start) and announced only when born (P13-TSK-015)")
class AlertsTest {

    private final AlertWorld world = new AlertWorld();

    @Test
    @DisplayName("INV-RSK-06: the same alert raised twice is one row - the second raise answers the standing alert,"
            + " writes nothing and announces nothing")
    void theSameAlertTwiceIsOneRow() {
        SignalSubject subject = AlertWorld.freshParty();
        Alerts.Raised first = world.alerts.raise(AlertWorld.UOW, AlertWorld.ato(subject, AlertWorld.day()));
        Alerts.Raised second = world.alerts.raise(AlertWorld.UOW, AlertWorld.ato(subject, AlertWorld.day()));

        assertThat(first.outcome()).isEqualTo(Alerts.Outcome.BORN);
        assertThat(second.outcome()).isEqualTo(Alerts.Outcome.ALREADY);
        assertThat(second.alert()).isEqualTo(first.alert());
        assertThat(world.store.all()).hasSize(1);
        assertThat(world.events).hasSize(1);
        assertThat(world.raisedCount).containsExactly(Map.entry(AlertKind.ACCOUNT_TAKEOVER, 1));
        assertThat(first.alert().status()).isEqualTo(AlertStatus.OPEN);
        assertThat(first.alert().raisedAt()).as("the database's stamp").isEqualTo(world.store.now);
    }

    @Test
    @DisplayName("the birth is ONE statement - the insert, never a read before it (a read-then-insert races); a"
            + " conflict reads the standing row after it")
    void theBirthIsOneInsert() {
        SignalSubject subject = AlertWorld.freshParty();
        world.alerts.raise(AlertWorld.UOW, AlertWorld.ato(subject, AlertWorld.day()));
        assertThat(world.store.log).as("[INV-RSK-06] the born-once protocol: insert first").containsExactly("insert");
        world.store.log.clear();
        world.alerts.raise(AlertWorld.UOW, AlertWorld.ato(subject, AlertWorld.day()));
        assertThat(world.store.log).containsExactly("insert", "findByKey");
    }

    @Test
    @DisplayName("scenarios 1 and 8: a replayed event and a reprocessed history - the same evidence, its window from the"
            + " evidence's database stamp, hours later - raise nothing new")
    void aReplayOrAReprocessingRaisesNothingNew() {
        SignalSubject subject = AlertWorld.freshParty();
        List<UUID> entries = List.of(EvaluationWorld.IDS.next(), EvaluationWorld.IDS.next());
        world.alerts.raise(AlertWorld.UOW, AlertWorld.structuring(subject, AlertWorld.day(), entries));
        world.store.now = world.store.now.plusSeconds(6 * 3600);
        Alerts.Raised replayed = world.alerts.raise(AlertWorld.UOW,
                AlertWorld.structuring(subject, AlertWindow.containing(WindowKind.D1, AlertWorld.EVIDENCE_STAMP), entries));
        assertThat(replayed.outcome()).isEqualTo(Alerts.Outcome.ALREADY);
        assertThat(world.store.all()).hasSize(1);
        assertThat(world.events).hasSize(1);
    }

    @Test
    @DisplayName("another rule, another window or another subject is another alert")
    void anotherKeyIsAnotherAlert() {
        SignalSubject subject = AlertWorld.freshParty();
        world.alerts.raise(AlertWorld.UOW, AlertWorld.ato(subject, AlertWorld.day()));
        world.alerts.raise(AlertWorld.UOW, AlertWorld.structuring(subject, AlertWorld.day(),
                List.of(EvaluationWorld.IDS.next())));
        world.alerts.raise(AlertWorld.UOW, AlertWorld.ato(subject,
                AlertWindow.containing(WindowKind.D1, AlertWorld.EVIDENCE_STAMP.plusSeconds(86_400))));
        world.alerts.raise(AlertWorld.UOW, AlertWorld.ato(AlertWorld.freshParty(), AlertWorld.day()));
        assertThat(world.store.all()).hasSize(4);
        assertThat(world.events).hasSize(4);
    }

    @Test
    @DisplayName("INV-RSK-08: risk.AlertRaised carries the alert id, kind, severity and subject - ids and codes only,"
            + " never the rule, its parameters, a reason, a score or the evidence - in the raise's flow")
    void theEventCarriesIdsAndCodesOnly() {
        SignalSubject subject = AlertWorld.freshParty();
        NewAlert asked = AlertWorld.ato(subject, AlertWorld.day());
        RiskAlert born = world.born(asked);

        assertThat(world.events).hasSize(1);
        EventEnvelope envelope = world.events.get(0);
        assertThat(envelope.eventType()).isEqualTo("risk.AlertRaised");
        assertThat(envelope.aggregateType()).isEqualTo("risk_alert");
        assertThat(envelope.producer()).isEqualTo("risk");
        assertThat(envelope.eventVersion()).isEqualTo(1);
        assertThat(envelope.correlationId()).isEqualTo(asked.correlation());
        assertThat(envelope.causationId()).isEqualTo(asked.causation());
        assertThat(world.payloads.get(0)).as("[INV-RSK-08] ids and closed codes only").containsExactlyInAnyOrderEntriesOf(
                Map.of("alertId", born.id().value().toString(), "kind", "ACCOUNT_TAKEOVER", "severity", "HIGH",
                        "subjectKind", "PARTY", "subjectRef", subject.ref()));
        String evidence = born.evidence().assessments().get(0).value().toString();
        assertThat(world.payloads.get(0).values()).doesNotContain(evidence, "RECENT_CREDENTIAL_CHANGE");
    }

    @Test
    @DisplayName("raiseAll: every birth in key order, every birth before any fold, the answers in the caller's order;"
            + " one key twice in a call is one birth and one ALREADY")
    void raiseAllIsOrdered() {
        List<String> seen = new ArrayList<>();
        world.folding = (unitOfWork, born) -> {
            seen.add("fold:" + born.key().subjectRef() + "/" + world.store.all().size());
            return AlertFolding.Folded.QUEUED;
        };
        SignalSubject a = AlertWorld.party(UUID.fromString("00000000-0000-7000-8000-00000000000a"));
        SignalSubject b = AlertWorld.party(UUID.fromString("00000000-0000-7000-8000-00000000000b"));
        SignalSubject c = AlertWorld.party(UUID.fromString("00000000-0000-7000-8000-00000000000c"));
        List<Alerts.Raised> answers = world.alerts.raiseAll(AlertWorld.UOW, List.of(
                AlertWorld.ato(c, AlertWorld.day()), AlertWorld.ato(a, AlertWorld.day()),
                AlertWorld.ato(b, AlertWorld.day()), AlertWorld.ato(a, AlertWorld.day())));

        assertThat(world.store.all()).extracting(alert -> alert.subject().ref())
                .as("born in key order").containsExactly(a.ref(), b.ref(), c.ref());
        assertThat(seen).as("every birth before any fold, folds in key order")
                .containsExactly("fold:" + a.ref() + "/3", "fold:" + b.ref() + "/3", "fold:" + c.ref() + "/3");
        assertThat(answers).extracting(raised -> raised.alert().subject().ref())
                .containsExactly(c.ref(), a.ref(), b.ref(), a.ref());
        assertThat(answers).extracting(Alerts.Raised::outcome).containsExactly(Alerts.Outcome.BORN,
                Alerts.Outcome.BORN, Alerts.Outcome.BORN, Alerts.Outcome.ALREADY);
        assertThat(world.events).hasSize(3);
        assertThat(world.spans).containsExactly("risk.alert.raise{}");
    }

    @Test
    @DisplayName("an operational alert is raised born once and never folded - it opens no case")
    void anOperationalAlertIsNeverFolded() {
        List<RiskAlert> folded = new ArrayList<>();
        world.folding = (unitOfWork, born) -> {
            folded.add(born);
            return AlertFolding.Folded.QUEUED;
        };
        UUID entry = EvaluationWorld.IDS.next();
        Alerts.Raised raised = world.alerts.raise(AlertWorld.UOW, AlertWorld.unclassified(AlertWorld.freshParty(), entry));
        assertThat(raised.outcome()).isEqualTo(Alerts.Outcome.BORN);
        assertThat(raised.alert().kind().operational()).isTrue();
        assertThat(raised.alert().policyVersion()).isEmpty();
        assertThat(raised.alert().status()).isEqualTo(AlertStatus.OPEN);
        assertThat(raised.folded()).isEqualTo(AlertFolding.Folded.QUEUED);
        assertThat(folded).isEmpty();
        assertThat(world.events).hasSize(1);
    }

    @Test
    @DisplayName("the evidence is stored as the raiser named it - references, bounded - and never copied into the event")
    void theEvidenceIsReferences() {
        List<UUID> entries = List.of(EvaluationWorld.IDS.next(), EvaluationWorld.IDS.next(), EvaluationWorld.IDS.next());
        RiskAlert born = world.born(AlertWorld.structuring(AlertWorld.freshParty(), AlertWorld.day(), entries));
        assertThat(born.evidence().journalEntries()).containsExactlyElementsOf(entries);
        assertThat(born.evidence().signals()).isEmpty();
        assertThat(world.payloads.get(0).values()).doesNotContainAnyElementsOf(
                entries.stream().map(UUID::toString).toList());
    }
}
