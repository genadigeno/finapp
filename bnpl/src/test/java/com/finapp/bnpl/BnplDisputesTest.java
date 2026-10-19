package com.finapp.bnpl;

import static com.finapp.bnpl.BnplConditionWorld.CLOCK;
import static com.finapp.bnpl.BnplConditionWorld.CORRELATION;
import static com.finapp.bnpl.BnplConditionWorld.DUE_2;
import static com.finapp.bnpl.BnplConditionWorld.DUE_3;
import static com.finapp.bnpl.BnplConditionWorld.IDS;
import static com.finapp.bnpl.BnplConditionWorld.SYSTEM;
import static com.finapp.bnpl.BnplConditionWorld.eur;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.Direction;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.money.Money;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-dsp, hermetic (`P12-TSK-023`; ADR-0106; {@code PHASE_12_PLAN.md} sections 5.8, 7.1, 7.2, 12.7 and 12.8;
 * {@code INV-BNPL-08}, {@code -07}, {@code -06}, {@code INV-LIFE-01}...{@code -04}, {@code INV-AUD-04}, {@code INV-MER-01}):
 * a customer disputes a BNPL purchase; the instalments a refund of the disputed amount would cancel - latest first - are
 * held from collection, days past due and late fees; the merchant accepts (a refund at once), answers (an operator resolves,
 * four-eyes above the pinned threshold), or stays silent past the response deadline (a refund, the system's); the customer
 * may withdraw. A refund outcome is T-ref's body under the purchase's one bound, so a dispute and a merchant refund never
 * credit the customer more than the gross (scenario 10). Over {@link BnplConditionWorld} - the in-memory {@code bnpl V006}
 * ... {@code V015} with the late fee - its hold ranks dropped where a case proves the domain alone keeps the hold. The
 * races across instances and the database's own ranks are {@code BnplDisputeDatabaseTest}'s.
 *
 * <p>The figures: pay-in-4, {@code G = 600.00}, four instalments of 150.00 - instalment 1 paid at purchase on 2026-10-17,
 * then due 2026-10-31, 2026-11-14 and 2026-11-28; the pinned window 14 days, the four-eyes threshold 250.00, the late fee
 * 5.00 on day 5.
 */
@DisplayName("BNPL disputes: the hold, the deadline, the resolution and the one bound (P12-TSK-023)")
class BnplDisputesTest {

    private static final Actor OPERATOR_A = new Actor("operator-a", ActorType.CUSTOMER);
    private static final Actor OPERATOR_B = new Actor("operator-b", ActorType.CUSTOMER);

    private BnplConditionWorld world;
    private BnplDisputes disputes;
    private final Map<String, UUID> operatorParties = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        world = new BnplConditionWorld();
        disputes = world.fakes.disputesOver(world.refunds,
                (uow, actor) -> Optional.ofNullable(operatorParties.get(actor.id())), IDS, CLOCK);
    }

    // ------------------------------------------------------------------ the opening and the hold

    @Test
    @DisplayName("an opening holds the instalments a refund of D would cancel, latest first - 300 holds 4 and 3 whole;"
            + " the deadline is the opening plus the pinned 14 days on the database clock; nothing is posted")
    void theOpeningPlacesTheHold() {
        BnplConditionWorld.Agreement p4 = world.payIn4();
        int lines = world.fakes.book.linesOf("").size();
        BnplDisputes.Acted opened = openAt("2026-10-20T12:00:00Z", p4, "open-1", 300_00);
        BnplDispute dispute = opened.dispute();
        assertThat(dispute.status()).isEqualTo(BnplDisputeStatus.OPEN);
        assertThat(dispute.holds()).extracting(held -> held.sequence() + " " + held.kind() + " "
                + held.principal().minorUnits()).containsExactly("4 UNBILLED 15000", "3 UNBILLED 15000");
        assertThat(dispute.responseDeadline()).contains(Instant.parse("2026-11-03T12:00:00Z"));
        assertThat(dispute.openedOn()).contains(LocalDate.of(2026, 10, 20));
        assertThat(dispute.refundedBefore()).isEqualTo(eur(0));
        assertThat(world.fakes.book.linesOf("").size()).as("a dispute posts nothing").isEqualTo(lines);
        assertThat(world.fakes.events).extracting(EventEnvelope::eventType).contains(BnplDisputes.OPENED_EVENT);
        assertThat(audited(BnplAuditAction.DISPUTE_OPENED)).singleElement()
                .satisfies(record -> assertThat(record.outcome()).isEqualTo(AuditOutcome.SUCCEEDED));
        assertThat(world.fakes.disputes.history).extracting(event -> event.from() + " " + event.to())
                .containsExactly("Optional.empty OPEN");
    }

    @Test
    @DisplayName("the hold covers the latest instalments exactly - 200 holds all of 4 and 50 of 3 - the same walk as the"
            + " refund allocator's, so a refund outcome cancels exactly what was held")
    void theHoldCoversTheLatestInstalments() {
        BnplConditionWorld.Agreement p4 = world.payIn4();
        BnplDispute dispute = openAt("2026-10-20T12:00:00Z", p4, "open-1", 200_00).dispute();
        assertThat(dispute.holds()).extracting(held -> held.sequence() + " " + held.principal().minorUnits())
                .containsExactly("4 15000", "3 5000");
        BnplDisputes.Acted accepted = respondAt("2026-10-22T09:00:00Z", dispute, BnplDisputes.Decision.ACCEPT);
        assertThat(accepted.refund().orElseThrow().refund().adjustments())
                .extracting(adjustment -> adjustment.sequence() + " " + adjustment.principalCancelled().minorUnits())
                .containsExactly("4 15000", "3 5000");
    }

    @Test
    @DisplayName("the hold suspends collection, DPD and fees: with 450 disputed, instalment 2 is billed on its date but"
            + " never collected, never past due and never charged late - the domain alone, the database's ranks dropped")
    void theHoldSuspendsCollectionDpdAndFees() {
        world.withFees(BnplTermsFixtures.payIn4().autoCollection(true));
        BnplConditionWorld.Agreement p4 = world.payIn4();
        openAt("2026-10-20T12:00:00Z", p4, "open-1", 450_00);
        world.fakes.collections.holdRankDropped = true;
        world.fakes.conditions.holdRankDropped = true;
        world.serviceOn(DUE_2, p4);
        assertThat(world.fakes.purchases.billedOn(p4.obligation(), DUE_2)).as("billing keeps the contract").isTrue();
        assertThat(world.fakes.collections.rows).as("no attempt on a held instalment").isEmpty();
        world.serviceOn(DUE_2.plusDays(6), p4);
        assertThat(world.fees(p4)).as("no late fee on a held instalment").isEmpty();
        assertThat(world.history(p4)).as("never past due").isEmpty();
        assertThat(world.conditions.derive(BnplApplicationFakes.UOW, p4.obligation(), pinned(p4),
                DUE_2.plusDays(6)).daysPastDue()).isZero();
    }

    @Test
    @DisplayName("the same purchase undisputed is collected on instalment 2's date - the hold is what stopped it")
    void withoutTheHoldTheInstalmentIsCollected() {
        world.withFees(BnplTermsFixtures.payIn4().autoCollection(true));
        BnplConditionWorld.Agreement p4 = world.payIn4();
        world.serviceOn(DUE_2, p4);
        assertThat(world.fakes.collections.rows).extracting(BnplCollectionAttempt::outcome)
                .containsExactly(CollectionOutcome.COLLECTED);
    }

    @Test
    @DisplayName("a partial hold suspends only the held instalments: with 200 disputed (4 and part of 3), instalment 2 is"
            + " collected on its date and instalment 3 is not")
    void thePartialHoldCollectsTheUndisputed() {
        world.withFees(BnplTermsFixtures.payIn4().autoCollection(true));
        BnplConditionWorld.Agreement p4 = world.payIn4();
        openAt("2026-10-20T12:00:00Z", p4, "open-1", 200_00);
        world.fakes.collections.holdRankDropped = true;
        world.serviceOn(DUE_2, p4);
        world.serviceOn(DUE_3, p4);
        assertThat(world.fakes.collections.rows).extracting(attempt -> attempt.attemptDate() + " " + attempt.outcome())
                .containsExactly(DUE_2 + " COLLECTED");
    }

    @Test
    @DisplayName("the database's rank refuses a collection of a held instalment and a fee on it for every writer (bnpl"
            + " V015's triggers, mirrored)")
    void theHoldRanksRefuseEveryWriter() {
        BnplConditionWorld.Agreement p4 = world.payIn4();
        openAt("2026-10-20T12:00:00Z", p4, "open-1", 450_00);
        world.serviceOn(DUE_2, p4);
        BnplPurchaseStore.BilledItem second = world.fakes.purchases.billedItems(BnplApplicationFakes.UOW,
                p4.obligation()).stream().filter(item -> item.dueDate().equals(DUE_2)).findFirst().orElseThrow();
        world.fakes.now = DUE_2.plusDays(6).atTime(9, 0).toInstant(ZoneOffset.UTC);
        BnplAgreement agreement = world.fakes.purchases.agreements.get(p4.agreement().value());
        assertThatThrownBy(() -> world.fakes.collections.insertAttempt(BnplApplicationFakes.UOW,
                new BnplCollectionAttempt(IDS.next(), p4.obligation(), second.instalment(), DUE_2.plusDays(6),
                        CollectionOutcome.INSUFFICIENT_FUNDS, eur(150_00), eur(0), agreement.version(), Optional.empty(),
                        CORRELATION)))
                .hasMessageContaining("never collected automatically");
        assertThatThrownBy(() -> world.fakes.conditions.insertAssessment(BnplApplicationFakes.UOW,
                new BnplFeeAssessment(IDS.next(), p4.obligation(), second.instalment(), BnplFeeKind.LATE,
                        DUE_2.plusDays(6), eur(5_00), p4.agreement(), agreement.version(),
                        com.finapp.ledger.JournalEntryId.of(IDS.next()), CORRELATION)))
                .hasMessageContaining("held instalment");
    }

    // ------------------------------------------------------------------ the lift

    @Test
    @DisplayName("upheld lifts the hold and re-bills nothing: no refund, no schedule version; the held instalment's DPD and"
            + " its late-fee day count from the lift's business date, never retroactively")
    void upheldLiftsTheHoldAndReBillsNothing() {
        BnplConditionWorld.Agreement p4 = world.payIn4();
        BnplDispute dispute = openAt("2026-10-20T12:00:00Z", p4, "open-1", 200_00).dispute();
        respondAt("2026-10-22T09:00:00Z", dispute, BnplDisputes.Decision.CONTEST);
        world.serviceOn(DUE_2, p4);
        world.repayEverythingDueOn(DUE_2, p4); // instalment 2 paid - only the held 3 can fall behind
        world.serviceOn(DUE_3, p4);
        int versions = world.fakes.purchases.schedules.get(p4.obligation().value()).version();
        LocalDate lift = LocalDate.of(2026, 11, 20);
        BnplDisputes.Acted upheld = resolveAt(lift.atTime(10, 0).toInstant(ZoneOffset.UTC), dispute, OPERATOR_A,
                BnplDisputeStatus.RESOLVED_UPHELD);
        assertThat(upheld.dispute().status()).isEqualTo(BnplDisputeStatus.RESOLVED_UPHELD);
        assertThat(upheld.dispute().resolvedOn()).contains(lift);
        assertThat(upheld.refund()).isEmpty();
        assertThat(world.fakes.refunds.rows).isEmpty();
        assertThat(world.fakes.purchases.schedules.get(p4.obligation().value()).version()).isEqualTo(versions);
        assertThat(world.conditions.derive(BnplApplicationFakes.UOW, p4.obligation(), pinned(p4), lift).daysPastDue())
                .as("6 days after its due date, but counted from the lift").isZero();
        assertThat(world.conditions.derive(BnplApplicationFakes.UOW, p4.obligation(), pinned(p4), lift.plusDays(3))
                .daysPastDue()).isEqualTo(3);
        world.fakes.conditions.holdRankDropped = true;
        world.serviceOn(lift.plusDays(4), p4);
        assertThat(world.fees(p4)).as("day 4 from the lift: no fee yet").isEmpty();
        world.serviceOn(lift.plusDays(5), p4);
        assertThat(world.fees(p4)).containsExactly("3 " + lift.plusDays(5) + " 500");
    }

    @Test
    @DisplayName("a withdrawal lifts the hold: WITHDRAWN by its customer, nothing posted, DPD from the withdrawal's date")
    void aWithdrawalLiftsTheHold() {
        BnplConditionWorld.Agreement p4 = world.payIn4();
        BnplDispute dispute = openAt("2026-10-20T12:00:00Z", p4, "open-1", 450_00).dispute();
        world.serviceOn(DUE_2, p4);
        LocalDate lift = DUE_2.plusDays(10);
        BnplDisputes.Acted withdrawn = withdrawAt(lift.atTime(8, 0).toInstant(ZoneOffset.UTC), dispute);
        assertThat(withdrawn.dispute().status()).isEqualTo(BnplDisputeStatus.WITHDRAWN);
        assertThat(withdrawn.dispute().resolvedBy()).contains("CUSTOMER:" + world.party);
        assertThat(world.fakes.refunds.rows).isEmpty();
        assertThat(world.conditions.derive(BnplApplicationFakes.UOW, p4.obligation(), pinned(p4), lift.plusDays(2))
                .daysPastDue()).isEqualTo(2);
        assertThatThrownBy(() -> world.fakes.inTransaction(() -> disputes.withdraw(BnplApplicationFakes.UOW,
                dispute.id(), UUID.randomUUID(), world.customer, CORRELATION)))
                .as("another party's dispute is the same 404").isInstanceOf(BnplDisputes.DisputeNotFound.class);
        assertThat(withdrawAt(lift.atTime(9, 0).toInstant(ZoneOffset.UTC), dispute).replayed())
                .as("a retried withdrawal converges").isTrue();
        assertThat(audited(BnplAuditAction.DISPUTE_WITHDRAWN)).hasSize(1);
    }

    // ------------------------------------------------------------------ the merchant and the deadline

    @Test
    @DisplayName("the merchant's acceptance is a refund of exactly D at once: door DISPUTE, the held instalments"
            + " cancelled, the fee returned pro rata, the payable debited, every entry balanced")
    void theMerchantsAcceptanceIsARefund() {
        BnplConditionWorld.Agreement p4 = world.payIn4();
        BnplDispute dispute = openAt("2026-10-20T12:00:00Z", p4, "open-1", 300_00).dispute();
        BnplDisputes.Acted accepted = respondAt("2026-10-21T09:00:00Z", dispute, BnplDisputes.Decision.ACCEPT);
        BnplRefund refund = accepted.refund().orElseThrow().refund();
        assertThat(accepted.dispute().status()).isEqualTo(BnplDisputeStatus.RESOLVED_REFUND);
        assertThat(accepted.dispute().resolvedBy()).contains("MERCHANT:" + world.merchant);
        assertThat(accepted.dispute().refund()).contains(refund.id());
        assertThat(refund.door()).isEqualTo(BnplRefund.Door.DISPUTE);
        assertThat(refund.dispute()).contains(dispute.id().value());
        assertThat(refund.amount()).isEqualTo(eur(300_00));
        assertThat(refund.cancelledUnbilled()).isEqualTo(eur(300_00));
        assertThat(refund.excess()).isEqualTo(eur(0));
        assertThat(balanced(BnplRefunds.POSTING_KEY_PREFIX + refund.id())).isTrue();
        assertThat(world.fakes.disputes.holdOf(p4.obligation()).heldOn(LocalDate.of(2026, 11, 28), LocalDate.of(2026, 10, 22)))
                .as("the hold lifted").isFalse();
        assertThat(audited(BnplAuditAction.DISPUTE_RESPONDED)).hasSize(1);
        assertThat(audited(BnplAuditAction.DISPUTE_RESOLVED)).hasSize(1);
        assertThat(world.fakes.events).extracting(EventEnvelope::eventType)
                .contains(BnplDisputes.RESOLVED_EVENT, BnplRefunds.APPLIED_EVENT);
    }

    @Test
    @DisplayName("merchant silence resolves as a refund at the deadline: nothing is claimed before it; at it the sweep's"
            + " step resolves RESOLVED_REFUND by SYSTEM, once - a second step and a second claim find nothing")
    void merchantSilenceResolvesAsARefundAtTheDeadline() {
        BnplConditionWorld.Agreement p4 = world.payIn4();
        BnplDispute dispute = openAt("2026-10-20T12:00:00Z", p4, "open-1", 300_00).dispute();
        world.fakes.now = Instant.parse("2026-11-03T11:59:59Z");
        assertThat(world.fakes.inTransaction(() -> disputes.claim(BnplApplicationFakes.UOW, 10, Duration.ofMinutes(15))))
                .isEmpty();
        assertThat(lapseAt("2026-11-03T11:59:59Z", dispute)).as("the step re-judges the deadline").isEmpty();
        world.fakes.now = Instant.parse("2026-11-03T12:00:00Z");
        assertThat(world.fakes.inTransaction(() -> disputes.claim(BnplApplicationFakes.UOW, 10, Duration.ofMinutes(15))))
                .containsExactly(dispute.id());
        BnplDisputes.Acted lapsed = lapseAt("2026-11-03T12:00:00Z", dispute).orElseThrow();
        assertThat(lapsed.dispute().status()).isEqualTo(BnplDisputeStatus.RESOLVED_REFUND);
        assertThat(lapsed.dispute().resolvedBy()).contains(BnplDispute.SYSTEM);
        assertThat(lapsed.refund().orElseThrow().refund().amount()).isEqualTo(eur(300_00));
        assertThat(audited(BnplAuditAction.DISPUTE_RESOLVED)).singleElement()
                .satisfies(record -> assertThat(record.actor().type()).isEqualTo(ActorType.SYSTEM));
        assertThat(lapseAt("2026-11-03T12:00:01Z", dispute)).isEmpty();
        world.fakes.now = Instant.parse("2026-11-04T12:00:00Z");
        assertThat(world.fakes.inTransaction(() -> disputes.claim(BnplApplicationFakes.UOW, 10, Duration.ofMinutes(15))))
                .isEmpty();
        assertThat(world.fakes.refunds.rows).hasSize(1);
    }

    @Test
    @DisplayName("a late acceptance at the deadline resolves once: the merchant at the deadline instant is refused with"
            + " nothing written, the sweep resolves it; an acceptance a second before wins and the sweep finds nothing")
    void aLateAcceptanceAtTheDeadlineResolvesOnce() {
        BnplConditionWorld.Agreement late = world.payIn4();
        BnplDispute atDeadline = openAt("2026-10-20T12:00:00Z", late, "open-1", 300_00).dispute();
        assertThatThrownBy(() -> respondAt("2026-11-03T12:00:00Z", atDeadline, BnplDisputes.Decision.ACCEPT))
                .isInstanceOf(BnplDisputes.DisputeNotActionable.class);
        assertThat(world.fakes.refunds.rows).isEmpty();
        assertThat(lapseAt("2026-11-03T12:00:00Z", atDeadline)).isPresent();

        BnplConditionWorld.Agreement early = world.payIn4();
        BnplDispute beforeIt = openAt("2026-10-20T12:00:00Z", early, "open-2", 300_00).dispute();
        respondAt("2026-11-03T11:59:59Z", beforeIt, BnplDisputes.Decision.ACCEPT);
        assertThat(lapseAt("2026-11-03T12:00:00Z", beforeIt)).isEmpty();
        assertThat(world.fakes.refunds.rows.values()).extracting(refund -> refund.dispute().orElseThrow())
                .containsExactlyInAnyOrder(atDeadline.id().value(), beforeIt.id().value());
    }

    @Test
    @DisplayName("the merchant answers only its own disputes (another merchant's is the same 404) and only once: a retried"
            + " contest converges, a contest after the deadline is refused")
    void theMerchantsDoorIsItsOwn() {
        BnplConditionWorld.Agreement p4 = world.payIn4();
        BnplDispute dispute = openAt("2026-10-20T12:00:00Z", p4, "open-1", 300_00).dispute();
        world.fakes.now = Instant.parse("2026-10-21T09:00:00Z");
        assertThatThrownBy(() -> world.fakes.inTransaction(() -> disputes.respond(BnplApplicationFakes.UOW, dispute.id(),
                UUID.randomUUID(), new BnplDisputes.Response(BnplDisputes.Decision.ACCEPT, "fine", "key-x"),
                world.merchantActor, CORRELATION))).isInstanceOf(BnplDisputes.DisputeNotFound.class);
        BnplDisputes.Acted contested = respondAt("2026-10-21T09:00:00Z", dispute, BnplDisputes.Decision.CONTEST);
        assertThat(contested.dispute().status()).isEqualTo(BnplDisputeStatus.MERCHANT_RESPONDED);
        assertThat(contested.dispute().merchantRespondedAt()).isPresent();
        assertThat(respondAt("2026-10-21T09:05:00Z", dispute, BnplDisputes.Decision.CONTEST).replayed()).isTrue();
        assertThatThrownBy(() -> respondAt("2026-10-21T09:06:00Z", dispute, BnplDisputes.Decision.ACCEPT))
                .isInstanceOf(BnplDisputes.DisputeNotActionable.class);
        assertThat(lapseAt("2026-11-04T12:00:00Z", dispute)).as("an answered dispute never lapses").isEmpty();
    }

    // ------------------------------------------------------------------ the operator

    @Test
    @DisplayName("ten resolvers, one resolution: the first operator's refund commits; the nine after it are refused or"
            + " converge, and one refund exists")
    void tenResolversOneResolution() {
        BnplConditionWorld.Agreement p4 = world.payIn4();
        BnplDispute dispute = openAt("2026-10-20T12:00:00Z", p4, "open-1", 200_00).dispute();
        respondAt("2026-10-21T09:00:00Z", dispute, BnplDisputes.Decision.CONTEST);
        List<String> answers = new ArrayList<>();
        for (int resolver = 0; resolver < 10; resolver++) {
            Actor operator = new Actor("operator-" + resolver, ActorType.CUSTOMER);
            try {
                resolveAt(Instant.parse("2026-10-25T10:00:00Z"), dispute, operator, BnplDisputeStatus.RESOLVED_REFUND);
                answers.add("resolved");
            } catch (BnplDisputes.DisputeNotActionable refused) {
                answers.add("refused");
            }
        }
        assertThat(answers).containsExactly("resolved", "refused", "refused", "refused", "refused", "refused", "refused",
                "refused", "refused", "refused");
        assertThat(world.fakes.refunds.rows).hasSize(1);
        assertThat(resolveAt(Instant.parse("2026-10-25T10:01:00Z"), dispute, new Actor("operator-0", ActorType.CUSTOMER),
                BnplDisputeStatus.RESOLVED_REFUND).replayed()).as("its own retry converges").isTrue();
    }

    @Test
    @DisplayName("four-eyes above the threshold: 300 > 250 is proposed, its proposer's approval refused with nothing written"
            + " and recorded FAILED in its own transaction, a second operator's approval resolves; a second proposal waits")
    void selfApprovalRefusedAndAudited() {
        BnplConditionWorld.Agreement p4 = world.payIn4();
        BnplDispute dispute = openAt("2026-10-20T12:00:00Z", p4, "open-1", 300_00).dispute();
        respondAt("2026-10-21T09:00:00Z", dispute, BnplDisputes.Decision.CONTEST);
        BnplDisputes.Acted proposed = resolveAt(Instant.parse("2026-10-25T10:00:00Z"), dispute, OPERATOR_A,
                BnplDisputeStatus.RESOLVED_REFUND);
        assertThat(proposed.outcome()).isEqualTo(BnplDisputes.Outcome.PROPOSED);
        assertThat(proposed.dispute().status()).isEqualTo(BnplDisputeStatus.MERCHANT_RESPONDED);
        UUID proposal = proposed.proposal().orElseThrow().id();
        assertThatThrownBy(() -> resolveAt(Instant.parse("2026-10-25T10:01:00Z"), dispute, OPERATOR_B,
                BnplDisputeStatus.RESOLVED_UPHELD)).isInstanceOf(BnplDisputes.ProposalPending.class);
        world.fakes.now = Instant.parse("2026-10-25T11:00:00Z");
        BnplDisputes.SelfApprovalRefused refused = org.junit.jupiter.api.Assertions.assertThrows(
                BnplDisputes.SelfApprovalRefused.class, () -> world.fakes.inTransaction(() -> disputes.approve(
                        BnplApplicationFakes.UOW, dispute.id(), proposal, "looks right", OPERATOR_A, CORRELATION)));
        assertThat(world.fakes.refunds.rows).as("nothing written").isEmpty();
        world.fakes.inTransaction(() -> {
            disputes.recordSelfApproval(BnplApplicationFakes.UOW, refused, OPERATOR_A, CORRELATION);
            return null;
        });
        assertThat(audited(BnplAuditAction.SELF_APPROVAL_REFUSED)).singleElement()
                .satisfies(record -> assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED));
        BnplDisputes.Acted approved = world.fakes.inTransaction(() -> disputes.approve(BnplApplicationFakes.UOW,
                dispute.id(), proposal, "the evidence is thin", OPERATOR_B, CORRELATION));
        assertThat(approved.dispute().status()).isEqualTo(BnplDisputeStatus.RESOLVED_REFUND);
        assertThat(approved.dispute().proposal()).contains(proposal);
        assertThat(approved.dispute().resolvedBy()).contains("OPERATOR:operator-b");
        assertThat(approved.proposal().orElseThrow().status()).isEqualTo(BnplDisputeProposal.Status.APPROVED);
        assertThat(world.fakes.refunds.rows).hasSize(1);
    }

    @Test
    @DisplayName("the four-eyes rank refuses a proposer's own approval for every writer (the CHECK, mirrored): a resolution"
            + " above the threshold without another's approval is refused at the edge")
    void theFourEyesRankRefusesEveryWriter() {
        BnplConditionWorld.Agreement p4 = world.payIn4();
        BnplDispute dispute = openAt("2026-10-20T12:00:00Z", p4, "open-1", 300_00).dispute();
        respondAt("2026-10-21T09:00:00Z", dispute, BnplDisputes.Decision.CONTEST);
        BnplDispute answered = world.fakes.disputes.rows.get(dispute.id().value());
        world.fakes.now = Instant.parse("2026-10-25T10:00:00Z");
        assertThatThrownBy(() -> world.fakes.disputes.move(BnplApplicationFakes.UOW, answered.resolved(
                BnplDisputeStatus.RESOLVED_UPHELD, "OPERATOR:operator-a", Optional.empty(), Optional.of("one person"),
                Optional.empty(), Optional.empty()), BnplDisputeStatus.MERCHANT_RESPONDED))
                .hasMessageContaining("another operator approved");
    }

    @Test
    @DisplayName("a rejection leaves the dispute awaiting a new proposal; the proposer may withdraw its own")
    void aRejectionReopensTheProposal() {
        BnplConditionWorld.Agreement p4 = world.payIn4();
        BnplDispute dispute = openAt("2026-10-20T12:00:00Z", p4, "open-1", 300_00).dispute();
        respondAt("2026-10-21T09:00:00Z", dispute, BnplDisputes.Decision.CONTEST);
        UUID first = resolveAt(Instant.parse("2026-10-25T10:00:00Z"), dispute, OPERATOR_A,
                BnplDisputeStatus.RESOLVED_UPHELD).proposal().orElseThrow().id();
        BnplDisputes.Acted withdrawn = world.fakes.inTransaction(() -> disputes.reject(BnplApplicationFakes.UOW,
                dispute.id(), first, "proposed in error", OPERATOR_A, CORRELATION));
        assertThat(withdrawn.proposal().orElseThrow().status()).isEqualTo(BnplDisputeProposal.Status.REJECTED);
        assertThat(resolveAt(Instant.parse("2026-10-25T11:00:00Z"), dispute, OPERATOR_B,
                BnplDisputeStatus.RESOLVED_REFUND).outcome()).isEqualTo(BnplDisputes.Outcome.PROPOSED);
        assertThat(audited(BnplAuditAction.DISPUTE_RESOLUTION_REJECTED)).hasSize(1);
    }

    @Test
    @DisplayName("an employee never resolves their own party's dispute: refused with nothing written, recorded FAILED")
    void anEmployeeNeverResolvesTheirOwnPartysDispute() {
        BnplConditionWorld.Agreement p4 = world.payIn4();
        BnplDispute dispute = openAt("2026-10-20T12:00:00Z", p4, "open-1", 200_00).dispute();
        respondAt("2026-10-21T09:00:00Z", dispute, BnplDisputes.Decision.CONTEST);
        operatorParties.put(OPERATOR_A.id(), world.party);
        BnplDisputes.SelfDealingRefused refused = org.junit.jupiter.api.Assertions.assertThrows(
                BnplDisputes.SelfDealingRefused.class, () -> resolveAt(Instant.parse("2026-10-25T10:00:00Z"), dispute,
                        OPERATOR_A, BnplDisputeStatus.RESOLVED_REFUND));
        assertThat(world.fakes.disputes.rows.get(dispute.id().value()).status())
                .isEqualTo(BnplDisputeStatus.MERCHANT_RESPONDED);
        world.fakes.inTransaction(() -> {
            disputes.recordSelfDealing(BnplApplicationFakes.UOW, refused, OPERATOR_A, CORRELATION);
            return null;
        });
        assertThat(audited(BnplAuditAction.SELF_DEALING_REFUSED)).singleElement()
                .satisfies(record -> assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED));
    }

    // ------------------------------------------------------------------ the one bound (scenario 10)

    @Test
    @DisplayName("scenario 10, refund first: a merchant refund of 200 leaves 400 to dispute - 401 is refused, 400 admitted"
            + " and resolved as a refund - at most G returned across both, then the purchase is FULLY_REFUNDED")
    void aRefundThenADisputeShareOneBound() {
        BnplConditionWorld.Agreement p4 = world.payIn4();
        world.refundOn(LocalDate.of(2026, 10, 19), p4, "refund-1", 200_00);
        assertThatThrownBy(() -> openAt("2026-10-20T12:00:00Z", p4, "open-1", 400_01))
                .isInstanceOf(BnplRefunds.RefundExceedsPurchase.class);
        BnplDispute dispute = openAt("2026-10-20T12:00:00Z", p4, "open-2", 400_00).dispute();
        assertThat(dispute.refundedBefore()).isEqualTo(eur(200_00));
        respondAt("2026-10-21T09:00:00Z", dispute, BnplDisputes.Decision.ACCEPT);
        assertThat(returnedToTheCustomerSide(p4)).isEqualTo(eur(600_00));
        assertThat(world.fakes.purchases.rows.get(p4.purchase().value()).status())
                .isEqualTo(BnplPurchaseStatus.FULLY_REFUNDED);
        assertThatThrownBy(() -> world.refundOn(LocalDate.of(2026, 10, 22), p4, "refund-2", 1))
                .isInstanceOf(BnplRefunds.PurchaseFullyRefunded.class);
        assertThatThrownBy(() -> openAt("2026-10-22T12:00:00Z", p4, "open-3", 1))
                .isInstanceOf(BnplRefunds.PurchaseFullyRefunded.class);
    }

    @Test
    @DisplayName("scenario 10, dispute first: while it is open a merchant refund and an operator refund are refused"
            + " DisputeOpen with nothing written (the domain alone, the database's rank dropped); after its refund"
            + " outcome refunds resume within what remains - never above G")
    void aDisputeThenARefundShareOneBound() {
        BnplConditionWorld.Agreement p4 = world.payIn4();
        BnplDispute dispute = openAt("2026-10-20T12:00:00Z", p4, "open-1", 300_00).dispute();
        world.fakes.refunds.disputeRankDropped = true;
        assertThatThrownBy(() -> world.refundOn(LocalDate.of(2026, 10, 21), p4, "refund-1", 100_00))
                .isInstanceOf(BnplRefunds.DisputeOpen.class);
        world.fakes.now = Instant.parse("2026-10-21T12:00:00Z");
        assertThatThrownBy(() -> world.fakes.inTransaction(() -> world.refunds.byOperator(BnplApplicationFakes.UOW,
                p4.purchase(), new BnplRefunds.Request("op-refund", eur(100_00), "on its behalf", Optional.empty()),
                OPERATOR_A, CORRELATION))).isInstanceOf(BnplRefunds.DisputeOpen.class);
        assertThat(world.fakes.refunds.rows).isEmpty();
        assertThatThrownBy(() -> openAt("2026-10-21T12:00:00Z", p4, "open-2", 100_00))
                .isInstanceOf(BnplDisputes.DisputeOpen.class);
        respondAt("2026-10-22T09:00:00Z", dispute, BnplDisputes.Decision.ACCEPT);
        world.refundOn(LocalDate.of(2026, 10, 23), p4, "refund-2", 300_00);
        assertThatThrownBy(() -> world.refundOn(LocalDate.of(2026, 10, 23), p4, "refund-3", 1))
                .isInstanceOf(BnplRefunds.PurchaseFullyRefunded.class);
        assertThat(returnedToTheCustomerSide(p4)).isEqualTo(eur(600_00));
    }

    @Test
    @DisplayName("the database's rank refuses a refund beside an open dispute for every writer, and a dispute's refund of"
            + " any amount but its claim (bnpl V015, mirrored)")
    void theRefundRankRefusesEveryWriter() {
        BnplConditionWorld.Agreement p4 = world.payIn4();
        openAt("2026-10-20T12:00:00Z", p4, "open-1", 300_00);
        world.fakes.now = Instant.parse("2026-10-21T12:00:00Z");
        BnplRefund raw = new BnplRefund(UUID.randomUUID(), p4.purchase(), p4.obligation(), world.merchant,
                BnplRefund.Door.MERCHANT, "MERCHANT:" + world.merchant, "raw", eur(100_00), eur(0), eur(4_00), eur(96_00),
                eur(0), eur(0), eur(100_00), BnplRefund.ExcessTo.WALLET, eur(0), eur(0), "REFUND_ALLOCATION_ENGINE_V1",
                p4.agreement(), 1, 1, "raw", com.finapp.ledger.JournalEntryId.of(IDS.next()), LocalDate.of(2026, 10, 21),
                Optional.empty(), CORRELATION, List.of());
        assertThatThrownBy(() -> world.fakes.refunds.insertRefund(BnplApplicationFakes.UOW, raw))
                .hasMessageContaining("open dispute");
    }

    // ------------------------------------------------------------------ the opening's refusals and its key

    @Test
    @DisplayName("the opening's refusals write nothing: another party's agreement is the same 404, a wrong currency or a"
            + " zero amount is invalid, a reason with a card number is refused; the key converges and is never reused")
    void theOpeningsRefusalsAndItsKey() {
        BnplConditionWorld.Agreement p4 = world.payIn4();
        world.fakes.now = Instant.parse("2026-10-20T12:00:00Z");
        assertThatThrownBy(() -> world.fakes.inTransaction(() -> disputes.open(BnplApplicationFakes.UOW, p4.agreement(),
                UUID.randomUUID(), request("k", eur(100_00), "not received"), world.customer, CORRELATION)))
                .isInstanceOf(BnplDisputes.DisputeNotFound.class);
        assertThatThrownBy(() -> openAt("2026-10-20T12:00:00Z", p4, "k", 0))
                .isInstanceOf(BnplDisputes.AmountInvalid.class);
        assertThatThrownBy(() -> world.fakes.inTransaction(() -> disputes.open(BnplApplicationFakes.UOW, p4.agreement(),
                world.party, request("k", Money.ofMinorUnits(100_00, com.finapp.sharedkernel.money.CurrencyCode.of("USD")),
                        "not received"), world.customer, CORRELATION)))
                .isInstanceOf(BnplDisputes.AmountInvalid.class);
        assertThatThrownBy(() -> world.fakes.inTransaction(() -> disputes.open(BnplApplicationFakes.UOW, p4.agreement(),
                world.party, request("k", eur(100_00), "card 4111 1111 1111 1111"), world.customer, CORRELATION)))
                .isInstanceOf(BnplReasons.ReasonRequired.class);
        assertThat(world.fakes.disputes.rows).isEmpty();
        BnplDisputes.Acted opened = openAt("2026-10-20T12:00:00Z", p4, "k", 100_00);
        BnplDisputes.Acted again = openAt("2026-10-20T12:05:00Z", p4, "k", 100_00);
        assertThat(again.replayed()).isTrue();
        assertThat(again.dispute().id()).isEqualTo(opened.dispute().id());
        assertThatThrownBy(() -> openAt("2026-10-20T12:06:00Z", p4, "k", 100_01))
                .isInstanceOf(BnplDisputes.DisputeKeyReused.class);
        assertThat(world.fakes.disputes.rows).hasSize(1);
    }

    @Test
    @DisplayName("T-dsp takes the purchase, then the obligation, then the dispute (B5, B4, B6) - and a refund outcome's"
            + " payable after them")
    void theDisputeTakesThePurchaseThenTheObligation() {
        BnplConditionWorld.Agreement p4 = world.payIn4();
        BnplDispute dispute = openAt("2026-10-20T12:00:00Z", p4, "open-1", 300_00).dispute();
        world.fakes.log.clear();
        respondAt("2026-10-21T09:00:00Z", dispute, BnplDisputes.Decision.ACCEPT);
        List<String> locks = world.fakes.log.stream().filter(step -> step.equals("B5") || step.equals("B4")
                || step.equals("B6") || step.equals("refund insert")).toList();
        assertThat(locks.subList(0, 3)).containsExactly("B5", "B4", "B6");
        assertThat(locks).endsWith("refund insert");
    }

    @Test
    @DisplayName("the opening takes the merchant's payable after B5 and B4 (P), and a merchant whose payable can no longer be"
            + " debited - closed - takes no dispute: refused with nothing written (ADR-0106 decision 11)")
    void theOpeningTakesThePayable() {
        BnplConditionWorld.Agreement p4 = world.payIn4();
        world.fakes.log.clear();
        openAt("2026-10-20T12:00:00Z", p4, "open-1", 100_00);
        List<String> locks = world.fakes.log.stream().filter(step -> step.equals("B5") || step.equals("B4")
                || step.equals("lock payable") || step.equals("dispute insert")).toList();
        assertThat(locks).containsExactly("B5", "B4", "lock payable", "dispute insert");

        BnplConditionWorld.Agreement closed = world.payIn4();
        world.fakes.book.statuses.put(world.payable, com.finapp.ledger.LedgerAccountStatus.CLOSED);
        assertThatThrownBy(() -> openAt("2026-10-21T12:00:00Z", closed, "open-2", 100_00))
                .isInstanceOf(BnplRefunds.RefundNotPostable.class);
        assertThat(world.fakes.disputes.rows.values()).noneMatch(row -> row.purchase().equals(closed.purchase()));
    }

    @Test
    @DisplayName("a dispute on a CLOSED obligation holds nothing; its refund outcome pays the customer's wallet only")
    void aDisputeOnAClosedObligation() {
        BnplConditionWorld.Agreement p4 = world.payIn4();
        for (LocalDate due : List.of(DUE_2, DUE_3, BnplConditionWorld.DUE_4)) {
            world.serviceOn(due, p4);
            world.repayOn(due, p4, "pay-" + due, 150_00);
        }
        assertThat(world.obligation(p4).status()).isEqualTo(BnplObligationStatus.CLOSED);
        BnplDispute dispute = openAt("2026-11-29T12:00:00Z", p4, "open-1", 600_00).dispute();
        assertThat(dispute.holds()).isEmpty();
        BnplRefund refund = respondAt("2026-11-30T09:00:00Z", dispute, BnplDisputes.Decision.ACCEPT).refund()
                .orElseThrow().refund();
        assertThat(refund.excess()).isEqualTo(eur(600_00));
        assertThat(refund.excessTo()).isEqualTo(BnplRefund.ExcessTo.WALLET);
    }

    @Test
    @DisplayName("the merchant's list shows its own open disputes only")
    void theMerchantsList() {
        BnplConditionWorld.Agreement p4 = world.payIn4();
        BnplDispute dispute = openAt("2026-10-20T12:00:00Z", p4, "open-1", 300_00).dispute();
        assertThat(disputes.openAtMerchant(BnplApplicationFakes.UOW, world.merchant)).extracting(BnplDispute::id)
                .containsExactly(dispute.id());
        assertThat(disputes.openAtMerchant(BnplApplicationFakes.UOW, UUID.randomUUID())).isEmpty();
        assertThat(disputes.anyOpenAtMerchant(BnplApplicationFakes.UOW, world.merchant)).isTrue();
        withdrawAt(Instant.parse("2026-10-21T09:00:00Z"), dispute);
        assertThat(disputes.anyOpenAtMerchant(BnplApplicationFakes.UOW, world.merchant)).isFalse();
    }

    @Test
    @DisplayName("the operator's read is recorded every time")
    void theOperatorsReadIsAudited() {
        BnplConditionWorld.Agreement p4 = world.payIn4();
        BnplDispute dispute = openAt("2026-10-20T12:00:00Z", p4, "open-1", 300_00).dispute();
        BnplDisputes.Investigated read = disputes.investigate(BnplApplicationFakes.UOW, dispute.id(), OPERATOR_A,
                CORRELATION);
        assertThat(read.dispute().id()).isEqualTo(dispute.id());
        assertThat(audited(BnplAuditAction.DISPUTE_READ)).hasSize(1);
        assertThat(dispute.toString()).doesNotContain("not received").doesNotContain("300");
    }

    // ------------------------------------------------------------------ helpers

    private static BnplDisputes.OpenRequest request(String key, Money amount, String reason) {
        return new BnplDisputes.OpenRequest(key, amount, BnplDisputeReason.NOT_RECEIVED, reason);
    }

    private BnplDisputes.Acted openAt(String instant, BnplConditionWorld.Agreement agreement, String key, long minor) {
        world.fakes.now = Instant.parse(instant);
        return world.fakes.inTransaction(() -> disputes.open(BnplApplicationFakes.UOW, agreement.agreement(),
                world.party, request(key, eur(minor), "not received"), world.customer, CORRELATION));
    }

    private BnplDisputes.Acted respondAt(String instant, BnplDispute dispute, BnplDisputes.Decision decision) {
        world.fakes.now = Instant.parse(instant);
        return world.fakes.inTransaction(() -> disputes.respond(BnplApplicationFakes.UOW, dispute.id(), world.merchant,
                new BnplDisputes.Response(decision, "we shipped it", "merchant-key-1"), world.merchantActor,
                CORRELATION));
    }

    private Optional<BnplDisputes.Acted> lapseAt(String instant, BnplDispute dispute) {
        world.fakes.now = Instant.parse(instant);
        return world.fakes.inTransaction(() -> disputes.lapse(BnplApplicationFakes.UOW, dispute.id(), SYSTEM,
                CORRELATION));
    }

    private BnplDisputes.Acted resolveAt(Instant instant, BnplDispute dispute, Actor operator, BnplDisputeStatus outcome) {
        world.fakes.now = instant;
        return world.fakes.inTransaction(() -> disputes.resolve(BnplApplicationFakes.UOW, dispute.id(), outcome,
                "on the evidence", operator, CORRELATION));
    }

    private BnplDisputes.Acted withdrawAt(Instant instant, BnplDispute dispute) {
        world.fakes.now = instant;
        return world.fakes.inTransaction(() -> disputes.withdraw(BnplApplicationFakes.UOW, dispute.id(), world.party,
                world.customer, CORRELATION));
    }

    private BnplTerms pinned(BnplConditionWorld.Agreement agreement) {
        BnplAgreement current = world.fakes.purchases.agreements.get(agreement.agreement().value());
        return world.fakes.terms.versions.get(current.termsVersionId()).terms();
    }

    private List<AuditRecord> audited(BnplAuditAction action) {
        return world.fakes.audited.stream().filter(record -> record.operation() == action).toList();
    }

    /** Σ the purchase's refunds - principal cancelled plus excess: what the customer side received. */
    private Money returnedToTheCustomerSide(BnplConditionWorld.Agreement agreement) {
        Money returned = eur(0);
        for (BnplRefund refund : world.fakes.refunds.rows.values()) {
            if (refund.purchase().equals(agreement.purchase())) {
                returned = returned.plus(refund.cancelled()).plus(refund.excess());
            }
        }
        return returned;
    }

    private boolean balanced(String key) {
        Money debits = eur(0);
        Money credits = eur(0);
        for (BnplApplicationFakes.Book.Line line : world.fakes.book.linesOf(key)) {
            if (line.direction() == Direction.DEBIT) {
                debits = debits.plus(line.amount());
            } else {
                credits = credits.plus(line.amount());
            }
        }
        return debits.isPositive() && debits.equals(credits);
    }
}
