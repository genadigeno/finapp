package com.finapp.app.risk;

import static com.finapp.app.risk.RiskEvaluationWorld.IDS;
import static com.finapp.app.risk.RiskEvaluationWorld.UNTOUCHABLE;
import static com.finapp.app.risk.RiskEvaluationWorld.eur;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.AssuranceLevel;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.payments.WithdrawalId;
import com.finapp.payments.WithdrawalRiskDecision;
import com.finapp.payments.WithdrawalVerdict;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.risk.ReasonCode;
import com.finapp.risk.RiskAssessment;
import com.finapp.risk.RiskDecision;
import com.finapp.risk.RiskOperation;
import com.finapp.risk.RiskPolicy;
import com.finapp.risk.RiskSignal;
import com.finapp.risk.RiskSignalId;
import com.finapp.risk.SignalAttributes;
import com.finapp.risk.SignalKind;
import com.finapp.risk.SignalSubject;
import com.finapp.risk.SubjectKind;
import com.finapp.risk.UnavailableCause;
import com.finapp.risk.VelocityDimension;
import com.finapp.risk.VelocityOutcome;
import com.finapp.risk.WindowKind;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.money.Money;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Payments' withdrawal seam answered by risk, over a real {@code RiskEvaluation} with in-memory stores and the C8
 * {@code WITHDRAWAL} fixture (`P13-TSK-009`; {@code PHASE_13_PLAN.md} sections 7.3, 12.1, 12.3; ADR-0108;
 * {@code INV-RSK-01}, {@code INV-RSK-02}, {@code INV-RSK-03}, {@code INV-RSK-04}, {@code INV-CON-03}): every decision
 * mapped to its verdict - ALLOW and a satisfied CHALLENGE permit and count; HOLD holds for the pinned version's 72 h and
 * counts nothing; DENY, REVIEW and an unsatisfied CHALLENGE refuse and count nothing - one assessment per withdrawal
 * (a second call answered from the seal), the fail-safe (no policy permits, recorded; a timeout is C1's DENY), the
 * request carrying the withdrawal's own facts and no device or destination, and nothing of payments' written on the
 * caller's connection. The real dispatch transaction, its locks and the concurrency are the owed
 * {@code WithdrawalRiskSeamDatabaseTest}'s (C5).
 */
@DisplayName("the withdrawal seam answered by risk: the mapping, one assessment, the fail-safe (P13-TSK-009)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class RiskWithdrawalRiskDecisionTest {

    private final RiskEvaluationWorld world = new RiskEvaluationWorld();
    private final List<LedgerAccountId> ownersAsked = new ArrayList<>();
    /** The wallets' customer accounts, by their ledger account - as the dispatch's own lock would read them. */
    private final Map<LedgerAccountId, UUID> owners = new java.util.HashMap<>();
    private final RiskWithdrawalRiskDecision seam = new RiskWithdrawalRiskDecision(world.evaluation,
            (unitOfWork, wallet) -> {
                assertThat(unitOfWork).isSameAs(UNTOUCHABLE);
                ownersAsked.add(wallet);
                return owners.get(wallet);
            });

    /** One customer's wallet: the party, the identity, the customer account and its ledger account. */
    private record Holder(UUID party, UUID identity, UUID account, LedgerAccountId wallet) {}

    private Holder holder() {
        Holder holder = new Holder(IDS.next(), IDS.next(), IDS.next(), LedgerAccountId.of(IDS.next()));
        owners.put(holder.wallet(), holder.account());
        return holder;
    }

    private WithdrawalRiskDecision.Judged withdrawal(Holder holder, Money amount) {
        return new WithdrawalRiskDecision.Judged(WithdrawalId.next(IDS), holder.party(), IDS.next(), holder.wallet(),
                IDS.next(), amount);
    }

    private <T> T as(Holder holder, Supplier<T> call) {
        try (SecurityContext.Scope actor = SecurityContext.enter(
                        new Actor(holder.identity().toString(), ActorType.CUSTOMER));
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(IDS)))) {
            return call.get();
        }
    }

    private WithdrawalVerdict judged(Holder holder, Money amount) {
        return as(holder, () -> seam.check(UNTOUCHABLE, withdrawal(holder, amount)));
    }

    private RiskAssessment last() {
        return world.assessmentStore.all().get(world.assessmentStore.all().size() - 1);
    }

    // ------------------------------------------------------------------ the mapping

    @Test
    @DisplayName("every decision maps to its verdict: ALLOW permits and counts; HOLD at EUR 2,500 holds for the"
            + " pinned 72 h and counts nothing; a screening DENY and the eleventh's VELOCITY_COUNT REVIEW refuse and"
            + " count nothing")
    void everyDecisionMapsToItsVerdict() {
        world.activate(RiskOperation.WITHDRAWAL);

        Holder ordinary = holder();
        assertThat(judged(ordinary, eur(10_00))).isEqualTo(WithdrawalVerdict.permit());
        assertThat(last().decision()).isEqualTo(RiskDecision.ALLOW);
        assertThat(world.count(VelocityDimension.PARTY, ordinary.party(), WindowKind.D1)).isEqualTo(1);
        assertThat(world.count(VelocityDimension.SOURCE_ACCOUNT, ordinary.account(), WindowKind.D1)).isEqualTo(1);

        Holder large = holder();
        WithdrawalVerdict held = judged(large, eur(2_500_00));
        assertThat(held.kind()).isEqualTo(WithdrawalVerdict.Kind.HOLD);
        assertThat(held.holdValidity()).as("the pinned version's hold validity, never a payments constant")
                .contains(RiskPolicy.DEFAULT_HOLD_VALIDITY).contains(Duration.ofHours(72));
        assertThat(last().decision()).isEqualTo(RiskDecision.HOLD);
        assertThat(last().velocity()).isEqualTo(VelocityOutcome.NOT_PERMITTED);
        assertThat(world.count(VelocityDimension.PARTY, large.party(), WindowKind.D1))
                .as("a HOLD permits nothing until a person decides: counted nothing").isZero();
        assertThat(world.cases).as("HOLD reaches the case opener (NONE in production until -016)").hasSize(1);

        world.screeningHit = Optional.of(true);
        Holder screened = holder();
        assertThat(judged(screened, eur(10_00))).isEqualTo(WithdrawalVerdict.refuse());
        assertThat(last().reasons()).containsExactly(ReasonCode.SCREENING_HIT);
        assertThat(world.count(VelocityDimension.PARTY, screened.party(), WindowKind.D1)).isZero();
        world.screeningHit = Optional.empty();

        Holder busy = holder();
        for (int i = 0; i < 10; i++) {
            assertThat(judged(busy, eur(1_00))).isEqualTo(WithdrawalVerdict.permit());
        }
        assertThat(judged(busy, eur(1_00))).as("the eleventh of the day from one wallet: REVIEW")
                .isEqualTo(WithdrawalVerdict.refuse());
        assertThat(last().decision()).isEqualTo(RiskDecision.REVIEW);
        assertThat(world.count(VelocityDimension.SOURCE_ACCOUNT, busy.account(), WindowKind.D1)).isEqualTo(10);
    }

    @Test
    @DisplayName("a CHALLENGE (a recovery within 72 h: the score band) refuses without a satisfying session - counting"
            + " nothing, CHALLENGE_REQUIRED sealed - and permits, counted, under a MULTI_FACTOR one")
    void aChallengeIsSatisfiedOnlyByAMultiFactorSession() throws Exception {
        world.activate(RiskOperation.WITHDRAWAL);
        List<String> asked = new ArrayList<>();

        Holder recovered = holder();
        recover(recovered);
        assertThat(judged(recovered, eur(10_00))).isEqualTo(WithdrawalVerdict.refuse());
        assertThat(last().decision()).isEqualTo(RiskDecision.CHALLENGE);
        assertThat(last().challengeSatisfied()).contains(false);
        assertThat(world.count(VelocityDimension.PARTY, recovered.party(), WindowKind.D1)).isZero();

        Holder stepped = holder();
        recover(stepped);
        var session = RiskEvaluationWorld.session(stepped.identity(), AssuranceLevel.MULTI_FACTOR, null);
        world.sessions = new IdentitySessionAssurance(RiskEvaluationWorld.liveSessions(asked, session));
        try (AutoCloseable presented = RiskEvaluationWorld.presenting(session)) {
            assertThat(judged(stepped, eur(10_00))).isEqualTo(WithdrawalVerdict.permit());
        }
        assertThat(last().decision()).isEqualTo(RiskDecision.CHALLENGE);
        assertThat(last().challengeSatisfied()).contains(true);
        assertThat(world.count(VelocityDimension.PARTY, stepped.party(), WindowKind.D1)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ one assessment

    @Test
    @DisplayName("one assessment per withdrawal: the command ref is the withdrawal's id, and a second call is"
            + " answered from the seal - one row, one event, one increment (INV-RSK-01, INV-CON-03)")
    void oneAssessmentPerWithdrawal() {
        world.activate(RiskOperation.WITHDRAWAL);
        Holder holder = holder();
        WithdrawalRiskDecision.Judged withdrawal = withdrawal(holder, eur(25_00));
        assertThat(as(holder, () -> seam.check(UNTOUCHABLE, withdrawal))).isEqualTo(WithdrawalVerdict.permit());
        assertThat(as(holder, () -> seam.check(UNTOUCHABLE, withdrawal))).isEqualTo(WithdrawalVerdict.permit());
        assertThat(world.assessmentStore.all()).singleElement().satisfies(sealed -> {
            assertThat(sealed.operation()).isEqualTo(RiskOperation.WITHDRAWAL);
            assertThat(sealed.commandRef().value()).isEqualTo(withdrawal.id().value().toString());
        });
        assertThat(world.decisionsAnnounced()).isEqualTo(1);
        assertThat(world.count(VelocityDimension.PARTY, holder.party(), WindowKind.D1)).isEqualTo(1);
    }

    @Test
    @DisplayName("a replayed HOLD answers the same hold validity")
    void aReplayedHoldHoldsAlike() {
        world.activate(RiskOperation.WITHDRAWAL);
        Holder holder = holder();
        WithdrawalRiskDecision.Judged withdrawal = withdrawal(holder, eur(3_000_00));
        WithdrawalVerdict first = as(holder, () -> seam.check(UNTOUCHABLE, withdrawal));
        WithdrawalVerdict replay = as(holder, () -> seam.check(UNTOUCHABLE, withdrawal));
        assertThat(replay).isEqualTo(first);
        assertThat(first.holdValidity()).contains(Duration.ofHours(72));
        assertThat(world.assessmentStore.all()).hasSize(1);
    }

    // ------------------------------------------------------------------ the fail-safe

    @Test
    @DisplayName("no policy is the production default: ALLOW(NO_POLICY_ACTIVE) recorded and counted - PERMIT")
    void noPolicyPermitsRecorded() {
        Holder holder = holder();
        assertThat(judged(holder, eur(5_000_00))).isEqualTo(WithdrawalVerdict.permit());
        assertThat(last().decision()).isEqualTo(RiskDecision.ALLOW);
        assertThat(last().failSafeReason()).contains(ReasonCode.NO_POLICY_ACTIVE);
        assertThat(last().unavailable()).contains(UnavailableCause.NO_POLICY);
        assertThat(world.count(VelocityDimension.PARTY, holder.party(), WindowKind.D1)).isEqualTo(1);
    }

    @Test
    @DisplayName("risk unavailable - the 250 ms budget breached - is C1's band for withdrawals: DENY at every band,"
            + " recorded RISK_UNAVAILABLE, REFUSE, counted nothing")
    void aTimeoutIsDenyAtEveryBand() {
        world.activate(RiskOperation.WITHDRAWAL);
        Holder holder = holder();
        world.failSerialise.push(RiskEvaluationWorld.timeout());
        assertThat(judged(holder, eur(1_00))).isEqualTo(WithdrawalVerdict.refuse());
        assertThat(last().decision()).isEqualTo(RiskDecision.DENY);
        assertThat(last().failSafeReason()).contains(ReasonCode.RISK_UNAVAILABLE);
        assertThat(last().unavailable()).contains(UnavailableCause.TIMEOUT);
        assertThat(world.count(VelocityDimension.PARTY, holder.party(), WindowKind.D1)).isZero();
    }

    // ------------------------------------------------------------------ the request

    @Test
    @DisplayName("the request is the withdrawal's own: its id as the command ref, the party, the wallet's customer"
            + " account (read on the dispatch's connection), the acting identity - and no device, no destination")
    void theRequestIsTheWithdrawalsOwn() {
        world.activate(RiskOperation.WITHDRAWAL);
        Holder holder = holder();
        judged(holder, eur(10_00));
        assertThat(ownersAsked).containsExactly(holder.wallet());
        RiskAssessment sealed = last();
        assertThat(sealed.subjects().party()).isEqualTo(holder.party());
        assertThat(sealed.subjects().sourceAccount()).contains(holder.account());
        assertThat(sealed.subjects().identity()).contains(holder.identity());
        assertThat(sealed.subjects().device()).as("no per-device identifier exists (-008's finding)").isEmpty();
        assertThat(sealed.subjects().destination())
                .as("an external instrument is no account and no beneficiary").isEmpty();
        assertThat(world.windowStore.upserted).as("party and source account, four window kinds each").hasSize(8);
    }

    @Test
    @DisplayName("the verdict carries nothing of the judgement: PERMIT, HOLD (with its validity) or REFUSE")
    void theVerdictCarriesNoReason() {
        world.activate(RiskOperation.WITHDRAWAL);
        WithdrawalVerdict held = judged(holder(), eur(2_500_00));
        assertThat(held.toString()).doesNotContain("AMOUNT_BAND").doesNotContain("HOLD_");
        assertThat(held.toString()).isEqualTo("WithdrawalVerdict[HOLD, PT72H]");
    }

    private void recover(Holder holder) {
        world.signalStore.add(new RiskSignal(RiskSignalId.next(IDS), SignalKind.RECOVERY_COMPLETED,
                SignalSubject.of(SubjectKind.IDENTITY, holder.identity()),
                new RiskSignal.SourceEvent(EventId.next(IDS), "identity.RecoveryCompleted", 1, "identity",
                        CorrelationId.of("flow"), CausationId.of("cause")),
                RiskEvaluationWorld.CLOCK.instant().minus(Duration.ofHours(10)),
                SignalAttributes.of(SignalKind.RECOVERY_COMPLETED,
                        Map.of("request_ref", IDS.next().toString(), "channel_ref", IDS.next().toString()))));
    }
}
