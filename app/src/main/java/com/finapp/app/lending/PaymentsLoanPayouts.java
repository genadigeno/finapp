package com.finapp.app.lending;

import com.finapp.lending.LoanId;
import com.finapp.lending.LoanPayout;
import com.finapp.lending.LoanPayoutStore;
import com.finapp.lending.LoanPayouts;
import com.finapp.lending.NotDispatchedReason;
import com.finapp.lending.PayoutStatus;
import com.finapp.party.CustomerStatus;
import com.finapp.party.PartyId;
import com.finapp.party.PartyStore;
import com.finapp.payments.NoEligibleRailException;
import com.finapp.payments.PaymentParticipants;
import com.finapp.payments.ProviderReference;
import com.finapp.payments.SystemWithdrawalRefusedException;
import com.finapp.payments.Withdrawal;
import com.finapp.payments.WithdrawalId;
import com.finapp.payments.WithdrawalStatus;
import com.finapp.payments.WithdrawalStore;
import com.finapp.payments.Withdrawals;
import com.finapp.platform.idempotency.IdempotencyInProgressException;
import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Lending's {@link LoanPayouts} over payments' withdrawal machinery (`P11-TSK-015`; ADR-0097 points 4, 5 and 8,
 * {@code PHASE_11_PLAN.md} section 7.3 (d)) - the composition root's half, because lending sees no payments module (A16).
 *
 * <p><strong>The dispatch</strong> is payments' SYSTEM entry ({@link Withdrawals#withdrawOnInstruction}): the payout's
 * recorded instruction {@code lending:<loanId>}, the net principal and the hold the disbursement placed, which payments
 * adopts under the wallet's lock. Payments consults this resolver under the instruction's claim, and only while no
 * withdrawal exists for it: the instruction must be lending's own recorded payout - {@code DISPATCHING}, naming this hold,
 * method and wallet (an unrecorded instruction is a defect, refused loudly, nothing written) - the borrower must have an
 * active customer record, and the method must still be the borrower's own active bank account; each of the last two is a
 * refusal payments RECORDS under the instruction's key and replays, as it does its routing refusal
 * ({@link NoEligibleRailException}) - so a refused instruction can never later produce a withdrawal, and lending's
 * {@code NOT_DISPATCHED} may release the hold. Another flight holding the key, or no push rail composed here, is no
 * answer now.
 *
 * <p><strong>The outcome</strong> is a plain read of payments' withdrawal by its instruction - never locked: concluded
 * {@code COMPLETED} or {@code FAILED} with its history's terminal instant, or undecided. No account identifier crosses
 * into lending: the destination is resolved here, inside payments' transaction, and never returned ({@code INV-RAIL-03}).
 */
public final class PaymentsLoanPayouts implements LoanPayouts {

    private final ObjectProvider<Withdrawals> engine;
    private final PaymentParticipants<Connection> participants;
    private final WithdrawalStore<Connection> withdrawals;
    private final PartyStore<Connection> parties;
    private final LoanPayoutStore payouts;

    public PaymentsLoanPayouts(
            ObjectProvider<Withdrawals> engine,
            PaymentParticipants<Connection> participants,
            WithdrawalStore<Connection> withdrawals,
            PartyStore<Connection> parties,
            LoanPayoutStore payouts) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.participants = Objects.requireNonNull(participants, "participants");
        this.withdrawals = Objects.requireNonNull(withdrawals, "withdrawals");
        this.parties = Objects.requireNonNull(parties, "parties");
        this.payouts = Objects.requireNonNull(payouts, "payouts");
    }

    @Override
    public Dispatch dispatch(Instruction instruction) {
        Objects.requireNonNull(instruction, "instruction");
        Withdrawals command = engine.getIfAvailable();
        if (command == null) {
            // No push rail on this deployment: no answer, the funds stay held, the dispatched-age gauge alerts.
            return new Dispatch.NotNow();
        }
        try {
            Withdrawals.Initiated initiated = command.withdrawOnInstruction(
                    new Withdrawals.Instruction(instruction.reference(), instruction.amount(), instruction.hold()),
                    unitOfWork -> resolved(unitOfWork, instruction));
            return new Dispatch.Accepted(initiated.id().value());
        } catch (NoEligibleRailException noRoute) {
            return new Dispatch.Refused(NotDispatchedReason.NO_ELIGIBLE_RAIL);
        } catch (SystemWithdrawalRefusedException refused) {
            // Only payments' RECORDED refusals are an answer: the instruction is refused for good, so no withdrawal can
            // be born under it and the hold is lending's to release. Anything else is a defect, loud.
            return switch (refused.reason()) {
                case CUSTOMER_UNAVAILABLE -> new Dispatch.Refused(NotDispatchedReason.CUSTOMER_UNAVAILABLE);
                case DESTINATION_UNAVAILABLE -> new Dispatch.Refused(NotDispatchedReason.DESTINATION_UNAVAILABLE);
                case NOT_A_SYSTEM_ACTOR, INSTRUCTION_MALFORMED, HOLD_NOT_ADOPTABLE -> throw refused;
            };
        } catch (IdempotencyInProgressException anotherFlight) {
            return new Dispatch.NotNow();
        }
    }

    @Override
    public Outcome outcome(Connection unitOfWork, LoanId loan, UUID withdrawal) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(withdrawal, "withdrawal");
        Withdrawal found = withdrawals.findByInstruction(unitOfWork, INSTRUCTION_NAMESPACE + ":" + loan.value())
                .filter(read -> read.id().value().equals(withdrawal))
                .orElseThrow(() -> new IllegalStateException("a dispatched payout's withdrawal is payments' record"));
        if (found.status() == WithdrawalStatus.COMPLETED || found.status() == WithdrawalStatus.FAILED) {
            return new Outcome.Concluded(
                    found.status() == WithdrawalStatus.COMPLETED ? PayoutStatus.PAID_OUT : PayoutStatus.FAILED,
                    withdrawals.concludedAt(unitOfWork, WithdrawalId.of(withdrawal))
                            .orElseThrow(() -> new IllegalStateException(
                                    "a concluded withdrawal's history records its terminal move")));
        }
        return new Outcome.Undecided();
    }

    /** Payments' resolver, inside its dispatch transaction: every refusal rolls the claim back, the key unburned. */
    private Withdrawals.Resolved resolved(Connection unitOfWork, Instruction instruction) {
        // The recorded instruction: lending's own payout, claimed, for exactly this hold, method and wallet.
        Optional<LoanPayout> recorded = payouts.find(unitOfWork, instruction.loan())
                .filter(payout -> payout.status() == PayoutStatus.DISPATCHING)
                .filter(payout -> payout.hold().equals(instruction.hold()))
                .filter(payout -> payout.paymentMethod().equals(instruction.paymentMethod()))
                .filter(payout -> payout.wallet().equals(instruction.wallet()));
        if (recorded.isEmpty()) {
            throw new IllegalStateException("a system withdrawal is asked only on lending's recorded payout instruction");
        }
        boolean customer = parties.findLiveCustomerFor(unitOfWork, PartyId.of(instruction.party()))
                .filter(found -> found.status() == CustomerStatus.ACTIVE)
                .isPresent();
        if (!customer) {
            throw new SystemWithdrawalRefusedException(SystemWithdrawalRefusedException.Reason.CUSTOMER_UNAVAILABLE);
        }
        PaymentParticipants.Wallet wallet = participants
                .walletOwnedBy(unitOfWork, instruction.party(), instruction.amount().currency())
                .filter(found -> found.account().equals(instruction.wallet()))
                .orElseThrow(() -> new IllegalStateException("the payout's wallet is the borrower's wallet in its currency"));
        ProviderReference destination = participants
                .bankDestinationOwnedBy(unitOfWork, instruction.party(), instruction.paymentMethod())
                .orElseThrow(() -> new SystemWithdrawalRefusedException(
                        SystemWithdrawalRefusedException.Reason.DESTINATION_UNAVAILABLE));
        return new Withdrawals.Resolved(instruction.party(), wallet.customerId(), wallet.account(), wallet.currency(),
                instruction.paymentMethod(), destination);
    }
}
