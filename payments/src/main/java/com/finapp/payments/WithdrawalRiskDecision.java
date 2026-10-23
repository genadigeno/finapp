package com.finapp.payments;

import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.money.Money;
import java.util.Objects;
import java.util.UUID;

/**
 * The withdrawal's risk seam (`P13-TSK-009`; {@code PHASE_13_PLAN.md} §7.3, §12.1; ADR-0062 §6's hold-then-dispatch;
 * {@code INV-BAL-04}, {@code INV-RSK-02}) - payments' own interface, implemented in {@code app} over risk (payments
 * gains no {@code risk} edge, {@code PaymentsModuleIsolationTest}). A <strong>required constructor parameter</strong>
 * of {@link Withdrawals} with no defaulted overload: a skipped control must not compile (the Phase 4 seam contract,
 * {@code transfers.TransferRiskDecision}'s, held by {@code WithdrawalsSeamIsRequiredTest}).
 *
 * <p><strong>Consulted in-lock, once.</strong> {@link Withdrawals} asks it inside the dispatch transaction, on that
 * transaction's own unit of work, <em>after</em> the ledger hold is placed under the wallet account's lock - so only an
 * affordable withdrawal is ever judged, and an unfunded one aborts with no judgement - and <em>before</em> the row is
 * committed; the verdict decides the state the withdrawal is born in ({@link WithdrawalVerdict}). The id it is handed is
 * the withdrawal's own, minted for this birth: the judgement's command reference, one per withdrawal. An
 * implementation writes no payments row and touches no hold ({@code INV-RSK-02}): payments commits the outcome.
 *
 * <p><strong>Only the person's withdrawal is judged.</strong> The SYSTEM entry ({@code withdrawOnInstruction},
 * lending's external payout) carries out a credit decision already made - credit judged the loan with risk's profile
 * signal - and is not consulted here: a second decision over one loan, counted as the customer's own withdrawal, would
 * be neither credit's nor the customer's (`P13-TSK-009`'s design, recorded as an owner item).
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
@FunctionalInterface
public interface WithdrawalRiskDecision<T> {

    /** Judges {@code withdrawal} under the held wallet lock, on the dispatch's own unit of work. */
    WithdrawalVerdict check(T unitOfWork, Judged withdrawal);

    /**
     * The withdrawal as judged, before it exists: its minted id, whose it is (the party and the customer), the wallet's
     * ledger account the hold was placed on, the instrument it pays to, and the amount. Identifiers and the amount
     * only - never the destination reference ({@code INV-RAIL-03}).
     */
    record Judged(
            WithdrawalId id,
            UUID partyId,
            UUID customerId,
            LedgerAccountId walletAccountId,
            UUID paymentMethodId,
            Money amount) {

        public Judged {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(partyId, "partyId must not be null");
            Objects.requireNonNull(customerId, "customerId must not be null");
            Objects.requireNonNull(walletAccountId, "walletAccountId must not be null");
            Objects.requireNonNull(paymentMethodId, "paymentMethodId must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
        }

        /** The withdrawal's id only - never a subject or the amount ({@code INV-AUD-02}). */
        @Override
        public String toString() {
            return "WithdrawalRiskDecision.Judged[" + id + "]";
        }
    }
}
