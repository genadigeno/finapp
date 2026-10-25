package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.security.Actor;
import com.finapp.risk.testing.RiskPolicyFixtures;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The override machine's edges, hermetic (`P13-TSK-012`; {@code RISK_LIFECYCLES.md} section 4.2a; ADR-0108 decision 7,
 * C7; {@code INV-RSK-04}, {@code INV-RSK-07}, {@code INV-AUD-04}): an override of a {@code DENY}, {@code HOLD} or
 * {@code REVIEW} proposed with a reason code and text, its effect fixed by the decision and the resolution; never an
 * {@code ALLOW} or a {@code CHALLENGE}; one standing override per assessment; four eyes at or above the pinned threshold
 * and for any {@code DENY}, the proposer's own reasoned approval below it; the proposer refused and the refusal recorded
 * {@code FAILED}; self-dealing refused for the officer's own party and for an officer placed in no party; the sealed
 * assessment never written. The domain's refusals are proven here alone - the in-memory store models no four-eyes
 * {@code CHECK}; the database's are {@code DecisionOverrideDatabaseTest}'s (written, owed - C5).
 */
@DisplayName("the decision override: four eyes by C7, never an ALLOW, never an edit of the decision (P13-TSK-012)")
class DecisionOverrideTest {

    private static final long THRESHOLD = RiskPolicyFixtures.OVERRIDE_THRESHOLD_MINOR; // EUR 1,000.00 (C7)

    private final OverrideWorld world = new OverrideWorld();

    @Test
    @DisplayName("at the threshold a second holder is required: the proposer's approval is SelfApprovalRefused with"
            + " nothing written and recorded FAILED in its own transaction; a second holder approves, issuing the"
            + " release under a permit, audited and announced")
    void atTheThresholdASecondHolderApproves() {
        RiskAssessment held = world.sealed(RiskOperation.WITHDRAWAL, RiskDecision.HOLD, THRESHOLD, THRESHOLD);
        Actor proposer = world.officer();
        DecisionOverride proposed = world.propose(held, OverrideResolution.PROCEED, proposer);
        assertThat(proposed.status()).isEqualTo(OverrideStatus.PROPOSED);
        assertThat(proposed.requiresSecondPerson()).as("C7: at or above EUR 1,000").isTrue();
        assertThat(proposed.effect()).isEqualTo(OverrideEffect.RELEASE_HELD_WITHDRAWAL);
        assertThat(proposed.target()).contains(UUID.fromString(held.commandRef().value()));

        DecisionOverrides.SelfApprovalRefused refused = catchThrowableOfType(
                DecisionOverrides.SelfApprovalRefused.class, () -> world.approve(proposed, proposer));
        assertThat(refused).as("INV-RSK-07: the proposer never approves above the threshold").isNotNull();
        assertThat(world.overrides.all()).singleElement()
                .satisfies(row -> assertThat(row.status()).isEqualTo(OverrideStatus.PROPOSED));
        world.machine.recordRefusal(EvaluationWorld.UOW, refused, proposer, OverrideWorld.correlation());
        assertThat(world.audited("risk.SelfApprovalRefused")).singleElement().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
            assertThat(record.targetType()).isEqualTo("decision_override");
            assertThat(record.targetId()).isEqualTo(proposed.id().value().toString());
            assertThat(record.changeSummary().orElseThrow()).contains("act=override:approve")
                    .doesNotContain(held.subjects().party().toString());
        });

        DecisionOverrides.Decided approved = world.approve(proposed, world.officer());
        DecisionOverride row = approved.override();
        assertThat(approved.replayed()).isFalse();
        assertThat(row.status()).isEqualTo(OverrideStatus.APPROVED);
        assertThat(row.action()).contains(OverrideEffect.RELEASE_HELD_WITHDRAWAL);
        assertThat(row.actionDueAt()).as("issued into the action machine, due at once (P13-TSK-017)")
                .contains(world.now());
        assertThat(row.actionAttempts()).as("nothing claimed yet").isZero();
        assertThat(world.issuedFor(row).status()).isEqualTo(ActionStatus.APPROVED);
        assertThat(world.issuedFor(row).id().value()).as("the action is the override's own id - its key kept")
                .isEqualTo(row.id().value());
        assertThat(row.actionKey()).isEqualTo("risk.action:" + row.id().value());
        assertThat(row.actionOutcome()).as("issued, not attempted - the dispatch is after the commit").isEmpty();
        assertThat(world.payments.calls).as("nothing is dispatched inside the approval").isEmpty();
        assertThat(world.audited("risk.OverrideApproved")).singleElement().satisfies(record -> {
            assertThat(record.reason()).contains("reviewed the evidence");
            assertThat(record.changeSummary().orElseThrow()).contains("effect=RELEASE_HELD_WITHDRAWAL",
                    "secondPerson=REQUIRED", "byProposer=false");
        });
        assertThat(world.events).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo("risk.RiskActionRequested");
            assertThat(event.aggregateType()).isEqualTo("risk_action");
        });
        assertThat(world.payloads.get(0)).containsEntry("kind", "RELEASE_HELD_WITHDRAWAL")
                .containsEntry("targetKind", "WITHDRAWAL").containsEntry("source", "OVERRIDE")
                .containsEntry("targetId", held.commandRef().value()).doesNotContainKey("outcome");
    }

    @Test
    @DisplayName("any DENY needs a second holder however small - the proposer refused; approved, it issues a"
            + " RESUBMISSION: no permit, no attempt, no event, nothing dispatched")
    void aDenyAlwaysNeedsASecondHolder() {
        RiskAssessment denied = world.failSafeDeny(10_00);
        assertThat(denied.decision()).isEqualTo(RiskDecision.DENY);
        Actor proposer = world.officer();
        DecisionOverride proposed = world.propose(denied, OverrideResolution.PROCEED, proposer);
        assertThat(proposed.requiresSecondPerson()).as("C7: any DENY").isTrue();
        assertThat(proposed.effect()).isEqualTo(OverrideEffect.RESUBMISSION);
        assertThat(proposed.target()).isEmpty();
        assertThatThrownBy(() -> world.approve(proposed, proposer))
                .isInstanceOf(DecisionOverrides.SelfApprovalRefused.class);

        DecisionOverride approved = world.approve(proposed, world.officer()).override();
        assertThat(approved.action()).contains(OverrideEffect.RESUBMISSION);
        assertThat(approved.actionDueAt()).isEmpty();
        assertThat(approved.actionAttempts()).isZero();
        assertThat(approved.awaitsOutcome()).isFalse();
        assertThat(world.actionStore.all()).as("a resubmission issues no action").isEmpty();
        assertThat(world.events).as("a resubmission requests no action").isEmpty();
    }

    @Test
    @DisplayName("below the threshold the proposer's own reasoned approval completes it - audited byProposer=true,"
            + " secondPerson=NOT_REQUIRED")
    void belowTheThresholdOneHolderWithAReason() {
        RiskAssessment held = world.sealed(RiskOperation.WITHDRAWAL, RiskDecision.HOLD, THRESHOLD - 1, THRESHOLD);
        Actor proposer = world.officer();
        DecisionOverride proposed = world.propose(held, OverrideResolution.PROCEED, proposer);
        assertThat(proposed.requiresSecondPerson()).isFalse();

        DecisionOverride approved = world.approve(proposed, proposer).override();
        assertThat(approved.status()).isEqualTo(OverrideStatus.APPROVED);
        assertThat(approved.decidedByProposer()).isTrue();
        assertThat(world.audited("risk.OverrideApproved")).singleElement().satisfies(record -> assertThat(
                record.changeSummary().orElseThrow()).contains("secondPerson=NOT_REQUIRED", "byProposer=true"));
        assertThat(world.audited("risk.SelfApprovalRefused")).isEmpty();
    }

    @Test
    @DisplayName("C7 fails closed: no pinned version, no threshold - a second holder; at the threshold yes, one cent"
            + " below no")
    void theThresholdFailsClosed() {
        RiskAssessment held = world.sealed(RiskOperation.WITHDRAWAL, RiskDecision.HOLD, 500_00, THRESHOLD);
        assertThat(DecisionOverrides.requiresSecondPerson(held, Optional.empty())).isTrue();
        assertThat(DecisionOverrides.requiresSecondPerson(held,
                Optional.of(OverrideWorld.policy(RiskOperation.WITHDRAWAL, RiskDecision.HOLD, 500_00)))).isTrue();
        assertThat(DecisionOverrides.requiresSecondPerson(held,
                Optional.of(OverrideWorld.policy(RiskOperation.WITHDRAWAL, RiskDecision.HOLD, 500_01)))).isFalse();
    }

    @Test
    @DisplayName("an ALLOW and a CHALLENGE are never overridden, and a refusal has nothing held to refuse - each"
            + " ActionNotProposable, nothing written")
    void anAllowIsNeverOverridden() {
        Actor proposer = world.officer();
        for (RiskAssessment never : List.of(
                world.sealed(RiskOperation.TRANSFER, RiskDecision.ALLOW, 100_00, THRESHOLD),
                world.sealed(RiskOperation.TRANSFER, RiskDecision.CHALLENGE, 100_00, THRESHOLD))) {
            assertThatThrownBy(() -> world.propose(never, OverrideResolution.PROCEED, proposer)).as(never.decision()
                    .name()).isInstanceOf(DecisionOverrides.ActionNotProposable.class)
                    .hasMessageContaining("never overridden");
        }
        RiskAssessment review = world.sealed(RiskOperation.TRANSFER, RiskDecision.REVIEW, 100_00, THRESHOLD);
        assertThatThrownBy(() -> world.propose(review, OverrideResolution.REFUSE, proposer))
                .isInstanceOf(DecisionOverrides.ActionNotProposable.class).hasMessageContaining("nothing held");
        assertThat(world.overrides.all()).isEmpty();
        assertThat(world.audit).isEmpty();
    }

    @Test
    @DisplayName("the effect is the decision's and the resolution's: a held withdrawal released or denied, a held card"
            + " payment captured or voided - each targeting the judged command - and a REVIEW's resubmission")
    void theEffectMatrix() {
        Actor proposer = world.officer();
        RiskAssessment withdrawal = world.sealed(RiskOperation.WITHDRAWAL, RiskDecision.HOLD, 100_00, THRESHOLD);
        assertThat(world.propose(withdrawal, OverrideResolution.REFUSE, proposer).effect())
                .isEqualTo(OverrideEffect.DENY_HELD_WITHDRAWAL);
        RiskAssessment card = world.sealed(RiskOperation.CARD_PAYMENT, RiskDecision.HOLD, 100_00, THRESHOLD);
        DecisionOverride capture = world.propose(card, OverrideResolution.PROCEED, proposer);
        assertThat(capture.effect()).isEqualTo(OverrideEffect.CAPTURE_HELD_PAYMENT);
        assertThat(capture.target()).contains(UUID.fromString(card.commandRef().value()));
        RiskAssessment voided = world.sealed(RiskOperation.CARD_PAYMENT, RiskDecision.HOLD, 100_00, THRESHOLD);
        assertThat(world.propose(voided, OverrideResolution.REFUSE, proposer).effect())
                .isEqualTo(OverrideEffect.VOID_HELD_PAYMENT);
        RiskAssessment review = world.sealed(RiskOperation.CROSS_BORDER, RiskDecision.REVIEW, 100_00, THRESHOLD);
        assertThat(world.propose(review, OverrideResolution.PROCEED, proposer).effect())
                .isEqualTo(OverrideEffect.RESUBMISSION);
        assertThat(world.audited("risk.OverrideProposed")).hasSize(4).allSatisfy(record -> {
            assertThat(record.reason()).contains("the customer confirmed the movement by phone");
            assertThat(record.changeSummary().orElseThrow()).contains("reasonCode=FALSE_POSITIVE");
        });
    }

    @Test
    @DisplayName("one standing override per assessment: a second proposal is ActionNotProposable; after a rejection a"
            + " new one stands; after an approval none does")
    void oneStandingOverridePerAssessment() {
        RiskAssessment held = world.sealed(RiskOperation.WITHDRAWAL, RiskDecision.HOLD, 100_00, THRESHOLD);
        DecisionOverride first = world.propose(held, OverrideResolution.PROCEED, world.officer());
        assertThatThrownBy(() -> world.propose(held, OverrideResolution.REFUSE, world.officer()))
                .isInstanceOf(DecisionOverrides.ActionNotProposable.class).hasMessageContaining("standing override");
        world.machine.reject(EvaluationWorld.UOW, held.id(), first.id(), "not enough evidence", world.officer(),
                OverrideWorld.correlation());
        DecisionOverride second = world.propose(held, OverrideResolution.PROCEED, world.officer());
        world.approve(second, world.officer());
        assertThatThrownBy(() -> world.propose(held, OverrideResolution.REFUSE, world.officer()))
                .isInstanceOf(DecisionOverrides.ActionNotProposable.class);
        assertThat(world.overrides.all()).extracting(DecisionOverride::status)
                .containsExactly(OverrideStatus.REJECTED, OverrideStatus.APPROVED);
    }

    @Test
    @DisplayName("a decided override is stale to anyone else - an approval after a rejection, a second approver - and"
            + " the same person's retry converges; a rejection issues nothing and records byProposer")
    void aDecidedOverrideIsStale() {
        RiskAssessment held = world.sealed(RiskOperation.WITHDRAWAL, RiskDecision.HOLD, 100_00, THRESHOLD);
        Actor proposer = world.officer();
        DecisionOverride withdrawn = world.propose(held, OverrideResolution.PROCEED, proposer);
        DecisionOverride rejected = world.machine.reject(EvaluationWorld.UOW, held.id(), withdrawn.id(), "withdrawn",
                proposer, OverrideWorld.correlation()).override();
        assertThat(rejected.status()).isEqualTo(OverrideStatus.REJECTED);
        assertThat(rejected.action()).isEmpty();
        assertThat(world.audited("risk.OverrideRejected")).singleElement().satisfies(record -> assertThat(
                record.changeSummary().orElseThrow()).contains("byProposer=true"));
        assertThatThrownBy(() -> world.approve(withdrawn, world.officer()))
                .isInstanceOf(DecisionOverrides.OverrideStale.class).hasMessageContaining("REJECTED");
        assertThat(world.machine.reject(EvaluationWorld.UOW, held.id(), withdrawn.id(), "withdrawn", proposer,
                OverrideWorld.correlation()).replayed()).isTrue();

        DecisionOverride proposed = world.propose(held, OverrideResolution.PROCEED, world.officer());
        Actor approver = world.officer();
        world.approve(proposed, approver);
        assertThatThrownBy(() -> world.approve(proposed, world.officer()))
                .isInstanceOf(DecisionOverrides.OverrideStale.class).hasMessageContaining("APPROVED");
        DecisionOverrides.Decided again = world.approve(proposed, approver);
        assertThat(again.replayed()).isTrue();
        assertThat(world.audited("risk.OverrideApproved")).as("a converged retry records nothing new").hasSize(1);
    }

    @Test
    @DisplayName("self-dealing fails closed on every act and read: the officer's own party OWN_PARTY, an officer"
            + " placed in no party UNPLACEABLE - nothing read or written, the refusal recorded FAILED naming no party")
    void selfDealingFailsClosed() {
        UUID party = UUID.randomUUID();
        RiskAssessment own = world.sealed(RiskOperation.WITHDRAWAL, RiskDecision.HOLD, 100_00, THRESHOLD, party);
        Actor insider = world.officerOf(party);
        RiskAssessments.SelfDealingRefused refused = catchThrowableOfType(RiskAssessments.SelfDealingRefused.class,
                () -> world.propose(own, OverrideResolution.PROCEED, insider));
        assertThat(refused.refusal()).isEqualTo(RiskAssessments.SelfDealingRefused.Cause.OWN_PARTY);
        assertThat(refused.act()).isEqualTo("override-propose");
        DecisionOverride proposed = world.propose(own, OverrideResolution.PROCEED, world.officer());
        assertThat(catchThrowableOfType(RiskAssessments.SelfDealingRefused.class,
                () -> world.approve(proposed, insider)).act()).isEqualTo("override-approve");
        assertThat(catchThrowableOfType(RiskAssessments.SelfDealingRefused.class, () -> world.machine.read(
                EvaluationWorld.UOW, own.id(), insider, OverrideWorld.correlation())).act()).isEqualTo("override-read");
        Actor nobody = new Actor("nobody", com.finapp.platform.security.ActorType.EMPLOYEE);
        RiskAssessments.SelfDealingRefused placeless = catchThrowableOfType(RiskAssessments.SelfDealingRefused.class,
                () -> world.machine.reject(EvaluationWorld.UOW, own.id(), proposed.id(), "no", nobody,
                        OverrideWorld.correlation()));
        assertThat(placeless.refusal()).isEqualTo(RiskAssessments.SelfDealingRefused.Cause.UNPLACEABLE);
        assertThat(world.overrides.all()).singleElement()
                .satisfies(row -> assertThat(row.status()).isEqualTo(OverrideStatus.PROPOSED));

        world.machine.recordRefusal(EvaluationWorld.UOW, own.id(), refused, insider, OverrideWorld.correlation());
        assertThat(world.audited("risk.SelfDealingRefused")).singleElement().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
            assertThat(record.targetId()).isEqualTo(own.id().value().toString());
            assertThat(record.changeSummary().orElseThrow()).contains("act=override-propose, cause=OWN_PARTY")
                    .doesNotContain(party.toString());
        });
    }

    @Test
    @DisplayName("a reason code outside the vocabulary, a blank, unbounded or card-shaped text, and an unreasoned"
            + " decision are each ReasonRequired, nothing written")
    void theReasonsAreRequired() {
        RiskAssessment held = world.sealed(RiskOperation.WITHDRAWAL, RiskDecision.HOLD, 100_00, THRESHOLD);
        Actor officer = world.officer();
        for (String[] defect : List.of(new String[] {"BECAUSE", "a text"}, new String[] {null, "a text"},
                new String[] {"FALSE_POSITIVE", " "}, new String[] {"FALSE_POSITIVE", "x".repeat(1001)},
                new String[] {"FALSE_POSITIVE", "card 4111 1111 1111 1111 was used"})) {
            assertThatThrownBy(() -> world.machine.propose(EvaluationWorld.UOW, held.id(), OverrideResolution.PROCEED,
                    defect[0], defect[1], officer, OverrideWorld.correlation())).as(String.valueOf(defect[0]))
                    .isInstanceOf(RiskReasons.ReasonRequired.class);
        }
        DecisionOverride proposed = world.propose(held, OverrideResolution.PROCEED, officer);
        assertThatThrownBy(() -> world.machine.approve(EvaluationWorld.UOW, held.id(), proposed.id(), "",
                world.officer(), OverrideWorld.correlation()))
                .isInstanceOf(RiskReasons.ReasonRequired.class);
        assertThat(world.overrides.all()).singleElement()
                .satisfies(row -> assertThat(row.status()).isEqualTo(OverrideStatus.PROPOSED));
    }

    @Test
    @DisplayName("INV-RSK-04: proposing, approving, rejecting and reading never write the sealed assessment - the store"
            + " is only read, the assessment equal to its seal and the seal holding")
    void theSealedAssessmentIsNeverWritten() {
        RiskAssessment held = world.sealed(RiskOperation.WITHDRAWAL, RiskDecision.HOLD, 2_000_00, THRESHOLD);
        RiskAssessment rejectedOne = world.sealed(RiskOperation.TRANSFER, RiskDecision.REVIEW, 100_00, THRESHOLD);
        world.evaluation.assessmentStore.log.clear();

        DecisionOverride released = world.propose(held, OverrideResolution.PROCEED, world.officer());
        world.approve(released, world.officer());
        DecisionOverride refused = world.propose(rejectedOne, OverrideResolution.PROCEED, world.officer());
        world.machine.reject(EvaluationWorld.UOW, rejectedOne.id(), refused.id(), "no", world.officer(),
                OverrideWorld.correlation());
        world.machine.read(EvaluationWorld.UOW, held.id(), world.officer(), OverrideWorld.correlation());

        assertThat(world.evaluation.assessmentStore.log).as("read, never written").containsOnly("read");
        assertThat(world.evaluation.assessmentStore.read(EvaluationWorld.UOW, held.id())).contains(held);
        assertThat(world.evaluation.assessmentStore.all()).allSatisfy(stored -> assertThat(stored.sealHolds()).isTrue());
        assertThat(world.evaluation.assessmentStore.read(EvaluationWorld.UOW, held.id()).orElseThrow().decision())
                .as("the decision stands, overridden").isEqualTo(RiskDecision.HOLD);
    }

    @Test
    @DisplayName("the reads serve the assessment and its overrides, recorded risk.AssessmentRead door=override; an"
            + " override of another assessment is OverrideNotFound")
    void theReadsAreServedAndRecorded() {
        RiskAssessment held = world.sealed(RiskOperation.WITHDRAWAL, RiskDecision.HOLD, 100_00, THRESHOLD);
        RiskAssessment other = world.sealed(RiskOperation.WITHDRAWAL, RiskDecision.HOLD, 100_00, THRESHOLD);
        DecisionOverride proposed = world.propose(held, OverrideResolution.PROCEED, world.officer());
        Actor reader = world.officer();

        DecisionOverrides.Served served = world.machine.read(EvaluationWorld.UOW, held.id(), reader,
                OverrideWorld.correlation());
        assertThat(served.assessment()).isEqualTo(held);
        assertThat(served.overrides()).extracting(DecisionOverride::id).containsExactly(proposed.id());
        assertThat(world.machine.readOne(EvaluationWorld.UOW, held.id(), proposed.id(), reader,
                OverrideWorld.correlation()).overrides()).hasSize(1);
        assertThatThrownBy(() -> world.machine.readOne(EvaluationWorld.UOW, other.id(), proposed.id(), reader,
                OverrideWorld.correlation())).isInstanceOf(DecisionOverrides.OverrideNotFound.class);
        assertThatThrownBy(() -> world.machine.read(EvaluationWorld.UOW, RiskAssessmentId.next(EvaluationWorld.IDS),
                reader, OverrideWorld.correlation())).isInstanceOf(DecisionOverrides.OverrideNotFound.class);
        List<AuditRecord> reads = world.audited("risk.AssessmentRead");
        assertThat(reads).hasSize(2).allSatisfy(record -> {
            assertThat(record.targetType()).isEqualTo("risk_assessment");
            assertThat(record.changeSummary().orElseThrow()).contains("door=override", "decision=HOLD");
        });
    }
}
