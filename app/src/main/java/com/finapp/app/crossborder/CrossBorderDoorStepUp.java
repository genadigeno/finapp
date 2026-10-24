package com.finapp.app.crossborder;

import com.finapp.identity.IdentityErrorCode;
import com.finapp.identity.IdentityId;
import com.finapp.identity.MfaEnrolmentStore;
import com.finapp.identity.MfaFactorType;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotencyRecordStore;
import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The cross-border payment door's step-up check, before the claim (`P13-TSK-011`; {@code PHASE_13_PLAN.md} section 12.3;
 * ADR-0108 decision 3) - the transfer's, withdrawal's and checkout's rules at the corridor's door: a proposed payment the
 * acting session cannot satisfy a challenge for is answered {@code 403 identity.AssuranceRequired} <strong>before any row
 * exists</strong> - no claim of the key, no quote accepted, no hold, no assessment - so the customer steps up and retries
 * <strong>the same key</strong> instead of reading a refused payment. Run by {@link CrossBorderPaymentDesk} in the door's
 * own short transaction, which writes nothing (and is not {@code READ ONLY}: the advice's pin is {@code FOR SHARE}).
 *
 * <p><strong>Four answers it must not give.</strong>
 *
 * <ul>
 *   <li><strong>A retry is never advised.</strong> When the door's key ({@code crossborder.payment:<actor>}) has a
 *       record, the claim replays its response of record; advising again could turn the retry of an authorised payment
 *       into a {@code 403} - a different answer for the same key ({@code INV-IDEM-01}).</li>
 *   <li><strong>A judged movement is never advised.</strong> A quote risk has already sealed (a payment authorised, or
 *       refused, under another key) is answered by its seal when the authorization runs; the advice port answers
 *       {@code false} for it, so the customer is never told to step up for a decision already taken.</li>
 *   <li><strong>No advice over somebody else's offer, or without the caller's wallet.</strong> The quote's offer must be
 *       the caller's and the caller must hold a wallet in its source currency; otherwise the advice is skipped and the
 *       authorization answers its own refusal as it always has - the advice is no oracle.</li>
 *   <li><strong>Nothing of the judgement.</strong> The port answers a boolean; the refusal is identity's step-up code
 *       with identity's words - which factor to use, never why ({@code INV-RSK-08}).</li>
 * </ul>
 */
final class CrossBorderDoorStepUp {

    /** What the door answers before the claim. */
    enum Answer {
        /** No step-up needed, or not this door's to advise: the claim and the authorization run. */
        PROCEED,
        /** A challenge the session does not satisfy, and the identity holds a factor to step up with. */
        STEP_UP,
        /** A challenge the session does not satisfy, and the identity holds no factor yet. */
        ENROL_AND_STEP_UP
    }

    private final CrossBorderStepUpAdvice advice;
    private final IdempotencyRecordStore<Connection> records;
    private final CrossBorderMovements movements;
    private final MfaEnrolmentStore<Connection> enrolments;

    CrossBorderDoorStepUp(
            CrossBorderStepUpAdvice advice,
            IdempotencyRecordStore<Connection> records,
            CrossBorderMovements movements,
            MfaEnrolmentStore<Connection> enrolments) {
        this.advice = Objects.requireNonNull(advice, "advice");
        this.records = Objects.requireNonNull(records, "records");
        this.movements = Objects.requireNonNull(movements, "movements");
        this.enrolments = Objects.requireNonNull(enrolments, "enrolments");
    }

    /**
     * The door's answer for the proposed payment, on the door's own {@code unitOfWork}: a recorded key never advised, the
     * caller's offer and wallet resolved first. Writes nothing.
     */
    Answer advise(Connection unitOfWork, UUID party, IdentityId identity, UUID quote, IdempotencyKey key) {
        if (records.find(unitOfWork, key).isPresent()) {
            return Answer.PROCEED;
        }
        Optional<CrossBorderMovements.Movement> proposed = movements.owned(unitOfWork, party, quote);
        if (proposed.isEmpty() || proposed.get().sourceAccount().isEmpty()) {
            return Answer.PROCEED;
        }
        if (!advice.stepUpRequired(unitOfWork, proposed.get())) {
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
                "A cross-border payment was answered with a step-up before the claim: the session is below"
                        + " MULTI_FACTOR",
                answer == Answer.STEP_UP
                        ? "this payment requires a second factor: step up with your enrolled factor and retry."
                        : "this payment requires a second factor: enrol one, step up with it, and retry.");
    }
}
