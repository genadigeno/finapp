package com.finapp.risk;

import static com.finapp.risk.EvaluationWorld.UOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every assessment of a day replayed (`P13-TSK-023`; ADR-0109 decision 4; {@code INV-RSK-01}; {@code DESIGN-023} D7):
 * over the evaluation's own in-memory world, so the assessments are real seals. A day replays {@code IDENTICAL} page by
 * page; a policy activated mid-day changes nothing - each assessment replays under its own pinned version; a corrupted
 * seal is {@code DIVERGED} and the first divergent is named - never one of the reader's own party, which is counted and
 * withheld; a reader the platform cannot place is refused (fail closed); a date the platform has not had is unknown;
 * nothing is written but {@code risk.Replayed}. The database's half is
 * {@code RiskProofsDatabaseTest#aDailyReplayPinsEachAssessmentsOwnVersion} (written and owed, C5).
 */
@DisplayName("the daily replay: every assessment of the day under its own pinned pair, IDENTICAL; the first divergent"
        + " named (P13-TSK-023)")
class DailyReplayTest {

    private static final RiskDay DAY = RiskDay.of(LocalDate.of(2026, 10, 23));
    private static final RiskPolicy V1 = RiskPolicyFixtures.v1(RiskOperation.TRANSFER);
    /** A successor that judges otherwise: the first three rules only - every hit list differs from V1's. */
    private static final RiskPolicy V2 = new RiskPolicy(V1.operation(), V1.engine(), V1.currency(),
            V1.rules().subList(0, 3), V1.scoreBands(), V1.ratingBands(), V1.failSafeBands(), V1.challengeAssurance(),
            V1.overrideThreshold(), V1.holdValidity());

    private final EvaluationWorld world = new EvaluationWorld();
    private final FakeRiskProofReads reads = new FakeRiskProofReads();
    private final Map<String, UUID> partyOfActor = new HashMap<>();
    private final List<AuditRecord> audit = new ArrayList<>();
    private final DailyReplay replay = new DailyReplay(reads, new RiskPolicies(world.policyStore),
            (unitOfWork, actor) -> Optional.ofNullable(partyOfActor.get(actor.id())), (unitOfWork, record) ->
                    audit.add(record), EvaluationWorld.IDS, EvaluationWorld.CLOCK);
    private final Actor analyst = new Actor("analyst-1", ActorType.CUSTOMER);

    @Test
    @DisplayName("a day of judged transfers and fail-safes replays IDENTICAL a bounded page at a time - each call at most"
            + " its limit, the cursor to continue with answered while more remain, every assessment counted once")
    void aDayReplaysIdenticalPageByPage() {
        partyOfActor.put(analyst.id(), UUID.randomUUID());
        seal(3); // no policy yet: three recorded fail-safes
        world.activate(V1);
        seal(DailyReplay.DEFAULT_LIMIT + 7);

        DailyReplay.Result first = replay.replay(StandInConnections.snapshot(), DAY, analyst);
        assertThat(first.known()).isTrue();
        assertThat(first.replayed()).as("the default page").isEqualTo(DailyReplay.DEFAULT_LIMIT);
        assertThat(first.identical()).isEqualTo(first.replayed());
        assertThat(first.next()).as("more remain").isPresent();
        DailyReplay.Result rest = replay.replay(StandInConnections.snapshot(), DAY, analyst, first.next(),
                DailyReplay.MAX_LIMIT);
        assertThat(rest.replayed()).isEqualTo(10);
        assertThat(rest.after()).isEqualTo(first.next());
        assertThat(rest.next()).as("the day is done").isEmpty();
        assertThat(rest.verdict()).isEqualTo(AssessmentReplay.Outcome.IDENTICAL);
        assertThat(first.first()).isEmpty();
        assertThat(rest.operations()).singleElement().satisfies(operation ->
                assertThat(operation.operation()).isEqualTo(RiskOperation.TRANSFER));

        DailyReplay.Result capped = replay.replay(StandInConnections.snapshot(), DAY, analyst, Optional.empty(),
                Integer.MAX_VALUE);
        assertThat(capped.replayed()).as("a larger limit is answered with the bound").isEqualTo(
                DailyReplay.DEFAULT_LIMIT + 10);
        assertThatThrownBy(() -> replay.replay(StandInConnections.snapshot(), DAY, analyst, Optional.empty(), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> replay.replay(StandInConnections.snapshot(), DAY, analyst,
                Optional.of(RiskAssessmentId.of(FakeRiskProofReads.mintedAt(java.time.Instant.parse(
                        "2026-10-24T09:00:00Z")))), 10)).as("a cursor of another day").isInstanceOf(
                IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a day not yet closed says so: today, and until the settle horizon past its end, a page answers"
            + " closed=false - the pages may miss an assessment committed behind a cursor - and closed=true after;"
            + " recorded in risk.Replayed's summary")
    void aDayNotYetClosedSaysSo() {
        partyOfActor.put(analyst.id(), UUID.randomUUID());
        world.activate(V1);
        seal(2);
        DailyReplay.Result today = replay.replay(StandInConnections.snapshot(), DAY, analyst);
        assertThat(today.known()).isTrue();
        assertThat(today.closed()).as("today can still gain an assessment").isFalse();
        replay.record(UOW, today, "the noon check", analyst, CorrelationId.of("flow-2"));
        assertThat(audit.get(0).changeSummary().orElseThrow()).startsWith("date=2026-10-23, known=true, closed=false,");

        reads.now = DAY.end().plus(DailyReplay.SETTLE).minusSeconds(1);
        assertThat(replay.replay(StandInConnections.snapshot(), DAY, analyst).closed())
                .as("within the settle horizon past midnight").isFalse();
        reads.now = DAY.end().plus(DailyReplay.SETTLE);
        DailyReplay.Result closed = replay.replay(StandInConnections.snapshot(), DAY, analyst);
        assertThat(closed.closed()).isTrue();
        assertThat(closed.replayed()).isEqualTo(today.replayed());
        assertThatThrownBy(() -> new DailyReplay(reads, new RiskPolicies(world.policyStore), (unitOfWork, actor) ->
                Optional.empty(), (unitOfWork, record) -> { }, EvaluationWorld.IDS, EvaluationWorld.CLOCK,
                java.time.Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a policy activated mid-day changes nothing of the morning: each assessment replays under the version it"
            + " pinned, never the one active now")
    void eachAssessmentReplaysUnderItsOwnVersion() {
        partyOfActor.put(analyst.id(), UUID.randomUUID());
        world.activate(V1);
        seal(4);
        world.activate(V2);
        seal(4);
        assertThat(reads.assessments.stream().map(a -> a.pin().orElseThrow().version()).distinct()).hasSize(2);

        DailyReplay.Result result = replay.replay(StandInConnections.snapshot(), DAY, analyst);
        assertThat(result.replayed()).isEqualTo(8);
        assertThat(result.verdict()).as("the morning under V1, the afternoon under V2")
                .isEqualTo(AssessmentReplay.Outcome.IDENTICAL);
    }

    @Test
    @DisplayName("a corrupted seal replays DIVERGED and the earliest divergent is named with what diverged - never a"
            + " figure; one of the reader's own party is counted and withheld, the next named instead")
    void theFirstDivergentIsNamedNeverTheReadersOwn() {
        UUID own = UUID.randomUUID();
        partyOfActor.put(analyst.id(), own);
        world.activate(V1);
        seal(2);
        RiskAssessment ownCorrupted = corrupt(sealOne(own));
        RiskAssessment corrupted = corrupt(sealOne(UUID.randomUUID()));
        seal(2);

        DailyReplay.Result result = replay.replay(StandInConnections.snapshot(), DAY, analyst);
        assertThat(result.diverged()).isEqualTo(2);
        assertThat(result.withheld()).isEqualTo(1);
        assertThat(result.first()).hasValueSatisfying(first -> {
            assertThat(first.assessment()).isEqualTo(corrupted.id()).isNotEqualTo(ownCorrupted.id());
            assertThat(first.diverged()).contains(AssessmentReplay.Divergence.INPUTS);
        });
        assertThat(result.verdict()).isEqualTo(AssessmentReplay.Outcome.DIVERGED);

        replay.record(UOW, result, "the morning's audit", analyst, CorrelationId.of("flow-1"));
        AuditRecord recorded = audit.get(0);
        assertThat(recorded.operation()).isEqualTo(RiskAuditAction.REPLAYED);
        assertThat(recorded.targetType()).isEqualTo(DailyReplay.TARGET_TYPE);
        assertThat(recorded.targetId()).isEqualTo("2026-10-23");
        assertThat(recorded.reason()).contains("the morning's audit");
        assertThat(recorded.changeSummary().orElseThrow())
                .contains("replayed=6, identical=4, diverged=2, withheld=1, first=" + corrupted.id().value())
                .doesNotContain(ownCorrupted.id().value().toString()).doesNotContain(own.toString());
    }

    @Test
    @DisplayName("fail closed: a reader the platform cannot place is refused before anything is read, recorded FAILED"
            + " against the day; the platform is no reader; a date the platform has not had is unknown - nothing"
            + " replayed")
    void refusalsAndUnknownDays() {
        assertThatThrownBy(() -> replay.replay(StandInConnections.snapshot(), DAY, analyst))
                .isInstanceOf(RiskAssessments.SelfDealingRefused.class)
                .satisfies(refused -> replay.recordRefusal(UOW, DAY, (RiskAssessments.SelfDealingRefused) refused,
                        analyst, CorrelationId.of("flow-2")));
        assertThat(audit).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(RiskAuditAction.SELF_DEALING_REFUSED);
            assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
            assertThat(record.changeSummary().orElseThrow()).contains("act=assessment-day-replay, cause=UNPLACEABLE");
        });
        assertThat(reads.calls).isEmpty();
        assertThatThrownBy(() -> replay.replay(StandInConnections.snapshot(), DAY, Actor.SYSTEM))
                .isInstanceOf(IllegalArgumentException.class);

        partyOfActor.put(analyst.id(), UUID.randomUUID());
        DailyReplay.Result tomorrow = replay.replay(StandInConnections.snapshot(),
                RiskDay.of(LocalDate.of(2026, 10, 24)), analyst);
        assertThat(tomorrow.known()).isFalse();
        assertThat(tomorrow.replayed()).isZero();
        assertThat(replay.replay(StandInConnections.snapshot(), RiskDay.of(LocalDate.of(2025, 12, 31)), analyst)
                .known()).isFalse();
        assertThatThrownBy(() -> replay.replay(StandInConnections.declaring(false, false,
                java.sql.Connection.TRANSACTION_REPEATABLE_READ), DAY, analyst))
                .isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------------

    private void seal(int count) {
        for (int i = 0; i < count; i++) {
            sealOne(UUID.randomUUID());
        }
    }

    private RiskAssessment sealOne(UUID party) {
        RiskEvaluation.Assessed assessed = world.evaluation.evaluate(UOW, EvaluationWorld.request(
                RiskOperation.TRANSFER, Money.ofMinorUnits(120_00, RiskPolicyFixtures.EUR), party)
                .sourceAccount(EvaluationWorld.IDS.next()).destinationAccount(EvaluationWorld.IDS.next())
                .actor(new Actor("customer-1", ActorType.CUSTOMER)).build());
        RiskAssessment sealed = RiskEvaluationTest.sealed(world, assessed);
        reads.assessments.add(sealed);
        return sealed;
    }

    /** {@code sealed} with its inputs text re-sealed unparseable - the replay's {@code INPUTS} divergence. */
    private RiskAssessment corrupt(RiskAssessment sealed) {
        String text = "not the canonical form";
        RiskAssessment corrupted = new RiskAssessment(sealed.id(), sealed.operation(), sealed.commandRef(),
                sealed.subjects(), sealed.amount(), sealed.pin(), sealed.engine(), sealed.score(), sealed.decision(),
                sealed.unavailable(), sealed.challengeSatisfied(), sealed.velocity(), sealed.cutAt(), text,
                CanonicalEvaluation.sha256(text), sealed.verdictSha256(), sealed.hits(), sealed.reasons(),
                sealed.latencyMicros(), sealed.correlationId(), sealed.causationId(), sealed.assessedAt());
        reads.assessments.remove(sealed);
        reads.assessments.add(corrupted);
        return corrupted;
    }
}
