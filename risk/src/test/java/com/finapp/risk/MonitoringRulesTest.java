package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.risk.testing.RiskPolicyFixtures;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The monitoring rules and their AML alerts over fakes (`P13-TSK-020`; {@code PHASE_13_PLAN.md} section 12.7, scenarios 8
 * and 12; ADR-0111 decision 3; {@code INV-RSK-06}, {@code INV-RSK-09}, {@code INV-RSK-04}): each kind over generated
 * movement sets, golden - the key (party, rule, the window's bucket), the severity, the evidence - one alert per party,
 * rule and window however many batches see it; every alert folded into the party's one open {@code AML} case; nothing
 * without an {@code ACTIVE} version; nothing new on reprocessing; the alert placed and served by the investigator's queue.
 * The database's half - real postings, the locks, two sweepers on one party - is {@code MonitoringRulesDatabaseTest}'s
 * (written and owed, C5).
 */
@DisplayName("the monitoring rules raise AML alerts born once per party, rule and window (P13-TSK-020)")
class MonitoringRulesTest {

    /** A Tuesday three days before the database's "today" (2026-10-23). */
    private static final LocalDate DAY = LocalDate.parse("2026-10-20");

    private final MonitoringWorld world = new MonitoringWorld();

    @Test
    @DisplayName("scenario 12: a structuring sequence over three batches of one day is ONE alert - born when the third"
            + " movement completes it, ALREADY after - and the next day's sequence is a second")
    void aStructuringSequenceAlertsOncePerWindow() {
        RiskPolicyVersionId version = world.monitoringActive();
        UUID account = world.accountOfNewParty();
        UUID party = world.partyOf(account);
        MonitoredTransaction first = world.withdrawal(account, 900_00, DAY);
        MonitoredTransaction second = world.withdrawal(account, 950_00, DAY);
        world.judge(first, second);
        assertThat(world.amlAlerts(party, RuleKind.STRUCTURING)).as("two movements: no sequence yet").isEmpty();

        MonitoredTransaction third = world.withdrawal(account, 800_00, DAY);
        world.judge(third);
        world.judge(world.withdrawal(account, 700_00, DAY));
        List<RiskAlert> alerts = world.amlAlerts(party, RuleKind.STRUCTURING);
        assertThat(alerts).as("once per window").hasSize(1);
        RiskAlert alert = alerts.getFirst();
        assertThat(alert.kind()).isEqualTo(AlertKind.AML);
        assertThat(alert.severity()).isEqualTo(AlertSeverity.HIGH);
        assertThat(alert.subject()).isEqualTo(SignalSubject.of(SubjectKind.PARTY, party));
        assertThat(alert.window()).isEqualTo(AlertWindow.stored(WindowKind.D1, start(DAY)));
        assertThat(alert.policyVersion()).contains(version);
        assertThat(alert.evidence().journalEntries()).as("the three movements it judged, in the journal's order")
                .containsExactly(first.journalEntryId(), second.journalEntryId(), third.journalEntryId());
        assertThat(alert.causationId()).isEqualTo(third.journalEntryId().toString());

        world.judge(world.withdrawal(account, 900_00, DAY.plusDays(1)), world.withdrawal(account, 900_00,
                DAY.plusDays(1)), world.withdrawal(account, 900_00, DAY.plusDays(1)));
        assertThat(world.amlAlerts(party, RuleKind.STRUCTURING)).extracting(RiskAlert::window)
                .containsExactly(AlertWindow.stored(WindowKind.D1, start(DAY)),
                        AlertWindow.stored(WindowKind.D1, start(DAY.plusDays(1))));
    }

    @Test
    @DisplayName("every alert of a party folds into its ONE open AML case - structuring, rapid in-out and a round amount"
            + " of one day, and a later window's alert too")
    void everyAlertFoldsIntoOneCase() {
        world.monitoringActive();
        UUID account = world.accountOfNewParty();
        UUID party = world.partyOf(account);
        world.judge(world.inflow(account, 1_000_00, DAY), world.withdrawal(account, 900_00, DAY),
                world.withdrawal(account, 950_00, DAY), world.withdrawal(account, 800_00, DAY));
        world.judge(world.withdrawal(account, 900_00, DAY.plusDays(1)), world.withdrawal(account, 900_00,
                DAY.plusDays(1)), world.withdrawal(account, 900_00, DAY.plusDays(1)));

        assertThat(world.amlAlerts()).extracting(alert -> alert.rule().code()).containsExactlyInAnyOrder(
                "STRUCTURING", "RAPID_IN_OUT", "ROUND_AMOUNT", "STRUCTURING");
        List<RiskCase> cases = world.cases(party, CaseKind.AML);
        assertThat(cases).as("one open AML case per party").hasSize(1);
        assertThat(world.amlAlerts()).allSatisfy(alert -> {
            assertThat(alert.status()).isEqualTo(AlertStatus.ATTACHED);
            assertThat(alert.attachedCase()).contains(cases.getFirst().id());
        });
    }

    @Test
    @DisplayName("no ACTIVE MONITORING version: the rows are seen, nothing is raised, no head enrolled, the party"
            + " counted NOT_JUDGED")
    void noActiveMonitoringPolicyRaisesNothing() {
        UUID account = world.accountOfNewParty();
        UUID party = world.partyOf(account);
        MonitoringRun.Judged judged = world.judge(world.withdrawal(account, 900_00, DAY),
                world.withdrawal(account, 950_00, DAY), world.withdrawal(account, 800_00, DAY));
        assertThat(judged).isEqualTo(new MonitoringRun.Judged(0, 0, 1, 0));
        assertThat(world.alertStore.all()).isEmpty();
        assertThat(world.caseStore.all()).isEmpty();
        assertThat(world.profiles.head(party)).as("no head enrolled").isEmpty();
        assertThat(world.outcome(MonitoringObserver.SubjectOutcome.NOT_JUDGED)).isEqualTo(1);
    }

    @Test
    @DisplayName("scenario 8: reprocessing - the same rows judged again on a later day - raises no second alert and"
            + " opens no second case: every key is the evidence's value date, never the run's clock")
    void reprocessingRaisesNoSecondAlert() {
        world.monitoringActive();
        UUID account = world.accountOfNewParty();
        UUID party = world.partyOf(account);
        List<MonitoredTransaction> rows = List.of(world.inflow(account, 1_000_00, DAY),
                world.withdrawal(account, 900_00, DAY), world.withdrawal(account, 950_00, DAY),
                world.withdrawal(account, 800_00, DAY));
        world.judge(rows.toArray(MonitoredTransaction[]::new));
        List<RiskAlert> before = world.alertStore.all();
        int cases = world.caseStore.all().size();
        int versions = world.profiles.versions(MonitoringWorld.UOW, party).size();

        world.velocity.now = MonitoringWorld.NOW.plusSeconds(86_400 * 9);
        world.profiles.now = world.velocity.now;
        world.rejudge(rows);
        world.rejudge(rows);
        assertThat(world.alertStore.all()).as("nothing new").isEqualTo(before);
        assertThat(world.caseStore.all()).hasSize(cases);
        assertThat(world.profiles.versions(MonitoringWorld.UOW, party)).hasSize(versions);
    }

    @Test
    @DisplayName("RAPID_IN_OUT: at least 80 % of the day's inflow out the same day - exactly 80 % fires, a cent less"
            + " does not; the evidence the day's movements in the policy's money")
    void rapidInOut() {
        world.monitoringActive();
        UUID account = world.accountOfNewParty();
        UUID party = world.partyOf(account);
        MonitoredTransaction in = world.inflow(account, 1_000_01, DAY);
        MonitoredTransaction out = world.withdrawal(account, 800_01, DAY);
        MonitoredTransaction dollars = world.withdrawal(account, 5_000_00, MonitoringWorld.USD, DAY);
        world.judge(in, out, dollars);
        List<RiskAlert> alerts = world.amlAlerts(party, RuleKind.RAPID_IN_OUT);
        assertThat(alerts).hasSize(1);
        assertThat(alerts.getFirst().severity()).isEqualTo(AlertSeverity.HIGH);
        assertThat(alerts.getFirst().evidence().journalEntries()).as("EUR only")
                .containsExactly(in.journalEntryId(), out.journalEntryId());

        UUID other = world.accountOfNewParty();
        world.judge(world.inflow(other, 1_000_00, DAY), world.withdrawal(other, 799_99, DAY));
        assertThat(world.amlAlerts(world.partyOf(other), RuleKind.RAPID_IN_OUT)).as("79.999 %").isEmpty();
    }

    @Test
    @DisplayName("DORMANT_THEN_ACTIVE: 90 days silent on the journal, then the party's judged outflow of at least"
            + " EUR 1,000 in the day (`-006`'s PARTY window) - and 48 silent days do not")
    void dormantThenActive() {
        world.monitoringActive();
        UUID account = world.accountOfNewParty();
        UUID party = world.partyOf(account);
        world.movements.rows.add(world.inflow(account, 50_00, LocalDate.parse("2026-06-01")));
        world.partyWindow(party, WindowKind.D1, DAY, 1, 1_000_00, MonitoringWorld.EUR);
        MonitoredTransaction reactivated = world.withdrawal(account, 1_000_00, DAY);
        world.judge(reactivated);
        List<RiskAlert> alerts = world.amlAlerts(party, RuleKind.DORMANT_THEN_ACTIVE);
        assertThat(alerts).hasSize(1);
        assertThat(alerts.getFirst().severity()).isEqualTo(AlertSeverity.MEDIUM);
        assertThat(alerts.getFirst().evidence().journalEntries()).containsExactly(reactivated.journalEntryId());

        UUID recent = world.accountOfNewParty();
        UUID recentParty = world.partyOf(recent);
        world.movements.rows.add(world.inflow(recent, 50_00, LocalDate.parse("2026-09-01")));
        world.partyWindow(recentParty, WindowKind.D1, DAY, 1, 1_000_00, MonitoringWorld.EUR);
        world.judge(world.withdrawal(recent, 1_000_00, DAY));
        assertThat(world.amlAlerts(recentParty, RuleKind.DORMANT_THEN_ACTIVE)).isEmpty();
    }

    @Test
    @DisplayName("ROUND_AMOUNT: the movement's own amount a multiple of EUR 1,000 - its day's alert, LOW, naming that"
            + " movement; a cent off does not; CORRIDOR_HIGH_RISK never fires over the journal (no corridor)")
    void roundAmountAndTheCorridor() {
        world.monitoringActive();
        UUID account = world.accountOfNewParty();
        UUID party = world.partyOf(account);
        MonitoredTransaction round = world.withdrawal(account, 3_000_00, DAY);
        world.judge(round, world.withdrawal(account, 3_000_01, DAY));
        List<RiskAlert> alerts = world.amlAlerts(party, RuleKind.ROUND_AMOUNT);
        assertThat(alerts).hasSize(1);
        assertThat(alerts.getFirst().severity()).isEqualTo(AlertSeverity.LOW);
        assertThat(alerts.getFirst().window()).isEqualTo(AlertWindow.stored(WindowKind.D1, start(DAY)));
        assertThat(alerts.getFirst().evidence().journalEntries()).containsExactly(round.journalEntryId());
        assertThat(world.amlAlerts(party, RuleKind.CORRIDOR_HIGH_RISK)).isEmpty();
    }

    @Test
    @DisplayName("VELOCITY_*: `-006`'s committed PARTY window over the thresholds - the D30 bucket's alert, MEDIUM, naming"
            + " the bucket's movements on the journal")
    void velocityReused() {
        world.monitoringActive();
        UUID account = world.accountOfNewParty();
        UUID party = world.partyOf(account);
        world.partyWindow(party, WindowKind.D30, DAY, 100, 50_000_00, MonitoringWorld.EUR);
        MonitoredTransaction moved = world.withdrawal(account, 10_00, DAY);
        world.judge(moved);
        Instant d30 = WindowStarts.start(WindowKind.D30, start(DAY));
        for (RuleKind velocity : List.of(RuleKind.VELOCITY_SUM, RuleKind.VELOCITY_COUNT)) {
            assertThat(world.amlAlerts(party, velocity)).singleElement().satisfies(alert -> {
                assertThat(alert.window()).isEqualTo(AlertWindow.stored(WindowKind.D30, d30));
                assertThat(alert.severity()).isEqualTo(AlertSeverity.MEDIUM);
                assertThat(alert.evidence().journalEntries()).containsExactly(moved.journalEntryId());
            });
        }
    }

    @Test
    @DisplayName("generated: over 300 random days of movements STRUCTURING alerts exactly when an independent integer"
            + " oracle says - at least 3 below EUR 1,000 summing above EUR 2,500 - never more than once")
    void structuringMatchesTheOracle() {
        world.monitoringActive();
        Random random = new Random(20_261_025L);
        for (int round = 0; round < 300; round++) {
            UUID account = world.accountOfNewParty();
            UUID party = world.partyOf(account);
            int n = random.nextInt(7);
            List<MonitoredTransaction> rows = new ArrayList<>();
            int below = 0;
            long belowSum = 0;
            for (int i = 0; i < n; i++) {
                long minor = 100_00 + random.nextInt(1_200_00);
                if (minor < 1_000_00) {
                    below++;
                    belowSum += minor;
                }
                rows.add(world.withdrawal(account, minor, DAY));
            }
            if (!rows.isEmpty()) {
                world.judge(rows.toArray(MonitoredTransaction[]::new));
            }
            boolean oracle = below >= 3 && belowSum > 2_500_00;
            assertThat(world.amlAlerts(party, RuleKind.STRUCTURING)).as("round %d: %d below, %d", round, below,
                    belowSum).hasSize(oracle ? 1 : 0);
        }
    }

    @Test
    @DisplayName("INV-RSK-04: every hit is recorded on its alert's evidence - STRUCTURING names exactly the window's"
            + " movements below its threshold, never the one above it")
    void everyHitNamesItsEvidence() {
        world.monitoringActive();
        UUID account = world.accountOfNewParty();
        UUID party = world.partyOf(account);
        MonitoredTransaction a = world.withdrawal(account, 900_00, DAY);
        MonitoredTransaction big = world.withdrawal(account, 5_000_00, DAY);
        MonitoredTransaction b = world.withdrawal(account, 950_00, DAY);
        MonitoredTransaction c = world.withdrawal(account, 800_00, DAY);
        world.judge(a, big, b, c);
        assertThat(world.amlAlerts(party, RuleKind.STRUCTURING)).singleElement().satisfies(alert ->
                assertThat(alert.evidence().journalEntries())
                        .containsExactly(a.journalEntryId(), b.journalEntryId(), c.journalEntryId()));
        assertThat(world.hits).as("rule.hit{operation=MONITORING}").contains(RuleKind.STRUCTURING, RuleKind.ROUND_AMOUNT);
    }

    @Test
    @DisplayName("an internal transfer judges BOTH parties - the payer's OUT and the payee's IN, read from the payer's"
            + " row - so money passing through the payee is its own rapid in-out")
    void theReceivingPartyIsJudgedToo() {
        world.monitoringActive();
        UUID payer = world.accountOfNewParty();
        UUID payee = world.accountOfNewParty();
        MonitoredTransaction transfer = world.transfer(payer, payee, 2_000_00, DAY);
        MonitoredTransaction onward = world.withdrawal(payee, 1_900_00, DAY);
        MonitoringRun.Judged judged = world.judge(transfer, onward);
        assertThat(judged.judged()).isEqualTo(2);
        assertThat(world.amlAlerts(world.partyOf(payee), RuleKind.RAPID_IN_OUT)).singleElement().satisfies(alert ->
                assertThat(alert.evidence().journalEntries())
                        .containsExactly(transfer.journalEntryId(), onward.journalEntryId()));
        assertThat(world.amlAlerts(world.partyOf(payer), RuleKind.RAPID_IN_OUT)).as("the payer only paid").isEmpty();
    }

    @Test
    @DisplayName("the alert is placed: its subject is the PARTY, which the production join places as itself, so the"
            + " investigator's queue serves it - and withholds it from an analyst of that party")
    void theAlertReachesTheQueue() {
        world.monitoringActive();
        UUID account = world.accountOfNewParty();
        UUID party = world.partyOf(account);
        world.judge(world.withdrawal(account, 900_00, DAY), world.withdrawal(account, 950_00, DAY),
                world.withdrawal(account, 800_00, DAY));
        RiskAlert alert = world.amlAlerts(party, RuleKind.STRUCTURING).getFirst();
        assertThat(MonitoringWorld.placedAsProduction(MonitoringWorld.UOW, alert.subject())).contains(party);

        Actor analyst = new Actor("aml-analyst", ActorType.EMPLOYEE);
        Actor ownParty = new Actor("party-analyst", ActorType.EMPLOYEE);
        AlertQueue queue = new AlertQueue(world.alertStore, (unitOfWork, actor) -> Optional.of(
                actor.equals(ownParty) ? party : UUID.randomUUID()), MonitoringWorld::placedAsProduction,
                (unitOfWork, record) -> { }, EvaluationWorld.IDS, EvaluationWorld.CLOCK);
        AlertQueue.Filter attachedAml = AlertQueue.Filter.named(Optional.of("ATTACHED"), Optional.of("AML"));
        assertThat(queue.page(MonitoringWorld.UOW, attachedAml, Optional.empty(), 20, analyst).alerts())
                .extracting(RiskAlert::id).contains(alert.id());
        assertThat(queue.page(MonitoringWorld.UOW, attachedAml, Optional.empty(), 20, ownParty).alerts())
                .extracting(RiskAlert::id).doesNotContain(alert.id());
    }

    @Test
    @DisplayName("a PROHIBITED party is still judged and alerted - monitoring never goes blind on it - and its rating"
            + " stands untouched")
    void aProhibitedPartyIsStillAlerted() {
        world.monitoringActive();
        UUID account = world.accountOfNewParty();
        UUID party = world.partyOf(account);
        world.profiles.seedDisposition(party, RiskRating.PROHIBITED);
        world.judge(world.withdrawal(account, 900_00, DAY), world.withdrawal(account, 950_00, DAY),
                world.withdrawal(account, 800_00, DAY));
        assertThat(world.amlAlerts(party, RuleKind.STRUCTURING)).hasSize(1);
        assertThat(world.profiles.versions(MonitoringWorld.UOW, party)).hasSize(1);
        assertThat(world.profiles.head(party).orElseThrow().rating()).contains(RiskRating.PROHIBITED);
    }

    @Test
    @DisplayName("the profile permit's own recompute raises today's AML alerts over its readings - and the rule leg's"
            + " raise of the same window meets it ALREADY: two judges of one party, one alert")
    void thePermitLegRaisesTodaysAlerts() {
        world.monitoringActive();
        UUID account = world.accountOfNewParty();
        UUID party = world.partyOf(account);
        LocalDate today = MonitoringWorld.TODAY;
        List<MonitoredTransaction> rows = List.of(world.withdrawal(account, 900_00, today),
                world.withdrawal(account, 950_00, today), world.withdrawal(account, 800_00, today));
        world.movements.rows.addAll(rows);
        world.profiles.pullForward(MonitoringWorld.UOW, party);
        MonitoringWorld.asSystem(() -> world.recompute.recomputeNextDue(MonitoringWorld.UOW));
        assertThat(world.amlAlerts(party, RuleKind.STRUCTURING)).hasSize(1);

        world.rejudge(rows);
        assertThat(world.amlAlerts(party, RuleKind.STRUCTURING)).hasSize(1);
        assertThat(world.cases(party, CaseKind.AML)).hasSize(1);
    }

    @Test
    @DisplayName("the readings move the rating: a structuring day today weighs 500 - the party MEDIUM, versioned with the"
            + " reason - while the alert carries no figure")
    void theRatingReadsTheJournal() {
        world.monitoringActive();
        UUID account = world.accountOfNewParty();
        UUID party = world.partyOf(account);
        LocalDate today = MonitoringWorld.TODAY;
        world.judge(world.withdrawal(account, 900_00, today), world.withdrawal(account, 950_00, today),
                world.withdrawal(account, 800_00, today));
        CustomerRiskProfileVersion version = world.profiles.versions(MonitoringWorld.UOW, party).getLast();
        assertThat(version.rating()).isEqualTo(RiskRating.MEDIUM);
        assertThat(version.reasons()).contains(ReasonCode.STRUCTURING);
    }

    private static Instant start(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
