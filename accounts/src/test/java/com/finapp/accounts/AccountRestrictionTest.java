package com.finapp.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("a customer agreement's restriction: the kinds, the machine, four eyes (P17-TSK-028, ADR-0153)")
class AccountRestrictionTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final Actor ANALYST = new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
    private static final Actor OFFICER = new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);

    @Test
    @DisplayName("the kinds are the two edges the agreement's machine has declared since Phase 3, and no other")
    void theKindsAreTheMachinesEdges() {
        assertThat(RestrictionKind.SUSPENSION.from()).isEqualTo(CustomerAccountStatus.ACTIVE);
        assertThat(RestrictionKind.SUSPENSION.to()).isEqualTo(CustomerAccountStatus.SUSPENDED);
        assertThat(RestrictionKind.REINSTATEMENT.from()).isEqualTo(CustomerAccountStatus.SUSPENDED);
        assertThat(RestrictionKind.REINSTATEMENT.to()).isEqualTo(CustomerAccountStatus.ACTIVE);
        for (RestrictionKind kind : RestrictionKind.values()) {
            assertThat(kind.from().canTransitionTo(kind.to())).as(kind.name()).isTrue();
        }
    }

    @Test
    @DisplayName("a restriction is decided once: PROPOSED to APPROVED or REJECTED, both terminal")
    void theMachineDecidesOnce() {
        assertThat(RestrictionStatus.PROPOSED.permittedTransitions())
                .containsExactlyInAnyOrder(RestrictionStatus.APPROVED, RestrictionStatus.REJECTED);
        assertThat(EnumSet.allOf(RestrictionStatus.class).stream().filter(RestrictionStatus::isTerminal))
                .containsExactlyInAnyOrder(RestrictionStatus.APPROVED, RestrictionStatus.REJECTED);
    }

    @Test
    @DisplayName("every reason code explains exactly one kind, and each kind has codes")
    void reasonCodesBelongToOneKind() {
        for (RestrictionKind kind : RestrictionKind.values()) {
            assertThat(Arrays.stream(RestrictionReason.values()).filter(reason -> reason.kind() == kind)).as(kind.name())
                    .isNotEmpty();
        }
        assertThatThrownBy(() -> AccountRestriction.propose(IDS, CLOCK, account(CustomerAccountStatus.ACTIVE),
                        RestrictionKind.SUSPENSION, RestrictionReason.INVESTIGATION_CLEARED, "cleared", ANALYST, "c"))
                .isInstanceOfSatisfying(AccountRestrictionRefusedException.class,
                        refused -> assertThat(refused.reason()).isEqualTo(AccountRestrictionRefusedException.Reason.REASON_INVALID));
    }

    @Test
    @DisplayName("a suspension applies to an ACTIVE agreement only, a reinstatement to a SUSPENDED one only")
    void aKindAppliesFromItsStatusOnly() {
        for (CustomerAccountStatus status : CustomerAccountStatus.values()) {
            for (RestrictionKind kind : RestrictionKind.values()) {
                RestrictionReason code = Arrays.stream(RestrictionReason.values()).filter(reason -> reason.kind() == kind)
                        .findFirst().orElseThrow();
                if (status == kind.from()) {
                    assertThat(AccountRestriction.propose(IDS, CLOCK, account(status), kind, code, "why", ANALYST, "c")
                            .status()).isEqualTo(RestrictionStatus.PROPOSED);
                } else {
                    assertThatThrownBy(() -> AccountRestriction.propose(IDS, CLOCK, account(status), kind, code, "why",
                                    ANALYST, "c"))
                            .as(kind + " of a " + status + " agreement")
                            .isInstanceOfSatisfying(AccountRestrictionRefusedException.class, refused -> assertThat(
                                    refused.reason()).isEqualTo(AccountRestrictionRefusedException.Reason.NOT_APPLICABLE));
                }
            }
        }
    }

    @Test
    @DisplayName("four eyes: the proposer never decides, an approval or a rejection (INV-AUD-04); another person does, once")
    void fourEyes() {
        AccountRestriction proposed = AccountRestriction.propose(IDS, CLOCK, account(CustomerAccountStatus.ACTIVE),
                RestrictionKind.SUSPENSION, RestrictionReason.FRAUD_SUSPECTED, "card testing seen", ANALYST, "c");
        for (RestrictionStatus outcome : EnumSet.of(RestrictionStatus.APPROVED, RestrictionStatus.REJECTED)) {
            assertThatThrownBy(() -> proposed.decide(outcome, ANALYST, "mine", CLOCK, "c2"))
                    .isInstanceOfSatisfying(AccountRestrictionRefusedException.class,
                            refused -> assertThat(refused.reason()).isEqualTo(AccountRestrictionRefusedException.Reason.SELF_DECISION));
        }
        AccountRestriction approved = proposed.decide(RestrictionStatus.APPROVED, OFFICER, "agreed", CLOCK, "c2");
        assertThat(approved.status()).isEqualTo(RestrictionStatus.APPROVED);
        assertThat(approved.decision().orElseThrow().decidedBy()).isEqualTo(OFFICER.id());
        assertThatThrownBy(() -> approved.decide(RestrictionStatus.REJECTED, OFFICER, "again", CLOCK, "c3"))
                .isInstanceOfSatisfying(AccountRestrictionRefusedException.class,
                        refused -> assertThat(refused.reason()).isEqualTo(AccountRestrictionRefusedException.Reason.ALREADY_DECIDED));
    }

    @Test
    @DisplayName("a decider's instance running behind the proposer's dates the decision at the proposal, never before it"
            + " (N instances, N clocks)")
    void aDecisionIsNeverDatedBeforeItsProposal() {
        AccountRestriction proposed = AccountRestriction.propose(IDS, CLOCK, account(CustomerAccountStatus.ACTIVE),
                RestrictionKind.SUSPENSION, RestrictionReason.FRAUD_SUSPECTED, "card testing seen", ANALYST, "c");
        Clock behind = Clock.offset(CLOCK, java.time.Duration.ofSeconds(-3));
        assertThat(proposed.decide(RestrictionStatus.APPROVED, OFFICER, "agreed", behind, "c2").decision().orElseThrow()
                .decidedAt()).isEqualTo(proposed.proposedAt());
        Clock ahead = Clock.offset(CLOCK, java.time.Duration.ofSeconds(3));
        assertThat(proposed.decide(RestrictionStatus.APPROVED, OFFICER, "agreed", ahead, "c2").decision().orElseThrow()
                .decidedAt()).isEqualTo(proposed.proposedAt().plusSeconds(3));
    }

    @Test
    @DisplayName("a reason is required, bounded and never holds a card number")
    void reasonsAreScreened() {
        CustomerAccount active = account(CustomerAccountStatus.ACTIVE);
        for (String bad : new String[] {null, " ", "x".repeat(AccountRestriction.REASON_BOUND + 1), "card 4111111111111111"}) {
            assertThatThrownBy(() -> AccountRestriction.propose(IDS, CLOCK, active, RestrictionKind.SUSPENSION,
                            RestrictionReason.FRAUD_SUSPECTED, bad, ANALYST, "c"))
                    .as(String.valueOf(bad).length() > 40 ? "too long" : String.valueOf(bad))
                    .isInstanceOf(AccountRestrictionRefusedException.class);
        }
    }

    private static CustomerAccount account(CustomerAccountStatus status) {
        CustomerAccount opened = CustomerAccount.open(IDS, CLOCK, UUID.randomUUID(), ProductType.WALLET);
        return switch (status) {
            case ACTIVE -> opened;
            case SUSPENDED -> opened.moveTo(CustomerAccountStatus.SUSPENDED, CLOCK);
            case CLOSED -> opened.moveTo(CustomerAccountStatus.CLOSED, CLOCK);
            case PENDING -> CustomerAccount.rehydrate(opened.id(), opened.customerId(), ProductType.WALLET,
                    CustomerAccountStatus.PENDING, opened.openedAt(), opened.statusChangedAt());
        };
    }
}
