package com.finapp.bnpl;

import static com.finapp.bnpl.BnplConditionWorld.DUE_2;
import static com.finapp.bnpl.BnplConditionWorld.DUE_3;
import static com.finapp.bnpl.BnplConditionWorld.DUE_4;
import static com.finapp.bnpl.BnplConditionWorld.eur;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.event.EventEnvelope;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Late fees and their release on a refund-cancelled instalment, hermetic (`P12-TSK-020`; A13, OQ-14;
 * {@code PHASE_12_PLAN.md} sections 7.2, 12.3, 12.6 point 4 and 12.11 F4/F5a; lending's {@code LateFees} - P11-TSK-025 -
 * mirrored; {@code INV-IDEM-02}, {@code INV-BNPL-01}, {@code INV-BNPL-06}; scenario 17): the pinned fee once per instalment
 * on the day its own age reaches the pinned day (grace delays it, never DPD), caught up after a missed day, never on a
 * settled instalment, capped per agreement, F4 balanced under {@code bnpl.fee:<instalment>:LATE} with the shared income row
 * taken after the wallet; paid first by every payer; and released - once - when a refund cancels its instalment whole.
 * Over {@link BnplConditionWorld}. The races and the database's ranks are {@code BnplLateFeeDatabaseTest}'s; the income
 * row's real lock-wait is {@code app}'s {@code BnplCollectionLedgerDatabaseTest#theFeeIncomeRowIsTakenAfterTheWallet}.
 */
@DisplayName("late fees in the servicing step and their release by a refund (P12-TSK-020)")
class BnplLateFeesTest {

    private BnplConditionWorld world;

    @BeforeEach
    void setUp() {
        world = new BnplConditionWorld();
    }

    // ------------------------------------------------------------------ the assessment (scenario 17's application half)

    @Test
    @DisplayName("assessed once at the late-fee day: nothing at age 4, EUR 5.00 on instalment 2 at age 5 - F4 DR"
            + " BNPL_FEES_DUE / CR BNPL_FEE_INCOME under bnpl.fee:<instalment>:LATE, posted and valued today, the row and"
            + " bnpl.BnplLateFeeAssessed - and nothing more the next day")
    void assessedOnceAtTheLateFeeDay() {
        BnplConditionWorld.Agreement p4 = world.payIn4();
        assertThat(world.serviceOn(DUE_2.plusDays(4), p4).fees()).isZero();
        ServicingOutcome fifth = world.serviceOn(DUE_2.plusDays(5), p4);
        assertThat(fifth.fees()).isEqualTo(1);
        assertThat(fifth.feesCapped()).isZero();
        assertThat(world.fees(p4)).containsExactly("2 " + DUE_2.plusDays(5) + " 500");
        UUID instalment = world.instalment(p4, 2).id();
        String key = BnplConditions.FEE_KEY_PREFIX + instalment + ":LATE";
        assertThat(world.labelled(world.fakes.book.linesOf(key)))
                .containsExactly("BNPL_FEES_DUE DEBIT 500", "BNPL_FEE_INCOME CREDIT 500");
        assertThat(world.fakes.book.lines).filteredOn(line -> line.key().equals(key))
                .allSatisfy(line -> {
                    assertThat(line.valueDate()).isEqualTo(DUE_2.plusDays(5));
                    assertThat(line.postingDate()).isEqualTo(DUE_2.plusDays(5));
                });
        assertThat(world.balance(world.accounts(p4).feesDue())).isEqualTo(eur(5_00));
        assertThat(world.fakes.events).extracting(EventEnvelope::eventType)
                .contains(BnplConditions.LATE_FEE_ASSESSED_EVENT);
        assertThat(world.serviceOn(DUE_2.plusDays(6), p4).fees()).isZero();
        assertThat(world.fees(p4)).hasSize(1);
        // The fee is part of what the instalment owes: the billed item shows it, the due state explains the ledger.
        BnplPurchaseStore.BilledItem item = world.fakes.purchases.billedItems(BnplApplicationFakes.UOW, p4.obligation())
                .stream().filter(row -> row.instalment().equals(instalment)).findFirst().orElseThrow();
        assertThat(item.billedFees()).isEqualTo(eur(5_00));
        assertThat(new BnplReceivables(world.fakes.purchases, world.fakes.book).dueState(BnplApplicationFakes.UOW,
                p4.obligation(), world.accounts(p4)).dueState().items()).isNotEmpty();
    }

    @Test
    @DisplayName("ten steps on the fee's day assess once: one row, one entry, one event (B4, UNIQUE (instalment_id, kind),"
            + " the ledger key)")
    void tenStepsAssessOnce() {
        BnplConditionWorld.Agreement p4 = world.payIn4();
        world.serviceOn(DUE_2, p4);
        world.fakes.events.clear();
        int fees = 0;
        for (int sweeper = 0; sweeper < 10; sweeper++) {
            fees += world.serviceOn(DUE_2.plusDays(5), p4).fees();
        }
        assertThat(fees).isEqualTo(1);
        assertThat(world.fees(p4)).hasSize(1);
        assertThat(world.fakes.book.keys).filteredOn(key -> key.startsWith(BnplConditions.FEE_KEY_PREFIX)).hasSize(1);
        assertThat(world.fakes.events).extracting(EventEnvelope::eventType)
                .filteredOn(BnplConditions.LATE_FEE_ASSESSED_EVENT::equals).hasSize(1);
        // Beneath the domain: a second fee for the instalment is refused by the unique.
        BnplFeeAssessment first = world.fakes.conditions.feesOf(p4.obligation()).get(0);
        assertThatThrownBy(() -> world.fakes.conditions.insertAssessment(BnplApplicationFakes.UOW,
                new BnplFeeAssessment(BnplConditionWorld.IDS.next(), p4.obligation(), first.instalment(),
                        BnplFeeKind.LATE, first.businessDate(), eur(1_00), first.agreement(), first.agreementVersion(),
                        com.finapp.ledger.JournalEntryId.of(BnplConditionWorld.IDS.next()), BnplConditionWorld.CORRELATION)))
                .isInstanceOf(BnplStorageException.class).hasMessageContaining("23505");
    }

    @Test
    @DisplayName("the cap holds per agreement: fees of 5.00 under a cap of 12.00 - instalments 2 and 3 in full, 4 capped at"
            + " the 2.00 the cap admits (counted capped), then nothing more ever")
    void theCapHolds() {
        world.withFees(BnplTermsFixtures.payIn4().lateFeeCap(eur(12_00)));
        BnplConditionWorld.Agreement p4 = world.payIn4();
        ServicingOutcome all = world.serviceOn(DUE_4.plusDays(5), p4);
        assertThat(all.fees()).isEqualTo(3);
        assertThat(all.feesCapped()).isEqualTo(1);
        assertThat(world.fees(p4)).containsExactly("2 " + DUE_4.plusDays(5) + " 500", "3 " + DUE_4.plusDays(5) + " 500",
                "4 " + DUE_4.plusDays(5) + " 200");
        assertThat(world.balance(world.accounts(p4).feesDue())).isEqualTo(eur(12_00));
        assertThat(world.serviceOn(DUE_4.plusDays(40), p4).fees()).isZero();
    }

    @Test
    @DisplayName("grace delays the fee: none at ages 1 to 4 (grace 3, day 5) though DPD runs from age 1; the fee at 5")
    void graceDelaysTheFee() {
        BnplConditionWorld.Agreement p4 = world.payIn4();
        for (int age = 1; age <= 4; age++) {
            assertThat(world.serviceOn(DUE_2.plusDays(age), p4).fees()).as("age %d", age).isZero();
        }
        assertThat(world.history(p4)).first().asString().startsWith(DUE_2.plusDays(1) + " DELINQUENCY 1");
        assertThat(world.serviceOn(DUE_2.plusDays(5), p4).fees()).isEqualTo(1);
    }

    @Test
    @DisplayName("each instalment by its OWN age, never by DPD: on instalment 3's day 4 only instalment 2 is charged; on its"
            + " day 5, instalment 3 too - one fee each")
    void eachInstalmentByItsOwnAge() {
        BnplConditionWorld.Agreement p4 = world.payIn4();
        world.serviceOn(DUE_3.plusDays(4), p4);
        assertThat(world.fees(p4)).containsExactly("2 " + DUE_3.plusDays(4) + " 500");
        world.serviceOn(DUE_3.plusDays(5), p4);
        assertThat(world.fees(p4)).containsExactly("2 " + DUE_3.plusDays(4) + " 500", "3 " + DUE_3.plusDays(5) + " 500");
    }

    @Test
    @DisplayName("a missed fee day is caught up while the instalment still owes - once, dated the day of the step; one paid"
            + " in the meantime is never charged late")
    void aMissedDayIsCaughtUpButASettledInstalmentIsNeverCharged() {
        BnplConditionWorld.Agreement owing = world.payIn4();
        world.serviceOn(DUE_2.plusDays(12), owing);
        assertThat(world.fees(owing)).containsExactly("2 " + DUE_2.plusDays(12) + " 500");
        BnplConditionWorld.Agreement settled = world.payIn4();
        world.serviceOn(DUE_2, settled);
        world.repayOn(DUE_2.plusDays(3), settled, "in grace", 150_00);
        assertThat(world.serviceOn(DUE_2.plusDays(5), settled).fees()).isZero();
        assertThat(world.fees(settled)).isEmpty();
    }

    @Test
    @DisplayName("every payer pays the fee first (FEES -> INTEREST -> PRINCIPAL) and an unpaid fee keeps its instalment"
            + " past due: 150.00 after a fee leaves 5.00 of principal due; 5.00 more cures it")
    void theFeeIsPaidFirstAndKeepsItsInstalmentPastDue() {
        BnplConditionWorld.Agreement p4 = world.payIn4();
        world.serviceOn(DUE_2.plusDays(5), p4);
        BnplRepayments.Repaid repaid = world.repayOn(DUE_2.plusDays(6), p4, "the bill", 150_00);
        assertThat(repaid.repayment().allocations()).extracting(line -> line.component().orElse("-") + " "
                + line.amount().minorUnits()).containsExactly("FEES 500", "PRINCIPAL 14500");
        assertThat(world.balance(world.accounts(p4).feesDue())).isEqualTo(eur(0));
        assertThat(world.conditions.read(BnplApplicationFakes.UOW, owned(p4)).orElseThrow().daysPastDue()).isEqualTo(6);
        world.repayOn(DUE_2.plusDays(6), p4, "the rest", 5_00);
        assertThat(world.conditions.read(BnplApplicationFakes.UOW, owned(p4)).orElseThrow().pastDue()).isFalse();
    }

    @Test
    @DisplayName("an unpaid fee alone keeps the instalment past due - DPD counts every component, the lending precedent")
    void anUnpaidFeeAloneKeepsItPastDue() {
        BnplConditionWorld.Agreement p4 = world.payIn4();
        world.serviceOn(DUE_2.plusDays(5), p4);
        // 155.00 would settle it; the credit held after it would be applied at the next billing.
        BnplPurchaseStore.BilledItem before = world.fakes.purchases.billedItems(BnplApplicationFakes.UOW, p4.obligation())
                .get(1);
        assertThat(before.dueTotal()).isEqualTo(eur(155_00));
        assertThat(world.fakes.conditions.daysPastDue(p4.obligation(), DUE_2.plusDays(5))).isEqualTo(5);
    }

    @Test
    @DisplayName("the fee's income row is taken after the wallet (the A2-F1 rule): on collecting terms the step locks the"
            + " wallet (P) for the collection first; the one L8 call holding BNPL_FEE_INCOME comes after it, beside the"
            + " fees-due row, and no earlier L8 call holds the income row")
    void theFeeIncomeRowIsTakenAfterTheWallet() {
        world.withFees(BnplTermsFixtures.payIn4().autoCollection(true).partialCollection(false));
        BnplConditionWorld.Agreement p4 = world.payIn4();
        world.availableTo(0);
        world.fakes.log.clear();
        world.fakes.book.projections.clear();
        ServicingOutcome step = world.serviceOn(DUE_2.plusDays(5), p4);
        assertThat(step.collected(CollectionOutcome.INSUFFICIENT_FUNDS)).isEqualTo(1);
        assertThat(step.fees()).isEqualTo(1);
        List<String> log = world.fakes.log;
        int wallet = log.indexOf("lock wallet");
        int post = log.indexOf("post " + BnplConditions.FEE_KEY_PREFIX);
        assertThat(wallet).isNotNegative();
        assertThat(post).isGreaterThan(wallet);
        int l8BeforeWallet = (int) log.subList(0, wallet).stream().filter("L8"::equals).count();
        int l8BeforePost = (int) log.subList(0, post).stream().filter("L8"::equals).count();
        assertThat(log.subList(wallet, post)).contains("L8");
        LedgerAccountId income = world.feeIncome();
        assertThat(world.fakes.book.projections.subList(0, l8BeforeWallet)).noneMatch(rows -> rows.contains(income));
        assertThat(world.fakes.book.projections.get(l8BeforePost - 1))
                .containsExactlyInAnyOrder(income, world.accounts(p4).feesDue().id());
    }

    @Test
    @DisplayName("no fee on terms that carry none (production until activation, A13), and none for a closed obligation")
    void noFeeWithoutTermsOrAfterClosure() {
        world.activate(BnplTermsFixtures.payIn4().build());
        BnplConditionWorld.Agreement free = world.payIn4();
        assertThat(world.serviceOn(DUE_2.plusDays(9), free).fees()).isZero();
        assertThat(world.fakes.book.keys).noneMatch(key -> key.startsWith(BnplConditions.FEE_KEY_PREFIX));
        world.withFees(BnplTermsFixtures.payIn4());
        BnplConditionWorld.Agreement paid = world.payIn4();
        world.repayOn(DUE_2.minusDays(1), paid, "ahead", 450_00);
        assertThat(world.serviceOn(DUE_4, paid).closed()).isTrue();
        BnplServicingStore.Locked closed = world.fakes.servicing.lock(BnplApplicationFakes.UOW, paid.obligation())
                .orElseThrow();
        BnplTerms pinned = world.fakes.terms.versions.get(closed.termsVersionId()).terms();
        assertThat(world.conditions.assessLateFees(BnplApplicationFakes.UOW, closed, pinned, world.accounts(paid),
                DUE_4.plusDays(10), BnplConditionWorld.CORRELATION)).isEqualTo(BnplConditions.Assessed.NOTHING);
    }

    @Test
    @DisplayName("a failure at the fee's row rolls the whole step back - no entry, no condition row, no event - and the"
            + " retry assesses it once")
    void aFailureWritesNothing() {
        BnplConditionWorld.Agreement p4 = world.payIn4();
        world.serviceOn(DUE_2, p4);
        int keys = world.fakes.book.keys.size();
        int events = world.fakes.events.size();
        world.fakes.failAt = "fee insert";
        assertThatThrownBy(() -> world.serviceOn(DUE_2.plusDays(5), p4))
                .isInstanceOf(BnplApplicationFakes.InjectedFailure.class);
        world.fakes.failAt = null;
        assertThat(world.fakes.book.keys).hasSize(keys);
        assertThat(world.fakes.events).hasSize(events);
        assertThat(world.history(p4)).isEmpty();
        assertThat(world.serviceOn(DUE_2.plusDays(5), p4).fees()).isEqualTo(1);
    }

    @Test
    @DisplayName("the fee row's ranks for every writer: before the day, above the fee, beyond the cap, on a settled"
            + " instalment - each refused")
    void theFeeRanks() {
        BnplConditionWorld.Agreement p4 = world.payIn4();
        world.serviceOn(DUE_2.plusDays(4), p4);
        BnplServicingStore.Locked locked = world.fakes.servicing.lock(BnplApplicationFakes.UOW, p4.obligation())
                .orElseThrow();
        UUID second = world.instalment(p4, 2).id();
        // Before the day: age 4.
        assertThatThrownBy(() -> world.fakes.conditions.insertAssessment(BnplApplicationFakes.UOW,
                fee(p4, locked, second, DUE_2.plusDays(4), 5_00))).hasMessageContaining("late-fee day");
        world.fakes.now = DUE_2.plusDays(6).atTime(9, 0).toInstant(java.time.ZoneOffset.UTC);
        // Above the pinned fee.
        assertThatThrownBy(() -> world.fakes.conditions.insertAssessment(BnplApplicationFakes.UOW,
                fee(p4, locked, second, DUE_2.plusDays(6), 5_01))).hasMessageContaining("pinned cap");
        world.repayOn(DUE_2.plusDays(6), p4, "settled", 150_00);
        world.fakes.now = DUE_2.plusDays(6).atTime(9, 0).toInstant(java.time.ZoneOffset.UTC);
        assertThatThrownBy(() -> world.fakes.conditions.insertAssessment(BnplApplicationFakes.UOW,
                fee(p4, locked, second, DUE_2.plusDays(6), 5_00))).hasMessageContaining("still owing");
    }

    // ------------------------------------------------------------------ the release (OQ-14, scenario 17's release half)

    @Test
    @DisplayName("OQ-14: a refund that cancels a fee-bearing billed instalment whole releases its fee in its own entry -"
            + " DR BNPL_FEE_INCOME / CR BNPL_FEES_DUE 5.00 - on the adjustment and the refund, published; fees due zero,"
            + " and the obligation the refund settled closes")
    void aCancelledInstalmentsLateFeeIsReleased() {
        BnplConditionWorld.Agreement p4 = world.payIn4();
        world.serviceOn(DUE_2.plusDays(5), p4);
        BnplRefunds.Refunded refunded = world.refundOn(DUE_2.plusDays(6), p4, "the rest back", 450_00);
        BnplRefund refund = refunded.refund();
        assertThat(refund.adjustments()).extracting(adjustment -> adjustment.sequence() + " " + adjustment.kind() + " "
                + adjustment.principalCancelled().minorUnits() + " " + adjustment.whole() + " "
                + adjustment.lateFeeReleased().minorUnits())
                .containsExactly("4 UNBILLED 15000 true 0", "3 UNBILLED 15000 true 0", "2 BILLED_UNPAID 15000 true 500");
        assertThat(refund.lateFeeReleased()).isEqualTo(eur(5_00));
        assertThat(world.labelled(world.fakes.book.linesOf(BnplRefunds.POSTING_KEY_PREFIX + refund.id())))
                .contains("BNPL_FEE_INCOME DEBIT 500", "BNPL_FEES_DUE CREDIT 500");
        assertThat(world.balance(world.accounts(p4).feesDue())).isEqualTo(eur(0));
        assertThat(world.fakes.book.balance(world.feeIncome(), false)).as("income released in full").isEqualTo(eur(0));
        assertThat(refunded.closure()).isPresent();
        assertThat(world.fakes.payloads).anyMatch(payload -> payload.contains("\"lateFeeReleasedMinor\":\"500\""));
    }

    @Test
    @DisplayName("a refund that cancels a fee-bearing billed instalment whole while principal stays owed elsewhere releases"
            + " that instalment's fee only: 300.00 cancels 4 (unbilled) and 3 (billed, whole) - 3's fee released, 2's kept")
    void aWholeCancellationReleasesThatInstalmentsFeeOnly() {
        BnplConditionWorld.Agreement p4 = world.payIn4();
        world.serviceOn(DUE_3.plusDays(5), p4);
        assertThat(world.fees(p4)).hasSize(2);
        BnplRefund refund = world.refundOn(DUE_3.plusDays(6), p4, "two back", 300_00).refund();
        assertThat(refund.adjustments()).extracting(adjustment -> adjustment.sequence() + " " + adjustment.kind() + " "
                + adjustment.whole() + " " + adjustment.lateFeeReleased().minorUnits())
                .containsExactly("4 UNBILLED true 0", "3 BILLED_UNPAID true 500");
        assertThat(world.balance(world.accounts(p4).feesDue())).isEqualTo(eur(5_00));
        assertThat(world.obligation(p4).status()).isEqualTo(BnplObligationStatus.ACTIVE);
    }

    @Test
    @DisplayName("a refund that cancels only part of a fee-bearing instalment releases nothing: 330.00 cancels 4, 3 and"
            + " 30.00 of 2 - the fee stays owed on what 2 still owes")
    void aPartlyCancelledInstalmentKeepsItsFee() {
        BnplConditionWorld.Agreement p4 = world.payIn4();
        world.serviceOn(DUE_2.plusDays(5), p4);
        BnplRefund refund = world.refundOn(DUE_2.plusDays(6), p4, "part", 330_00).refund();
        assertThat(refund.lateFeeReleased()).isEqualTo(eur(0));
        assertThat(refund.adjustments()).extracting(BnplRefund.Adjustment::whole).containsExactly(true, true, false);
        assertThat(world.balance(world.accounts(p4).feesDue())).isEqualTo(eur(5_00));
    }

    @Test
    @DisplayName("never released twice, never a paid fee: a fee paid before the refund is kept (paid is paid); a second"
            + " refund after a release releases nothing more, and the assessment stays the one row it was")
    void neverReleasedTwice() {
        BnplConditionWorld.Agreement paidFirst = world.payIn4();
        world.serviceOn(DUE_2.plusDays(5), paidFirst);
        world.repayOn(DUE_2.plusDays(6), paidFirst, "the fee", 5_00);
        BnplRefund kept = world.refundOn(DUE_2.plusDays(6), paidFirst, "all", 450_00).refund();
        assertThat(kept.lateFeeReleased()).as("the paid fee is not refunded by a release").isEqualTo(eur(0));

        world.withFees(BnplTermsFixtures.payIn4().lateFeeCap(eur(5_00)));
        BnplConditionWorld.Agreement twice = world.payIn4();
        world.serviceOn(DUE_2.plusDays(5), twice);
        BnplRefund first = world.refundOn(DUE_2.plusDays(6), twice, "first", 450_00).refund();
        assertThat(first.lateFeeReleased()).isEqualTo(eur(5_00));
        BnplRefund second = world.refundOn(DUE_2.plusDays(6), twice, "second", 150_00).refund();
        assertThat(second.lateFeeReleased()).isEqualTo(eur(0));
        assertThat(second.adjustments()).isEmpty();
        assertThat(world.fakes.conditions.feesOf(twice.obligation())).hasSize(1);
    }

    @Test
    @DisplayName("the release's ranks at commit: an adjustment releasing a fee on a part-cancelled or unbilled instalment"
            + " is refused by its shape")
    void theReleasesShape() {
        assertThatThrownBy(() -> new BnplRefund.Adjustment(BnplConditionWorld.IDS.next(), 2, UUID.randomUUID(),
                RefundAllocation.Cancellation.Kind.BILLED_UNPAID, eur(30_00), eur(0), false, eur(5_00)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BnplRefund.Adjustment(BnplConditionWorld.IDS.next(), 4, UUID.randomUUID(),
                RefundAllocation.Cancellation.Kind.UNBILLED, eur(150_00), eur(0), true, eur(5_00)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new BnplRefund.Adjustment(BnplConditionWorld.IDS.next(), 2, UUID.randomUUID(),
                RefundAllocation.Cancellation.Kind.BILLED_UNPAID, eur(0), eur(0), true, eur(5_00)).lateFeeReleased())
                .as("a fee-only row is a release").isEqualTo(eur(5_00));
    }

    // ------------------------------------------------------------------ helpers

    private BnplPurchaseStore.Owned owned(BnplConditionWorld.Agreement agreement) {
        return new BnplPurchaseStore.Owned(world.obligation(agreement),
                world.fakes.purchases.rows.get(agreement.purchase().value()),
                world.fakes.purchases.agreements.get(agreement.agreement().value()), eur(BnplConditionWorld.GROSS));
    }

    private static BnplFeeAssessment fee(BnplConditionWorld.Agreement agreement, BnplServicingStore.Locked locked,
            UUID instalment, java.time.LocalDate date, long minor) {
        return new BnplFeeAssessment(BnplConditionWorld.IDS.next(), agreement.obligation(), instalment, BnplFeeKind.LATE,
                date, eur(minor), locked.agreement(), locked.agreementVersion(),
                com.finapp.ledger.JournalEntryId.of(BnplConditionWorld.IDS.next()), BnplConditionWorld.CORRELATION);
    }
}
