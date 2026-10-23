package com.finapp.app.risk;

import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.payments.WithdrawalRiskDecision;
import com.finapp.payments.WithdrawalVerdict;
import com.finapp.risk.CommandRef;
import com.finapp.risk.RiskDecision;
import com.finapp.risk.RiskEvaluation;
import java.sql.Connection;
import java.util.Objects;
import java.util.UUID;

/**
 * Payments' withdrawal risk seam, answered by risk (`P13-TSK-009`; {@code PHASE_13_PLAN.md} sections 7.3, 12.1, 12.3;
 * ADR-0108 decisions 1 and 3; {@code INV-RSK-01}, {@code INV-RSK-02}) - T-eval for {@code (WITHDRAWAL, withdrawal id)} on
 * the dispatch's own connection, inside its transaction, after the hold was placed under the wallet account's lock.
 *
 * <p><strong>The mapping</strong> ({@link #verdict}):
 * <ul>
 *   <li>{@code ALLOW} - a recorded fail-safe {@code ALLOW(NO_POLICY_ACTIVE)}/{@code ALLOW(RISK_UNAVAILABLE)} included -
 *       and a {@code CHALLENGE} the acting session satisfied → {@code PERMIT}: born {@code DISPATCHED}, counted once in
 *       the windows by T-eval;</li>
 *   <li>{@code HOLD} → {@code HOLD} with the pinned version's hold validity ({@code Assessed.holdValidity}): born
 *       {@code HELD}, the hold kept; <strong>not counted</strong> - a window counts what the decision permits, and a
 *       {@code HOLD} permits nothing until a person decides (OQ-1's reading confirmed for {@code HOLD});
 *       {@code CaseOpener} is called by T-eval ({@code NONE} until `-016`);</li>
 *   <li>{@code DENY} (a rule, a band, the C1 fail-safe band - {@code DENY} at every band for withdrawals),
 *       {@code REVIEW}, and an unsatisfied {@code CHALLENGE} (the assessment's {@code CHALLENGE_REQUIRED}; the door's
 *       advice makes it the race's answer) → {@code REFUSE}: born {@code FAILED(RISK_REFUSED)}, the hold released by
 *       payments, nothing counted.</li>
 * </ul>
 *
 * <p><strong>Writes nothing of payments'</strong> ({@code INV-RSK-02}): the one read it makes of another module is the
 * wallet's {@code owner_ref}, re-read with {@link LedgerAccountStore#lockForShare} on the row the dispatch
 * <em>already holds</em> {@code FOR UPDATE} (the hold's lock) - granted without a new lock, so no order changes
 * ({@code TransferSubjects}' reasoning). {@code RiskEvaluation.RaceLost} - unreachable, the withdrawal id being minted
 * fresh under the person's claim - propagates and rolls the dispatch back with its count; a failure of the lookup or the
 * seal aborts the dispatch by `-004`'s design: a judged withdrawal is never committed without its assessment.
 */
public final class RiskWithdrawalRiskDecision implements WithdrawalRiskDecision<Connection> {

    /** The wallet's customer account id (its {@code owner_ref}), read on the dispatch's unit of work, inside its lock. */
    @FunctionalInterface
    public interface WalletOwner {
        UUID of(Connection unitOfWork, LedgerAccountId wallet);

        /** The production read: {@code lockForShare} on the row the dispatch already holds {@code FOR UPDATE}. */
        static WalletOwner over(LedgerAccountStore<Connection> ledgerAccounts) {
            Objects.requireNonNull(ledgerAccounts, "ledgerAccounts");
            return (unitOfWork, wallet) -> ledgerAccounts.lockForShare(unitOfWork, wallet)
                    .flatMap(LedgerAccount::ownerRef)
                    .orElseThrow(() -> new IllegalStateException("a withdrawal's wallet " + wallet + " is gone or owned"
                            + " by nobody under the dispatch's own lock - an invariant is broken"));
        }
    }

    private final RiskEvaluation evaluation;
    private final WalletOwner owners;

    public RiskWithdrawalRiskDecision(RiskEvaluation evaluation, WalletOwner owners) {
        this.evaluation = Objects.requireNonNull(evaluation, "evaluation");
        this.owners = Objects.requireNonNull(owners, "owners");
    }

    @Override
    public WithdrawalVerdict check(Connection unitOfWork, Judged withdrawal) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(withdrawal, "withdrawal");
        UUID source = owners.of(unitOfWork, withdrawal.walletAccountId());
        return verdict(evaluation.evaluate(unitOfWork, WithdrawalRiskJudgement.request(
                CommandRef.of(withdrawal.id().value()), withdrawal.amount(), withdrawal.partyId(), source)));
    }

    /** The sealed assessment as payments' three-valued verdict - nothing of the judgement crosses. */
    static WithdrawalVerdict verdict(RiskEvaluation.Assessed assessed) {
        if (assessed.permits()) {
            return WithdrawalVerdict.permit();
        }
        if (assessed.decision() == RiskDecision.HOLD) {
            return WithdrawalVerdict.hold(assessed.holdValidity().orElseThrow(() -> new IllegalStateException(
                    "a HOLD answers its pinned version's hold validity")));
        }
        return WithdrawalVerdict.refuse();
    }
}
