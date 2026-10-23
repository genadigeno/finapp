package com.finapp.app.risk;

import static com.finapp.app.risk.RiskEvaluationWorld.CLOCK;
import static com.finapp.app.risk.RiskEvaluationWorld.IDS;
import static com.finapp.app.risk.RiskEvaluationWorld.UNTOUCHABLE;
import static com.finapp.app.risk.RiskEvaluationWorld.eur;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.identity.AssuranceLevel;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.risk.ReasonCode;
import com.finapp.risk.RiskAssessment;
import com.finapp.risk.RiskAssurance;
import com.finapp.risk.RiskDecision;
import com.finapp.risk.RiskEngine;
import com.finapp.risk.RiskEvaluation;
import com.finapp.risk.RiskEvaluationRequest;
import com.finapp.risk.RiskOperation;
import com.finapp.risk.RiskPolicy;
import com.finapp.risk.RiskRating;
import com.finapp.risk.RiskRule;
import com.finapp.risk.RuleKind;
import com.finapp.risk.RuleParameters;
import com.finapp.risk.ScoreBand;
import com.finapp.risk.UnavailableCause;
import com.finapp.risk.VelocityDimension;
import com.finapp.risk.VelocityOutcome;
import com.finapp.risk.WindowKind;
import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import com.finapp.transfers.FailureReason;
import com.finapp.transfers.SeamVerdict;
import com.finapp.transfers.Transfer;
import com.finapp.transfers.TransferId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Transfers' two seams answered by risk, over a real {@link RiskEvaluation} with in-memory stores and the C8 TRANSFER
 * fixture (`P13-TSK-008`; {@code PHASE_13_PLAN.md} sections 7.3, 12.1, 12.3; ADR-0108; {@code INV-RSK-01},
 * {@code INV-RSK-02}, {@code INV-RSK-03}, {@code INV-CON-03}): every decision mapped to its verdict and its committed
 * reason; both seams of one transfer sealing ONE assessment and counting ONE increment per window (the second
 * replayed); a {@code CHALLENGE} refused without a satisfying session and permitted with one; the fail-safe reaching
 * the seam; {@code RaceLost} propagating; the request carrying the transfer's own facts; and the adapters writing
 * nothing of their own on the caller's connection. The concurrency, the savepoints and the real execution's locks are
 * the owed {@code TransferRiskSeamDatabaseTest}'s (C5).
 *
 * <p>{@link #execute} consults the seams exactly as {@code TransferExecution} does ({@code :233-248}): the limit seam
 * first - a {@code REFUSE} commits {@code LIMIT_REFUSED} and the risk seam is not consulted - then the risk seam.
 */
@DisplayName("the transfer seams answered by risk: one assessment, the mapping, the challenge, the fail-safe"
        + " (P13-TSK-008)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class RiskTransferSeamsTest {

    private final RiskEvaluationWorld world = new RiskEvaluationWorld();
    private final Map<TransferId, TransferSubjects.Subjects> subjects = new HashMap<>();
    private final TransferRiskJudgement judgement = new TransferRiskJudgement(world.evaluation,
            (unitOfWork, transfer) -> subjects.get(transfer.id()));
    private final RiskTransferLimitCheck limits = new RiskTransferLimitCheck(judgement);
    private final RiskTransferRiskDecision risk = new RiskTransferRiskDecision(judgement);
    private final List<String> consulted = new ArrayList<>();
    private Holder lastPayer;
    private Transfer lastTransfer;

    /** One customer: a party, an identity, an account (customer and ledger ids). */
    private record Holder(UUID party, UUID identity, UUID account, LedgerAccountId wallet) {
        static Holder fresh() {
            return new Holder(IDS.next(), IDS.next(), IDS.next(), LedgerAccountId.of(IDS.next()));
        }

        Holder withAnotherAccount() {
            return new Holder(party, identity, IDS.next(), LedgerAccountId.of(IDS.next()));
        }
    }

    // ------------------------------------------------------------------ the mapping

    @Test
    @DisplayName("every decision maps to its verdict and reason: ALLOW posts; an unsatisfied CHALLENGE is RISK_REFUSED"
            + " (CHALLENGE_REQUIRED); the EUR 10,000 AMOUNT_BAND DENY and the eleventh's VELOCITY_COUNT REVIEW are"
            + " LIMIT_REFUSED; a screening DENY, a PROHIBITED profile and a score band's REVIEW are RISK_REFUSED")
    void everyDecisionMapsToItsVerdictAndReason() {
        world.activate(RiskOperation.TRANSFER);

        assertThat(judged(Holder.fresh(), Holder.fresh(), eur(100_00), RiskDecision.ALLOW)).isEmpty();

        Holder challenged = Holder.fresh();
        assertThat(judged(challenged, Holder.fresh(), eur(600_00), RiskDecision.CHALLENGE))
                .contains(FailureReason.RISK_REFUSED);
        RiskEvaluation.Assessed unsatisfied = lastAssessed();
        assertThat(unsatisfied.refusalReason()).contains(ReasonCode.CHALLENGE_REQUIRED);
        assertThat(unsatisfied.limitDecided()).as("a challenge is never a limit refusal").isFalse();
        assertThat(world.count(VelocityDimension.PARTY, challenged.party(), WindowKind.D1))
                .as("a refused transfer counts nothing").isZero();

        assertThat(judged(Holder.fresh(), Holder.fresh(), eur(10_000_00), RiskDecision.DENY))
                .contains(FailureReason.LIMIT_REFUSED);
        assertThat(consulted).as("the limit refused: the risk seam is not consulted").endsWith("limits");

        Holder busy = Holder.fresh();
        for (int i = 0; i < 10; i++) {
            assertThat(judged(busy, Holder.fresh(), eur(1_00), RiskDecision.ALLOW)).isEmpty();
        }
        assertThat(judged(busy, Holder.fresh(), eur(1_00), RiskDecision.REVIEW))
                .as("the eleventh of the day from one account: VELOCITY_COUNT's own REVIEW")
                .contains(FailureReason.LIMIT_REFUSED);
        assertThat(world.count(VelocityDimension.SOURCE_ACCOUNT, busy.account(), WindowKind.D1)).isEqualTo(10);

        world.screeningHit = Optional.of(true);
        assertThat(judged(Holder.fresh(), Holder.fresh(), eur(100_00), RiskDecision.DENY))
                .contains(FailureReason.RISK_REFUSED);
        assertThat(lastAssessed().reasons()).containsExactly(ReasonCode.SCREENING_HIT);
        assertThat(judged(Holder.fresh(), Holder.fresh(), eur(10_000_00), RiskDecision.DENY))
                .as("a tie - the limit's own DENY and the screening's: the limit seam runs first")
                .contains(FailureReason.LIMIT_REFUSED);
        world.screeningHit = Optional.empty();

        Holder prohibited = Holder.fresh();
        world.profileStore.seedDisposition(prohibited.party(), RiskRating.PROHIBITED);
        assertThat(judged(prohibited, Holder.fresh(), eur(100_00), RiskDecision.DENY))
                .contains(FailureReason.RISK_REFUSED);

        world.activate(bandedReview());
        assertThat(judged(Holder.fresh(), Holder.fresh(), eur(10_00), RiskDecision.REVIEW))
                .as("a score band pushed by a weighted AMOUNT_BAND decides as risk, not as a limit")
                .contains(FailureReason.RISK_REFUSED);
        assertThat(world.cases).as("REVIEW reaches the case opener (NONE in production until -016)").hasSize(2);
    }

    // ------------------------------------------------------------------ one assessment

    @Test
    @DisplayName("both seams of one transfer: ONE assessment, ONE RiskDecisionMade, ONE increment per window - the limit"
            + " seam evaluates, the risk seam's call is answered from the seal (INV-RSK-01, INV-CON-03)")
    void bothSeamsOneAssessmentOneIncrement() {
        world.activate(RiskOperation.TRANSFER);
        Holder payer = Holder.fresh();
        Holder payee = Holder.fresh();
        Transfer transfer = transfer(payer, payee, eur(100_00));

        assertThat(inFlow(payer, () -> limits.check(UNTOUCHABLE, transfer))).isEqualTo(SeamVerdict.PERMIT);
        assertThat(world.assessmentStore.log).containsExactly("find", "insert");
        assertThat(inFlow(payer, () -> risk.check(UNTOUCHABLE, transfer))).isEqualTo(SeamVerdict.PERMIT);
        assertThat(world.assessmentStore.log).as("the second seam reads the seal and writes nothing")
                .containsExactly("find", "insert", "find");
        assertThat(inFlow(payer, () -> judgement.judge(UNTOUCHABLE, transfer)).replayed()).isTrue();

        assertThat(world.assessmentStore.all()).hasSize(1);
        assertThat(world.decisionsAnnounced()).isEqualTo(1);
        assertThat(world.windowStore.upserted).as("three dimensions times four window kinds, once").hasSize(12);
        assertThat(world.count(VelocityDimension.PARTY, payer.party(), WindowKind.D1)).isEqualTo(1);
        assertThat(world.count(VelocityDimension.SOURCE_ACCOUNT, payer.account(), WindowKind.D1)).isEqualTo(1);
        assertThat(world.count(VelocityDimension.DESTINATION, payee.account(), WindowKind.D1)).isEqualTo(1);
        assertThat(world.reports).as("the meters see one evaluation").hasSize(1);
    }

    @Test
    @DisplayName("ten transfers of one party from ten accounts count ten in the PARTY window - the party is the"
            + " identity's, whatever account the money leaves")
    void tenTransfersOfOnePartyFromTenAccountsCountTen() {
        world.activate(RiskOperation.TRANSFER);
        Holder payer = Holder.fresh();
        for (int i = 0; i < 10; i++) {
            Holder account = payer.withAnotherAccount();
            assertThat(judged(account, Holder.fresh(), eur(10_00), RiskDecision.ALLOW)).isEmpty();
        }
        assertThat(world.count(VelocityDimension.PARTY, payer.party(), WindowKind.D1)).isEqualTo(10);
        assertThat(world.assessmentStore.all()).hasSize(10)
                .allSatisfy(sealed -> assertThat(sealed.velocity()).isEqualTo(VelocityOutcome.COUNTED));
    }

    // ------------------------------------------------------------------ the challenge

    @Test
    @DisplayName("a CHALLENGE is satisfied by the acting session read live: MULTI_FACTOR and STRONG permit and count;"
            + " PASSWORD, a revoked session and no request at all refuse RISK_REFUSED with CHALLENGE_REQUIRED and count"
            + " nothing")
    void aChallengeIsSatisfiedOnlyByAMultiFactorSessionReadLive() throws Exception {
        world.activate(RiskOperation.TRANSFER);
        List<String> asked = new ArrayList<>();

        for (AssuranceLevel level : AssuranceLevel.values()) {
            Holder payer = Holder.fresh();
            var session = RiskEvaluationWorld.session(payer.identity(), level, "Firefox on Linux");
            world.sessions = new IdentitySessionAssurance(RiskEvaluationWorld.liveSessions(asked, session));
            try (AutoCloseable presented = RiskEvaluationWorld.presenting(session)) {
                Optional<FailureReason> refused = judged(payer, Holder.fresh(), eur(600_00), RiskDecision.CHALLENGE);
                if (level == AssuranceLevel.PASSWORD) {
                    assertThat(refused).as(level.name()).contains(FailureReason.RISK_REFUSED);
                    assertThat(lastAssessed().challengeSatisfied()).contains(false);
                    assertThat(lastAssessed().velocity()).isEqualTo(VelocityOutcome.NOT_PERMITTED);
                } else {
                    assertThat(refused).as(level.name()).isEmpty();
                    assertThat(lastAssessed().challengeSatisfied()).contains(true);
                    assertThat(world.count(VelocityDimension.PARTY, payer.party(), WindowKind.D1)).isEqualTo(1);
                }
            }
        }
        assertThat(asked).as("read once per transfer: the second seam replays").hasSize(3);

        Holder revoked = Holder.fresh();
        var gone = RiskEvaluationWorld.session(revoked.identity(), AssuranceLevel.MULTI_FACTOR, null);
        world.sessions = new IdentitySessionAssurance(RiskEvaluationWorld.liveSessions(asked));
        try (AutoCloseable presented = RiskEvaluationWorld.presenting(gone)) {
            assertThat(judged(revoked, Holder.fresh(), eur(600_00), RiskDecision.CHALLENGE))
                    .as("the door saw it; the live re-read does not - the race's answer")
                    .contains(FailureReason.RISK_REFUSED);
        }

        assertThat(judged(Holder.fresh(), Holder.fresh(), eur(600_00), RiskDecision.CHALLENGE))
                .as("no request bound: no session, unsatisfied - the safe direction")
                .contains(FailureReason.RISK_REFUSED);
    }

    // ------------------------------------------------------------------ the fail-safe

    @Test
    @DisplayName("the fail-safe reaches the seam: no policy is ALLOW(NO_POLICY_ACTIVE), counted, posted; a timed-out"
            + " subject lock below EUR 1,000 is ALLOW(RISK_UNAVAILABLE) and posts, at EUR 1,000 DENY(RISK_UNAVAILABLE)"
            + " and RISK_REFUSED - never LIMIT_REFUSED: a fail-safe has no hit")
    void theFailSafeReachesTheSeam() {
        Holder unjudged = Holder.fresh();
        assertThat(judged(unjudged, Holder.fresh(), eur(50_000_00), RiskDecision.ALLOW)).isEmpty();
        assertThat(lastAssessed().failSafe()).contains(ReasonCode.NO_POLICY_ACTIVE);
        assertThat(lastAssessed().velocity()).isEqualTo(VelocityOutcome.COUNTED);
        assertThat(sealedLast().unavailable()).contains(UnavailableCause.NO_POLICY);

        world.activate(RiskOperation.TRANSFER);
        world.failSerialise.push(RiskEvaluationWorld.timeout());
        assertThat(judged(Holder.fresh(), Holder.fresh(), eur(999_99), RiskDecision.ALLOW)).isEmpty();
        assertThat(lastAssessed().failSafe()).contains(ReasonCode.RISK_UNAVAILABLE);
        assertThat(sealedLast().unavailable()).contains(UnavailableCause.TIMEOUT);

        world.failSerialise.push(RiskEvaluationWorld.timeout());
        assertThat(judged(Holder.fresh(), Holder.fresh(), eur(1_000_00), RiskDecision.DENY))
                .contains(FailureReason.RISK_REFUSED);
        assertThat(lastAssessed().failSafe()).contains(ReasonCode.RISK_UNAVAILABLE);
        assertThat(lastAssessed().velocity()).isEqualTo(VelocityOutcome.NOT_PERMITTED);
    }

    // ------------------------------------------------------------------ what propagates, what is built, what is written

    @Test
    @DisplayName("RaceLost propagates from either seam - never swallowed into a verdict - so the transaction rolls back"
            + " with its count and the retry finds the seal")
    void raceLostPropagates() {
        world.activate(RiskOperation.TRANSFER);
        Holder payer = Holder.fresh();
        Transfer transfer = transfer(payer, Holder.fresh(), eur(100_00));
        inFlow(payer, () -> limits.check(UNTOUCHABLE, transfer));
        world.assessmentStore.missNextFind = true;
        assertThatThrownBy(() -> inFlow(payer, () -> risk.check(UNTOUCHABLE, transfer)))
                .isInstanceOf(RiskEvaluation.RaceLost.class);
        assertThat(world.assessmentStore.all()).hasSize(1);
    }

    @Test
    @DisplayName("the request is the transfer's own: the transfer id as the command ref, the amount, the payer party, the"
            + " two customer accounts in their roles, the initiating identity, the flow's correlation - and no device:"
            + " the session's label is a browser build, not a device, and no DEVICE window or lock is taken over it")
    void theRequestIsTheTransfersOwn() throws Exception {
        world.activate(RiskOperation.TRANSFER);
        Holder payer = Holder.fresh();
        Holder payee = Holder.fresh();
        Transfer transfer = transfer(payer, payee, eur(42_00));
        CorrelationId flow = CorrelationId.generate(IDS);
        var session = RiskEvaluationWorld.session(payer.identity(), AssuranceLevel.PASSWORD, "Firefox on Linux");
        try (AutoCloseable presented = RiskEvaluationWorld.presenting(session);
                SecurityContext.Scope actor = SecurityContext.enter(actor(payer));
                CorrelationContext.Scope scope = CorrelationContext.enter(Correlation.startingWith(flow))) {
            limits.check(UNTOUCHABLE, transfer);
        }
        RiskAssessment sealed = sealedLast();
        assertThat(sealed.commandRef().value()).isEqualTo(transfer.id().value().toString());
        assertThat(sealed.operation()).isEqualTo(RiskOperation.TRANSFER);
        assertThat(sealed.amount()).isEqualTo(eur(42_00));
        assertThat(sealed.subjects().party()).isEqualTo(payer.party());
        assertThat(sealed.subjects().identity()).contains(payer.identity());
        assertThat(sealed.subjects().sourceAccount()).contains(payer.account());
        assertThat(sealed.subjects().destination()).contains(new RiskEvaluationRequest.Destination(
                RiskEvaluationRequest.DestinationKind.ACCOUNT, payee.account()));
        assertThat(sealed.subjects().device()).as("a User-Agent label is never a device subject").isEmpty();
        assertThat(world.windowStore.upserted).as("party, source, destination - four windows each, no DEVICE")
                .hasSize(12)
                .noneMatch(key -> key.dimension() == VelocityDimension.DEVICE);
        assertThat(sealed.correlationId()).isEqualTo(flow.value());
        assertThat(world.envelopes).singleElement()
                .satisfies(event -> assertThat(event.correlationId()).isEqualTo(flow));
    }

    @Test
    @DisplayName("the adapters write nothing of their own (INV-RSK-02): every seam call here ran on a connection any use"
            + " of which fails - the only writes are risk's, through T-eval - and they answer two values")
    void theAdaptersWriteNothingOfTheirOwn() {
        world.activate(RiskOperation.TRANSFER);
        Holder payer = Holder.fresh();
        Transfer refused = transfer(payer, Holder.fresh(), eur(10_000_00));
        assertThat(inFlow(payer, () -> limits.check(UNTOUCHABLE, refused))).isEqualTo(SeamVerdict.REFUSE);
        assertThat(inFlow(payer, () -> risk.check(UNTOUCHABLE, refused))).isEqualTo(SeamVerdict.REFUSE);
        assertThat(world.assessmentStore.all()).hasSize(1);
        assertThat(world.windowStore.upserted).as("a refusal counts nothing").isEmpty();
    }

    // ------------------------------------------------------------------ the execution's order, mirrored

    /**
     * One transfer through both seams in {@code TransferExecution}'s order, under the payer's actor and a fresh flow:
     * empty when it would post, else the reason the execution commits. Asserts the sealed decision.
     */
    private Optional<FailureReason> judged(Holder payer, Holder payee, Money amount, RiskDecision expected) {
        Transfer transfer = transfer(payer, payee, amount);
        Optional<FailureReason> outcome = inFlow(payer, () -> execute(transfer));
        assertThat(lastAssessed().decision()).as("the sealed decision").isEqualTo(expected);
        assertThat(lastAssessed().commandRef().value()).isEqualTo(transfer.id().value().toString());
        return outcome;
    }

    private Optional<FailureReason> execute(Transfer transfer) {
        consulted.add("limits");
        if (limits.check(UNTOUCHABLE, transfer) == SeamVerdict.REFUSE) {
            return Optional.of(FailureReason.LIMIT_REFUSED);
        }
        consulted.add("risk");
        if (risk.check(UNTOUCHABLE, transfer) == SeamVerdict.REFUSE) {
            return Optional.of(FailureReason.RISK_REFUSED);
        }
        return Optional.empty();
    }

    private Transfer transfer(Holder payer, Holder payee, Money amount) {
        Transfer transfer = Transfer.initiate(IDS, CLOCK, IDS.next(), payer.wallet(), payee.wallet(), amount, "rent",
                payer.identity());
        subjects.put(transfer.id(), new TransferSubjects.Subjects(payer.party(), payer.account(), payee.account()));
        lastPayer = payer;
        lastTransfer = transfer;
        return transfer;
    }

    /** The last judged transfer's answer, as the seams read it - replayed from its seal by the real judgement. */
    private RiskEvaluation.Assessed lastAssessed() {
        RiskEvaluation.Assessed replayed = inFlow(lastPayer, () -> judgement.judge(UNTOUCHABLE, lastTransfer));
        assertThat(replayed.replayed()).isTrue();
        return replayed;
    }

    private RiskAssessment sealedLast() {
        List<RiskAssessment> all = world.assessmentStore.all();
        return all.get(all.size() - 1);
    }

    private static <T> T inFlow(Holder payer, Supplier<T> work) {
        try (SecurityContext.Scope actor = SecurityContext.enter(actor(payer));
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(IDS)))) {
            return work.get();
        }
    }

    private static Actor actor(Holder holder) {
        return new Actor(holder.identity().toString(), ActorType.CUSTOMER);
    }

    /** A TRANSFER version whose only rule is a weighted AMOUNT_BAND at EUR 0.01 worth 700: the REVIEW band decides. */
    private static RiskPolicy bandedReview() {
        return new RiskPolicy(RiskOperation.TRANSFER, RiskEngine.RISK_ENGINE_V1, RiskPolicyFixtures.EUR,
                List.of(RiskRule.weighted(1, RuleKind.AMOUNT_BAND, RuleParameters.of(RuleKind.AMOUNT_BAND,
                        Map.of("threshold_minor", RiskPolicyFixtures.minor(1))), 700)),
                List.of(new ScoreBand(0, 599, RiskDecision.ALLOW), new ScoreBand(600, 1000, RiskDecision.REVIEW)),
                List.of(), RiskPolicyFixtures.failSafe(1_000_00, RiskDecision.ALLOW, RiskDecision.DENY),
                Optional.of(RiskAssurance.MULTI_FACTOR), Optional.of(eur(1_000_00)), Optional.empty());
    }
}
