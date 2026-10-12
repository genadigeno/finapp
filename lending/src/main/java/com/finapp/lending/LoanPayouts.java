package com.finapp.lending;

import com.finapp.ledger.HoldId;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The external payout's leg through payments - a port {@code app} implements, because lending has no build edge to
 * {@code payments} (A16; ADR-0097 points 4, 5 and 8). Lending never implements payments' {@code OutboundCreditComposition}
 * and payments never calls lending: lending <em>instructs</em> a withdrawal on its recorded instruction and <em>reads</em>
 * its outcome - so no lending transaction ever waits on a payments row, and no payments transaction on a lending one.
 */
public interface LoanPayouts {

    /** The instruction prefix: {@code lending:<loanId>} - payments' key {@code payments.withdrawal:lending/<loanId>}. */
    String INSTRUCTION_NAMESPACE = "lending";

    /**
     * Asks payments to withdraw the payout's funds to the borrower's verified destination, adopting the hold the
     * disbursement placed - payments' own system-actor entry, its own transactions and wire call (ADR-0046). Called with
     * NO lending transaction open and no lending lock held (PHASE_11_PLAN.md section 7.3 (d)). Re-asking under the same
     * instruction is safe: payments replays its recorded answer, or converges on its row.
     *
     * @return payments took it (its withdrawal, whatever the scheme then said), refused it, or could not answer now
     */
    Dispatch dispatch(Instruction instruction);

    /**
     * Payments' word on the payout's withdrawal, read plainly in the caller's transaction - never locked (ADR-0097 point
     * 8): concluded {@code PAID_OUT} or {@code FAILED} with payments' conclusion instant, or still undecided
     * ({@code DISPATCHED} or {@code UNKNOWN} there).
     */
    Outcome outcome(Connection unitOfWork, LoanId loan, UUID withdrawal);

    /** One payout's instruction - every field a stored fact; never an account identifier ({@code INV-RAIL-03}). */
    record Instruction(
            LoanId loan,
            UUID party,
            UUID paymentMethod,
            LedgerAccountId wallet,
            HoldId hold,
            Money amount,
            CorrelationId correlation) {

        public Instruction {
            Objects.requireNonNull(loan, "loan must not be null");
            Objects.requireNonNull(party, "party must not be null");
            Objects.requireNonNull(paymentMethod, "paymentMethod must not be null");
            Objects.requireNonNull(wallet, "wallet must not be null");
            Objects.requireNonNull(hold, "hold must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
            if (!amount.isPositive()) {
                throw new IllegalArgumentException("a payout moves a positive amount");
            }
        }

        /** The recorded instruction payments keys on: {@code lending:<loanId>}. */
        public String reference() {
            return INSTRUCTION_NAMESPACE + ":" + loan.value();
        }

        @Override
        public String toString() {
            return "Instruction[" + reference() + "]";
        }
    }

    /** What payments answered the dispatch. */
    sealed interface Dispatch {

        /** Payments holds the withdrawal - dispatched, already concluded, or awaiting the scheme's word. */
        record Accepted(UUID withdrawal) implements Dispatch {
            public Accepted {
                Objects.requireNonNull(withdrawal, "withdrawal must not be null");
            }
        }

        /** Payments refused or could not route it: nothing was sent, the hold is the instructor's to release. */
        record Refused(NotDispatchedReason reason) implements Dispatch {
            public Refused {
                Objects.requireNonNull(reason, "reason must not be null");
            }
        }

        /**
         * No answer now - another flight holds the instruction, or no push rail is composed on this deployment: the
         * claim's permit lapses and a later sweep re-drives under the same instruction.
         */
        record NotNow() implements Dispatch {}
    }

    /** Payments' word on the withdrawal. */
    sealed interface Outcome {

        /** Still awaiting the scheme's word ({@code DISPATCHED} or {@code UNKNOWN} at payments): interest waits. */
        record Undecided() implements Outcome {}

        /** Payments concluded it - {@code PAID_OUT} or {@code FAILED} - at {@code at} (its history's terminal move). */
        record Concluded(PayoutStatus status, Instant at) implements Outcome {
            public Concluded {
                Objects.requireNonNull(status, "status must not be null");
                Objects.requireNonNull(at, "at must not be null");
                if (status != PayoutStatus.PAID_OUT && status != PayoutStatus.FAILED) {
                    throw new IllegalArgumentException("payments concludes a payout PAID_OUT or FAILED");
                }
            }
        }
    }
}
