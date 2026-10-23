package com.finapp.app.transfers;

import com.finapp.identity.IdentityErrorCode;
import com.finapp.identity.IdentityId;
import com.finapp.identity.MfaEnrolmentStore;
import com.finapp.identity.MfaFactorType;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotencyRecordStore;
import com.finapp.sharedkernel.money.Money;
import com.finapp.transfers.TransferExecution;
import com.finapp.transfers.TransferParticipants;
import java.sql.Connection;
import java.util.Objects;
import java.util.UUID;

/**
 * The transfer door's step-up check, before the command (`P13-TSK-008`; {@code PHASE_13_PLAN.md} section 12.3;
 * ADR-0108 decision 3): a proposed transfer the acting session cannot satisfy a challenge for is answered
 * {@code 403 identity.AssuranceRequired} <strong>before any row exists</strong>, so the customer steps up and retries
 * instead of reading a committed refusal. Run by {@link TransferService} in the door's own short transaction, which
 * writes nothing.
 *
 * <p><strong>Three answers it must not give.</strong>
 *
 * <ul>
 *   <li><strong>A retry is never advised.</strong> When the command's key ({@code transfer.execute}) already has a
 *       record, the command will replay its original judgement or answer the conflict; advising again would read
 *       windows that now include the original's own count and could turn the retry of a {@code COMPLETED} transfer
 *       into a {@code 403} - a different answer for the same key ({@code INV-IDEM-01}). A claim still in flight is not
 *       visible and the advice runs; the command's in-lock judgement is authoritative either way.</li>
 *   <li><strong>No advice over somebody else's account.</strong> The source must resolve as the caller's own
 *       ({@code TransferParticipants.sourceOwnedBy}, the command's own resolution) before risk is asked anything; an
 *       unowned or unknown source skips the advice, and the command answers {@code transfers.UnknownSource} as it
 *       always has - so the advice is no oracle over another account's windows.</li>
 *   <li><strong>Nothing of the judgement.</strong> The port answers a boolean; the refusal is identity's step-up code
 *       with identity's step-up words - which factor to use, never why ({@code INV-RSK-08}).</li>
 * </ul>
 *
 * <p>The detail follows the always-{@code MULTI_FACTOR} doors' (the credit request desk's): a customer with an active
 * factor steps up with it; one without must enrol one first.
 */
final class TransferDoorStepUp {

    /** What the door answers before the command. */
    enum Answer {
        /** No step-up needed, or not this door's to advise: the command runs. */
        PROCEED,
        /** A challenge the session does not satisfy, and the identity holds a factor to step up with. */
        STEP_UP,
        /** A challenge the session does not satisfy, and the identity holds no factor yet. */
        ENROL_AND_STEP_UP
    }

    private final TransferStepUpAdvice advice;
    private final IdempotencyRecordStore<Connection> records;
    private final TransferParticipants<Connection> participants;
    private final MfaEnrolmentStore<Connection> enrolments;

    TransferDoorStepUp(TransferStepUpAdvice advice, IdempotencyRecordStore<Connection> records,
            TransferParticipants<Connection> participants, MfaEnrolmentStore<Connection> enrolments) {
        this.advice = Objects.requireNonNull(advice, "advice");
        this.records = Objects.requireNonNull(records, "records");
        this.participants = Objects.requireNonNull(participants, "participants");
        this.enrolments = Objects.requireNonNull(enrolments, "enrolments");
    }

    /** True when {@code idempotencyKey} already names a transfer command - a retry, never advised. */
    boolean retried(Connection unitOfWork, String idempotencyKey) {
        return records.find(unitOfWork, new IdempotencyKey(TransferExecution.IDEMPOTENCY_SCOPE, idempotencyKey))
                .isPresent();
    }

    /** The door's answer for the proposed transfer, on the door's own {@code unitOfWork}. Writes nothing. */
    Answer advise(Connection unitOfWork, UUID party, IdentityId identity, UUID sourceRef, UUID destinationRef,
            Money amount) {
        if (participants.sourceOwnedBy(unitOfWork, party, sourceRef, amount.currency()).isEmpty()) {
            return Answer.PROCEED;
        }
        if (!advice.stepUpRequired(unitOfWork, new TransferStepUpAdvice.Proposed(party, identity.value(), sourceRef,
                destinationRef, amount))) {
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
        return new ApiException(IdentityErrorCode.ASSURANCE_REQUIRED,
                "A transfer was answered with a step-up before the command: the session is below MULTI_FACTOR",
                answer == Answer.STEP_UP
                        ? "this transfer requires a second factor: step up with your enrolled factor and retry."
                        : "this transfer requires a second factor: enrol one, step up with it, and retry.");
    }
}
