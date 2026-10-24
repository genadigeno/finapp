package com.finapp.app.crossborder;

import static com.finapp.app.crossborder.CrossBorderDoorComposition.EUR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.finapp.app.risk.RiskEvaluationWorld;
import com.finapp.crossborder.CorridorKey;
import com.finapp.crossborder.CrossborderErrorCode;
import com.finapp.fx.FxErrorCode;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityErrorCode;
import com.finapp.identity.Session;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.risk.RiskAssurance;
import com.finapp.risk.RiskDecision;
import com.finapp.risk.RiskOperation;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The cross-border payment door with risk at it (`P13-TSK-011`; {@code PHASE_13_PLAN.md} sections 2.2 C6, 7.3, 12.3;
 * ADR-0108 decision 3; {@code INV-RSK-01}, {@code INV-RSK-08}, {@code INV-IDEM-01}), the real desk and the real
 * authorization over {@link CrossBorderDoorComposition}: the step-up advice answering {@code 403
 * identity.AssuranceRequired} in its own transaction before the claim, with identity's words and nothing of the judgement;
 * a recorded key, a quote already judged, an offer not the caller's and a caller with no wallet never advised; a seam's
 * refusal keeping risk's record beside the claim's outcome (no rollback to the savepoint) and answering only
 * crossborder's 422; and a refusal after the permit rolling back to the savepoint, the judgement with it.
 */
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@DisplayName("the cross-border door with risk at it: the step-up before the claim, the seam refusal recorded"
        + " (P13-TSK-011)")
class CrossBorderDoorAdviceTest {

    private static final CorridorKey EUR_USD_US = CorridorKey.parse("EUR-USD-US");

    private final CrossBorderDoorComposition door = new CrossBorderDoorComposition();

    @BeforeEach
    void policy() {
        door.world.activate(RiskOperation.CROSS_BORDER);
    }

    // ------------------------------------------------------------------ the advice

    @Test
    @DisplayName("a challenge the session cannot satisfy is 403 identity.AssuranceRequired from the door's own"
            + " transaction: no claim, no assessment, no count - and the words name the factor, never the judgement")
    void anUnsatisfiedChallengeIsAnsweredBeforeTheClaim() {
        UUID quote = door.offer(EUR_USD_US, eur(600_00), usd(650_00));
        ApiException refused = authorize(quote, "k-1", AssuranceLevel.PASSWORD);
        assertThat(refused.errorCode()).isEqualTo(IdentityErrorCode.ASSURANCE_REQUIRED);
        assertThat(refused.errorCode().status()).isEqualTo(403);
        assertThat(door.journal).as("one transaction - the advice's - and never the claim's").containsExactly("tx");
        assertThat(door.records).as("the key is not claimed").isEmpty();
        assertThat(door.world.assessmentStore.all()).as("an advice seals nothing").isEmpty();
        assertThat(door.world.windowStore.upserted).as("an advice counts nothing").isEmpty();
        assertThat(refused.clientDetail()).hasValueSatisfying(detail -> assertThat(detail)
                .contains("enrol one").doesNotContain("AMOUNT").doesNotContain("CHALLENGE").doesNotContain("score"));

        door.enrolled = true;
        ApiException enrolled = authorize(quote, "k-1", AssuranceLevel.PASSWORD);
        assertThat(enrolled.clientDetail()).hasValueSatisfying(detail ->
                assertThat(detail).contains("step up with your enrolled factor"));
    }

    @Test
    @DisplayName("a satisfied session is not stepped up: the advice's transaction, then the claim's, the seams permitting"
            + " - the acceptance's refusal after them rolling back to the savepoint")
    void aSatisfiedSessionProceeds() {
        door.world.sessions = (unitOfWork, actor) -> Optional.of(RiskAssurance.MULTI_FACTOR);
        UUID quote = door.offer(EUR_USD_US, eur(600_00), usd(650_00));
        ApiException refused = authorize(quote, "k-2", AssuranceLevel.MULTI_FACTOR);
        assertThat(refused.errorCode()).isEqualTo(FxErrorCode.QUOTE_EXPIRED);
        assertThat(door.journal).containsExactly("tx", "tx", "savepoint", "rollback to the savepoint");
        assertThat(door.world.assessmentStore.all()).singleElement()
                .satisfies(sealed -> assertThat(sealed.decision()).isEqualTo(RiskDecision.CHALLENGE));
        assertThat(storedResponse("k-2")).isEqualTo("ERR|FX|QUOTE_EXPIRED");
    }

    @Test
    @DisplayName("a retry of a recorded key is never advised (INV-IDEM-01): the claim replays its response of record")
    void aRecordedKeyIsNeverAdvised() {
        UUID quote = door.offer(EUR_USD_US, eur(600_00), usd(650_00));
        door.recorded(door.key("k-3"), quote, CrossborderErrorCode.LIMIT_REFUSED.name());
        ApiException replayed = authorize(quote, "k-3", AssuranceLevel.PASSWORD);
        assertThat(replayed.errorCode()).as("the record replayed, not a step-up")
                .isEqualTo(CrossborderErrorCode.LIMIT_REFUSED);
        assertThat(door.world.assessmentStore.all()).isEmpty();
    }

    @Test
    @DisplayName("a quote risk already judged is never advised: under a new key the authorization replays the seal -"
            + " its answer of record, never a step-up for a decision already taken")
    void aJudgedQuoteIsNeverAdvised() {
        UUID quote = door.offer(EUR_USD_US, eur(600_00), usd(650_00));
        door.world.sessions = (unitOfWork, actor) -> Optional.of(RiskAssurance.MULTI_FACTOR);
        assertThat(authorize(quote, "k-4", AssuranceLevel.MULTI_FACTOR).errorCode())
                .as("judged and sealed - a satisfied CHALLENGE - then refused by the acceptance")
                .isEqualTo(FxErrorCode.QUOTE_EXPIRED);
        // The in-memory store is no database: the seal the savepoint rolled back is still here, as it would be had
        // the authorization committed it - the "already judged" state this case needs.
        door.world.sessions = (unitOfWork, actor) -> Optional.empty();
        ApiException again = authorize(quote, "k-5", AssuranceLevel.PASSWORD);
        assertThat(again.errorCode()).as("the seal replayed by the authorization, not a fresh advice's 403")
                .isEqualTo(FxErrorCode.QUOTE_EXPIRED);
        assertThat(door.world.assessmentStore.all()).hasSize(1);
    }

    @Test
    @DisplayName("an offer not the caller's, or a caller with no wallet in its source currency, is never advised over:"
            + " the authorization answers its own refusal as it always has")
    void unownedOrUnfundedIsNeverAdvisedOver() {
        ApiException unknown = authorize(RiskEvaluationWorld.IDS.next(), "k-6", AssuranceLevel.PASSWORD);
        assertThat(unknown.errorCode()).isEqualTo(CrossborderErrorCode.OFFER_NOT_FOUND);
        door.walletHeld = false;
        UUID quote = door.offer(EUR_USD_US, eur(600_00), usd(650_00));
        ApiException unfunded = authorize(quote, "k-7", AssuranceLevel.PASSWORD);
        assertThat(unfunded.errorCode()).as("judged in the authorization, refused there - not stepped up")
                .isEqualTo(CrossborderErrorCode.RISK_REFUSED);
    }

    // ------------------------------------------------------------------ the seam refusal, recorded

    @Test
    @DisplayName("a seam's refusal keeps risk's record (INV-RSK-01): no rollback to the savepoint, the claim's ERR"
            + " stored beside the seal - LimitRefused for the EUR 10,000 band, RiskRefused for a screening hit")
    void aSeamRefusalKeepsTheJudgement() {
        UUID limited = door.offer(EUR_USD_US, eur(10_000_00), usd(10_800_00));
        ApiException limit = authorize(limited, "k-8", AssuranceLevel.PASSWORD);
        assertThat(limit.errorCode()).isEqualTo(CrossborderErrorCode.LIMIT_REFUSED);
        assertThat(limit.errorCode().status()).isEqualTo(422);
        assertThat(door.journal).as("the savepoint set and never rolled back to")
                .containsExactly("tx", "tx", "savepoint");
        assertThat(storedResponse("k-8")).isEqualTo("ERR|XB|LIMIT_REFUSED");

        door.journal.clear();
        door.world.screeningHit = Optional.of(true);
        UUID screened = door.offer(EUR_USD_US, eur(100_00), usd(108_00));
        ApiException risk = authorize(screened, "k-9", AssuranceLevel.PASSWORD);
        assertThat(risk.errorCode()).isEqualTo(CrossborderErrorCode.RISK_REFUSED);
        assertThat(door.journal).containsExactly("tx", "tx", "savepoint");
        assertThat(storedResponse("k-9")).isEqualTo("ERR|XB|RISK_REFUSED");
        assertThat(door.world.assessmentStore.all()).extracting(sealed -> sealed.decision())
                .containsExactly(RiskDecision.DENY, RiskDecision.DENY);
        assertThat(risk.clientDetail().orElse("") + risk.getMessage())
                .as("C6: crossborder's generic words, nothing of risk")
                .doesNotContain("SCREENING").doesNotContain("score").doesNotContain("rule");
    }

    @Test
    @DisplayName("every other refusal still rolls back to the savepoint - a permit then refused by the acceptance leaves"
            + " no judgement behind (a rolled-back command leaves none)")
    void aRefusalAfterThePermitRollsBack() {
        UUID quote = door.offer(EUR_USD_US, eur(100_00), usd(108_00));
        ApiException refused = authorize(quote, "k-10", AssuranceLevel.PASSWORD);
        assertThat(refused.errorCode()).isEqualTo(FxErrorCode.QUOTE_EXPIRED);
        assertThat(door.journal).containsExactly("tx", "tx", "savepoint", "rollback to the savepoint");
        assertThat(CrossBorderPaymentDesk.keepsTheJudgement(new CrossBorderPaymentDesk.Refusal("FX", "QUOTE_EXPIRED")))
                .isFalse();
        assertThat(CrossBorderPaymentDesk.keepsTheJudgement(
                new CrossBorderPaymentDesk.Refusal("XB", CrossborderErrorCode.BENEFICIARY_NOT_PAYABLE.name())))
                .isFalse();
        for (CrossborderErrorCode seam : List.of(CrossborderErrorCode.RISK_REFUSED, CrossborderErrorCode.LIMIT_REFUSED)) {
            assertThat(CrossBorderPaymentDesk.keepsTheJudgement(new CrossBorderPaymentDesk.Refusal("XB", seam.name())))
                    .isTrue();
        }
    }

    // ------------------------------------------------------------------ plumbing

    /** The door's POST under the caller's session at {@code level}: the refusal it answers. */
    private ApiException authorize(UUID quote, String clientKey, AssuranceLevel level) {
        Session session = RiskEvaluationWorld.session(door.identity.value(), level, "Firefox on Linux");
        try (SecurityContext.Scope actor = SecurityContext.enter(
                        new Actor(door.identity.value().toString(), ActorType.CUSTOMER));
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(RiskEvaluationWorld.IDS)))) {
            return catchThrowableOfType(ApiException.class, () -> door.desk.authorize(session, clientKey,
                    new CrossBorderPaymentController.CrossBorderPaymentRequest(quote.toString())));
        }
    }

    private String storedResponse(String clientKey) {
        return new String(door.records.get(door.key(clientKey).scope() + "|" + clientKey).response().body(),
                StandardCharsets.UTF_8);
    }

    private static Money eur(long minor) {
        return Money.ofMinorUnits(minor, EUR);
    }

    private static Money usd(long minor) {
        return Money.ofMinorUnits(minor, CurrencyCode.of("USD"));
    }
}
