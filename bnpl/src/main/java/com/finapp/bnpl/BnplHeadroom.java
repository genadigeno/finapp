package com.finapp.bnpl;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.util.Objects;

/**
 * A currency's lending capital headroom as bnpl sees it (`P12-TSK-005`; ADR-0104 decision 3, PHASE_12_PLAN.md section
 * 12.9; {@code INV-BNPL-10}, {@code INV-LND-13}) - bnpl's own record, returned by {@link BnplCapital#underLock}, so
 * bnpl learns one figure and no lending type: the headroom left after the committed principal of loans awaiting
 * disbursement and every loan, line and BNPL obligation's principal outstanding. Not the capital, not the committed
 * term, not the outstanding term - what a BNPL judgement needs and nothing more.
 *
 * <p>A headroom may be below zero: a repudiated bank statement can withdraw capital already deployed (ADR-0096's
 * Follow-up, read by {@code P11-DOC-001}); then nothing is admitted until capital is recognised again.
 *
 * @param currency the currency judged
 * @param headroom the headroom in that currency, at its persisted scale; negative only after a withdrawal of evidence
 */
public record BnplHeadroom(CurrencyCode currency, Money headroom) {

    public BnplHeadroom {
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(headroom, "headroom must not be null");
        if (!headroom.currency().equals(currency)) {
            throw new IllegalArgumentException("a headroom is held in the currency it was judged in");
        }
    }

    /**
     * Whether a purchase's gross {@code principal} can be funded - the gross, never the merchant's net (ADR-0104
     * decision 1: the bound is on what the platform is owed; counting the gross errs low).
     */
    public boolean admits(Money principal) {
        Objects.requireNonNull(principal, "principal must not be null");
        if (!principal.currency().equals(currency)) {
            throw new IllegalArgumentException("a principal is judged against the headroom of its own currency");
        }
        if (!principal.isPositive()) {
            throw new IllegalArgumentException("a principal judged against capital is positive");
        }
        return headroom.minus(principal).minorUnits() >= 0;
    }

    /** The currency only - the headroom is an amount ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "BnplHeadroom[" + currency.code() + "]";
    }
}
