package com.finapp.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("a merchant's BNPL fee assignment by four eyes (P17-TSK-116)")
class BnplFeeAssignmentProposalTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Actor PROPOSER = new Actor("administrator-1", ActorType.CUSTOMER);
    private static final Actor CONTROLLER = new Actor("controller-1", ActorType.CUSTOMER);

    @Test
    @DisplayName("born PROPOSED; decided once, never by its proposer (INV-AUD-04), never dated before its proposal")
    void decidedOnceByAnotherPerson() {
        BnplFeeAssignmentProposal proposed = BnplFeeAssignmentProposal.propose(IDS, CLOCK, MerchantId.of(IDS.next()),
                MerchantBnplProduct.BNPL_PAY_IN_4, FeeScheduleId.of(IDS.next()), "contract pricing", PROPOSER, "flow");
        assertThat(proposed.status()).isEqualTo(BnplFeeAssignmentProposal.Status.PROPOSED);
        assertThatThrownBy(() -> proposed.decide(BnplFeeAssignmentProposal.Status.APPROVED, PROPOSER, "mine", CLOCK, "c"))
                .isInstanceOfSatisfying(BnplFeeAssignmentProposal.Refused.class, refused -> assertThat(refused.reason())
                        .isEqualTo(BnplFeeAssignmentProposal.Refused.Reason.SELF_DECISION));
        Clock behind = Clock.offset(CLOCK, Duration.ofMinutes(-5));
        BnplFeeAssignmentProposal approved = proposed.decide(BnplFeeAssignmentProposal.Status.APPROVED, CONTROLLER,
                "reviewed", behind, "c");
        assertThat(approved.decision()).hasValueSatisfying(decision -> {
            assertThat(decision.decidedBy()).isEqualTo(CONTROLLER.id());
            assertThat(decision.decidedAt()).as("another instance's clock behind").isEqualTo(proposed.proposedAt());
        });
        for (BnplFeeAssignmentProposal.Status again : BnplFeeAssignmentProposal.Status.values()) {
            assertThatThrownBy(() -> approved.decide(again, new Actor("controller-2", ActorType.CUSTOMER), "again", CLOCK,
                    "c")).isInstanceOfSatisfying(BnplFeeAssignmentProposal.Refused.class, refused -> assertThat(
                            refused.reason()).isEqualTo(BnplFeeAssignmentProposal.Refused.Reason.ALREADY_DECIDED));
        }
        assertThatThrownBy(() -> new BnplFeeAssignmentProposal(proposed.id(), proposed.merchantId(), proposed.product(),
                proposed.feeScheduleId(), proposed.proposalReason(), proposed.proposedBy(), proposed.proposedByType(),
                proposed.proposedAt(), proposed.proposalCorrelationId(), BnplFeeAssignmentProposal.Status.REJECTED,
                java.util.Optional.of(new BnplFeeAssignmentProposal.Decision(PROPOSER.id(), "CUSTOMER", CLOCK.instant(),
                        "mine", "c")))).isInstanceOf(IllegalArgumentException.class);
    }
}
