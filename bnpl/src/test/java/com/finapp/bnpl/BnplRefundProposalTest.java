package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("an operator's BNPL refund above the threshold, by four eyes (P17-TSK-116)")
class BnplRefundProposalTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final Actor PROPOSER = new Actor("operator-1", ActorType.CUSTOMER);
    private static final Actor APPROVER = new Actor("controller-1", ActorType.CUSTOMER);

    @Test
    @DisplayName("born PROPOSED; decided once, never by its proposer (INV-AUD-04); an approval and only an approval names its"
            + " refund; never dated before its proposal")
    void decidedOnceByAnotherPerson() {
        BnplRefundProposal proposed = BnplRefundProposal.propose(IDS, CLOCK, BnplPurchaseId.of(IDS.next()),
                Money.ofMinorUnits(1_500_00, EUR), Money.ofMinorUnits(1_500_00, EUR), 1, "goods came back", PROPOSER,
                "flow");
        assertThatThrownBy(() -> proposed.requireDecidableBy(BnplRefundProposal.Status.APPROVED, PROPOSER))
                .isInstanceOfSatisfying(BnplRefundProposal.Refused.class, refused -> assertThat(refused.reason())
                        .isEqualTo(BnplRefundProposal.Refused.Reason.SELF_DECISION));
        UUID refund = IDS.next();
        BnplRefundProposal approved = proposed.decide(BnplRefundProposal.Status.APPROVED, APPROVER, "reviewed",
                Clock.offset(CLOCK, Duration.ofMinutes(-5)), "c", Optional.of(refund));
        assertThat(approved.refundId()).contains(refund);
        assertThat(approved.decision()).hasValueSatisfying(decision -> assertThat(decision.decidedAt())
                .isEqualTo(proposed.proposedAt()));
        assertThatThrownBy(() -> approved.requireDecidableBy(BnplRefundProposal.Status.REJECTED,
                new Actor("controller-2", ActorType.CUSTOMER))).isInstanceOfSatisfying(BnplRefundProposal.Refused.class,
                        refused -> assertThat(refused.reason()).isEqualTo(BnplRefundProposal.Refused.Reason.ALREADY_DECIDED));
        assertThatThrownBy(() -> proposed.decide(BnplRefundProposal.Status.APPROVED, APPROVER, "no refund", CLOCK, "c",
                Optional.empty())).as("an approval names its refund").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> proposed.decide(BnplRefundProposal.Status.REJECTED, APPROVER, "a refund", CLOCK, "c",
                Optional.of(refund))).as("a rejection names none").isInstanceOf(IllegalArgumentException.class);
    }
}
