package com.finapp.lending;

import com.finapp.platform.security.Actor;
import java.sql.Connection;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The one self-dealing judgement every servicing correction makes before it locks anything (`P11-TSK-020` built it in
 * {@link RepaymentReversals}; `P11-TSK-025`'s {@link Waivers} is its second caller; ADR-0099 point 2, {@code INV-LND-11}, the
 * Phase 10 to 11 transition's R1): an operator whose party holds the loan - or one the platform cannot place in a party
 * (fail closed) - never proposes, approves or rejects a correction on it.
 */
final class LoanSelfDealing {

    private LoanSelfDealing() {}

    /**
     * The loan's party - a frozen column, read unlocked so any lock the act takes comes after - once the actor is judged
     * not to be of it.
     *
     * @param act what was attempted, as the refusal's record names it
     * @throws RepaymentReversals.SelfDealingRefused the actor's party holds the loan, or the actor has none the platform knows
     */
    static UUID judge(LoanStore loans, LoanActingParty actingParties, Connection unitOfWork, LoanId loan, Actor actor,
            String act, Supplier<? extends RuntimeException> notFound) {
        UUID party = loans.partyOf(unitOfWork, loan).orElseThrow(notFound);
        Optional<UUID> actorsParty = actingParties.partyOf(unitOfWork, actor);
        if (actorsParty.isEmpty() || actorsParty.get().equals(party)) {
            throw new RepaymentReversals.SelfDealingRefused(loan, act);
        }
        return party;
    }
}
