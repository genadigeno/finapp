package com.finapp.app.payments;

import com.finapp.accounts.CustomerAccount;
import com.finapp.accounts.CustomerAccountStore;
import com.finapp.accounts.ProductType;
import com.finapp.identity.IdentityErrorCode;
import com.finapp.identity.IdentityId;
import com.finapp.identity.MfaEnrolmentStore;
import com.finapp.identity.MfaFactorType;
import com.finapp.payments.PaymentParticipants;
import com.finapp.payments.Withdrawals;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotencyRecordStore;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The withdrawal door's step-up check, before the command (`P13-TSK-009`; {@code PHASE_13_PLAN.md} section 12.3;
 * ADR-0108 decision 3) - the transfer door's ({@code TransferDoorStepUp}) rules at the outbound door: a proposed
 * withdrawal the acting session cannot satisfy a challenge for is answered {@code 403 identity.AssuranceRequired}
 * <strong>before any row exists</strong>, so the customer steps up and retries the same key instead of reading a
 * committed {@code FAILED(RISK_REFUSED)}. Run by {@link WithdrawalService} in the door's own short transaction, which
 * writes nothing (and is not {@code READ ONLY}: the advice's pin is {@code FOR SHARE}).
 *
 * <p><strong>Three answers it must not give.</strong>
 *
 * <ul>
 *   <li><strong>A retry is never advised.</strong> When the command's key ({@code payments.withdrawal:<customer>}) has a
 *       record, the command replays its judged status or answers the conflict; advising again would read windows that
 *       now include the original's own count and could turn the retry of a dispatched withdrawal into a {@code 403} -
 *       a different answer for the same key ({@code INV-IDEM-01}).</li>
 *   <li><strong>No advice over somebody else's money.</strong> The wallet and the instrument must resolve as the
 *       caller's own (the command's own resolutions) before risk is asked anything; otherwise the advice is skipped and
 *       the command answers its own {@code 422} as it always has - the advice is no oracle.</li>
 *   <li><strong>Nothing of the judgement.</strong> The port answers a boolean; the refusal is identity's step-up code
 *       with identity's words - which factor to use, never why ({@code INV-RSK-08}).</li>
 * </ul>
 */
final class WithdrawalDoorStepUp {

    /** What the door answers before the command. */
    enum Answer {
        /** No step-up needed, or not this door's to advise: the command runs. */
        PROCEED,
        /** A challenge the session does not satisfy, and the identity holds a factor to step up with. */
        STEP_UP,
        /** A challenge the session does not satisfy, and the identity holds no factor yet. */
        ENROL_AND_STEP_UP
    }

    private final WithdrawalStepUpAdvice advice;
    private final IdempotencyRecordStore<Connection> records;
    private final PaymentParticipants<Connection> participants;
    private final CustomerAccountStore<Connection> products;
    private final MfaEnrolmentStore<Connection> enrolments;

    WithdrawalDoorStepUp(
            WithdrawalStepUpAdvice advice,
            IdempotencyRecordStore<Connection> records,
            PaymentParticipants<Connection> participants,
            CustomerAccountStore<Connection> products,
            MfaEnrolmentStore<Connection> enrolments) {
        this.advice = Objects.requireNonNull(advice, "advice");
        this.records = Objects.requireNonNull(records, "records");
        this.participants = Objects.requireNonNull(participants, "participants");
        this.products = Objects.requireNonNull(products, "products");
        this.enrolments = Objects.requireNonNull(enrolments, "enrolments");
    }

    /**
     * The door's answer for the proposed withdrawal, on the door's own {@code unitOfWork}: the caller's wallet in the
     * amount's currency and the caller's instrument resolved first, a recorded key never advised. Writes nothing.
     */
    Answer advise(
            Connection unitOfWork,
            UUID party,
            IdentityId identity,
            UUID paymentMethodId,
            Money amount,
            String idempotencyKey) {
        Optional<PaymentParticipants.Wallet> wallet =
                participants.walletOwnedBy(unitOfWork, party, amount.currency());
        if (wallet.isEmpty()) {
            return Answer.PROCEED;
        }
        if (records.find(
                        unitOfWork,
                        new IdempotencyKey(
                                Withdrawals.claimScopeOf(wallet.get().customerId()), idempotencyKey))
                .isPresent()) {
            return Answer.PROCEED;
        }
        if (participants.bankDestinationOwnedBy(unitOfWork, party, paymentMethodId).isEmpty()) {
            return Answer.PROCEED;
        }
        // The wallet's customer account - the product the money leaves, the ledger's owner_ref:
        // the SOURCE_ACCOUNT subject the command's judgement will count.
        Optional<UUID> source =
                products.findLive(unitOfWork, wallet.get().customerId(), ProductType.WALLET)
                        .map(CustomerAccount::id)
                        .map(id -> id.value());
        if (source.isEmpty()) {
            return Answer.PROCEED;
        }
        if (!advice.stepUpRequired(
                unitOfWork,
                new WithdrawalStepUpAdvice.Proposed(party, identity.value(), source.get(), amount))) {
            return Answer.PROCEED;
        }
        return enrolments.findActive(unitOfWork, identity, MfaFactorType.TOTP).isPresent()
                ? Answer.STEP_UP
                : Answer.ENROL_AND_STEP_UP;
    }

    /** The {@code 403} for a step-up answer - identity's code and words, nothing of the judgement. */
    static ApiException refusal(Answer answer) {
        if (answer == Answer.PROCEED) {
            throw new IllegalArgumentException("PROCEED is not a refusal");
        }
        return new ApiException(
                IdentityErrorCode.ASSURANCE_REQUIRED,
                "A withdrawal was answered with a step-up before the command: the session is below"
                        + " MULTI_FACTOR",
                answer == Answer.STEP_UP
                        ? "this withdrawal requires a second factor: step up with your enrolled factor"
                                + " and retry."
                        : "this withdrawal requires a second factor: enrol one, step up with it, and"
                                + " retry.");
    }
}
