package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.risk.testing.InMemoryRescreeningPermitStore;
import com.finapp.risk.testing.InMemoryScreeningListStore;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rescreening leg, hermetic over fake kyc ports (`P13-TSK-022`; {@code PHASE_13_PLAN.md} sections 7.1, 7.5, 12.10;
 * {@code RISK_LIFECYCLES.md} section 4.10; {@code INV-RSK-06}, {@code INV-RSK-02}, {@code INV-RSK-08}): an activation's
 * enqueue reaching every {@code ACTIVE} party once under ten enqueuers and a crash; ten sweepers rescreening a permit once,
 * a lease lost mid-ask recording nothing; a hit alerting once and casing; a clear recorded once; a stale screening, kyc
 * pending and kyc unavailable backed off and failed at the bound, never a clearance; a party deactivated meanwhile
 * skipped; a hit no {@code MONITORING} rule names recorded and counted. The real {@link Alerts} folds into the real
 * {@link Cases} ({@link CaseWorld}). The database's counts are {@code RescreeningDatabaseTest}'s (written, owed - C5).
 */
@DisplayName("the rescreening leg: every active party once, each permit once, a hit alerted once and cased (P13-TSK-022)")
class RescreeningRunTest {

    private static final Connection UOW = CaseWorld.UOW;
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final Actor OFFICER = new Actor("officer-1", ActorType.CUSTOMER);
    private static final Actor SECOND = new Actor("officer-2", ActorType.CUSTOMER);
    private static final RescreeningRun.Bounds BOUNDS = new RescreeningRun.Bounds(3, Duration.ofMinutes(5),
            Duration.ofMinutes(15));

    private final CaseWorld world = new CaseWorld();
    private final InMemoryScreeningListStore lists = new InMemoryScreeningListStore();
    private final InMemoryRescreeningPermitStore permits = new InMemoryRescreeningPermitStore(lists);
    private final TreeSet<UUID> active = new TreeSet<>(RescreeningRun::databaseOrder);
    private final Kyc kyc = new Kyc();
    private final MemorySignals signalStore = new MemorySignals();
    private final List<EventEnvelope> events = new ArrayList<>();
    private final List<Map<String, String>> payloads = new ArrayList<>();
    private final List<RescreeningObserver.Conclusion> concluded = new ArrayList<>();
    private final List<Integer> enqueued = new ArrayList<>();
    private final List<Class<? extends Throwable>> askFailures = new ArrayList<>();
    private final ScreeningListVersions versions = new ScreeningListVersions(lists,
            (AuditWriter<Connection>) (uow, record) -> world.audit.add(record), EvaluationWorld.IDS, EvaluationWorld.CLOCK);

    private RescreeningRun run = run(RescreeningRun.Bounds.DEFAULT);

    // ------------------------------------------------------------------ the enqueue

    @Test
    @DisplayName("an activation enqueues every ACTIVE party once: bounded batches after the cursor, a second enqueuer"
            + " skipping the held version, the census complete on a short page - one permit per (party, version)")
    void anActivationEnqueuesEveryActivePartyOnce() {
        for (int i = 0; i < 25; i++) {
            active.add(UUID.randomUUID());
        }
        ScreeningListVersion version = activated(ScreeningList.SANCTIONS, "v1");
        assertThat(permits.all()).as("nothing is enqueued in the activation's transaction").isEmpty();

        // Ten enqueuers, round after round: the first claims the version and holds it to its commit, the other nine skip.
        int rounds = 0;
        while (true) {
            RescreeningRun.Enqueued first = run.enqueueNextBatch(UOW, 10);
            for (int other = 0; other < 9; other++) {
                assertThat(run.enqueueNextBatch(UOW, 10)).as("SKIP LOCKED: the held version is skipped")
                        .isEqualTo(RescreeningRun.Enqueued.NOTHING);
            }
            commit();
            rounds++;
            if (!first.claimed() || first.complete()) {
                break;
            }
        }
        assertThat(rounds).isEqualTo(3);
        assertThat(permits.all()).hasSize(25);
        assertThat(permits.all().stream().map(RescreeningPermit::partyId).distinct()).hasSize(25);
        assertThat(permits.all()).allMatch(p -> p.listVersionId().equals(version.id())
                && p.status() == RescreeningPermit.Status.PENDING && p.correlationId().equals(version.correlationId()));
        ScreeningListVersion done = lists.get(version.id());
        assertThat(done.enqueued()).isEqualTo(25);
        assertThat(done.enqueueNextAt()).isEmpty();
        assertThat(done.enqueueCompletedAt()).isPresent();
        assertThat(done.enqueueCursor()).contains(active.last());
        assertThat(enqueued).containsExactly(10, 10, 5);
        assertThat(run.enqueueNextBatch(UOW, 10)).as("the census complete: nothing due")
                .isEqualTo(RescreeningRun.Enqueued.NOTHING);
    }

    @Test
    @DisplayName("an enqueue that crashes before its advance rolls back whole and re-runs from the committed cursor into"
            + " the same keys - no party twice, none missed")
    void aCrashedEnqueueReRunsIntoTheSameKeys() {
        for (int i = 0; i < 12; i++) {
            active.add(UUID.randomUUID());
        }
        ScreeningListVersion version = activated(ScreeningList.PEP, "v7");
        run.enqueueNextBatch(UOW, 5);
        commit();
        Map<ScreeningListVersionId, ScreeningListVersion> listsBefore = lists.snapshot();
        Object permitsBefore = permits.snapshot();
        RescreeningRun crashing = new RescreeningRun(new CrashOnAdvance(lists), permits, this::page, kyc,
                signals(), world.alerts::raiseAll, EvaluationWorld.IDS,
                RescreeningObserver.NONE, RescreeningRun.Bounds.DEFAULT);
        assertThatThrownBy(() -> crashing.enqueueNextBatch(UOW, 5)).hasMessageContaining("crash");
        lists.restore(listsBefore);
        permits.restore(permitsBefore);
        commit();
        while (run.enqueueNextBatch(UOW, 5).claimed()) {
            commit();
        }
        assertThat(permits.all()).hasSize(12);
        assertThat(permits.all().stream().map(RescreeningPermit::partyId).distinct()).hasSize(12);
        assertThat(lists.get(version.id()).enqueued()).isEqualTo(12);
    }

    @Test
    @DisplayName("a retired version's enqueue stops where it stood - its successor's census covers every party; the"
            + " retired version's permits run on")
    void aRetiredVersionsEnqueueStops() {
        for (int i = 0; i < 8; i++) {
            active.add(UUID.randomUUID());
        }
        ScreeningListVersion v1 = activated(ScreeningList.SANCTIONS, "v1");
        run.enqueueNextBatch(UOW, 3);
        commit();
        ScreeningListVersion v2 = activated(ScreeningList.SANCTIONS, "v2");
        assertThat(lists.get(v1.id()).enqueueNextAt()).isEmpty();
        while (run.enqueueNextBatch(UOW, 3).claimed()) {
            commit();
        }
        assertThat(permits.all().stream().filter(p -> p.listVersionId().equals(v1.id()))).hasSize(3);
        assertThat(permits.all().stream().filter(p -> p.listVersionId().equals(v2.id()))).hasSize(8);
    }

    @Test
    @DisplayName("the page is checked in the database's order - each half unsigned, as PostgreSQL orders uuid: parties"
            + " either side of a sign bit are ascending, and a page out of that order is refused")
    void aPageInTheDatabasesOrderIsAscending() {
        UUID belowTheSignBit = new UUID(0x7fff_ffff_ffff_ffffL, 0L);
        UUID aboveTheSignBit = new UUID(0x8000_0000_0000_0000L, 0L);
        UUID lowHalfAbove = new UUID(0x8000_0000_0000_0000L, 0x8000_0000_0000_0000L);
        active.add(aboveTheSignBit);
        active.add(lowHalfAbove);
        active.add(belowTheSignBit);
        assertThat(active).containsExactly(belowTheSignBit, aboveTheSignBit, lowHalfAbove);
        activated(ScreeningList.SANCTIONS, "v1");
        enqueueAll();
        assertThat(permits.all()).extracting(RescreeningPermit::partyId)
                .containsExactlyInAnyOrder(belowTheSignBit, aboveTheSignBit, lowHalfAbove);

        RescreeningRun disordered = new RescreeningRun(lists, permits,
                (uow, after, limit) -> List.of(lowHalfAbove, belowTheSignBit), kyc, signals(),
                world.alerts::raiseAll, EvaluationWorld.IDS,
                RescreeningObserver.NONE, RescreeningRun.Bounds.DEFAULT);
        activated(ScreeningList.PEP, "p1");
        assertThatThrownBy(() -> disordered.enqueueNextBatch(UOW, 10)).hasMessageContaining("not ascending");
    }

    // ------------------------------------------------------------------ a permit

    @Test
    @DisplayName("ten sweepers rescreen a permit once: one claim per lease, kyc asked once under the permit's key, one"
            + " conclusion, one signal")
    void tenSweepersRescreenOnce() {
        UUID party = oneEnqueued(ScreeningList.SANCTIONS, "v1");
        kyc.answers.put(party, screened(false, lists.now.plusSeconds(60)));
        List<RescreeningPermit> claimed = new ArrayList<>();
        for (int sweeper = 0; sweeper < 10; sweeper++) {
            claimed(run.claimNextDue(UOW, LEASE)).ifPresent(claimed::add);
        }
        assertThat(claimed).as("one claim of ten").hasSize(1);
        assertThat(claimed.getFirst().attempts()).isEqualTo(1);
        PartyRescreening.Answer answer = run.ask(claimed.getFirst(), LEASE);
        assertThat(kyc.asked).containsExactly(party + "|SANCTIONS|" + PartyRescreening.KEY_PREFIX
                + claimed.getFirst().id().value());
        Optional<RescreeningRun.Concluded> done = conclude(claimed.getFirst(), answer);
        assertThat(done.orElseThrow().conclusion()).isEqualTo(RescreeningObserver.Conclusion.CLEARED);
        commit();
        for (int sweeper = 0; sweeper < 10; sweeper++) {
            assertThat(run.claimNextDue(UOW, LEASE)).as("a concluded permit is never claimed again")
                    .isEqualTo(RescreeningRun.Claim.NOTHING);
        }
        assertThat(signalStore.rows).hasSize(1);
        assertThat(permits.all().getFirst().status()).isEqualTo(RescreeningPermit.Status.DONE);
    }

    @Test
    @DisplayName("a lease lost mid-ask: the slower conclusion of the first claim finds a newer attempt standing and writes"
            + " nothing; the second claim (attempt 2) concludes - one signal, one alert, never two")
    void aLeaseLostMidAskRecordsOnce() {
        UUID party = oneEnqueued(ScreeningList.SANCTIONS, "v1");
        kyc.answers.put(party, screened(true, lists.now.plusSeconds(60)));
        RescreeningPermit first = claimed(run.claimNextDue(UOW, LEASE)).orElseThrow();
        commit();
        PartyRescreening.Answer slow = run.ask(first, LEASE);
        tick(LEASE.plusSeconds(1));
        RescreeningPermit second = claimed(run.claimNextDue(UOW, LEASE)).orElseThrow();
        commit();
        assertThat(second.attempts()).isEqualTo(2);
        assertThat(conclude(first, slow)).as("the earlier claim's conclusion, with the second claim standing").isEmpty();
        commit();
        assertThat(permits.all().getFirst().status()).isEqualTo(RescreeningPermit.Status.PENDING);
        assertThat(conclude(second, run.ask(second, LEASE))).isPresent();
        commit();
        assertThat(signalStore.rows).hasSize(1);
        assertThat(world.alertStore.all()).hasSize(1);
        assertThat(concluded).containsExactly(RescreeningObserver.Conclusion.HIT);
    }

    @Test
    @DisplayName("a hit alerts once and cases: SCREENING_HIT recorded born of the permit, the CRITICAL fact alert of the"
            + " activation's day raised - its own code its rule, no policy - and folded into the party's AML case; a"
            + " second list's hit that day is its own alert (one per party and list version), folded beside it")
    void aHitAlertsOnceAndCases() {
        UUID party = oneEnqueued(ScreeningList.SANCTIONS, "v1");
        Instant activatedAt = lists.all().getFirst().activatedAt().orElseThrow();
        UUID screening = UUID.randomUUID();
        kyc.answers.put(party, new PartyRescreening.Screened(screening, true, activatedAt.plusSeconds(30),
                activatedAt.plusSeconds(31)));

        RescreeningRun.Concluded hit = rescreenOnce().orElseThrow();
        assertThat(hit.conclusion()).isEqualTo(RescreeningObserver.Conclusion.HIT);
        RiskSignal signal = signalStore.rows.getFirst();
        assertThat(signal.kind()).isEqualTo(SignalKind.SCREENING_HIT);
        assertThat(signal.source().id().value()).as("born of the permit").isEqualTo(hit.permit().id().value());
        assertThat(signal.attributes().get("screening_ref")).contains(screening.toString());
        RescreeningPermit done = permits.all().getFirst();
        assertThat(done.outcome()).contains(RescreeningPermit.Outcome.HIT);
        assertThat(done.signalId()).contains(signal.id());

        RiskAlert alert = world.alertStore.all().getFirst();
        assertThat(alert.kind()).isEqualTo(AlertKind.SCREENING_HIT);
        assertThat(alert.rule().code()).isEqualTo("SCREENING_HIT");
        assertThat(alert.severity()).isEqualTo(AlertSeverity.CRITICAL);
        assertThat(alert.rule().ownCode()).as("a fact's own code").isTrue();
        assertThat(alert.policyVersion()).as("no policy names a fact").isEmpty();
        assertThat(alert.window()).isEqualTo(AlertWindow.containing(WindowKind.D1, activatedAt));
        assertThat(alert.evidence().signals()).containsExactly(signal.id());
        assertThat(alert.status()).isEqualTo(AlertStatus.ATTACHED);
        RiskCase aml = world.caseStore.all().getFirst();
        assertThat(aml.kind()).isEqualTo(CaseKind.AML);
        assertThat(aml.subject()).isEqualTo(SignalSubject.of(SubjectKind.PARTY, party));
        assertThat(alert.attachedCase()).contains(aml.id());

        // A PEP version activated the same day hits the same party: its own signal and - the review's IMPORTANT 3 - its
        // own alert, one per (party, list version), folded into the party's open AML case beside the first.
        tick(Duration.ofMinutes(10));
        ScreeningListVersion pep = activated(ScreeningList.PEP, "p1");
        enqueueAll();
        kyc.answers.put(party, screened(true, pep.activatedAt().orElseThrow().plusSeconds(10)));
        assertThat(AlertWindow.containing(WindowKind.D1, pep.activatedAt().orElseThrow()))
                .isEqualTo(AlertWindow.containing(WindowKind.D1, activatedAt));
        assertThat(rescreenOnce().orElseThrow().conclusion()).isEqualTo(RescreeningObserver.Conclusion.HIT);
        assertThat(signalStore.rows).hasSize(2);
        assertThat(world.alertStore.all()).as("born once per (party, list version): two versions, two alerts").hasSize(2)
                .allSatisfy(each -> assertThat(each.attachedCase()).contains(aml.id()));
        assertThat(world.alertStore.all()).extracting(RiskAlert::key).extracting(AlertKey::factSignal)
                .containsExactlyInAnyOrder(Optional.of(signalStore.rows.get(0).id().value()),
                        Optional.of(signalStore.rows.get(1).id().value()));
        assertThat(world.caseStore.all()).as("folded into the one open AML case").hasSize(1);

        // A replay of the second hit's alert meets its own key: born once.
        RescreeningPermit second = permits.all().stream().filter(p -> p.listVersionId().equals(pep.id())).findFirst()
                .orElseThrow();
        CaseWorld.asSystem(() -> world.alerts.raiseAll(UOW, List.of(RescreeningRun.hitAlert(second,
                signalStore.rows.get(1).id(), CorrelationId.of(second.correlationId()),
                com.finapp.sharedkernel.correlation.CausationId.of("replay")))));
        assertThat(world.alertStore.all()).as("the second hit's key is its own, and born once").hasSize(2);
    }

    @Test
    @DisplayName("the review's IMPORTANT 3, its scenario: a PEP v3 hit at 08:00 is cased, the case closed a false positive"
            + " at 10:00, a SANCTIONS v7 hit of the party at 14:00 the same day is its own alert - born, and cased anew"
            + " (a successor naming the closed case), never lost to the first alert's key")
    void aSecondHitOfTheDayAfterTheFirstCaseClosedIsCased() {
        lists.now = Instant.parse("2026-10-26T08:00:00Z");
        permits.now = lists.now;
        UUID party = oneEnqueued(ScreeningList.PEP, "v3");
        kyc.answers.put(party, screened(true, lists.now.plusSeconds(30)));
        assertThat(rescreenOnce().orElseThrow().conclusion()).isEqualTo(RescreeningObserver.Conclusion.HIT);
        RiskCase first = world.caseStore.all().getFirst();
        assertThat(first.kind()).isEqualTo(CaseKind.AML);

        tick(Duration.ofHours(2));
        RiskCase closing = world.cases.current(UOW, first.id()).orElseThrow();
        world.cases.proposeDisposition(UOW, first.id(), closing.revision(), "CLOSED_FALSE_POSITIVE", false,
                "a namesake, not the party", world.analyst(), CaseWorld.correlation());
        assertThat(world.cases.current(UOW, first.id()).orElseThrow().status()).isEqualTo(CaseStatus.DISPOSED);

        tick(Duration.ofHours(4));
        ScreeningListVersion sanctions = activated(ScreeningList.SANCTIONS, "v7");
        enqueueAll();
        assertThat(AlertWindow.containing(WindowKind.D1, sanctions.activatedAt().orElseThrow()))
                .as("the same UTC day").isEqualTo(AlertWindow.containing(WindowKind.D1,
                        Instant.parse("2026-10-26T08:00:00Z")));
        kyc.answers.put(party, screened(true, permits.now.plusSeconds(30)));
        assertThat(rescreenOnce().orElseThrow().conclusion()).isEqualTo(RescreeningObserver.Conclusion.HIT);

        assertThat(world.alertStore.all()).as("two hits, two alerts").hasSize(2);
        RiskAlert sanctionsHit = world.alertStore.all().stream()
                .filter(alert -> alert.evidence().signals().contains(signalStore.rows.get(1).id())).findFirst()
                .orElseThrow();
        assertThat(sanctionsHit.status()).as("born and cased, never an orphan of the closed case")
                .isEqualTo(AlertStatus.ATTACHED);
        RiskCase second = world.cases.current(UOW, sanctionsHit.attachedCase().orElseThrow()).orElseThrow();
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.status()).isEqualTo(CaseStatus.OPEN);
        assertThat(second.kind()).isEqualTo(CaseKind.AML);
        assertThat(second.predecessor()).contains(first.id());
    }

    @Test
    @DisplayName("the last review's item 3: a hit told while the permit is PENDING concludes it HIT at once - the signal"
            + " born of the permit, the alert, the case - and the claim's slower conclusion, computed from a clear read"
            + " before the hit committed, records nothing")
    void aHitToldWhilePendingConcludesItAndAStaleClearRecordsNothing() {
        oneEnqueued(ScreeningList.SANCTIONS, "v1");
        RescreeningPermit claimed = claimed(run.claimNextDue(UOW, LEASE)).orElseThrow();
        commit();
        PartyRescreening.Answer stale = screened(false, lists.now.plusSeconds(30));
        PartyRescreening.Screened hit = screened(true, lists.now.plusSeconds(40));
        Optional<RescreeningRun.Concluded> told = CaseWorld.asSystem(() -> run.hit(UOW, claimed.id(), hit));
        commit();
        assertThat(told.orElseThrow().conclusion()).isEqualTo(RescreeningObserver.Conclusion.HIT);
        assertThat(conclude(claimed, stale)).as("the stale clear finds the permit DONE").isEmpty();
        commit();
        RescreeningPermit done = permits.all().getFirst();
        assertThat(done.outcome()).contains(RescreeningPermit.Outcome.HIT);
        assertThat(signalStore.rows).singleElement().satisfies(signal -> {
            assertThat(signal.kind()).isEqualTo(SignalKind.SCREENING_HIT);
            assertThat(signal.source().id().value()).as("born of the permit").isEqualTo(claimed.id().value());
        });
        assertThat(world.alertStore.all()).singleElement()
                .satisfies(alert -> assertThat(alert.status()).isEqualTo(AlertStatus.ATTACHED));
        assertThat(world.caseStore.all()).extracting(RiskCase::kind).containsExactly(CaseKind.AML);
        assertThat(concluded).containsExactly(RescreeningObserver.Conclusion.HIT);
        assertThat(CaseWorld.asSystem(() -> run.hit(UOW, claimed.id(), hit))).as("told twice: once").isEmpty();
    }

    @Test
    @DisplayName("the last review's item 3: a hit told after the permit concluded CLEARED - a late callback for an"
            + " abandoned question - is still told, once: its own SCREENING_HIT signal born of kyc's check, its own alert"
            + " folded into the party's AML case; the permit stays as it concluded")
    void aLateHitAfterAClearIsStillToldOnce() {
        UUID party = oneEnqueued(ScreeningList.SANCTIONS, "v1");
        kyc.answers.put(party, screened(false, lists.now.plusSeconds(30)));
        assertThat(rescreenOnce().orElseThrow().conclusion()).isEqualTo(RescreeningObserver.Conclusion.CLEARED);
        RescreeningPermit cleared = permits.all().getFirst();
        UUID abandonedQuestion = EvaluationWorld.IDS.next();
        PartyRescreening.Screened late = new PartyRescreening.Screened(abandonedQuestion, true,
                lists.now.plusSeconds(20), lists.now.plusSeconds(90));

        Optional<RescreeningRun.Concluded> told = CaseWorld.asSystem(() -> run.hit(UOW, cleared.id(), late));
        commit();
        assertThat(told.orElseThrow().conclusion()).isEqualTo(RescreeningObserver.Conclusion.LATE_HIT);
        assertThat(permits.all().getFirst().outcome()).as("the permit as it concluded")
                .contains(RescreeningPermit.Outcome.CLEARED);
        assertThat(signalStore.rows).extracting(RiskSignal::kind)
                .containsExactly(SignalKind.SCREENING_CLEARED, SignalKind.SCREENING_HIT);
        RiskSignal lateSignal = signalStore.rows.get(1);
        assertThat(lateSignal.source().id().value()).as("born of kyc's check, never the permit")
                .isEqualTo(abandonedQuestion);
        assertThat(lateSignal.attributes().get("screening_ref")).contains(abandonedQuestion.toString());
        RiskAlert alert = world.alertStore.all().getFirst();
        assertThat(world.alertStore.all()).hasSize(1);
        assertThat(alert.key().factSignal()).contains(lateSignal.id().value());
        assertThat(alert.status()).isEqualTo(AlertStatus.ATTACHED);
        assertThat(world.caseStore.all()).extracting(RiskCase::kind).containsExactly(CaseKind.AML);

        assertThat(CaseWorld.asSystem(() -> run.hit(UOW, cleared.id(), late))).as("a replay tells nothing more").isEmpty();
        assertThat(signalStore.rows).hasSize(2);
        assertThat(world.alertStore.all()).hasSize(1);
        assertThat(concluded).containsExactly(RescreeningObserver.Conclusion.CLEARED,
                RescreeningObserver.Conclusion.LATE_HIT);
    }

    @Test
    @DisplayName("the last review's item 3: a hit told on a permit concluded HIT, on a permit no claim has asked, or asked"
            + " before the version's activation records nothing")
    void aHitToldWhereNothingIsOwedRecordsNothing() {
        UUID party = oneEnqueued(ScreeningList.SANCTIONS, "v1");
        RescreeningPermit unclaimed = permits.all().getFirst();
        assertThat(CaseWorld.asSystem(() -> run.hit(UOW, unclaimed.id(), screened(true, lists.now.plusSeconds(5)))))
                .as("no claim yet: the first claim reads the hit").isEmpty();
        assertThat(CaseWorld.asSystem(() -> run.hit(UOW, unclaimed.id(), screened(true, lists.now.minusSeconds(5)))))
                .as("asked before the activation").isEmpty();
        commit();
        kyc.answers.put(party, screened(true, lists.now.plusSeconds(30)));
        assertThat(rescreenOnce().orElseThrow().conclusion()).isEqualTo(RescreeningObserver.Conclusion.HIT);
        assertThat(CaseWorld.asSystem(() -> run.hit(UOW, unclaimed.id(), screened(true, lists.now.plusSeconds(60)))))
                .as("concluded HIT: told already").isEmpty();
        assertThat(signalStore.rows).hasSize(1);
        assertThat(world.alertStore.all()).hasSize(1);
        assertThatThrownBy(() -> run.hit(UOW, unclaimed.id(), screened(false, lists.now.plusSeconds(60))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the review's IMPORTANT 4: an onboarding party with no KYC case is DONE(NOT_ONBOARDED) - counted on its"
            + " own, nothing recorded, never the exposure FAILED counts")
    void anOnboardingPartyWithNoCaseIsNotOnboarded() {
        UUID party = oneEnqueued(ScreeningList.SANCTIONS, "v1");
        kyc.answers.put(party, new PartyRescreening.NotOnboarded());
        assertThat(rescreenOnce().orElseThrow().conclusion()).isEqualTo(RescreeningObserver.Conclusion.NOT_ONBOARDED);
        RescreeningPermit done = permits.all().getFirst();
        assertThat(done.status()).isEqualTo(RescreeningPermit.Status.DONE);
        assertThat(done.outcome()).contains(RescreeningPermit.Outcome.NOT_ONBOARDED);
        assertThat(done.signalId()).isEmpty();
        assertThat(signalStore.rows).isEmpty();
        assertThat(concluded).containsExactly(RescreeningObserver.Conclusion.NOT_ONBOARDED);
        assertThat(RescreeningPermit.Outcome.NOT_ONBOARDED.recordsASignal()).isFalse();
    }

    @Test
    @DisplayName("the review's IMPORTANT 2: each claim's question carries its attempt - the most key ordinals it may have"
            + " asked - and its abandonment line, the claim's instant less one lease, both on the database's clock")
    void theQuestionCarriesTheClaimsAttemptAndAbandonmentLine() {
        UUID party = oneEnqueued(ScreeningList.SANCTIONS, "v1");
        for (int attempt = 1; attempt <= 3; attempt++) {
            Instant claimedAt = permits.now;
            RescreeningPermit claimed = claimed(run.claimNextDue(UOW, LEASE)).orElseThrow();
            commit();
            conclude(claimed, run.ask(claimed, LEASE));
            commit();
            PartyRescreening.Question question = kyc.questions.getLast();
            assertThat(question.attempt()).isEqualTo(attempt);
            assertThat(question.abandonedBefore()).isEqualTo(claimedAt.minus(LEASE));
            assertThat(question.requestKey()).isEqualTo(claimed.requestKey());
            assertThat(question.partyId()).isEqualTo(party);
            tick(Duration.ofHours(7));
        }
    }

    @Test
    @DisplayName("a clear is recorded once: SCREENING_CLEARED about the party, observed when kyc decided it, naming the"
            + " list version and kyc's screening by reference; RiskSignalRecorded carries ids and codes only; no alert")
    void aClearIsRecordedOnce() {
        UUID party = oneEnqueued(ScreeningList.PEP, "v3");
        ScreeningListVersion version = lists.all().getFirst();
        Instant decided = version.activatedAt().orElseThrow().plus(Duration.ofHours(2));
        UUID screening = UUID.randomUUID();
        kyc.answers.put(party, new PartyRescreening.Screened(screening, false, decided.minus(Duration.ofHours(1)),
                decided));
        RescreeningRun.Concluded cleared = rescreenOnce().orElseThrow();
        assertThat(cleared.conclusion()).isEqualTo(RescreeningObserver.Conclusion.CLEARED);
        RiskSignal signal = signalStore.rows.getFirst();
        assertThat(signal.kind()).isEqualTo(SignalKind.SCREENING_CLEARED);
        assertThat(signal.subject()).isEqualTo(SignalSubject.of(SubjectKind.PARTY, party));
        assertThat(signal.observedAt()).isEqualTo(decided);
        assertThat(signal.attributes().asMap()).containsExactly(
                Map.entry("list_version_ref", version.id().value().toString()),
                Map.entry("screening_ref", screening.toString()));
        assertThat(signal.source().type()).isEqualTo("risk.RescreeningCleared");
        assertThat(signal.source().producer()).isEqualTo("risk");
        assertThat(signal.source().correlation().value()).isEqualTo(version.correlationId());
        assertThat(events).extracting(EventEnvelope::eventType).containsExactly(RiskSignals.EVENT_TYPE);
        assertThat(payloads.getFirst()).containsOnlyKeys("signalId", "kind", "subjectKind", "subjectRef")
                .containsEntry("kind", "SCREENING_CLEARED").containsEntry("subjectKind", "PARTY");
        assertThat(world.alertStore.all()).isEmpty();
        assertThat(permits.all().getFirst().outcome()).contains(RescreeningPermit.Outcome.CLEARED);
    }

    @Test
    @DisplayName("INV-RSK-02 - risk asks, kyc screens: a screening kyc decided BEFORE the activation is STALE - never a"
            + " clearance under the version - backed off, and FAILED with its cause at the bound; nothing recorded")
    void aStaleClearanceIsNotARescreen() {
        run = run(BOUNDS);
        UUID party = oneEnqueued(ScreeningList.SANCTIONS, "v1");
        Instant activatedAt = lists.all().getFirst().activatedAt().orElseThrow();
        kyc.answers.put(party, new PartyRescreening.Screened(UUID.randomUUID(), false, activatedAt.minusSeconds(2),
                activatedAt.minusSeconds(1)));
        assertThat(rescreenOnce().orElseThrow().conclusion()).isEqualTo(RescreeningObserver.Conclusion.DEFERRED);
        RescreeningPermit waiting = permits.all().getFirst();
        assertThat(waiting.cause()).contains(RescreeningPermit.Cause.STALE);
        assertThat(waiting.nextAttemptAt()).contains(permits.now.plus(Duration.ofMinutes(5)));
        assertThat(run.claimNextDue(UOW, LEASE)).as("not due before its backoff")
                .isEqualTo(RescreeningRun.Claim.NOTHING);
        tick(Duration.ofMinutes(6));
        assertThat(rescreenOnce().orElseThrow().conclusion()).isEqualTo(RescreeningObserver.Conclusion.DEFERRED);
        assertThat(permits.all().getFirst().nextAttemptAt()).as("the backoff doubles")
                .contains(permits.now.plus(Duration.ofMinutes(10)));
        tick(Duration.ofMinutes(11));
        assertThat(rescreenOnce().orElseThrow().conclusion()).isEqualTo(RescreeningObserver.Conclusion.FAILED);
        RescreeningPermit failed = permits.all().getFirst();
        assertThat(failed.status()).isEqualTo(RescreeningPermit.Status.FAILED);
        assertThat(failed.cause()).contains(RescreeningPermit.Cause.STALE);
        assertThat(failed.attempts()).isEqualTo(3);
        assertThat(signalStore.rows).isEmpty();
        assertThat(world.alertStore.all()).isEmpty();
    }

    @Test
    @DisplayName("currency is judged by the ask, never the answer: a check kyc was asked BEFORE the activation and decided"
            + " after it is STALE - never a clearance under the version; one millisecond before is stale; an ask at the"
            + " activation itself - one clock, the database's - is the rescreening")
    void aCheckAskedBeforeTheActivationIsStaleHoweverLateItsAnswer() {
        UUID party = oneEnqueued(ScreeningList.SANCTIONS, "v1");
        Instant activatedAt = lists.all().getFirst().activatedAt().orElseThrow();
        kyc.answers.put(party, new PartyRescreening.Screened(UUID.randomUUID(), false, activatedAt.minusSeconds(30),
                activatedAt.plus(Duration.ofHours(1))));
        assertThat(rescreenOnce().orElseThrow().conclusion()).as("dispatched before, decided after: a false clearance")
                .isEqualTo(RescreeningObserver.Conclusion.DEFERRED);
        assertThat(permits.all().getFirst().cause()).contains(RescreeningPermit.Cause.STALE);

        tick(Duration.ofMinutes(6));
        kyc.answers.put(party, new PartyRescreening.Screened(UUID.randomUUID(), false, activatedAt.minusMillis(1),
                activatedAt.plus(Duration.ofHours(2))));
        assertThat(rescreenOnce().orElseThrow().conclusion()).as("asked a millisecond before the version came into force")
                .isEqualTo(RescreeningObserver.Conclusion.DEFERRED);
        assertThat(signalStore.rows).isEmpty();

        tick(Duration.ofMinutes(11));
        kyc.answers.put(party, new PartyRescreening.Screened(UUID.randomUUID(), false, activatedAt,
                activatedAt.plus(Duration.ofHours(3))));
        assertThat(rescreenOnce().orElseThrow().conclusion()).isEqualTo(RescreeningObserver.Conclusion.CLEARED);
        assertThat(signalStore.rows).hasSize(1);
        assertThatThrownBy(() -> new PartyRescreening.Screened(UUID.randomUUID(), false, activatedAt,
                activatedAt.minusSeconds(1))).as("decided before it was asked").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a conclusion that always fails is bounded: each claim counts an attempt, and the claim past the bound fails"
            + " the permit UNCONCLUDED without asking kyc again - never re-claimed every lease forever")
    void aConclusionThatAlwaysFailsIsBounded() {
        UUID party = oneEnqueued(ScreeningList.PEP, "v1");
        kyc.answers.put(party, screened(false, lists.now.plusSeconds(60)));
        RiskSignalStore refusing = new MemorySignals() {
            @Override
            public boolean insert(Connection unitOfWork, RiskSignal signal) {
                throw new IllegalStateException("the store refuses the row");
            }
        };
        run = new RescreeningRun(lists, permits, this::page, kyc,
                new RiskSignals(refusing, (uow, envelope, payload, mediaType) -> { }, EvaluationWorld.IDS,
                        EvaluationWorld.CLOCK),
                world.alerts::raiseAll, EvaluationWorld.IDS, observer(), BOUNDS);
        for (int attempt = 1; attempt <= BOUNDS.maxAttempts(); attempt++) {
            RescreeningPermit permit = claimed(run.claimNextDue(UOW, LEASE)).orElseThrow();
            commit();
            assertThat(permit.attempts()).isEqualTo(attempt);
            PartyRescreening.Answer answer = run.ask(permit, LEASE);
            assertThatThrownBy(() -> conclude(permit, answer)).hasMessageContaining("refuses");
            commit();
            tick(LEASE.plusSeconds(1));
        }
        assertThat(kyc.asked).hasSize(BOUNDS.maxAttempts());
        RescreeningRun.Claim past = run.claimNextDue(UOW, LEASE);
        commit();
        assertThat(past).isInstanceOf(RescreeningRun.Claim.Exhausted.class);
        RescreeningPermit failed = permits.all().getFirst();
        assertThat(failed.status()).isEqualTo(RescreeningPermit.Status.FAILED);
        assertThat(failed.cause()).contains(RescreeningPermit.Cause.UNCONCLUDED);
        assertThat(failed.attempts()).isEqualTo(BOUNDS.maxAttempts() + 1);
        assertThat(kyc.asked).as("kyc is not asked past the bound").hasSize(BOUNDS.maxAttempts());
        assertThat(concluded).containsExactly(RescreeningObserver.Conclusion.FAILED);
        tick(Duration.ofHours(7));
        assertThat(run.claimNextDue(UOW, LEASE)).isEqualTo(RescreeningRun.Claim.NOTHING);
    }

    @Test
    @DisplayName("kyc unavailable: the port's throw is Unavailable - its class counted, never its message - the permit"
            + " backed off and FAILED(UNAVAILABLE) at the bound")
    void kycUnavailableBacksOffThenFails() {
        run = run(BOUNDS);
        UUID party = oneEnqueued(ScreeningList.PEP, "v1");
        kyc.failing.add(party);
        for (int attempt = 1; attempt <= 3; attempt++) {
            RescreeningRun.Concluded result = rescreenOnce().orElseThrow();
            assertThat(result.conclusion()).isEqualTo(attempt < 3 ? RescreeningObserver.Conclusion.DEFERRED
                    : RescreeningObserver.Conclusion.FAILED);
            tick(Duration.ofMinutes(16));
        }
        assertThat(permits.all().getFirst().cause()).contains(RescreeningPermit.Cause.UNAVAILABLE);
        assertThat(askFailures).containsOnly(IllegalStateException.class).hasSize(3);
        assertThat(signalStore.rows).isEmpty();
    }

    @Test
    @DisplayName("kyc pending: a screening in flight waits IN_FLIGHT, none ever decided NOT_SCREENED - re-permitted, never"
            + " a clearance; once kyc's current screening arrives, it is recorded")
    void kycPendingWaits() {
        UUID party = oneEnqueued(ScreeningList.SANCTIONS, "v1");
        kyc.answers.put(party, new PartyRescreening.Pending(PartyRescreening.Wait.IN_FLIGHT));
        rescreenOnce();
        assertThat(permits.all().getFirst().cause()).contains(RescreeningPermit.Cause.IN_FLIGHT);
        tick(Duration.ofMinutes(6));
        kyc.answers.put(party, new PartyRescreening.Pending(PartyRescreening.Wait.NOT_SCREENED));
        rescreenOnce();
        assertThat(permits.all().getFirst().cause()).contains(RescreeningPermit.Cause.NOT_SCREENED);
        tick(Duration.ofMinutes(11));
        kyc.answers.put(party, screened(false, permits.now));
        assertThat(rescreenOnce().orElseThrow().conclusion()).isEqualTo(RescreeningObserver.Conclusion.CLEARED);
        assertThat(permits.all().getFirst().cause()).as("a done permit keeps no cause").isEmpty();
        assertThat(signalStore.rows).hasSize(1);
    }

    @Test
    @DisplayName("a party deactivated meanwhile is DONE(SKIPPED): nothing recorded, nothing alerted")
    void aPartyDeactivatedMeanwhileIsSkipped() {
        UUID party = oneEnqueued(ScreeningList.SANCTIONS, "v1");
        kyc.answers.put(party, new PartyRescreening.Inactive());
        assertThat(rescreenOnce().orElseThrow().conclusion()).isEqualTo(RescreeningObserver.Conclusion.SKIPPED);
        RescreeningPermit skipped = permits.all().getFirst();
        assertThat(skipped.outcome()).contains(RescreeningPermit.Outcome.SKIPPED);
        assertThat(skipped.signalId()).isEmpty();
        assertThat(signalStore.rows).isEmpty();
    }

    @Test
    @DisplayName("the review's IMPORTANT 2: a hit is alerted whatever policy is in force - none here - because its alert is a"
            + " fact, raised by no rule: the alert born, the AML case opened, the permit DONE(HIT)")
    void aHitIsAlertedWhateverPolicyIsInForce() {
        UUID party = oneEnqueued(ScreeningList.SANCTIONS, "v1");
        kyc.answers.put(party, screened(true, lists.now.plusSeconds(60)));
        assertThat(rescreenOnce().orElseThrow().conclusion()).isEqualTo(RescreeningObserver.Conclusion.HIT);
        assertThat(signalStore.rows).extracting(RiskSignal::kind).containsExactly(SignalKind.SCREENING_HIT);
        assertThat(world.alertStore.all()).as("no MONITORING version exists, and the hit is alerted").hasSize(1);
        assertThat(world.alertStore.all().getFirst().policyVersion()).isEmpty();
        assertThat(world.caseStore.all()).extracting(RiskCase::kind).containsExactly(CaseKind.AML);
        assertThat(permits.all().getFirst().outcome()).contains(RescreeningPermit.Outcome.HIT);
    }

    @Test
    @DisplayName("the bounds: the backoff doubles from its first wait to its cap; a claim's lease is positive; a batch is"
            + " 1..ActiveParties.BOUND")
    void theBounds() {
        RescreeningRun.Bounds bounds = RescreeningRun.Bounds.DEFAULT;
        assertThat(bounds.maxAttempts()).isEqualTo(12);
        assertThat(bounds.backoff(1)).isEqualTo(Duration.ofMinutes(5));
        assertThat(bounds.backoff(2)).isEqualTo(Duration.ofMinutes(10));
        assertThat(bounds.backoff(7)).isEqualTo(Duration.ofMinutes(320));
        assertThat(bounds.backoff(8)).isEqualTo(Duration.ofHours(6));
        assertThat(bounds.backoff(40)).isEqualTo(Duration.ofHours(6));
        assertThatThrownBy(() -> run.claimNextDue(UOW, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> run.enqueueNextBatch(UOW, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> run.enqueueNextBatch(UOW, ActiveParties.BOUND + 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RescreeningRun.Bounds(0, Duration.ofMinutes(1), Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ the world

    private RescreeningRun run(RescreeningRun.Bounds bounds) {
        return new RescreeningRun(lists, permits, this::page, kyc, signals(), world.alerts::raiseAll,
                EvaluationWorld.IDS, observer(), bounds);
    }

    /** The meters' port, remembering what it was told. */
    private RescreeningObserver observer() {
        return new RescreeningObserver() {
            @Override
            public void concluded(Conclusion conclusion) {
                concluded.add(conclusion);
            }

            @Override
            public void enqueued(int born) {
                enqueued.add(born);
            }

            @Override
            public void askFailed(Class<? extends Throwable> cause) {
                askFailures.add(cause);
            }
        };
    }

    private RiskSignals signals() {
        OutboxWriter<Connection> outbox = (uow, envelope, payload, mediaType) -> {
            events.add(envelope);
            payloads.add(EventPayload.fields(payload));
        };
        return new RiskSignals(signalStore, outbox, EvaluationWorld.IDS, EvaluationWorld.CLOCK);
    }

    private List<UUID> page(Connection unitOfWork, Optional<UUID> after, int limit) {
        // The database's order - each half unsigned - as PostgreSQL pages uuid.
        return active.stream().filter(party -> after.isEmpty() || RescreeningRun.databaseOrder(party, after.get()) > 0)
                .limit(limit).toList();
    }

    /** A version recorded by one person and activated by another, at the stores' database clock. */
    private ScreeningListVersion activated(ScreeningList list, String label) {
        ScreeningListVersion recorded = versions.record(UOW, list, label, "a new edition", OFFICER,
                CorrelationId.of("list-" + label)).version();
        return versions.activate(UOW, recorded.id(), "in force", SECOND, CorrelationId.of("activation-" + label))
                .version();
    }

    /** One active party, one version activated and fully enqueued; the party. */
    private UUID oneEnqueued(ScreeningList list, String label) {
        UUID party = UUID.randomUUID();
        active.add(party);
        activated(list, label);
        enqueueAll();
        return party;
    }

    private void enqueueAll() {
        while (run.enqueueNextBatch(UOW, 100).claimed()) {
            commit();
        }
        commit();
    }

    /** One sweeper's three steps on the next due permit, each its own fake transaction. */
    private Optional<RescreeningRun.Concluded> rescreenOnce() {
        Optional<RescreeningPermit> claimed = claimed(run.claimNextDue(UOW, LEASE));
        commit();
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        Optional<RescreeningRun.Concluded> done = conclude(claimed.get(), run.ask(claimed.get(), LEASE));
        commit();
        return done;
    }

    private Optional<RescreeningRun.Concluded> conclude(RescreeningPermit claimed, PartyRescreening.Answer answer) {
        Supplier<Optional<RescreeningRun.Concluded>> step = () -> run.conclude(UOW, claimed, answer);
        return CaseWorld.asSystem(step);
    }

    private void commit() {
        lists.release();
        permits.release();
    }

    /** The database clock moves for every store. */
    private void tick(Duration by) {
        lists.now = lists.now.plus(by);
        permits.now = permits.now.plus(by);
    }

    /** kyc's screening asked at {@code askedAt} and decided a second later. */
    private static PartyRescreening.Screened screened(boolean hit, Instant askedAt) {
        return new PartyRescreening.Screened(UUID.randomUUID(), hit, askedAt, askedAt.plusSeconds(1));
    }

    /** The permit a claim took for its ask - empty when nothing was due or the claim failed it past the bound. */
    private static Optional<RescreeningPermit> claimed(RescreeningRun.Claim claim) {
        return claim instanceof RescreeningRun.Claim.Claimed taken ? Optional.of(taken.permit()) : Optional.empty();
    }

    /** kyc's port, scripted: an answer per party, or a throw; every request remembered. */
    private static final class Kyc implements PartyRescreening {
        final Map<UUID, Answer> answers = new HashMap<>();
        final List<UUID> failing = new ArrayList<>();
        final List<String> asked = new ArrayList<>();
        final List<Question> questions = new ArrayList<>();

        @Override
        public Answer ask(Question question) {
            questions.add(question);
            asked.add(question.partyId() + "|" + question.list() + "|" + question.requestKey());
            if (failing.contains(question.partyId())) {
                throw new IllegalStateException("kyc's store could not be read: a message that never leaves");
            }
            return answers.getOrDefault(question.partyId(),
                    new PartyRescreening.Pending(PartyRescreening.Wait.NOT_SCREENED));
        }
    }

    /** risk_signal in memory: born once per source event. */
    static class MemorySignals implements RiskSignalStore {
        final List<RiskSignal> rows = new ArrayList<>();
        private final Map<UUID, RiskSignal> bySource = new LinkedHashMap<>();

        @Override
        public boolean insert(Connection unitOfWork, RiskSignal signal) {
            if (bySource.containsKey(signal.source().id().value())) {
                return false;
            }
            bySource.put(signal.source().id().value(), signal);
            rows.add(signal);
            return true;
        }

        @Override
        public int countSince(Connection unitOfWork, SignalSubject subject, SignalKind kind, Instant since) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int countByMerchantSince(Connection unitOfWork, UUID merchant, SignalKind kind, Instant since) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<RiskSignal> latest(Connection unitOfWork, SignalSubject subject, SignalKind kind) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Instant> firstObservedAt(Connection unitOfWork, SignalSubject subject, SignalKind kind) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean seen(Connection unitOfWork, SignalSubject subject) {
            throw new UnsupportedOperationException();
        }
    }

    /** The list store, crashing at the enqueue's advance - after the batch's permits were inserted. */
    private static final class CrashOnAdvance implements ScreeningListStore {
        private final ScreeningListStore delegate;

        CrashOnAdvance(ScreeningListStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public void lockList(Connection unitOfWork, ScreeningList list) {
            delegate.lockList(unitOfWork, list);
        }

        @Override
        public int maxOrdinal(Connection unitOfWork, ScreeningList list) {
            return delegate.maxOrdinal(unitOfWork, list);
        }

        @Override
        public Optional<ScreeningListVersion> insert(Connection unitOfWork, NewVersion version) {
            return delegate.insert(unitOfWork, version);
        }

        @Override
        public Optional<ScreeningListVersion> find(Connection unitOfWork, ScreeningListVersionId id) {
            return delegate.find(unitOfWork, id);
        }

        @Override
        public Optional<ScreeningListVersion> findByLabel(Connection unitOfWork, ScreeningList list, String version) {
            return delegate.findByLabel(unitOfWork, list, version);
        }

        @Override
        public Optional<ScreeningListVersion> lock(Connection unitOfWork, ScreeningListVersionId id) {
            return delegate.lock(unitOfWork, id);
        }

        @Override
        public Optional<ScreeningListVersion> lockActive(Connection unitOfWork, ScreeningList list) {
            return delegate.lockActive(unitOfWork, list);
        }

        @Override
        public boolean retire(Connection unitOfWork, ScreeningListVersionId id) {
            return delegate.retire(unitOfWork, id);
        }

        @Override
        public Optional<ScreeningListVersion> activate(Connection unitOfWork, ScreeningListVersionId id, Actor activator,
                String correlationId) {
            return delegate.activate(unitOfWork, id, activator, correlationId);
        }

        @Override
        public Optional<ScreeningListVersion> claimEnqueueDue(Connection unitOfWork) {
            return delegate.claimEnqueueDue(unitOfWork);
        }

        @Override
        public ScreeningListVersion advanceEnqueue(Connection unitOfWork, ScreeningListVersionId id,
                Optional<UUID> cursor, int born, boolean complete) {
            throw new IllegalStateException("crash before the advance");
        }
    }
}
