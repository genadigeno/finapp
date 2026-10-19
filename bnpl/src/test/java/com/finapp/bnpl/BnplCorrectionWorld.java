package com.finapp.bnpl;

import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.money.Money;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The hermetic world of the correction suites (`P12-TSK-024`): {@link BnplConditionWorld} - T-pur, T-svc with its
 * collection, condition and late-fee slot, T-rep and T-ref over {@link BnplApplicationFakes}, pay-in-4 terms carrying the
 * plan's late fee - with T-rev ({@link BnplRepaymentReversals}) and T-wvr ({@link BnplWaivers}) wired over the fakes'
 * {@link BnplApplicationFakes.Corrections} (bnpl V016 in memory), and three operators: {@link #alice} and {@link #bob} of
 * parties of their own, and {@link #insider}, whose party is the customer's. Every act runs as one transaction of the
 * fakes, the operator in scope (the ledger's reversal takes its actor from the scope, as the real one does).
 */
@SuppressWarnings("try") // The SecurityContext Scope is used for its close side effect (the idiom).
final class BnplCorrectionWorld {

    final BnplConditionWorld base = new BnplConditionWorld();
    final BnplApplicationFakes fakes = base.fakes;
    /** Whose party each operator is - the acting-party port's answer; an operator absent from it has none. */
    final Map<String, UUID> operatorParties = new LinkedHashMap<>();
    final BnplActingParty actingParties = (uow, actor) -> Optional.ofNullable(operatorParties.get(actor.id()));
    final BnplRepaymentReversals reversals;
    final BnplWaivers waivers;
    final Actor alice = new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER);
    final Actor bob = new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER);
    final Actor insider = new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER);

    BnplCorrectionWorld() {
        reversals = new BnplRepaymentReversals(fakes.corrections, fakes.servicing, fakes.purchases, fakes.credit,
                fakes.capital, fakes.book, actingParties, fakes.outbox, fakes.audit, BnplConditionWorld.IDS,
                BnplConditionWorld.CLOCK);
        waivers = new BnplWaivers(fakes.corrections, fakes.servicing, fakes.purchases, fakes.wallets, fakes.book,
                base.closures, actingParties, fakes.outbox, fakes.audit, BnplConditionWorld.IDS, BnplConditionWorld.CLOCK);
        operatorParties.put(alice.id(), UUID.randomUUID());
        operatorParties.put(bob.id(), UUID.randomUUID());
        operatorParties.put(insider.id(), base.party);
        fakes.credit.profiled = true;
    }

    /** {@code work} at 11:00 UTC on {@code date}, as one transaction with {@code actor} in scope. */
    <T> T on(LocalDate date, Actor actor, Supplier<T> work) {
        fakes.now = date.atTime(11, 0).toInstant(ZoneOffset.UTC);
        try (SecurityContext.Scope scope = SecurityContext.enter(actor)) {
            return fakes.inTransaction(work);
        }
    }

    BnplRepaymentReversal propose(LocalDate date, BnplConditionWorld.Agreement agreement, UUID repayment, Actor actor) {
        return on(date, actor, () -> reversals.propose(BnplApplicationFakes.UOW, agreement.agreement(), repayment,
                "applied to the wrong agreement", actor, BnplConditionWorld.CORRELATION));
    }

    BnplRepaymentReversals.Decided approve(LocalDate date, BnplConditionWorld.Agreement agreement, UUID repayment,
            UUID reversal, Actor actor) {
        return on(date, actor, () -> reversals.approve(BnplApplicationFakes.UOW, agreement.agreement(), repayment,
                reversal, "checked against the bank statement", actor, BnplConditionWorld.CORRELATION));
    }

    BnplRepaymentReversals.Decided reject(LocalDate date, BnplConditionWorld.Agreement agreement, UUID repayment,
            UUID reversal, Actor actor) {
        return on(date, actor, () -> reversals.reject(BnplApplicationFakes.UOW, agreement.agreement(), repayment,
                reversal, "not a mistake after all", actor, BnplConditionWorld.CORRELATION));
    }

    BnplWaiver proposeWaiver(LocalDate date, BnplConditionWorld.Agreement agreement, UUID assessment, long minor,
            Actor actor) {
        return on(date, actor, () -> waivers.propose(BnplApplicationFakes.UOW, agreement.agreement(), assessment,
                eur(minor), "goodwill after a complaint", actor, BnplConditionWorld.CORRELATION));
    }

    BnplWaivers.Decided approveWaiver(LocalDate date, BnplConditionWorld.Agreement agreement, UUID waiver, Actor actor) {
        return on(date, actor, () -> waivers.approve(BnplApplicationFakes.UOW, agreement.agreement(), waiver,
                "complaint upheld", actor, BnplConditionWorld.CORRELATION));
    }

    BnplWaivers.Decided rejectWaiver(LocalDate date, BnplConditionWorld.Agreement agreement, UUID waiver, Actor actor) {
        return on(date, actor, () -> waivers.reject(BnplApplicationFakes.UOW, agreement.agreement(), waiver,
                "no grounds", actor, BnplConditionWorld.CORRELATION));
    }

    /** The obligation's fee assessments, oldest first. */
    List<BnplFeeAssessment> fees(BnplConditionWorld.Agreement agreement) {
        return fakes.conditions.feesOf(agreement.obligation());
    }

    /** The billed item of instalment {@code sequence}, as the due state reads it. */
    BnplPurchaseStore.BilledItem item(BnplConditionWorld.Agreement agreement, int sequence) {
        UUID instalment = base.instalment(agreement, sequence).id();
        return fakes.purchases.billedItems(BnplApplicationFakes.UOW, agreement.obligation()).stream()
                .filter(row -> row.instalment().equals(instalment)).findFirst().orElseThrow();
    }

    /** {@code BNPL_FEE_INCOME}'s balance (credit-normal) - every world here holds one agreement's fees. */
    Money feeIncome() {
        return fakes.book.balance(base.feeIncome(), false);
    }

    static Money eur(long minor) {
        return BnplConditionWorld.eur(minor);
    }
}
