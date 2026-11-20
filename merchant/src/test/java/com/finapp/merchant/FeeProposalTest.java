package com.finapp.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("a fee schedule version and a merchant's fee assignment by four eyes (P17-TSK-123)")
class FeeProposalTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Actor PROPOSER = new Actor("administrator-1", ActorType.EMPLOYEE);
    private static final Actor CONTROLLER = new Actor("controller-1", ActorType.EMPLOYEE);
    private static final Actor SECOND_CONTROLLER = new Actor("controller-2", ActorType.EMPLOYEE);
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    @Test
    @DisplayName("a version proposal: born PROPOSED with the terms; decided once, never by its proposer (INV-AUD-04),"
            + " never dated before its proposal; an approval names the version it minted, a rejection none")
    void aVersionIsDecidedOnceByAnotherPerson() {
        FeeSchedules.NewVersion terms = new FeeSchedules.NewVersion(FeeRate.of(new BigDecimal("0.029")),
                Money.ofMinorUnits(30, EUR), RoundingPolicy.HALF_EVEN, RefundFeePolicy.RETAINED, Optional.empty(),
                "new pricing");
        FeeScheduleVersionProposal proposed = FeeScheduleVersionProposal.propose(IDS, CLOCK, FeeScheduleId.of(IDS.next()),
                terms, PROPOSER, "flow");
        assertThat(proposed.status()).isEqualTo(FeeProposalStatus.PROPOSED);
        assertThat(proposed.terms()).as("the approval mints exactly the proposed terms, with the proposer's reason")
                .isEqualTo(terms);
        FeeScheduleVersionId minted = FeeScheduleVersionId.next(IDS);
        assertThatThrownBy(() -> proposed.approve(PROPOSER, "mine", CLOCK, "c", minted))
                .isInstanceOfSatisfying(FeeProposalRefused.class, refused -> assertThat(refused.reason())
                        .isEqualTo(FeeProposalRefused.Reason.SELF_DECISION));
        assertThatThrownBy(() -> proposed.reject(PROPOSER, "mine", CLOCK, "c"))
                .isInstanceOfSatisfying(FeeProposalRefused.class, refused -> assertThat(refused.reason())
                        .isEqualTo(FeeProposalRefused.Reason.SELF_DECISION));

        FeeScheduleVersionProposal approved = proposed.approve(CONTROLLER, "reviewed",
                Clock.offset(CLOCK, Duration.ofMinutes(-5)), "c", minted);
        assertThat(approved.status()).isEqualTo(FeeProposalStatus.APPROVED);
        assertThat(approved.mintedVersion()).contains(minted);
        assertThat(approved.decision()).hasValueSatisfying(decision -> {
            assertThat(decision.decidedBy()).isEqualTo(CONTROLLER.id());
            assertThat(decision.decidedAt()).as("another instance's clock behind").isEqualTo(proposed.proposedAt());
        });
        assertThatThrownBy(() -> approved.reject(SECOND_CONTROLLER, "again", CLOCK, "c"))
                .isInstanceOfSatisfying(FeeProposalRefused.class, refused -> assertThat(refused.reason())
                        .isEqualTo(FeeProposalRefused.Reason.ALREADY_DECIDED));
        assertThatThrownBy(() -> approved.approve(SECOND_CONTROLLER, "again", CLOCK, "c", minted))
                .isInstanceOfSatisfying(FeeProposalRefused.class, refused -> assertThat(refused.reason())
                        .isEqualTo(FeeProposalRefused.Reason.ALREADY_DECIDED));

        FeeScheduleVersionProposal rejected = proposed.reject(CONTROLLER, "not now", CLOCK, "c");
        assertThat(rejected.mintedVersion()).isEmpty();

        assertThatThrownBy(() -> new FeeScheduleVersionProposal(proposed.id(), proposed.feeScheduleId(), proposed.rate(),
                proposed.fixed(), proposed.roundingPolicy(), proposed.refundFeePolicy(), proposed.effectiveFrom(),
                proposed.proposalReason(), proposed.proposedBy(), proposed.proposedByType(), proposed.proposedAt(),
                proposed.proposalCorrelationId(), FeeProposalStatus.APPROVED, Optional.of(new FeeProposalDecision(
                        PROPOSER.id(), "EMPLOYEE", CLOCK.instant(), "mine", "c")), Optional.of(minted)))
                .as("a self-decided row is unrepresentable").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeeScheduleVersionProposal(proposed.id(), proposed.feeScheduleId(), proposed.rate(),
                proposed.fixed(), proposed.roundingPolicy(), proposed.refundFeePolicy(), proposed.effectiveFrom(),
                proposed.proposalReason(), proposed.proposedBy(), proposed.proposedByType(), proposed.proposedAt(),
                proposed.proposalCorrelationId(), FeeProposalStatus.APPROVED, Optional.of(new FeeProposalDecision(
                        CONTROLLER.id(), "EMPLOYEE", CLOCK.instant(), "ok", "c")), Optional.empty()))
                .as("an approval that minted nothing is unrepresentable").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an assignment proposal: born PROPOSED; decided once, never by its proposer (INV-AUD-04), never dated"
            + " before its proposal")
    void anAssignmentIsDecidedOnceByAnotherPerson() {
        FeeAssignmentProposal proposed = FeeAssignmentProposal.propose(IDS, CLOCK, MerchantId.of(IDS.next()),
                FeeScheduleId.of(IDS.next()), "contract pricing", PROPOSER, "flow");
        assertThat(proposed.status()).isEqualTo(FeeProposalStatus.PROPOSED);
        assertThatThrownBy(() -> proposed.decide(FeeProposalStatus.APPROVED, PROPOSER, "mine", CLOCK, "c"))
                .isInstanceOfSatisfying(FeeProposalRefused.class, refused -> assertThat(refused.reason())
                        .isEqualTo(FeeProposalRefused.Reason.SELF_DECISION));
        assertThatThrownBy(() -> proposed.decide(FeeProposalStatus.PROPOSED, CONTROLLER, "back", CLOCK, "c"))
                .as("PROPOSED is no decision").isInstanceOfSatisfying(FeeProposalRefused.class, refused ->
                        assertThat(refused.reason()).isEqualTo(FeeProposalRefused.Reason.ALREADY_DECIDED));
        FeeAssignmentProposal approved = proposed.decide(FeeProposalStatus.APPROVED, CONTROLLER, "reviewed",
                Clock.offset(CLOCK, Duration.ofMinutes(-5)), "c");
        assertThat(approved.decision()).hasValueSatisfying(decision ->
                assertThat(decision.decidedAt()).isEqualTo(proposed.proposedAt()));
        for (FeeProposalStatus again : FeeProposalStatus.values()) {
            assertThatThrownBy(() -> approved.decide(again, SECOND_CONTROLLER, "again", CLOCK, "c"))
                    .isInstanceOfSatisfying(FeeProposalRefused.class, refused -> assertThat(refused.reason())
                            .isEqualTo(FeeProposalRefused.Reason.ALREADY_DECIDED));
        }
        assertThatThrownBy(() -> new FeeAssignmentProposal(proposed.id(), proposed.merchantId(), proposed.feeScheduleId(),
                proposed.proposalReason(), proposed.proposedBy(), proposed.proposedByType(), proposed.proposedAt(),
                proposed.proposalCorrelationId(), FeeProposalStatus.REJECTED, Optional.of(new FeeProposalDecision(
                        PROPOSER.id(), "EMPLOYEE", CLOCK.instant(), "mine", "c"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a reason is 1 to 1000 characters holding no instrument shape; the refusal never repeats it")
    void aReasonIsScreened() {
        assertThat(FeeScheduleProposals.reasoned("the contract's pricing")).isEqualTo("the contract's pricing");
        for (String refused : new String[] {null, " ", "x".repeat(1001), "refund to 4111111111111111"}) {
            assertThatThrownBy(() -> FeeScheduleProposals.reasoned(refused))
                    .isInstanceOf(FeeScheduleProposals.ReasonRefused.class)
                    .satisfies(thrown -> assertThat(thrown.getMessage()).doesNotContain("4111"));
        }
    }
}
