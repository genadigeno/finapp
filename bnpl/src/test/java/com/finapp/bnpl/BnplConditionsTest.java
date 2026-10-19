package com.finapp.bnpl;

import static com.finapp.bnpl.BnplConditionWorld.DUE_2;
import static com.finapp.bnpl.BnplConditionWorld.DUE_3;
import static com.finapp.bnpl.BnplConditionWorld.DUE_4;
import static com.finapp.bnpl.BnplConditionWorld.eur;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.event.EventEnvelope;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Delinquency and default, hermetic (`P12-TSK-020`; {@code PHASE_12_PLAN.md} section 12.8, A14; lending's
 * {@code LoanConditions} - P11-TSK-024 - mirrored; {@code INV-LND-12} and {@code INV-LND-08} by analogy,
 * {@code INV-HIST-01}): the servicing step derives DPD from the billed and settled rows on the database clock's business
 * date - a difference of dates, never a counter - records a change of bucket or flag once per business date and only on a
 * change, defaults at the pinned threshold and clears at cure, never evaluates a closed obligation, and the customer's
 * read derives the condition live. Over {@link BnplConditionWorld} - in-memory {@code bnpl V013} whose ranks re-derive
 * DPD, the bucket and the flag from the rows. The races across instances and the database's own ranks are
 * {@code BnplConditionDatabaseTest}'s.
 */
@DisplayName("delinquency and default in the servicing step (P12-TSK-020)")
class BnplConditionsTest {

    private BnplConditionWorld world;

    @BeforeEach
    void setUp() {
        world = new BnplConditionWorld();
        // The condition suite charges no fee: the fee is the late-fee suite's, and an unpaid fee keeps an instalment due.
        world.activate(BnplTermsFixtures.payIn4().build());
    }

    @Test
    @DisplayName("current, then each bucket as DPD crosses its bound, then cure: one DELINQUENCY row per change - from"
            + " CURRENT to B1 the day after the due date, to B2 at 30, B3 at 60 - and back to CURRENT the step after the"
            + " customer pays everything due (twelve months of pay-in-N, so the cure is no closure); each published with"
            + " the bucket before it")
    void currentToEachBucketAndCure() {
        BnplConditionWorld.Agreement pn = world.payIn12();
        LocalDate due = world.instalment(pn, 1).dueDate();
        assertThat(world.serviceAccruedOn(due, pn).conditionChanges()).as("due today is not yet past due").isZero();
        assertThat(world.serviceAccruedOn(due.plusDays(1), pn).conditionChanges()).isEqualTo(1);
        world.serviceAccruedOn(due.plusDays(29), pn);
        world.serviceAccruedOn(due.plusDays(30), pn);
        world.serviceAccruedOn(due.plusDays(59), pn);
        world.serviceAccruedOn(due.plusDays(60), pn);
        world.repayEverythingDueOn(due.plusDays(61), pn);
        world.serviceAccruedOn(due.plusDays(61), pn);
        assertThat(world.obligation(pn).status()).isEqualTo(BnplObligationStatus.ACTIVE);
        assertThat(world.history(pn)).containsExactly(
                due.plusDays(1) + " DELINQUENCY 1 B1_1_29 false from CURRENT",
                due.plusDays(30) + " DELINQUENCY 30 B2_30_59 false from B1_1_29",
                due.plusDays(60) + " DELINQUENCY 60 B3_60_89 false from B2_30_59",
                due.plusDays(61) + " DELINQUENCY 0 CURRENT false from B3_60_89");
        assertThat(world.fakes.events).extracting(EventEnvelope::eventType)
                .filteredOn(type -> type.startsWith("bnpl.BnplDe"))
                .containsExactly(BnplConditions.DELINQUENCY_CHANGED_EVENT, BnplConditions.DELINQUENCY_CHANGED_EVENT,
                        BnplConditions.DELINQUENCY_CHANGED_EVENT, BnplConditions.DELINQUENCY_CHANGED_EVENT);
        assertThat(payloadsOf(BnplConditions.DELINQUENCY_CHANGED_EVENT)).last().asString()
                .contains("\"bucket\":\"CURRENT\"").contains("\"previousBucket\":\"B3_60_89\"")
                .contains("\"daysPastDue\":\"0\"").doesNotContain(world.party.toString());
    }

    @Test
    @DisplayName("default at the pinned threshold (90) - B4 and the flag in one step, bnpl.BnplDefaulted - held while"
            + " anything is past due, cleared at cure with no probation: CURRENT and bnpl.BnplDefaultCleared")
    void defaultAtNinetyClearedAtCure() {
        BnplConditionWorld.Agreement pn = world.payIn12();
        LocalDate due = world.instalment(pn, 1).dueDate();
        world.serviceAccruedOn(due.plusDays(89), pn);
        assertThat(world.serviceAccruedOn(due.plusDays(90), pn).conditionChanges()).isEqualTo(2);
        world.serviceAccruedOn(due.plusDays(100), pn);
        world.repayEverythingDueOn(due.plusDays(101), pn);
        world.serviceAccruedOn(due.plusDays(101), pn);
        assertThat(world.history(pn)).containsExactly(
                due.plusDays(89) + " DELINQUENCY 89 B3_60_89 false from CURRENT",
                due.plusDays(90) + " DELINQUENCY 90 B4_90_PLUS true from B3_60_89",
                due.plusDays(90) + " DEFAULT 90 B4_90_PLUS true",
                due.plusDays(101) + " DELINQUENCY 0 CURRENT false from B4_90_PLUS",
                due.plusDays(101) + " DEFAULT 0 CURRENT false");
        assertThat(world.fakes.events).extracting(EventEnvelope::eventType)
                .containsSubsequence(BnplConditions.DEFAULTED_EVENT, BnplConditions.DEFAULT_CLEARED_EVENT);
    }

    @Test
    @DisplayName("a partial cure keeps the flag: paying the oldest instalment while a younger one is still past due moves"
            + " DPD to the younger one's age - the bucket falls, the default stays until DPD is 0")
    void aPartialCureKeepsTheDefault() {
        BnplConditionWorld.Agreement p4 = world.payIn4();
        world.serviceOn(DUE_2.plusDays(90), p4);
        world.repayOn(DUE_2.plusDays(91), p4, "oldest", 150_00);
        world.serviceOn(DUE_2.plusDays(91), p4);
        // Instalment 3 (due 14 days after 2) is now the oldest past due: DPD 77, B3 - still defaulted.
        assertThat(world.history(p4)).containsExactly(
                DUE_2.plusDays(90) + " DELINQUENCY 90 B4_90_PLUS true from CURRENT",
                DUE_2.plusDays(90) + " DEFAULT 90 B4_90_PLUS true",
                DUE_2.plusDays(91) + " DELINQUENCY 77 B3_60_89 true from B4_90_PLUS");
        assertThat(world.conditions.read(BnplApplicationFakes.UOW, owned(p4))).hasValueSatisfying(state -> {
            assertThat(state.defaulted()).isTrue();
            assertThat(state.daysPastDue()).isEqualTo(77);
        });
    }

    @Test
    @DisplayName("re-running a day writes nothing: a second and third step on the same date append no row - once a"
            + " business date, even after a repayment cured it that day (the next date records the cure)")
    void reRunningADayWritesNothing() {
        BnplConditionWorld.Agreement p4 = world.payIn4();
        world.serviceOn(DUE_2.plusDays(3), p4);
        assertThat(world.serviceOn(DUE_2.plusDays(3), p4).conditionChanges()).isZero();
        world.repayOn(DUE_2.plusDays(3), p4, "cure", 150_00);
        assertThat(world.serviceOn(DUE_2.plusDays(3), p4).conditionChanges()).isZero();
        assertThat(world.history(p4)).containsExactly(DUE_2.plusDays(3) + " DELINQUENCY 3 B1_1_29 false from CURRENT");
        assertThat(world.serviceOn(DUE_2.plusDays(4), p4).conditionChanges()).isEqualTo(1);
        assertThat(world.history(p4)).last().isEqualTo(DUE_2.plusDays(4) + " DELINQUENCY 0 CURRENT false from B1_1_29");
    }

    @Test
    @DisplayName("ten steps on one date - five handed the id, five claiming - record one condition change, one event")
    void tenStepsOneConditionChange() {
        BnplConditionWorld.Agreement p4 = world.payIn4();
        world.serviceOn(DUE_2, p4);
        world.fakes.events.clear();
        int changes = 0;
        for (int sweeper = 0; sweeper < 10; sweeper++) {
            changes += world.serviceOn(DUE_2.plusDays(2), p4).conditionChanges();
        }
        assertThat(changes).isEqualTo(1);
        assertThat(world.history(p4)).hasSize(1);
        assertThat(world.fakes.events).extracting(EventEnvelope::eventType)
                .containsExactly(BnplConditions.DELINQUENCY_CHANGED_EVENT);
    }

    @Test
    @DisplayName("a cure between sweeps is recorded, never overwritten: B1, then the repayment, then CURRENT on the next"
            + " step - both rows kept, the history append-only")
    void aCureBetweenSweepsIsRecordedNotOverwritten() {
        BnplConditionWorld.Agreement p4 = world.payIn4();
        world.serviceOn(DUE_2.plusDays(5), p4);
        world.repayOn(DUE_2.plusDays(6), p4, "cure", 150_00);
        world.serviceOn(DUE_2.plusDays(7), p4);
        assertThat(world.history(p4)).containsExactly(DUE_2.plusDays(5) + " DELINQUENCY 5 B1_1_29 false from CURRENT",
                DUE_2.plusDays(7) + " DELINQUENCY 0 CURRENT false from B1_1_29");
    }

    @Test
    @DisplayName("a missed day computes from the dates: a step on day 1, the sweeper down for 33 days, the next step"
            + " records DPD 34 (B2) in one row - never a day at a time, never a counter of sweeps")
    void aMissedDayComputesFromTheDates() {
        BnplConditionWorld.Agreement p4 = world.payIn4();
        world.serviceOn(DUE_2.plusDays(1), p4);
        world.serviceOn(DUE_2.plusDays(34), p4);
        assertThat(world.history(p4)).containsExactly(DUE_2.plusDays(1) + " DELINQUENCY 1 B1_1_29 false from CURRENT",
                DUE_2.plusDays(34) + " DELINQUENCY 34 B2_30_59 false from B1_1_29");
        // And a first step long after the due date: DPD from the oldest unpaid due date, whatever was billed that day.
        BnplConditionWorld.Agreement late = world.payIn4();
        world.serviceOn(DUE_4.plusDays(2), late);
        assertThat(world.history(late)).containsExactly(DUE_4.plusDays(2) + " DELINQUENCY 30 B2_30_59 false from CURRENT");
    }

    @Test
    @DisplayName("DPD is the oldest unpaid instalment's age: a partial payment of the oldest leaves it past due (no"
            + " tolerance - a minor unit unpaid is unpaid)")
    void aPartialPaymentLeavesTheOldestPastDue() {
        BnplConditionWorld.Agreement p4 = world.payIn4();
        world.serviceOn(DUE_3.plusDays(1), p4);
        world.repayOn(DUE_3.plusDays(2), p4, "almost", 149_99);
        world.serviceOn(DUE_3.plusDays(2), p4);
        assertThat(world.history(p4)).containsExactly(DUE_3.plusDays(1) + " DELINQUENCY 15 B1_1_29 false from CURRENT");
        assertThat(world.conditions.read(BnplApplicationFakes.UOW, owned(p4)).orElseThrow().daysPastDue()).isEqualTo(16);
    }

    @Test
    @DisplayName("grace delays only the late fee, never DPD: the day after the due date is DPD 1 though the pinned grace"
            + " is 3 days")
    void graceDelaysOnlyTheLateFeeNeverDpd() {
        BnplConditionWorld.Agreement p4 = world.payIn4();
        world.serviceOn(DUE_2.plusDays(1), p4);
        assertThat(world.history(p4)).containsExactly(DUE_2.plusDays(1) + " DELINQUENCY 1 B1_1_29 false from CURRENT");
    }

    @Test
    @DisplayName("no condition after closure (scenario 13's hermetic half): a step that closes the obligation evaluates"
            + " nothing, a closed obligation is not serviced, the condition step refuses to evaluate one, and the read"
            + " answers no condition")
    void noConditionAfterClosure() {
        BnplConditionWorld.Agreement p4 = world.payIn4();
        world.serviceOn(DUE_2.plusDays(1), p4);
        world.repayOn(DUE_2.plusDays(2), p4, "everything ahead", 450_00);
        ServicingOutcome closing = world.serviceOn(DUE_4, p4);
        assertThat(closing.closed()).isTrue();
        assertThat(closing.conditionChanges()).isZero();
        assertThat(world.obligation(p4).status()).isEqualTo(BnplObligationStatus.CLOSED);
        assertThat(world.serviceOn(DUE_4.plusDays(30), p4).kind()).isEqualTo(ServicingOutcome.Kind.NOT_SERVICED);
        assertThat(world.history(p4)).containsExactly(DUE_2.plusDays(1) + " DELINQUENCY 1 B1_1_29 false from CURRENT");
        BnplServicingStore.Locked closed = world.fakes.servicing.lock(BnplApplicationFakes.UOW, p4.obligation())
                .orElseThrow();
        BnplTerms pinned = world.fakes.terms.versions.get(closed.termsVersionId()).terms();
        assertThat(world.conditions.evaluate(BnplApplicationFakes.UOW, closed, pinned, DUE_4.plusDays(30),
                BnplConditionWorld.CORRELATION)).isZero();
        assertThat(world.conditions.read(BnplApplicationFakes.UOW, owned(p4))).isEmpty();
        // Beneath the domain, the history's rank: no row for a closed obligation.
        world.fakes.now = DUE_4.plusDays(30).atTime(9, 0).toInstant(java.time.ZoneOffset.UTC);
        assertThatThrownBy(() -> world.fakes.conditions.insertCondition(BnplApplicationFakes.UOW,
                new BnplConditionEvent(BnplConditionWorld.IDS.next(), p4.obligation(), DUE_4.plusDays(30),
                        BnplConditionKind.DELINQUENCY, BnplDelinquencyState.CURRENT,
                        Optional.of(BnplDelinquencyBucket.B1_1_29), closed.agreement(), closed.agreementVersion(),
                        BnplConditionWorld.CORRELATION)))
                .isInstanceOf(BnplStorageException.class).hasMessageContaining("only for an ACTIVE obligation");
    }

    @Test
    @DisplayName("the history's ranks for every writer: a change that is no change, a DPD not the rows', a flag not the"
            + " rule's, a row dated before a recorded one - each refused; a failure in the step writes nothing")
    void theHistorysRanks() {
        BnplConditionWorld.Agreement p4 = world.payIn4();
        world.serviceOn(DUE_2.plusDays(10), p4);
        BnplServicingStore.Locked locked = world.fakes.servicing.lock(BnplApplicationFakes.UOW, p4.obligation())
                .orElseThrow();
        world.fakes.now = DUE_2.plusDays(11).atTime(9, 0).toInstant(java.time.ZoneOffset.UTC);
        List<BnplConditionEvent> refused = List.of(
                // No change: the flag "changed" from not defaulted to not defaulted.
                event(p4, locked, DUE_2.plusDays(11), BnplConditionKind.DEFAULT,
                        new BnplDelinquencyState(11, Optional.of(BnplDelinquencyBucket.B1_1_29), false), Optional.empty()),
                // A writer's DPD: 40 is not the rows' 11.
                event(p4, locked, DUE_2.plusDays(11), BnplConditionKind.DELINQUENCY,
                        new BnplDelinquencyState(40, Optional.of(BnplDelinquencyBucket.B2_30_59), false),
                        Optional.of(BnplDelinquencyBucket.B1_1_29)),
                // A flag set before the threshold.
                event(p4, locked, DUE_2.plusDays(11), BnplConditionKind.DEFAULT,
                        new BnplDelinquencyState(11, Optional.of(BnplDelinquencyBucket.B1_1_29), true), Optional.empty()));
        for (BnplConditionEvent row : refused) {
            assertThatThrownBy(() -> world.fakes.conditions.insertCondition(BnplApplicationFakes.UOW, row))
                    .isInstanceOf(BnplStorageException.class);
        }
        world.fakes.failAt = "condition insert";
        assertThatThrownBy(() -> world.serviceOn(DUE_2.plusDays(40), p4))
                .isInstanceOf(BnplApplicationFakes.InjectedFailure.class);
        world.fakes.failAt = null;
        assertThat(world.history(p4)).hasSize(1);
        world.serviceOn(DUE_2.plusDays(40), p4);
        world.fakes.now = DUE_2.plusDays(40).atTime(10, 0).toInstant(java.time.ZoneOffset.UTC);
        assertThatThrownBy(() -> world.fakes.conditions.insertCondition(BnplApplicationFakes.UOW,
                event(p4, locked, DUE_2.plusDays(39), BnplConditionKind.DELINQUENCY,
                        new BnplDelinquencyState(39, Optional.of(BnplDelinquencyBucket.B2_30_59), false),
                        Optional.of(BnplDelinquencyBucket.B1_1_29))))
                .isInstanceOf(BnplStorageException.class);
    }

    @Test
    @DisplayName("the customer's read derives the condition live on the database clock - before the next step records"
            + " it - and never shows a figure the history did not decide")
    void theReadIsLive() {
        BnplConditionWorld.Agreement p4 = world.payIn4();
        world.serviceOn(DUE_2, p4);
        world.fakes.now = DUE_2.plusDays(12).atTime(15, 0).toInstant(java.time.ZoneOffset.UTC);
        assertThat(world.conditions.read(BnplApplicationFakes.UOW, owned(p4))).hasValueSatisfying(state -> {
            assertThat(state.daysPastDue()).isEqualTo(12);
            assertThat(state.bucket()).contains(BnplDelinquencyBucket.B1_1_29);
            assertThat(state.defaulted()).isFalse();
            assertThat(state.toString()).doesNotContain("12");
        });
        assertThat(world.history(p4)).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private BnplPurchaseStore.Owned owned(BnplConditionWorld.Agreement agreement) {
        BnplObligation obligation = world.obligation(agreement);
        return new BnplPurchaseStore.Owned(obligation, world.fakes.purchases.rows.get(agreement.purchase().value()),
                world.fakes.purchases.agreements.get(agreement.agreement().value()), eur(BnplConditionWorld.GROSS));
    }

    private List<String> payloadsOf(String type) {
        List<String> found = new java.util.ArrayList<>();
        for (int i = 0; i < world.fakes.events.size(); i++) {
            if (world.fakes.events.get(i).eventType().equals(type)) {
                found.add(world.fakes.payloads.get(i));
            }
        }
        return found;
    }

    private static BnplConditionEvent event(BnplConditionWorld.Agreement agreement, BnplServicingStore.Locked locked,
            LocalDate date, BnplConditionKind kind, BnplDelinquencyState state,
            Optional<BnplDelinquencyBucket> previous) {
        return new BnplConditionEvent(BnplConditionWorld.IDS.next(), agreement.obligation(), date, kind, state, previous,
                locked.agreement(), locked.agreementVersion(), BnplConditionWorld.CORRELATION);
    }
}
