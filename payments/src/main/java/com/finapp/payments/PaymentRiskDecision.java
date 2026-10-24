package com.finapp.payments;

import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.money.Money;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The checkout payment's risk seam (`P13-TSK-010`; {@code PHASE_13_PLAN.md} §7.3, §12.1, §12.3; ADR-0108;
 * {@code INV-RSK-01}, {@code INV-RSK-02}) - payments' own interface, implemented in {@code app} over risk (payments
 * gains no {@code risk} edge, {@code PaymentsModuleIsolationTest}). A <strong>required constructor parameter</strong>
 * of {@link PaymentConfirmation} with no defaulted overload: a skipped control must not compile
 * ({@code PaymentConfirmationSeamIsRequiredTest}, the Phase 4 seam contract).
 *
 * <p><strong>Consulted in Tx1, once per intent.</strong> {@link PaymentConfirmation} asks it inside the confirmation's
 * first transaction, on that transaction's own unit of work, with the intent row already locked {@code FOR UPDATE},
 * <em>after</em> the eligibility checks - the instrument re-resolved, the credit account still open, the routing plan
 * computed (a plan with no rail is routing's refusal, committed without asking) and, for the wallet, the book rail's
 * account pair locked - and <em>before</em> anything of the routing decision, the attempt or the intent's move is
 * written. Money rows first, risk's rows last ({@code DISTRIBUTED_EXECUTION.md} §3, the Phase 13 lock order). The
 * intent's id is the judgement's command reference: one assessment per intent, whoever confirms it how often.
 *
 * <p><strong>Every confirmation is offered</strong>, whatever its instrument and wherever it credits: the
 * implementation judges what it judges (the merchant-bound card and wallet payments) and answers {@code PERMIT} for the
 * rest. An implementation writes no payments row and touches no hold ({@code INV-RSK-02}): payments commits the
 * outcome. {@link PaymentVerdict.Kind#HOLD} is honoured for a card ({@link InstrumentKind#CARD_TOKEN}) alone - payments
 * refuses it loudly for any other instrument, a wiring defect (C2: a wallet has no native hold at confirmation).
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
@FunctionalInterface
public interface PaymentRiskDecision<T> {

    /** Judges {@code payment} under the locked intent, on the confirmation's own unit of work. */
    PaymentVerdict check(T unitOfWork, Judged payment);

    /**
     * The payment as judged, before anything of its dispatch exists: the intent's id, the payer's party, the
     * instrument's kind as re-resolved at the act, the payer's wallet ledger account when the wallet is the instrument,
     * the ledger account the payment credits (a merchant's payable for a checkout, the payer's own wallet for a top-up),
     * and the amount. Identifiers and the amount only - never a token ({@code INV-PAY-02}).
     */
    record Judged(
            PaymentIntentId intentId,
            UUID partyId,
            InstrumentKind instrument,
            Optional<LedgerAccountId> debitAccount,
            LedgerAccountId creditAccount,
            Money amount) {

        public Judged {
            Objects.requireNonNull(intentId, "intentId must not be null");
            Objects.requireNonNull(partyId, "partyId must not be null");
            Objects.requireNonNull(instrument, "instrument must not be null");
            Objects.requireNonNull(debitAccount, "debitAccount must not be null");
            Objects.requireNonNull(creditAccount, "creditAccount must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            if ((instrument == InstrumentKind.WALLET) != debitAccount.isPresent()) {
                throw new IllegalArgumentException(
                        "a judged payment names a debit account exactly when the wallet is its instrument");
            }
        }

        /** The intent's id only - never a subject or the amount ({@code INV-AUD-02}). */
        @Override
        public String toString() {
            return "PaymentRiskDecision.Judged[" + intentId + ", " + instrument + "]";
        }
    }
}
