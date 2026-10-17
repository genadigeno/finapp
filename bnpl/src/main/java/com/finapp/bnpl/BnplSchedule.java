package com.finapp.bnpl;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * A BNPL Schedule - the instalments a purchase's principal falls due in (`P12-TSK-008`; ADR-0102 decision 2,
 * {@code PHASE_12_PLAN.md} section 12.2; {@code INV-BNPL-05}). Version 1 of pay-in-4's is {@link BnplScheduleEngineV1}'s.
 *
 * <p><strong>Conservation is asserted at construction</strong>, so a value of this type conserves or does not exist:
 * the instalments are numbered 1 to n; the first falls due on the purchase date (instalment 1 is collected at purchase,
 * A5) and each later one strictly after its predecessor; every instalment's principal is positive and in the schedule's
 * currency; and, stated as the invariant reads, Σ instalment principal = principal exactly. A violation is a defect of
 * the engine that built it ({@link IllegalArgumentException}), never a business refusal - those are
 * {@link BnplScheduleOutcome.Refused}. How the principal is split and how far apart the dates are is the engine
 * version's own rule, held by its tests, not this record's: the record is what every version must satisfy.
 *
 * <p><strong>A projection, not yet the stored fact.</strong> The acceptance (`P12-TSK-013`) records it as the
 * obligation's BNPL Schedule version 1 ({@code bnpl_schedule}, {@code bnpl_instalment}); the offer (`P12-TSK-011`)
 * shows the same value as its illustration, which is never stored as a schedule version ({@code GLOSSARY.md}).
 *
 * <p>Named for the engine version that produced it, so a schedule is re-derivable by that version years later
 * ({@code BnplReplayProof}, `P12-TSK-025`). Amounts are RESTRICTED-FINANCIAL: {@link #toString()} names the engine and
 * the instalment count only.
 *
 * @param engine the schedule engine version that produced it - a {@link BnplEngine.Kind#BNPL_SCHEDULE} version
 * @param currency every amount's currency - the terms'
 * @param principal the principal scheduled, {@code P} - the purchase's gross for version 1
 * @param purchaseDate {@code D1}, the purchase's business date - instalment 1's due date
 * @param instalments the instalments, in order
 */
public record BnplSchedule(
        BnplEngine engine, CurrencyCode currency, Money principal, LocalDate purchaseDate, List<Instalment> instalments) {

    public BnplSchedule {
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(purchaseDate, "purchaseDate");
        instalments = List.copyOf(Objects.requireNonNull(instalments, "instalments"));
        if (engine.kind() != BnplEngine.Kind.BNPL_SCHEDULE) {
            throw new IllegalArgumentException("a BNPL schedule is named for a BNPL schedule engine version");
        }
        if (instalments.isEmpty()) {
            throw new IllegalArgumentException("a BNPL schedule has at least one instalment");
        }
        if (!principal.isPositive() || !principal.currency().equals(currency)
                || principal.scale() != currency.minorUnits()) {
            throw new IllegalArgumentException("a BNPL schedule schedules a positive principal in its one currency, at"
                    + " the currency's scale");
        }
        Money scheduled = Money.zero(currency);
        LocalDate previous = null;
        for (int index = 0; index < instalments.size(); index++) {
            Instalment instalment = instalments.get(index);
            if (instalment.sequence() != index + 1) {
                throw new IllegalArgumentException("instalment " + (index + 1) + " is numbered in order");
            }
            if (previous == null ? !instalment.dueDate().equals(purchaseDate) : !instalment.dueDate().isAfter(previous)) {
                throw new IllegalArgumentException("instalment " + (index + 1) + " falls due on the purchase date if it"
                        + " is the first, and strictly after its predecessor otherwise");
            }
            if (!instalment.principal().currency().equals(currency)) {
                throw new IllegalArgumentException("instalment " + (index + 1) + " is in the schedule's currency");
            }
            scheduled = scheduled.plus(instalment.principal());
            previous = instalment.dueDate();
        }
        if (!scheduled.equals(principal)) {
            throw new IllegalArgumentException("a BNPL schedule conserves: its instalments' principal sums to its"
                    + " principal exactly (INV-BNPL-05)");
        }
    }

    /** The number of instalments, {@code n}. */
    public int instalmentCount() {
        return instalments.size();
    }

    /** Instalment 1 - due on the purchase date, collected at purchase (A5; pay-in-4's carries the remainder, OQ-3). */
    public Instalment atPurchase() {
        return instalments.get(0);
    }

    /** The engine and the instalment count only - never an amount. */
    @Override
    public String toString() {
        return "BnplSchedule[" + engine + ", " + instalments.size() + " instalments]";
    }

    /**
     * One BNPL Instalment of the schedule - the principal falling due on {@code dueDate}. Pay-in-4 charges no interest,
     * so the principal is the whole amount due. Judged in itself (numbered from 1, a positive principal); how
     * instalments chain and conserve is the {@link BnplSchedule}'s judgement. {@link #toString()} names the sequence and
     * the due date only.
     *
     * @param sequence 1 to n
     * @param dueDate the day it falls due
     * @param principal its principal - positive
     */
    public record Instalment(int sequence, LocalDate dueDate, Money principal) {

        public Instalment {
            Objects.requireNonNull(dueDate, "dueDate");
            Objects.requireNonNull(principal, "principal");
            if (sequence < 1) {
                throw new IllegalArgumentException("an instalment is numbered from 1");
            }
            if (!principal.isPositive()) {
                throw new IllegalArgumentException("instalment " + sequence + " falls due for a positive principal");
            }
        }

        /** The sequence and due date only - never an amount. */
        @Override
        public String toString() {
            return "Instalment[" + sequence + ", due " + dueDate + "]";
        }
    }
}
