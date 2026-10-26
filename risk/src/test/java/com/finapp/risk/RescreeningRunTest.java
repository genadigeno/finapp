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
import com.finapp.risk.testing.RiskPolicyFixtures;
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
    private final TreeSet<UUID> active = new TreeSet<>();
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
                signals(), new RiskPolicies(world.policyStore), world.alerts::raiseAll, EvaluationWorld.IDS,
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

    // ------------------------------------------------------------------ a permit

    @Test
    @DisplayName("ten sweepers rescreen a permit once: one claim per lease, kyc asked once under the permit's key, one"
            + " conclusion, one signal")
    void tenSweepersRescreenOnce() {
        UUID party = oneEnqueued(ScreeningList.SANCTIONS, "v1");
        kyc.answers.put(party, screened(false, lists.now.plusSeconds(1)));
        List<RescreeningPermit> claimed = new ArrayList<>();
        for (int sweeper = 0; sweeper < 10; sweeper++) {
            run.claimNextDue(UOW, LEASE).ifPresent(claimed::add);
        }
        assertThat(claimed).as("one claim of ten").hasSize(1);
        assertThat(claimed.getFirst().attempts()).isEqualTo(1);
        PartyRescreening.Answer answer = run.ask(claimed.getFirst());
        assertThat(kyc.asked).containsExactly(party + "|SANCTIONS|" + PartyRescreening.KEY_PREFIX
                + claimed.getFirst().id().value());
        Optional<RescreeningRun.Concluded> done = conclude(claimed.getFirst(), answer);
        assertThat(done.orElseThrow().conclusion()).isEqualTo(RescreeningObserver.Conclusion.CLEARED);
        commit();
        for (int sweeper = 0; sweeper < 10; sweeper++) {
            assertThat(run.claimNextDue(UOW, LEASE)).as("a concluded permit is never claimed again").isEmpty();
        }
        assertThat(signalStore.rows).hasSize(1);
        assertThat(permits.all().getFirst().status()).isEqualTo(RescreeningPermit.Status.DONE);
    }

    @Test
    @DisplayName("a lease lost mid-ask: the slower conclusion of the first claim finds a newer attempt standing and writes"
            + " nothing; the second claim (attempt 2) concludes - one signal, one alert, never two")
    void aLeaseLostMidAskRecordsOnce() {
        UUID party = oneEnqueued(ScreeningList.SANCTIONS, "v1");
        kyc.answers.put(party, screened(true, lists.now.plusSeconds(1)));
        world.policyStore.activate(RiskPolicyFixtures.v1(RiskOperation.MONITORING));
        RescreeningPermit first = run.claimNextDue(UOW, LEASE).orElseThrow();
        commit();
        PartyRescreening.Answer slow = run.ask(first);
        tick(LEASE.plusSeconds(1));
        RescreeningPermit second = run.claimNextDue(UOW, LEASE).orElseThrow();
        commit();
        assertThat(second.attempts()).isEqualTo(2);
        assertThat(conclude(first, slow)).as("the earlier claim's conclusion, with the second claim standing").isEmpty();
        commit();
        assertThat(permits.all().getFirst().status()).isEqualTo(RescreeningPermit.Status.PENDING);
        assertThat(conclude(second, run.ask(second))).isPresent();
        commit();
        assertThat(signalStore.rows).hasSize(1);
        assertThat(world.alertStore.all()).hasSize(1);
        assertThat(concluded).containsExactly(RescreeningObserver.Conclusion.HIT);
    }

    @Test
    @DisplayName("a hit alerts once and cases: SCREENING_HIT recorded born of the permit, the MONITORING version's"
            + " SCREENING_HIT rule raises the CRITICAL alert of the activation's hour, folded into the party's AML case; a"
            + " second list's hit that hour meets the standing alert")
    void aHitAlertsOnceAndCases() {
        RiskPolicyVersionId monitoring = world.policyStore.activate(RiskPolicyFixtures.v1(RiskOperation.MONITORING));
        UUID party = oneEnqueued(ScreeningList.SANCTIONS, "v1");
        Instant activatedAt = lists.all().getFirst().activatedAt().orElseThrow();
        UUID screening = UUID.randomUUID();
        kyc.answers.put(party, new PartyRescreening.Screened(screening, true, activatedAt.plusSeconds(30)));

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
        assertThat(alert.policyVersion()).contains(monitoring);
        assertThat(alert.window()).isEqualTo(AlertWindow.containing(WindowKind.H1, activatedAt));
        assertThat(alert.evidence().signals()).containsExactly(signal.id());
        assertThat(alert.status()).isEqualTo(AlertStatus.ATTACHED);
        RiskCase aml = world.caseStore.all().getFirst();
        assertThat(aml.kind()).isEqualTo(CaseKind.AML);
        assertThat(aml.subject()).isEqualTo(SignalSubject.of(SubjectKind.PARTY, party));
        assertThat(alert.attachedCase()).contains(aml.id());

        // A PEP version activated in the same hour hits the same party: its own signal, the alert standing - one alert,
        // one case.
        tick(Duration.ofMinutes(10));
        ScreeningListVersion pep = activated(ScreeningList.PEP, "p1");
        enqueueAll();
        kyc.answers.put(party, new PartyRescreening.Screened(UUID.randomUUID(), true,
                pep.activatedAt().orElseThrow().plusSeconds(5)));
        assertThat(AlertWindow.containing(WindowKind.H1, pep.activatedAt().orElseThrow()))
                .isEqualTo(AlertWindow.containing(WindowKind.H1, activatedAt));
        assertThat(rescreenOnce().orElseThrow().conclusion()).isEqualTo(RescreeningObserver.Conclusion.HIT);
        assertThat(signalStore.rows).hasSize(2);
        assertThat(world.alertStore.all()).as("born once per (party, rule, window start)").hasSize(1);
        assertThat(world.caseStore.all()).hasSize(1);
    }

    @Test
    @DisplayName("a clear is recorded once: SCREENING_CLEARED about the party, observed when kyc decided it, naming the"
            + " list version and kyc's screening by reference; RiskSignalRecorded carries ids and codes only; no alert")
    void aClearIsRecordedOnce() {
        UUID party = oneEnqueued(ScreeningList.PEP, "v3");
        ScreeningListVersion version = lists.all().getFirst();
        Instant decided = version.activatedAt().orElseThrow().plus(Duration.ofHours(2));
        UUID screening = UUID.randomUUID();
        kyc.answers.put(party, new PartyRescreening.Screened(screening, false, decided));
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
        kyc.answers.put(party, new PartyRescreening.Screened(UUID.randomUUID(), false, activatedAt.minusSeconds(1)));
        assertThat(rescreenOnce().orElseThrow().conclusion()).isEqualTo(RescreeningObserver.Conclusion.DEFERRED);
        RescreeningPermit waiting = permits.all().getFirst();
        assertThat(waiting.cause()).contains(RescreeningPermit.Cause.STALE);
        assertThat(waiting.nextAttemptAt()).contains(permits.now.plus(Duration.ofMinutes(5)));
        assertThat(run.claimNextDue(UOW, LEASE)).as("not due before its backoff").isEmpty();
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
    @DisplayName("a hit no MONITORING rule names - no version in force - is recorded and counted HIT_NOT_ALERTED: an alert"
            + " names the policy rule that raised it, so none is invented; never silently")
    void aHitNoRuleNamesIsRecordedNotAlerted() {
        UUID party = oneEnqueued(ScreeningList.SANCTIONS, "v1");
        kyc.answers.put(party, screened(true, lists.now.plusSeconds(1)));
        assertThat(rescreenOnce().orElseThrow().conclusion()).isEqualTo(RescreeningObserver.Conclusion.HIT_NOT_ALERTED);
        assertThat(signalStore.rows).extracting(RiskSignal::kind).containsExactly(SignalKind.SCREENING_HIT);
        assertThat(world.alertStore.all()).isEmpty();
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
        RescreeningObserver observer = new RescreeningObserver() {
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
        return new RescreeningRun(lists, permits, this::page, kyc, signals(), new RiskPolicies(world.policyStore),
                world.alerts::raiseAll, EvaluationWorld.IDS, observer, bounds);
    }

    private RiskSignals signals() {
        OutboxWriter<Connection> outbox = (uow, envelope, payload, mediaType) -> {
            events.add(envelope);
            payloads.add(EventPayload.fields(payload));
        };
        return new RiskSignals(signalStore, outbox, EvaluationWorld.IDS, EvaluationWorld.CLOCK);
    }

    private List<UUID> page(Connection unitOfWork, Optional<UUID> after, int limit) {
        return active.stream().filter(party -> after.isEmpty() || party.compareTo(after.get()) > 0).limit(limit).toList();
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
        Optional<RescreeningPermit> claimed = run.claimNextDue(UOW, LEASE);
        commit();
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        Optional<RescreeningRun.Concluded> done = conclude(claimed.get(), run.ask(claimed.get()));
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

    private static PartyRescreening.Screened screened(boolean hit, Instant decidedAt) {
        return new PartyRescreening.Screened(UUID.randomUUID(), hit, decidedAt);
    }

    /** kyc's port, scripted: an answer per party, or a throw; every request remembered. */
    private static final class Kyc implements PartyRescreening {
        final Map<UUID, Answer> answers = new HashMap<>();
        final List<UUID> failing = new ArrayList<>();
        final List<String> asked = new ArrayList<>();

        @Override
        public Answer ask(UUID partyId, ScreeningList list, String requestKey) {
            asked.add(partyId + "|" + list + "|" + requestKey);
            if (failing.contains(partyId)) {
                throw new IllegalStateException("kyc's store could not be read: a message that never leaves");
            }
            return answers.getOrDefault(partyId, new PartyRescreening.Pending(PartyRescreening.Wait.NOT_SCREENED));
        }
    }

    /** risk_signal in memory: born once per source event. */
    static final class MemorySignals implements RiskSignalStore {
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
