package com.finapp.reconciliation;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A parked cross-border return, as the four-eyes {@code TRANSFER_TO_ACCOUNT} that returns it needs it (`P9-TSK-023`,
 * the lifecycle document section 4, T-g): declared here, implemented in {@code app} over payments and crossborder.
 * At the proposal it judges, writing nothing; at the approval - inside the approval's transaction - it records the
 * person's return: the return fact ({@code applied_by = RESOLUTION}), the fee refunded, the payment
 * {@code RETURNED}. The machine refuses on every answer but the recorded one: the port never throws the machine's
 * own refusals.
 */
public interface ResolvedCorridorReturns {

    /** What the port found. */
    enum Judgement {
        /** The parked line names no cross-border credit: an ordinary transfer, nothing to record. */
        NOT_A_CORRIDOR_RETURN,
        /** The credit is completed and not returned, the target its customer's wallet: the transfer may proceed. */
        RETURNABLE,
        /** The person's return was recorded (at the approval only). */
        RECORDED,
        /** The credit's return already exists - applied from evidence, or by another resolution. */
        ALREADY_RETURNED,
        /** The credit is still in flight: its own outcome comes first. */
        NOT_COMPLETED,
        /** The credit failed: its return credits no one - an offset or a write-off, never a transfer. */
        FAILED,
        /** The target is not the credit's own customer's wallet in the parked currency. */
        NOT_THE_CUSTOMERS_WALLET
    }

    /** The parked return: the line's end-to-end reference, its value, and the transfer's chosen target. */
    record ParkedReturn(Optional<String> endToEndReference, Money amount, UUID targetAccountId) {
        public ParkedReturn {
            Objects.requireNonNull(endToEndReference, "endToEndReference must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            Objects.requireNonNull(targetAccountId, "targetAccountId must not be null");
        }
    }

    /** At the proposal: whether the transfer may proceed - nothing written. */
    Judgement judge(Connection unitOfWork, ParkedReturn parked);

    /** At the approval, in its transaction: the person's return recorded, or why not - nothing written then. */
    Judgement record(Connection unitOfWork, ParkedReturn parked, UUID resolutionId, Actor approver, CorrelationId correlation);

    /** No corridor - every parked line an ordinary transfer (a composition without cross-border payments). */
    ResolvedCorridorReturns NONE = new ResolvedCorridorReturns() {
        @Override
        public Judgement judge(Connection unitOfWork, ParkedReturn parked) {
            return Judgement.NOT_A_CORRIDOR_RETURN;
        }

        @Override
        public Judgement record(
                Connection unitOfWork, ParkedReturn parked, UUID resolutionId, Actor approver, CorrelationId correlation) {
            return Judgement.NOT_A_CORRIDOR_RETURN;
        }
    };
}
