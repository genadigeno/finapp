package com.finapp.app.risk;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.payments.InstrumentKind;
import com.finapp.payments.PaymentRiskDecision;
import com.finapp.payments.PaymentVerdict;
import com.finapp.risk.CommandRef;
import com.finapp.risk.RiskDecision;
import com.finapp.risk.RiskEvaluation;
import com.finapp.risk.RiskOperation;
import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Payments' checkout payment risk seam, answered by risk (`P13-TSK-010`; {@code PHASE_13_PLAN.md} sections 7.3, 12.1,
 * 12.3; ADR-0108 decisions 1 and 3; {@code INV-RSK-01}, {@code INV-RSK-02}) - T-eval for {@code (CARD_PAYMENT | WALLET_PAYMENT,
 * intent id)} on the confirmation's own connection, inside its Tx1, under the intent row's lock and after the money rows
 * the confirmation takes (the credit account {@code FOR SHARE}; for the wallet, the book rail's pair {@code FOR UPDATE}).
 *
 * <p><strong>What it judges.</strong> A payment that credits a {@code MERCHANT_PAYABLE} - a checkout - paid by a card
 * ({@code CARD_PAYMENT}) or the payer's wallet ({@code WALLET_PAYMENT}). Everything else is answered {@code PERMIT} with
 * no assessment: a wallet top-up (the pay-in, outside Phase 13's judged operations) and a pay-by-bank checkout (no
 * operation in risk's vocabulary judges it - recorded for the owner). The command reference is the intent's id: one
 * assessment per intent, so a re-evaluation of the same intent replays the sealed decision.
 *
 * <p><strong>The mapping</strong> ({@link #verdict}):
 * <ul>
 *   <li>{@code ALLOW} - a recorded fail-safe {@code ALLOW(NO_POLICY_ACTIVE)}/{@code ALLOW(RISK_UNAVAILABLE)} included
 *       (C1: below EUR 250) - and a {@code CHALLENGE} the acting session satisfied → {@code PERMIT}: routed and
 *       dispatched, counted once in the windows by T-eval;</li>
 *   <li>{@code HOLD} ({@code CARD_PAYMENT} only, slice 2) → {@code HOLD} with the pinned version's hold validity: the
 *       card authorised, the capture deferred; <strong>not counted</strong> (a window counts what the decision
 *       permits; `-009`'s reading for {@code HOLD});</li>
 *   <li>{@code DENY} (a rule, a band, C1's fail-safe at or above EUR 250) and an unsatisfied {@code CHALLENGE} (the
 *       assessment's {@code CHALLENGE_REQUIRED}; the door's advice makes it the race's answer) → {@code REFUSE}: the
 *       intent ends {@code FAILED(RISK_REFUSED)}, nothing routed or sent, nothing counted.</li>
 * </ul>
 *
 * <p><strong>Writes nothing of payments'</strong> ({@code INV-RSK-02}): the one read it makes of another module is the
 * ledger accounts' purpose and {@code owner_ref}, with {@link LedgerAccountStore#lockForShare} on rows the confirmation
 * <em>already holds</em> - granted without a new lock, so no order changes ({@code TransferSubjects}' reasoning).
 * {@code RiskEvaluation.RaceLost} - unreachable, the confirmation serialising its intent first - propagates and rolls
 * Tx1 back with its count; a failure of the lookup or the seal aborts Tx1 by `-004`'s design: a judged payment is never
 * committed without its assessment.
 */
public final class RiskPaymentRiskDecision implements PaymentRiskDecision<Connection> {

    /** A ledger account's purpose and owner, read on the confirmation's unit of work, inside its lock. */
    @FunctionalInterface
    public interface Accounts {

        /** The account the confirmation already locked - its purpose and {@code owner_ref}. */
        Account of(Connection unitOfWork, LedgerAccountId account);

        /** What the seam reads of a ledger account. */
        record Account(AccountPurpose purpose, Optional<UUID> ownerRef) {
            public Account {
                Objects.requireNonNull(purpose, "purpose");
                Objects.requireNonNull(ownerRef, "ownerRef");
            }
        }

        /** The production read: {@code lockForShare} on the row the confirmation already holds. */
        static Accounts over(LedgerAccountStore<Connection> ledgerAccounts) {
            Objects.requireNonNull(ledgerAccounts, "ledgerAccounts");
            return (unitOfWork, account) -> ledgerAccounts.lockForShare(unitOfWork, account)
                    .map(row -> new Account(row.purpose(), row.ownerRef()))
                    .orElseThrow(() -> new IllegalStateException("a payment's ledger account " + account
                            + " is gone under the confirmation's own lock - an invariant is broken"));
        }
    }

    private final RiskEvaluation evaluation;
    private final Accounts accounts;

    public RiskPaymentRiskDecision(RiskEvaluation evaluation, Accounts accounts) {
        this.evaluation = Objects.requireNonNull(evaluation, "evaluation");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
    }

    @Override
    public PaymentVerdict check(Connection unitOfWork, Judged payment) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(payment, "payment");
        Optional<RiskOperation> operation = operationOf(payment.instrument());
        if (operation.isEmpty()) {
            return PaymentVerdict.permit();
        }
        Accounts.Account credited = accounts.of(unitOfWork, payment.creditAccount());
        if (credited.purpose() != AccountPurpose.MERCHANT_PAYABLE) {
            // A wallet top-up - the pay-in - is not a judged operation in Phase 13: permitted, no assessment.
            return PaymentVerdict.permit();
        }
        UUID merchant = credited.ownerRef().orElseThrow(() -> new IllegalStateException(
                "a merchant's payable names its merchant (owner_ref) - an invariant is broken"));
        Optional<UUID> source = payment.debitAccount()
                .map(wallet -> accounts.of(unitOfWork, wallet).ownerRef().orElseThrow(() ->
                        new IllegalStateException("a payer's wallet names its customer account (owner_ref)")));
        return verdict(evaluation.evaluate(unitOfWork, PaymentRiskJudgement.request(
                operation.get(), CommandRef.of(payment.intentId().value()), payment.amount(), payment.partyId(),
                merchant, source)));
    }

    /** The judged operation of an instrument: the card's and the wallet's; a bank pay-in has none. */
    static Optional<RiskOperation> operationOf(InstrumentKind instrument) {
        return switch (instrument) {
            case CARD_TOKEN -> Optional.of(RiskOperation.CARD_PAYMENT);
            case WALLET -> Optional.of(RiskOperation.WALLET_PAYMENT);
            case BANK_ACCOUNT -> Optional.empty();
        };
    }

    /** The sealed assessment as payments' three-valued verdict - nothing of the judgement crosses. */
    static PaymentVerdict verdict(RiskEvaluation.Assessed assessed) {
        if (assessed.permits()) {
            return PaymentVerdict.permit();
        }
        if (assessed.decision() == RiskDecision.HOLD) {
            return PaymentVerdict.hold(assessed.holdValidity().orElseThrow(() -> new IllegalStateException(
                    "a HOLD answers its pinned version's hold validity")));
        }
        return PaymentVerdict.refuse();
    }
}
